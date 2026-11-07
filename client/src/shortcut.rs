//! The Start menu (11/06/2026): asked once on the first screen, a shortcut to this program in your own Start menu
//! (%APPDATA%\Microsoft\Windows\Start Menu\Programs), with the ✼ for its icon (outstation.ico beside the settings, made
//! from the window's own icon). Nothing else: it still doesn't start with Windows, and nothing needs admin. Once you've
//! said yes, each start points it at wherever this copy is (a newer version saved somewhere else takes its place);
//! "take it out of the Start menu" (settings ▸ more) removes it. It carries the program's id (notify.rs, AUMID): Windows
//! shows a desktop program's notifications only when its Start menu shortcut does (11/06/2026).

use std::path::PathBuf;

/// The shortcut's name in the Start menu (and so what a search there finds).
pub const NAME: &str = crate::settings::NAME;
/// the shortcut's name before 11/06/2026 (taken out as the new one's made)
const OLD_NAME: &str = "outside-portal";

fn start_menu() -> Option<PathBuf> {
    let appdata = std::env::var_os("APPDATA")?;
    Some(PathBuf::from(appdata).join("Microsoft").join("Windows").join("Start Menu").join("Programs"))
}

pub fn link_path() -> Option<PathBuf> {
    start_menu().map(|d| d.join(format!("{}.lnk", NAME)))
}

pub fn exists() -> bool {
    link_path().is_some_and(|p| p.exists())
}

/// The ✼ as an icon file (one PNG inside an ICO, as Windows has read since Vista), beside the settings.
fn icon_file(beside: &std::path::Path) -> std::io::Result<PathBuf> {
    let png: &[u8] = include_bytes!("../assets/icon.png");
    let (w, h) = (u32::from_be_bytes(png[16..20].try_into().unwrap()), u32::from_be_bytes(png[20..24].try_into().unwrap()));
    let mut ico = Vec::with_capacity(22 + png.len());
    ico.extend_from_slice(&[0, 0, 1, 0, 1, 0]); // reserved, type 1 (an icon), one image
    ico.push(if w >= 256 { 0 } else { w as u8 });
    ico.push(if h >= 256 { 0 } else { h as u8 });
    ico.extend_from_slice(&[0, 0, 1, 0, 32, 0]); // no palette, reserved, one plane, 32 bits a pixel
    ico.extend_from_slice(&(png.len() as u32).to_le_bytes());
    ico.extend_from_slice(&22u32.to_le_bytes()); // where the image starts
    ico.extend_from_slice(png);
    let p = beside.with_file_name("outstation.ico");
    if std::fs::read(&p).ok().as_deref() != Some(ico.as_slice()) {
        std::fs::write(&p, &ico)?;
    }
    Ok(p)
}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

/// Make (or point again) the Start menu's shortcut at this copy. `settings_path`: where outstation.ico goes.
pub fn make(settings_path: &std::path::Path) -> Result<(), String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    let link = link_path().ok_or("Windows didn't say where your Start menu is")?;
    let ico = icon_file(settings_path).map_err(|e| format!("couldn't write the icon: {}", e))?;
    // (the shortcut it had before the name was outstation, and its icon)
    if let Some(old) = start_menu().map(|d| d.join(format!("{}.lnk", OLD_NAME))) { let _ = std::fs::remove_file(old); }
    let _ = std::fs::remove_file(settings_path.with_file_name("portal.ico"));
    if let Some(d) = link.parent() { std::fs::create_dir_all(d).map_err(|e| e.to_string())?; }
    // (on a thread of its own: COM wants its own apartment, and the window's thread has one already)
    std::thread::spawn(move || -> Result<(), String> {
        use windows::core::{Interface, PCWSTR};
        use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CoUninitialize, IPersistFile, CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED};
        use windows::Win32::UI::Shell::{IShellLinkW, ShellLink};
        unsafe {
            let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
            let made = (|| -> windows::core::Result<()> {
                let sl: IShellLinkW = CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER)?;
                let (path, dir, icon) = (wide(&exe.to_string_lossy()), wide(&exe.parent().map(|d| d.to_string_lossy().to_string()).unwrap_or_default()), wide(&ico.to_string_lossy()));
                let about = wide("⊂λ,ｍ⊙ outstation: your RuneLite, transmitting to your Outside page (ghillie.wiki)");
                sl.SetPath(PCWSTR(path.as_ptr()))?;
                sl.SetWorkingDirectory(PCWSTR(dir.as_ptr()))?;
                sl.SetDescription(PCWSTR(about.as_ptr()))?;
                sl.SetIconLocation(PCWSTR(icon.as_ptr()), 0)?;
                // the program's id, for its notifications (a string PROPVARIANT pointing at our own buffer, which
                // outlives the call; never cleared, so Windows never frees what it didn't allocate)
                {
                    use windows::Win32::Storage::EnhancedStorage::PKEY_AppUserModel_ID;
                    use windows::Win32::System::Com::StructuredStorage::{PROPVARIANT, PROPVARIANT_0, PROPVARIANT_0_0, PROPVARIANT_0_0_0};
                    use windows::Win32::System::Variant::VT_LPWSTR;
                    use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
                    let mut id = wide(crate::notify::AUMID);
                    let pv = std::mem::ManuallyDrop::new(PROPVARIANT { Anonymous: PROPVARIANT_0 { Anonymous: std::mem::ManuallyDrop::new(PROPVARIANT_0_0 {
                        vt: VT_LPWSTR, wReserved1: 0, wReserved2: 0, wReserved3: 0,
                        Anonymous: PROPVARIANT_0_0_0 { pwszVal: windows::core::PWSTR(id.as_mut_ptr()) },
                    }) } });
                    let store: IPropertyStore = sl.cast()?;
                    store.SetValue(&PKEY_AppUserModel_ID, &*pv)?;
                    store.Commit()?;
                }
                let file: IPersistFile = sl.cast()?;
                let at = wide(&link.to_string_lossy());
                file.Save(PCWSTR(at.as_ptr()), true)
            })();
            CoUninitialize();
            made.map_err(|e| format!("Windows wouldn't make the shortcut: {}", e))
        }
    }).join().map_err(|_| "the shortcut's helper stopped".to_string())?
}

/// Take it out of the Start menu.
pub fn remove() -> Result<(), String> {
    match link_path() {
        Some(p) if p.exists() => std::fs::remove_file(p).map_err(|e| e.to_string()),
        _ => Ok(()),
    }
}
