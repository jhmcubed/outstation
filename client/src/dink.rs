//! Dink's posts, taken on this PC (parity lines 33-34).
//!
//! Dink (RuneLite's notifier) posts here as it would to a Discord webhook: http://127.0.0.1:<port>/dink. Nothing
//! listens beyond this PC. Each post:
//!   - goes on to the broadcast if your switches let it (its kind on, a loot stack worth enough, a kill count on the
//!     beat), as Dink's own JSON without the screenshot, to the relay's Dink route. The relay keeps only Dink's own
//!     kinds and drops anything naming another player, so the same rules hold whichever relay it is.
//!   - goes on to your Discord webhook, as Dink sent it (the embed and the screenshot), if forward.py's rules want it.

use crate::settings::{enc, Settings, Shared};
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
    if forward_wants(&s.forward, &payload) {
        let urls: Vec<String> = s.forward.get("discord").and_then(|x| x.as_array()).map(|a| {
            a.iter().filter_map(|u| u.as_str()).filter(|u| u.starts_with("https://") || u.starts_with("http://")).map(|u| u.to_string()).collect()
        }).unwrap_or_default();
        if !urls.is_empty() {
            went.push("Discord");
            let (body, ct) = (body.clone(), content_type.clone());
            std::thread::spawn(move || {
                for u in urls {
                    if let Some(why) = post_discord(&u, &body, &ct) {
                        // never the URL itself in the log: it's the key to the server
                        crate::transmit::log(&format!("forward to Discord failed: {}", why));
                    }
                }
            });
        }
    }
    if s.attached() && portal_wants(&s, &payload) {
        went.push("the portal");
        let url = format!("{}/dink/{}?key={}", s.http_base(), enc(&s.channel), enc(&s.key));
        let json = payload.to_string();
        std::thread::spawn(move || {
            let r = ureq::post(&url).header("Content-Type", "application/json").send(json.as_bytes());
            if let Err(e) = r {
                crate::transmit::log(&format!("a report didn't reach the relay ({})", e));
            }
        });
    }
    st.lock().unwrap().last = Some((kind, Instant::now(), if went.is_empty() { "nowhere".into() } else { went.join(" and ") }));
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

/// Your switches for the broadcast.
pub fn portal_wants(s: &Settings, p: &Value) -> bool {
    let kind = p.get("type").and_then(|x| x.as_str()).unwrap_or("");
    if !crate::settings::REPORTS.contains(&kind) || !s.dink.kind_on(kind) {
        return false;
    }
    match kind {
        "LOOT" => s.dink.loot_min == 0 || stacks(p).iter().any(|(_, v)| *v >= s.dink.loot_min as i64),
        "KILL_COUNT" => {
            let e = extra(p);
            let count = int(e.get("count"));
            let every = s.dink.kc_every.max(1) as i64;
            every == 1 || count == 1 || e.get("isPersonalBest").and_then(|x| x.as_bool()).unwrap_or(false)
                || (count > 0 && count % every == 0)
        }
        _ => true,
    }
}

/// Would Discord have got this post under the rules? (forward.py's Forward.wants)
pub fn forward_wants(rules: &Value, p: &Value) -> bool {
    let kind = p.get("type").and_then(|x| x.as_str()).unwrap_or("");
    let Some(rule) = rules.get("forward").and_then(|f| f.get(kind)) else { return false };
    match rule {
        Value::Bool(b) => *b,
        Value::Null => false,
        Value::Object(r) if kind == "LOOT" => {
            let least = int(r.get("minValue"));
            let items = stacks(p);
            if items.iter().any(|(_, v)| *v >= least) {
                return true;
            }
            let allow: Vec<String> = r.get("allow").and_then(|x| x.as_array()).map(|a| {
                a.iter().filter_map(|x| x.as_str()).map(|x| x.trim().to_lowercase()).filter(|x| !x.is_empty()).collect()
            }).unwrap_or_default();
            items.iter().any(|(n, _)| allow.iter().any(|a| glob(a, n)))
        }
        Value::Object(r) if kind == "KILL_COUNT" => {
            let e = extra(p);
            let count = int(e.get("count"));
            if r.get("personalBest").and_then(|x| x.as_bool()).unwrap_or(false)
                && e.get("isPersonalBest").and_then(|x| x.as_bool()).unwrap_or(false) {
                return true;
            }
            if r.get("first").and_then(|x| x.as_bool()).unwrap_or(false) && count == 1 {
                return true;
            }
            let text = format!("{} {}", p.get("content").and_then(|x| x.as_str()).unwrap_or(""),
                               e.get("gameMessage").and_then(|x| x.as_str()).unwrap_or("")).to_lowercase();
            let every = int(r.get(if text.contains(" lap") { "lapsEvery" } else { "every" }).or(Some(&Value::from(1))));
            every != 0 && count > 0 && count % every == 0
        }
        Value::Object(o) => !o.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Number(n) => n.as_f64() != Some(0.0),
        Value::String(s) => !s.is_empty(),
    }
}

