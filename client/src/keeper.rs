//! Friends' portals (the keeper's build, `--features station`): channels handed out and taken back on the relay, from
//! a drawer of the portal's own window. ghillie's tools/portal/friends.py and tools/launcher/portal_friends.py, again.
//!
//! A friend's portal is a channel on the Cloudflare relay (relay/), named after their player, with a key only their
//! client holds. Only the relay's keeper hands them out, with the relay's admin token (docs/protocol.md, "Handing out
//! channels"). The token and the relay's address are in Documents\ghillie portal\admin.json (the same file friends.py
//! reads), never in a repository: anyone with the token can hand out and take back every channel.

use serde_json::{json, Map, Value};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const SITE: &str = "https://ghillie.wiki/outside?player=";

pub fn admin_path() -> PathBuf {
    let home = std::env::var_os("USERPROFILE").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    home.join("Documents").join("ghillie portal").join("admin.json")
}

pub fn load() -> Map<String, Value> {
    std::fs::read_to_string(admin_path()).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok())
        .and_then(|v| v.as_object().cloned()).unwrap_or_default()
}

pub fn save(m: &Map<String, Value>) -> std::io::Result<()> {
    let p = admin_path();
    if let Some(d) = p.parent() { std::fs::create_dir_all(d)?; }
    let tmp = p.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_string_pretty(&Value::Object(m.clone())).unwrap())?;
    std::fs::rename(tmp, p)
}

fn field(m: &Map<String, Value>, k: &str) -> String {
    m.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string()
}

// (a player's name as its channel, and a relay's address as http(s): the same as a friend's client asking)
pub use crate::ask::{channel_of, http_base};

/// A new admin token: 32 random bytes, base64url (as friends.py's secrets.token_urlsafe(32)).
pub fn new_token() -> Result<String, String> {
    use base64::Engine;
    use ring::rand::SecureRandom;
    let mut b = [0u8; 32];
    ring::rand::SystemRandom::new().fill(&mut b).map_err(|_| "Windows gave no random bytes".to_string())?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b))
}

fn call(method: &str, route: &str, auth: bool) -> Result<Value, String> {
    let m = load();
    let base = http_base(&field(&m, "relay"));
    if base.is_empty() { return Err("no relay yet: set its address first (set up ▸)".into()); }
    let token = field(&m, "token");
    if auth && token.len() < 16 { return Err("no admin token yet: make one first (set up ▸)".into()); }
    let url = format!("{}{}", base, route);
    let bearer = format!("Bearer {}", token);
    let r = match method {
        "POST" => {
            let mut q = ureq::post(&url).header("User-Agent", "ghillie field station");
            if auth { q = q.header("Authorization", &bearer); }
            q.send_empty()
        }
        "DELETE" => {
            let mut q = ureq::delete(&url).header("User-Agent", "ghillie field station");
            if auth { q = q.header("Authorization", &bearer); }
            q.call()
        }
        _ => {
            let mut q = ureq::get(&url).header("User-Agent", "ghillie field station");
            if auth { q = q.header("Authorization", &bearer); }
            q.call()
        }
    };
    match r {
        Ok(mut r) => {
            let t = r.body_mut().read_to_string().unwrap_or_default();
            Ok(if t.trim().is_empty() { Value::Null } else { serde_json::from_str(&t).unwrap_or(Value::Null) })
        }
        Err(ureq::Error::StatusCode(403)) => Err("the relay refused the admin token (is it the one Cloudflare has?)".into()),
        Err(ureq::Error::StatusCode(c)) => Err(format!("the relay answered {}", c)),
        Err(e) => Err(format!("the relay is out of reach ({})", e)),
    }
}

#[derive(Clone, Debug)]
pub struct Row { pub channel: String, pub live: bool, pub viewers: u64, pub title: String, pub client: String, pub live_s: u64 }

/// Someone asking for a portal (a friend's client, from its attach screen): the keeper's to hand out or turn down.
#[derive(Clone, Debug)]
pub struct AskRow { pub channel: String, pub player: String, pub note: String, pub at_ms: u64, pub given: bool }

