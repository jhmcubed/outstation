//! Your year (11/10/2026; docs/record.md): this PC's record of the player's travels and activity, and its encrypted copy
//! on the relay.
//!
//! The record: what the relay passes this outstation's own desk ({"mine"}: following's square, what they're doing,
//! their Array events) and the status line's live or dark, one JSON line each, appended to
//! %APPDATA%\outstation\record\<YYYY-MM>.jsonl (desk.rs hands each line here). Only while the app's open; nothing finer
//! than the relay already has, but the exact path (`here`, with the plugin's "Record my exact path for my year" on,
//! 11/10/2026), which the relay passes only to this desk and never keeps. Plain JSON on this PC: it's the player's own.
//!
//! The copy (with "keep an encrypted copy on the relay" on): each day's lines from this PC, sealed with the record key
//! (recordfmt.rs: AES-256-GCM, a key only the player's own computers hold) and uploaded under an id that says nothing,
//! hourly, on going dark, and when asked. The relay keeps ciphertext only. "open my year" opens the Outside page's year
//! view with the key in the address's fragment (#k=…), which the browser never sends to any server.

use crate::recordfmt as fmt;
use crate::settings::{enc, Settings, Shared};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// The year view (docs/record.md, "Viewing"): the player's Outside page, its year, the key after "#k=".
pub const YEAR_PAGE: &str = "https://ghillie.wiki/outside";
/// a heartbeat at most this often while a place or an activity is held (docs/record.md, "The local record")
const HEARTBEAT_MS: i64 = 60_000;
/// the exact path (docs/record.md, "The exact path"): one `here` line written at most this often; one that comes sooner
/// waits (the latest of them, with its own time) and is written once the time's up, so the tile they stopped on is never
/// lost
const HERE_MS: i64 = 3_000;
/// the copy brought up to date this often while the app runs (and on going dark, and when asked)
const SYNC_EVERY: Duration = Duration::from_secs(3600);
const FIRST_SYNC: Duration = Duration::from_secs(60);

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// The record's folder: `record` beside the settings file.
pub fn folder(settings: &Shared) -> PathBuf {
    settings.path.with_file_name("record")
}

/// The record key from the settings, if there's one and it reads.
pub fn key_of(s: &Settings) -> Option<fmt::Key> {
    if s.record_key.trim().is_empty() { None } else { fmt::parse_key(&s.record_key).ok() }
}

/// The year view's address for this outstation, with its key in the fragment.
pub fn year_url(s: &Settings, k: &fmt::Key) -> String {
    format!("{}?player={}&view=year#k={}", YEAR_PAGE, enc(&s.channel), fmt::key_b64u(k))
}

// ---- the recorder ----

#[derive(Default)]
struct Held {
    /// what was last written of each (None: nothing yet since the desk came up)
    place: Option<Value>,
    activity: Option<Value>,
    /// the exact path's last `here` line (its value, its `t`, and when it was written by this PC's clock), and the
    /// latest that came too soon after it (its `at`, its value), waiting
    here: Option<Value>,
    here_t: i64,
    here_wrote: i64,
    here_waiting: Option<(i64, Value)>,
    live: Option<bool>,
    /// when a place or activity line was last written (this PC's clock), for the heartbeat
    last: i64,
    up: bool,
}

pub struct Recorder {
    dir: PathBuf,
    on: AtomicBool,
    held: Mutex<Held>,
    /// lines written since the app opened (for the settings' line)
    pub written: std::sync::atomic::AtomicU64,
}

impl Recorder {
    fn new(dir: PathBuf) -> Recorder {
        Recorder { dir, on: AtomicBool::new(false), held: Mutex::new(Held::default()), written: Default::default() }
    }

    fn write(&self, l: &str, t: i64) {
        let path = self.dir.join(fmt::month_file(t));
        let ok = std::fs::create_dir_all(&self.dir).and_then(|_| {
            use std::io::Write;
            let mut f = std::fs::OpenOptions::new().create(true).append(true).open(&path)?;
            f.write_all(format!("{}\n", l).as_bytes())
        });
        match ok {
            Ok(()) => { self.written.fetch_add(1, Ordering::Relaxed); }
            Err(e) => crate::transmit::log(&format!("your year: couldn't write {} ({})", path.display(), e)),
        }
    }

