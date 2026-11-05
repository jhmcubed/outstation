//! The window: the portal's whole control (portal_chat.py and portal_settings.py, rewritten; parity lines 19-32).
//!
//! One window in the abyss's black: the live light and go live / end stream, the upload line, the sound row, the
//! chat (answer in it; right-click a line to take it back or block its viewer), polls, and pings over RuneLite.
//! settings ▸ and dink ▸ pop drawers out on the right. Until it's attached to a relay it shows the attach screen.

use crate::capture::{self, Image};
use crate::desk::{Desk, In};
use crate::settings::{self, Settings, Shared, REPORTS};
use crate::transmit::Transmit;
use crate::{dink, sound, win};
use eframe::egui::{self, Color32, FontId, RichText, Sense, Stroke, Vec2};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const TITLE: &str = "the portal ✼ chat";
const ABYSS: Color32 = Color32::BLACK;
const LIME: Color32 = Color32::from_rgb(0xb9, 0xff, 0x00);
const BRIGHT: Color32 = Color32::from_rgb(0xe4, 0xff, 0x8a);
const DIM: Color32 = Color32::from_rgb(0x3a, 0x55, 0x10);
const TRACK: Color32 = Color32::from_rgb(0x0d, 0x14, 0x04);
const TEAL: Color32 = Color32::from_rgb(0x04, 0x94, 0xaa);
const CYAN: Color32 = Color32::from_rgb(0x2e, 0xe0, 0xf5);
const PINK: Color32 = Color32::from_rgb(0xff, 0x69, 0xb4);
const GREY: Color32 = Color32::from_rgb(0x99, 0x99, 0x99);
const WHEN: Color32 = Color32::from_rgb(0x66, 0x66, 0x66);
const TEXT: Color32 = Color32::from_rgb(0xdd, 0xdd, 0xdd);
const FIELD: Color32 = Color32::from_rgb(0x05, 0x08, 0x05);
const DRAWER_W: f32 = 560.0;
const LABELS: &[(&str, &str)] = &[("off", "off"), ("game", "the game"), ("all", "everything")];

fn body(size: f32) -> FontId { FontId::new(size, egui::FontFamily::Proportional) }
fn title_font(size: f32) -> FontId { FontId::new(size, egui::FontFamily::Name("title".into())) }
fn symbol_font(size: f32) -> FontId { FontId::new(size, egui::FontFamily::Name("symbols".into())) }

/// Words in the RuneScape font, with any symbol it lacks in egui's own.
fn job(text: &str, size: f32, colour: Color32) -> egui::text::LayoutJob {
    let mut j = egui::text::LayoutJob::default();
    append(&mut j, text, size, colour, false);
    j
}

fn append(j: &mut egui::text::LayoutJob, text: &str, size: f32, colour: Color32, strike: bool) {
    let mut run = String::new();
    let mut sym = false;
    let push = |j: &mut egui::text::LayoutJob, run: &str, sym: bool| {
        if run.is_empty() { return; }
        let mut f = egui::TextFormat { font_id: if sym { symbol_font(size) } else { body(size) }, color: colour, ..Default::default() };
        if strike { f.strikethrough = Stroke::new(1.0, colour); }
        j.append(run, 0.0, f);
    };
    for c in text.chars() {
        let is = c as u32 > 0xff;
        if is != sym && !run.is_empty() {
            push(j, &run, sym);
            run.clear();
        }
        sym = is;
        run.push(c);
    }
    push(j, &run, sym);
}

fn rich(text: &str, size: f32, colour: Color32) -> egui::WidgetText {
    job(text, size, colour).into()
}

#[derive(PartialEq, Clone, Copy)]
enum Drawer { None, Settings, Dink }

struct Line {
    id: Option<i64>,
    who: i64,
    host: bool,
    name: String,
    queued: bool,
    text: String,
    when: String,
    hidden: bool,
    note: bool,
    ping: bool,
}

struct PollForm { question: String, options: String, seconds: u32 }

struct Ping { at: Instant, x: f32, y: f32, name: String, n: u64 }

/// The settings drawer: four bars, a sample of RuneLite as viewers would get it, and what it costs.
struct SettingsPanel {
    fps: u32,
    width: u32,
    quality: u8,
    viewers: u32,
    saved: (u32, u32, u8, u32),
    sample: Option<Arc<Image>>,
    sample_note: String,
    taking: Arc<Mutex<Option<Result<Image, String>>>>,
    shown: Option<(egui::TextureHandle, (u32, u32), f64, (u32, u32, u8))>,
    status: (String, Color32),
}

pub struct App {
    settings: Shared,
    tx: Transmit,
    sound: Arc<Mutex<sound::Status>>,
    dink: Arc<Mutex<dink::Status>>,
    desk: Desk,
    lines: Vec<Line>,
    poll: Option<Value>,
    poll_ends: Instant,
    poll_form: Option<PollForm>,
    entry: String,
    up: bool,
    live: bool,
    open: bool,
    viewers: u32,
    portal_bps: Option<u64>,
    pc_bps: Arc<Mutex<Option<u64>>>,
    started: Instant,
    drawer: Drawer,
    panel: Option<SettingsPanel>,
    attach_code: String,
    attach_note: String,
    by_hand: bool,
    hand: (String, String, String, String),
    pings: Vec<Ping>,
    ping_n: u64,
    asking_close: bool,
    quitting: bool,
    hidden: bool,
    tray: Option<tray_icon::TrayIcon>,
    tray_items: Option<TrayItems>,
    tray_rx: std::sync::mpsc::Receiver<tray_icon::menu::MenuEvent>,
    newer: Arc<Mutex<Option<String>>>,
    told_newer: bool,
    webhook_shown: bool,
}

struct TrayItems {
    open: tray_icon::menu::MenuItem,
    live: tray_icon::menu::MenuItem,
    modes: Vec<(String, tray_icon::menu::CheckMenuItem)>,
    quit: tray_icon::menu::MenuItem,
}

pub fn icon_rgba() -> (Vec<u8>, u32, u32) {
    let dec = png::Decoder::new(std::io::Cursor::new(include_bytes!("../assets/icon.png").as_slice()));
    let mut r = dec.read_info().expect("the icon");
    let mut buf = vec![0; r.output_buffer_size().unwrap_or(64 * 64 * 4)];
    let info = r.next_frame(&mut buf).expect("the icon");
    buf.truncate(info.buffer_size());
    (buf, info.width, info.height)
}

impl App {
    pub fn new(cc: &eframe::CreationContext, settings: Shared, tx: Transmit, sound: Arc<Mutex<sound::Status>>,
               dink: Arc<Mutex<dink::Status>>) -> App {
        let ctx = cc.egui_ctx.clone();
        fonts(&ctx);
        style(&ctx);
        let wake = ctx.clone();
        let desk = Desk::start(settings.clone(), move || wake.request_repaint());
        // this PC's whole upload, a second at a time
        let pc_bps = Arc::new(Mutex::new(None));
        {
            let (pc, ctx) = (pc_bps.clone(), ctx.clone());
            std::thread::spawn(move || {
                let mut last: Option<(u64, Instant)> = None;
                loop {
                    if let Some(sent) = win::bytes_sent() {
                        let now = Instant::now();
                        if let Some((was, at)) = last {
                            if sent >= was {
                                *pc.lock().unwrap() = Some(((sent - was) as f64 * 8.0 / (now - at).as_secs_f64().max(0.001)) as u64);
                                ctx.request_repaint();
                            }
                        }
                        last = Some((sent, now));
                    }
                    std::thread::sleep(Duration::from_secs(1));
                }
            });
        }
        // the tray (friends have no field station): its menu reaches the window even while it's hidden
        let (tray_tx, tray_rx) = std::sync::mpsc::channel();
        {
            let ctx = ctx.clone();
            tray_icon::menu::MenuEvent::set_event_handler(Some(move |e| {
                let _ = tray_tx.send(e);
                ctx.request_repaint();
            }));
        }
        let (tray, tray_items) = make_tray(&settings.get());
        // a newer version, if the repository has one (it never updates itself)
        let newer = Arc::new(Mutex::new(None));
        if settings.get().check_updates {
            let n = newer.clone();
            std::thread::spawn(move || {
                let r = ureq::get("https://api.github.com/repos/jhmcubed/outside-portal/releases/latest")
                    .header("User-Agent", "outside-portal").call();
                if let Ok(mut r) = r {
                    if let Ok(v) = r.body_mut().read_to_string().map(|t| serde_json::from_str::<Value>(&t).unwrap_or(Value::Null)) {
                        if let Some(tag) = v.get("tag_name").and_then(|x| x.as_str()) {
                            if newer_than(tag.trim_start_matches('v'), env!("CARGO_PKG_VERSION")) {
                                *n.lock().unwrap() = Some(tag.to_string());
                            }
                        }
                    }
                }
            });
        }
        let s = settings.get();
        let mut app = App {
            settings, tx, sound, dink, desk, lines: Vec::new(), poll: None, poll_ends: Instant::now(), poll_form: None,
            entry: String::new(), up: false, live: false, open: false, viewers: 0, portal_bps: None, pc_bps,
            started: Instant::now(), drawer: Drawer::None, panel: None,
            attach_code: String::new(), attach_note: String::new(), by_hand: false,
            hand: (s.relay.clone(), s.channel.clone(), String::new(), s.code.clone()),
            pings: Vec::new(), ping_n: 0, asking_close: false, quitting: false, hidden: false,
            tray, tray_items, tray_rx, newer, told_newer: false, webhook_shown: false,
        };
        if !s.attached() {
            if let Some(code) = win::paste_text().filter(|c| c.trim().starts_with("portal1:")) {
                app.attach_code = code.trim().to_string();
                app.attach_note = "an attach code was on the clipboard".into();
            }
        }
        if std::env::args().any(|a| a == "--show-settings") {
            app.open_drawer(&ctx, Drawer::Settings);
        } else if std::env::args().any(|a| a == "--show-dink") {
            app.open_drawer(&ctx, Drawer::Dink);
        }
        app
    }

