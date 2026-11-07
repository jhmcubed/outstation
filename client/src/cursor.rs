//! The cursor: ghillie.wiki's own (its field-kit.js), drawn here as real Windows cursors, so it never lags behind
//! the hand and it's the same over the title bar, the edges and every window of the portal's.
//!
//! - A small pixel ✼, white with a dark rim; over anything that can be clicked it turns lime and twinkles (its arms
//!   and its diagonals taking turns reaching out, a frame every 160 ms), as on the site.
//! - Over text you can type in, an I-beam in the same pixels. At the window's edges, Windows' own double arrows
//!   (11/06/2026, Jace: "two different cursors ... 1px location difference"): once an edge is pressed, Windows' own
//!   resizing shows its arrow whatever this says, so drawn in pixels here the two had taken turns, a pixel apart.
//! - Each of the portal's windows is subclassed (comctl32's `SetWindowSubclass`) so its `WM_SETCURSOR` is answered
//!   here, ahead of winit's. Only the portal's own windows: it never touches another program's (RuneLite's least of
//!   all), and the cursor goes back to Windows' own the moment the hand leaves them.

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::OnceLock;
use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::Graphics::Gdi::{CreateBitmap, CreateDIBSection, DeleteObject, BITMAPINFO, BITMAPINFOHEADER, BI_RGB,
                                    DIB_RGB_COLORS};
use windows::Win32::UI::Shell::{DefSubclassProc, SetWindowSubclass};
use windows::Win32::UI::WindowsAndMessaging::{CreateIconIndirect, EnumWindows, GetWindowThreadProcessId, SetCursor,
                                              HCURSOR, ICONINFO, WM_SETCURSOR};

#[derive(Clone, Copy, PartialEq, Debug)]
#[repr(u8)]
pub enum Kind { Star = 0, Hot = 1, Beam = 2, EastWest = 3, NorthSouth = 4, NwSe = 5, NeSw = 6 }

const LIME: [u8; 3] = [0xb9, 0xff, 0x00];
const WHITE: [u8; 3] = [0xff, 0xff, 0xff];
const SUBCLASS_ID: usize = 0x6768_696c; // ("ghil")

static WANT: AtomicU8 = AtomicU8::new(0);
/// While the settings wait for a new hotkey (11/06/2026: egui can't hear Pause or Scroll Lock): the next key pressed
/// in the portal's own windows, from their own key messages (never a hook, never another program's).
pub static LISTEN_KEYS: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
static LAST_VK: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// One of the portal's own hotkeys pressed while LISTEN_KEYS is set (hotkeys.rs hands it here instead of acting on it:
/// Windows gives such a key to the hotkey, never to the window).
pub fn put_key(vk: u32) {
    LAST_VK.store(vk, Ordering::Relaxed);
}

/// The key pressed in the portal's windows since this was last asked, while LISTEN_KEYS is set.
pub fn take_key() -> Option<u32> {
    let vk = LAST_VK.swap(0, Ordering::Relaxed);
    (vk != 0).then_some(vk)
}
static FRAME: AtomicU8 = AtomicU8::new(0);
// the cursors, made once: the star, the four frames of the hot star, the I-beam, the four double arrows
static MADE: OnceLock<Vec<isize>> = OnceLock::new();

/// a 9 × 9 star: arms of l1, diagonals of l2, round a core (field-kit.js's starShape)
fn star(l1: i32, l2: i32) -> Vec<(i32, i32)> {
    let mut s = vec![(0, 0)];
    for a in 1..=l1.max(l2) {
        if a <= l1 { s.extend([(a, 0), (-a, 0), (0, a), (0, -a)]); }
        if a <= l2 { s.extend([(a, a), (-a, a), (a, -a), (-a, -a)]); }
    }
    s
}

fn beam() -> Vec<(i32, i32)> {
    let mut s: Vec<(i32, i32)> = (-4..=4).map(|y| (0, y)).collect();
    s.extend([(-1, -4), (1, -4), (-1, 4), (1, 4)]);
    s
}