    /// The desk's connected: what it says from now on is recorded (with the settings' switch on).
    pub fn up(&self) {
        let mut h = self.held.lock().unwrap();
        *h = Held { up: true, ..Held::default() };
    }

    /// The desk's gone: nothing more till it's back (no heartbeat for a place nobody's telling us of).
    pub fn down(&self) {
        *self.held.lock().unwrap() = Held::default();
    }

    /// The settings' switch, and the heartbeat (desk.rs, about every half second while the desk is up).
    pub fn tick(&self, s: &Settings) {
        self.on.store(s.record, Ordering::Relaxed);
        if !s.record { return; }
        let now = now_ms();
        let mut h = self.held.lock().unwrap();
        if !h.up { return; }
        // (a `here` that came too soon after the last: written once its time's up)
        if h.here_waiting.is_some() && now - h.here_wrote >= HERE_MS {
            let (t, v) = h.here_waiting.take().unwrap();
            h.here = Some(v.clone());
            h.here_t = t;
            h.here_wrote = now;
            drop(h);
            self.write(&fmt::line(t, &[("here", &v)]), t);
            h = self.held.lock().unwrap();
        }
        if now - h.last < HEARTBEAT_MS { return; }
        let place = h.place.clone().filter(|v| !v.is_null());
        let activity = h.activity.clone().filter(|v| !v.is_null());
        let here = h.here.clone().filter(|v| !v.is_null());
        if place.is_none() && activity.is_none() && here.is_none() { return; }
        let mut fields: Vec<(&str, &Value)> = Vec::new();
        if let Some(p) = &place { fields.push(("where", p)); }
        if let Some(a) = &activity { fields.push(("activity", a)); }
        if let Some(x) = &here { fields.push(("here", x)); }
        h.last = now;
        drop(h);
        self.write(&fmt::line(now, &fields), now);
    }

    /// The exact path's `here` (docs/record.md, "The exact path"): written as it comes, but at most one line every
    /// HERE_MS (one sooner waits, the latest of them, for tick); null at once, if a tile was the last said.
    fn take_here(&self, new: &Value, at: i64, now: i64) {
        let mut h = self.held.lock().unwrap();
        if new.is_null() {
            h.here_waiting = None;
            if !h.here.as_ref().is_some_and(|v| !v.is_null()) { return; }
        } else if h.here.as_ref() == Some(new) {
            h.here_waiting = None;
            return;
        } else if h.here.is_some() && at - h.here_t < HERE_MS {
            h.here_waiting = Some((at, new.clone()));
            return;
        }
        h.here = Some(new.clone());
        h.here_t = at;
        h.here_wrote = now;
        h.last = now;
        drop(h);
        self.write(&fmt::line(at, &[("here", new)]), at);
    }

    /// A line from the desk: {"mine": {...}} (the owner's own, docs/record.md) or a status line ({"relay": {"live"}}).
    pub fn take(&self, v: &Value) {
        if !self.on.load(Ordering::Relaxed) { return; }
        let now = now_ms();
        if let Some(m) = v.get("mine").filter(|m| m.is_object()) {
            let at = m.get("at").and_then(|x| x.as_i64()).filter(|t| *t > 0).unwrap_or(now);
            if let Some(e) = m.get("event").filter(|e| e.is_object()) {
                self.write(&fmt::line(at, &[("event", e)]), at);
            }
            if let Some(new) = m.get("here").filter(|x| x.is_null() || x.is_object()) {
                self.take_here(new, at, now);
            }
            for (field, which) in [("where", 0), ("activity", 1)] {
                let Some(new) = m.get(field) else { continue };
                if !(new.is_null() || new.is_object()) { continue; }
                let mut h = self.held.lock().unwrap();
                let slot = if which == 0 { &mut h.place } else { &mut h.activity };
                if slot.as_ref() == Some(new) { continue; }
                *slot = Some(new.clone());
                h.last = now;
                drop(h);
                self.write(&fmt::line(at, &[(field, new)]), at);
            }
        } else if let Some(live) = v.get("relay").and_then(|r| r.get("live")).and_then(|x| x.as_bool()) {
            let mut h = self.held.lock().unwrap();
            if !h.up || h.live == Some(live) { return; }
            h.live = Some(live);
            drop(h);
            self.write(&fmt::line(now, &[("live", &Value::Bool(live))]), now);
        }
    }
}

// ---- the copy on the relay ----

