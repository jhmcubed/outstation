//! The three hotkeys (main's round two, 11/05/2026, Jace: "replace the pause key with a static key and allow for
//! changing the hotkey in the settings"):
//!   - the static key (Pause by default): viewers see the portal's static, "signal obscured", while the sound and the
//!     chat go on
//!   - the sound key (Scroll Lock): off, the game, everything
//!   - the clip key (Insert): the replay buffer saved as a clip
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
const NAMES: [&str; 3] = ["static", "sound", "clip"];

pub fn start(settings: Shared, obscured: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        let mut held: [Option<u32>; 3] = [None, None, None];
        let mut taken_told: [Option<u32>; 3] = [None, None, None];
        loop {
            let s = settings.get();
            let want = [&s.static_key, &s.sound_key, &s.clip_key].map(|k| crate::win::key_code(k).map(|v| v as u32));
            for i in 0..3 {
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
                        } else if taken_told[i] != Some(vk) {
                            crate::transmit::log(&format!("the {} key is taken by another program", NAMES[i]));
                            taken_told[i] = Some(vk);
                        }
                    }
                }
            }
            // what Windows has told this thread; the keys looked at again every half second
            let mut msg = MSG::default();
            for _ in 0..25 {
                while unsafe { PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE) }.as_bool() {
                    if msg.message != WM_HOTKEY {
                        continue;
                    }
                    match msg.wParam.0 as i32 {
                        STATIC => {
                            let now = !obscured.load(Ordering::Relaxed);
                            obscured.store(now, Ordering::Relaxed);
                            crate::transmit::log(if now { "static: the picture obscured (sound and chat go on)" } else { "the picture back" });
                        }
                        SOUND => {
                            let m = crate::sound::next_mode(&settings.get().sound);
                            let _ = settings.update(|s| s.sound = m.into());
                            crate::transmit::log(&format!("sound: {}", crate::sound::label(m)));
                        }
                        CLIP => crate::clip::save(&settings.get().clip_folder),
                        _ => {}
                    }
                }
                std::thread::sleep(Duration::from_millis(20));
            }
        }
    });
}
