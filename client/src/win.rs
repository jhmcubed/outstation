//! Windows' own pieces: RuneLite's window and canvas, keys, processes, the upload count, the clipboard.

use std::time::Instant;
use windows::core::{BOOL, PCWSTR};
use windows::Win32::Foundation::{HWND, LPARAM, RECT};
use windows::Win32::Graphics::Dwm::{DwmGetWindowAttribute, DWMWA_EXTENDED_FRAME_BOUNDS};
use windows::Win32::System::Diagnostics::ToolHelp::{
    CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W, TH32CS_SNAPPROCESS,
};
use windows::Win32::UI::HiDpi::GetDpiForWindow;
use windows::Win32::UI::WindowsAndMessaging::*;

pub fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

pub fn text(hwnd: HWND) -> String {
    unsafe {
        let n = GetWindowTextLengthW(hwnd);
        let mut buf = vec![0u16; (n + 1).max(1) as usize];
        let got = GetWindowTextW(hwnd, &mut buf);
        String::from_utf16_lossy(&buf[..got.max(0) as usize])
    }
}

fn class(hwnd: HWND) -> String {
    unsafe {
        let mut buf = [0u16; 256];
        let n = GetClassNameW(hwnd, &mut buf);
        String::from_utf16_lossy(&buf[..n.max(0) as usize])
    }
}

pub fn rect(hwnd: HWND) -> (i32, i32, i32, i32) {
    let mut r = RECT::default();
    unsafe {
        let _ = GetWindowRect(hwnd, &mut r);
    }
    (r.left, r.top, r.right, r.bottom)
}

/// The window as Windows draws it (DWM's frame bounds: no invisible resize borders), which is what Windows Graphics
/// Capture hands over.
pub fn frame_bounds(hwnd: HWND) -> (i32, i32, i32, i32) {
    let mut r = RECT::default();
    let ok = unsafe {
        DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, &mut r as *mut RECT as *mut _, std::mem::size_of::<RECT>() as u32)
    };
    if ok.is_ok() && r.right > r.left { (r.left, r.top, r.right, r.bottom) } else { rect(hwnd) }
}

pub fn visible(hwnd: HWND) -> bool {
    unsafe { IsWindowVisible(hwnd).as_bool() }
}
pub fn minimised(hwnd: HWND) -> bool {
    unsafe { IsIconic(hwnd).as_bool() }
}
pub fn alive(hwnd: HWND) -> bool {
    unsafe { IsWindow(Some(hwnd)).as_bool() }
}
pub fn dpi_scale(hwnd: HWND) -> f64 {
    let d = unsafe { GetDpiForWindow(hwnd) };
    if d == 0 { 1.0 } else { d as f64 / 96.0 }
}

/// The largest visible top-level window whose title starts with prefix.
pub fn find_window(prefix: &str) -> Option<(HWND, String)> {
    struct Found<'a> { prefix: &'a str, best: Option<(i64, HWND, String)> }
    unsafe extern "system" fn each(h: HWND, l: LPARAM) -> BOOL {
        let f = unsafe { &mut *(l.0 as *mut Found) };
        if visible(h) {
            let t = text(h);
            if t.starts_with(f.prefix) {
                let (a, b, c, d) = rect(h);
                let area = (c - a) as i64 * (d - b) as i64;
                if f.best.as_ref().map_or(true, |x| area > x.0) {
                    f.best = Some((area, h, t));
                }
            }
        }
        BOOL(1)
    }
    let mut f = Found { prefix, best: None };
    unsafe {
        let _ = EnumWindows(Some(each), LPARAM(&mut f as *mut Found as isize));
    }
    f.best.map(|(_, h, t)| (h, t))
}