/// Every channel handed out, live ones first, with whether each is live (the open portals' list: a channel with a
/// watch code isn't on it, so it reads as off air).
pub fn listing() -> Result<Vec<Row>, String> {
    let given = call("GET", "/admin/channels", true)?;
    let public = call("GET", "/channels", false).ok().and_then(|v| v.get("channels").cloned()).unwrap_or(Value::Null);
    let find = |ch: &str| public.as_array().and_then(|a| a.iter().find(|c| c.get("channel").and_then(|x| x.as_str()) == Some(ch)).cloned());
    let mut rows: Vec<Row> = given.as_array().cloned().unwrap_or_default().iter().filter_map(|c| {
        let ch = c.get("channel")?.as_str()?.to_string();
        let p = find(&ch);
        Some(Row {
            // (the version the last transmitter said: the admin list's own, from relays since 11/06/2026)
            client: c.get("client").and_then(|x| x.as_str()).unwrap_or("").to_string(),
            // (seconds on air this month, UTC: the keeper's bill)
            live_s: c.get("live_s").and_then(|x| x.as_u64()).unwrap_or(0),
            live: p.as_ref().and_then(|p| p.get("live")).and_then(|x| x.as_bool()).unwrap_or(false),
            viewers: p.as_ref().and_then(|p| p.get("viewers")).and_then(|x| x.as_u64()).unwrap_or(0),
            title: p.as_ref().and_then(|p| p.get("title")).and_then(|x| x.as_str()).unwrap_or("").to_string(),
            channel: ch,
        })
    }).collect();
    rows.sort_by(|a, b| b.live.cmp(&a.live).then(a.channel.cmp(&b.channel)));
    Ok(rows)
}

/// Those asking for a portal, oldest first.
pub fn asking() -> Result<Vec<AskRow>, String> {
    let v = call("GET", "/admin/requests", true)?;
    Ok(v.as_array().cloned().unwrap_or_default().iter().filter_map(|r| {
        let s = |k: &str| r.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
        Some(AskRow { channel: r.get("channel")?.as_str()?.to_string(), player: s("player"), note: s("note"),
                      at_ms: r.get("at").and_then(|x| x.as_u64()).unwrap_or(0),
                      given: r.get("given").and_then(|x| x.as_bool()).unwrap_or(false) })
    }).collect())
}

/// Answer one: handed out (the code waits on the relay for their client, which attaches itself) or turned down.
pub fn answer(channel: &str, yes: bool) -> Result<String, String> {
    if yes {
        call("POST", &format!("/admin/requests/{}", channel), true)?;
        Ok(format!("{}: handed out · their portal attaches itself within a minute, at {}{}", channel, SITE.trim_start_matches("https://"), channel))
    } else {
        call("DELETE", &format!("/admin/requests/{}", channel), true)?;
        Ok(format!("{}: turned down · their portal says so", channel))
    }
}

/// The keeper's notice to every portal now ("" for none).
pub fn notice() -> Result<String, String> {
    let v = call("GET", "/admin/notice", true)?;
    Ok(v.pointer("/notice/text").and_then(|x| x.as_str()).unwrap_or("").to_string())
}

/// A new notice (every friend's portal shows it in its chat, once), or "" to take it down.
pub fn set_notice(text: &str) -> Result<String, String> {
    let m = load();
    let base = http_base(&field(&m, "relay"));
    if base.is_empty() { return Err("no relay yet: set its address first (set up ▸)".into()); }
    let url = format!("{}/admin/notice", base);
    let bearer = format!("Bearer {}", field(&m, "token"));
    let r = if text.trim().is_empty() {
        ureq::delete(&url).header("User-Agent", "ghillie field station").header("Authorization", &bearer).call()
    } else {
        ureq::put(&url).header("User-Agent", "ghillie field station").header("Authorization", &bearer)
            .header("Content-Type", "application/json").send(json!({ "text": text.trim() }).to_string().as_bytes())
    };
    match r {
        Ok(_) => Ok(if text.trim().is_empty() { "the notice is taken down".into() } else { "the notice went to every portal".into() }),
        Err(ureq::Error::StatusCode(403)) => Err("the relay refused the admin token (is it the one Cloudflare has?)".into()),
        Err(ureq::Error::StatusCode(c)) => Err(format!("the relay answered {}", c)),
        Err(e) => Err(format!("the relay is out of reach ({})", e)),
    }
}

/// A channel handed out (or a new key for one already out): (channel, attach code).
pub fn give(player: &str) -> Result<(String, String), String> {
    let ch = channel_of(player)?;
    let got = call("POST", &format!("/admin/channels/{}", ch), true)?;
    let code = got.get("code").and_then(|x| x.as_str()).ok_or("the relay gave no attach code")?.to_string();
    Ok((ch, code))
}

pub fn take(player: &str) -> Result<String, String> {
    let ch = channel_of(player)?;
    call("DELETE", &format!("/admin/channels/{}", ch), true)?;
    Ok(ch)
}

/// The portals button's words: with how many are asking for a portal, when any are.
pub fn portals_words(asking: usize, open: bool) -> String {
    let arrow = if open { "◂" } else { "▸" };
    if asking == 0 { format!("portals {}", arrow) } else { format!("portals · {} asking {}", asking, arrow) }
}

/// The chat's line for those newly asking for a portal (None for nobody).
pub fn asking_line(players: &[String]) -> Option<String> {
    match players {
        [] => None,
        [one] => Some(format!("{} is asking for a portal · open portals to hand one out or turn it down", one)),
        many => Some(format!("{} are asking for a portal: {} · open portals to answer them", many.len(), many.join(", "))),
    }
}

