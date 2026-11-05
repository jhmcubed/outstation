//! The two hotkeys: the pause key (the picture shut for a moment) and the sound key (off, the game, everything).
//!
//! Through Windows' RegisterHotKey: Windows tells this program when one of its own two keys is pressed, and nothing
//! else. It never watches the keyboard. (The Python portal looked at the keys' state every 20 ms; this is the same to
//! use, and plainly not a keylogger.)

use crate::settings::Shared;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use windows::Win32::UI::Input::KeyboardAndMouse::{RegisterHotKey, UnregisterHotKey, HOT_KEY_MODIFIERS, MOD_NOREPEAT};
use windows::Win32::UI::WindowsAndMessaging::{PeekMessageW, MSG, PM_REMOVE, WM_HOTKEY};

const PAUSE: i32 = 1;
const SOUND: i32 = 2;

pub fn start(settings: Shared, paused: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        let mut held: [Option<u32>; 2] = [None, None];
        loop {
            let s = settings.get();
            let want = [crate::win::key_code(&s.pause_key).map(|v| v as u32), crate::win::key_code(&s.sound_key).map(|v| v as u32)];
            for (i, id) in [PAUSE, SOUND].iter().enumerate() {
                // one key can't be both: the pause key wins
                let w = if i == 1 && want[1] == want[0] { None } else { want[i] };
                if w != held[i] {
                    unsafe {
                        if held[i].is_some() {
                            let _ = UnregisterHotKey(None, *id);
                        }
                    }
                    held[i] = None;
                    if let Some(vk) = w {
                        let ok = unsafe { RegisterHotKey(None, *id, HOT_KEY_MODIFIERS(MOD_NOREPEAT.0), vk) }.is_ok();
                        if ok {
                            held[i] = Some(vk);
                        } else {
                            crate::transmit::log(&format!("the {} key is taken by another program", if i == 0 { "pause" } else { "sound" }));
                        }
                    }
                }
            }
            // what Windows has told this thread
            let mut msg = MSG::default();
            for _ in 0..25 {
                while unsafe { PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE) }.as_bool() {
                    if msg.message == WM_HOTKEY {
                        if msg.wParam.0 as i32 == PAUSE {
                            let now = !paused.load(Ordering::Relaxed);
                            paused.store(now, Ordering::Relaxed);
                            crate::transmit::log(if now { "paused: nothing goes out" } else { "unpaused" });
                        } else if msg.wParam.0 as i32 == SOUND {
                            let m = crate::sound::next_mode(&settings.get().sound);
                            let _ = settings.update(|s| s.sound = m.into());
                            crate::transmit::log(&format!("sound: {}", crate::sound::label(m)));
                        }
                    }
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            // (and every half second, the keys looked at again in case they changed)
            if held[0].is_none() && want[0].is_some() {
                std::thread::sleep(Duration::from_secs(4)); // (taken: try again now and then, quietly)
            }
        }
    });
}
