//! Dink's posts, taken on this PC, for the clips that save themselves.
//!
//! Dink (RuneLite's notifier) posts here as it would to a Discord webhook: http://127.0.0.1:<port>/dink. Nothing
//! listens beyond this PC. A post of something big (a pet, a new collection log slot, a 99, a drop worth enough) saves
//! the tape as a clip a few seconds on, and, with their switches, puts a copy on the Outside page and sends it to
//! Discord (the Dink drawer's webhook). Nothing else of the post goes anywhere.
//!
//! (The Dink trim, 11/17/2026, Jace: the Outside plugin's "Copy my address for Dink" points Dink straight at the page, so
//! the outstation no longer passes Dink's posts on: not to the relay (two copies of every field report, and the map
//! region it took out let the relay's Wilderness check miss), and not to Discord (Dink keeps its own Discord address).)

use crate::settings::Shared;
use serde_json::Value;
use std::io::Read;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const MAX_POST: usize = 16 * 1024 * 1024;
const USER_AGENT: &str = "DiscordBot (https://ghillie.wiki, 1)";

#[derive(Clone, Default)]
pub struct Status {
    pub listening: Option<String>,
    pub error: Option<String>,
    /// the last post: its kind, when, and where it went
    pub last: Option<(String, Instant, String)>,
}

pub fn start(settings: Shared) -> Arc<Mutex<Status>> {
    let status = Arc::new(Mutex::new(Status::default()));
    let st = status.clone();
    std::thread::spawn(move || loop {
        let port = settings.get().dink.port;
        match tiny_http::Server::http(("127.0.0.1", port)) {
            Ok(server) => {
                {
                    let mut s = st.lock().unwrap();
                    s.listening = Some(format!("http://127.0.0.1:{}/dink", port));
                    s.error = None;
                }
                loop {
                    match server.recv_timeout(Duration::from_millis(500)) {
                        Ok(Some(req)) => handle(req, &settings, &st),
                        Ok(None) => {}
                        Err(_) => break,
                    }
                    if settings.get().dink.port != port {
                        break; // (a new port: listen there instead)
                    }
                }
            }
            Err(e) => {
                let mut s = st.lock().unwrap();
                s.listening = None;
                s.error = Some(format!("port {} is taken ({})", port, e));
                drop(s);
                std::thread::sleep(Duration::from_secs(5));
            }
        }
    });
    status
}