#[derive(Clone, Default)]
pub struct SyncStatus {
    pub busy: bool,
    /// the last pass: when, and what it said (or why it failed)
    pub last: Option<(Instant, Result<String, String>)>,
}

pub struct Year {
    pub rec: Arc<Recorder>,
    pub status: Mutex<SyncStatus>,
    /// asked to bring the copy up to date now (a key pasted, "open my year")
    asked: AtomicBool,
    /// and to open the year view once it's done
    open_after: AtomicBool,
}

static YEAR: OnceLock<Arc<Year>> = OnceLock::new();

/// The recorder desk.rs hands its lines to (None before start).
pub fn recorder() -> Option<Arc<Recorder>> {
    YEAR.get().map(|y| y.rec.clone())
}

pub fn year() -> Option<Arc<Year>> {
    YEAR.get().cloned()
}

/// The recorder, and the copy's own thread.
pub fn start(settings: Shared) {
    let y = Arc::new(Year { rec: Arc::new(Recorder::new(folder(&settings))), status: Mutex::new(SyncStatus::default()),
                            asked: AtomicBool::new(false), open_after: AtomicBool::new(false) });
    if YEAR.set(y.clone()).is_err() { return; }
    std::thread::spawn(move || sync_loop(settings, y));
}

impl Year {
    /// Bring the relay's copy up to date now (and, with `open`, open the year view after).
    pub fn ask(&self, open: bool) {
        if open { self.open_after.store(true, Ordering::Relaxed); }
        self.asked.store(true, Ordering::Relaxed);
    }
}

fn sync_loop(settings: Shared, y: Arc<Year>) {
    let started = Instant::now();
    let mut last: Option<Instant> = None;
    let mut was_live = settings.get().on_air;
    let mut registered = String::new();
    loop {
        std::thread::sleep(Duration::from_secs(2));
        let s = settings.get();
        let going_dark = was_live && !s.on_air;
        was_live = s.on_air;
        let asked = y.asked.swap(false, Ordering::Relaxed);
        let open = y.open_after.load(Ordering::Relaxed) && asked;
        let due = asked || going_dark || match last { None => started.elapsed() >= FIRST_SYNC, Some(t) => t.elapsed() >= SYNC_EVERY };
        if !due { continue; }
        let Some(k) = key_of(&s).filter(|_| s.record_sync && s.attached()) else {
            if open {
                y.open_after.store(false, Ordering::Relaxed);
                *y.status.lock().unwrap() = SyncStatus { busy: false, last: Some((Instant::now(), Err(
                    "the year view reads the relay's encrypted copy: switch \"keep an encrypted copy on the relay\" on first".into()))) };
            }
            continue;
        };
        last = Some(Instant::now());
        y.status.lock().unwrap().busy = true;
        let got = pass(&s, &k, &y.rec.dir, &mut registered);
        if let Err(e) = &got { crate::transmit::log(&format!("your year: {}", e)); }
        *y.status.lock().unwrap() = SyncStatus { busy: false, last: Some((Instant::now(), got)) };
        if open {
            y.open_after.store(false, Ordering::Relaxed);
            let url = year_url(&s, &k);
            if !crate::win::open_page(&url) {
                crate::transmit::log("your year: couldn't open the year view");
            }
        }
    }
}

