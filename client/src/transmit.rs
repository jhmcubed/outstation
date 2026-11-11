//! The capture's own loop: frames to the relay's transmit socket (capture.py's run(); parity lines 5-11, 13).

use crate::capture::{self, Gpu, Grabber, Options, HOLD_BACK, MAX_FPS, SHARED_CEILING_MBIT};
use crate::net::{Got, Out, Socket, WAITS};
use crate::settings::Shared;
use crate::win::WindowCache;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Clone, Default, Debug)]
pub struct Status {
    /// connected to the relay as its transmitter
    pub connected: bool,
    /// why nothing's going out (None: frames are going)
    pub why: Option<String>,
    pub viewers: u32,
    /// lookers asking for frames (the relay's status line's "lookers", 11/10/2026); None: a relay that doesn't say
    pub lookers: Option<u32>,
    /// the last few seconds: frames a second and KB a frame
    pub fps: f64,
    pub kb: f64,
    /// the last thing worth saying (the log line)
    pub note: String,
    /// stopped for good (4000, 4001, 4003, 4026) until the settings change
    pub stopped: Option<String>,
    /// dark but recording: the tape rolling while dark, nothing sent
    pub taping: bool,
}

pub struct Transmit {
    pub status: Arc<Mutex<Status>>,
    /// the static key's state: no picture goes out, viewers see static, the sound and chat go on
    pub paused: Arc<AtomicBool>,
}

impl Transmit {
    pub fn start(settings: Shared) -> Transmit {
        let status = Arc::new(Mutex::new(Status::default()));
        let paused = Arc::new(AtomicBool::new(false));
        {
            let (status, paused) = (status.clone(), paused.clone());
            std::thread::spawn(move || run(settings, status, paused));
        }
        Transmit { status, paused }
    }
}

// Who's watching (11/10/2026, Jace: test streams left running used up the relay's free Durable Object requests for the
// day; every message a transmitter sends counts, watched or not). With no viewer seated, the picture goes out only one
// frame every IDLE_FRAME_EVERY seconds (so whoever opens it first sees a recent picture at once, and the stream comes in
// full the moment the relay says someone's there), the sound not at all, and the small frames only while lookers ask.
// Nothing changes on this computer: every frame and every packet is still taken and kept for clips.
static WATCHED: AtomicBool = AtomicBool::new(false);
pub const IDLE_FRAME_EVERY: f64 = 10.0;
/// whether anyone's seated watching, as the relay last said (the sound's loop sends only then)
pub fn watched() -> bool {
    WATCHED.load(Ordering::Relaxed)
}

static LOG: std::sync::OnceLock<Mutex<Option<std::fs::File>>> = std::sync::OnceLock::new();

/// The log: portal.log beside the settings (and the console, when there is one).
pub fn set_log(path: std::path::PathBuf) {
    if let Some(d) = path.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    // a log past 2 MB starts again
    if std::fs::metadata(&path).map(|m| m.len() > 2_000_000).unwrap_or(false) {
        let _ = std::fs::rename(&path, path.with_extension("log.old"));
    }
    let f = std::fs::OpenOptions::new().create(true).append(true).open(path).ok();
    let _ = LOG.set(Mutex::new(f));
}

pub fn log(s: &str) {
    eprintln!("{}", s);
    if let Some(m) = LOG.get() {
        if let Some(f) = m.lock().unwrap().as_mut() {
            use std::io::Write;
            let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
            let _ = writeln!(f, "{} {}", t, s);
        }
    }
}

fn say(status: &Arc<Mutex<Status>>, f: impl FnOnce(&mut Status)) {
    f(&mut status.lock().unwrap());
}