fn handle(mut req: tiny_http::Request, settings: &Shared, st: &Arc<Mutex<Status>>) {
    if *req.method() != tiny_http::Method::Post {
        let _ = req.respond(tiny_http::Response::empty(405));
        return;
    }
    let content_type = req.headers().iter().find(|h| h.field.equiv("Content-Type")).map(|h| h.value.to_string()).unwrap_or_default();
    let mut body = Vec::new();
    if req.as_reader().take(MAX_POST as u64 + 1).read_to_end(&mut body).is_err() || body.len() > MAX_POST {
        let _ = req.respond(tiny_http::Response::empty(413));
        return;
    }
    let _ = req.respond(tiny_http::Response::empty(204));
    let Some(payload) = payload(&content_type, &body) else { return };
    // (Dink posts each event once to each address it lists: listed twice, every event came twice. The same post
    // again within 10 s is one event)
    {
        static SEEN: std::sync::OnceLock<Mutex<Vec<(String, Instant)>>> = std::sync::OnceLock::new();
        let mut seen = SEEN.get_or_init(|| Mutex::new(Vec::new())).lock().unwrap();
        seen.retain(|(_, t)| t.elapsed() < Duration::from_secs(10));
        let key = payload.to_string();
        if seen.iter().any(|(k, _)| *k == key) {
            crate::transmit::log("dink: the same post again: dropped (is this address listed twice in Dink?)");
            return;
        }
        seen.push((key, Instant::now()));
    }
    let s = settings.get();
    let kind = payload.get("type").and_then(|x| x.as_str()).unwrap_or("").to_string();
    let mut went = Vec::new();
    // (where the clip goes in Discord, if one saves itself: the Dink drawer's webhook)
    let webhook = s.clip_webhook();
    // something big (11/06/2026, Jace: "an auto clip when something big drops"): the replay buffer saved a few seconds
    // on, so the clip holds the moment and a little after it
    // (while transmitting, or dark with the tape rolling: dark but recording, 11/07/2026)
    let rolling = (s.on_air && s.attached()) || s.taping_dark();
    if s.dink.auto_clip && rolling && s.replay_seconds > 0 {
        // (one moment, one clip, 11/10/2026: a pet comes with its new collection log slot, and Dink posts both at once;
        // each had made its own copy, so Jace's Baby mole went to his page and to Discord twice. A big moment within
        // MERGE of one still waiting to be written joins it, and its words join the clip's name)
        let mut joined = false;
        let big = big_moment(&payload, s.dink.clip_gp).filter(|w| {
            let starts = moment_starts(w);
            joined = !starts;
            starts
        });
        if joined {
            went.push("the clip already being made");
        }
        if let Some(what) = big {
            // (to the Outside page only while transmitting: a clip from while dark goes to this PC and Discord only)
            let up = s.dink.clips_up && s.on_air && s.attached();
            let to_discord = s.dink.clips_discord && !webhook.is_empty();
            went.push(match (up, to_discord) {
                (true, true) => "a clip, on your Outside page and in Discord too",
                (true, false) => "a clip, on your Outside page too",
                (false, true) => "a clip, in Discord too",
                _ => "a clip",
            });
            // (a standard copy, for the page or Discord: it runs to 5 s after the moment, so it waits that long, if the
            // buffer holds that much)
            let copy = up || to_discord;
            let secs = if copy { (crate::clip::AFTER / 1000).min((s.replay_seconds / 3).max(1) as u64) } else { (s.replay_seconds / 4).clamp(1, 4) as u64 };
            let (folder, wait, moment, settings) = (s.clip_folder.clone(), Duration::from_secs(secs), Instant::now(), settings.clone());
            std::thread::spawn(move || {
                std::thread::sleep(wait);
                let what = moment_ends().unwrap_or(what);
                crate::clip::save_as(&folder, Some(what.clone()));
                if !copy {
                    return;
                }
                // (made once, for both: the page's and Discord's copy are the same)
                let items = crate::clip::snapshot();
                let page = match crate::clip::page_copy(&items, moment) {
                    Ok(p) => p,
                    Err(e) => return crate::clip::say(format!("no copy of the clip for {}: {}",
                                                              if up { "your Outside page" } else { "Discord" }, e)),
                };
                if up {
                    let s = settings.get();
                    match crate::clipsup::upload_copy(&s, &what, &kind_of(&what), moment, &page) {
                        Ok(said) => crate::clip::say(said),
                        Err(e) => crate::clip::say(format!("the clip didn't reach your Outside page: {}", e)),
                    }
                }
                if to_discord {
                    let (body, ct) = discord_clip(&what, &page.mp4);
                    // (never the address itself in the log: it's the key to the server)
                    crate::clip::say(match post_discord(&webhook, &body, &ct) {
                        None => format!("in Discord: {} ({:.1} MB)", what, page.mp4.len() as f64 / 1048576.0),
                        Some(why) => { crate::transmit::log(&format!("the clip didn't reach Discord: {}", why)); format!("the clip didn't reach Discord: {}", why) }
                    });
                }
            });
        }
    }
    st.lock().unwrap().last = Some((kind, Instant::now(), if went.is_empty() { "nowhere".into() } else { went.join(" and ") }));
}

/// How close two big moments must be to be one clip (Dink posts a pet and its log slot within a second or two).
const MERGE: Duration = Duration::from_secs(15);

/// The moment whose clip is waiting to be written: when it began and its words so far.
static PENDING: Mutex<Option<(Instant, String)>> = Mutex::new(None);

/// A big moment: true when it starts a clip of its own; false when it joins the one already waiting (its words added).
fn moment_starts(what: &str) -> bool {
    let mut p = PENDING.lock().unwrap();
    match p.as_mut() {
        Some((at, words)) if at.elapsed() < MERGE => {
            *words = join_words(words, what);
            false
        }
        _ => {
            *p = Some((Instant::now(), what.to_string()));
            true
        }
    }
}

/// The waiting moment's words, as the clip's name, and no moment waiting any more.
fn moment_ends() -> Option<String> {
    PENDING.lock().unwrap().take().map(|(_, w)| w)
}