/// The game canvas: the largest visible AWT canvas inside the window (screen pixels).
pub fn find_canvas(hwnd: HWND) -> Option<(i32, i32, i32, i32)> {
    let mut best: Option<(i64, (i32, i32, i32, i32))> = None;
    unsafe extern "system" fn each(h: HWND, l: LPARAM) -> BOOL {
        let best = unsafe { &mut *(l.0 as *mut Option<(i64, (i32, i32, i32, i32))>) };
        if class(h).starts_with("SunAwtCanvas") && visible(h) {
            let r = rect(h);
            let area = (r.2 - r.0) as i64 * (r.3 - r.1) as i64;
            if best.as_ref().map_or(true, |x| area > x.0) {
                *best = Some((area, r));
            }
        }
        BOOL(1)
    }
    unsafe {
        let _ = EnumChildWindows(Some(hwnd), Some(each), LPARAM(&mut best as *mut _ as isize));
    }
    best.map(|x| x.1)
}

/// RuneLite puts the character's name in its title once logged in ("RuneLite - name").
pub fn logged_in(title: &str, prefix: &str) -> bool {
    title.starts_with(&format!("{} - ", prefix)) && title.chars().count() > prefix.chars().count() + 3
}

/// RuneLite's window, looked for among all windows once a second; in between only its title is read again.
pub struct WindowCache {
    pub prefix: String,
    hwnd: Option<HWND>,
    title: String,
    at: Option<Instant>,
}

impl WindowCache {
    pub fn new(prefix: &str) -> Self {
        WindowCache { prefix: prefix.to_string(), hwnd: None, title: String::new(), at: None }
    }
    pub fn get(&mut self, fresh: bool) -> (Option<HWND>, String) {
        let stale = self.at.map_or(true, |t| t.elapsed().as_secs_f64() > 1.0);
        if self.hwnd.is_none() || stale || !self.hwnd.map_or(false, alive) {
            match find_window(&self.prefix) {
                Some((h, t)) => { self.hwnd = Some(h); self.title = t; }
                None => { self.hwnd = None; self.title.clear(); }
            }
            self.at = Some(Instant::now());
        } else if fresh {
            if let Some(h) = self.hwnd { self.title = text(h); }
        }
        (self.hwnd, self.title.clone())
    }
}

// ---- keys ----

/// The keys a hotkey may be, by name (the Python portal's names, and its settings' key codes)
pub const KEYS: &[(&str, i32, &str)] = &[
    ("pause", 0x13, "Pause"), ("scrolllock", 0x91, "Scroll Lock"), ("insert", 0x2D, "Insert"), ("home", 0x24, "Home"),
    ("end", 0x23, "End"), ("pageup", 0x21, "Page Up"), ("pagedown", 0x22, "Page Down"), ("delete", 0x2E, "Delete"),
    ("capslock", 0x14, "Caps Lock"), ("numlock", 0x90, "Num Lock"),
];

/// A hotkey's name to show: "Pause", "F9", or its code
pub fn key_name(name: &str) -> String {
    match key_code(name) {
        Some(vk) => KEYS.iter().find(|k| k.1 == vk).map(|k| k.2.to_string())
            .or_else(|| (0x70..=0x87).contains(&vk).then(|| format!("F{}", vk - 0x6F)))
            .unwrap_or_else(|| format!("key {}", vk)),
        None => "none".into(),
    }
}

pub fn key_code(name: &str) -> Option<i32> {
    let t = name.trim().to_lowercase().replace(' ', "");
    if let Some(k) = KEYS.iter().find(|k| k.0 == t) {
        return Some(k.1);
    }
    if let Some(n) = t.strip_prefix('f').and_then(|n| n.parse::<i32>().ok()).filter(|n| (1..=24).contains(n)) {
        return Some(0x6F + n);
    }
    match t.as_str() {
        _ => {
            if let Some(h) = t.strip_prefix("0x") { i32::from_str_radix(h, 16).ok() } else { t.parse().ok() }
        }
    }
}

// ---- RuneLite's process ----

const RUNELITE_NAMES: &[&str] = &["runelite.exe", "java.exe", "javaw.exe"];