fn run(settings: Shared, status: Arc<Mutex<Status>>, paused: Arc<AtomicBool>) {
    let mut gpu = Gpu::new();
    let mut gdi = Grabber::new();
    let mut failures = 0usize;
    let mut told_off = false;
    let mut stopped_for: Option<(String, String, String)> = None; // (relay, channel, key) it stopped on
    loop {
        settings.refresh();
        let s = settings.get();
        let who = (s.relay.clone(), s.channel.clone(), s.key.clone());
        // dark but recording (11/07/2026): dark (or with no channel at all), the tape still rolls if asked: the same
        // capture, nothing sent, the frames only into the replay buffer for clips
        if (!s.on_air || !s.attached()) && s.taping_dark() {
            if !told_off && s.attached() {
                log("off air: the stream's ended (go live from the outstation's window)");
                told_off = true;
            }
            log("dark, the tape rolling: clips only, nothing sent");
            say(&status, |st| { st.connected = false; st.viewers = 0; st.lookers = None; st.taping = true; st.why = Some("off air".into());
                                st.note = "dark · the tape's rolling (clips only, nothing sent)".into(); });
            let ended = live(&settings, &status, &paused, &mut Out::Tape, &mut gpu, &mut gdi);
            say(&status, |st| { st.taping = false; st.fps = 0.0; });
            log(&format!("the tape stopped: {}", ended));
            continue;
        }
        if stopped_for.as_ref().is_some_and(|x| *x == who) || !s.attached() {
            say(&status, |st| { st.connected = false; if !s.attached() { st.why = Some("not attached".into()); } });
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }
        stopped_for = None;
        say(&status, |st| st.stopped = None);
        if !s.on_air {
            // the stream's ended (end stream): nothing goes out, and nothing's held open
            if !told_off {
                log("off air: the stream's ended (go live from the portal's window)");
                told_off = true;
            }
            gpu.stop();
            say(&status, |st| { st.connected = false; st.why = Some("off air".into()); st.viewers = 0; st.lookers = None; });
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }
        if told_off {
            log("going live");
            told_off = false;
        }
        let url = s.socket_url("transmit", &format!("key={}&code={}", crate::settings::enc(&s.key), crate::settings::enc(&s.code)));
        let ws = match Socket::open(&url) {
            Ok(ws) => ws,
            Err(e) => {
                let wait = WAITS[failures.min(WAITS.len() - 1)];
                failures += 1;
                let note = format!("no relay at {} ({}); again in {} s", s.relay, e, wait);
                log(&note);
                say(&status, |st| { st.connected = false; st.note = note; });
                sleep_unless_changed(&settings, &who, wait);
                continue;
            }
        };
        failures = 0;
        log(&format!("transmitting on {} as {} (the Pause key pauses)", s.relay, s.channel));
        say(&status, |st| { st.connected = true; st.note = "transmitting".into(); });
        let mut ws = Out::Relay(ws);
        let closed = live(&settings, &status, &paused, &mut ws, &mut gpu, &mut gdi);
        ws.close();
        log(&format!("off air: {}", closed));
        say(&status, |st| { st.connected = false; st.viewers = 0; st.lookers = None; st.note = format!("off air: {}", closed); });
        if closed.contains("(4000)") {
            // another capture started on this channel: the newest one wins, this one steps aside (reconnecting would
            // only knock that one off in turn)
            log(&format!("another capture took over {}; this one stops", s.channel));
            say(&status, |st| st.stopped = Some("another capture took over this channel".into()));
            stopped_for = Some(who);
            continue;
        }
        if closed.ends_with("(4026)") {
            // this client is older than the relay allows: trying again won't help, a newer one will
            let note = crate::settings::too_old_note(closed.trim_end_matches("(4026)"));
            log(&note);
            say(&status, |st| st.stopped = Some(note.clone()));
            stopped_for = Some(who);
            continue;
        }
        if closed.contains("(4001)") || closed.contains("(4003)") || closed.contains("(4029)") {
            say(&status, |st| st.stopped = Some(closed.clone()));
            stopped_for = Some(who);
            continue;
        }
        if closed == "the stream ended" || closed == "the settings changed" {
            continue;
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn sleep_unless_changed(settings: &Shared, who: &(String, String, String), secs: u64) {
    let end = Instant::now() + Duration::from_secs(secs);
    while Instant::now() < end {
        std::thread::sleep(Duration::from_millis(250));
        settings.refresh();
        let s = settings.get();
        if (s.relay, s.channel, s.key) != *who || !s.on_air {
            return;
        }
    }
}

/// One connection's life: frames out, the status lines in. Why it ended.
fn live(settings: &Shared, status: &Arc<Mutex<Status>>, paused: &Arc<AtomicBool>, ws: &mut Out, gpu: &mut Gpu,
        gdi: &mut Grabber) -> String {
    let taping = ws.taping();
    WATCHED.store(false, Ordering::Relaxed); // (till the relay says otherwise)
    let mut last_full_out = Instant::now() - Duration::from_secs(60);
    let mut taken = 0u32;
    let first = settings.get();
    let who = (first.relay.clone(), first.channel.clone(), first.key.clone(), first.code.clone());
    let mut windows = WindowCache::new(&first.title);
    let mut due = Instant::now();
    let (mut last_state, mut last_state_at, mut last_print) = (String::new(), Instant::now() - Duration::from_secs(10), Instant::now());
    let (mut sent, mut bytes_sent) = (0u32, 0usize);
    let mut held: VecDeque<(Instant, Vec<u8>)> = VecDeque::new();
    let mut last_thumb = Instant::now() - Duration::from_secs(5);
    let mut last_seq: Option<u64> = None;
    let mut last_refresh = Instant::now();
    // the shared relay's ceiling (capture.rs, SHARED_CEILING_MBIT): bytes allowed, refilled as time passes, at most a
    // second's worth saved up; a frame that would overdraw it waits for the next one
    let shared = first.relay.starts_with("wss://") && !taping;
    let ceiling = SHARED_CEILING_MBIT * 1e6 / 8.0;
    let (mut allowance, mut allowance_at) = (ceiling, Instant::now());
    let mut over_ceiling = 0u32;
    loop {
        // the relay's lines: how many watch, or a close (only those already here: the frames never wait on them)
        loop {
            match ws.try_recv() {
                Ok(Got::Text(t)) => {
                    if let Ok(v) = serde_json::from_str::<serde_json::Value>(&t) {
                        if let Some(n) = v.pointer("/relay/viewers").and_then(|x| x.as_u64()) {
                            say(status, |st| st.viewers = n as u32);
                            WATCHED.store(n > 0, Ordering::Relaxed);
                        }
                        if v.pointer("/relay/channel").is_some() {
                            let l = v.pointer("/relay/lookers").and_then(|x| x.as_u64()).map(|n| n as u32);
                            say(status, |st| st.lookers = l);
                        }
                    }
                }
                Ok(Got::Closed(code, why)) if !why.is_empty() => return format!("{} ({})", why, code),
                Ok(Got::Closed(code, _)) => return format!("the relay closed it ({})", code),
                Ok(Got::Nothing) => break,
                Ok(Got::Binary(_)) => {}
                Err(e) => return e,
            }
        }
        if last_refresh.elapsed() > Duration::from_millis(250) {
            settings.refresh();
            last_refresh = Instant::now();
        }
        let s = settings.get();
        crate::clip::set_seconds(s.replay_seconds);
        if taping {
            // (the tape, while dark: it gives way the moment the outstation transmits, or the tape's turned off)
            if s.on_air && s.attached() {
                return "going live".into();
            }
            if !s.taping_dark() {
                return "the tape's off".into();
            }
        } else {
            if !s.on_air {
                return "the stream ended".into();
            }
            if (s.relay.clone(), s.channel.clone(), s.key.clone(), s.code.clone()) != who {
                return "the settings changed".into();
            }
        }
        if windows.prefix != s.title {
            windows = WindowCache::new(&s.title);
        }
        let o = Options::of(&s);
        let every = 1.0 / s.fps.clamp(1, MAX_FPS) as f64;
        let (hwnd, title) = windows.get(false);
        let mut why = capture::gate(hwnd, &title, &s, paused.load(Ordering::Relaxed));
        // from the GPU, frames are kept to its own schedule (taken evenly, at the rate asked), and each new one goes
        // as soon as it's kept
        let gpu_on = hwnd.is_some_and(|h| gpu.running(h)) && s.method != "gdi";
        let mut wait_gpu = false;
        if why.is_none() && gpu_on {
            let got = gpu.newest(1.0);
            wait_gpu = got.as_ref().map_or(true, |g| g.seq == last_seq);
        }
        if why.is_none() && !wait_gpu {
            let h = hwnd.unwrap();
            match capture::frame(h, &o, gpu, gdi, 0.0, None) {
                Ok((img, _)) => {
                    // look again after the grab: a pause (or a logout) while it was taken counts
                    let (h2, t2) = windows.get(true);
                    why = capture::gate(h2, &t2, &s, paused.load(Ordering::Relaxed));
                    if why.is_none() && (img.seq.is_none() || img.seq != last_seq) {
                        // the frame's number is the moment it was taken, so the page plays frames back as evenly
                        // as they were taken
                        let jpeg = img.jpeg(capture::number(&img), s.quality);
                        allowance = (allowance + allowance_at.elapsed().as_secs_f64() * ceiling).min(ceiling);
                        allowance_at = Instant::now();
                        if shared && (jpeg.len() as f64) > allowance {
                            over_ceiling += 1;
                        } else {
                            allowance -= jpeg.len() as f64;
                            held.push_back((Instant::now(), jpeg));
                        }
                        taken += 1;
                        // a small copy now and then, for lookers only (held back and gated like the rest; none on the
                        // tape, which has no lookers; none while the relay says no looker asks, 11/10/2026)
                        let lookers = status.lock().unwrap().lookers;
                        if !taping && lookers != Some(0) && last_thumb.elapsed().as_secs_f64() >= capture::THUMB_EVERY {
                            held.push_back((Instant::now(), img.thumb(capture::number(&img))));
                            last_thumb = Instant::now();
                        }
                        last_seq = img.seq;
                    }
                }
                Err(e) => why = Some(e),
            }
        }
        if why.is_some() {
            // the gate shut: whatever was grabbed in the last moments goes too
            held.clear();
        }
        while held.front().is_some_and(|f| f.0.elapsed().as_secs_f64() >= HOLD_BACK) {
            let data = held.pop_front().unwrap().1;
            if &data[4..6] != b"TH" {
                crate::clip::keep(&data); // (the replay buffer: full frames, never the small ones; every one, watched or not)
                // (nobody seated: one now and then, so the relay's last frame stays recent; the tape sends nothing anyway)
                if !taping && !WATCHED.load(Ordering::Relaxed) && last_full_out.elapsed().as_secs_f64() < IDLE_FRAME_EVERY {
                    continue;
                }
                last_full_out = Instant::now();
                bytes_sent += data.len();
                sent += 1;
            }
            if let Err(e) = ws.binary(data) {
                return e;
            }
        }
        // (static: the static key, which viewers see as static, the sound still going; shut: anything else)
        let state = match &why { None => "open", Some(w) if w == capture::STATIC_WHY => "static", _ => "shut" };
        // (on a change at once; else every 5 s as a reminder: each line counts against the relay's requests)
        if !taping && (state != last_state || last_state_at.elapsed().as_secs_f64() > 5.0) {
            if let Err(e) = ws.text(&format!("{{\"state\": \"{}\"}}", state)) {
                return e;
            }
            last_state = state.into();
            last_state_at = Instant::now();
        }
        say(status, |st| st.why = why.clone());
        if last_print.elapsed().as_secs_f64() > if taping { 60.0 } else { 5.0 } {
            let span = last_print.elapsed().as_secs_f64();
            let viewers = status.lock().unwrap().viewers;
            let idle = !taping && viewers == 0 && taken > 0;
            let note = if idle {
                // (frames still taken at the rate asked, for clips; the KB a frame from those sent, else as it was)
                let f = taken as f64 / span;
                let k = if sent > 0 { bytes_sent as f64 / sent as f64 / 1024.0 } else { status.lock().unwrap().kb };
                say(status, |st| { st.fps = f; st.kb = k; });
                format!("live: nobody watching, so a frame every {:.0} s and no sound go out ({:.1} frames a second kept for clips){}",
                        IDLE_FRAME_EVERY, f, if why.is_some() { "; shut" } else { "" })
            } else if sent > 0 {
                let (f, k) = (sent as f64 / span, bytes_sent as f64 / sent as f64 / 1024.0);
                say(status, |st| { st.fps = f; st.kb = k; });
                let over = if over_ceiling > 0 {
                    format!(" ({} skipped for the shared relay's {:.0} Mbit/s ceiling: a lower width or quality keeps them)", over_ceiling, SHARED_CEILING_MBIT)
                } else { String::new() };
                if taping { format!("the tape: {:.1} frames a second, {:.0} KB a frame, nothing sent", f, k) }
                else { format!("live: {:.1} frames a second, {:.0} KB a frame, {} watching{}", f, k, viewers, over) }
            } else if let Some(w) = &why {
                say(status, |st| { st.fps = 0.0; });
                format!("shut: {}; {} watching", w, viewers)
            } else {
                format!("starting; {} watching", viewers)
            };
            log(&note);
            say(status, |st| st.note = note);
            last_print = Instant::now();
            sent = 0;
            taken = 0;
            bytes_sent = 0;
            over_ceiling = 0;
        }
        if gpu_on && why.is_none() {
            // back the moment the GPU's next kept frame lands (at most 20 ms, for the relay's lines meanwhile)
            gpu.wait_newer(last_seq, Duration::from_millis(20));
            continue;
        }
        // paced to fixed deadlines: a short sleep rounds up, and sleeping "what's left of this frame" each time
        // loses that every frame
        due += Duration::from_secs_f64(every);
        let now = Instant::now();
        if now > due + Duration::from_secs_f64(every) {
            due = now; // far behind (a slow frame): start the beat again
        }
        if due > now {
            std::thread::sleep(due - now);
        }
    }
}