/// What to send them with the code (the words are placeholders, Jace's to change; friends.py's message).
pub fn message(channel: &str, code: &str) -> String {
    format!("your portal's attach code:\n\n{}\n\nopen outside-portal, paste it, and press go live. your portal opens at {}{}\n\
             (keep the code to yourself: it's the key to your channel)", code, SITE, channel)
}

/// The drawer's state: the list (read again every 10 s while it's open), what's being asked, and the last answer.
pub struct Keeper {
    pub rows: Arc<Mutex<Option<Result<Vec<Row>, String>>>>,
    /// those asking for a portal (read with the list)
    pub asks: Arc<Mutex<Option<Result<Vec<AskRow>, String>>>>,
    /// (asked first) answering a request: (hand it out?, the channel)
    pub confirm_ask: Option<(bool, String)>,
    /// the notice on the relay now (read with the list), and the one being written
    pub notice_now: Arc<Mutex<Option<String>>>,
    pub notice_text: String,
    read_at: Option<Instant>,
    /// (those asking, looked at every minute with the drawer shut too) when last, and the requests already told of in
    /// the chat (channel and when they asked: someone turned down who asks again is told of again)
    asks_at: Option<Instant>,
    told: std::collections::HashSet<(String, u64)>,
    busy: Arc<Mutex<bool>>,
    pub answer: Arc<Mutex<Option<Result<(String, String), String>>>>,
    pub note: Arc<Mutex<String>>,
    pub name: String,
    pub relay: String,
    pub setup_open: bool,
    /// (asked first) a new code for someone who has one, or taking one back: the channel
    pub confirm: Option<(bool, String)>,
}

impl Keeper {
    pub fn new() -> Keeper {
        let m = load();
        Keeper { rows: Arc::new(Mutex::new(None)), asks: Arc::new(Mutex::new(None)), confirm_ask: None,
                 notice_now: Arc::new(Mutex::new(None)), notice_text: String::new(),
                 read_at: None, asks_at: None, told: std::collections::HashSet::new(), busy: Arc::new(Mutex::new(false)),
                 answer: Arc::new(Mutex::new(None)), note: Arc::new(Mutex::new(String::new())), name: String::new(),
                 relay: field(&m, "relay"), setup_open: field(&m, "relay").is_empty(), confirm: None }
    }