    fn note(&mut self, text: impl Into<String>) {
        self.lines.push(Line { id: None, who: 0, host: false, name: String::new(), queued: false, text: text.into(),
                               when: String::new(), hidden: false, note: true, ping: false });
    }

    // ---- what comes in ----

    fn drain(&mut self, ctx: &egui::Context) {
        while let Ok(m) = self.desk.inbox.try_recv() {
            match m {
                In::Up(name) => {
                    self.up = true;
                    self.note(format!("connected · your lines go out as {}", name));
                }
                In::Down => { self.up = false; self.live = false; self.open = false; }
                In::Note(n) => self.note(n),
                In::Msg(v) => self.message(v),
            }
        }
        while let Ok(e) = self.tray_rx.try_recv() {
            self.tray_event(ctx, e);
        }
    }

    fn message(&mut self, m: Value) {
        if let Some(r) = m.get("relay") {
            self.live = r.get("live").and_then(|x| x.as_bool()).unwrap_or(false);
            self.viewers = r.get("viewers").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
            self.open = r.get("open").and_then(|x| x.as_bool()).unwrap_or(false);
        } else if let Some(c) = m.get("chat") {
            let id = c.get("id").and_then(|x| x.as_i64());
            if id.is_some() && self.lines.iter().any(|l| l.id == id) {
                return; // (a reconnect hands over the lines so far again)
            }
            let host = c.get("host").and_then(|x| x.as_bool()).unwrap_or(false);
            let at = c.get("at").and_then(|x| x.as_i64()).unwrap_or(0);
            self.lines.push(Line {
                id, who: c.get("who").and_then(|x| x.as_i64()).unwrap_or(0), host,
                name: c.get("name").and_then(|x| x.as_str()).unwrap_or("").into(),
                queued: c.get("queued").and_then(|x| x.as_bool()).unwrap_or(false),
                text: c.get("text").and_then(|x| x.as_str()).unwrap_or("").into(),
                when: local_hhmm(at), hidden: false, note: false, ping: false,
            });
            if !host {
                win::flash(TITLE);
            }
        } else if let Some(id) = m.get("unsay").and_then(|x| x.as_i64()) {
            for l in self.lines.iter_mut().filter(|l| l.id == Some(id)) { l.hidden = true; }
        } else if let Some(p) = m.get("poll") {
            self.poll_ends = Instant::now() + Duration::from_secs(p.get("left").and_then(|x| x.as_u64()).unwrap_or(0));
            self.poll = Some(p.clone());
        } else if m.get("poll_gone").is_some() {
            // a closed poll's result goes after a minute and a half (the relay's POLL_KEPT)
            self.poll = None;
        } else if let Some(u) = m.get("upload") {
            self.portal_bps = u.get("bps").and_then(|x| x.as_u64());
        } else if let Some(p) = m.get("ping") {
            let name = p.get("name").and_then(|x| x.as_str()).unwrap_or("a visitor").to_string();
            self.lines.push(Line { id: None, who: 0, host: false, name: String::new(), queued: false,
                                   text: format!("{} pointed at something", if name.is_empty() { "a visitor" } else { &name }),
                                   when: String::new(), hidden: false, note: true, ping: true });
            if self.settings.get().pings {
                if let Some((l, t, r, b)) = frame_rect(&self.settings.get()) {
                    let (x, y) = (p.get("x").and_then(|x| x.as_f64()).unwrap_or(0.5), p.get("y").and_then(|x| x.as_f64()).unwrap_or(0.5));
                    self.ping_n += 1;
                    self.pings.push(Ping { at: Instant::now(), x: (l as f64 + (r - l) as f64 * x) as f32,
                                           y: (t as f64 + (b - t) as f64 * y) as f32, name, n: self.ping_n });
                }
            }
        }
    }

    // ---- going live ----

    fn go_live(&mut self) {
        let s = self.settings.get();
        if s.on_air && self.up && self.live {
            let _ = self.settings.update(|s| s.on_air = false);
            self.note("the stream's ended: Outside shows the portal off air");
        } else {
            if let Err(e) = self.settings.update(|s| s.on_air = true) {
                self.note(format!("couldn't go live: {}", e));
            }
            self.started = Instant::now();
        }
    }

    fn status_line(&self) -> (String, Color32) {
        let s = self.settings.get();
        let tx = self.tx.status.lock().unwrap().clone();
        if let Some(why) = &tx.stopped {
            return (format!("stopped: {}", why), PINK);
        }
        if !self.up {
            return if self.started.elapsed().as_secs() < 8 { ("starting the portal…".into(), GREY) }
                   else { ("the relay isn't answering (it keeps trying)".into(), GREY) };
        }
        if !s.on_air {
            ("off air · the stream's ended".into(), GREY)
        } else if self.live && self.open {
            (format!("live · {} watching", self.viewers), PINK)
        } else if self.live {
            (format!("paused · the Pause key, or RuneLite minimised · {} watching", self.viewers), TEAL)
        } else {
            ("going live: looking for RuneLite…".into(), GREY)
        }
    }

    // ---- the drawers ----

    fn open_drawer(&mut self, ctx: &egui::Context, which: Drawer) {
        let size = ctx.input(|i| i.viewport().inner_rect.map(|r| r.size())).unwrap_or(Vec2::new(480.0, 640.0));
        let was = self.drawer;
        let target = if was == which { Drawer::None } else { which };
        let w = match (was == Drawer::None, target == Drawer::None) {
            (true, false) => size.x + DRAWER_W,
            (false, true) => (size.x - DRAWER_W).max(380.0),
            _ => size.x,
        };
        self.drawer = target;
        if target == Drawer::Settings && self.panel.is_none() {
            self.panel = Some(SettingsPanel::new(&self.settings.get()));
        }
        if target != Drawer::Settings {
            self.panel = None;
        }
        ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(Vec2::new(w, size.y.max(if target == Drawer::None { 0.0 } else { 700.0 }))));
    }

    // ---- the tray ----

    fn tray_event(&mut self, ctx: &egui::Context, e: tray_icon::menu::MenuEvent) {
        let Some(items) = &self.tray_items else { return };
        if e.id == *items.open.id() {
            self.show(ctx);
        } else if e.id == *items.live.id() {
            self.go_live();
        } else if e.id == *items.quit.id() {
            self.quitting = true;
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        } else if let Some((m, _)) = items.modes.iter().find(|(_, i)| e.id == *i.id()) {
            let m = m.clone();
            let _ = self.settings.update(|s| s.sound = m);
        }
    }

    fn show(&mut self, ctx: &egui::Context) {
        self.hidden = false;
        ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
        // (brought to the front by Windows' own call, never egui's Focus: winit's focus sends a pretend Alt key)
        win::show_window(TITLE);
    }

    fn sync_tray(&self) {
        let Some(items) = &self.tray_items else { return };
        let s = self.settings.get();
        items.live.set_text(if s.on_air && self.up && self.live { "end stream" } else { "go live" });
        for (m, i) in &items.modes {
            i.set_checked(*m == sound::mode_of(&s.sound));
        }
        if let Some(t) = &self.tray {
            let _ = t.set_tooltip(Some(format!("the portal · {}", self.status_line().0)));
        }
    }
}

