//! Your year's format (11/10/2026; docs/record.md): the record key and what's made from it, a chunk sealed and opened,
//! and the record's lines and days. Nothing here touches a file, the network or Windows (record.rs does), so it's the
//! part the page has to match, and its tests check it against WebCrypto's vectors (tests/fixtures/record/vectors.json).
//!
//! The record key: 32 random bytes. Made from it with HMAC-SHA256 (no one key doing two jobs): the seal key (AES-256-GCM),
//! the read token (reading the relay's copy), the write token (the public summary) and each chunk's id. A sealed chunk:
//! the format (1), a 12-byte random nonce, then AES-256-GCM's ciphertext with its 16-byte tag appended, the chunk's id as
//! additional data; what's sealed is the chunk's lines as raw DEFLATE. ring does the cryptography (it's in the build
//! already, for TLS) and flate2 the compressing (pure Rust; in the build already, for PNG).

use base64::Engine;
use ring::{aead, hmac};
use serde_json::Value;
use std::io::{Read, Write};

/// the record key as text: this, then the 32 bytes as base64url without padding
pub const KEY_PREFIX: &str = "record1:";
/// a sealed chunk's format byte
pub const FORMAT: u8 = 1;
/// lines in one part of a day's chunk at most (a day of play is a few hundred)
pub const PART_LINES: usize = 4000;

pub type Key = [u8; 32];

fn b64u(b: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

/// A new record key, from the system's random numbers.
pub fn new_key() -> Key {
    use ring::rand::SecureRandom;
    let mut k = [0u8; 32];
    ring::rand::SystemRandom::new().fill(&mut k).expect("the system's random numbers");
    k
}

/// The key as it's copied, pasted and kept in the settings: "record1:" and base64url.
pub fn key_text(k: &Key) -> String {
    format!("{}{}", KEY_PREFIX, b64u(k))
}

/// The key as it goes in the year view's address (#k=…): base64url alone.
pub fn key_b64u(k: &Key) -> String {
    b64u(k)
}

/// A pasted key ("record1:…", or the 43 characters alone), or why not.
pub fn parse_key(text: &str) -> Result<Key, String> {
    let t = text.trim();
    let raw = t.strip_prefix(KEY_PREFIX).unwrap_or(t);
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(raw.trim_end_matches('=').as_bytes())
        .map_err(|_| format!("that isn't a record key (it starts {})", KEY_PREFIX))?;
    bytes.try_into().map_err(|_| "that record key is cut short or changed".to_string())
}

fn mac(k: &Key, what: &str) -> [u8; 32] {
    let tag = hmac::sign(&hmac::Key::new(hmac::HMAC_SHA256, k), what.as_bytes());
    tag.as_ref().try_into().expect("SHA-256 is 32 bytes")
}

/// The AES-256-GCM key: HMAC(k, "outstation record seal v1").
pub fn seal_key(k: &Key) -> [u8; 32] {
    mac(k, "outstation record seal v1")
}

/// What reads the relay's copy: base64url(HMAC(k, "outstation record read v1")). The relay keeps only its SHA-256.
pub fn read_token(k: &Key) -> String {
    b64u(&mac(k, "outstation record read v1"))
}

/// What posts or takes down the public summary: base64url(HMAC(k, "outstation record write v1")).
pub fn write_token(k: &Key) -> String {
    b64u(&mac(k, "outstation record write v1"))
}

/// A chunk's name on the relay: the first 16 bytes of HMAC(k, "outstation record chunk v1\n<device>\n<day>\n<part>"),
/// base64url (22 characters). It says nothing of the device or the day to anyone without the key.
pub fn chunk_id(k: &Key, device: &str, day: &str, part: usize) -> String {
    b64u(&mac(k, &format!("outstation record chunk v1\n{}\n{}\n{}", device, day, part))[..16])
}

fn aead_key(k: &Key) -> aead::LessSafeKey {
    aead::LessSafeKey::new(aead::UnboundKey::new(&aead::AES_256_GCM, &seal_key(k)).expect("a 32-byte key"))
}

/// Raw DEFLATE (no zlib or gzip header): the browser's DecompressionStream("deflate-raw").
pub fn deflate(data: &[u8]) -> Vec<u8> {
    let mut e = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
    e.write_all(data).expect("into memory");
    e.finish().expect("into memory")
}

#[cfg_attr(not(test), allow(dead_code))] // (opening is the page's job; the app's tests check it)
pub fn inflate(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    flate2::read::DeflateDecoder::new(data).take(64 << 20).read_to_end(&mut out).map_err(|e| format!("not deflated ({})", e))?;
    Ok(out)
}

/// Already-compressed bytes sealed with this nonce: [1] + nonce + ciphertext + tag. (seal() makes the nonce; this is
/// apart so the tests can give WebCrypto's vector its own.)
pub fn seal_with(k: &Key, id: &str, compressed: &[u8], nonce: [u8; 12]) -> Vec<u8> {
    let mut body = compressed.to_vec();
    aead_key(k).seal_in_place_append_tag(aead::Nonce::assume_unique_for_key(nonce), aead::Aad::from(id.as_bytes()), &mut body)
        .expect("AES-GCM seals anything this small");
    let mut out = Vec::with_capacity(13 + body.len());
    out.push(FORMAT);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&body);
    out
}

