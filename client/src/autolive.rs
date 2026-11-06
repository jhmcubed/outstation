//! Go live when RuneLite opens (11/06/2026, Jace: "build the go live when runelite opens feature"), and, beside it,
//! end the stream when RuneLite closes. Both are in settings ▸ more, the first off to begin with.
//!
//! Once a second it looks for RuneLite's window (as the capture does). When the window appears (or is already open as
//! the portal starts) and the portal is attached and off air, it goes live. It only acts on the window appearing, so
//! "end stream" while RuneLite is open is never undone: the next go-live waits for RuneLite to open again. With
//! "...and end the stream when it closes", RuneLite gone for 30 s (time for it to restart after an update) ends the
//! stream, so a forgotten portal doesn't keep a channel on air.

use crate::settings::Shared;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// how long RuneLite may be gone before the stream ends (its own restart after an update takes a few seconds)
const GONE_FOR: Duration = Duration::from_secs(30);

static NOTES: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// set when this went live by itself, for the window to do what a press of "go live" also does (the station's relay)
pub static WENT_LIVE: AtomicBool = AtomicBool::new(false);

/// What it did, for the window's chat (taken once).
pub fn take_notes() -> Vec<String> {
    std::mem::take(&mut *NOTES.lock().unwrap())
}

fn note(s: &str) {
    crate::transmit::log(s);
    NOTES.lock().unwrap().push(s.to_string());
}

pub fn start(settings: Shared) {
    std::thread::spawn(move || {
        let mut was_open = false;
        let mut gone_since: Option<Instant> = None;
        loop {
            std::thread::sleep(Duration::from_secs(1));
            settings.refresh();
            let s = settings.get();
            // (and not while the first-time setup's going: ticked there with RuneLite open, it goes live as it's finished)
            if !s.auto_live || !s.attached() || s.setup_step.is_some() {
                was_open = false;
                gone_since = None;
                continue;
            }
            let open = crate::win::find_window(&s.title).is_some();
            if open && !was_open && !s.on_air {
                if settings.update(|x| x.on_air = true).is_ok() {
                    WENT_LIVE.store(true, Ordering::SeqCst);
                    note("RuneLite opened: going live (settings ▸ more: go live by itself whenever RuneLite opens)");
                }
            }
            if open {
                gone_since = None;
            } else if was_open {
                gone_since = Some(Instant::now());
            }
            if let Some(t) = gone_since {
                if t.elapsed() >= GONE_FOR {
                    gone_since = None;
                    if s.auto_end && s.on_air && settings.update(|x| x.on_air = false).is_ok() {
                        note("RuneLite closed: the stream's ended (it goes live again when RuneLite opens)");
                    }
                }
            }
            was_open = open;
        }
    });
}