fn make_tray(_s: &Settings) -> (Option<tray_icon::TrayIcon>, Option<TrayItems>) {
    use tray_icon::menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem, Submenu};
    let open = MenuItem::new("open the portal", true, None);
    let live = MenuItem::new("go live", true, None);
    let modes: Vec<(String, CheckMenuItem)> =
        LABELS.iter().map(|(m, l)| (m.to_string(), CheckMenuItem::new(*l, true, false, None))).collect();
    let quit = MenuItem::new("quit", true, None);
    let snd = Submenu::new("sound", true);
    for (_, i) in &modes {
        let _ = snd.append(i);
    }
    let menu = Menu::new();
    let _ = menu.append_items(&[&open, &live, &snd, &PredefinedMenuItem::separator(), &quit]);
    let (rgba, w, h) = icon_rgba();
    let icon = tray_icon::Icon::from_rgba(rgba, w, h).ok();
    let mut b = tray_icon::TrayIconBuilder::new().with_menu(Box::new(menu)).with_tooltip("the portal");
    if let Some(i) = icon {
        b = b.with_icon(i);
    }
    match b.build() {
        Ok(t) => (Some(t), Some(TrayItems { open, live, modes, quit })),
        Err(_) => (None, None),
    }
}

// ---- the look ----

fn fonts(ctx: &egui::Context) {
    let mut f = egui::FontDefinitions::default();
    f.font_data.insert("chat".into(), Arc::new(egui::FontData::from_static(include_bytes!("../assets/fonts/runescape-chat.ttf"))));
    f.font_data.insert("large".into(), Arc::new(egui::FontData::from_static(include_bytes!("../assets/fonts/runescape-large.ttf"))));
    f.families.get_mut(&egui::FontFamily::Proportional).unwrap().insert(0, "chat".into());
    let mut title = vec!["large".to_string()];
    title.extend(f.families[&egui::FontFamily::Proportional].iter().skip(1).cloned());
    f.families.insert(egui::FontFamily::Name("title".into()), title);
    // RuneScape Chat draws anything it lacks as a box: past Latin-1 (✼ ▸ ◂ ×) egui's own fonts draw it
    let mut symbols: Vec<String> = Vec::new();
    // Windows' own symbol font (every Windows 10 and 11 has it), then the title's, then egui's
    let windir = std::env::var("WINDIR").unwrap_or_else(|_| r"C:\Windows".into());
    if let Ok(bytes) = std::fs::read(std::path::Path::new(&windir).join("Fonts").join("seguisym.ttf")) {
        f.font_data.insert("seguisym".into(), Arc::new(egui::FontData::from_owned(bytes)));
        symbols.push("seguisym".into());
    }
    symbols.push("large".into());
    symbols.extend(f.families[&egui::FontFamily::Proportional].iter().skip(1).cloned());
    f.families.insert(egui::FontFamily::Name("symbols".into()), symbols);
    ctx.set_fonts(f);
}

fn style(ctx: &egui::Context) {
    let mut v = egui::Visuals::dark();
    v.panel_fill = ABYSS;
    v.window_fill = ABYSS;
    v.extreme_bg_color = FIELD;
    v.override_text_color = Some(TEXT);
    v.selection.bg_fill = TEAL;
    v.selection.stroke = Stroke::new(1.0, LIME);
    v.text_cursor.stroke = Stroke::new(2.0, LIME);
    v.widgets.inactive.bg_stroke = Stroke::new(1.0, DIM);
    v.widgets.hovered.bg_stroke = Stroke::new(1.0, TEAL);
    v.widgets.active.bg_stroke = Stroke::new(1.0, LIME);
    v.widgets.inactive.weak_bg_fill = ABYSS;
    v.widgets.hovered.weak_bg_fill = ABYSS;
    v.widgets.active.weak_bg_fill = ABYSS;
    v.widgets.inactive.corner_radius = egui::CornerRadius::ZERO;
    v.widgets.hovered.corner_radius = egui::CornerRadius::ZERO;
    v.widgets.active.corner_radius = egui::CornerRadius::ZERO;
    v.widgets.noninteractive.corner_radius = egui::CornerRadius::ZERO;
    v.menu_corner_radius = egui::CornerRadius::ZERO;
    v.window_corner_radius = egui::CornerRadius::ZERO;
    ctx.set_visuals(v);
    ctx.all_styles_mut(|s| {
        s.text_styles.insert(egui::TextStyle::Body, body(16.0));
        s.text_styles.insert(egui::TextStyle::Button, body(16.0));
        s.text_styles.insert(egui::TextStyle::Small, body(14.0));
        s.spacing.item_spacing = Vec2::new(6.0, 4.0);
    });
}

/// A button as the station's: lime words in a thin dim edge, teal on hover; `lit` for the one in use.
fn button(ui: &mut egui::Ui, text: &str, small: bool, lit: Option<bool>) -> egui::Response {
    let colour = match lit { Some(true) => LIME, Some(false) => GREY, None => LIME };
    let edge = match lit { Some(true) => LIME, _ => DIM };
    let sz = if small { 14.0 } else { 16.0 };
    let galley = ui.painter().layout_job(job(text, sz, colour));
    let size = galley.size() + Vec2::new(12.0, 4.0);
    let (rect, resp) = ui.allocate_exact_size(size, Sense::click());
    let hover = resp.hovered();
    let p = ui.painter();
    p.rect_stroke(rect, 0.0, Stroke::new(1.0, if hover { TEAL } else { edge }), egui::StrokeKind::Inside);
    let g = ui.painter().layout_job(job(text, sz, if hover { TEAL } else { colour }));
    p.galley(rect.center() - g.size() / 2.0, g, colour);
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
}

fn pink_button(ui: &mut egui::Ui, text: &str, pink: bool) -> egui::Response {
    let (colour, edge) = if pink { (PINK, PINK) } else { (GREY, DIM) };
    let g = ui.painter().layout_no_wrap(text.to_string(), body(16.0), colour);
    let (rect, resp) = ui.allocate_exact_size(g.size() + Vec2::new(12.0, 4.0), Sense::click());
    let hover = resp.hovered();
    ui.painter().rect_stroke(rect, 0.0, Stroke::new(1.0, if hover { TEAL } else { edge }), egui::StrokeKind::Inside);
    let g = ui.painter().layout_no_wrap(text.to_string(), body(16.0), if hover { TEAL } else { colour });
    ui.painter().galley(rect.center() - g.size() / 2.0, g, colour);
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
}

/// Blocks on the 2 px grid, as the station's bars: `on` of `cells` lit.
fn blocks(ui: &mut egui::Ui, cells: usize, fills: impl Fn(usize) -> Color32, h: f32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::new(cells as f32 * 6.0 + 2.0, h), Sense::hover());
    for i in 0..cells {
        let x = rect.left() + 1.0 + i as f32 * 6.0;
        ui.painter().rect_filled(egui::Rect::from_min_size(egui::pos2(x, rect.top() + 1.0), Vec2::new(4.0, h - 2.0)), 0.0, fills(i));
    }
}

/// A stepped bar: click or drag along it, the wheel or the arrow keys step it.
fn bar(ui: &mut egui::Ui, label: &str, unit: &str, lo: u32, hi: u32, step: u32, value: &mut u32) -> bool {
    let mut changed = false;
    ui.horizontal(|ui| {
        ui.label(rich(&label, 16.0, LIME));
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.label(rich(&format!("{} {}", value, unit).trim().to_string(), 16.0, LIME));
        });
    });
    let w = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(w, 20.0), Sense::click_and_drag());
    let snap = |v: f32| -> u32 {
        let v = v.clamp(lo as f32, hi as f32);
        lo + (((v - lo as f32) / step as f32).round() as u32) * step
    };
    if let Some(p) = resp.interact_pointer_pos() {
        let v = snap(lo as f32 + (hi - lo) as f32 * (p.x - rect.left() - 2.0) / (rect.width() - 4.0).max(1.0));
        if v != *value { *value = v; changed = true; }
        resp.request_focus();
    }
    if resp.hovered() {
        let d = ui.input(|i| i.smooth_scroll_delta.y);
        if d != 0.0 {
            let v = if d > 0.0 { (*value + step).min(hi) } else { value.saturating_sub(step).max(lo) };
            if v != *value { *value = v; changed = true; }
        }
    }
    if resp.has_focus() {
        let (l, r) = ui.input(|i| (i.key_pressed(egui::Key::ArrowLeft), i.key_pressed(egui::Key::ArrowRight)));
        if l && *value > lo { *value = value.saturating_sub(step).max(lo); changed = true; }
        if r && *value < hi { *value = (*value + step).min(hi); changed = true; }
    }
    let p = ui.painter();
    p.rect_stroke(rect, 0.0, Stroke::new(1.0, if resp.hovered() || resp.has_focus() { TEAL } else { DIM }), egui::StrokeKind::Inside);
    let cells = (((rect.width() - 4.0) / 6.0) as usize).max(8);
    let on = (cells as f32 * (*value - lo) as f32 / (hi - lo).max(1) as f32).round() as usize;
    for i in 0..cells {
        let x = rect.left() + 3.0 + i as f32 * 6.0;
        let fill = if i + 1 == on { BRIGHT } else if i < on { LIME } else { TRACK };
        p.rect_filled(egui::Rect::from_min_size(egui::pos2(x, rect.top() + 4.0), Vec2::new(4.0, rect.height() - 8.0)), 0.0, fill);
    }
    ui.add_space(8.0);
    changed
}

