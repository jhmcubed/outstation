//! A watch on the window (11/07/2026, Jace: opened with RuneLite open and "transmit by itself" on, the field station
//! went "not responding" for a few seconds, then carried on, showing the monitor). Each frame of the window says where
//! it's got to (`at`); a thread of its own looks four times a second, and when a frame has taken over 2 s, the log
//! says where it's held up and, once it goes on, for how long. Nothing else: it only writes to the station log, so the
//! next "copy the station log" carries it. (Not reproduced in Wine, which has no Windows Graphics Capture; the log is
//! how it's found on the PC that has it.)

use std::sync::Mutex;
use std::time::{Duration, Instant};

struct Now {
    /// where the frame is, and when it began (None between frames)
    at: &'static str,
    began: Option<Instant>,
    /// told already for this frame: where it was held up
    told: Option<&'static str>,
}

static NOW: Mutex<Now> = Mutex::new(Now { at: "", began: None, told: None });

/// how long a frame may take before the log hears of it
const HELD: Duration = Duration::from_secs(2);

/// A frame begins (`what`: where).
pub fn begin(what: &'static str) {
    let mut n = NOW.lock().unwrap();
    if let (Some(t), Some(w)) = (n.began, n.told) {
        crate::transmit::log(&format!("the window went on after {:.1} s (held up at {}, last at {})", t.elapsed().as_secs_f64(), w, n.at));
    }
    n.at = what;
    n.began = Some(Instant::now());
    n.told = None;
}

/// The frame's got this far.
pub fn at(what: &'static str) {
    NOW.lock().unwrap().at = what;
}

/// The frame's done.
pub fn end() {
    let mut n = NOW.lock().unwrap();
    if let (Some(t), Some(w)) = (n.began, n.told) {
        crate::transmit::log(&format!("the window went on after {:.1} s (held up at {}, last at {})", t.elapsed().as_secs_f64(), w, n.at));
    }
    n.began = None;
    n.told = None;
}

/// The watch, from the window's start.
pub fn watch() {
    std::thread::spawn(|| loop {
        std::thread::sleep(Duration::from_millis(250));
        let mut n = NOW.lock().unwrap();
        if let Some(t) = n.began {
            if n.told.is_none() && t.elapsed() >= HELD {
                n.told = Some(n.at);
                crate::transmit::log(&format!("the window's been held up {:.1} s, at {}", t.elapsed().as_secs_f64(), n.at));
            }
        }
    });
}