fn processes() -> std::collections::HashMap<u32, (u32, String)> {
    let mut out = std::collections::HashMap::new();
    unsafe {
        let Ok(snap) = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) else { return out };
        let mut e = PROCESSENTRY32W { dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32, ..Default::default() };
        let mut ok = Process32FirstW(snap, &mut e).is_ok();
        while ok {
            let n = e.szExeFile.iter().position(|&c| c == 0).unwrap_or(e.szExeFile.len());
            out.insert(e.th32ProcessID, (e.th32ParentProcessID, String::from_utf16_lossy(&e.szExeFile[..n]).to_lowercase()));
            ok = Process32NextW(snap, &mut e).is_ok();
        }
        let _ = windows::Win32::Foundation::CloseHandle(snap);
    }
    out
}

/// The process whose tree is RuneLite: the window's, or the RuneLite launcher above it. Never further up than
/// RuneLite's own processes (Explorer's tree would be nearly everything).
pub fn runelite_pid(prefix: &str) -> Option<u32> {
    let (hwnd, _) = find_window(prefix)?;
    let mut pid = 0u32;
    unsafe {
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
    }
    if pid == 0 {
        return None;
    }
    let procs = processes();
    loop {
        let (parent, name) = procs.get(&pid).cloned().unwrap_or((0, String::new()));
        let parent_name = procs.get(&parent).map(|x| x.1.clone()).unwrap_or_default();
        if RUNELITE_NAMES.contains(&parent_name.as_str()) && RUNELITE_NAMES.contains(&name.as_str()) {
            pid = parent;
        } else {
            return Some(pid);
        }
    }
}

// ---- this PC's upload ----

/// Every byte this PC has sent, on its working hardware interfaces (as netstat -e counts them).
pub fn bytes_sent() -> Option<u64> {
    use windows::Win32::NetworkManagement::IpHelper::{FreeMibTable, GetIfTable2, MIB_IF_TABLE2};
    use windows::Win32::NetworkManagement::Ndis::IfOperStatusUp;
    unsafe {
        let mut table: *mut MIB_IF_TABLE2 = std::ptr::null_mut();
        if GetIfTable2(&mut table).is_err() || table.is_null() {
            return None;
        }
        let t = &*table;
        let rows = std::slice::from_raw_parts(t.Table.as_ptr(), t.NumEntries as usize);
        let mut sum = 0u64;
        for r in rows {
            if r.InterfaceAndOperStatusFlags._bitfield & 1 != 0 && r.OperStatus == IfOperStatusUp {
                sum += r.OutOctets;
            }
        }
        FreeMibTable(table as *const _);
        Some(sum)
    }
}

// ---- the clipboard ----

/// Open one of ghillie.wiki's pages in the browser (the friends list's names): Windows opens it with whatever opens
/// web pages. Nothing but ghillie.wiki's own pages is opened this way.
pub fn open_page(url: &str) -> bool {
    use windows::Win32::UI::Shell::ShellExecuteW;
    use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
    if !url.starts_with("https://ghillie.wiki/") || url.chars().any(|c| c.is_whitespace() || c == '"') { return false; }
    let (verb, what) = (wide("open"), wide(url));
    let r = unsafe { ShellExecuteW(None, windows::core::PCWSTR(verb.as_ptr()), windows::core::PCWSTR(what.as_ptr()), None, None, SW_SHOWNORMAL) };
    r.0 as usize > 32
}

pub fn copy_text(s: &str) -> bool {
    use windows::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, OpenClipboard, SetClipboardData};
    use windows::Win32::System::Memory::{GlobalAlloc, GlobalLock, GlobalUnlock, GMEM_MOVEABLE};
    let w = wide(s);
    unsafe {
        if OpenClipboard(None).is_err() {
            return false;
        }
        let _ = EmptyClipboard();
        let ok = (|| -> Option<()> {
            let h = GlobalAlloc(GMEM_MOVEABLE, w.len() * 2).ok()?;
            let p = GlobalLock(h) as *mut u16;
            if p.is_null() { return None; }
            std::ptr::copy_nonoverlapping(w.as_ptr(), p, w.len());
            let _ = GlobalUnlock(h);
            SetClipboardData(13, Some(windows::Win32::Foundation::HANDLE(h.0))).ok()?; // CF_UNICODETEXT
            Some(())
        })();
        let _ = CloseClipboard();
        ok.is_some()
    }
}