/// One cursor: each of the shape's pixels `px` screen pixels wide, a dark rim round it (85% black), the hot spot its
/// middle. 32-bit with alpha, so its edges are its own.
fn make(shape: &[(i32, i32)], fill: [u8; 3], px: i32) -> isize {
    const R: i32 = 5; // (the shape within ±4, its rim within ±5)
    let n = (2 * R + 1) * px;
    let mut buf = vec![0u8; (n * n * 4) as usize];
    let on: std::collections::HashSet<(i32, i32)> = shape.iter().copied().collect();
    let mut paint = |cx: i32, cy: i32, bgra: [u8; 4]| {
        for y in (cy + R) * px..(cy + R + 1) * px {
            for x in (cx + R) * px..(cx + R + 1) * px {
                let i = ((y * n + x) * 4) as usize;
                buf[i..i + 4].copy_from_slice(&bgra);
            }
        }
    };
    for &(x, y) in shape {
        for dy in -1..=1 {
            for dx in -1..=1 {
                if !on.contains(&(x + dx, y + dy)) { paint(x + dx, y + dy, [0, 0, 0, 217]); }
            }
        }
    }
    for &(x, y) in shape {
        paint(x, y, [fill[2], fill[1], fill[0], 255]);
    }
    unsafe {
        let mut info = BITMAPINFO::default();
        info.bmiHeader = BITMAPINFOHEADER {
            biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32, biWidth: n, biHeight: -n, biPlanes: 1, biBitCount: 32,
            biCompression: BI_RGB.0, ..Default::default()
        };
        let mut bits: *mut std::ffi::c_void = std::ptr::null_mut();
        let Ok(colour) = CreateDIBSection(None, &info, DIB_RGB_COLORS, &mut bits, None, 0) else { return 0 };
        if bits.is_null() { return 0; }
        std::ptr::copy_nonoverlapping(buf.as_ptr(), bits as *mut u8, buf.len());
        let zeros = vec![0u8; ((n + 15) / 16 * 2 * n) as usize];
        let mask = CreateBitmap(n, n, 1, 1, Some(zeros.as_ptr() as *const _));
        let icon = ICONINFO { fIcon: false.into(), xHotspot: (R * px + px / 2) as u32, yHotspot: (R * px + px / 2) as u32,
                              hbmMask: mask, hbmColor: colour };
        let made = CreateIconIndirect(&icon).map(|h| h.0 as isize).unwrap_or(0);
        let _ = DeleteObject(colour.into());
        let _ = DeleteObject(mask.into());
        made
    }
}

fn made() -> &'static Vec<isize> {
    MADE.get_or_init(|| {
        // the site's 2 px grid at 100%, larger with the screen's scale
        let dpi = unsafe { windows::Win32::UI::HiDpi::GetDpiForSystem() } as f32;
        let px = ((2.0 * dpi / 96.0).round() as i32).max(2);
        let mut v = vec![make(&star(3, 2), WHITE, px)];
        for (l1, l2) in [(3, 2), (2, 2), (2, 3), (2, 2)] {
            v.push(make(&star(l1, l2), LIME, px));
        }
        v.push(make(&beam(), WHITE, px));
        // (the edges: Windows' own, the arrows its resizing shows: see the header)
        use windows::Win32::UI::WindowsAndMessaging::{LoadCursorW, IDC_SIZENESW, IDC_SIZENS, IDC_SIZENWSE, IDC_SIZEWE};
        for id in [IDC_SIZEWE, IDC_SIZENS, IDC_SIZENWSE, IDC_SIZENESW] {
            v.push(unsafe { LoadCursorW(None, id) }.map(|h| h.0 as isize).unwrap_or(0));
        }
        v
    })
}

fn current() -> Option<HCURSOR> {
    let m = made();
    let i = match WANT.load(Ordering::Relaxed) {
        1 => 1 + FRAME.load(Ordering::Relaxed) as usize % 4,
        2 => 5, 3 => 6, 4 => 7, 5 => 8, 6 => 9,
        _ => 0,
    };
    let h = *m.get(i)?;
    (h != 0).then(|| HCURSOR(h as *mut _))
}

unsafe extern "system" fn proc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM, _id: usize, _data: usize) -> LRESULT {
    // (the hand over the window's own area: the portal's windows have no other, being drawn whole by the portal)
    use windows::Win32::UI::WindowsAndMessaging::{WM_KEYDOWN, WM_SYSKEYDOWN};
    if (msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN) && LISTEN_KEYS.load(Ordering::Relaxed) {
        LAST_VK.store(wp.0 as u32, Ordering::Relaxed);
    }
    if msg == WM_SETCURSOR && (lp.0 & 0xffff) as u32 == 1 {
        if let Some(c) = current() {
            unsafe { SetCursor(Some(c)); }
            return LRESULT(1);
        }
    }
    unsafe { DefSubclassProc(hwnd, msg, wp, lp) }
}

unsafe extern "system" fn each(hwnd: HWND, _l: LPARAM) -> windows::core::BOOL {
    let mut pid = 0u32;
    unsafe {
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == std::process::id() {
            // (the same id again only updates it: a window already taken in is left as it is)
            let _ = SetWindowSubclass(hwnd, Some(proc), SUBCLASS_ID, 0);
            see_through(hwnd);
        }
    }
    true.into()
}