fn field(ui: &mut egui::Ui, text: &mut String, width: f32, hint: &str, secret: bool) -> egui::Response {
    ui.add(egui::TextEdit::singleline(text).desired_width(width).hint_text(rich(&hint, 16.0, WHEN))
        .password(secret).font(body(16.0)).text_color(Color32::from_rgb(0xee, 0xee, 0xee)))
}

fn local_hhmm(ms: i64) -> String {
    use windows::Win32::Foundation::{FILETIME, SYSTEMTIME};
    use windows::Win32::System::Time::{FileTimeToSystemTime, SystemTimeToTzSpecificLocalTime};
    // ms since 1970 -> FILETIME (100 ns since 1601)
    let ft = (ms as u64).saturating_mul(10_000) + 116_444_736_000_000_000;
    let f = FILETIME { dwLowDateTime: ft as u32, dwHighDateTime: (ft >> 32) as u32 };
    let (mut utc, mut local) = (SYSTEMTIME::default(), SYSTEMTIME::default());
    unsafe {
        if FileTimeToSystemTime(&f, &mut utc).is_err() || SystemTimeToTzSpecificLocalTime(None, &utc, &mut local).is_err() {
            return String::new();
        }
    }
    format!("{:02}:{:02}", local.wHour, local.wMinute)
}

fn newer_than(a: &str, b: &str) -> bool {
    let p = |s: &str| s.split('.').map(|x| x.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    p(a) > p(b)
}

/// Where the portal's frame is on the screen (physical pixels): RuneLite's canvas, or its 3D view when only that
/// goes out (fixed mode, view "viewport").
fn frame_rect(s: &Settings) -> Option<(i32, i32, i32, i32)> {
    let (hwnd, _) = win::find_window(&s.title)?;
    if win::minimised(hwnd) {
        return None;
    }
    let (l, t, r, b) = win::find_canvas(hwnd).unwrap_or_else(|| win::rect(hwnd));
    if s.view == "viewport" {
        let (w, h) = ((r - l) as f64, (b - t) as f64);
        let (fw, fh) = capture::FIXED;
        if (w / h.max(1.0) - fw / fh).abs() < 0.02 {
            let (vx, vy, vw, vh) = capture::FIXED_VIEWPORT;
            let k = w / fw;
            return Some((l + (vx * k) as i32, t + (vy * k) as i32, l + ((vx + vw) * k) as i32, t + ((vy + vh) * k) as i32));
        }
    }
    Some((l, t, r, b))
}

// ---- the settings drawer ----

impl SettingsPanel {
    fn new(s: &Settings) -> SettingsPanel {
        let saved = (s.fps.clamp(1, 60), s.width.clamp(192, 1536), s.quality.clamp(10, 95), s.max_viewers.clamp(1, 20));
        let mut p = SettingsPanel { fps: saved.0, width: saved.1, quality: saved.2, viewers: saved.3, saved,
                                    sample: None, sample_note: String::new(), taking: Arc::new(Mutex::new(None)),
                                    shown: None, status: (String::new(), DIM) };
        p.take_sample(s);
        p
    }

    fn take_sample(&mut self, s: &Settings) {
        self.status = ("taking a sample from RuneLite…".into(), DIM);
        let (slot, s) = (self.taking.clone(), s.clone());
        std::thread::spawn(move || {
            *slot.lock().unwrap() = Some(capture::sample(&s));
        });
    }

    fn values(&self) -> (u32, u32, u8, u32) { (self.fps, self.width, self.quality, self.viewers) }

    fn ui(&mut self, ui: &mut egui::Ui, settings: &Shared) {
        if let Some(got) = self.taking.lock().unwrap().take() {
            match got {
                Ok(img) => {
                    self.sample_note = format!("a sample of RuneLite as it is now, the game {} px wide", img.w);
                    self.status = (self.sample_note.clone(), DIM);
                    self.sample = Some(Arc::new(img));
                    self.shown = None;
                }
                Err(why) => self.status = (format!("no sample: {}. The numbers below wait for one (open RuneLite, then take a new sample).", why), DIM),
            }
        }
        ui.label(rich("the stream's settings", 16.0, LIME));
        if self.values() != self.saved {
            ui.label(rich("not saved", 14.0, PINK));
        } else {
            ui.label(rich("what the portal sends, and what it costs", 16.0, DIM));
        }
        ui.add_space(8.0);
        bar(ui, "frames a second", "a second", 1, 60, 1, &mut self.fps);
        bar(ui, "width", "px", 192, 1536, 16, &mut self.width);
        let mut q = self.quality as u32;
        if bar(ui, "quality", "", 10, 95, 1, &mut q) { self.quality = q as u8; }
        bar(ui, "viewers at once", "at most", 1, 20, 1, &mut self.viewers);
        // the picture as viewers get it, scaled to fit with hard pixels
        let boxsize = Vec2::new(512.0, 300.0);
        let (rect, _) = ui.allocate_exact_size(boxsize, Sense::hover());
        ui.painter().rect_filled(rect, 0.0, FIELD);
        let mut weight = format!("{} a second, {} px wide, quality {}, {} watching at most", self.fps, self.width, self.quality, self.viewers);
        if let Some(img) = &self.sample {
            let key = (self.width.min(img.w), 0, self.quality);
            if self.shown.as_ref().map_or(true, |s| s.3 != (key.0, key.1, key.2)) {
                let small = img.shrunk(self.width);
                let jpg = small.jpeg(0, self.quality);
                let kb = (jpg.len() - 4) as f64 / 1024.0;
                if let Some((rgb, w, h)) = decode(&jpg[4..]) {
                    let ci = egui::ColorImage::from_rgb([w as usize, h as usize], &rgb);
                    let tex = ui.ctx().load_texture("sample", ci, egui::TextureOptions::NEAREST);
                    self.shown = Some((tex, (w, h), kb, key));
                }
            }
            if let Some((tex, (w, h), kb, _)) = &self.shown {
                let k = (boxsize.x / *w as f32).min(boxsize.y / *h as f32);
                let size = Vec2::new((*w as f32 * k).floor(), (*h as f32 * k).floor());
                let r = egui::Rect::from_center_size(rect.center(), size);
                ui.painter().image(tex.id(), r, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), Color32::WHITE);
                let capped = if self.width > img.w { format!("  (the game is only {} px wide)", img.w) } else { String::new() };
                let mbit = kb * self.fps as f64 * 8.0 / 1024.0;
                weight = format!("{} × {}, about {:.1} KB a frame{}\n{} a second: about {:.0} KB/s, {:.1} Mbit/s up for each viewer\n{} watching at most: about {:.1} Mbit/s up in all",
                                 w, h, kb, capped, self.fps, kb * self.fps as f64, mbit, self.viewers, mbit * self.viewers as f64);
                if settings.get().relay.starts_with("wss://") {
                    weight += "\n(on Cloudflare your upload sends one copy, however many watch)";
                }
            }
        }
        ui.label(rich(&weight, 16.0, LIME));
        ui.add_space(4.0);
        ui.add(egui::Label::new(rich(&self.status.0.clone(), 16.0, self.status.1)).wrap());
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            if button(ui, "take a new sample", false, None).clicked() { self.take_sample(&settings.get()); }
            if button(ui, "as saved", false, None).clicked() {
                (self.fps, self.width, self.quality, self.viewers) = self.saved;
                self.status = ("as saved".into(), DIM);
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if button(ui, "save", false, None).clicked() {
                    let (f, w, q, v) = self.values();
                    match settings.update(|s| { s.fps = f; s.width = w; s.quality = q; s.max_viewers = v; }) {
                        Ok(()) => {
                            self.saved = self.values();
                            self.status = ("saved: it all takes at once, the viewer cap too".into(), LIME);
                        }
                        Err(e) => self.status = (format!("couldn't save: {}", e), PINK),
                    }
                }
            });
        });
        ui.add_space(12.0);
        more(ui, settings);
    }
}