/// Two moments' words as one name: a pet first (the clip's kind follows its first words); a log slot for the same thing
/// as the pet (the pet's own slot) says nothing more.
fn join_words(a: &str, b: &str) -> String {
    let (first, second) = if b.starts_with("pet ") && !a.starts_with("pet ") { (b, a) } else { (a, b) };
    let same = |x: &str, y: &str| x.splitn(2, ' ').nth(1) == y.splitn(2, ' ').nth(1);
    if second.starts_with("log ") && same(first, second) { return first.to_string(); }
    if first.split(" · ").any(|w| w == second) { return first.to_string(); }
    format!("{} · {}", first, second)
}

/// Is this post something big enough to clip by itself: a pet (a duplicate too: kept, 11/06/2026, Jace), a new
/// collection log slot, a 99 (11/06/2026, Jace: "add 99's"; a skill reaching 99, not a virtual level past it), or a
/// drop worth `gp` or more (the whole drop, every stack in it)? What it was, in a few words, for the clip's name.
pub fn big_moment(p: &Value, gp: u64) -> Option<String> {
    let e = extra(p);
    let text = |k: &str| e.get(k).and_then(|x| x.as_str()).map(|x| x.trim().to_lowercase()).filter(|x| !x.is_empty());
    match p.get("type").and_then(|x| x.as_str()).unwrap_or("") {
        "PET" => Some(format!("pet {}", text("petName").unwrap_or_else(|| "a pet".into()))),
        "COLLECTION" => Some(format!("log {}", text("itemName").unwrap_or_else(|| "a new slot".into()))),
        "LEVEL" => {
            let skills: Vec<String> = e.get("levelledSkills").and_then(|x| x.as_object()).map(|m| {
                m.iter().filter(|(_, v)| int(Some(v)) == 99).map(|(k, _)| k.trim().to_lowercase()).collect()
            }).unwrap_or_default();
            if skills.is_empty() { None } else { Some(format!("99 {}", skills.join(" and "))) }
        }
        "LOOT" => {
            let all = stacks(p);
            let worth: i64 = all.iter().map(|(_, v)| *v).sum();
            if gp == 0 || worth < gp as i64 { return None; }
            let best = all.iter().max_by_key(|(_, v)| *v).map(|(n, _)| n.clone()).unwrap_or_default();
            Some(format!("drop {} {}", gp_words(worth), best).trim().to_string())
        }
        _ => None,
    }
}

/// The clip as a Discord webhook post (multipart, as Dink's own are): a line naming it, in the outstation's words, and
/// the video, which Discord plays in the channel. Its body and Content-Type.
pub fn discord_clip(what: &str, mp4: &[u8]) -> (Vec<u8>, String) {
    let b = "outstationclipfordiscord";
    let payload = serde_json::json!({ "content": format!("✼ recovered footage: {}", what) }).to_string();
    let name = format!("{}.mp4", { let n = crate::clip::file_safe(what).replace(' ', "-"); if n.is_empty() { "clip".to_string() } else { n } });
    let mut out = Vec::with_capacity(mp4.len() + payload.len() + 512);
    out.extend_from_slice(format!("--{}\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n", b).as_bytes());
    out.extend_from_slice(payload.as_bytes());
    out.extend_from_slice(format!("\r\n--{}\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"{}\"\r\nContent-Type: video/mp4\r\n\r\n", b, name).as_bytes());
    out.extend_from_slice(mp4);
    out.extend_from_slice(format!("\r\n--{}--\r\n", b).as_bytes());
    (out, format!("multipart/form-data; boundary={}", b))
}

/// A big moment's kind, as Dink names it, from its words (big_moment's).
fn kind_of(what: &str) -> String {
    if what.starts_with("pet ") { "PET" } else if what.starts_with("log ") { "COLLECTION" } else if what.starts_with("99 ") { "LEVEL" } else { "LOOT" }.to_string()
}

/// 4200000 as "4.2m" (the page's own way: gpWords in outside-portal.js)
pub fn gp_words(n: i64) -> String {
    let f = n as f64;
    if f >= 1e9 { format!("{:.2}b", f / 1e9) } else if f >= 1e6 { format!("{:.1}m", f / 1e6) } else if f >= 1e3 { format!("{}k", (f / 1e3).round()) } else { n.to_string() }
}