/// A chunk's plaintext compressed and sealed under its id, with a new random nonce.
pub fn seal(k: &Key, id: &str, plaintext: &[u8]) -> Vec<u8> {
    use ring::rand::SecureRandom;
    let mut nonce = [0u8; 12];
    ring::rand::SystemRandom::new().fill(&mut nonce).expect("the system's random numbers");
    seal_with(k, id, &deflate(plaintext), nonce)
}

/// A sealed chunk opened and inflated: its plaintext, or why not (another key, another id, changed or cut short).
#[cfg_attr(not(test), allow(dead_code))] // (opening is the page's job; the app's tests check it)
pub fn open(k: &Key, id: &str, sealed: &[u8]) -> Result<Vec<u8>, String> {
    if sealed.len() < 1 + 12 + 16 || sealed[0] != FORMAT {
        return Err("not a sealed chunk".into());
    }
    let nonce: [u8; 12] = sealed[1..13].try_into().expect("12 bytes");
    let mut body = sealed[13..].to_vec();
    let plain = aead_key(k).open_in_place(aead::Nonce::assume_unique_for_key(nonce), aead::Aad::from(id.as_bytes()), &mut body)
        .map_err(|_| "it won't open: another record key, or changed".to_string())?;
    inflate(plain)
}

// ---- the lines ----

/// JSON as relay.py and the relays write it (", " and ": "), so the record reads the same wherever it's looked at.
pub fn pyjson(v: &Value) -> String {
    match v {
        Value::Array(a) => format!("[{}]", a.iter().map(pyjson).collect::<Vec<_>>().join(", ")),
        Value::Object(o) => format!("{{{}}}", o.iter().map(|(k, x)| format!("{}: {}", Value::String(k.clone()), pyjson(x))).collect::<Vec<_>>().join(", ")),
        _ => v.to_string(),
    }
}

/// One line of the record: {"t": ms, then each field in the order given}.
pub fn line(t: i64, fields: &[(&str, &Value)]) -> String {
    let mut s = format!("{{\"t\": {}", t);
    for (k, v) in fields {
        s.push_str(&format!(", \"{}\": {}", k, pyjson(v)));
    }
    s.push('}');
    s
}

/// A line's time (its "t"), or None for a line that isn't one of the record's.
pub fn line_t(l: &str) -> Option<i64> {
    serde_json::from_str::<Value>(l).ok()?.get("t")?.as_i64()
}

/// The UTC date of a time in ms: (year, month, day).
pub fn ymd(ms: i64) -> (i64, u32, u32) {
    // (Howard Hinnant's days-to-civil)
    let z = ms.div_euclid(86_400_000) + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (yoe + era * 400 + if m <= 2 { 1 } else { 0 }, m, d)
}

/// "2026-11-10": the day a time falls in (UTC), as chunks are named.
pub fn day_of(ms: i64) -> String {
    let (y, m, d) = ymd(ms);
    format!("{:04}-{:02}-{:02}", y, m, d)
}

/// "2026-11.jsonl": the month's file a time's line goes in (UTC).
pub fn month_file(ms: i64) -> String {
    let (y, m, _) = ymd(ms);
    format!("{:04}-{:02}.jsonl", y, m)
}