/// fnmatch's * and ? (case already folded)
fn glob(pat: &str, s: &str) -> bool {
    let (p, t): (Vec<char>, Vec<char>) = (pat.chars().collect(), s.chars().collect());
    fn go(p: &[char], t: &[char]) -> bool {
        match p.first() {
            None => t.is_empty(),
            Some('*') => (0..=t.len()).any(|i| go(&p[1..], &t[i..])),
            Some('?') => !t.is_empty() && go(&p[1..], &t[1..]),
            Some(c) => t.first() == Some(c) && go(&p[1..], &t[1..]),
        }
    }
    go(&p, &t)
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
                std::thread::sleep(Duration::from_secs_f64(wait.min(10.0)));
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

    fn rules() -> Value {
        // relay-py/forward.example.json: Jace's ::dinkexport of 11/04/2026
        serde_json::from_str(include_str!("../../relay-py/forward.example.json")).unwrap()
    }
    fn loot(stacks: &[(&str, i64, i64)]) -> Value {
        json!({"type": "LOOT", "content": "loot", "extra": {"items": stacks.iter().map(|(n, q, p)| json!({"name": n, "quantity": q, "priceEach": p})).collect::<Vec<_>>(), "source": "Vorkath"}})
    }
    fn kc(count: i64, pb: bool, laps: bool) -> Value {
        json!({"type": "KILL_COUNT", "content": if laps { format!("has completed {} laps at X", count) } else { "kc".into() },
               "extra": {"boss": "X", "count": count, "isPersonalBest": pb}})
    }

    // (relay-py/test_relay.py's Rules, the same cases)
    #[test]
    fn loot_needs_a_stack_worth_a_million_or_an_allowed_item() {
        let f = rules();
        assert!(!forward_wants(&f, &loot(&[("Dragon bones", 2, 3000)])));
        assert!(forward_wants(&f, &loot(&[("Dragon bones", 2, 3000), ("Draconic visage", 1, 2500000)])));
        assert!(forward_wants(&f, &loot(&[("Coins", 1000000, 1)])));
        assert!(forward_wants(&f, &loot(&[("Blood quartz", 1, 0)])));
        assert!(forward_wants(&f, &loot(&[("Awakener's orb", 1, 900000)])));
    }

    #[test]
    fn kill_counts_every_25_the_first_and_personal_bests() {
        let f = rules();
        assert!(!forward_wants(&f, &kc(26, false, false)));
        assert!(forward_wants(&f, &kc(50, false, false)));
        assert!(forward_wants(&f, &kc(1, false, false)));
        assert!(forward_wants(&f, &kc(37, true, false)));
        assert!(!forward_wants(&f, &kc(25, false, true)));
        assert!(forward_wants(&f, &kc(2000, false, true)));
    }

    #[test]
    fn what_was_on_still_goes_and_what_was_off_doesnt() {
        let f = rules();
        for k in ["LEVEL", "DEATH", "QUEST", "PET", "COLLECTION", "PLAYER_KILL"] {
            assert!(forward_wants(&f, &json!({"type": k})), "{}", k);
        }
        for k in ["SLAYER", "SPEEDRUN", "GRAND_EXCHANGE", "TRADE", "CHAT", "XP_MILESTONE", "SOMETHING_NEW"] {
            assert!(!forward_wants(&f, &json!({"type": k})), "{}", k);
        }
    }

    #[test]
    fn the_portal_switches() {
        let mut s = Settings::default();
        assert!(portal_wants(&s, &json!({"type": "LEVEL"})));
        assert!(!portal_wants(&s, &json!({"type": "TRADE"})));
        s.dink.kinds.insert("LEVEL".into(), json!(false));
        assert!(!portal_wants(&s, &json!({"type": "LEVEL"})));
        s.dink.loot_min = 100000;
        assert!(!portal_wants(&s, &loot(&[("Bones", 1, 100)])));
        assert!(portal_wants(&s, &loot(&[("Bones", 1, 100), ("Visage", 1, 200000)])));
        s.dink.kc_every = 10;
        assert!(!portal_wants(&s, &kc(7, false, false)));
        assert!(portal_wants(&s, &kc(20, false, false)) && portal_wants(&s, &kc(1, false, false)) && portal_wants(&s, &kc(7, true, false)));
    }

    #[test]
    fn a_multipart_post_gives_its_payload_and_leaves_the_screenshot() {
        let body = b"--b\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n{\"type\": \"LEVEL\"}\r\n--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"i.png\"\r\n\r\n\x89PNG--b junk\r\n--b--\r\n";
        assert_eq!(payload("multipart/form-data; boundary=b", body).unwrap()["type"], "LEVEL");
        assert_eq!(payload("application/json", b"{\"type\": \"PET\"}").unwrap()["type"], "PET");
    }
}
