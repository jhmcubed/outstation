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
    // (where Dink's post went in Discord: the clip, if one saves itself, follows it there)
    let mut discord_urls: Vec<String> = Vec::new();
    if forward_wants(&s.forward, &payload) {
        let urls = forward_urls(&s.forward, &kind);
        discord_urls = urls.clone();
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
        went.push("your Outside page");
        let url = format!("{}/dink/{}?key={}", s.http_base(), enc(&s.channel), enc(&s.key));
        // (only what the relay uses leaves this PC: for_relay; and when it came, so two drops alike in a few seconds
        // stay two at the relay, whose own check for a post sent twice would take them for one)
        let mut trimmed = for_relay(&payload);
        trimmed["at"] = serde_json::json!(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0));
        let json = trimmed.to_string();
        std::thread::spawn(move || {
            let r = ureq::post(&url).header("Content-Type", "application/json").send(json.as_bytes());
            if let Err(e) = r {
                crate::transmit::log(&format!("a report didn't reach the relay ({})", e));
            }
        });
    }
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
            let to_discord = s.dink.clips_discord && !discord_urls.is_empty();
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
                    let mut failed = None;
                    for u in &discord_urls {
                        if let Some(why) = post_discord(u, &body, &ct) {
                            // (never the address itself in the log: it's the key to the server)
                            crate::transmit::log(&format!("the clip didn't reach Discord: {}", why));
                            failed = Some(why);
                        }
                    }
                    crate::clip::say(match failed {
                        None => format!("in Discord, under Dink's post: {} ({:.1} MB)", what, page.mp4.len() as f64 / 1048576.0),
                        Some(why) => format!("the clip didn't reach Discord: {}", why),
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

/// What of Dink's post goes to the relay (11/06/2026): only what the relay uses of it, so nothing else leaves this PC.
/// That's its kind, the line it shows (the embed's description, else its title, else the post's content: as the relay
/// picks it), and the few numbers the readings and the drop guesses need for that kind (relay/src/text.ts's
/// reportData and channel.ts's dinkCounts, relay-py's report_data and dink_counts: the same fields, every loot stack
/// and the loot's kill count included). Everything else in Dink's post stays here: your player's name and account type as
/// Dink sends them, Dink's account hash, your Discord user if Dink adds it, the world and the region, the screenshot,
/// a raid party's names, a PvP killer's name, the player a PvP drop came from. A PvP drop or death keeps no line (the
/// relay would drop it: it names the other player), and a death says only that it was PvP, never who.
pub fn for_relay(p: &Value) -> Value {
    let kind = p.get("type").and_then(|x| x.as_str()).unwrap_or("");
    let e = extra(p);
    let keep: &[&str] = match kind {
        "KILL_COUNT" => &["boss", "count", "isPersonalBest"],
        "LEVEL" => &["levelledSkills", "allSkills", "combatLevel"],
        "LOOT" => &["category", "source", "killCount"],
        "COLLECTION" => &["itemName", "itemId", "completedEntries", "totalEntries"],
        "PET" => &["petName", "duplicate", "milestone"],
        "QUEST" => &["questName", "completedQuests", "totalQuests"],
        "CLUE" => &["clueType", "numberCompleted"],
        "SLAYER" => &["slayerTask", "slayerCompleted", "slayerPoints"],
        "COMBAT_ACHIEVEMENT" => &["tier", "task"],
        "ACHIEVEMENT_DIARY" => &["area", "difficulty"],
        _ => &[],
    };
    let mut out = serde_json::Map::new();
    for k in keep {
        if let Some(v) = e.get(*k) { out.insert(k.to_string(), v.clone()); }
    }
    let truthy = |v: Option<&Value>| match v {
        None | Some(Value::Null) | Some(Value::Bool(false)) => false,
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Number(n)) => n.as_f64() != Some(0.0),
        _ => true,
    };
    let from_player = kind == "LOOT" && e.get("category").and_then(|x| x.as_str()).is_some_and(|c| c.eq_ignore_ascii_case("PLAYER"));
    let pvp_death = kind == "DEATH" && (truthy(e.get("isPvp")) || truthy(e.get("pvp")) || truthy(e.get("killerName")));
    if kind == "LOOT" {
        // (each stack's name, how many, their price and the item's id: the readings keep the first 12, a drop guess
        // looks at every one)
        let items: Vec<Value> = e.get("items").and_then(|x| x.as_array()).map(|a| a.iter().filter(|i| i.is_object()).map(|i| {
            let mut it = serde_json::Map::new();
            for k in ["name", "quantity", "priceEach", "id"] {
                if let Some(v) = i.get(k) { it.insert(k.to_string(), v.clone()); }
            }
            Value::Object(it)
        }).collect()).unwrap_or_default();
        out.insert("items".into(), Value::Array(items));
        if from_player { out.remove("source"); }
    }
    if pvp_death { out.insert("isPvp".into(), Value::Bool(true)); }
    let said = |v: Option<&Value>| v.and_then(|x| x.as_str()).filter(|x| !x.is_empty()).map(|x| x.to_string());
    let first = p.get("embeds").and_then(|x| x.as_array()).and_then(|a| a.first());
    let text = if from_player || pvp_death { String::new() } else {
        said(first.and_then(|f| f.get("description"))).or_else(|| said(first.and_then(|f| f.get("title"))))
            .or_else(|| said(p.get("content"))).unwrap_or_default()
    };
    serde_json::json!({ "type": kind, "content": text, "extra": Value::Object(out) })
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

/// Where a kind goes on to: its own webhooks if its rule names some (a notifier with its own webhook in Dink:
/// dinksetup.rs), else the rules' Discord list.
pub fn forward_urls(rules: &Value, kind: &str) -> Vec<String> {
    let own = rules.get("forward").and_then(|f| f.get(kind)).and_then(|r| r.get("webhooks")).and_then(|x| x.as_array());
    own.or_else(|| rules.get("discord").and_then(|x| x.as_array())).map(|a| {
        a.iter().filter_map(|u| u.as_str()).filter(|u| u.starts_with("https://") || u.starts_with("http://")).map(|u| u.to_string()).collect()
    }).unwrap_or_default()
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
    fn only_what_the_relay_keeps_leaves_this_pc() {
        let kc = serde_json::json!({
            "type": "KILL_COUNT", "playerName": "Lichen Ring", "accountType": "IRONMAN", "dinkAccountHash": "abc123",
            "discordUser": {"id": "1234", "name": "someone"}, "world": 330, "regionId": 12345, "clanName": "moss",
            "content": "", "embeds": [{"title": "kill count", "description": "**Lichen Ring** has defeated Vorkath with a completion count of 50",
                                       "author": {"name": "Lichen Ring"}, "fields": [{"name": "x", "value": "y"}]}],
            "extra": {"boss": "Vorkath", "count": 50, "isPersonalBest": false, "gameMessage": "...", "party": ["Moss Fern", "Bog"]}});
        let t = for_relay(&kc);
        assert_eq!(t, serde_json::json!({"type": "KILL_COUNT", "content": "**Lichen Ring** has defeated Vorkath with a completion count of 50",
                                         "extra": {"boss": "Vorkath", "count": 50, "isPersonalBest": false}}));
        let s = t.to_string();
        for gone in ["abc123", "1234", "330", "12345", "Moss Fern", "IRONMAN", "moss\""] { assert!(!s.contains(gone), "{} went", gone); }
        // a drop from another player: its stacks, never who it came from, and no line (it names them)
        let pk = serde_json::json!({"type": "LOOT", "embeds": [{"description": "Lichen Ring has looted 1m from Moss Fern"}],
            "extra": {"category": "PLAYER", "source": "Moss Fern", "items": [{"name": "Coins", "quantity": 1000000, "priceEach": 1, "id": 995, "rarity": null, "criteria": ["VALUE"]}, 7]}});
        assert_eq!(for_relay(&pk), serde_json::json!({"type": "LOOT", "content": "",
            "extra": {"category": "PLAYER", "items": [{"name": "Coins", "quantity": 1000000, "priceEach": 1, "id": 995}]}}));
        // a death in PvP: that it was, never who
        let d = serde_json::json!({"type": "DEATH", "content": "Lichen Ring was killed by Moss Fern", "extra": {"isPvp": true, "killerName": "Moss Fern", "valueLost": 9}});
        assert_eq!(for_relay(&d), serde_json::json!({"type": "DEATH", "content": "", "extra": {"isPvp": true}}));
        // a plain JSON post's own words, when there's no embed
        // a boss's drop: every stack, and the kill count it came at (a drop guess decides on it)
        let many: Vec<Value> = (0..15).map(|n| serde_json::json!({"name": format!("item {}", n), "quantity": 1, "priceEach": n, "id": n, "rarity": 0.1})).collect();
        let boss = serde_json::json!({"type": "LOOT", "content": "drop", "extra": {"category": "NPC", "source": "Vorkath", "killCount": 51, "npcId": 8061, "party": ["Bog"], "items": many}});
        let tb = for_relay(&boss);
        assert_eq!(tb["extra"]["killCount"], 51);
        assert_eq!(tb["extra"]["source"], "Vorkath");
        assert_eq!(tb["extra"]["items"].as_array().unwrap().len(), 15);
        assert_eq!(tb["extra"]["items"][14], serde_json::json!({"name": "item 14", "quantity": 1, "priceEach": 14, "id": 14}));
        assert!(tb["extra"].get("party").is_none() && tb["extra"].get("npcId").is_none());
        let lv = serde_json::json!({"type": "LEVEL", "content": "Lichen Ring has levelled Attack to 99",
            "extra": {"levelledSkills": {"Attack": 99}, "allSkills": {"Attack": 99}, "combatLevel": {"value": 100, "increased": true}}});
        assert_eq!(for_relay(&lv)["content"], "Lichen Ring has levelled Attack to 99");
        assert_eq!(for_relay(&lv)["extra"]["combatLevel"]["value"], 100);
    }

    #[test]
    fn a_multipart_post_gives_its_payload_and_leaves_the_screenshot() {
        let body = b"--b\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n{\"type\": \"LEVEL\"}\r\n--b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"i.png\"\r\n\r\n\x89PNG--b junk\r\n--b--\r\n";
        assert_eq!(payload("multipart/form-data; boundary=b", body).unwrap()["type"], "LEVEL");
        assert_eq!(payload("application/json", b"{\"type\": \"PET\"}").unwrap()["type"], "PET");
    }
}