/// A day's plaintexts, one a part: a header line, then the lines as they are, each ending "\n".
pub fn day_parts(device: &str, day: &str, lines: &[String]) -> Vec<Vec<u8>> {
    let groups: Vec<&[String]> = if lines.is_empty() { vec![&lines[..]] } else { lines.chunks(PART_LINES).collect() };
    groups.iter().enumerate().map(|(part, ls)| {
        // (the header in the order docs/record.md gives it)
        let head = format!("{{\"record\": 1, \"device\": {}, \"day\": {}, \"part\": {}}}", Value::from(device), Value::from(day), part);
        let mut s = head;
        s.push('\n');
        for l in ls.iter() {
            s.push_str(l);
            s.push('\n');
        }
        s.into_bytes()
    }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const VECTORS: &str = include_str!("../../tests/fixtures/record/vectors.json");

    fn v() -> Value {
        serde_json::from_str(VECTORS).unwrap()
    }
    fn unhex(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }
    fn key() -> Key {
        unhex(v()["key_hex"].as_str().unwrap()).try_into().unwrap()
    }

    #[test]
    fn what_the_key_makes_matches_webcrypto() {
        let (v, k) = (v(), key());
        assert_eq!(key_text(&k), v["key_text"].as_str().unwrap());
        assert_eq!(parse_key(v["key_text"].as_str().unwrap()).unwrap(), k);
        assert_eq!(parse_key(v["key_b64u"].as_str().unwrap()).unwrap(), k);
        assert!(parse_key("record1:short").is_err());
        assert!(parse_key("hello there").is_err());
        assert_eq!(seal_key(&k).to_vec(), unhex(v["seal_key_hex"].as_str().unwrap()));
        assert_eq!(read_token(&k), v["read_token"].as_str().unwrap());
        assert_eq!(write_token(&k), v["write_token"].as_str().unwrap());
        assert_eq!(chunk_id(&k, v["device"].as_str().unwrap(), v["day"].as_str().unwrap(), 0), v["chunk_id"].as_str().unwrap());
        assert_eq!(chunk_id(&k, "0123456789abcdef", "2026-11-10", 0).len(), 22);
    }

    #[test]
    fn a_seal_is_webcryptos_aes_gcm_byte_for_byte() {
        let (v, k) = (v(), key());
        let id = v["chunk_id"].as_str().unwrap();
        let nonce: [u8; 12] = unhex(v["nonce_hex"].as_str().unwrap()).try_into().unwrap();
        let sealed = seal_with(&k, id, &unhex(v["compressed_hex"].as_str().unwrap()), nonce);
        assert_eq!(sealed, unhex(v["sealed_hex"].as_str().unwrap()));
        // (and WebCrypto's opens here, to the plaintext, inflated)
        assert_eq!(String::from_utf8(open(&k, id, &sealed).unwrap()).unwrap(), v["plaintext"].as_str().unwrap());
    }

    #[test]
    fn a_chunk_round_trips_and_wont_open_wrongly() {
        let k = new_key();
        let id = chunk_id(&k, "abc", "2026-11-10", 0);
        let plain = day_parts("abc", "2026-11-10", &[line(5, &[("live", &Value::Bool(true))])]).remove(0);
        let a = seal(&k, &id, &plain);
        let b = seal(&k, &id, &plain);
        assert_ne!(a, b); // (a new nonce each time)
        assert_eq!((a[0], open(&k, &id, &a).unwrap()), (1, plain.clone()));
        assert!(open(&k, &chunk_id(&k, "abc", "2026-11-11", 0), &a).is_err()); // (under another name)
        assert!(open(&new_key(), &id, &a).is_err()); // (another key)
        let mut changed = a.clone();
        *changed.last_mut().unwrap() ^= 1;
        assert!(open(&k, &id, &changed).is_err());
        assert!(open(&k, &id, &a[..20]).is_err());
    }

    #[test]
    fn lines_days_and_parts() {
        let w = serde_json::json!({"x": 3264, "y": 3264, "world": 302});
        let a = serde_json::json!({"kind": "skilling", "skill": "Mining"});
        // (a where's own fields in whatever order serde_json keeps them: the relays' is x, y, world)
        assert_eq!(line(1794268872400, &[("where", &w), ("activity", &a)]),
                   format!(r#"{{"t": 1794268872400, "where": {}, "activity": {{"kind": "skilling", "skill": "Mining"}}}}"#, pyjson(&w)));
        assert_eq!(line(7, &[("where", &Value::Null)]), r#"{"t": 7, "where": null}"#);
        assert_eq!(line_t(r#"{"t": 1794268800000, "live": true}"#), Some(1794268800000));
        assert_eq!(line_t("not json"), None);
        assert_eq!(day_of(1794268800000), "2026-11-10");
        assert_eq!(day_of(1794268800000 - 1), "2026-11-09");
        assert_eq!(day_of(0), "1970-01-01");
        assert_eq!(day_of(951_782_400_000), "2000-02-29");
        assert_eq!(month_file(1794268800000), "2026-11.jsonl");
        // (the vectors' plaintext is a day's part as day_parts makes it)
        let v = v();
        let plain = v["plaintext"].as_str().unwrap();
        let lines: Vec<String> = plain.lines().skip(1).map(|l| l.to_string()).collect();
        let parts = day_parts("0123456789abcdef", "2026-11-10", &lines);
        assert_eq!(String::from_utf8(parts[0].clone()).unwrap(), plain);
        let many: Vec<String> = (0..PART_LINES + 1).map(|i| line(i as i64, &[("live", &Value::Bool(true))])).collect();
        let parts = day_parts("d", "2026-11-10", &many);
        assert_eq!(parts.len(), 2);
        assert!(String::from_utf8(parts[1].clone()).unwrap().starts_with(r#"{"record": 1, "device": "d", "day": "2026-11-10", "part": 1}"#));
    }
}