/// Dink's JSON: the payload_json part of a multipart post, or a plain JSON body.
pub fn payload(content_type: &str, body: &[u8]) -> Option<Value> {
    if content_type.to_lowercase().starts_with("multipart/") {
        let boundary = content_type.split(';').find_map(|p| p.trim().strip_prefix("boundary="))?.trim_matches('"');
        let marker = format!("--{}", boundary);
        let text = body;
        let mut at = 0;
        while let Some(i) = find(&text[at..], marker.as_bytes()) {
            let start = at + i + marker.len();
            let end = find(&text[start..], marker.as_bytes()).map(|j| start + j).unwrap_or(text.len());
            let part = &text[start..end];
            if let Some(h) = find(part, b"\r\n\r\n") {
                let head = String::from_utf8_lossy(&part[..h]).to_lowercase();
                if head.contains("name=\"payload_json\"") {
                    let mut data = &part[h + 4..];
                    while data.ends_with(b"\r\n") || data.ends_with(b"\n") { data = &data[..data.len() - 1]; }
                    return serde_json::from_slice(data).ok();
                }
            }
            at = end;
            if end >= text.len() { break; }
        }
        return None;
    }
    serde_json::from_slice(body).ok()
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

fn extra(p: &Value) -> &serde_json::Map<String, Value> {
    static EMPTY: std::sync::OnceLock<serde_json::Map<String, Value>> = std::sync::OnceLock::new();
    p.get("extra").and_then(|x| x.as_object()).unwrap_or_else(|| EMPTY.get_or_init(serde_json::Map::new))
}

fn int(v: Option<&Value>) -> i64 {
    match v {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        Some(Value::String(s)) => s.trim().parse().unwrap_or(0),
        _ => 0,
    }
}

fn stacks(p: &Value) -> Vec<(String, i64)> {
    extra(p).get("items").and_then(|x| x.as_array()).map(|a| {
        a.iter().filter_map(|i| i.as_object()).map(|i| {
            (i.get("name").and_then(|x| x.as_str()).unwrap_or("").to_lowercase(),
             int(i.get("quantity")).saturating_mul(int(i.get("priceEach"))))
        }).collect()
    }).unwrap_or_default()
}

fn post_discord(url: &str, body: &[u8], content_type: &str) -> Option<String> {
    let mut last = None;
    for _ in 0..3 {
        let r = ureq::post(url).header("Content-Type", content_type).header("User-Agent", USER_AGENT)
            .config().http_status_as_error(false).build().send(body);
        match r {
            Ok(resp) if resp.status().is_success() => return None,
            Ok(resp) if resp.status().as_u16() == 429 => {
                let wait = resp.headers().get("Retry-After").and_then(|v| v.to_str().ok()).and_then(|v| v.parse::<f64>().ok()).unwrap_or(1.0);
                std::thread::sleep(Duration::from_secs_f64(if wait.is_finite() { wait.clamp(0.0, 10.0) } else { 1.0 }));
                last = Some("rate limited".to_string());
            }
            Ok(resp) => return Some(format!("HTTP {}", resp.status().as_u16())),
            Err(e) => {
                last = Some(e.to_string());
                std::thread::sleep(Duration::from_secs(2));
            }
        }
    }
    last
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn loot(stacks: &[(&str, i64, i64)]) -> Value {
        json!({"type": "LOOT", "content": "loot", "extra": {"items": stacks.iter().map(|(n, q, p)| json!({"name": n, "quantity": q, "priceEach": p})).collect::<Vec<_>>(), "source": "Vorkath"}})
    }
    fn kc(count: i64, pb: bool, laps: bool) -> Value {
        json!({"type": "KILL_COUNT", "content": if laps { format!("has completed {} laps at X", count) } else { "kc".into() },
               "extra": {"boss": "X", "count": count, "isPersonalBest": pb}})
    }

    #[test]
    fn a_clip_goes_to_discord_as_dink_posts_do() {
        let (body, ct) = discord_clip("pet vorki", b"MP4BYTES");
        assert_eq!(ct, "multipart/form-data; boundary=outstationclipfordiscord");
        let text = String::from_utf8_lossy(&body);
        assert!(text.contains("name=\"payload_json\""));
        assert!(text.contains("recovered footage: pet vorki"));
        assert!(text.contains("name=\"files[0]\"; filename=\"pet-vorki.mp4\""));
        assert!(text.contains("Content-Type: video/mp4\r\n\r\nMP4BYTES\r\n--outstationclipfordiscord--"));
        // (the same parser that reads Dink's own multipart posts finds its line)
        assert!(payload(&ct, &body).unwrap()["content"].as_str().unwrap().contains("pet vorki"));
    }

    #[test]
    fn a_pet_and_its_log_slot_are_one_clip() {
        // (11/10/2026: Baby mole came as a pet and a log slot together, and went twice)
        assert_eq!(join_words("pet baby mole", "log baby mole"), "pet baby mole");
        assert_eq!(join_words("log baby mole", "pet baby mole"), "pet baby mole");
        assert_eq!(join_words("pet baby mole", "drop 4.2m dragon pickaxe"), "pet baby mole · drop 4.2m dragon pickaxe");
        assert_eq!(join_words("pet baby mole · drop 4.2m dragon pickaxe", "drop 4.2m dragon pickaxe"), "pet baby mole · drop 4.2m dragon pickaxe");
        assert_eq!(kind_of(&join_words("log mole claw", "pet baby mole")), "PET");
    }

    #[test]
    fn big_moments_clip_themselves() {
        assert_eq!(big_moment(&json!({"type": "PET", "extra": {"petName": "Vorki"}}), 1_000_000).as_deref(), Some("pet vorki"));
        assert_eq!(big_moment(&json!({"type": "COLLECTION", "extra": {"itemName": "Skeletal visage"}}), 1_000_000).as_deref(), Some("log skeletal visage"));
        assert_eq!(big_moment(&loot(&[("Dragon bones", 2, 3000)]), 1_000_000), None);
        assert_eq!(big_moment(&loot(&[("Dragon bones", 2, 3000), ("Skeletal visage", 1, 4_200_000)]), 1_000_000).as_deref(),
                   Some("drop 4.2m skeletal visage"));
        assert_eq!(big_moment(&loot(&[("Coins", 600_000, 1), ("Rune bolts", 400, 1000)]), 1_000_000).as_deref(), Some("drop 1.0m coins"));
        assert_eq!(big_moment(&loot(&[("Skeletal visage", 1, 4_200_000)]), 0), None); // (0: drops never)
        assert_eq!(big_moment(&kc(412, true, false), 1), None);
        let level = |skills: Value| json!({"type": "LEVEL", "extra": {"levelledSkills": skills}});
        assert_eq!(big_moment(&level(json!({"Slayer": 99})), 1_000_000).as_deref(), Some("99 slayer"));
        assert_eq!(big_moment(&level(json!({"Attack": 99, "Strength": 99})), 1_000_000).as_deref(), Some("99 attack and strength"));
        assert_eq!(big_moment(&level(json!({"Slayer": 98})), 1_000_000), None);
        assert_eq!(big_moment(&level(json!({"Slayer": 100})), 1_000_000), None); // (a virtual level: not a 99)
        assert_eq!(kind_of("99 slayer"), "LEVEL");
        assert_eq!(big_moment(&json!({"type": "PET", "extra": {"petName": "Vorki", "duplicate": true}}), 1).as_deref(), Some("pet vorki"));
        assert_eq!(crate::clip::file_safe("drop 4.2m abyssal whip <a/b>?"), "drop 4.2m abyssal whip a b");
    }

    // (the Python relay's forwarding Rules' cases, kept here since it was retired, 11/17/2026)
    #[test]
    fn a_multipart_post_gives_its_payload_and_leaves_the_screenshot() {
        let body = b"--b\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n{\"type\": \"LEVEL\"}\r\n--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"i.png\"\r\n\r\n\x89PNG--b junk\r\n--b--\r\n";
        assert_eq!(payload("multipart/form-data; boundary=b", body).unwrap()["type"], "LEVEL");
        assert_eq!(payload("application/json", b"{\"type\": \"PET\"}").unwrap()["type"], "PET");
    }
}
