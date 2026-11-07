//! The client's settings: one JSON file, read by every part and written by the window and the hotkeys.
//!
//! %APPDATA%\outstation\settings.json (or --settings <file>). Anything in the file this version doesn't know is
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
fn d_static_key() -> String { "pause".into() }
fn d_clip_key() -> String { "insert".into() }
fn d_chat_size() -> f32 { 16.0 }
fn d_game_chat_size() -> f32 { 20.0 }
fn d_game_chat_fade() -> u32 { 20 }
fn d_guess_min() -> u64 { 1_000_000 }
fn d_scale() -> f32 { 1.0 }
fn d_mask_key() -> String { "end".into() }

/// A hotkey as the Python portal's settings hold it: a name ("pause") or a Windows key code (45).
fn de_key<'de, D: serde::Deserializer<'de>>(d: D) -> Result<String, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::String(s) => s,
        Value::Number(n) => format!("0x{:x}", n.as_u64().unwrap_or(0)),
        _ => String::new(),
    })
}
fn d_true() -> bool { true }
fn d_sound() -> String { "off".into() }
fn d_sound_key() -> String { "scrolllock".into() }
fn d_kbps() -> u32 { 128 }
fn d_viewers() -> u32 { 10 }
fn d_upload() -> f64 { 40.0 }
fn d_dink_port() -> u16 { 8796 }
fn d_one() -> u32 { 1 }
fn d_clip_gp() -> u64 { 1_000_000 }

