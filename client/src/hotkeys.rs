//! The four hotkeys (main's round two, 11/05/2026, Jace: "replace the pause key with a static key and allow for
//! changing the hotkey in the settings"):
//!   - the static key (Pause by default): viewers see the portal's static, "signal obscured", while the sound and the
//!     chat go on
//!   - the sound key (Scroll Lock): off, the game, everything
//!   - the clip key (Insert): the replay buffer saved as a clip
//!   - the mask key (End): your own masks off and on
//! Each is changed in the settings and taken at once.
//!
//! Through Windows' RegisterHotKey: Windows tells this program when one of its own keys is pressed, and nothing else.
//! It never watches the keyboard. (The Python portal looked at the keys' state every 20 ms; this is the same to use,
//! and plainly not a keylogger.)

use crate::settings::Shared;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use windows::Win32::UI::Input::KeyboardAndMouse::{RegisterHotKey, UnregisterHotKey, HOT_KEY_MODIFIERS, MOD_NOREPEAT};
use windows::Win32::UI::WindowsAndMessaging::{PeekMessageW, MSG, PM_REMOVE, WM_HOTKEY};

const STATIC: i32 = 1;
const SOUND: i32 = 2;
const CLIP: i32 = 3;
const MASK: i32 = 4;
const NAMES: [&str; 4] = ["static", "sound", "clip", "redaction"];

/// Which keys another program holds (Windows refused them): the settings say so beside each.
static TAKEN: [AtomicBool; 4] = [AtomicBool::new(false), AtomicBool::new(false), AtomicBool::new(false), AtomicBool::new(false)];
static NOTES: std::sync::Mutex<Vec<String>> = std::sync::Mutex::new(Vec::new());

pub fn taken(i: usize) -> bool { TAKEN.get(i).is_some_and(|t| t.load(Ordering::Relaxed)) }

/// What the keys did, for the chat (taken once): a press used to be said only in the log, so off air one looked
/// as if it hadn't been heard.
pub fn take_notes() -> Vec<String> { std::mem::take(&mut *NOTES.lock().unwrap()) }

fn note(s: String) {
    crate::transmit::log(&s);
    NOTES.lock().unwrap().push(s);
}

pub fn start(settings: Shared, obscured: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        let mut held: [Option<u32>; 4] = [None; 4];
        let mut taken_told: [Option<u32>; 4] = [None; 4];
        loop {
            let s = settings.get();
            let want = [&s.static_key, &s.sound_key, &s.clip_key, &s.mask_key].map(|k| crate::win::key_code(k).map(|v| v as u32));
            for i in 0..4 {
                // one key can't be two: the first of them keeps it
                let w = if (0..i).any(|j| want[j] == want[i]) { None } else { want[i] };
                if w != held[i] {
                    if held[i].is_some() {
                        unsafe { let _ = UnregisterHotKey(None, i as i32 + 1); }
                    }
                    held[i] = None;
                    if let Some(vk) = w {
                        if unsafe { RegisterHotKey(None, i as i32 + 1, HOT_KEY_MODIFIERS(MOD_NOREPEAT.0), vk) }.is_ok() {
                            held[i] = Some(vk);
                            taken_told[i] = None;
                            TAKEN[i].store(false, Ordering::Relaxed);
                        } else {
                            TAKEN[i].store(true, Ordering::Relaxed);
                            if taken_told[i] != Some(vk) {
                                note(format!("the {} key ({}) is held by another program, so it does nothing here: settings ▸ hotkeys, pick another", NAMES[i], crate::win::key_name(&format!("0x{:x}", vk))));
                                taken_told[i] = Some(vk);
                            }
                        }
                    } else {
                        TAKEN[i].store(false, Ordering::Relaxed);
                    }
                }
            }
            // what Windows has told this thread; the keys looked at again every half second
            let mut msg = MSG::default();
            // (asleep till Windows has something for this thread, at most till the half second's up: it had looked
            // every 20 ms, 50 wake-ups a second for nothing; 11/07/2026)
            let half = std::time::Instant::now() + Duration::from_millis(500);
            loop {
                let left = half.saturating_duration_since(std::time::Instant::now());
                if left.is_zero() {
                    break;
                }
                unsafe {
                    windows::Win32::UI::WindowsAndMessaging::MsgWaitForMultipleObjects(
                        None, false, left.as_millis() as u32, windows::Win32::UI::WindowsAndMessaging::QS_ALLINPUT);
                }
                while unsafe { PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE) }.as_bool() {
                    if msg.message != WM_HOTKEY {
                        continue;
                    }
                    // (the settings waiting for a new hotkey: one of these pressed is the answer, not a press: Windows
                    // never hands it to the window as a key)
                    if crate::cursor::LISTEN_KEYS.load(Ordering::Relaxed) {
                        crate::cursor::put_key(((msg.lParam.0 >> 16) & 0xffff) as u32);
                        continue;
                    }
                    match msg.wParam.0 as i32 {
                        STATIC => {
                            let now = !obscured.load(Ordering::Relaxed);
                            obscured.store(now, Ordering::Relaxed);
                            note(if now { "static key: observers see static, the sound and the chat going on (press it again for the picture)".into() } else { "static key: the picture's back".into() });
                        }
                        SOUND => {
                            let m = crate::sound::next_mode(&settings.get().sound);
                            let _ = settings.update(|s| s.sound = m.into());
                            note(format!("sound key: {}", crate::sound::label(m)));
                        }
                        CLIP => crate::clip::save(&settings.get().clip_folder),
                        MASK => {
                            let on = !settings.get().masks_on;
                            let _ = settings.update(|s| s.masks_on = on);
                            note(if on { "redaction key: redactions on".into() } else { "redaction key: redactions off (everything goes out)".into() });
                        }
                        _ => {}
                    }
                }
            }
        }
    });
}