fn decode(jpg: &[u8]) -> Option<(Vec<u8>, u32, u32)> {
    let mut d = jpeg_decoder::Decoder::new(jpg);
    let px = d.decode().ok()?;
    let info = d.info()?;
    Some((px, info.width as u32, info.height as u32))
}

/// The rest of the capture's and the channel's settings (what settings.json held by hand).
fn more(ui: &mut egui::Ui, settings: &Shared) {
    egui::CollapsingHeader::new(rich("more", 16.0, LIME)).id_salt("more").show(ui, |ui| {
        let mut s = settings.get();
        let before = serde_json::to_string(&s).unwrap_or_default();
        let choice = |ui: &mut egui::Ui, label: &str, value: &mut String, options: &[(&str, &str)]| {
            ui.horizontal(|ui| {
                ui.label(rich(&label, 16.0, GREY));
                for (v, l) in options {
                    if button(ui, l, true, Some(value == v)).clicked() { *value = v.to_string(); }
                }
            });
        };
        choice(ui, "what goes out:", &mut s.view, &[("canvas", "the whole game"), ("viewport", "fixed mode's 3D view")]);
        choice(ui, "blacked out:", &mut s.mask, &[("none", "nothing"), ("chat", "the chat"), ("side", "the side panel"), ("both", "both")]);
        choice(ui, "taken by:", &mut s.method, &[("auto", "the first that works"), ("gpu", "Windows Graphics Capture"), ("gdi", "PrintWindow")]);
        ui.checkbox(&mut s.check_updates, rich("look for a newer version on GitHub when this opens", 16.0, GREY));
        ui.checkbox(&mut s.only_logged_in, rich("nothing while logged out (needs RuneLite's \"username in title\")", 16.0, GREY));
        choice(ui, "the pause key:", &mut s.pause_key, &[("pause", "Pause"), ("scrolllock", "Scroll Lock"), ("f12", "F12")]);
        choice(ui, "the sound key:", &mut s.sound_key, &[("scrolllock", "Scroll Lock"), ("pause", "Pause"), ("f12", "F12")]);
        ui.horizontal(|ui| {
            ui.label(rich("each sound bus:", 16.0, GREY));
            ui.add(egui::DragValue::new(&mut s.sound_kbps).range(32..=256).suffix(" kbit/s"));
            ui.label(rich("your upload:", 16.0, GREY));
            ui.add(egui::DragValue::new(&mut s.upload_mbit).range(1.0..=10000.0).suffix(" Mbit/s"));
        });
        ui.horizontal(|ui| {
            ui.label(rich("RuneLite's title starts:", 16.0, GREY));
            field(ui, &mut s.title, 140.0, "RuneLite", false);
        });
        ui.add_space(6.0);
        ui.label(rich("the channel", 16.0, LIME));
        ui.horizontal(|ui| {
            ui.label(rich("your name in the chat:", 16.0, GREY));
            field(ui, &mut s.chat_name, 160.0, &s.channel.clone(), false);
        });
        ui.horizontal(|ui| {
            ui.label(rich("watch code (empty: anyone):", 16.0, GREY));
            field(ui, &mut s.code, 140.0, "", false);
        });
        ui.label(rich(&format!("relay {} · channel {}", s.relay, s.channel), 14.0, DIM));
        let detach = button(ui, "attach another code…", true, None).clicked();
        if detach {
            s.key.clear();
        }
        if serde_json::to_string(&s).unwrap_or_default() != before {
            let s2 = s.clone();
            let _ = settings.update(|x| *x = s2);
        }
    });
}

// ---- the dink drawer ----

fn dink_drawer(app: &mut App, ui: &mut egui::Ui) {
    let st = app.dink.lock().unwrap().clone();
    let mut s = app.settings.get();
    let before = serde_json::to_string(&s).unwrap_or_default();
    ui.label(rich("Dink: what goes into your broadcast", 16.0, LIME));
    ui.add_space(4.0);
    match (&st.listening, &st.error) {
        (Some(url), _) => {
            ui.label(rich("Dink's address (in RuneLite: Dink ▸ Primary Webhook URLs, on a line of its own):", 16.0, GREY));
            ui.horizontal(|ui| {
                ui.label(rich(&url, 16.0, LIME));
                if button(ui, "copy", true, None).clicked() {
                    win::copy_text(url);
                    app.note("Dink's address is on the clipboard");
                }
            });
        }
        (None, Some(e)) => { ui.label(rich(&format!("not listening: {}", e), 16.0, PINK)); }
        _ => { ui.label(rich("starting…", 16.0, GREY)); }
    }
    ui.horizontal(|ui| {
        ui.label(rich("its port:", 16.0, GREY));
        ui.add(egui::DragValue::new(&mut s.dink.port).range(1024..=65535));
    });
    match &st.last {
        Some((kind, at, went)) => ui.label(rich(&format!("heard from Dink {} ago: {} (to {})", ago(at.elapsed()), kind, went), 16.0, TEAL)),
        None => ui.label(rich("nothing heard from Dink yet", 16.0, GREY)),
    };
    ui.add_space(8.0);
    ui.label(rich("on your portal", 16.0, LIME));
    egui::Grid::new("kinds").num_columns(2).spacing(Vec2::new(18.0, 2.0)).show(ui, |ui| {
        for (i, k) in REPORTS.iter().enumerate() {
            let mut on = s.dink.kind_on(k);
            let colour = if on { TEXT } else { GREY };
            if ui.checkbox(&mut on, rich(&kind_label(k), 16.0, colour)).changed() {
                s.dink.kinds.insert(k.to_string(), Value::Bool(on));
            }
            if i % 2 == 1 { ui.end_row(); }
        }
    });
    ui.horizontal(|ui| {
        ui.label(rich("loot: a stack worth at least", 16.0, GREY));
        ui.add(egui::DragValue::new(&mut s.dink.loot_min).range(0..=2_147_483_647u64).speed(1000.0).suffix(" gp"));
    });
    ui.horizontal(|ui| {
        ui.label(rich("kill counts: every", 16.0, GREY));
        ui.add(egui::DragValue::new(&mut s.dink.kc_every).range(1..=1000));
        ui.label(rich("(and always the first, and personal bests)", 16.0, GREY));
    });
    ui.label(rich("never, whatever's ticked: anything naming another player (trades, chat, player kills, a PvP killer), and screenshots", 14.0, DIM));
    ui.add_space(10.0);
    ui.label(rich("on to your Discord too", 16.0, LIME));
    forward_ui(ui, &mut s.forward, &mut app.webhook_shown);
    if serde_json::to_string(&s).unwrap_or_default() != before {
        let _ = app.settings.update(|x| *x = s);
    }
}