/// A request for a portal, waiting for the keeper's answer (ask.rs): kept so it outlives closing the portal.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Asked {
    /// the keeper's relay, as http(s)
    pub relay: String,
    pub channel: String,
    /// the name as it was typed
    pub player: String,
    /// the secret that collects the answer (the relay keeps only its SHA-256)
    pub pickup: String,
    /// when it was asked (seconds since 1970)
    pub at: u64,
}

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
    /// a clip saved by itself when Dink tells of something big (a pet, a new collection log slot, a 99, a drop worth
    /// clip_gp or more), while live and with the replay buffer on (dink.rs, big_moment)
    #[serde(default = "d_true")]
    pub auto_clip: bool,
    #[serde(default = "d_clip_gp")]
    pub clip_gp: u64,
    /// and each one put on the player's Outside page too (clipsup.rs): a standard copy, off to begin with
    #[serde(default)]
    pub clips_up: bool,
    /// and each one sent to Discord under Dink's post, where that post went (dink.rs, 11/07/2026: "Dink, but with
    /// video"): the same standard copy, on to begin with, and only ever where Dink's post was going anyway
    #[serde(default = "d_true")]
    pub clips_discord: bool,
    /// the drawer's first question, "do you use Dink with Discord too?": not answered yet, yes or no
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub discord: Option<bool>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for Dink {
    fn default() -> Self {
        Dink { port: d_dink_port(), kinds: Map::new(), loot_min: 0, kc_every: 1, auto_clip: true, clip_gp: d_clip_gp(), clips_up: false, clips_discord: true, discord: None, extra: Map::new() }
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
    /// the broadcast's title (11/06/2026): beside "✼ portal" on Outside, scrolling slowly when it's too long to fit;
    /// empty: none. It goes out through the desk ({"title"}), at most 100 characters.
    #[serde(default)]
    pub stream_title: String,
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
    /// the static key (round two: it replaced the pause key): viewers see static, the sound and chat go on
    #[serde(default = "d_static_key", alias = "pause_key", deserialize_with = "de_key")]
    pub static_key: String,
    /// the clip key: the replay buffer saved as a clip
    #[serde(default = "d_clip_key", deserialize_with = "de_key")]
    pub clip_key: String,
    /// your own masks (11/05/2026, Jace: "mask a custom area of their screen if there is something they don't want shown
    /// on their client"): boxes blacked out of every frame before it leaves this PC, each [x, y, w, h] as a share of the
    /// frame (so they stay put when RuneLite is resized)
    #[serde(default)]
    pub masks: Vec<[f64; 4]>,
    /// whether they're on (the mask key turns them off and on)
    #[serde(default = "d_true")]
    pub masks_on: bool,
    #[serde(default = "d_mask_key", deserialize_with = "de_key")]
    pub mask_key: String,
    /// the replay buffer: the last 0 to 30 seconds kept here, for a clip (0 keeps nothing)
    #[serde(default)]
    pub replay_seconds: u32,
    /// dark but recording (11/07/2026): while dark, the tape keeps rolling (the capture runs, nothing's sent), so clips
    /// still save themselves, to this PC and Discord; off to begin with
    #[serde(default)]
    pub tape_dark: bool,
    /// clips only (11/07/2026, Jace: an easier route "for people who are only downloading/using the program to record
    /// automatic clips"): no channel, no key; the window is the tape, Dink and the clips
    #[serde(default)]
    pub clips_only: bool,
    /// where clips go (empty: Videos\outstation)
    #[serde(default)]
    pub clip_folder: String,
    #[serde(default)]
    pub only_logged_in: bool,
    /// the mouse pointer in the picture, where it's over RuneLite (11/06/2026; off: the picture never has it).
    /// Windows Graphics Capture draws it; PrintWindow, the fallback, can't.
    #[serde(default)]
    pub show_cursor: bool,
    /// go live / end stream (off for a new portal: 11/06/2026, it had begun live, so attaching a code streamed at once,
    /// before the first-time setup was done; a portal left live stays live as it was)
    #[serde(default)]
    pub on_air: bool,
    /// "off", "game" or "all"
    #[serde(default = "d_sound")]
    pub sound: String,
    #[serde(default = "d_sound_key", deserialize_with = "de_key")]
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
    /// minimising the window sends it to the tray (the stream goes on)
    #[serde(default = "d_true")]
    pub minimize_to_tray: bool,
    /// the ready check above the title while off air (RuneLite, logged in, the relay, Dink, the sound)
    #[serde(default = "d_true")]
    pub ready_check: bool,
    /// the keeper's last notice shown (its "at"), so a reconnect or a restart doesn't show it again
    #[serde(default)]
    pub notice_seen: u64,
    /// the first-time setup (ui.rs, setup_screen), from attaching till it's finished or skipped: which step it's at
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub setup_step: Option<u8>,
    /// go live when RuneLite opens (autolive.rs), and with it end the stream when RuneLite closes
    #[serde(default)]
    pub auto_live: bool,
    #[serde(default = "d_true")]
    pub auto_end: bool,
    /// the friends list (friendlist.rs): players' names as typed, in the order added
    #[serde(default)]
    pub friends: Vec<String>,
    /// a line in the chat when a friend goes live or off air
    #[serde(default = "d_true")]
    pub friends_tell: bool,
    /// and Windows' own notification when one goes live (notify.rs; it needs the Start menu's shortcut)
    #[serde(default = "d_true")]
    pub friends_toast: bool,
    /// the Start menu's shortcut (shortcut.rs): not asked yet, yes (kept pointing at this copy), or no
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub start_menu: Option<bool>,
    /// the live view: the portal as the site gets it, small, in the window (off: not connected at all)
    #[serde(default = "d_true")]
    pub live_view: bool,
    /// viewers' ✼ (the signal button) shown over RuneLite
    #[serde(default = "d_true")]
    pub signals: bool,
    /// the whole window's size: text, buttons and all (1.0 as made)
    #[serde(default = "d_scale")]
    pub ui_scale: f32,
    /// viewers may ping at all (the desk's {"pings"})
    #[serde(default = "d_true")]
    pub pings_allowed: bool,
    /// viewers may chat at all (the desk's {"chat_on"}; the host's own lines go out either way)
    #[serde(default = "d_true")]
    pub chat_allowed: bool,
    /// the look (theme.rs): the browns' tint, taupe 0 to brown 1 (none yet: jute's on this PC, else taupe), and
    /// anyone's own colours, a role's key to "#rrggbb"
    #[serde(default)]
    pub tint: Option<f32>,
    #[serde(default)]
    pub colours: std::collections::BTreeMap<String, String>,
    /// the chat over the game, and where it sits (its top-left as a share of the game's width and height)
    #[serde(default = "d_true")]
    pub game_chat: bool,
    #[serde(default)]
    pub game_chat_at: Option<[f64; 2]>,
    /// the chat's text size: in this window, and over the game
    #[serde(default = "d_chat_size")]
    pub chat_size: f32,
    #[serde(default = "d_game_chat_size")]
    pub game_chat_size: f32,
    /// how long a line over the game stays before it fades out, in seconds (0: it stays till newer ones push it off;
    /// 11/07/2026, Jace: "make the chat on screen fade out after a certain amount of time")
    #[serde(default = "d_game_chat_fade")]
    pub game_chat_fade: u32,
    /// the chat over the game drawn into the picture too, where it sits over the game and as big (11/07/2026, Jace:
    /// "add a toggle to capture chat on the broadcast screen"): drawn on this PC into each frame (chatpic.rs), so
    /// observers, the clips and the tape all have it. Off to begin with.
    #[serde(default)]
    pub chat_in_picture: bool,
    /// a drop from the guessed boss worth this much decides a guess round
    #[serde(default = "d_guess_min")]
    pub guess_min_value: u64,
    #[serde(default)]
    pub dink: Dink,
    /// look on GitHub for a newer version when the window opens (it never updates itself)
    #[serde(default = "d_true")]
    pub check_updates: bool,
    /// forward.py's rules: {"discord": [webhook…], "forward": {"LEVEL": true, "LOOT": {…}, …}}
    #[serde(default)]
    pub forward: Value,
    /// a request for a portal waiting for the keeper (the attach screen's "ask for one"; ask.rs)
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub asked: Option<Asked>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for Settings {
    fn default() -> Self {
        serde_json::from_str("{}").unwrap()
    }
}

impl Settings {
    /// clips only, and not attached to a channel: the window shows the tape and the clips, not the transmission
    pub fn clips_mode(&self) -> bool {
        self.clips_only && !self.attached()
    }
    /// the tape kept while dark: asked for, and with some seconds to keep
    pub fn taping_dark(&self) -> bool {
        self.tape_dark && self.replay_seconds > 0
    }
    pub fn attached(&self) -> bool {
        !self.relay.is_empty() && !self.channel.is_empty() && self.key.len() >= 8
    }
    pub fn host_name(&self) -> String {
        if self.chat_name.trim().is_empty() { self.channel.clone() } else { self.chat_name.trim().to_string() }
    }
    /// the relay's address for one of its sockets, with this client's version (v=): a relay that needs a newer
    /// client turns it away with 4026 and says which (docs/protocol.md, "The client's version")
    pub fn socket_url(&self, role: &str, query: &str) -> String {
        let sep = if query.is_empty() { "" } else { "&" };
        format!("{}/{}/{}?{}{}v={}", self.relay.trim_end_matches('/'), role, enc(&self.channel), query, sep, enc(VERSION))
    }
    /// its http(s) address (for Dink's posts)
    pub fn http_base(&self) -> String {
        let r = self.relay.trim_end_matches('/');
        if let Some(rest) = r.strip_prefix("wss://") { format!("https://{}", rest) }
        else if let Some(rest) = r.strip_prefix("ws://") { format!("http://{}", rest) }
        else { r.to_string() }
    }
}

/// this client's version (client/Cargo.toml's, which each release's tag must match)
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// where the releases are, for "a newer one is out" and "too old for the relay" (shown, never opened or fetched)
pub const RELEASES: &str = "github.com/jhmcubed/outstation/releases";
/// what the portal does and doesn't, line by line, and how to check it (the attach screen's link)
pub const SAFETY_PAGE: &str = "https://github.com/jhmcubed/outstation/blob/main/SAFETY.md";

/// The words for a relay's 4026 (this client too old for it), from the relay's own reason.
pub fn too_old_note(reason: &str) -> String {
    let why = if reason.trim().is_empty() { "this outstation is too old for the relay" } else { reason.trim() };
    format!("{} (this is {}): get the newest at {}", why, VERSION, RELEASES)
}

pub fn enc(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) { out.push(b as char) } else { out.push_str(&format!("%{:02X}", b)) }
    }
    out
}

