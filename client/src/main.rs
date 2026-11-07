//! outside-portal: the portal's broadcaster. RuneLite's window, its sound and the chat, to the relay
//! (docs/protocol.md), for your Outside page on ghillie.wiki.
//!
//!     outside-portal.exe                      the window (and the tray)
//!     outside-portal.exe --once frame.png     one frame to a file, nothing sent
//!     outside-portal.exe --bench 60           what a frame costs, stage by stage, nothing sent
//!     outside-portal.exe --clip-selftest DIR  a two-second clip from made-up frames and a tone, into DIR
//!     outside-portal.exe --page-selftest DIR  the Outside page's copy of a clip (clipsup.rs) from made-up frames, its sizes
//!     outside-portal.exe --settings <file>    another settings file (one window per file)
//!     outside-portal.exe --show-settings      open with the settings drawer out
//!
//! The settings are %APPDATA%\outside-portal\settings.json, and the log is portal.log beside it.
//!
//! Built with `--features station` it's the field station too (station.rs): `--station <launcher.json>`, and `--tray`
//! to open in the tray. It opens off air: going live is asked for (go live, or a mode with the portal in it).

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod ask;
mod autolive;
mod capture;
mod clip;
mod clipsup;
mod clock;
mod crash;
mod cursor;
mod desk;
mod dink;
mod dinksetup;
mod feed;
mod friendlist;
mod hotkeys;
#[cfg(feature = "station")]
mod keeper;
mod net;
mod notify;
mod report;
mod settings;
mod shortcut;
mod sound;
#[cfg(feature = "station")]
mod station;
mod theme;
mod transmit;
mod ui;
mod win;

use std::path::PathBuf;

fn arg_after(args: &[String], flag: &str) -> Option<String> {
    args.iter().position(|a| a == flag).and_then(|i| args.get(i + 1)).cloned()
}

fn main() {
    clock::start();
    win::dpi_aware();
    let args: Vec<String> = std::env::args().collect();
    let path = arg_after(&args, "--settings").map(PathBuf::from).unwrap_or_else(settings::default_path);
    transmit::set_log(path.with_file_name("portal.log"));
    crash::install(path.with_file_name("portal.log"));
    let shared = settings::Shared::open(path.clone());

    if let Some(file) = arg_after(&args, "--once") {
        win::console();
        match capture::once(&shared.get(), &file) {
            Ok(note) => println!("{}", note),
            Err(e) => { eprintln!("{}", e); std::process::exit(1); }
        }
        return;
    }
    if let Some(dir) = arg_after(&args, "--clip-selftest") {
        win::console();
        match clip::selftest(std::path::Path::new(&dir)) {
            Ok(p) => println!("{}", p.display()),
            Err(e) => { eprintln!("{}", e); std::process::exit(1); }
        }
        return;
    }
    if let Some(dir) = arg_after(&args, "--page-selftest") {
        win::console();
        match clip::page_selftest(std::path::Path::new(&dir)) {
            Ok(said) => println!("{}", said),
            Err(e) => { eprintln!("{}", e); std::process::exit(1); }
        }
        return;
    }
    if let Some(n) = arg_after(&args, "--bench") {
        win::console();
        if let Err(e) = capture::bench(&shared.get(), n.parse().unwrap_or(60)) {
            eprintln!("{}", e);
            std::process::exit(1);
        }
        return;
    }

    // one window per settings file: a second copy brings the first to the front
    let tag = {
        use std::hash::{Hash, Hasher};
        let mut h = std::collections::hash_map::DefaultHasher::new();
        path.to_string_lossy().to_lowercase().hash(&mut h);
        format!("{:016x}", h.finish())
    };
    if !win::first_copy(&tag, ui::TITLE) {
        return;
    }

    // the field station opens off air, whatever the portal was left as: it's started to make music as often as to play
    #[cfg(feature = "station")]
    let _ = shared.update(|s| s.on_air = false);
    let tray_only = cfg!(feature = "station") && args.iter().any(|a| a == "--tray");

    let tx = transmit::Transmit::start(shared.clone());
    hotkeys::start(shared.clone(), tx.paused.clone());
    let snd = sound::start(shared.clone());
    let dk = dink::start(shared.clone());
    autolive::start(shared.clone());
    let (rgba, w, h) = ui::icon_rgba();
    let opts = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_title(ui::TITLE)
            .with_decorations(false) // (its own title bar, edges and cursor: ui.rs, cursor.rs)
            .with_inner_size([480.0, 640.0])
            .with_min_inner_size([380.0, 440.0])
            .with_visible(!tray_only)
            .with_icon(eframe::egui::IconData { rgba, width: w, height: h }),
        // (Ctrl+C, X and V come to the window as keys, for ui.rs's WindowsClipboard to take to Windows' own clipboard:
        // built without eframe's clipboard, they'd only reached a clipboard of its own)
        clipboard_shortcuts: false,
        ..Default::default()
    };
    let _ = eframe::run_native(ui::TITLE, opts, Box::new(move |cc| Ok(Box::new(ui::App::new(cc, shared, tx, snd, dk)))));
}