fn forward_ui(ui: &mut egui::Ui, rules: &mut Value, shown: &mut bool) {
    if !rules.is_object() {
        *rules = json!({"discord": [], "forward": {}});
    }
    let mut url = rules.get("discord").and_then(|x| x.as_array()).and_then(|a| a.first()).and_then(|x| x.as_str()).unwrap_or("").to_string();
    ui.horizontal(|ui| {
        ui.label(rich("webhook:", 16.0, GREY));
        let was = url.clone();
        field(ui, &mut url, 300.0, "https://discord.com/api/webhooks/…", !*shown);
        if button(ui, if *shown { "hide" } else { "show" }, true, None).clicked() { *shown = !*shown; }
        if url != was {
            rules["discord"] = if url.trim().is_empty() { json!([]) } else { json!([url.trim()]) };
        }
    });
    if url.trim().is_empty() {
        ui.label(rich("(none: nothing goes to Discord. Anyone with a webhook's address can post to its server, so it stays on this PC.)", 14.0, DIM));
        return;
    }
    let f = rules.get_mut("forward").filter(|x| x.is_object()).is_some();
    if !f {
        rules["forward"] = json!({});
    }
    let fw = rules["forward"].as_object_mut().unwrap();
    let mut kinds: Vec<&str> = REPORTS.to_vec();
    kinds.push("PLAYER_KILL");
    egui::Grid::new("forward").num_columns(2).spacing(Vec2::new(18.0, 2.0)).show(ui, |ui| {
        for (i, k) in kinds.iter().enumerate() {
            let mut on = match fw.get(*k) { Some(Value::Bool(b)) => *b, Some(Value::Object(_)) => true, _ => false };
            let colour = if on { TEXT } else { GREY };
            if ui.checkbox(&mut on, rich(&kind_label(k), 16.0, colour)).changed() {
                let v = match (*k, on) {
                    ("LOOT", true) => json!({"minValue": 1000000, "allow": []}),
                    ("KILL_COUNT", true) => json!({"every": 25, "lapsEvery": 1000, "first": true, "personalBest": true}),
                    (_, b) => Value::Bool(b),
                };
                fw.insert(k.to_string(), v);
            }
            if i % 2 == 1 { ui.end_row(); }
        }
    });
    if let Some(Value::Object(l)) = fw.get_mut("LOOT") {
        ui.horizontal(|ui| {
            ui.label(rich("loot to Discord: a stack worth", 16.0, GREY));
            let mut v = l.get("minValue").and_then(|x| x.as_u64()).unwrap_or(0);
            if ui.add(egui::DragValue::new(&mut v).speed(10000.0).suffix(" gp")).changed() { l.insert("minValue".into(), json!(v)); }
        });
        let mut allow = l.get("allow").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(", ")).unwrap_or_default();
        ui.horizontal(|ui| {
            ui.label(rich("or these items (commas, * wildcards):", 16.0, GREY));
            if field(ui, &mut allow, 220.0, "", false).changed() {
                l.insert("allow".into(), json!(allow.split(',').map(|x| x.trim()).filter(|x| !x.is_empty()).collect::<Vec<_>>()));
            }
        });
    }
    if let Some(Value::Object(k)) = fw.get_mut("KILL_COUNT") {
        ui.horizontal(|ui| {
            ui.label(rich("kill counts to Discord: every", 16.0, GREY));
            let mut v = k.get("every").and_then(|x| x.as_u64()).unwrap_or(25);
            if ui.add(egui::DragValue::new(&mut v).range(1..=10000)).changed() { k.insert("every".into(), json!(v)); }
            ui.label(rich("laps every", 16.0, GREY));
            let mut l = k.get("lapsEvery").and_then(|x| x.as_u64()).unwrap_or(1000);
            if ui.add(egui::DragValue::new(&mut l).range(1..=100000)).changed() { k.insert("lapsEvery".into(), json!(l)); }
        });
        ui.horizontal(|ui| {
            let mut first = k.get("first").and_then(|x| x.as_bool()).unwrap_or(true);
            if ui.checkbox(&mut first, rich("the first", 16.0, GREY)).changed() { k.insert("first".into(), json!(first)); }
            let mut pb = k.get("personalBest").and_then(|x| x.as_bool()).unwrap_or(true);
            if ui.checkbox(&mut pb, rich("personal bests", 16.0, GREY)).changed() { k.insert("personalBest".into(), json!(pb)); }
        });
    }
    ui.label(rich("Discord gets Dink's own post, as Dink sent it, screenshot and all. Every kind ticked here goes whole, so keep those notifiers' thresholds in Dink as you want Discord to have them.", 14.0, DIM));
}

fn kind_label(k: &str) -> &'static str {
    match k {
        "LEVEL" => "levels", "XP_MILESTONE" => "XP milestones", "LOOT" => "loot", "QUEST" => "quests",
        "CLUE" => "clues", "KILL_COUNT" => "kill counts", "COLLECTION" => "the collection log", "PET" => "pets",
        "SLAYER" => "slayer tasks", "SPEEDRUN" => "speedruns", "COMBAT_ACHIEVEMENT" => "combat achievements",
        "ACHIEVEMENT_DIARY" => "diaries", "BARBARIAN_ASSAULT_GAMBLE" => "BA gambles", "DEATH" => "deaths",
        "LEAGUES_AREA" => "Leagues areas", "LEAGUES_RELIC" => "Leagues relics", "LEAGUES_TASK" => "Leagues tasks",
        "GRAND_EXCHANGE" => "the GE", "PLAYER_KILL" => "player kills", _ => "other",
    }
}

fn ago(d: Duration) -> String {
    let s = d.as_secs();
    if s < 60 { format!("{} s", s) } else if s < 3600 { format!("{} min", s / 60) } else { format!("{} h", s / 3600) }
}

// ---- the attach screen ----

/// SAFETY.md's plain words, short (the attach screen's "is it safe for my account?")
const SAFE: &[&str] = &[
    "it never touches RuneLite or the game: it isn't a plugin, and never opens RuneLite's process",
    "it can't press a key or click for you",
    "it sees only what RuneLite's window draws, as OBS or Discord's screen share would (press Pause before a bank PIN)",
    "it never asks for your login, and never reads what RuneLite and Jagex say to each other",
    "Windows tells it only when its own two keys are pressed: Pause and Scroll Lock",
    "it sends only to the relay in your attach code, your Discord webhook if you set one, and GitHub to look for updates",
    "it needs no admin, installs nothing, and doesn't start with Windows",
];

fn attach_screen(app: &mut App, ui: &mut egui::Ui) {
    ui.add_space(24.0);
    ui.label(RichText::new("⊂λ,ｍ⊙ ✼ the portal").font(title_font(26.0)).color(LIME));
    let _ = rich;
    ui.add_space(10.0);
    ui.label(rich("this receiver is not attached to a channel yet.", 16.0, GREY));
    ui.label(rich("paste the attach code you were given (it starts portal1:).", 16.0, GREY));
    ui.add_space(8.0);
    ui.add(egui::TextEdit::multiline(&mut app.attach_code).desired_rows(3).desired_width(f32::INFINITY).font(body(14.0)));
    ui.add_space(6.0);
    ui.horizontal(|ui| {
        if button(ui, "attach", false, None).clicked() {
            match settings::parse_attach(&app.attach_code) {
                Ok((relay, channel, key)) => {
                    let _ = app.settings.update(|s| { s.relay = relay; s.channel = channel.clone(); s.key = key; });
                    app.attach_note.clear();
                    app.attach_code.clear();
                    app.note(format!("attached: {} · ghillie.wiki/outside?player={}", channel, channel));
                }
                Err(e) => app.attach_note = e,
            }
        }
        if button(ui, "paste", true, None).clicked() {
            if let Some(t) = win::paste_text() { app.attach_code = t.trim().to_string(); }
        }
        if button(ui, if app.by_hand { "by hand ◂" } else { "by hand ▸" }, true, None).clicked() { app.by_hand = !app.by_hand; }
    });
    if !app.attach_note.is_empty() {
        ui.label(rich(&app.attach_note.clone(), 16.0, PINK));
    }
    if app.by_hand {
        ui.add_space(8.0);
        ui.label(rich("a relay of your own (relay-py), or one you were told about:", 16.0, GREY));
        let (r, c, k, w) = &mut app.hand;
        egui::Grid::new("hand").num_columns(2).show(ui, |ui| {
            ui.label(rich("relay", 16.0, GREY)); field(ui, r, 300.0, "ws://127.0.0.1:8786", false); ui.end_row();
            ui.label(rich("channel", 16.0, GREY)); field(ui, c, 200.0, "your player's name", false); ui.end_row();
            ui.label(rich("key", 16.0, GREY)); field(ui, k, 200.0, "8 characters or more", true); ui.end_row();
            ui.label(rich("watch code", 16.0, GREY)); field(ui, w, 200.0, "(empty: anyone)", false); ui.end_row();
        });
        if button(ui, "use these", false, None).clicked() {
            let (r, c, k, w) = app.hand.clone();
            if !(r.starts_with("ws://") || r.starts_with("wss://")) || c.trim().is_empty() || k.len() < 8 {
                app.attach_note = "a relay (ws:// or wss://), a channel and a key of 8 characters or more".into();
            } else {
                let _ = app.settings.update(|s| { s.relay = r.trim().into(); s.channel = c.trim().into(); s.key = k; s.code = w.trim().into(); });
            }
        }
    }
    ui.add_space(14.0);
    egui::CollapsingHeader::new(rich("is it safe for my account?", 16.0, LIME)).id_salt("safe").show(ui, |ui| {
        for line in SAFE {
            ui.add(egui::Label::new(rich(line, 14.0, GREY)).wrap());
        }
        ui.add(egui::Label::new(rich("every line of it, and how to check it yourself: github.com/jhmcubed/outside-portal, SAFETY.md", 14.0, TEAL)).wrap());
    });
    if let Some(p) = settings::ghillie_path() {
        ui.add_space(14.0);
        ui.label(rich("ghillie's own portal settings are on this PC:", 16.0, GREY));
        if button(ui, "take them (its relay, channel, key and Discord rules)", true, None).clicked() {
            match settings::from_ghillie(&p) {
                Some(g) => {
                    let keep_port = app.settings.get().dink.port;
                    let _ = app.settings.update(|s| { *s = g; s.dink.port = keep_port; });
                    app.note("took ghillie portal's settings: the relay on this PC (start.py's), its channel and key");
                }
                None => app.attach_note = "those settings have no key yet: start the portal once".into(),
            }
        }
    }
}