/// What's been uploaded (sent.json): for which relay, channel and key; each month file's size when it was last read
/// through; each day's digest as last uploaded.
#[derive(serde::Serialize, serde::Deserialize, Default)]
struct Sent {
    #[serde(default, rename = "for")]
    for_: String,
    #[serde(default)]
    files: std::collections::BTreeMap<String, u64>,
    #[serde(default)]
    days: std::collections::BTreeMap<String, String>,
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

fn sha(b: &[u8]) -> String {
    hex(ring::digest::digest(&ring::digest::SHA256, b).as_ref())
}

/// This PC's device id (16 hex characters, random, made once), in the record's folder: not in the settings, so a
/// settings file copied to another PC doesn't make two PCs one.
pub fn device(dir: &Path) -> String {
    let path = dir.join("device");
    if let Ok(d) = std::fs::read_to_string(&path) {
        let d = d.trim().to_string();
        if d.len() == 16 && d.chars().all(|c| c.is_ascii_hexdigit()) { return d; }
    }
    use ring::rand::SecureRandom;
    let mut b = [0u8; 8];
    let _ = ring::rand::SystemRandom::new().fill(&mut b);
    let d = hex(&b);
    let _ = std::fs::create_dir_all(dir).and_then(|_| std::fs::write(&path, &d));
    d
}

fn http(r: Result<ureq::http::Response<ureq::Body>, ureq::Error>, base: &str) -> Result<(u16, String), String> {
    let mut r = r.map_err(|_| format!("the relay ({}) isn't answering", base))?;
    let status = r.status().as_u16();
    Ok((status, r.body_mut().read_to_string().unwrap_or_default()))
}

fn why(status: u16, text: &str) -> String {
    serde_json::from_str::<Value>(text).ok().and_then(|v| v.get("error").and_then(|x| x.as_str()).map(|x| x.to_string()))
        .unwrap_or_else(|| if status == 404 { "this relay doesn't keep records".into() } else { format!("the relay answered {}", status) })
}

/// One pass: the tokens registered (once a run for each key), then each day whose lines changed since its last upload,
/// sealed and sent. What it did, or why it stopped.
fn pass(s: &Settings, k: &fmt::Key, dir: &Path, registered: &mut String) -> Result<String, String> {
    let base = s.http_base();
    let fp = sha(format!("{}\n{}\n{}", base, s.channel, fmt::read_token(k)).as_bytes())[..16].to_string();
    if *registered != fp {
        let url = format!("{}/record/{}/token?key={}", base, enc(&s.channel), enc(&s.key));
        let body = serde_json::json!({ "read": fmt::read_token(k), "write": fmt::write_token(k) }).to_string();
        let (status, text) = http(ureq::post(&url).header("User-Agent", "outstation").header("Content-Type", "application/json")
            .config().http_status_as_error(false).build().send(body.as_bytes()), &base)?;
        if status != 204 { return Err(format!("the relay wouldn't take the record key's tokens: {}", why(status, &text))); }
        *registered = fp.clone();
    }
    let sent_path = dir.join("sent.json");
    let mut sent: Sent = std::fs::read_to_string(&sent_path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
    if sent.for_ != fp { sent = Sent { for_: fp.clone(), ..Sent::default() }; }
    let dev = device(dir);
    let mut months: Vec<(String, u64)> = std::fs::read_dir(dir).map(|rd| rd.filter_map(|e| e.ok()).filter_map(|e| {
        let name = e.file_name().to_string_lossy().to_string();
        let ok = name.len() == 13 && name.ends_with(".jsonl");
        ok.then(|| (name, e.metadata().map(|m| m.len()).unwrap_or(0)))
    }).collect()).unwrap_or_default();
    months.sort();
    let (mut days_up, mut bytes_up) = (0usize, 0usize);
    let save = |sent: &Sent| {
        let tmp = sent_path.with_extension("json.tmp");
        let _ = std::fs::write(&tmp, serde_json::to_string(sent).unwrap_or_default()).and_then(|_| std::fs::rename(&tmp, &sent_path));
    };
    for (name, size) in months {
        if sent.files.get(&name) == Some(&size) { continue; }
        let text = std::fs::read_to_string(dir.join(&name)).map_err(|e| format!("couldn't read {} ({})", name, e))?;
        let mut days: std::collections::BTreeMap<String, Vec<String>> = Default::default();
        for l in text.lines() {
            if let Some(t) = fmt::line_t(l) { days.entry(fmt::day_of(t)).or_default().push(l.to_string()); }
        }
        for (day, lines) in days {
            let digest = sha(lines.join("\n").as_bytes())[..32].to_string();
            if sent.days.get(&day) == Some(&digest) { continue; }
            for (part, plain) in fmt::day_parts(&dev, &day, &lines).into_iter().enumerate() {
                let id = fmt::chunk_id(k, &dev, &day, part);
                let sealed = fmt::seal(k, &id, &plain);
                let url = format!("{}/record/{}/{}?key={}", base, enc(&s.channel), id, enc(&s.key));
                let (status, text) = http(ureq::put(&url).header("User-Agent", "outstation").header("Content-Type", "application/octet-stream")
                    .config().http_status_as_error(false).build().send(sealed.as_slice()), &base)?;
                if status != 201 {
                    save(&sent);
                    return Err(format!("the relay didn't keep {}: {}", day, why(status, &text)));
                }
                bytes_up += sealed.len();
            }
            sent.days.insert(day, digest);
            days_up += 1;
        }
        sent.files.insert(name, size);
        save(&sent);
    }
    Ok(if days_up == 0 { "the relay's copy is up to date".into() }
       else { format!("{} day{} sent ({:.1} KB, encrypted)", days_up, if days_up == 1 { "" } else { "s" }, bytes_up as f64 / 1024.0) })
}

/// "delete my record": the relay's copy (every chunk, the tokens, the public summary) and, with `files`, this PC's
/// record too (its device id stays). On the caller's thread: what to say, or why not.
pub fn delete(s: &Settings, dir: &Path, files: bool) -> Result<String, String> {
    let mut said = Vec::new();
    if s.attached() {
        let base = s.http_base();
        let url = format!("{}/record/{}?key={}", base, enc(&s.channel), enc(&s.key));
        let (status, text) = http(ureq::delete(&url).header("User-Agent", "outstation").config().http_status_as_error(false).build().call(), &base)?;
        if status != 204 && status != 404 { return Err(format!("the relay didn't delete it: {}", why(status, &text))); }
        said.push("the relay's copy is gone (and your public year, if you'd posted one)");
    }
    let _ = std::fs::remove_file(dir.join("sent.json"));
    if files {
        if let Ok(rd) = std::fs::read_dir(dir) {
            for e in rd.filter_map(|e| e.ok()) {
                let n = e.file_name().to_string_lossy().to_string();
                if n.ends_with(".jsonl") { let _ = std::fs::remove_file(e.path()); }
            }
        }
        said.push("this PC's record is gone");
    }
    Ok(said.join("; "))
}

/// How much this PC's record holds: (days, bytes).
pub fn local_size(dir: &Path) -> (usize, u64) {
    let mut bytes = 0;
    let mut days = std::collections::BTreeSet::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.filter_map(|e| e.ok()) {
            let n = e.file_name().to_string_lossy().to_string();
            if !n.ends_with(".jsonl") { continue; }
            bytes += e.metadata().map(|m| m.len()).unwrap_or(0);
            if let Ok(t) = std::fs::read_to_string(e.path()) {
                for l in t.lines() { if let Some(t) = fmt::line_t(l) { days.insert(fmt::day_of(t)); } }
            }
        }
    }
    (days.len(), bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn lines(dir: &Path) -> Vec<Value> {
        let mut out: Vec<Value> = Vec::new();
        for e in std::fs::read_dir(dir).unwrap() {
            let p = e.unwrap().path();
            if p.extension().is_some_and(|x| x == "jsonl") {
                for l in std::fs::read_to_string(&p).unwrap().lines() { out.push(serde_json::from_str(l).unwrap()); }
            }
        }
        out.sort_by_key(|v| v["t"].as_i64());
        out
    }

    /// The exact path (docs/record.md, "The exact path"): a `here` line as it comes, at most one every 3 s, the
    /// latest that came too soon written once its time's up, the same tile not again, null at once.
    #[test]
    fn here_lines_at_most_one_every_three_seconds() {
        let dir = std::env::temp_dir().join(format!("outstation-record-test-{}", now_ms()));
        let r = Recorder::new(dir.clone());
        r.on.store(true, Ordering::Relaxed);
        r.up();
        let at = 1_794_268_800_000i64;
        let tile = |x: i64| json!({"x": x, "y": 3218, "plane": 0, "world": 302});
        let mine = |h: Value, t: i64| json!({"mine": {"here": h, "at": t}});
        r.take(&mine(tile(3222), at));
        r.take(&mine(tile(3222), at + 3_500));                  // (the same tile: nothing)
        r.take(&mine(tile(3223), at + 4_000));
        r.take(&mine(tile(3224), at + 5_000));                  // (too soon: waits...)
        r.take(&mine(tile(3225), at + 6_000));                  // (...and a later one takes its place)
        {
            let mut h = r.held.lock().unwrap();
            h.here_wrote -= HERE_MS;                            // (its time's up)
        }
        let s = Settings { record: true, ..Settings::default() };
        r.tick(&s);
        r.take(&mine(Value::Null, at + 6_500));                 // (null at once)
        r.take(&mine(Value::Null, at + 7_000));                 // (and once)
        let got: Vec<(i64, Value)> = lines(&dir).into_iter().map(|v| (v["t"].as_i64().unwrap(), v["here"].clone())).collect();
        assert_eq!(got, vec![(at, tile(3222)), (at + 4_000, tile(3223)), (at + 6_000, tile(3225)), (at + 6_500, Value::Null)]);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