/// The see-through windows over RuneLite (their titles "outstation ✼ …": pings, ✼, the chat over the game, the
/// redactions) with nothing of Windows' own round them: no shadow, no border, no rounded corners (11/07/2026, Jace:
/// "the invisible/transparent chatbox also has a shadow around it"). Once a window; the main window keeps its own.
fn see_through(hwnd: HWND) {
    use windows::Win32::Graphics::Dwm::{DwmSetWindowAttribute, DWMWA_BORDER_COLOR, DWMWA_NCRENDERING_POLICY, DWMWA_WINDOW_CORNER_PREFERENCE};
    use windows::Win32::UI::WindowsAndMessaging::GetWindowTextW;
    static DONE: std::sync::Mutex<Vec<isize>> = std::sync::Mutex::new(Vec::new());
    let key = hwnd.0 as isize;
    if DONE.lock().unwrap().contains(&key) { return; }
    let mut buf = [0u16; 128];
    let n = unsafe { GetWindowTextW(hwnd, &mut buf) } as usize;
    let title = String::from_utf16_lossy(&buf[..n.min(buf.len())]);
    if !title.starts_with("outstation ✼ ") { return; }
    DONE.lock().unwrap().push(key);
    let set = |what, value: u32| unsafe {
        // (Windows 10 knows no border colour or corners: it says so and draws neither anyway)
        let _ = DwmSetWindowAttribute(hwnd, what, &value as *const u32 as *const _, 4);
    };
    set(DWMWA_NCRENDERING_POLICY, 1); // DWMNCRP_DISABLED: no frame drawn by Windows, so no shadow
    set(DWMWA_WINDOW_CORNER_PREFERENCE, 1); // DWMWCP_DONOTROUND
    set(DWMWA_BORDER_COLOR, 0xFFFF_FFFE); // DWMWA_COLOR_NONE
}

/// Every window of the portal's own (the main one, and the chat-over-the-game and masks windows as they come)
/// answers the cursor from here. Cheap; called each frame.
pub fn adopt() {
    unsafe { let _ = EnumWindows(Some(each), LPARAM(0)); }
}

/// Windows' "animation effects" switched off (Settings ▸ Accessibility ▸ Visual effects): the portal holds still, as
/// the site does with reduced motion. Read once.
pub fn still() -> bool {
    static STILL: OnceLock<bool> = OnceLock::new();
    *STILL.get_or_init(|| unsafe {
        use windows::Win32::UI::WindowsAndMessaging::{SystemParametersInfoW, SPI_GETCLIENTAREAANIMATION,
                                                      SYSTEM_PARAMETERS_INFO_UPDATE_FLAGS};
        let mut on = windows::core::BOOL(1);
        let _ = SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, Some(&mut on as *mut _ as *mut _),
                                      SYSTEM_PARAMETERS_INFO_UPDATE_FLAGS(0));
        !on.as_bool()
    })
}

/// What egui asked for, as one of these.
pub fn kind_of(icon: eframe::egui::CursorIcon) -> Kind {
    use eframe::egui::CursorIcon as C;
    match icon {
        C::PointingHand => Kind::Hot,
        C::Text | C::VerticalText => Kind::Beam,
        C::ResizeHorizontal | C::ResizeEast | C::ResizeWest | C::ResizeColumn => Kind::EastWest,
        C::ResizeVertical | C::ResizeNorth | C::ResizeSouth | C::ResizeRow => Kind::NorthSouth,
        C::ResizeNwSe | C::ResizeNorthWest | C::ResizeSouthEast => Kind::NwSe,
        C::ResizeNeSw | C::ResizeNorthEast | C::ResizeSouthWest => Kind::NeSw,
        _ => Kind::Star,
    }
}

/// At the end of a window's frame: the cursor egui asked for becomes ours (shown at once if it changed), and egui
/// is told the plain arrow, so winit never sets Windows' own over it.
pub fn follow(ctx: &eframe::egui::Context, still: bool) {
    let icon = ctx.output(|o| o.cursor_icon);
    ctx.output_mut(|o| o.cursor_icon = eframe::egui::CursorIcon::Default);
    if ctx.input(|i| i.pointer.hover_pos().is_none()) {
        return;
    }
    let k = kind_of(icon);
    let frame = if k == Kind::Hot && !still { ((ctx.input(|i| i.time) / 0.16) as u64 % 4) as u8 } else { 0 };
    let kind_changed = WANT.swap(k as u8, Ordering::Relaxed) != k as u8;
    let frame_changed = FRAME.swap(frame, Ordering::Relaxed) != frame;
    if kind_changed || frame_changed {
        if let Some(c) = current() { unsafe { SetCursor(Some(c)); } }
    }
    if k == Kind::Hot && !still {
        ctx.request_repaint_after(std::time::Duration::from_millis(160));
    }
}