    /// the list read again (now, or when 10 s have gone), off the window's thread
    pub fn read(&mut self, now: bool, wake: impl Fn() + Send + 'static) {
        if *self.busy.lock().unwrap() { return; }
        if !now && self.read_at.is_some_and(|t| t.elapsed() < Duration::from_secs(10)) { return; }
        self.read_at = Some(Instant::now());
        let (rows, asks, busy, notice_now) = (self.rows.clone(), self.asks.clone(), self.busy.clone(), self.notice_now.clone());
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let got = listing();
            *rows.lock().unwrap() = Some(got);
            *asks.lock().unwrap() = Some(asking());
            if let Ok(n) = notice() { *notice_now.lock().unwrap() = Some(n); }
            *busy.lock().unwrap() = false;
            wake();
        });
    }

    /// Those asking, read again once a minute with the drawer shut (11/06/2026, Jace: "add the asking count and a chat
    /// line"), so the portals button can count them and the chat can say so; with the drawer open its own read (every
    /// 10 s) has them. Nothing without an admin token.
    pub fn watch_asks(&mut self, wake: impl Fn() + Send + 'static) {
        let minute = Duration::from_secs(60);
        if *self.busy.lock().unwrap() || self.asks_at.is_some_and(|t| t.elapsed() < minute)
            || self.read_at.is_some_and(|t| t.elapsed() < minute) { return; }
        self.asks_at = Some(Instant::now());
        if !self.has_token() { return; }
        let (asks, busy) = (self.asks.clone(), self.busy.clone());
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            // (a relay out of reach keeps the last count: the drawer says why when it's opened)
            if let Ok(a) = asking() { *asks.lock().unwrap() = Some(Ok(a)); }
            *busy.lock().unwrap() = false;
            wake();
        });
    }

    /// how many are asking now (0 till the first read)
    pub fn asking_now(&self) -> usize {
        match &*self.asks.lock().unwrap() { Some(Ok(a)) => a.len(), _ => 0 }
    }

    /// The requests not yet told of in the chat, oldest first.
    pub fn new_asks(&mut self) -> Vec<AskRow> {
        let Some(Ok(asks)) = self.asks.lock().unwrap().clone() else { return Vec::new() };
        asks.into_iter().filter(|r| self.told.insert((r.channel.clone(), r.at_ms))).collect()
    }

    pub fn has(&self, channel: &str) -> bool {
        matches!(&*self.rows.lock().unwrap(), Some(Ok(r)) if r.iter().any(|x| x.channel == channel))
    }

    /// hand one out (or a new code), off the window's thread; the code comes back in `answer`
    pub fn give(&mut self, player: String, wake: impl Fn() + Send + 'static) {
        let (answer, busy, rows) = (self.answer.clone(), self.busy.clone(), self.rows.clone());
        *answer.lock().unwrap() = None;
        std::thread::spawn(move || {
            let got = give(&player);
            *answer.lock().unwrap() = Some(got);
            if let Ok(l) = listing() { *rows.lock().unwrap() = Some(Ok(l)); }
            let _ = busy;
            wake();
        });
    }

    pub fn take(&mut self, player: String, wake: impl Fn() + Send + 'static) {
        let (note, rows) = (self.note.clone(), self.rows.clone());
        std::thread::spawn(move || {
            *note.lock().unwrap() = match take(&player) {
                Ok(ch) => format!("{}: taken back · its key refused, its stream cut off (viewers stay, off air)", ch),
                Err(e) => e,
            };
            if let Ok(l) = listing() { *rows.lock().unwrap() = Some(Ok(l)); }
            wake();
        });
    }

    /// answer a request, off the window's thread; the list and the requests read again after
    pub fn answer(&mut self, channel: String, yes: bool, wake: impl Fn() + Send + 'static) {
        let (note, rows, asks) = (self.note.clone(), self.rows.clone(), self.asks.clone());
        std::thread::spawn(move || {
            *note.lock().unwrap() = match answer(&channel, yes) { Ok(s) => s, Err(e) => e };
            if let Ok(l) = listing() { *rows.lock().unwrap() = Some(Ok(l)); }
            *asks.lock().unwrap() = Some(asking());
            wake();
        });
    }

    /// send a notice (or "" to take it down), off the window's thread
    pub fn send_notice(&mut self, text: String, wake: impl Fn() + Send + 'static) {
        let (note, notice_now) = (self.note.clone(), self.notice_now.clone());
        std::thread::spawn(move || {
            *note.lock().unwrap() = match set_notice(&text) { Ok(s) => s, Err(e) => e };
            if let Ok(n) = notice() { *notice_now.lock().unwrap() = Some(n); }
            wake();
        });
    }

    pub fn save_relay(&self) -> Result<(), String> {
        let mut m = load();
        m.insert("relay".into(), json!(self.relay.trim()));
        save(&m).map_err(|e| e.to_string())
    }

    pub fn make_token(&self) -> Result<String, String> {
        let t = new_token()?;
        let mut m = load();
        m.insert("token".into(), json!(t));
        save(&m).map_err(|e| e.to_string())?;
        Ok(t)
    }

    pub fn has_token(&self) -> bool {
        field(&load(), "token").len() >= 16
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_players_name_as_its_channel() {
        assert_eq!(channel_of("  Moss  Fern ").unwrap(), "mossfern");
        assert_eq!(channel_of("ja_ck-1").unwrap(), "jack1");
        assert!(channel_of("").is_err());
        assert!(channel_of("thirteen chars").is_err());
        assert!(channel_of("no/slash").is_err());
    }

    #[test]
    fn those_asking_are_counted_and_told_once() {
        assert_eq!(portals_words(0, false), "portals ▸");
        assert_eq!(portals_words(2, true), "portals · 2 asking ◂");
        assert_eq!(asking_line(&[]), None);
        assert_eq!(asking_line(&["Moss Fern".into()]).unwrap(), "Moss Fern is asking for a portal · open portals to hand one out or turn it down");
        assert_eq!(asking_line(&["a".into(), "b".into()]).unwrap(), "2 are asking for a portal: a, b · open portals to answer them");
        let mut k = Keeper::new();
        let row = |ch: &str, at: u64| AskRow { channel: ch.into(), player: ch.into(), note: String::new(), at_ms: at, given: false };
        *k.asks.lock().unwrap() = Some(Ok(vec![row("moss", 1), row("fern", 2)]));
        assert_eq!(k.asking_now(), 2);
        assert_eq!(k.new_asks().len(), 2);
        assert_eq!(k.new_asks().len(), 0);
        // (turned down and asking again: a new request, told of again)
        *k.asks.lock().unwrap() = Some(Ok(vec![row("moss", 5)]));
        assert_eq!(k.new_asks().iter().map(|r| r.channel.clone()).collect::<Vec<_>>(), vec!["moss".to_string()]);
    }

    #[test]
    fn tokens_are_long_and_new_each_time() {
        let (a, b) = (new_token().unwrap(), new_token().unwrap());
        assert_eq!(a.len(), 43);
        assert_ne!(a, b);
        assert_eq!(http_base("wss://r.example/"), "https://r.example");
        assert_eq!(http_base("r.example"), "https://r.example");
    }
}
