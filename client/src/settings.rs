//! The client's settings: one JSON file, read by every part and written by the window and the hotkeys.
//!
//! %APPDATA%\outside-portal\settings.json (or --settings <file>). Anything in the file this version doesn't know is
//! kept as it was. The fields are those of ghillie's start.py settings, as the parity list asks
//! (claude/the-portal.md, "Parity"), with the capture's options as fields of their own instead of a "capture" list.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::path::{Path, PathBuf};
use std::sync::{Arc, RwLock};
use std::time::SystemTime;

pub const REPORTS: &[&str] = &[
    "LEVEL", "XP_MILESTONE", "LOOT", "QUEST", "CLUE", "KILL_COUNT", "COLLECTION", "PET", "SLAYER", "SPEEDRUN",
    "COMBAT_ACHIEVEMENT", "ACHIEVEMENT_DIARY", "BARBARIAN_ASSAULT_GAMBLE", "DEATH", "LEAGUES_AREA", "LEAGUES_RELIC",
    "LEAGUES_TASK", "GRAND_EXCHANGE",
];

fn d_relay() -> String { String::new() }
fn d_fps() -> u32 { 8 }
fn d_width() -> u32 { 384 }
fn d_quality() -> u8 { 60 }
fn d_view() -> String { "canvas".into() }
fn d_mask() -> String { "none".into() }
fn d_method() -> String { "auto".into() }
fn d_title() -> String { "RuneLite".into() }
fn d_pause_key() -> String { "pause".into() }
fn d_true() -> bool { true }
fn d_sound() -> String { "off".into() }
fn d_sound_key() -> String { "scrolllock".into() }
fn d_kbps() -> u32 { 128 }
fn d_viewers() -> u32 { 10 }
fn d_upload() -> f64 { 40.0 }
fn d_dink_port() -> u16 { 8796 }
fn d_one() -> u32 { 1 }

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Dink {
    /// the local port Dink posts to (http://127.0.0.1:<port>/dink)
    #[serde(default = "d_dink_port")]
    pub port: u16,
    /// which kinds go into the broadcast (missing: on)
    #[serde(default)]
    pub kinds: Map<String, Value>,
    /// loot on the portal: a stack worth at least this (0: every drop Dink sends)
    #[serde(default)]
    pub loot_min: u64,
    /// kill counts on the portal: every Nth (1: each), and always the first and personal bests
    #[serde(default = "d_one")]
    pub kc_every: u32,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for Dink {
    fn default() -> Self {
        Dink { port: d_dink_port(), kinds: Map::new(), loot_min: 0, kc_every: 1, extra: Map::new() }
    }
}

impl Dink {
    pub fn kind_on(&self, kind: &str) -> bool {
        self.kinds.get(kind).and_then(|v| v.as_bool()).unwrap_or(true)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Settings {
    /// the relay: wss://… (Cloudflare) or ws://127.0.0.1:8786 (a relay-py of your own)
    #[serde(default = "d_relay")]
    pub relay: String,
    #[serde(default)]
    pub channel: String,
    #[serde(default)]
    pub key: String,
    /// the watch code viewers need (empty: anyone)
    #[serde(default)]
    pub code: String,
    /// the name your lines go out under in the chat (the channel's when empty)
    #[serde(default)]
    pub chat_name: String,
    #[serde(default = "d_fps")]
    pub fps: u32,
    #[serde(default = "d_width")]
    pub width: u32,
    #[serde(default = "d_quality")]
    pub quality: u8,
    /// "canvas" (the whole game) or "viewport" (fixed mode's 3D view only)
    #[serde(default = "d_view")]
    pub view: String,
    /// "none", "chat", "side" or "both"
    #[serde(default = "d_mask")]
    pub mask: String,
    /// "auto", "gpu" (Windows Graphics Capture) or "gdi" (PrintWindow)
    #[serde(default = "d_method")]
    pub method: String,
    /// the start of RuneLite's window title
    #[serde(default = "d_title")]
    pub title: String,
    #[serde(default = "d_pause_key")]
    pub pause_key: String,
    #[serde(default)]
    pub only_logged_in: bool,
    /// go live / end stream
    #[serde(default = "d_true")]
    pub on_air: bool,
    /// "off", "game" or "all"
    #[serde(default = "d_sound")]
    pub sound: String,
    #[serde(default = "d_sound_key")]
    pub sound_key: String,
    #[serde(default = "d_kbps")]
    pub sound_kbps: u32,
    /// viewers at once (the desk's {"cap"})
    #[serde(default = "d_viewers")]
    pub max_viewers: u32,
    /// your home upload, for the upload line
    #[serde(default = "d_upload")]
    pub upload_mbit: f64,
    /// pings shown over RuneLite
    #[serde(default = "d_true")]
    pub pings: bool,
    #[serde(default)]
    pub dink: Dink,
    /// look on GitHub for a newer version when the window opens (it never updates itself)
    #[serde(default = "d_true")]
    pub check_updates: bool,
    /// forward.py's rules: {"discord": [webhook…], "forward": {"LEVEL": true, "LOOT": {…}, …}}
    #[serde(default)]
    pub forward: Value,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for Settings {
    fn default() -> Self {
        serde_json::from_str("{}").unwrap()
    }
}

impl Settings {
    pub fn attached(&self) -> bool {
        !self.relay.is_empty() && !self.channel.is_empty() && self.key.len() >= 8
    }
    pub fn host_name(&self) -> String {
        if self.chat_name.trim().is_empty() { self.channel.clone() } else { self.chat_name.trim().to_string() }
    }
    /// the relay's address for one of its sockets
    pub fn socket_url(&self, role: &str, query: &str) -> String {
        format!("{}/{}/{}?{}", self.relay.trim_end_matches('/'), role, enc(&self.channel), query)
    }
    /// its http(s) address (for Dink's posts)
    pub fn http_base(&self) -> String {
        let r = self.relay.trim_end_matches('/');
        if let Some(rest) = r.strip_prefix("wss://") { format!("https://{}", rest) }
        else if let Some(rest) = r.strip_prefix("ws://") { format!("http://{}", rest) }
        else { r.to_string() }
    }
}

pub fn enc(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) { out.push(b as char) } else { out.push_str(&format!("%{:02X}", b)) }
    }
    out
}

pub fn default_path() -> PathBuf {
    let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    base.join("outside-portal").join("settings.json")
}

/// ghillie's own portal settings (start.py's), which the attach screen can take over
pub fn ghillie_path() -> Option<PathBuf> {
    let home = std::env::var_os("USERPROFILE")?;
    let p = PathBuf::from(home).join("Documents").join("ghillie portal").join("settings.json");
    p.exists().then_some(p)
}

/// The settings shared by every part: kept in memory, saved on each change, read again when the file changes.
#[derive(Clone)]
pub struct Shared {
    pub path: Arc<PathBuf>,
    inner: Arc<RwLock<(Settings, Option<SystemTime>)>>,
}

impl Shared {
    pub fn open(path: PathBuf) -> Shared {
        let (s, t) = read(&path);
        Shared { path: Arc::new(path), inner: Arc::new(RwLock::new((s, t))) }
    }
    pub fn get(&self) -> Settings {
        self.inner.read().unwrap().0.clone()
    }
    /// a change, saved at once
    pub fn update(&self, f: impl FnOnce(&mut Settings)) -> std::io::Result<()> {
        let mut g = self.inner.write().unwrap();
        f(&mut g.0);
        write(&self.path, &g.0)?;
        g.1 = modified(&self.path);
        Ok(())
    }
    /// read again if the file changed since (another program, or the hand)
    pub fn refresh(&self) {
        let now = modified(&self.path);
        let seen = self.inner.read().unwrap().1;
        if now.is_some() && now != seen {
            let (s, t) = read(&self.path);
            *self.inner.write().unwrap() = (s, t);
        }
    }
}

fn modified(p: &Path) -> Option<SystemTime> {
    std::fs::metadata(p).and_then(|m| m.modified()).ok()
}

fn read(p: &Path) -> (Settings, Option<SystemTime>) {
    let s = std::fs::read_to_string(p).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
    (s, modified(p))
}

fn write(p: &Path, s: &Settings) -> std::io::Result<()> {
    if let Some(dir) = p.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let tmp = p.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_string_pretty(s).unwrap())?;
    std::fs::rename(&tmp, p)
}

/// An attach code: "portal1:" and base64url JSON {"relay", "channel", "key"}.
pub fn parse_attach(code: &str) -> Result<(String, String, String), String> {
    use base64::Engine;
    let code = code.trim();
    let raw = code.strip_prefix("portal1:").ok_or("that isn't an attach code (it starts portal1:)")?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(raw.trim_end_matches('=').as_bytes())
        .map_err(|_| "the attach code is cut short or changed".to_string())?;
    let v: Value = serde_json::from_slice(&bytes).map_err(|_| "the attach code is cut short or changed".to_string())?;
    let get = |k: &str| v.get(k).and_then(|x| x.as_str()).map(|s| s.to_string());
    match (get("relay"), get("channel"), get("key")) {
        (Some(r), Some(c), Some(k)) if (r.starts_with("ws://") || r.starts_with("wss://")) && !c.is_empty() && k.len() >= 8 => Ok((r, c, k)),
        _ => Err("the attach code is missing its relay, channel or key".into()),
    }
}

/// ghillie's start.py settings, as this client's: the relay on this PC, its channel, key, code and the rest.
pub fn from_ghillie(p: &Path) -> Option<Settings> {
    let v: Value = serde_json::from_str(&std::fs::read_to_string(p).ok()?).ok()?;
    let mut s = Settings::default();
    let port = v.get("port").and_then(|x| x.as_u64()).unwrap_or(8786);
    s.relay = format!("ws://127.0.0.1:{}", port);
    let st = |k: &str| v.get(k).and_then(|x| x.as_str()).map(|x| x.to_string());
    s.channel = st("channel").unwrap_or_else(|| "sburb".into());
    s.key = st("key")?;
    s.code = st("code").unwrap_or_default();
    s.chat_name = st("chat_name").unwrap_or_default();
    if let Some(m) = st("sound") { s.sound = m; }
    if let Some(m) = st("sound_key") { s.sound_key = m; }
    if let Some(n) = v.get("fps").and_then(|x| x.as_u64()) { s.fps = n as u32; }
    if let Some(n) = v.get("max_viewers").and_then(|x| x.as_u64()) { s.max_viewers = n as u32; }
    if let Some(n) = v.get("sound_kbps").and_then(|x| x.as_u64()) { s.sound_kbps = n as u32; }
    if let Some(n) = v.get("upload_mbit").and_then(|x| x.as_f64()) { s.upload_mbit = n; }
    if let Some(b) = v.get("on_air").and_then(|x| x.as_bool()) { s.on_air = b; }
    // the capture's own options, from its "capture" list
    let cap: Vec<String> = v.get("capture").and_then(|x| x.as_array()).map(|a| {
        a.iter().map(|x| x.as_str().map(|s| s.to_string()).unwrap_or_else(|| x.to_string())).collect()
    }).unwrap_or_default();
    let arg = |flag: &str| cap.iter().position(|x| x == flag).and_then(|i| cap.get(i + 1)).cloned();
    if let Some(w) = arg("--width").and_then(|x| x.parse().ok()) { s.width = w; }
    if let Some(q) = arg("--quality").and_then(|x| x.parse().ok()) { s.quality = q; }
    if let Some(x) = arg("--view") { s.view = x; }
    if let Some(x) = arg("--mask") { s.mask = x; }
    if let Some(x) = arg("--method") { s.method = x; }
    if let Some(x) = arg("--title") { s.title = x; }
    if let Some(x) = arg("--pause-key") { s.pause_key = x; }
    if cap.iter().any(|x| x == "--only-logged-in") { s.only_logged_in = true; }
    // and his Discord forwarding rules (forward.json beside it)
    if let Some(f) = p.parent().map(|d| d.join("forward.json")) {
        if let Some(rules) = std::fs::read_to_string(f).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) {
            s.forward = rules;
        }
    }
    Some(s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_attach_code_reads_back() {
        use base64::Engine;
        let json = r#"{"relay": "wss://outside-portal.x.workers.dev", "channel": "ja", "key": "abcdefghijkl"}"#;
        let code = format!("portal1:{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json));
        assert_eq!(parse_attach(&code).unwrap(), ("wss://outside-portal.x.workers.dev".into(), "ja".into(), "abcdefghijkl".into()));
        assert!(parse_attach("portal1:!!!").is_err());
        assert!(parse_attach("hello").is_err());
    }

    #[test]
    fn unknown_fields_are_kept_and_defaults_fill_in() {
        let s: Settings = serde_json::from_str(r#"{"channel": "ja", "later": 5, "dink": {"port": 9000, "soon": true}}"#).unwrap();
        assert_eq!((s.fps, s.width, s.quality, s.dink.port), (8, 384, 60, 9000));
        let back = serde_json::to_value(&s).unwrap();
        assert_eq!(back["later"], 5);
        assert_eq!(back["dink"]["soon"], true);
    }

    #[test]
    fn urls() {
        let mut s = Settings::default();
        s.relay = "wss://r.example/".into();
        s.channel = "a b".into();
        assert_eq!(s.socket_url("watch", "code="), "wss://r.example/watch/a%20b?code=");
        assert_eq!(s.http_base(), "https://r.example");
    }
}