// ---- the window ----

impl eframe::App for App {
    fn clear_color(&self, _v: &egui::Visuals) -> [f32; 4] {
        [0.0, 0.0, 0.0, 0.0] // (the ping windows are see-through; the main window paints its own black)
    }

    fn logic(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.settings.refresh();
        self.drain(ctx);
        self.sync_tray();
        if ctx.input(|i| i.viewport().close_requested()) && !self.quitting {
            let s = self.settings.get();
            if s.on_air && self.live {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                self.asking_close = true;
                if self.hidden { self.show(ctx); }
            }
        }
        // the pings' windows: about 0.6 s each
        self.pings.retain(|p| p.at.elapsed() < Duration::from_millis(640));
        ctx.request_repaint_after(Duration::from_millis(if self.pings.is_empty() { 110 } else { 30 }));
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        let newer = self.newer.lock().unwrap().clone();
        if let Some(tag) = newer {
            if !self.told_newer {
                self.told_newer = true;
                self.note(format!("a newer version is out ({}): github.com/jhmcubed/outside-portal/releases", tag));
            }
        }
        if ui.input(|i| i.key_pressed(egui::Key::Escape)) && self.poll_form.is_none() && !self.asking_close {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
        let frame = egui::Frame::new().fill(ABYSS).inner_margin(egui::Margin::symmetric(12, 10));
        if !self.settings.get().attached() {
            egui::CentralPanel::no_frame().frame(frame).show(ui, |ui| attach_screen(self, ui));
            self.pings_ui(&ctx);
            return;
        }
        if self.drawer != Drawer::None {
            egui::Panel::right("drawer").exact_size(DRAWER_W).frame(egui::Frame::new().fill(ABYSS).stroke(Stroke::new(1.0, DIM))
                .inner_margin(egui::Margin::symmetric(18, 12))).show(ui, |ui| {
                egui::ScrollArea::vertical().id_salt("drawer_scroll").show(ui, |ui| {
                    if self.drawer == Drawer::Settings {
                        if let Some(p) = &mut self.panel { p.ui(ui, &self.settings); }
                    } else {
                        dink_drawer(self, ui);
                    }
                });
            });
        }
        egui::Panel::top("top").frame(frame).show(ui, |ui| self.top(ui));
        egui::Panel::bottom("answer").frame(egui::Frame::new().fill(ABYSS).inner_margin(egui::Margin::symmetric(12, 8))).show(ui, |ui| self.answer(ui));
        egui::CentralPanel::no_frame().frame(egui::Frame::new().fill(ABYSS).inner_margin(egui::Margin::symmetric(12, 0))).show(ui, |ui| {
            self.poll_ui(ui);
            self.chat(ui);
        });
        if self.asking_close {
            self.close_modal(&ctx);
        }
        self.pings_ui(&ctx);
    }
}

impl App {
    fn top(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        ui.horizontal(|ui| {
            ui.label(RichText::new("⊂λ,ｍ⊙ ✼ the portal").font(title_font(22.0)).color(LIME));
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let s_open = self.drawer == Drawer::Settings;
                if button(ui, if s_open { "settings ◂" } else { "settings ▸" }, true, None).clicked() { self.open_drawer(&ctx, Drawer::Settings); }
                let d_open = self.drawer == Drawer::Dink;
                if button(ui, if d_open { "dink ◂" } else { "dink ▸" }, true, None).clicked() { self.open_drawer(&ctx, Drawer::Dink); }
            });
        });
        ui.add_space(6.0);
        // the live light, what's happening, and going live
        ui.horizontal(|ui| {
            let (rect, _) = ui.allocate_exact_size(Vec2::splat(18.0), Sense::hover());
            let c = rect.center();
            let on = self.settings.get().on_air;
            if self.up && self.live && self.open && on {
                const STEPS: [f32; 8] = [3.0, 4.0, 5.0, 6.0, 7.0, 6.0, 5.0, 4.0];
                let beat = (ui.input(|i| i.time) / 0.11) as usize;
                let r = STEPS[beat % STEPS.len()];
                if r >= 6.0 {
                    ui.painter().circle_stroke(c, r + 2.0, Stroke::new(2.0, Color32::from_rgb(0x5a, 0x21, 0x40)));
                }
                ui.painter().circle_filled(c, r, PINK);
            } else if self.up && self.live && on {
                ui.painter().circle_filled(c, 5.0, TEAL);
            } else {
                ui.painter().circle_stroke(c, 5.0, Stroke::new(2.0, Color32::from_rgb(0x55, 0x55, 0x55)));
            }
            let (text, colour) = self.status_line();
            let ending = on && self.up && self.live;
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if pink_button(ui, if ending { "end stream" } else { "go live" }, !ending).clicked() { self.go_live(); }
                ui.add(egui::Label::new(rich(&text, 14.0, colour)).truncate());
            });
        });
        ui.add_space(4.0);
        // the upload: the portal's share (the relay's count) and this PC's in all (Windows' own count)
        ui.horizontal(|ui| {
            let s = self.settings.get();
            let cap = if s.upload_mbit > 0.0 { s.upload_mbit } else { 40.0 };
            let pc = *self.pc_bps.lock().unwrap();
            let share = pc.unwrap_or(0) as f64 / (cap * 1e6);
            let cells = 22;
            let on = ((cells as f64 * share).round() as usize).min(cells);
            let portal_on = if self.up { ((cells as f64 * self.portal_bps.unwrap_or(0) as f64 / (cap * 1e6)).round() as usize).min(on) } else { 0 };
            blocks(ui, cells, |i| if share > 0.8 && i < on { PINK } else if i < portal_on { LIME } else if i < on { TEAL } else { TRACK }, 12.0);
            let mbit = |b: Option<u64>| b.map(|b| format!("{:.1}", b as f64 / 1e6)).unwrap_or_else(|| "…".into());
            let mut parts = Vec::new();
            if self.up && self.portal_bps.is_some() {
                let who = if s.relay.starts_with("wss://") { "the relay sends" } else { "the portal" };
                parts.push(format!("{} {}", who, mbit(self.portal_bps)));
            }
            parts.push(format!("this PC {} of {} Mbit/s", mbit(pc), cap));
            ui.label(rich(&format!("upload: {}", parts.join(" · ")), 14.0, if share > 0.8 { PINK } else { GREY }));
        });
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            let s = self.settings.get();
            ui.label(rich("sound:", 14.0, GREY));
            for (m, l) in LABELS {
                if button(ui, l, true, Some(sound::mode_of(&s.sound) == *m)).clicked() {
                    let m = m.to_string();
                    if let Err(e) = self.settings.update(|s| s.sound = m) { self.note(format!("couldn't save the sound's mode: {}", e)); }
                }
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let mut p = s.pings;
                if ui.checkbox(&mut p, rich("pings on my screen", 14.0, GREY)).changed() {
                    let _ = self.settings.update(|s| s.pings = p);
                }
            });
        });
    }

    fn chat(&mut self, ui: &mut egui::Ui) {
        let mut act: Option<Value> = None;
        let mut notes = Vec::new();
        egui::Frame::new().stroke(Stroke::new(1.0, DIM)).inner_margin(egui::Margin::symmetric(6, 4)).show(ui, |ui| {
            ui.set_min_size(ui.available_size());
            egui::ScrollArea::vertical().stick_to_bottom(true).auto_shrink([false, false]).show(ui, |ui| {
                for l in &self.lines {
                    let mut job = egui::text::LayoutJob::default();
                    let fmt = |c: Color32, strike: bool| egui::TextFormat {
                        font_id: body(16.0), color: if strike { Color32::from_rgb(0x55, 0x55, 0x55) } else { c },
                        strikethrough: if strike { Stroke::new(1.0, Color32::from_rgb(0x55, 0x55, 0x55)) } else { Stroke::NONE },
                        ..Default::default()
                    };
                    let gone = Color32::from_rgb(0x55, 0x55, 0x55);
                    let c = |x: Color32| if l.hidden { gone } else { x };
                    let _ = &fmt;
                    if l.note {
                        append(&mut job, &format!("✼ {}", l.text), 16.0, if l.ping { CYAN } else { GREY }, false);
                    } else {
                        append(&mut job, &l.name, 16.0, c(if l.host { LIME } else { CYAN }), l.hidden);
                        if l.queued { append(&mut job, " (in line)", 16.0, c(Color32::from_rgb(0x77, 0x77, 0x77)), l.hidden); }
                        append(&mut job, &format!("  {}", l.text), 16.0, c(TEXT), l.hidden);
                        append(&mut job, &format!("  {}", l.when), 16.0, c(WHEN), l.hidden);
                    }
                    job.wrap.max_width = ui.available_width();
                    let r = ui.add(egui::Label::new(job).sense(Sense::click()));
                    if let Some(id) = l.id {
                        let (who, host) = (l.who, l.host);
                        r.context_menu(|ui| {
                            if ui.button("take this line back off everyone's page").clicked() { act = Some(json!({"hide": id})); ui.close(); }
                            if !host && who != 0 {
                                if ui.button("block this viewer").clicked() { act = Some(json!({"block": who})); notes.push(format!("blocked viewer {}", who)); ui.close(); }
                                if ui.button("unblock this viewer").clicked() { act = Some(json!({"unblock": who})); notes.push(format!("unblocked viewer {}", who)); ui.close(); }
                            }
                        });
                    }
                }
            });
        });
        if let Some(a) = act { self.desk.send(a); }
        for n in notes { self.note(n); }
    }

    fn answer(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            let w = ui.available_width() - 150.0;
            let r = field(ui, &mut self.entry, w.max(80.0), "", false);
            let enter = r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
            if button(ui, "send", false, None).clicked() || enter {
                let text = self.entry.trim().to_string();
                if !text.is_empty() {
                    if self.desk.send(json!({"say": text})) { self.entry.clear(); } else { self.note("not sent: the relay isn't answering"); }
                }
                r.request_focus();
            }
            if button(ui, "a poll…", false, None).clicked() {
                self.poll_form = Some(PollForm { question: String::new(), options: String::new(), seconds: 60 });
            }
        });
    }

    fn poll_ui(&mut self, ui: &mut egui::Ui) {
        if let Some(f) = &mut self.poll_form {
            let mut send = false;
            let mut drop = false;
            egui::Frame::new().stroke(Stroke::new(1.0, LIME)).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
                ui.label(rich("the question", 14.0, GREY));
                field(ui, &mut f.question, ui.available_width(), "", false);
                ui.label(rich("the options, one a line (2 to 6)", 14.0, GREY));
                ui.add(egui::TextEdit::multiline(&mut f.options).desired_rows(4).desired_width(f32::INFINITY));
                ui.horizontal(|ui| {
                    ui.label(rich("open for", 14.0, GREY));
                    egui::ComboBox::from_id_salt("secs").selected_text(f.seconds.to_string()).show_ui(ui, |ui| {
                        for s in [30, 60, 90, 120, 180, 300, 600] { ui.selectable_value(&mut f.seconds, s, s.to_string()); }
                    });
                    ui.label(rich("seconds", 14.0, GREY));
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if button(ui, "send it out", true, None).clicked() { send = true; }
                        if button(ui, "never mind", true, None).clicked() { drop = true; }
                    });
                });
            });
            if send {
                let options: Vec<String> = f.options.lines().map(|o| o.trim().to_string()).filter(|o| !o.is_empty()).collect();
                if options.len() < 2 {
                    self.note("a poll needs at least two options");
                } else if self.desk.send(json!({"poll": {"question": f.question.trim(), "options": options.iter().take(6).collect::<Vec<_>>(), "seconds": f.seconds}})) {
                    self.poll_form = None;
                } else {
                    self.note("not sent: the relay isn't answering");
                }
            }
            if drop { self.poll_form = None; }
            ui.add_space(6.0);
            return;
        }
        let Some(p) = self.poll.clone() else { return };
        let open = p.get("open").and_then(|x| x.as_bool()).unwrap_or(false);
        let counts: Vec<u64> = p.get("counts").and_then(|x| x.as_array()).map(|a| a.iter().map(|x| x.as_u64().unwrap_or(0)).collect()).unwrap_or_default();
        let options: Vec<String> = p.get("options").and_then(|x| x.as_array()).map(|a| a.iter().map(|x| x.as_str().unwrap_or("").to_string()).collect()).unwrap_or_default();
        let total = counts.iter().sum::<u64>().max(1);
        let most = counts.iter().cloned().max().unwrap_or(0);
        let mut close = false;
        let mut dismiss = false;
        egui::Frame::new().stroke(Stroke::new(1.0, if open { LIME } else { DIM })).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
            ui.horizontal(|ui| {
                let q = p.get("question").and_then(|x| x.as_str()).filter(|q| !q.is_empty()).unwrap_or("a poll");
                ui.label(rich(&format!("{}{}", q, if open { "" } else { " · closed" }), 16.0, if open { LIME } else { GREY }));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if open {
                        if button(ui, "close it", true, None).clicked() { close = true; }
                        let left = self.poll_ends.saturating_duration_since(Instant::now()).as_secs();
                        ui.label(rich(&format!("{} s left", left), 14.0, GREY));
                    } else if button(ui, "×", true, None).clicked() {
                        dismiss = true;
                    }
                });
            });
            for (o, n) in options.iter().zip(counts.iter()) {
                let won = !open && most > 0 && *n == most;
                ui.horizontal(|ui| {
                    let (r, _) = ui.allocate_exact_size(Vec2::new(150.0, 18.0), Sense::hover());
                    ui.painter().text(r.left_center(), egui::Align2::LEFT_CENTER, o, body(14.0), if won { PINK } else { TEXT });
                    let lit = (28.0 * *n as f64 / total as f64).round() as usize;
                    blocks(ui, 28, |i| if i < lit { if won { PINK } else { LIME } } else { ABYSS }, 10.0);
                    ui.label(rich(&n.to_string(), 14.0, GREY));
                });
            }
        });
        if close { self.desk.send(json!({"poll_close": true})); }
        if dismiss { self.poll = None; }
        ui.add_space(6.0);
    }

    fn close_modal(&mut self, ctx: &egui::Context) {
        egui::Modal::new(egui::Id::new("closing")).frame(egui::Frame::new().fill(ABYSS).stroke(Stroke::new(1.0, LIME))
            .inner_margin(egui::Margin::same(16))).show(ctx, |ui| {
            ui.label(rich("the portal is live.", 16.0, LIME));
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if button(ui, "end the stream and close", false, None).clicked() {
                    let _ = self.settings.update(|s| s.on_air = false);
                    self.asking_close = false;
                    self.quitting = true;
                    ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                }
                if button(ui, "keep it going in the tray", false, None).clicked() {
                    self.asking_close = false;
                    self.hidden = true;
                    ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
                }
                if button(ui, "never mind", false, None).clicked() { self.asking_close = false; }
            });
        });
    }

    /// A ring that grows over the spot, with the viewer's name, in a see-through window that clicks pass through.
    fn pings_ui(&mut self, ctx: &egui::Context) {
        const WIDE: f32 = 520.0;
        const TALL: f32 = 160.0;
        let ppp = ctx.pixels_per_point();
        for p in &self.pings {
            let k = (p.at.elapsed().as_secs_f32() / 0.54).min(1.0); // about 0.6 s, in 6 steps of 90 ms
            let step = (k * 6.0).floor();
            let kk = step / 6.0;
            let colour = if step < 3.6 { LIME } else { DIM };
            let name_colour = if step < 6.0 { LIME } else { DIM };
            let pos = egui::pos2(p.x / ppp - WIDE / 2.0, p.y / ppp - TALL / 2.0);
            let builder = egui::ViewportBuilder::default().with_title("the portal ✼ ping").with_decorations(false)
                .with_transparent(true).with_always_on_top().with_mouse_passthrough(true).with_taskbar(false)
                .with_resizable(false).with_active(false).with_position(pos).with_inner_size(Vec2::new(WIDE, TALL));
            let name = p.name.clone();
            ctx.show_viewport_immediate(egui::ViewportId::from_hash_of(("ping", p.n)), builder, move |ui, _| {
                let c = egui::pos2(WIDE / 2.0, TALL / 2.0);
                let painter = ui.painter();
                let r = 14.0 + 28.0 * kk;
                painter.circle_stroke(c, r, Stroke::new(3.0, colour));
                painter.circle_filled(c, 2.0, colour);
                if !name.is_empty() {
                    // the name: a dark rim round it first, then the name itself
                    let at = egui::pos2(c.x + 22.0, c.y - 26.0);
                    for (dx, dy) in [(-2.0, 0.0), (2.0, 0.0), (0.0, -2.0), (0.0, 2.0), (-2.0, -2.0), (2.0, 2.0), (-2.0, 2.0), (2.0, -2.0)] {
                        painter.text(at + Vec2::new(dx, dy), egui::Align2::LEFT_CENTER, &name, body(32.0), Color32::from_rgb(10, 10, 10));
                    }
                    painter.text(at, egui::Align2::LEFT_CENTER, &name, body(32.0), name_colour);
                }
            });
        }
    }
}