pub fn paste_text() -> Option<String> {
    use windows::Win32::System::DataExchange::{CloseClipboard, GetClipboardData, OpenClipboard};
    use windows::Win32::System::Memory::{GlobalLock, GlobalUnlock};
    unsafe {
        OpenClipboard(None).ok()?;
        let out = (|| -> Option<String> {
            let h = GetClipboardData(13).ok()?;
            let g = windows::Win32::Foundation::HGLOBAL(h.0);
            let p = GlobalLock(g) as *const u16;
            if p.is_null() { return None; }
            let mut n = 0;
            while *p.add(n) != 0 && n < 1 << 20 { n += 1; }  // (Dink's whole export, a few tens of KB)
            let s = String::from_utf16_lossy(std::slice::from_raw_parts(p, n));
            let _ = GlobalUnlock(g);
            Some(s)
        })();
        let _ = CloseClipboard();
        out
    }
}

// ---- this window ----

/// One window per settings file: a second copy brings the first to the front and leaves.
pub fn first_copy(tag: &str, title: &str) -> bool {
    use windows::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows::Win32::System::Threading::CreateMutexW;
    let name = wide(&format!("Local\\outside-portal-{}", tag));
    unsafe {
        let _m = CreateMutexW(None, false, PCWSTR(name.as_ptr()));
        if GetLastError() == ERROR_ALREADY_EXISTS {
            show_window(title);
            return false;
        }
        std::mem::forget(_m);
    }
    true
}

/// The portal's own window: the largest top-level window with exactly this title belonging to this program (one of
/// its own processes, outside-portal.exe), never another program's window that happens to share a title
pub fn find_main(title: &str) -> Option<HWND> {
    struct Found<'a> { title: &'a str, best: Option<(i64, HWND)>, procs: std::collections::HashMap<u32, (u32, String)>, me: String }
    unsafe extern "system" fn each(h: HWND, l: LPARAM) -> BOOL {
        let f = unsafe { &mut *(l.0 as *mut Found) };
        let mut pid = 0u32;
        unsafe { GetWindowThreadProcessId(h, Some(&mut pid)); }
        let ours = f.procs.get(&pid).is_some_and(|p| p.1 == f.me);
        if ours && text(h) == f.title {
            let (a, b, c, d) = rect(h);
            let area = (c - a) as i64 * (d - b) as i64;
            if f.best.map_or(true, |x| area > x.0) {
                f.best = Some((area, h));
            }
        }
        BOOL(1)
    }
    let me = std::env::current_exe().ok().and_then(|p| p.file_name().map(|n| n.to_string_lossy().to_lowercase()))
        .unwrap_or_else(|| "outside-portal.exe".into());
    let mut f = Found { title, best: None, procs: processes(), me };
    unsafe {
        let _ = EnumWindows(Some(each), LPARAM(&mut f as *mut Found as isize));
    }
    f.best.map(|x| x.1)
}

pub fn show_window(title: &str) {
    unsafe {
        if let Some(h) = find_main(title) {
            let _ = ShowWindow(h, SW_SHOW);
            let _ = ShowWindow(h, SW_RESTORE);
            let _ = SetForegroundWindow(h);
        }
    }
}

/// The taskbar button flashes until the window is looked at.
pub fn flash(title: &str) {
    unsafe {
        if let Some(h) = find_main(title) {
            if GetForegroundWindow() == h {
                return;
            }
            let fi = FLASHWINFO { cbSize: std::mem::size_of::<FLASHWINFO>() as u32, hwnd: h,
                                  dwFlags: FLASHW_TRAY | FLASHW_TIMERNOFG, uCount: 3, dwTimeout: 0 };
            let _ = FlashWindowEx(&fi);
        }
    }
}

/// A console to print to, when started from one (--once, --bench).
pub fn console() {
    use windows::Win32::System::Console::{AttachConsole, ATTACH_PARENT_PROCESS};
    unsafe {
        let _ = AttachConsole(ATTACH_PARENT_PROCESS);
    }
}

pub fn dpi_aware() {
    use windows::Win32::UI::HiDpi::{SetProcessDpiAwarenessContext, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2};
    unsafe {
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
}