/// The program's own name, as Windows and the window show it (its files and folders are plain `outstation`).
pub const NAME: &str = "⊂λ,ｍ⊙ outstation";

/// The key's prefix (11/06/2026: the attach code is the station's key now, "outstation1:"); a key from before,
/// "portal1:", is the same inside and still read.
pub const KEY_PREFIX: &str = "outstation1:";
pub const OLD_KEY_PREFIX: &str = "portal1:";
pub fn is_key(s: &str) -> bool {
    let s = s.trim();
    s.starts_with(KEY_PREFIX) || s.starts_with(OLD_KEY_PREFIX)
}

pub fn default_path() -> PathBuf {
    let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    let now = base.join("outstation");
    // (11/06/2026, the name's outstation: the folder it had as outside-portal moved once, settings, log and all)
    let old = base.join("outside-portal");
    if !now.exists() && old.is_dir() {
        let _ = std::fs::rename(&old, &now);
    }
    now.join("settings.json")
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

/// This outstation's own station key, made again from its settings (11/07/2026: for the Outside plugin's settings).
pub fn make_attach(relay: &str, channel: &str, key: &str) -> String {
    use base64::Engine;
    let body = serde_json::json!({ "relay": relay, "channel": channel, "key": key }).to_string();
    format!("{}{}", KEY_PREFIX, base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(body))
}

/// A station key (the attach code): "outstation1:" (or, from before, "portal1:") and base64url JSON {"relay",
/// "channel", "key"}.
pub fn parse_attach(code: &str) -> Result<(String, String, String), String> {
    use base64::Engine;
    let code = code.trim();
    let raw = code.strip_prefix(KEY_PREFIX).or_else(|| code.strip_prefix(OLD_KEY_PREFIX))
        .ok_or_else(|| format!("that isn't a station key (it starts {})", KEY_PREFIX))?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(raw.trim_end_matches('=').as_bytes())
        .map_err(|_| "the station key is cut short or changed".to_string())?;
    let v: Value = serde_json::from_slice(&bytes).map_err(|_| "the station key is cut short or changed".to_string())?;
    let get = |k: &str| v.get(k).and_then(|x| x.as_str()).map(|s| s.to_string());
    match (get("relay"), get("channel"), get("key")) {
        (Some(r), Some(c), Some(k)) if (r.starts_with("ws://") || r.starts_with("wss://")) && !c.is_empty() && k.len() >= 8 => Ok((r, c, k)),
        _ => Err("the station key is missing its relay, channel or key".into()),
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_attach_code_reads_back() {
        use base64::Engine;
        let json = r#"{"relay": "wss://outside-portal.x.workers.dev", "channel": "ja", "key": "abcdefghijkl"}"#;
        let body = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json);
        // (a key now, and one from before the name was outstation: the same inside)
        for code in [format!("outstation1:{}", body), format!("portal1:{}", body)] {
            assert!(is_key(&code));
            assert_eq!(parse_attach(&code).unwrap(), ("wss://outside-portal.x.workers.dev".into(), "ja".into(), "abcdefghijkl".into()));
        }
        assert!(parse_attach("outstation1:!!!").is_err());
        // (its own key, made again for the Outside plugin, reads back the same)
        let made = make_attach("wss://relay.ghillie.workers.dev", "sburb", "abcdefghijkl");
        assert_eq!(parse_attach(&made).unwrap(), ("wss://relay.ghillie.workers.dev".into(), "sburb".into(), "abcdefghijkl".into()));
        assert!(!is_key("hello"));
        assert!(parse_attach("hello").is_err());
    }

    #[test]
    fn unknown_fields_are_kept_and_defaults_fill_in() {
        let s: Settings = serde_json::from_str(r#"{"channel": "ja", "later": 5, "dink": {"port": 9000, "soon": true}}"#).unwrap();
        assert_eq!((s.fps, s.width, s.quality, s.dink.port), (8, 384, 60, 9000));
        // (11/07/2026: the chat over the game fades after 20 s, and isn't in the picture, to begin with)
        assert_eq!((s.game_chat_fade, s.chat_in_picture), (20, false));
        let back = serde_json::to_value(&s).unwrap();
        assert_eq!(back["later"], 5);
        assert_eq!(back["dink"]["soon"], true);
    }

    #[test]
    fn urls() {
        let mut s = Settings::default();
        s.relay = "wss://r.example/".into();
        s.channel = "a b".into();
        assert_eq!(s.socket_url("watch", "code="), format!("wss://r.example/watch/a%20b?code=&v={}", VERSION));
        assert_eq!(s.socket_url("sound", ""), format!("wss://r.example/sound/a%20b?v={}", VERSION));
        assert_eq!(s.http_base(), "https://r.example");
    }
}
