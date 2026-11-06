//! The window: the portal's whole control (portal_chat.py and portal_settings.py, rewritten; parity lines 19-32).
//!
//! One window in the abyss's black: the live light and go live / end stream, the upload line, the sound row, the
//! chat (answer in it; right-click a line to take it back or block its viewer), polls, and pings over RuneLite.
//! settings ▸ and dink ▸ pop drawers out on the right. Until it's attached to a relay it shows the attach screen.

use crate::capture::{self, Image};
use crate::desk::{Desk, In};
use crate::settings::{self, Settings, Shared, REPORTS};
use crate::transmit::Transmit;
use crate::{dink, sound, theme, win};
use eframe::egui::{self, Color32, FontId, RichText, Sense, Stroke, Vec2};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

// (its own title: ghillie's Python chat window is "the portal ✼ chat", and the two may be open at once)
pub const TITLE: &str = "⊂λ,ｍ⊙ ✼ the portal";
// the colours, as the look has them now (theme.rs: jute's browns at the tint, or anyone's own)
#[allow(non_snake_case)] fn ABYSS() -> Color32 { theme::pal().bg }
#[allow(non_snake_case)] fn LIME() -> Color32 { theme::pal().lime }
#[allow(non_snake_case)] fn BRIGHT() -> Color32 { theme::mix(theme::pal().lime, Color32::WHITE, 0.55) }
#[allow(non_snake_case)] fn DIM() -> Color32 { theme::pal().line }
#[allow(non_snake_case)] fn TRACK() -> Color32 { theme::pal().well }
#[allow(non_snake_case)] fn TEAL() -> Color32 { theme::pal().teal }
#[allow(non_snake_case)] fn CYAN() -> Color32 { theme::pal().cyan }
#[allow(non_snake_case)] fn PINK() -> Color32 { theme::pal().pink }
#[allow(non_snake_case)] fn GREY() -> Color32 { theme::pal().dim }
#[allow(non_snake_case)] fn WHEN() -> Color32 { theme::pal().faint }
#[allow(non_snake_case)] fn TEXT() -> Color32 { theme::pal().text }
#[allow(non_snake_case)] fn FIELD() -> Color32 { theme::pal().inset }
/// quiet words (where the black look had them in the lines' dim lime)
#[allow(non_snake_case)] fn QUIET() -> Color32 { theme::pal().faint }
const DRAWER_W: f32 = 560.0;
const MIN_W: f32 = 400.0;
// (11/05/2026, Jace: "everything" sounded as if it took the microphone too; it's the game and the rest of the PC's
// own sound, never a microphone)
const LABELS: &[(&str, &str)] = &[("off", "off"), ("game", "the game"), ("all", "game + desktop")];
const TITLE_H: f32 = 30.0;
/// the pings' choice: (its place, its words, what it does)
const PINGS: [(u8, &str, &str); 3] = [
    (0, "off", "viewers' clicks on the picture go nowhere"),
    (1, "the site", "a ring on every viewer's page where someone pointed"),
    (2, "the site + my screen", "on viewers' pages, and a ring over your RuneLite too: only on this PC, never in the stream"),
];
const EDGE: f32 = 6.0;
const CURSOR_SWITCH: &str = "my cursor in the picture";

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
    // (an ellipsis as three of the font's own dots: from the symbol font it stood taller than the words round it)
    let text = text.replace('…', "...");
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

/// How wide words are, laid out in one line.
fn text_w(ui: &egui::Ui, text: &str, size: f32) -> f32 {
    ui.painter().layout_job(job(text, size, TEXT())).size().x
}

/// How wide a checkbox with these words (14 px) is.
fn check_w(ui: &egui::Ui, text: &str) -> f32 {
    ui.spacing().icon_width + ui.spacing().icon_spacing + text_w(ui, text, 14.0) + 2.0
}

/// How wide a small button with these words is.
fn button_w(ui: &egui::Ui, text: &str) -> f32 {
    text_w(ui, text, 14.0) + 14.0
}

/// Words that wrap at `width`.
fn wrapped(text: &str, size: f32, colour: Color32, width: f32) -> egui::WidgetText {
    let mut j = job(text, size, colour);
    j.wrap.max_width = width;
    j.into()
}

/// Rows for things this wide in `avail`: as many to a row as fit (at least one), in order.
fn flow(widths: &[f32], avail: f32, gap: f32) -> Vec<std::ops::Range<usize>> {
    let mut rows = Vec::new();
    let (mut start, mut used) = (0, 0.0f32);
    for (i, w) in widths.iter().enumerate() {
        let need = if i == start { *w } else { used + gap + w };
        if i > start && need > avail {
            rows.push(start..i);
            start = i;
            used = *w;
        } else {
            used = need;
        }
    }
    if start < widths.len() { rows.push(start..widths.len()); }
    rows
}

/// A hover box's words (the field kit's tooltip: the words, wrapping at the box's width).
fn tip(text: &str) -> egui::WidgetText {
    let mut j = job(text, 14.0, TEXT());
    j.wrap.max_width = 320.0;
    j.into()
}

#[derive(PartialEq, Clone, Copy)]
#[cfg_attr(not(feature = "station"), allow(dead_code))]
enum Drawer { None, Settings, Dink, Friends }

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

/// a viewer's ✼ rising over RuneLite (the signal button: as many as they like)
struct Star { at: Instant, x: f32, name: String }

/// a line over the game: when it came, its name, its words, whether it's the host's
struct OverLine { at: Instant, name: String, text: String, host: bool }

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
    listening: Option<usize>,
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
    // main's round two (11/05/2026)
    static_on: bool,
    signals: u32,
    stars: Vec<Star>,
    guess: Option<Value>,
    guess_form: Option<(String, String)>,
    banned: Vec<(i64, String)>,
    over: Vec<OverLine>,
    history_until: Instant,
    moving_chat: bool,
    zoom: f32,
    feed: Arc<Mutex<crate::feed::Feed>>,
    feed_tex: Option<(egui::TextureHandle, Instant)>,
    acked: Option<u64>,
    tray_live: Option<Option<usize>>, // (the tray's ✼ as last set: None lime, Some(step) the pulse)
    tray_click: std::sync::mpsc::Receiver<()>,
    editing_masks: bool,
    mask_drag: Option<MaskDrag>,
    // the window's own frame: the smallest size last asked of Windows (the drawer open or not, the size in %), and
    // the edge the hand is on
    min_sent: Option<(Drawer, u32)>,
    born: Instant,
    edge: Option<egui::viewport::ResizeDirection>,
    // who's watching (the relay's {"viewers"}), and whether the list is open
    viewer_list: Vec<Value>,
    show_viewers: bool,
    // the look as last applied (the tint, anyone's own colours)
    look: (Option<f32>, std::collections::BTreeMap<String, String>),
    // the broadcast's title as it's being typed (it goes out on Enter, or when the box is left)
    title_edit: String,
    // asking for a portal from the attach screen (ask.rs), and the channel it attached to when the answer came
    asker: crate::ask::Asker,
    asked_attached: Arc<Mutex<Option<String>>>,
    /// the chat turned away for good (a client too old for the relay), for the status line while off air too
    desk_stopped: Option<String>,
    /// the ready check's look at RuneLite's window (its title), taken once a second
    ready_seen: (Instant, Option<String>),
    // the field station (the keeper's build): what it starts, its part of the tray, friends' portals
    #[cfg(feature = "station")]
    station: Option<crate::station::Station>,
    #[cfg(feature = "station")]
    station_tray: Option<crate::station::Tray>,
    #[cfg(feature = "station")]
    keeper: crate::keeper::Keeper,
    #[cfg(feature = "station")]
    told_tray: bool,
}

/// a mask being drawn (from where the hand went down) or moved (which one, and where it was held)
#[derive(Clone, Copy)]
enum MaskDrag { New(egui::Pos2), Move(usize, egui::Vec2) }

struct TrayItems {
    open: tray_icon::menu::MenuItem,
    live: tray_icon::menu::MenuItem,
    modes: Vec<(String, tray_icon::menu::CheckMenuItem)>,
    quit: tray_icon::menu::MenuItem,
}

/// The ✼ in one colour, for the tray: every lit pixel's colour moved to it, its shape and edges kept, all at `alpha`
/// (lime while not live; hotpink pulsing while live: 11/05/2026, Jace)
pub fn tinted(colour: Color32, alpha: f32) -> (Vec<u8>, u32, u32) {
    let (mut px, w, h) = icon_rgba();
    for p in px.chunks_exact_mut(4) {
        let lum = (p[0] as f32 * 0.3 + p[1] as f32 * 0.59 + p[2] as f32 * 0.11) / 255.0;
        let k = (lum * 1.25).min(1.0);
        p[0] = (colour.r() as f32 * k) as u8;
        p[1] = (colour.g() as f32 * k) as u8;
        p[2] = (colour.b() as f32 * k) as u8;
        p[3] = (p[3] as f32 * alpha) as u8;
    }
    (px, w, h)
}

/// the tray's pulse while live: a step every 100 ms, 1.2 s round, its brightness eased from full to 30% and back
const TRAY_STEPS: usize = 12;
fn tray_frame(step: Option<usize>) -> (Vec<u8>, u32, u32) {
    match step {
        None => tinted(LIME(), 1.0),
        Some(i) => {
            let a = 0.65 + 0.35 * (i as f32 / TRAY_STEPS as f32 * std::f32::consts::TAU).cos();
            tinted(PINK(), a)
        }
    }
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
        style(&ctx, &settings.get());
        let wake = ctx.clone();
        let desk = Desk::start(settings.clone(), move || wake.request_repaint());
        let wake2 = ctx.clone();
        let feed = crate::feed::start(settings.clone(), move || wake2.request_repaint());
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
        // a click on the tray's ✼ opens the window too
        let (click_tx, tray_click) = std::sync::mpsc::channel();
        {
            let ctx = ctx.clone();
            tray_icon::TrayIconEvent::set_event_handler(Some(move |e: tray_icon::TrayIconEvent| {
                let opened = matches!(e, tray_icon::TrayIconEvent::Click { button: tray_icon::MouseButton::Left,
                                                                            button_state: tray_icon::MouseButtonState::Up, .. }
                                         | tray_icon::TrayIconEvent::DoubleClick { .. });
                if opened {
                    let _ = click_tx.send(());
                    ctx.request_repaint();
                }
            }));
        }
        #[cfg(feature = "station")]
        let (station, station_note) = match crate::station::Station::find_file() {
            Some(f) => match crate::station::Station::open(&f) {
                Ok(mut st) => { st.refresh(true); (Some(st), None) }
                Err(e) => (None, Some(format!("the field station's file wouldn't read: {}", e))),
            },
            None => (None, Some("no launcher.json found up from here (--station <file>): the portal alone".to_string())),
        };
        #[cfg(feature = "station")]
        let (tray, tray_items, station_tray) = make_tray(&settings.get(), station.as_ref());
        #[cfg(not(feature = "station"))]
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
                            if newer_than(tag.trim_start_matches('v'), crate::settings::VERSION) {
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
            static_on: false, signals: 0, stars: Vec::new(), guess: None, guess_form: None, banned: Vec::new(),
            over: Vec::new(), history_until: Instant::now(), moving_chat: false, zoom: 1.0,
            feed, feed_tex: None, acked: None, tray_live: None, tray_click, editing_masks: false, mask_drag: None,
            min_sent: None, edge: None, born: Instant::now(), viewer_list: Vec::new(), show_viewers: false,
            look: (s.tint, s.colours.clone()),
            title_edit: s.stream_title.clone(),
            asker: crate::ask::Asker::new(), asked_attached: Arc::new(Mutex::new(None)), desk_stopped: None,
            ready_seen: (Instant::now() - Duration::from_secs(5), None),
            #[cfg(feature = "station")]
            station, #[cfg(feature = "station")] station_tray,
            #[cfg(feature = "station")]
            keeper: crate::keeper::Keeper::new(),
            #[cfg(feature = "station")]
            told_tray: false,
        };
        #[cfg(feature = "station")]
        {
            if let Some(n) = station_note { app.note(n); }
            if app.station.as_ref().is_some_and(|s| s.python_too) {
                app.note("the Python field station is running too: close it from its tray (close the station)");
            }
            if std::env::args().any(|a| a == "--tray") { app.hidden = true; }
        }
        if let Some(what) = crate::crash::last_time() {
            app.note(format!("the portal stopped unexpectedly last time ({}). settings ▸ more ▸ \"copy my log\" puts what happened on the clipboard, to send to the listening post", what));
        }
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
                    self.desk_stopped = None;
                    self.history_until = Instant::now() + Duration::from_millis(800); // (the chat so far: not over the game again)
                    self.note(format!("connected · your lines go out as {}", name));
                }
                In::Down => { self.up = false; self.live = false; self.open = false; }
                In::Note(n) => self.note(n),
                In::Msg(v) => self.message(v),
                In::Stopped(why) => self.desk_stopped = Some(why),
            }
        }
        while let Ok(e) = self.tray_rx.try_recv() {
            self.tray_event(ctx, e);
        }
        if self.tray_click.try_recv().is_ok() {
            while self.tray_click.try_recv().is_ok() {}
            self.show(ctx);
        }
    }

    fn message(&mut self, m: Value) {
        if let Some(n) = m.get("notice") {
            // the keeper's notice to every portal (docs/protocol.md, "A notice from the keeper"): shown once
            let (text, at) = (n.get("text").and_then(|x| x.as_str()).unwrap_or(""), n.get("at").and_then(|x| x.as_u64()).unwrap_or(0));
            if !text.is_empty() && at != self.settings.get().notice_seen {
                let _ = self.settings.update(|s| s.notice_seen = at);
                self.note(format!("from the listening post: {}", text));
            }
            return;
        }
        if let Some(r) = m.get("relay") {
            self.live = r.get("live").and_then(|x| x.as_bool()).unwrap_or(false);
            self.viewers = r.get("viewers").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
            self.open = r.get("open").and_then(|x| x.as_bool()).unwrap_or(false);
            self.static_on = r.get("static").and_then(|x| x.as_bool()).unwrap_or(false);
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
            if Instant::now() > self.history_until {
                self.over.push(OverLine { at: Instant::now(), name: c.get("name").and_then(|x| x.as_str()).unwrap_or("").into(),
                                          text: c.get("text").and_then(|x| x.as_str()).unwrap_or("").into(), host });
                if self.over.len() > 7 { self.over.remove(0); }
            }
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
        } else if let Some(sg) = m.get("signal") {
            // the signal button, as often as they like: counted, and a ✼ over RuneLite
            self.signals += 1;
            if self.settings.get().signals && self.stars.len() < 60 {
                let who = sg.get("who").and_then(|x| x.as_u64()).unwrap_or(0) as f32;
                let x = 0.25 + ((who * 0.618 + self.signals as f32 * 0.137) % 1.0) * 0.5;
                self.stars.push(Star { at: Instant::now(), x, name: sg.get("name").and_then(|x| x.as_str()).unwrap_or("").into() });
            }
        } else if let Some(g) = m.get("guess") {
            self.guess = Some(g.clone());
        } else if m.get("guess_gone").is_some() {
            self.guess = None;
        } else if let Some(b) = m.get("banned").and_then(|x| x.as_array()) {
            self.banned = b.iter().map(|v| (v.get("who").and_then(|x| x.as_i64()).unwrap_or(0),
                                            v.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string())).collect();
        } else if let Some(v) = m.get("viewers").and_then(|x| x.as_array()) {
            // (who's watching, each time it changes: the desk's own list)
            self.viewer_list = v.clone();
        } else if let Some(u) = m.get("upload") {
            self.portal_bps = u.get("bps").and_then(|x| x.as_u64());
            self.acked = u.get("acked").and_then(|x| x.as_u64());
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
            #[cfg(feature = "station")]
            self.station_relay();
            if let Err(e) = self.settings.update(|s| s.on_air = true) {
                self.note(format!("couldn't go live: {}", e));
            }
            self.started = Instant::now();
        }
    }

    fn status_line(&self) -> (String, Color32) {
        let s = self.settings.get();
        let tx = self.tx.status.lock().unwrap().clone();
        if let Some(why) = tx.stopped.as_ref().or(self.desk_stopped.as_ref()) {
            return (format!("stopped: {}", why), PINK());
        }
        if !self.up {
            return if self.started.elapsed().as_secs() < 8 { ("starting the portal…".into(), GREY()) }
                   else { ("the relay isn't answering (it keeps trying)".into(), GREY()) };
        }
        if !s.on_air {
            ("off air · the stream's ended".into(), GREY())
        } else if self.live && self.open {
            (format!("live · {} watching", self.viewers), PINK())
        } else if self.live && self.static_on {
            (format!("static · viewers see \"signal obscured\", the sound going on (the static key) · {} watching", self.viewers), TEAL())
        } else if self.live {
            (format!("paused · RuneLite minimised or closed · {} watching", self.viewers), TEAL())
        } else {
            ("going live: looking for RuneLite…".into(), GREY())
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
            #[cfg(feature = "station")]
            if let Some(st) = &mut self.station {
                // close the station: everything it started stopped, and the portal off air
                st.stop_all();
                let _ = self.settings.update(|s| s.on_air = false);
            }
            self.quitting = true;
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        } else if let Some((m, _)) = items.modes.iter().find(|(_, i)| e.id == *i.id()) {
            let m = m.clone();
            let _ = self.settings.update(|s| s.sound = m);
        } else {
            #[cfg(feature = "station")]
            self.station_ask(ctx, &e.id);
        }
    }

    fn show(&mut self, ctx: &egui::Context) {
        self.hidden = false;
        ctx.send_viewport_cmd(egui::ViewportCommand::Visible(true));
        ctx.send_viewport_cmd(egui::ViewportCommand::Minimized(false));
        // (brought to the front by Windows' own call, never egui's Focus: winit's focus sends a pretend Alt key)
        win::show_window(TITLE);
    }

    fn sync_tray(&mut self, ctx: &egui::Context) {
        let live = self.up && self.live && self.open && self.settings.get().on_air;
        // (while live, a step of the pulse every 100 ms: the tray's ✼ beats even with the window in the tray)
        let step = live.then(|| if crate::cursor::still() { 0 } else {
            (self.born.elapsed().as_millis() / 100) as usize % TRAY_STEPS
        });
        if live { ctx.request_repaint_after(Duration::from_millis(100)); }
        if self.tray_live != Some(step) {
            self.tray_live = Some(step);
            if let Some(t) = &self.tray {
                let (rgba, w, h) = tray_frame(step);
                if let Ok(i) = tray_icon::Icon::from_rgba(rgba, w, h) {
                    let _ = t.set_icon(Some(i));
                }
            }
        }
        #[cfg(feature = "station")]
        if let Some(st) = &mut self.station {
            for n in st.refresh(false) {
                self.lines.push(Line { id: None, who: 0, host: false, name: String::new(), queued: false, text: n,
                                       when: String::new(), hidden: false, note: true, ping: false });
            }
            if let Some(t) = &self.station_tray { t.sync(st); }
        }
        let Some(items) = &self.tray_items else { return };
        let s = self.settings.get();
        items.live.set_text(if s.on_air && self.up && self.live { "end stream" } else { "go live" });
        for (m, i) in &items.modes {
            i.set_checked(*m == sound::mode_of(&s.sound));
        }
        if let Some(t) = &self.tray {
            #[cfg(feature = "station")]
            let _ = t.set_tooltip(Some(match &self.station {
                Some(st) => format!("⊂λ,ｍ⊙ field station\nthe portal · {}\n{}", self.status_line().0, st.tooltip()),
                None => format!("the portal · {}", self.status_line().0),
            }));
            #[cfg(not(feature = "station"))]
            let _ = t.set_tooltip(Some(format!("the portal · {}", self.status_line().0)));
        }
    }
}

#[cfg(feature = "station")]
fn make_tray(s: &Settings, station: Option<&crate::station::Station>)
             -> (Option<tray_icon::TrayIcon>, Option<TrayItems>, Option<crate::station::Tray>) {
    use tray_icon::menu::PredefinedMenuItem;
    let (menu, open, live, modes, quit_at) = tray_menu(s);
    let mut st_tray = None;
    if let Some(st) = station {
        let _ = menu.append(&PredefinedMenuItem::separator());
        st_tray = Some(crate::station::Tray::build(st, &menu));
    }
    let quit = tray_icon::menu::MenuItem::new(if station.is_some() { "close the station" } else { quit_at }, true, None);
    let _ = menu.append_items(&[&PredefinedMenuItem::separator(), &quit]);
    let (t, items) = tray_finish(menu, open, live, modes, quit);
    (t, items, st_tray)
}

#[cfg(not(feature = "station"))]
fn make_tray(s: &Settings) -> (Option<tray_icon::TrayIcon>, Option<TrayItems>) {
    use tray_icon::menu::{MenuItem, PredefinedMenuItem};
    let (menu, open, live, modes, quit_at) = tray_menu(s);
    let quit = MenuItem::new(quit_at, true, None);
    let _ = menu.append_items(&[&PredefinedMenuItem::separator(), &quit]);
    tray_finish(menu, open, live, modes, quit)
}

type SoundItems = Vec<(String, tray_icon::menu::CheckMenuItem)>;

/// the portal's own part of the tray's menu: open it, go live, the sound
fn tray_menu(_s: &Settings) -> (tray_icon::menu::Menu, tray_icon::menu::MenuItem, tray_icon::menu::MenuItem, SoundItems, &'static str) {
    use tray_icon::menu::{CheckMenuItem, Menu, MenuItem, Submenu};
    let open = MenuItem::new("open the portal", true, None);
    let live = MenuItem::new("go live", true, None);
    let modes: Vec<(String, CheckMenuItem)> =
        LABELS.iter().map(|(m, l)| (m.to_string(), CheckMenuItem::new(*l, true, false, None))).collect();
    let snd = Submenu::new("sound", true);
    for (_, i) in &modes {
        let _ = snd.append(i);
    }
    let menu = Menu::new();
    let _ = menu.append_items(&[&open, &live, &snd]);
    (menu, open, live, modes, "quit")
}

fn tray_finish(menu: tray_icon::menu::Menu, open: tray_icon::menu::MenuItem, live: tray_icon::menu::MenuItem, modes: SoundItems,
               quit: tray_icon::menu::MenuItem) -> (Option<tray_icon::TrayIcon>, Option<TrayItems>) {
    let (rgba, w, h) = tray_frame(None);
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

// ---- the field station (the keeper's build) ----

#[cfg(feature = "station")]
impl App {
    /// a click in the station's part of the tray's menu
    fn station_ask(&mut self, ctx: &egui::Context, id: &tray_icon::menu::MenuId) {
        use crate::station::{open_target, Ask};
        let Some(ask) = self.station_tray.as_ref().and_then(|t| t.ask(id)) else { return };
        let Some(st) = &mut self.station else { return };
        let mut said = None;
        match ask {
            Ask::Start(i) => if let Err(e) = st.start(i) { said = Some(e) },
            Ask::Stop(i) => st.stop(i),
            Ask::Page(i) => if let Some(p) = st.services[i].page() { open_target(&p) },
            Ask::Log(i) => open_target(&st.services[i].log.display().to_string()),
            Ask::Link(i) => open_target(&st.links[i].target.clone()),
            Ask::StopAll => st.stop_all(),
            Ask::Friends => {
                self.show(ctx);
                if self.drawer != Drawer::Friends { self.open_drawer(ctx, Drawer::Friends); }
            }
            Ask::Mode(i) => {
                let ids = st.modes[i].start.clone();
                for id in ids {
                    if id == "portal" {
                        // the portal itself: its relay (while it's this PC's), the window, and live
                        self.show(ctx);
                        if !(self.settings.get().on_air && self.up && self.live) { self.go_live(); }
                    } else if let Some(st) = &mut self.station {
                        if let Some(k) = st.index(&id) {
                            if let Err(e) = st.start(k) { said = Some(e); }
                        }
                    }
                }
            }
        }
        if let Some(e) = said { self.note(e); }
        if let Some(st) = &mut self.station {
            st.refresh(true);
            if let Some(t) = &self.station_tray { t.sync(st); }
        }
    }

    /// Going live: while the portal's relay is this PC's (ws://127.0.0.1, ghillie's relay.py until his channel moves to
    /// Cloudflare), the station's "relay" starts it first if it isn't running.
    fn station_relay(&mut self) {
        let r = self.settings.get().relay;
        let local = r.starts_with("ws://127.0.0.1");
        let Some(st) = &mut self.station else { return };
        if !local { return; }
        if let Some(k) = st.index("relay") {
            st.refresh(true);
            if st.states[k] == crate::station::State::Off {
                match st.start(k) {
                    Ok(()) => self.note("the relay on this PC is starting"),
                    Err(e) => self.note(e),
                }
            }
        }
    }
}

/// The friends' portals drawer: hand one out (its attach code, to send them), every channel handed out (live ones lit,
/// read again every 10 s), a new code or taking one back (each asked about first), and setting up the relay and token.
#[cfg(feature = "station")]
fn friends_drawer(app: &mut App, ui: &mut egui::Ui) {
    let ctx = ui.ctx().clone();
    let wake = move || ctx.request_repaint();
    app.keeper.read(false, wake.clone());
    ui.label(rich("friends' portals", 16.0, LIME()));
    let relay = crate::keeper::load().get("relay").and_then(|x| x.as_str()).unwrap_or("").to_string();
    ui.label(rich(&if relay.is_empty() { "no relay set yet".to_string() } else { format!("on {}", crate::keeper::http_base(&relay)) }, 14.0, QUIET()));
    ui.add_space(4.0);
    // set up: the relay and the token
    let open = app.keeper.setup_open;
    if button(ui, if open { "set up ▾" } else { "set up ▸" }, true, Some(open)).clicked() { app.keeper.setup_open = !open; }
    if app.keeper.setup_open {
        ui.horizontal(|ui| {
            ui.label(rich("the relay's address:", 14.0, GREY()));
            field(ui, &mut app.keeper.relay, 300.0, "outside-portal.<you>.workers.dev", false);
        });
        ui.horizontal(|ui| {
            if button(ui, "save", true, None).clicked() {
                match app.keeper.save_relay() {
                    Ok(()) => { app.note("friends' portals: the relay's address saved"); app.keeper.read(true, wake.clone()); }
                    Err(e) => app.note(format!("couldn't save it: {}", e)),
                }
            }
            let has = app.keeper.has_token();
            if button(ui, if has { "a new token" } else { "make a token" }, true, None)
                .on_hover_text(tip("a new admin token, kept in Documents\\ghillie portal\\admin.json and copied: Cloudflare needs the same one (in outside-portal\\relay: npx wrangler secret put ADMIN_TOKEN, and paste it). A new one stops the old one working once Cloudflare has it.")).clicked() {
                match app.keeper.make_token() {
                    Ok(t) => { win::copy_text(&t); app.note("a new admin token is on the clipboard: give Cloudflare the same one (npx wrangler secret put ADMIN_TOKEN)"); }
                    Err(e) => app.note(e),
                }
            }
            ui.label(rich(if has { "a token is kept on this PC" } else { "no token yet" }, 14.0, GREY()));
        });
        ui.label(wrapped("anyone with the token can hand out and take back every channel: it stays on this PC (Documents\\ghillie portal\\admin.json), never in a repository", 14.0, QUIET(), ui.available_width()));
    }
    ui.add_space(10.0);
    // those asking for a portal (a friend's client, from its attach screen; ask.rs)
    let asks = app.keeper.asks.lock().unwrap().clone();
    if let Some(Ok(asks)) = asks {
        if !asks.is_empty() {
            let now_ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
            ui.label(rich(&format!("asking ({})", asks.len()), 16.0, PINK()));
            for r in asks {
                ui.horizontal(|ui| {
                    ui.label(rich(&format!("{} · {} ago", r.player, ago(Duration::from_millis(now_ms.saturating_sub(r.at_ms)))), 14.0, TEXT()));
                    if r.given { ui.label(rich("has a portal already", 14.0, PINK())); }
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if button(ui, "turn down", true, None).clicked() { app.keeper.confirm_ask = Some((false, r.channel.clone())); }
                        if button(ui, "hand out", true, None).clicked() {
                            if r.given { app.keeper.confirm_ask = Some((true, r.channel.clone())); }
                            else { app.keeper.answer(r.channel.clone(), true, wake.clone()); }
                        }
                    });
                });
                if !r.note.is_empty() { ui.label(wrapped(&format!("“{}”", r.note), 14.0, GREY(), ui.available_width())); }
            }
            if let Some((yes, ch)) = app.keeper.confirm_ask.clone() {
                let words = if yes { format!("{} has a portal already: handing it out makes a new key, and whoever has the old code is cut off. is this request theirs?", ch) }
                            else { format!("turn down the request for {}? their portal says so (they can ask again)", ch) };
                ui.label(wrapped(&words, 14.0, PINK(), ui.available_width()));
                ui.horizontal(|ui| {
                    if button(ui, if yes { "hand it out" } else { "turn it down" }, true, None).clicked() {
                        app.keeper.confirm_ask = None;
                        app.keeper.answer(ch.clone(), yes, wake.clone());
                    }
                    if button(ui, "never mind", true, None).clicked() { app.keeper.confirm_ask = None; }
                });
            }
            ui.add_space(10.0);
        }
    }
    // a notice to every portal (docs/protocol.md, "A notice from the keeper")
    ui.label(rich("a notice to every portal", 16.0, LIME()));
    let now = app.keeper.notice_now.lock().unwrap().clone().unwrap_or_default();
    ui.label(wrapped(&if now.is_empty() { "none up now".to_string() } else { format!("up now: “{}”", now) }, 14.0, GREY(), ui.available_width()));
    ui.horizontal(|ui| {
        let r = field(ui, &mut app.keeper.notice_text, 300.0, "one line every friend's portal shows in its chat", false);
        let typed = app.keeper.notice_text.trim().to_string();
        if (button(ui, "send it", true, None).clicked() || (r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)))) && !typed.is_empty() {
            app.keeper.send_notice(typed, wake.clone());
            app.keeper.notice_text.clear();
        }
        if !now.is_empty() && button(ui, "take it down", true, None).clicked() { app.keeper.send_notice(String::new(), wake.clone()); }
    });
    ui.add_space(10.0);
    // hand one out
    ui.label(rich("hand one out", 16.0, LIME()));
    ui.horizontal(|ui| {
        ui.label(rich("their player's name:", 14.0, GREY()));
        let r = field(ui, &mut app.keeper.name, 160.0, "", false);
        let go = button(ui, "hand out", true, None).clicked() || (r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)));
        if go {
            match crate::keeper::channel_of(&app.keeper.name) {
                Err(e) => app.note(e),
                Ok(ch) if app.keeper.has(&ch) => app.keeper.confirm = Some((false, ch)),
                Ok(_) => { let n = app.keeper.name.clone(); app.keeper.give(n, wake.clone()); }
            }
        }
    });
    let answer = app.keeper.answer.lock().unwrap().clone();
    match answer {
        Some(Ok((ch, code))) => {
            ui.label(rich(&format!("{}'s attach code:", ch), 14.0, GREY()));
            ui.label(wrapped(&code, 13.0, TEAL(), ui.available_width()));
            ui.horizontal(|ui| {
                if button(ui, "copy the code", true, None).clicked() { win::copy_text(&code); app.note("the attach code is on the clipboard"); }
                if button(ui, "copy a message for them", true, None).clicked() {
                    win::copy_text(&crate::keeper::message(&ch, &code));
                    app.note("a message with the code is on the clipboard");
                }
            });
            ui.label(rich(&format!("their portal: {}{}", crate::keeper::SITE.trim_start_matches("https://"), ch), 14.0, QUIET()));
        }
        Some(Err(e)) => { ui.label(rich(&e, 14.0, PINK())); }
        None => {}
    }
    // asked first
    if let Some((taking, ch)) = app.keeper.confirm.clone() {
        ui.add_space(6.0);
        let words = if taking { format!("take {} back? its key is refused and its stream cut off (viewers stay, off air)", ch) }
                    else { format!("{} has one already: a new code makes a new key, and their old code stops working", ch) };
        ui.label(wrapped(&words, 14.0, PINK(), ui.available_width()));
        ui.horizontal(|ui| {
            if button(ui, if taking { "take it back" } else { "a new code" }, true, None).clicked() {
                app.keeper.confirm = None;
                if taking { app.keeper.take(ch.clone(), wake.clone()); } else { app.keeper.give(ch.clone(), wake.clone()); }
            }
            if button(ui, "never mind", true, None).clicked() { app.keeper.confirm = None; }
        });
    }
    let note = app.keeper.note.lock().unwrap().clone();
    if !note.is_empty() { ui.label(wrapped(&note, 14.0, TEAL(), ui.available_width())); }
    ui.add_space(10.0);
    // every channel handed out
    ui.horizontal(|ui| {
        ui.label(rich("handed out", 16.0, LIME()));
        if button(ui, "read again", true, None).clicked() { app.keeper.read(true, wake.clone()); }
    });
    let rows = app.keeper.rows.lock().unwrap().clone();
    match rows {
        None => { ui.label(rich("reading…", 14.0, GREY())); }
        Some(Err(e)) => { ui.label(wrapped(&e, 14.0, PINK(), ui.available_width())); }
        Some(Ok(rows)) if rows.is_empty() => { ui.label(rich("none yet", 14.0, GREY())); }
        Some(Ok(rows)) => {
            let total: u64 = rows.iter().map(|r| r.live_s).sum();
            ui.label(rich(&format!("this month: {} on air in all", hours(total)), 14.0, GREY()))
                .on_hover_text(tip("every channel's time live since the 1st (UTC), as the relay counts it: Cloudflare's bill follows it (claude/the-portal.md, \"Hosting, checked again\")"));
            for r in rows {
                ui.horizontal(|ui| {
                    let words = if r.live { format!("{} · live · {} watching", r.channel, r.viewers) } else { format!("{} · off air", r.channel) };
                    ui.label(rich(&words, 14.0, if r.live { PINK() } else { TEXT() }));
                    if !r.title.is_empty() { ui.label(rich(&r.title, 14.0, GREY())); }
                    if !r.client.is_empty() { ui.label(rich(&format!("v{}", r.client), 13.0, QUIET())); }
                    if r.live_s > 0 { ui.label(rich(&format!("{} this month", hours(r.live_s)), 13.0, QUIET())); }
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if button(ui, "take back", true, None).clicked() { app.keeper.confirm = Some((true, r.channel.clone())); }
                        if button(ui, "new code", true, None).clicked() { app.keeper.confirm = Some((false, r.channel.clone())); }
                    });
                });
            }
        }
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

/// The look (theme.rs, jute's), then the portal's own text sizes and spacing.
fn style(ctx: &egui::Context, st: &Settings) {
    theme::set(ctx, look_tint(st), &st.colours);
    ctx.all_styles_mut(|s| {
        s.text_styles.insert(egui::TextStyle::Body, body(16.0));
        s.text_styles.insert(egui::TextStyle::Button, body(16.0));
        s.text_styles.insert(egui::TextStyle::Small, body(14.0));
        s.spacing.item_spacing = Vec2::new(6.0, 4.0);
        // (a scrollbar of its own width beside what it scrolls, never over it: 11/05/2026, the settings' words had
        // been under it)
        s.spacing.scroll = egui::style::ScrollStyle::solid();
        s.spacing.tooltip_width = 340.0;
        s.interaction.tooltip_delay = 0.25;
        s.animation_time = 0.12;
    });
}

/// The browns' tint: the one picked here, else jute's on this PC, else taupe.
fn look_tint(st: &Settings) -> f32 {
    st.tint.or_else(theme::jute_tint).unwrap_or(0.0).clamp(0.0, 1.0)
}

/// A button as jute's: lime words on a dark well in a thin line, teal under the hand; `lit` for the one in use
/// (its well lit lime-dark, its edge lime).
fn button(ui: &mut egui::Ui, text: &str, small: bool, lit: Option<bool>) -> egui::Response {
    let colour = match lit { Some(true) => LIME(), Some(false) => GREY(), None => LIME() };
    let edge = match lit { Some(true) => LIME(), _ => DIM() };
    let sz = if small { 14.0 } else { 16.0 };
    let galley = ui.painter().layout_job(job(text, sz, colour));
    let size = Vec2::new(galley.size().x + 14.0, (sz * 1.5).round());
    let (rect, resp) = ui.allocate_exact_size(size, Sense::click());
    let hover = resp.hovered();
    let p = ui.painter();
    let pal = theme::pal();
    let fill = if hover { theme::mix(pal.well, pal.teal, 0.3) } else if lit == Some(true) { theme::mix(pal.well, pal.lime_dim, 0.6) } else { pal.well };
    p.rect_filled(rect, 2.0, fill);
    p.rect_stroke(rect, 2.0, Stroke::new(1.0, if hover { TEAL() } else { edge }), egui::StrokeKind::Inside);
    let g = ui.painter().layout_job(job(text, sz, if hover { Color32::WHITE } else { colour }));
    p.galley(rect.center() - g.size() / 2.0, g, colour);
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
}

fn pink_button(ui: &mut egui::Ui, text: &str, pink: bool) -> egui::Response {
    let (colour, edge) = if pink { (PINK(), PINK()) } else { (GREY(), DIM()) };
    let g = ui.painter().layout_no_wrap(text.to_string(), body(16.0), colour);
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(g.size().x + 14.0, 24.0), Sense::click());
    let hover = resp.hovered();
    let pal = theme::pal();
    let fill = if hover { theme::mix(pal.well, pal.teal, 0.3) } else if pink { theme::mix(pal.well, pal.pink, 0.18) } else { pal.well };
    ui.painter().rect_filled(rect, 2.0, fill);
    ui.painter().rect_stroke(rect, 2.0, Stroke::new(1.0, if hover { TEAL() } else { edge }), egui::StrokeKind::Inside);
    let g = ui.painter().layout_no_wrap(text.to_string(), body(16.0), if hover { Color32::WHITE } else { colour });
    ui.painter().galley(rect.center() - g.size() / 2.0, g, colour);
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
}

/// The site's star (field-kit.js's, arms of 3 and diagonals of 2), on a 2-point grid round `c`.
fn pixel_star(p: &egui::Painter, c: egui::Pos2, colour: Color32) {
    let c = egui::pos2(c.x.round(), c.y.round());
    let mut cells = vec![(0, 0)];
    for a in 1..=3 {
        cells.extend([(a, 0), (-a, 0), (0, a), (0, -a)]);
        if a <= 2 { cells.extend([(a, a), (-a, a), (a, -a), (-a, -a)]); }
    }
    for (x, y) in cells {
        let at = egui::pos2(c.x + x as f32 * 2.0 - 1.0, c.y + y as f32 * 2.0 - 1.0);
        p.rect_filled(egui::Rect::from_min_size(at, Vec2::splat(2.0)), 0.0, colour);
    }
}

/// The title bar's own minimise (0), fill the screen (1; `maxed`: give it back) and close (2), drawn on the
/// 2-point grid: lime, teal under the hand, the close pink.
fn control(ui: &mut egui::Ui, which: u8, maxed: bool) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(30.0, 24.0), Sense::click());
    let hover = resp.hovered();
    let c = if hover { if which == 2 { PINK() } else { TEAL() } } else { LIME() };
    let p = ui.painter();
    if hover { p.rect_stroke(rect, 0.0, Stroke::new(1.0, c), egui::StrokeKind::Inside); }
    let m = egui::pos2(rect.center().x.round(), rect.center().y.round());
    let cell = |x: f32, y: f32| egui::Rect::from_min_size(egui::pos2(m.x + x - 1.0, m.y + y - 1.0), Vec2::splat(2.0));
    let sq = |x: f32, y: f32, w: f32| egui::Rect::from_min_size(egui::pos2(m.x + x, m.y + y), Vec2::splat(w));
    match which {
        0 => { p.rect_filled(egui::Rect::from_min_size(egui::pos2(m.x - 5.0, m.y + 3.0), Vec2::new(10.0, 2.0)), 0.0, c); }
        1 if maxed => {
            p.rect_stroke(sq(-5.0, -2.0, 8.0), 0.0, Stroke::new(2.0, c), egui::StrokeKind::Inside);
            p.rect_filled(egui::Rect::from_min_size(egui::pos2(m.x - 2.0, m.y - 5.0), Vec2::new(8.0, 2.0)), 0.0, c);
            p.rect_filled(egui::Rect::from_min_size(egui::pos2(m.x + 4.0, m.y - 5.0), Vec2::new(2.0, 8.0)), 0.0, c);
        }
        1 => { p.rect_stroke(sq(-5.0, -5.0, 10.0), 0.0, Stroke::new(2.0, c), egui::StrokeKind::Inside); }
        _ => {
            for i in -2..=2 {
                let k = i as f32 * 2.0;
                p.rect_filled(cell(k, k), 0.0, c);
                p.rect_filled(cell(k, -k), 0.0, c);
            }
        }
    }
    resp.on_hover_cursor(egui::CursorIcon::PointingHand)
}

/// A light as Outside's: a dot of 3.5 in a glow (its box-shadow, 4 px and 10 px), all at `a`.
fn glow(p: &egui::Painter, c: egui::Pos2, colour: Color32, a: f32) {
    for (r, k) in [(9.0, 0.07), (7.5, 0.12), (6.0, 0.2), (4.8, 0.35)] {
        p.circle_filled(c, r, colour.gamma_multiply(k * a));
    }
    p.circle_filled(c, 3.5, colour.gamma_multiply(a));
}

/// Blocks on the 2 px grid, as the station's bars: `on` of `cells` lit.
fn blocks(ui: &mut egui::Ui, cells: usize, fills: impl Fn(usize) -> Color32, h: f32) {
    let (rect, _) = ui.allocate_exact_size(Vec2::new(cells as f32 * 6.0 + 2.0, h), Sense::hover());
    for i in 0..cells {
        let x = rect.left() + 1.0 + i as f32 * 6.0;
        ui.painter().rect_filled(egui::Rect::from_min_size(egui::pos2(x, rect.top() + 1.0), Vec2::new(4.0, h - 2.0)), 0.0, fills(i));
    }
}

/// A stepped bar: click or drag along it, or the arrow keys step it (never the wheel, which scrolls the drawer).
fn bar(ui: &mut egui::Ui, label: &str, unit: &str, lo: u32, hi: u32, step: u32, value: &mut u32, hint: &str) -> bool {
    let mut changed = false;
    ui.horizontal(|ui| {
        let r = ui.label(rich(&label, 16.0, LIME()));
        if !hint.is_empty() { r.on_hover_text(tip(hint)); }
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.label(rich(&format!("{} {}", value, unit).trim().to_string(), 16.0, LIME()));
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
    if resp.has_focus() {
        let (l, r) = ui.input(|i| (i.key_pressed(egui::Key::ArrowLeft), i.key_pressed(egui::Key::ArrowRight)));
        if l && *value > lo { *value = value.saturating_sub(step).max(lo); changed = true; }
        if r && *value < hi { *value = (*value + step).min(hi); changed = true; }
    }
    let p = ui.painter();
    p.rect_stroke(rect, 0.0, Stroke::new(1.0, if resp.hovered() || resp.has_focus() { TEAL() } else { DIM() }), egui::StrokeKind::Inside);
    let cells = (((rect.width() - 4.0) / 6.0) as usize).max(8);
    let on = (cells as f32 * (*value - lo) as f32 / (hi - lo).max(1) as f32).round() as usize;
    for i in 0..cells {
        let x = rect.left() + 3.0 + i as f32 * 6.0;
        let fill = if i + 1 == on { BRIGHT() } else if i < on { LIME() } else { TRACK() };
        p.rect_filled(egui::Rect::from_min_size(egui::pos2(x, rect.top() + 4.0), Vec2::new(4.0, rect.height() - 8.0)), 0.0, fill);
    }
    ui.add_space(8.0);
    changed
}

fn field(ui: &mut egui::Ui, text: &mut String, width: f32, hint: &str, secret: bool) -> egui::Response {
    ui.add(egui::TextEdit::singleline(text).desired_width(width).hint_text(rich(&hint, 16.0, WHEN()))
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
                                    shown: None, status: (String::new(), QUIET()), listening: None };
        p.take_sample(s);
        p
    }

    fn take_sample(&mut self, s: &Settings) {
        self.status = ("taking a sample from RuneLite…".into(), QUIET());
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
                    self.status = (self.sample_note.clone(), QUIET());
                    self.sample = Some(Arc::new(img));
                    self.shown = None;
                }
                Err(why) => self.status = (format!("no sample: {}. The numbers below wait for one (open RuneLite, then take a new sample).", why), QUIET()),
            }
        }
        ui.label(rich("the stream's settings", 16.0, LIME()));
        if self.values() != self.saved {
            ui.label(rich("not saved", 14.0, PINK()));
        } else {
            ui.label(rich("what the portal sends, and what it costs", 16.0, QUIET()));
        }
        ui.add_space(8.0);
        bar(ui, "frames a second", "a second", 1, 60, 1, &mut self.fps, "how many pictures a second viewers get: more is smoother, and each one costs upload (on your own relay, for every viewer)");
        bar(ui, "width", "px", 192, 1536, 16, &mut self.width, "the picture's width as it leaves this PC (never wider than the game itself): wider is sharper, and heavier");
        let mut q = self.quality as u32;
        if bar(ui, "quality", "", 10, 95, 1, &mut q, "each picture's JPEG quality: higher is cleaner and heavier; below about 50 the edges start to smear") { self.quality = q as u8; }
        bar(ui, "viewers at once", "at most", 1, 20, 1, &mut self.viewers, "how many can watch at once; the rest wait in line for a spot (and can still chat)");
        // the picture as viewers get it, scaled to fit with hard pixels
        let boxsize = Vec2::new(ui.available_width().min(512.0), 300.0);
        let (rect, _) = ui.allocate_exact_size(boxsize, Sense::hover());
        ui.painter().rect_filled(rect, 0.0, FIELD());
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
                weight = if settings.get().relay.starts_with("wss://") {
                    // (on Cloudflare the relay sends each viewer their copy: this PC sends one, however many watch)
                    format!("{} × {}, about {:.1} KB a frame{}\n{} a second: about {:.0} KB/s, {:.1} Mbit/s up from this PC, however many watch\n(the relay sends each of the {} at most their own copy)",
                            w, h, kb, capped, self.fps, kb * self.fps as f64, mbit, self.viewers)
                } else {
                    format!("{} × {}, about {:.1} KB a frame{}\n{} a second: about {:.0} KB/s, {:.1} Mbit/s up for each viewer\n{} watching at most: about {:.1} Mbit/s up in all",
                            w, h, kb, capped, self.fps, kb * self.fps as f64, mbit, self.viewers, mbit * self.viewers as f64)
                };
            }
        }
        ui.label(rich(&weight, 16.0, LIME()));
        ui.add_space(4.0);
        ui.add(egui::Label::new(rich(&self.status.0.clone(), 16.0, self.status.1)).wrap());
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            if button(ui, "take a new sample", false, None).clicked() { self.take_sample(&settings.get()); }
            if button(ui, "as saved", false, None).clicked() {
                (self.fps, self.width, self.quality, self.viewers) = self.saved;
                self.status = ("as saved".into(), QUIET());
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if button(ui, "save", false, None).clicked() {
                    let (f, w, q, v) = self.values();
                    match settings.update(|s| { s.fps = f; s.width = w; s.quality = q; s.max_viewers = v; }) {
                        Ok(()) => {
                            self.saved = self.values();
                            self.status = ("saved: it all takes at once, the viewer cap too".into(), LIME());
                        }
                        Err(e) => self.status = (format!("couldn't save: {}", e), PINK()),
                    }
                }
            });
        });
        ui.add_space(12.0);
        self.round_two(ui, settings);
        ui.add_space(12.0);
        more(ui, settings);
    }

    /// The replay buffer, the hotkeys (pressed to change), the chat's text sizes and the window's size: each saved
    /// and taken at once.
    fn round_two(&mut self, ui: &mut egui::Ui, settings: &Shared) {
        let s = settings.get();
        let mut replay = s.replay_seconds.min(30);
        if bar(ui, "replay buffer", "s kept", 0, 30, 1, &mut replay, "the last few seconds of what the portal sent, kept in memory only, so the clip key can save them. 0 keeps nothing.") {
            let _ = settings.update(|x| x.replay_seconds = replay);
            self.status = (if replay == 0 { "the replay buffer: off".into() } else { format!("the replay buffer: the last {} s (the clip key, or \"clip\", saves them)", replay) }, LIME());
        }
        ui.label(rich("hotkeys", 16.0, LIME())).on_hover_text(tip("keys that work wherever you are, RuneLite included. The portal is told of these keys only (Windows' RegisterHotKey), never anything else you type."));
        const HOTKEYS: [(&str, &str); 4] = [("static key", "shows viewers static, the sound going on"),
                                            ("sound key", "the sound: off, the game, game + desktop"),
                                            ("clip key", "saves the replay buffer as a clip"),
                                            ("mask key", "your own masks off and on")];
        let current = [s.static_key.clone(), s.sound_key.clone(), s.clip_key.clone(), s.mask_key.clone()];
        for (i, (label, what)) in HOTKEYS.iter().enumerate() {
            ui.horizontal(|ui| {
                ui.add_sized([86.0, 18.0], egui::Label::new(rich(label, 16.0, LIME())));
                let shown = if self.listening == Some(i) { ("press a key…".to_string(), PINK()) } else { (win::key_name(&current[i]), Color32::WHITE) };
                ui.add_sized([110.0, 18.0], egui::Label::new(rich(&shown.0, 16.0, shown.1)));
                if button(ui, if self.listening == Some(i) { "never mind" } else { "change" }, true, None).clicked() {
                    self.listening = if self.listening == Some(i) { None } else { Some(i) };
                }
                ui.label(rich(what, 14.0, QUIET()));
            });
        }
        if let Some(i) = self.listening {
            // the next key pressed (egui hears most; Pause and Scroll Lock are here as buttons, as egui can't hear them)
            let mut got: Option<String> = None;
            ui.horizontal(|ui| {
                ui.label(rich("or:", 14.0, GREY()));
                for (name, label) in [("pause", "Pause"), ("scrolllock", "Scroll Lock"), ("numlock", "Num Lock"), ("capslock", "Caps Lock")] {
                    if button(ui, label, true, None).clicked() { got = Some(name.into()); }
                }
            });
            let pressed = ui.input(|inp| inp.events.iter().find_map(|e| match e {
                egui::Event::Key { key, pressed: true, modifiers, .. } if modifiers.is_none() => Some(*key),
                _ => None,
            }));
            if let Some(k) = pressed {
                if k == egui::Key::Escape { self.listening = None; } else if let Some(n) = key_of(k) { got = Some(n); }
            }
            if let Some(name) = got {
                let vk = win::key_code(&name);
                let taken = (0..4).find(|j| *j != i && win::key_code(&current[*j]) == vk);
                if let Some(j) = taken {
                    self.status = (format!("{} is already the {}", win::key_name(&name), HOTKEYS[j].0), PINK());
                } else {
                    let _ = settings.update(|x| match i { 0 => x.static_key = name.clone(), 1 => x.sound_key = name.clone(),
                                                          2 => x.clip_key = name.clone(), _ => x.mask_key = name.clone() });
                    self.status = (format!("the {} is {} now (it takes at once)", HOTKEYS[i].0, win::key_name(&name)), LIME());
                }
                self.listening = None;
            }
        }
        ui.add_space(8.0);
        ui.label(rich("text and size", 16.0, LIME()));
        let mut chat = s.chat_size.round() as u32;
        if bar(ui, "the chat's text", "px", 10, 40, 1, &mut chat, "the size of the chat's words in this window") { let _ = settings.update(|x| x.chat_size = chat as f32); }
        let mut over = s.game_chat_size.round() as u32;
        if bar(ui, "the chat over the game", "px", 10, 48, 1, &mut over, "the size of the words over RuneLite") { let _ = settings.update(|x| x.game_chat_size = over as f32); }
        ui.horizontal_wrapped(|ui| {
            ui.label(rich("this window's size:", 16.0, LIME())).on_hover_text(tip("everything in this window, bigger or smaller: its words, buttons and boxes"));
            let now = (s.ui_scale * 100.0).round() as u32;
            for pc in [75u32, 90, 100, 110, 125, 150, 175, 200] {
                if button(ui, &format!("{}%", pc), true, Some(now == pc)).clicked() {
                    let _ = settings.update(|x| x.ui_scale = pc as f32 / 100.0);
                }
            }
        });
        ui.add_space(10.0);
        self.look(ui, settings);
    }

    /// The look: jute's tint strip (taupe to brown), and anyone's own colours for any part.
    fn look(&mut self, ui: &mut egui::Ui, settings: &Shared) {
        let s = settings.get();
        ui.label(rich("the look", 16.0, LIME())).on_hover_text(tip("jute's and bale's browns. The strip moves them all from taupe to a deeper brown; each colour below can be your own instead."));
        // the strip: the plate's colour all along the tint, a lime mark where it is
        let tint = look_tint(&s);
        let (tr, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width().min(360.0), 16.0), Sense::click_and_drag());
        let resp = resp.on_hover_text(tip(&format!("the tint: {:.0}% toward brown. Drag or click along it; double-click for taupe.", tint * 100.0)));
        let n = 48;
        for i in 0..n {
            let x0 = tr.left() + tr.width() * i as f32 / n as f32;
            let x1 = tr.left() + tr.width() * (i + 1) as f32 / n as f32;
            ui.painter().rect_filled(egui::Rect::from_min_max(egui::pos2(x0, tr.top()), egui::pos2(x1 + 0.5, tr.bottom())), 0.0,
                                     theme::plate_at(i as f32 / (n - 1) as f32));
        }
        ui.painter().rect_stroke(tr, 2.0, Stroke::new(1.0, if resp.hovered() || resp.dragged() { TEAL() } else { DIM() }), egui::StrokeKind::Outside);
        let mx = tr.left() + tr.width() * tint;
        ui.painter().rect_filled(egui::Rect::from_center_size(egui::pos2(mx, tr.center().y), Vec2::new(3.0, tr.height() + 5.0)), 1.0, LIME());
        if resp.double_clicked() {
            let _ = settings.update(|x| x.tint = Some(0.0));
        } else if resp.dragged() || resp.clicked() {
            if let Some(q) = resp.interact_pointer_pos() {
                let t = ((q.x - tr.left()) / tr.width()).clamp(0.0, 1.0);
                let _ = settings.update(|x| x.tint = Some((t * 1000.0).round() / 1000.0));
            }
        }
        ui.add_space(6.0);
        // each part's colour: a click opens the picker; × gives it back to the look
        egui::Grid::new("colours").num_columns(3).spacing(Vec2::new(10.0, 4.0)).show(ui, |ui| {
            for (key, name, what) in theme::ROLES {
                let own = s.colours.get(*key).cloned();
                ui.label(rich(name, 14.0, if own.is_some() { LIME() } else { GREY() })).on_hover_text(tip(what));
                let mut c = theme::role(key);
                if egui::color_picker::color_edit_button_srgba(ui, &mut c, egui::color_picker::Alpha::Opaque).changed() {
                    let (k, v) = (key.to_string(), theme::hex(c));
                    let _ = settings.update(|x| { x.colours.insert(k, v); });
                }
                if own.is_some() {
                    if button(ui, "×", true, None).on_hover_text(tip("back to the look's own")).clicked() {
                        let k = key.to_string();
                        let _ = settings.update(|x| { x.colours.remove(&k); });
                    }
                } else {
                    ui.label("");
                }
                ui.end_row();
            }
        });
        if !s.colours.is_empty() || s.tint.is_some() {
            ui.add_space(4.0);
            if button(ui, "as jute", true, None).on_hover_text(tip("every colour back to jute's, at jute's tint on this PC (or taupe)")).clicked() {
                let _ = settings.update(|x| { x.colours.clear(); x.tint = None; });
            }
        }
    }
}

/// a key egui heard, as a hotkey's name (only keys that make sense alone: not letters or digits, which you type)
fn key_of(k: egui::Key) -> Option<String> {
    use egui::Key::*;
    let n = match k {
        Insert => "insert", Home => "home", End => "end", PageUp => "pageup", PageDown => "pagedown", Delete => "delete",
        F1 => "f1", F2 => "f2", F3 => "f3", F4 => "f4", F5 => "f5", F6 => "f6", F7 => "f7", F8 => "f8", F9 => "f9",
        F10 => "f10", F11 => "f11", F12 => "f12", F13 => "f13", F14 => "f14", F15 => "f15", F16 => "f16", F17 => "f17",
        F18 => "f18", F19 => "f19", F20 => "f20", F21 => "f21", F22 => "f22", F23 => "f23", F24 => "f24",
        _ => return None,
    };
    Some(n.into())
}

fn gp(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i) % 3 == 0 { out.push(','); }
        out.push(c);
    }
    out
}

fn decode(jpg: &[u8]) -> Option<(Vec<u8>, u32, u32)> {
    let mut d = jpeg_decoder::Decoder::new(jpg);
    let px = d.decode().ok()?;
    let info = d.info()?;
    Some((px, info.width as u32, info.height as u32))
}

/// The rest of the capture's and the channel's settings (what settings.json held by hand).
/// when "copy my log" was last pressed (the drawer says so for a moment)
static LOG_COPIED: Mutex<Option<Instant>> = Mutex::new(None);

fn more(ui: &mut egui::Ui, settings: &Shared) {
    egui::CollapsingHeader::new(rich("more", 16.0, LIME())).id_salt("more").show(ui, |ui| {
        let mut s = settings.get();
        let before = serde_json::to_string(&s).unwrap_or_default();
        let choice = |ui: &mut egui::Ui, label: &str, value: &mut String, options: &[(&str, &str, &str)]| {
            ui.horizontal_wrapped(|ui| {
                ui.label(rich(&label, 16.0, GREY()));
                for (v, l, t) in options {
                    if button(ui, l, true, Some(value == v)).on_hover_text(tip(t)).clicked() { *value = v.to_string(); }
                }
            });
        };
        choice(ui, "what goes out:", &mut s.view, &[
            ("canvas", "the whole game", "everything in RuneLite's game area: the world, the minimap, the side panel and the chat"),
            ("viewport", "fixed mode's 3D view", "only with RuneLite's Fixed layout (Classic, 765 × 503): just the 3D world in its frame, without the minimap, side panel and chat round it. In Resizable mode it changes nothing; use masks there.")]);
        choice(ui, "taken by:", &mut s.method, &[
            ("auto", "the first that works", "Windows Graphics Capture, else PrintWindow, else the screen"),
            ("gpu", "Windows Graphics Capture", "the graphics card's own copy of RuneLite's window: the quickest, and it never needs the window in front"),
            ("gdi", "PrintWindow", "the older way, for a PC without Windows Graphics Capture: slower")]);
        ui.checkbox(&mut s.check_updates, rich("look for a newer version on GitHub when this opens", 16.0, GREY()))
            .on_hover_text(tip("once, as it opens: asks GitHub's public page of releases for the newest version's number, and says so in the chat if there's a newer one. Nothing of yours is sent (GitHub sees what any web visit shows: your IP address). It never downloads or installs anything."));
        ui.checkbox(&mut s.ready_check, rich("the ready check above the title, while off air", 16.0, GREY()))
            .on_hover_text(tip("RuneLite open, logged in, the relay answering, Dink heard and the sound's mode, each ticked or not"));
        ui.checkbox(&mut s.minimize_to_tray, rich("minimising puts it in the tray (the stream goes on)", 16.0, GREY()))
            .on_hover_text(tip("the window goes to the ✼ by the clock; a click on it brings the window back"));
        ui.checkbox(&mut s.only_logged_in, rich("nothing while logged out (needs RuneLite's \"username in title\")", 16.0, GREY()))
            .on_hover_text(tip("the picture stops at the login screen (your username never shows): RuneLite has to put it in its title for the portal to tell"));
        ui.horizontal(|ui| {
            ui.label(rich("clips go to:", 16.0, GREY())).on_hover_text(tip("the folder the clip key and \"clip\" save to (empty: Videos\\outside-portal)"));
            let hint = crate::clip::default_folder().display().to_string();
            field(ui, &mut s.clip_folder, 260.0, &hint, false);
        });
        ui.horizontal(|ui| {
            ui.label(rich("a drop guess is decided by a drop worth:", 16.0, GREY())).on_hover_text(tip("a guess round ends on the first drop from its boss worth this much, as Dink reports it"));
            ui.add(egui::DragValue::new(&mut s.guess_min_value).speed(10000.0).suffix(" gp"));
        });
        ui.horizontal(|ui| {
            ui.label(rich("each sound bus:", 16.0, GREY())).on_hover_text(tip("the sound's quality, for each of its two busses: 96 is plenty for the game"));
            ui.add(egui::DragValue::new(&mut s.sound_kbps).range(32..=256).suffix(" kbit/s"));
            ui.label(rich("your upload:", 16.0, GREY())).on_hover_text(tip("your connection's upload speed: the upload bar's full width"));
            ui.add(egui::DragValue::new(&mut s.upload_mbit).range(1.0..=10000.0).suffix(" Mbit/s"));
        });
        ui.horizontal(|ui| {
            ui.label(rich("RuneLite's title starts:", 16.0, GREY())).on_hover_text(tip("the portal finds RuneLite's window by its title: change it only if yours is renamed"));
            field(ui, &mut s.title, 140.0, "RuneLite", false);
        });
        ui.add_space(6.0);
        ui.label(rich("the channel", 16.0, LIME()));
        ui.horizontal(|ui| {
            ui.label(rich("your name in the chat:", 16.0, GREY()));
            field(ui, &mut s.chat_name, 160.0, &s.channel.clone(), false);
        });
        ui.horizontal(|ui| {
            ui.label(rich("watch code (empty: anyone):", 16.0, GREY())).on_hover_text(tip("a code viewers need in their link to watch. Empty: anyone with your Outside page can."));
            field(ui, &mut s.code, 140.0, "", false);
        });
        ui.label(rich(&format!("relay {} · channel {}", s.relay, s.channel), 14.0, QUIET()));
        let detach = button(ui, "attach another code…", true, None).clicked();
        if detach {
            s.key.clear();
        }
        ui.add_space(8.0);
        ui.label(rich("if something goes wrong", 16.0, LIME()));
        ui.horizontal_wrapped(|ui| {
            if button(ui, "copy my log", true, None)
                .on_hover_text(tip("the log's last 400 lines and this version, on the clipboard, to paste to the listening post. Keys, watch codes, attach codes and Discord webhook addresses are taken out first.")).clicked() {
                win::copy_text(&crate::crash::log_for_sending());
                *LOG_COPIED.lock().unwrap() = Some(Instant::now());
            }
            let copied = LOG_COPIED.lock().unwrap().is_some_and(|t| t.elapsed() < Duration::from_secs(5));
            ui.label(rich(if copied { "on the clipboard (keys and codes taken out)" } else { "what the portal did, for a bug report" }, 14.0, if copied { TEAL() } else { QUIET() }));
        });
        ui.label(wrapped(&format!("its log: {}", settings.path.with_file_name("portal.log").display()), 13.0, QUIET(), ui.available_width()));
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
    ui.label(rich("Dink: what goes into your broadcast", 16.0, LIME()));
    ui.add_space(4.0);
    match (&st.listening, &st.error) {
        (Some(url), _) => {
            ui.label(rich("Dink's address (in RuneLite: Dink ▸ Primary Webhook URLs, on a line of its own):", 16.0, GREY()));
            ui.horizontal(|ui| {
                ui.label(rich(&url, 16.0, LIME()));
                if button(ui, "copy", true, None).clicked() {
                    win::copy_text(url);
                    app.note("Dink's address is on the clipboard");
                }
            });
        }
        (None, Some(e)) => { ui.label(rich(&format!("not listening: {}", e), 16.0, PINK())); }
        _ => { ui.label(rich("starting…", 16.0, GREY())); }
    }
    ui.horizontal(|ui| {
        ui.label(rich("its port:", 16.0, GREY()));
        ui.add(egui::DragValue::new(&mut s.dink.port).range(1024..=65535));
    });
    match &st.last {
        Some((kind, at, went)) => ui.label(rich(&format!("heard from Dink {} ago: {} (to {})", ago(at.elapsed()), kind, went), 16.0, TEAL())),
        None => ui.label(rich("nothing heard from Dink yet", 16.0, GREY())),
    };
    ui.add_space(8.0);
    ui.label(rich("on your portal", 16.0, LIME()));
    egui::Grid::new("kinds").num_columns(2).spacing(Vec2::new(18.0, 2.0)).show(ui, |ui| {
        for (i, k) in REPORTS.iter().enumerate() {
            let mut on = s.dink.kind_on(k);
            let colour = if on { TEXT() } else { GREY() };
            if ui.checkbox(&mut on, rich(&kind_label(k), 16.0, colour)).changed() {
                s.dink.kinds.insert(k.to_string(), Value::Bool(on));
            }
            if i % 2 == 1 { ui.end_row(); }
        }
    });
    ui.horizontal(|ui| {
        ui.label(rich("loot: a stack worth at least", 16.0, GREY()));
        ui.add(egui::DragValue::new(&mut s.dink.loot_min).range(0..=2_147_483_647u64).speed(1000.0).suffix(" gp"));
    });
    ui.horizontal(|ui| {
        ui.label(rich("kill counts: every", 16.0, GREY()));
        ui.add(egui::DragValue::new(&mut s.dink.kc_every).range(1..=1000));
        ui.label(rich("(and always the first, and personal bests)", 16.0, GREY()));
    });
    ui.label(rich("never, whatever's ticked: anything naming another player (trades, chat, player kills, a PvP killer), and screenshots", 14.0, QUIET()));
    ui.add_space(10.0);
    ui.label(rich("on to your Discord too", 16.0, LIME()));
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
        ui.label(rich("webhook:", 16.0, GREY()));
        let was = url.clone();
        field(ui, &mut url, 300.0, "https://discord.com/api/webhooks/…", !*shown);
        if button(ui, if *shown { "hide" } else { "show" }, true, None).clicked() { *shown = !*shown; }
        if url != was {
            rules["discord"] = if url.trim().is_empty() { json!([]) } else { json!([url.trim()]) };
        }
    });
    if url.trim().is_empty() {
        ui.label(rich("(none: nothing goes to Discord. Anyone with a webhook's address can post to its server, so it stays on this PC.)", 14.0, QUIET()));
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
            let colour = if on { TEXT() } else { GREY() };
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
            ui.label(rich("loot to Discord: a stack worth", 16.0, GREY()));
            let mut v = l.get("minValue").and_then(|x| x.as_u64()).unwrap_or(0);
            if ui.add(egui::DragValue::new(&mut v).speed(10000.0).suffix(" gp")).changed() { l.insert("minValue".into(), json!(v)); }
        });
        let mut allow = l.get("allow").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(|x| x.as_str()).collect::<Vec<_>>().join(", ")).unwrap_or_default();
        ui.horizontal(|ui| {
            ui.label(rich("or these items (commas, * wildcards):", 16.0, GREY()));
            if field(ui, &mut allow, 220.0, "", false).changed() {
                l.insert("allow".into(), json!(allow.split(',').map(|x| x.trim()).filter(|x| !x.is_empty()).collect::<Vec<_>>()));
            }
        });
    }
    if let Some(Value::Object(k)) = fw.get_mut("KILL_COUNT") {
        ui.horizontal(|ui| {
            ui.label(rich("kill counts to Discord: every", 16.0, GREY()));
            let mut v = k.get("every").and_then(|x| x.as_u64()).unwrap_or(25);
            if ui.add(egui::DragValue::new(&mut v).range(1..=10000)).changed() { k.insert("every".into(), json!(v)); }
            ui.label(rich("laps every", 16.0, GREY()));
            let mut l = k.get("lapsEvery").and_then(|x| x.as_u64()).unwrap_or(1000);
            if ui.add(egui::DragValue::new(&mut l).range(1..=100000)).changed() { k.insert("lapsEvery".into(), json!(l)); }
        });
        ui.horizontal(|ui| {
            let mut first = k.get("first").and_then(|x| x.as_bool()).unwrap_or(true);
            if ui.checkbox(&mut first, rich("the first", 16.0, GREY())).changed() { k.insert("first".into(), json!(first)); }
            let mut pb = k.get("personalBest").and_then(|x| x.as_bool()).unwrap_or(true);
            if ui.checkbox(&mut pb, rich("personal bests", 16.0, GREY())).changed() { k.insert("personalBest".into(), json!(pb)); }
        });
    }
    ui.label(rich("Discord gets Dink's own post, as Dink sent it, screenshot and all. Every kind ticked here goes whole, so keep those notifiers' thresholds in Dink as you want Discord to have them.", 14.0, QUIET()));
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

/// Time on air as the keeper's list says it: minutes under an hour, else hours to a tenth.
#[cfg(feature = "station")]
fn hours(s: u64) -> String {
    if s < 3600 { format!("{} min", s / 60) } else { format!("{:.1} h", s as f64 / 3600.0) }
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
    "it doesn't watch your keyboard: Windows tells it only when one of its own four keys is pressed (Pause, Scroll Lock, Insert and End, to begin with)",
    "it sends only to the relay in your attach code, your Discord webhook if you set one, and GitHub to look for updates",
    "it needs no admin, installs nothing, and doesn't start with Windows",
];

fn attach_screen(app: &mut App, ui: &mut egui::Ui) {
    ui.add_space(24.0);
    ui.label(RichText::new("⊂λ,ｍ⊙ ✼ the portal").font(title_font(26.0)).color(LIME()));
    let _ = rich;
    ui.add_space(10.0);
    ui.label(rich("this receiver is not attached to a channel yet.", 16.0, GREY()));
    ui.label(rich("paste the attach code you were given (it starts portal1:).", 16.0, GREY()));
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
        ui.label(rich(&app.attach_note.clone(), 16.0, PINK()));
    }
    if app.by_hand {
        ui.add_space(8.0);
        ui.label(rich("a relay of your own (relay-py), or one you were told about:", 16.0, GREY()));
        let (r, c, k, w) = &mut app.hand;
        egui::Grid::new("hand").num_columns(2).show(ui, |ui| {
            ui.label(rich("relay", 16.0, GREY())); field(ui, r, 300.0, "ws://127.0.0.1:8786", false); ui.end_row();
            ui.label(rich("channel", 16.0, GREY())); field(ui, c, 200.0, "your player's name", false); ui.end_row();
            ui.label(rich("key", 16.0, GREY())); field(ui, k, 200.0, "8 characters or more", true); ui.end_row();
            ui.label(rich("watch code", 16.0, GREY())); field(ui, w, 200.0, "(empty: anyone)", false); ui.end_row();
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
    ask_section(app, ui);
    ui.add_space(14.0);
    egui::CollapsingHeader::new(rich("is it safe for my account?", 16.0, LIME())).id_salt("safe").show(ui, |ui| {
        for line in SAFE {
            ui.add(egui::Label::new(rich(line, 14.0, GREY())).wrap());
        }
        ui.add(egui::Label::new(rich("every line of it, and how to check it yourself: github.com/jhmcubed/outside-portal, SAFETY.md", 14.0, TEAL())).wrap());
    });
    if let Some(p) = settings::ghillie_path() {
        ui.add_space(14.0);
        ui.label(rich("ghillie's own portal settings are on this PC:", 16.0, GREY()));
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

/// "no code yet? ask for one": the player's name and a note for the keeper, sent to the keeper's relay (ask.rs); then,
/// while it waits, how long it's waited and "never mind". The answer is collected by itself (App::logic).
fn ask_section(app: &mut App, ui: &mut egui::Ui) {
    let ctx = ui.ctx().clone();
    let wake = move || ctx.request_repaint();
    let w = ui.available_width();
    ui.add_space(12.0);
    if let Some(a) = app.settings.get().asked.clone() {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(a.at);
        ui.label(wrapped(&format!("asked for {}'s portal {} ago · waiting for the listening post's answer", a.player,
                                  ago(Duration::from_secs(now.saturating_sub(a.at)))), 16.0, TEXT(), w));
        ui.label(wrapped("this screen attaches by itself when the answer comes: it looks every 20 s while the portal is open", 14.0, QUIET(), w));
        if button(ui, "never mind (take the request back)", true, None).clicked() { app.asker.cancel(&app.settings); }
    } else {
        let open = app.asker.open;
        if button(ui, if open { "no code yet? ask for one ▾" } else { "no code yet? ask for one ▸" }, true, Some(open)).clicked() {
            app.asker.open = !open;
        }
        if app.asker.open {
            if crate::ask::HOME_RELAY.is_empty() {
                ui.label(wrapped("asking opens with the first release, once the listening post's relay is up. until then, ask the listening post for an attach code.", 14.0, GREY(), w));
            } else {
                egui::Grid::new("ask").num_columns(2).show(ui, |ui| {
                    ui.label(rich("your player's name", 16.0, GREY()));
                    field(ui, &mut app.asker.player, 160.0, "as in the game", false);
                    ui.end_row();
                    ui.label(rich("a note for the listening post", 16.0, GREY()));
                    field(ui, &mut app.asker.note, 300.0, "how they know you: a clan, a friend, your discord", false);
                    ui.end_row();
                });
                let channel = crate::ask::channel_of(&app.asker.player);
                match &channel {
                    Ok(ch) => { ui.label(rich(&format!("your portal would open at ghillie.wiki/outside?player={}", ch), 14.0, QUIET())); }
                    Err(e) if !app.asker.player.trim().is_empty() => { ui.label(rich(e, 14.0, PINK())); }
                    Err(_) => {}
                }
                let ready = channel.is_ok() && !app.asker.busy();
                ui.horizontal(|ui| {
                    if ui.add_enabled_ui(ready, |ui| button(ui, if app.asker.busy() { "asking…" } else { "ask" }, false, None)).inner.clicked() {
                        app.asker.ask(app.settings.clone(), wake.clone());
                    }
                });
                ui.label(wrapped("what's sent: the name, the note, and a random code this PC keeps to collect the answer. nothing else.", 14.0, QUIET(), w));
            }
        }
    }
    let said = app.asker.said.lock().unwrap().clone();
    if let Some((words, bad)) = said {
        ui.label(wrapped(&words, 14.0, if bad { PINK() } else { TEAL() }, w));
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
        // a request for a portal, looked at again while it waits; answered yes, it attaches by itself
        let c = ctx.clone();
        self.asker.tend(self.settings.clone(), self.asked_attached.clone(), move || c.request_repaint());
        let came = self.asked_attached.lock().unwrap().take();
        if let Some(ch) = came {
            self.note(format!("the listening post said yes: attached to {} · your portal opens at ghillie.wiki/outside?player={}", ch, ch));
        }
        self.sync_tray(ctx);
        #[allow(unused_mut)]
        let mut into_tray = false;
        #[cfg(feature = "station")]
        if ctx.input(|i| i.viewport().close_requested()) && !self.quitting && self.station.is_some() && self.tray.is_some() {
            into_tray = true;
            // (the field station lives in the tray: its window closes into it, the stream and the services going on;
            // the tray's "close the station" ends it all)
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            self.hidden = true;
            ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
            if !self.told_tray {
                self.told_tray = true;
                self.note("the window went into the tray: the field station keeps running there (its menu ▸ close the station ends it)");
            }
        }
        if ctx.input(|i| i.viewport().close_requested()) && !self.quitting && !into_tray {
            let s = self.settings.get();
            if s.on_air && self.live {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                self.asking_close = true;
                if self.hidden { self.show(ctx); }
            }
        }
        if let Some(n) = crate::clip::take_note() {
            self.note(n);
        }
        // minimised: into the tray (the tray's "open the portal" brings it back), the stream going on
        if !self.hidden && self.tray.is_some() && self.settings.get().minimize_to_tray
            && ctx.input(|i| i.viewport().minimized) == Some(true) {
            self.hidden = true;
            ctx.send_viewport_cmd(egui::ViewportCommand::Visible(false));
        }
        // the look (the tint, anyone's own colours), whenever they change
        {
            let st = self.settings.get();
            if self.look.0 != st.tint || self.look.1 != st.colours {
                self.look = (st.tint, st.colours.clone());
                style(ctx, &st);
            }
        }
        // the interface's size (text, buttons and all), from the settings
        let scale = self.settings.get().ui_scale.clamp(0.75, 2.0);
        if (scale - self.zoom).abs() > 0.001 {
            self.zoom = scale;
            ctx.set_zoom_factor(scale);
        }
        // the pings: about 0.6 s each; a ✼ about 1.3 s; a line over the game 20 s, then dim, then gone
        self.pings.retain(|p| p.at.elapsed() < Duration::from_millis(640));
        self.stars.retain(|p| p.at.elapsed() < Duration::from_millis(1300));
        self.over.retain(|l| l.at.elapsed() < Duration::from_millis(21_500));
        let busy = !self.pings.is_empty() || !self.stars.is_empty();
        ctx.request_repaint_after(Duration::from_millis(if busy { 30 } else { 110 }));
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        let newer = self.newer.lock().unwrap().clone();
        if let Some(tag) = newer {
            if !self.told_newer {
                self.told_newer = true;
                self.note(format!("a newer version is out ({}): {}", tag, crate::settings::RELEASES));
            }
        }
        let listening = self.panel.as_ref().is_some_and(|p| p.listening.is_some());
        if ui.input(|i| i.key_pressed(egui::Key::Escape)) && self.poll_form.is_none() && self.guess_form.is_none()
            && !self.asking_close && !listening && !self.moving_chat && !self.editing_masks {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
        let frame = egui::Frame::new().fill(ABYSS()).inner_margin(egui::Margin::symmetric(12, 10));
        // the window's own frame (11/05/2026, Jace: "a complete custom gui"): its title bar, its edges, its border.
        // Its whole area filled first, so no corner or margin is ever see-through to what's behind.
        ctx.layer_painter(egui::LayerId::background()).rect_filled(ctx.content_rect(), 0.0, ABYSS());
        self.edge = self.edge_at(&ctx);
        self.min_size(&ctx);
        egui::Panel::top("titlebar").frame(egui::Frame::new().fill(ABYSS()).inner_margin(egui::Margin { left: 10, right: 4, top: 2, bottom: 0 }))
            .show_separator_line(false).show(ui, |ui| self.title_bar(ui));
        if !self.settings.get().attached() {
            egui::CentralPanel::no_frame().frame(frame).show(ui, |ui| attach_screen(self, ui));
            self.pings_ui(&ctx);
            self.frame_done(&ctx);
            return;
        }
        if self.drawer != Drawer::None {
            // (a plate of its own, as jute's sections are)
            let pal = theme::pal();
            // (the panel itself filled to its edges, the plate drawn inside it: 11/05/2026, a plate as the panel's own
            // frame had left its margin and its rounded corners unpainted, and the window is see-through there)
            let _ = pal;
            egui::Panel::right("drawer").exact_size(DRAWER_W).show_separator_line(false)
                .frame(egui::Frame::new().fill(ABYSS()).inner_margin(egui::Margin { left: 0, right: 8, top: 2, bottom: 10 })).show(ui, |ui| {
                let at = ui.painter().add(egui::Shape::Noop);
                let r = egui::Frame::new().inner_margin(egui::Margin::symmetric(16, 12)).show(ui, |ui| {
                    ui.set_min_size(ui.available_size());
                    egui::ScrollArea::vertical().id_salt("drawer_scroll").auto_shrink([false, false]).show(ui, |ui| {
                        if self.drawer == Drawer::Settings {
                            if let Some(p) = &mut self.panel { p.ui(ui, &self.settings); }
                        } else if self.drawer == Drawer::Friends {
                            #[cfg(feature = "station")]
                            friends_drawer(self, ui);
                        } else {
                            dink_drawer(self, ui);
                        }
                    });
                }).response.rect;
                ui.painter().set(at, theme::plate(r));
            });
        }
        egui::Panel::top("top").show_separator_line(false)
            // (its top 2 in, the answer row's bottom 10 in: the drawer's plate keeps the same lines, 11/05/2026)
            .frame(egui::Frame::new().fill(ABYSS()).inner_margin(egui::Margin { left: 8, right: 8, top: 2, bottom: 6 })).show(ui, |ui| self.top(ui));
        egui::Panel::bottom("answer").show_separator_line(false)
            .frame(egui::Frame::new().fill(ABYSS()).inner_margin(egui::Margin { left: 10, right: 10, top: 6, bottom: 10 })).show(ui, |ui| self.answer(ui));
        egui::CentralPanel::no_frame().frame(egui::Frame::new().fill(ABYSS()).inner_margin(egui::Margin::symmetric(10, 0))).show(ui, |ui| {
            self.viewers_ui(ui);
            self.poll_ui(ui);
            self.guess_ui(ui);
            self.banned_ui(ui);
            self.chat(ui);
        });
        if self.asking_close {
            self.close_modal(&ctx);
        }
        self.pings_ui(&ctx);
        self.frame_done(&ctx);
    }
}

impl App {
    // ---- the window's own frame ----

    /// The title bar: the star and the title, dragged to move the window (a double click fills the screen or gives
    /// it back), and its own minimise, fill and close.
    fn title_bar(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        let (rect, resp) = ui.allocate_exact_size(Vec2::new(ui.available_width(), TITLE_H), Sense::click_and_drag());
        let resp = resp.on_hover_cursor(egui::CursorIcon::Default);
        let maxed = ctx.input(|i| i.viewport().maximized.unwrap_or(false));
        if self.edge.is_none() {
            if resp.double_clicked() {
                ctx.send_viewport_cmd(egui::ViewportCommand::Maximized(!maxed));
            } else if resp.drag_started_by(egui::PointerButton::Primary) {
                ctx.send_viewport_cmd(egui::ViewportCommand::StartDrag);
            }
        }
        let p = ui.painter();
        pixel_star(p, egui::pos2(rect.left() + 8.0, rect.center().y), LIME());
        let room = rect.width() - 24.0 - 3.0 * 32.0;
        let words = if p.layout_no_wrap(TITLE.to_string(), title_font(20.0), LIME()).size().x <= room { TITLE } else { "the portal" };
        let title = p.layout_no_wrap(words.to_string(), title_font(20.0), LIME());
        // (lime lettering over RuneScape's shadow, as jute's titles)
        let at = egui::pos2(rect.left() + 22.0, rect.center().y - title.size().y / 2.0);
        let shade = p.layout_no_wrap(words.to_string(), title_font(20.0), theme::pal().shadow);
        p.galley(at + Vec2::new(1.0, 1.0), shade, theme::pal().shadow);
        p.galley(at, title, LIME());
        let mut child = ui.new_child(egui::UiBuilder::new().max_rect(rect).layout(egui::Layout::right_to_left(egui::Align::Center)));
        child.spacing_mut().item_spacing.x = 2.0;
        if control(&mut child, 2, false).on_hover_text(rich("close", 14.0, GREY())).clicked() {
            ctx.send_viewport_cmd(egui::ViewportCommand::Close);
        }
        let fill = if maxed { "give the screen back" } else { "fill the screen" };
        if control(&mut child, 1, maxed).on_hover_text(rich(fill, 14.0, GREY())).clicked() {
            ctx.send_viewport_cmd(egui::ViewportCommand::Maximized(!maxed));
        }
        let tray = self.tray.is_some() && self.settings.get().minimize_to_tray;
        let min = if tray { "into the tray (the stream goes on)" } else { "minimise" };
        if control(&mut child, 0, false).on_hover_text(rich(min, 14.0, GREY())).clicked() {
            ctx.send_viewport_cmd(egui::ViewportCommand::Minimized(true));
        }
    }

    /// The edge (or corner) the hand is on, 6 points in from the window's side, unless it fills the screen.
    fn edge_at(&self, ctx: &egui::Context) -> Option<egui::viewport::ResizeDirection> {
        use egui::viewport::ResizeDirection as D;
        if ctx.input(|i| i.viewport().maximized.unwrap_or(false)) { return None; }
        let r = ctx.content_rect();
        let p = ctx.input(|i| i.pointer.hover_pos())?;
        let (l, rt, t, b) = (p.x - r.left() < EDGE, r.right() - p.x < EDGE, p.y - r.top() < EDGE, r.bottom() - p.y < EDGE);
        // (the corners a little larger, as Windows' own are)
        let c = EDGE * 3.0;
        let (lc, rc, tc, bc) = (p.x - r.left() < c, r.right() - p.x < c, p.y - r.top() < c, r.bottom() - p.y < c);
        Some(if (t && lc) || (l && tc) { D::NorthWest }
             else if (t && rc) || (rt && tc) { D::NorthEast }
             else if (b && lc) || (l && bc) { D::SouthWest }
             else if (b && rc) || (rt && bc) { D::SouthEast }
             else if l { D::West } else if rt { D::East } else if t { D::North } else if b { D::South }
             else { return None })
    }

    /// The smallest the window may be, so nothing in it is ever pushed out of sight or drawn over another part: with
    /// a drawer open, the drawer and the window beside it (11/05/2026: shrunk from the right, the drawer had covered
    /// the chat). In points, so it grows with the window's size in %.
    fn min_size(&mut self, ctx: &egui::Context) {
        let key = (self.drawer, (self.zoom * 100.0).round() as u32);
        if self.min_sent == Some(key) { return; }
        self.min_sent = Some(key);
        let min = if self.drawer == Drawer::None { Vec2::new(MIN_W, 460.0) } else { Vec2::new(MIN_W + DRAWER_W, 640.0) };
        ctx.send_viewport_cmd(egui::ViewportCommand::MinInnerSize(min));
        // (and if it's smaller now, it grows to fit)
        if let Some(r) = ctx.input(|i| i.viewport().inner_rect) {
            if r.width() < min.x - 0.5 || r.height() < min.y - 0.5 {
                ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(Vec2::new(r.width().max(min.x), r.height().max(min.y))));
            }
        }
    }

    /// After everything's drawn: the edges' resize (the hand pressed on one), the border, and the cursor.
    fn frame_done(&mut self, ctx: &egui::Context) {
        use egui::viewport::ResizeDirection as D;
        if let Some(d) = self.edge {
            ctx.set_cursor_icon(match d {
                D::West | D::East => egui::CursorIcon::ResizeHorizontal,
                D::North | D::South => egui::CursorIcon::ResizeVertical,
                D::NorthWest | D::SouthEast => egui::CursorIcon::ResizeNwSe,
                D::NorthEast | D::SouthWest => egui::CursorIcon::ResizeNeSw,
            });
            if ctx.input(|i| i.pointer.primary_pressed()) {
                ctx.send_viewport_cmd(egui::ViewportCommand::BeginResize(d));
            }
        }
        if !ctx.input(|i| i.viewport().maximized.unwrap_or(false)) {
            let p = ctx.layer_painter(egui::LayerId::new(egui::Order::Foreground, egui::Id::new("edge")));
            p.rect_stroke(ctx.content_rect(), 0.0, Stroke::new(1.0, DIM()), egui::StrokeKind::Inside);
        }
        crate::cursor::adopt();
        crate::cursor::follow(ctx, crate::cursor::still());
    }

    /// The stream's plate: who's watching, the drawers, the live light and going live, the upload, the live view,
    /// the sound, and the switches. Every row wraps rather than run out of the window, and what belongs together
    /// wraps together (a switch and its button).
    fn top(&mut self, ui: &mut egui::Ui) {
        let at = ui.painter().add(egui::Shape::Noop);
        let r = egui::Frame::new().inner_margin(egui::Margin::symmetric(12, 10)).show(ui, |ui| {
            ui.set_width(ui.available_width());
            self.top_inner(ui);
        }).response.rect;
        ui.painter().set(at, theme::plate(r));
    }

    fn top_inner(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        ui.horizontal(|ui| {
            let open = self.show_viewers;
            let words = format!("watching: {} {}", self.viewers, if open { "▾" } else { "▸" });
            if button(ui, &words, true, Some(open)).on_hover_text(tip("who's watching now, and who's in line for a spot (names as they gave them in the chat)")).clicked() {
                self.show_viewers = !open;
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let s_open = self.drawer == Drawer::Settings;
                let signals = self.signals;
                if button(ui, if s_open { "settings ◂" } else { "settings ▸" }, true, None).clicked() { self.open_drawer(&ctx, Drawer::Settings); }
                let d_open = self.drawer == Drawer::Dink;
                if button(ui, if d_open { "dink ◂" } else { "dink ▸" }, true, None).clicked() { self.open_drawer(&ctx, Drawer::Dink); }
                #[cfg(feature = "station")]
                {
                    let f_open = self.drawer == Drawer::Friends;
                    if button(ui, if f_open { "friends ◂" } else { "friends ▸" }, true, None)
                        .on_hover_text(tip("friends' portals: hand one out (its attach code, to send them), or take one back")).clicked() {
                        self.open_drawer(&ctx, Drawer::Friends);
                    }
                }
                if signals > 0 {
                    ui.label(rich(&format!("✼ {}", signals), 14.0, PINK())).on_hover_text(tip("viewers' signals since this opened"));
                }
            });
        });
        ui.add_space(6.0);
        // the live light, what's happening (beside it, wrapping), and going live
        ui.horizontal(|ui| {
            let (rect, _) = ui.allocate_exact_size(Vec2::splat(18.0), Sense::hover());
            let c = rect.center();
            let on = self.settings.get().on_air;
            if self.up && self.live && self.open && on {
                // as Outside's while transmitting (outside.css, .outside-signal.transmitting .outside-live): a hotpink
                // dot in its own glow, fading to 15% and back every half second, eased (11/05/2026, Jace: "smoother")
                let a = if crate::cursor::still() { 1.0 } else {
                    (0.575 + 0.425 * (ui.input(|i| i.time) * std::f64::consts::TAU / 0.5).cos()) as f32
                };
                glow(ui.painter(), c, PINK(), a);
                if !crate::cursor::still() { ui.ctx().request_repaint_after(Duration::from_millis(33)); }
            } else if self.up && self.live && on {
                glow(ui.painter(), c, TEAL(), 1.0);
            } else {
                ui.painter().circle_stroke(c, 4.5, Stroke::new(2.0, WHEN()));
            }
            let (text, colour) = self.status_line();
            let ending = on && self.up && self.live;
            let go = if ending { "end stream" } else { "go live" };
            let bw = ui.painter().layout_no_wrap(go.to_string(), body(16.0), LIME()).size().x + 14.0 + 10.0;
            let w = (ui.available_width() - bw).max(40.0);
            ui.allocate_ui_with_layout(Vec2::new(w, 18.0), egui::Layout::left_to_right(egui::Align::Center), |ui| {
                ui.set_width(w);
                ui.add(egui::Label::new(rich(&text, 14.0, colour)).wrap());
            });
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if pink_button(ui, go, !ending).clicked() { self.go_live(); }
            });
        });
        ui.add_space(4.0);
        self.ready_row(ui);
        self.title_row(ui);
        ui.add_space(4.0);
        // the upload: the portal's share (the relay's count) and this PC's in all (Windows' own count)
        ui.horizontal_wrapped(|ui| {
            let s = self.settings.get();
            let cap = if s.upload_mbit > 0.0 { s.upload_mbit } else { 40.0 };
            let pc = *self.pc_bps.lock().unwrap();
            let share = pc.unwrap_or(0) as f64 / (cap * 1e6);
            let cells = 22;
            let on = ((cells as f64 * share).round() as usize).min(cells);
            let portal_on = if self.up { ((cells as f64 * self.portal_bps.unwrap_or(0) as f64 / (cap * 1e6)).round() as usize).min(on) } else { 0 };
            blocks(ui, cells, |i| if share > 0.8 && i < on { PINK() } else if i < portal_on { LIME() } else if i < on { TEAL() } else { TRACK() }, 12.0);
            let mbit = |b: Option<u64>| b.map(|b| format!("{:.1}", b as f64 / 1e6)).unwrap_or_else(|| "…".into());
            let mut parts = Vec::new();
            if self.up && self.portal_bps.is_some() {
                let who = if s.relay.starts_with("wss://") { "the relay sends" } else { "the portal" };
                parts.push(format!("{} {}", who, mbit(self.portal_bps)));
            }
            parts.push(format!("this PC {} of {} Mbit/s", mbit(pc), cap));
            // (its words beside the bar when they fit, else under it, whole)
            let text = format!("upload: {}", parts.join(" · "));
            if text_w(ui, &text, 14.0) > ui.available_width() { ui.end_row(); }
            ui.add(egui::Label::new(wrapped(&text, 14.0, if share > 0.8 { PINK() } else { GREY() }, ui.available_width())))
                .on_hover_text(tip("lime: what the portal sends; teal: the rest of this PC's upload; pink: past 80% of your upload (its speed is set in settings ▸ more)"));
        });
        self.live_view(ui);
        ui.add_space(6.0);
        ui.horizontal_wrapped(|ui| {
            let s = self.settings.get();
            ui.label(rich("sound:", 14.0, GREY()));
            const TIPS: [&str; 3] = ["no sound goes out",
                                     "RuneLite's own sound only",
                                     "RuneLite's sound, and as a second bus viewers can turn down on their own, the rest of what this PC plays (music, videos, the voice chat you hear). Never a microphone."];
            for (i, (m, l)) in LABELS.iter().enumerate() {
                if button(ui, l, true, Some(sound::mode_of(&s.sound) == *m)).on_hover_text(tip(TIPS[i])).clicked() {
                    let m = m.to_string();
                    if let Err(e) = self.settings.update(|s| s.sound = m) { self.note(format!("couldn't save the sound's mode: {}", e)); }
                }
            }
        });
        // what viewers may do, what shows over RuneLite (see-through windows of the portal's own, never in the
        // stream), and your masks
        ui.add_space(4.0);
        let s = self.settings.get();
        let masks = if s.masks.is_empty() { "masks".to_string() } else { format!("masks ({})", s.masks.len()) };
        let gap = ui.spacing().item_spacing.x;
        let small = 4.0; // (the gap inside a piece)
        let widths = [
            check_w(ui, "viewers may chat"),
            text_w(ui, "pings:", 14.0) + PINGS.iter().map(|(_, l, _)| small + button_w(ui, l)).sum::<f32>(),
            0.0,
            check_w(ui, "✼ on my screen"),
            check_w(ui, "chat over the game") + gap + button_w(ui, if self.moving_chat { "done" } else { "move it" }),
            check_w(ui, &masks) + gap + button_w(ui, if self.editing_masks { "done" } else { "edit masks" }),
            check_w(ui, CURSOR_SWITCH),
        ];
        let _ = small;
        let pieces: Vec<usize> = (0..widths.len()).filter(|i| *i != 2).collect();
        let w2: Vec<f32> = pieces.iter().map(|i| widths[*i]).collect();
        for row in flow(&w2, ui.available_width(), gap + 4.0) {
            let row: Vec<usize> = row.map(|k| pieces[k]).collect();
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = gap + 4.0;
                for i in row { self.switch(ui, i, &s, &masks); }
            });
        }
    }

    /// The ready check (11/06/2026), while off air: is RuneLite open, is its title saying who's logged in (when the
    /// picture should stop at the login screen), is the relay answering, has Dink been heard, and the sound's mode.
    /// Each ticked or not, with how to fix it under the hand; "hide" puts it away (settings ▸ more brings it back).
    fn ready_row(&mut self, ui: &mut egui::Ui) {
        let s = self.settings.get();
        if !s.ready_check || (s.on_air && self.up && self.live) { return; }
        if self.ready_seen.0.elapsed() > Duration::from_secs(1) {
            self.ready_seen = (Instant::now(), win::find_window(&s.title).map(|(_, t)| t));
        }
        let title = self.ready_seen.1.clone();
        let dink = self.dink.lock().unwrap().clone();
        // (words, ok: Some(true) ticked, Some(false) not, None neither (optional), what's under the hand)
        let mut items: Vec<(String, Option<bool>, String)> = Vec::new();
        items.push(match &title {
            Some(t) => ("RuneLite".into(), Some(true), format!("found: {}", t)),
            None => ("RuneLite".into(), Some(false), format!("no RuneLite window: open RuneLite (the portal looks for a window whose title starts \"{}\": settings ▸ more)", s.title)),
        });
        if s.only_logged_in {
            let ok = title.as_deref().is_some_and(|t| win::logged_in(t, &s.title));
            items.push(("logged in".into(), Some(ok), if ok { "RuneLite's title has your name, so the picture stops at the login screen".into() }
                        else { "RuneLite's title doesn't say who's logged in: log in, or turn on RuneLite's \"username in title\" (settings ▸ more: \"nothing while logged out\" needs it)".into() }));
        }
        items.push(if self.up { ("the relay".into(), Some(true), format!("answering: {}", s.relay)) }
                   else { ("the relay".into(), Some(false), "the relay isn't answering: check the internet here, or the attach code".into()) });
        items.push(match (&dink.last, &dink.listening, &dink.error) {
            (Some((kind, at, _)), _, _) => ("Dink".into(), Some(true), format!("heard from Dink {} ago ({})", ago(at.elapsed()), kind)),
            (None, _, Some(e)) => ("Dink".into(), Some(false), format!("Dink can't reach the portal: {}", e)),
            _ => ("Dink".into(), None, "not heard from Dink yet: dink ▸ has the address to add to Dink in RuneLite (only for drops and level-ups under your portal)".into()),
        });
        let sound = match s.sound.as_str() { "game" => "the game", "all" => "game + desktop", _ => "off" };
        items.push((format!("sound: {}", sound), None, "the sound row below: off, the game, or game + desktop (never a microphone)".into()));
        ui.horizontal_wrapped(|ui| {
            ui.label(rich("ready?", 14.0, GREY()));
            for (words, ok, why) in &items {
                let (mark, colour) = match ok { Some(true) => ("✓", LIME()), Some(false) => ("✗", PINK()), None => ("·", GREY()) };
                ui.label(rich(&format!("{} {}", mark, words), 14.0, colour)).on_hover_text(tip(why));
            }
            if button(ui, "hide", true, None).on_hover_text(tip("puts the ready check away (settings ▸ more brings it back)")).clicked() {
                let _ = self.settings.update(|x| x.ready_check = false);
            }
        });
        ui.add_space(2.0);
    }

    /// The broadcast's title (11/06/2026, Jace: "the option to title broadcast - make it run along next to the
    /// "portal" text on the tab"): on Outside it sits beside "✼ portal" in the portal's bar, scrolling slowly when it
    /// doesn't fit. It goes out on Enter or when the box is left, not letter by letter.
    fn title_row(&mut self, ui: &mut egui::Ui) {
        let saved = self.settings.get().stream_title;
        ui.horizontal(|ui| {
            ui.label(rich("title:", 14.0, GREY()))
                .on_hover_text(tip("the broadcast's title, beside ✼ portal on your Outside page (and on the open portals): it scrolls slowly when it's too long to fit. Enter sends it; empty: none."));
            let w = ui.available_width();
            let r = ui.add(egui::TextEdit::singleline(&mut self.title_edit).desired_width(w).char_limit(100)
                .hint_text(rich("untitled · a title for this broadcast (Enter sends it)", 14.0, WHEN()))
                .font(body(14.0)).text_color(Color32::from_rgb(0xee, 0xee, 0xee)));
            let typed = self.title_edit.trim().to_string();
            if r.lost_focus() || (r.has_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter))) {
                if typed != saved.trim() {
                    match self.settings.update(|s| s.stream_title = typed.clone()) {
                        Ok(()) => self.note(if typed.is_empty() { "the broadcast's title taken down".to_string() }
                                            else { format!("the broadcast's title: {}", typed) }),
                        Err(e) => self.note(format!("couldn't save the title: {}", e)),
                    }
                }
                self.title_edit = typed;
            } else if !r.has_focus() && self.title_edit != saved {
                self.title_edit = saved; // (changed elsewhere: the settings file, another window)
            }
        });
    }

    /// One of the switches (in `top_inner`'s order): what viewers may do, what shows over RuneLite (see-through
    /// windows of the portal's own, never in the stream), and your masks, each with its button where it has one.
    fn switch(&mut self, ui: &mut egui::Ui, i: usize, s: &Settings, masks: &str) {
        let gap = ui.spacing().item_spacing.x - 4.0;
        match i {
            0 => {
                let mut talk = s.chat_allowed;
                if ui.checkbox(&mut talk, rich("viewers may chat", 14.0, GREY()))
                    .on_hover_text(tip("off: viewers' lines go nowhere, and their pages say the chat is off. Your own lines still go out.")).changed() {
                    let _ = self.settings.update(|s| s.chat_allowed = talk);
                }
            }
            1 => {
                // (11/05/2026, Jace: "viewers may ping" and "pings on my screen" read as the same thing: one choice now.
                // Off: viewers' pings go nowhere; the site: on viewers' pages only; and my screen: a ring over RuneLite
                // too, on this PC only, never in the stream.)
                ui.spacing_mut().item_spacing.x = 4.0;
                ui.label(rich("pings:", 14.0, GREY())).on_hover_text(tip("a viewer's click on the picture: a ring where they pointed"));
                let now = if !s.pings_allowed { 0 } else if !s.pings { 1 } else { 2 };
                for (k, (_, l, t)) in PINGS.iter().enumerate() {
                    if button(ui, l, true, Some(now == k)).on_hover_text(tip(t)).clicked() {
                        let _ = self.settings.update(|s| { s.pings_allowed = k > 0; s.pings = k == 2; });
                    }
                }
                ui.spacing_mut().item_spacing.x = gap + 4.0;
            }
            2 => {}
            6 => {
                // the mouse pointer in the picture (11/06/2026, Jace: "a toggle to show or hide cursor"): Windows draws it
                // into the frames itself, so the portal never reads where it is
                let mut on = s.show_cursor;
                if ui.checkbox(&mut on, rich(CURSOR_SWITCH, 14.0, GREY()))
                    .on_hover_text(tip("your mouse pointer in the picture viewers see, where it's over RuneLite. Off: it never shows. Windows draws it into each frame (Windows Graphics Capture, the usual way); PrintWindow, the fallback, can't, so there it stays hidden.")).changed() {
                    let _ = self.settings.update(|s| s.show_cursor = on);
                }
            }
            3 => {
                let mut st = s.signals;
                if ui.checkbox(&mut st, rich("✼ on my screen", 14.0, GREY()))
                    .on_hover_text(tip("each viewer's ✼ rising over RuneLite. Only on this PC.")).changed() {
                    let _ = self.settings.update(|s| s.signals = st);
                }
            }
            4 => {
                let mut over = s.game_chat;
                ui.spacing_mut().item_spacing.x = gap;
                if ui.checkbox(&mut over, rich("chat over the game", 14.0, GREY()))
                    .on_hover_text(tip("the newest lines over RuneLite, fading after 20 s. Only on this PC: it's never in the stream.")).changed() {
                    let _ = self.settings.update(|s| s.game_chat = over);
                }
                if button(ui, if self.moving_chat { "done" } else { "move it" }, true, Some(self.moving_chat))
                    .on_hover_text(tip("drag the chat over the game where you want it; Enter or done keeps it")).clicked() {
                    if !self.moving_chat && frame_rect(s).is_none() {
                        self.note("RuneLite isn't open (or it's minimised): open it, then move the chat over it");
                    } else {
                        self.moving_chat = !self.moving_chat;
                    }
                }
                ui.spacing_mut().item_spacing.x = gap + 4.0;
            }
            _ => {
                // your own masks: boxes blacked out of every frame before it leaves this PC
                let mut on = s.masks_on;
                ui.spacing_mut().item_spacing.x = gap;
                if ui.checkbox(&mut on, rich(masks, 14.0, GREY()))
                    .on_hover_text(tip(&format!("boxes blacked out of every frame before it leaves this PC, so what's under them is never sent. The {} key turns them off and on.", win::key_name(&s.mask_key)))).changed() {
                    let _ = self.settings.update(|x| x.masks_on = on);
                }
                if button(ui, if self.editing_masks { "done" } else { "edit masks" }, true, Some(self.editing_masks))
                    .on_hover_text(tip("draw boxes over RuneLite (drag), move them, right-click one to take it away")).clicked() {
                    if !self.editing_masks && frame_rect(s).is_none() {
                        self.note("RuneLite isn't open (or it's minimised): open it, then draw the masks over it");
                    } else {
                        self.editing_masks = !self.editing_masks;
                        self.mask_drag = None;
                    }
                }
                ui.spacing_mut().item_spacing.x = gap + 4.0;
            }
        }
    }

    /// The live view: the portal as the site gets it, small, beside how much viewers' pages say they've had.
    /// "the live view" switches it off (not connected at all, so it costs nothing).
    fn live_view(&mut self, ui: &mut egui::Ui) {
        let s = self.settings.get();
        ui.add_space(4.0);
        // what's come through, first (its words go beside the switch when they fit, else under it)
        let mut words = None;
        if s.live_view {
            let (frame, rate, connected) = {
                let mut f = self.feed.lock().unwrap();
                f.came.retain(|t| t.elapsed() < Duration::from_secs(4));
                let rate = f.came.len() as f32 / 4.0;
                (f.frame.take(), rate, f.connected)
            };
            if let Some((rgb, w, h, at)) = frame {
                let img = egui::ColorImage::from_rgb([w as usize, h as usize], &rgb);
                match &mut self.feed_tex {
                    Some((t, when)) => { t.set(img, egui::TextureOptions::NEAREST); *when = at; }
                    None => self.feed_tex = Some((ui.ctx().load_texture("live view", img, egui::TextureOptions::NEAREST), at)),
                }
            }
            let fresh = self.feed_tex.as_ref().is_some_and(|(_, at)| at.elapsed() < Duration::from_secs(3));
            let text = if !connected {
                "reaching the relay…".to_string()
            } else if fresh {
                let had = match self.acked { Some(b) if b > 0 => format!(" · viewers' pages have had {:.1} Mbit/s", b as f64 / 1e6), _ => if self.viewers > 0 { " · viewers: not heard from yet".into() } else { " · nobody watching".into() } };
                format!("the site's view: {:.1} a second through the relay{}", rate, had)
            } else {
                "the site's view: nothing coming through (off air, static, or paused)".to_string()
            };
            words = Some((text, if fresh { LIME() } else { GREY() }));
        }
        let gap = ui.spacing().item_spacing.x;
        let beside = words.as_ref().is_some_and(|(t, _)| check_w(ui, "the live view") + gap + text_w(ui, t, 14.0) <= ui.available_width());
        let mut toggle = |ui: &mut egui::Ui, feed_tex: &mut Option<(egui::TextureHandle, Instant)>| {
            let mut on = s.live_view;
            if ui.checkbox(&mut on, rich("the live view", 14.0, GREY())).on_hover_text(tip("the portal as the site gets it, through the relay as a viewer's page would (off: not connected at all, so it costs nothing)")).changed() {
                let _ = self.settings.update(|x| x.live_view = on);
                if !on { *feed_tex = None; }
            }
        };
        if beside {
            ui.horizontal(|ui| {
                toggle(ui, &mut self.feed_tex);
                if let Some((t, c)) = &words { ui.label(rich(t, 14.0, *c)); }
            });
        } else {
            toggle(ui, &mut self.feed_tex);
            if let Some((t, c)) = &words { ui.add(egui::Label::new(wrapped(t, 14.0, *c, ui.available_width()))); }
        }
        if s.live_view {
            if let Some((t, at)) = &self.feed_tex {
                let size = t.size_vec2();
                let w = 192.0f32.min(size.x);
                let h = size.y * w / size.x.max(1.0);
                let (rect, _) = ui.allocate_exact_size(Vec2::new(w, h), Sense::hover());
                let tint = if at.elapsed() < Duration::from_secs(3) { Color32::WHITE } else { Color32::from_gray(70) };
                ui.painter().image(t.id(), rect, egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0)), tint);
                ui.painter().rect_stroke(rect, 0.0, Stroke::new(1.0, DIM()), egui::StrokeKind::Outside);
            }
        }
    }

    fn chat(&mut self, ui: &mut egui::Ui) {
        let mut act: Option<Value> = None;
        let mut notes = Vec::new();
        egui::Frame::new().fill(FIELD()).stroke(Stroke::new(1.0, DIM())).corner_radius(3).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
            ui.set_min_size(ui.available_size());
            egui::ScrollArea::vertical().stick_to_bottom(true).auto_shrink([false, false]).show(ui, |ui| {
                let size = self.settings.get().chat_size.clamp(10.0, 40.0);
                let banned: Vec<i64> = self.banned.iter().map(|b| b.0).collect();
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
                        append(&mut job, &format!("✼ {}", l.text), size, if l.ping { CYAN() } else { GREY() }, false);
                    } else {
                        append(&mut job, &l.name, size, c(if l.host { LIME() } else { CYAN() }), l.hidden);
                        if l.queued { append(&mut job, " (in line)", size, c(Color32::from_rgb(0x77, 0x77, 0x77)), l.hidden); }
                        append(&mut job, &format!("  {}", l.text), size, c(TEXT()), l.hidden);
                        append(&mut job, &format!("  {}", l.when), size * 0.85, c(WHEN()), l.hidden);
                    }
                    job.wrap.max_width = ui.available_width();
                    // (right-clickable, but not a button: the plain star over it)
                    let mut r = ui.add(egui::Label::new(job).sense(Sense::click())).on_hover_cursor(egui::CursorIcon::Default);
                    if let Some(id) = l.id {
                        let (who, host, name) = (l.who, l.host, l.name.clone());
                        if !l.hidden {
                            r = r.on_hover_text(tip("right-click: take it back, or ban this viewer"));
                        }
                        r.context_menu(|ui| {
                            if ui.button("take this line back off everyone's page").clicked() { act = Some(json!({"hide": id})); ui.close(); }
                            if !host && who != 0 {
                                if banned.contains(&who) {
                                    if ui.button(format!("unban {}", name)).clicked() { act = Some(json!({"unban": who})); notes.push(format!("unbanned {}", name)); ui.close(); }
                                } else if ui.button(format!("ban {} (every line of theirs taken back)", name)).clicked() {
                                    act = Some(json!({"ban": who}));
                                    notes.push(format!("banned {}", name));
                                    ui.close();
                                }
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
        // (narrow: the line across the window, the buttons under it)
        let narrow = ui.available_width() < 470.0;
        let mut line: Option<egui::Response> = None;
        if narrow {
            line = Some(field(ui, &mut self.entry, ui.available_width(), "say something", false));
            ui.add_space(4.0);
        }
        ui.horizontal_wrapped(|ui| {
            let r = match line.take() { Some(r) => r, None => {
                let w = ui.available_width() - 290.0;
                field(ui, &mut self.entry, w.max(80.0), "say something", false)
            } };
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
            if button(ui, "a guess…", false, None).clicked() {
                self.guess_form = Some((String::new(), String::new()));
            }
            if button(ui, "clip", false, None).on_hover_text("the replay buffer saved as a clip (the clip key does the same)").clicked() {
                crate::clip::save(&self.settings.get().clip_folder);
            }
        });
    }

    /// the drop guesses: a round for a boss, viewers guessing the kill the next good drop lands on (the relay decides
    /// it from Dink's kill counts and loot)
    fn guess_ui(&mut self, ui: &mut egui::Ui) {
        if let Some((boss, what)) = &mut self.guess_form {
            let (mut go, mut drop) = (false, false);
            egui::Frame::new().stroke(Stroke::new(1.0, PINK())).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
                ui.label(rich("drop guesses: the boss (as Dink names it)", 14.0, GREY()));
                field(ui, boss, ui.available_width(), "Vorkath", false);
                ui.label(rich("what they're guessing (a unique, the pet…)", 14.0, GREY()));
                field(ui, what, ui.available_width(), "the next good drop", false);
                let least = self.settings.get().guess_min_value;
                ui.add(egui::Label::new(rich(&format!("decided by a drop worth {} or more from it, or by \"it dropped\"", gp(least)), 14.0, GREY())).wrap());
                ui.horizontal(|ui| {
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if button(ui, "open it", true, None).clicked() { go = true; }
                        if button(ui, "never mind", true, None).clicked() { drop = true; }
                    });
                });
            });
            if go {
                let (b, w) = (boss.trim().to_string(), what.trim().to_string());
                if b.is_empty() {
                    self.note("a guess round needs a boss");
                } else if self.desk.send(json!({"guess_open": {"boss": b, "what": w}})) {
                    self.guess_form = None;
                } else {
                    self.note("not sent: the relay isn't answering");
                }
            }
            if drop { self.guess_form = None; }
            ui.add_space(6.0);
        }
        let Some(g) = self.guess.clone() else { return };
        let result = g.get("result").filter(|r| !r.is_null()).cloned();
        let open = g.get("open").and_then(|x| x.as_bool()).unwrap_or(false);
        let mut send: Option<Value> = None;
        egui::Frame::new().stroke(Stroke::new(1.0, if result.is_none() { PINK() } else { DIM() })).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
            ui.horizontal(|ui| {
                let s = |k: &str| g.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
                ui.label(rich(&format!("drop guesses · {}: {}", s("boss"), s("what")), 16.0, if result.is_none() { PINK() } else { GREY() }));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if button(ui, "call it off", true, None).clicked() { send = Some(json!({"guess_off": true})); }
                    if result.is_none() {
                        if button(ui, "it dropped", true, None).clicked() { send = Some(json!({"guess_drop": true})); }
                        if open && button(ui, "stop guesses", true, None).clicked() { send = Some(json!({"guess_close": true})); }
                    }
                });
            });
            let text = match &result {
                Some(r) => {
                    let winners: Vec<String> = r.get("winners").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(|x| x.as_str()).map(|x| x.to_string()).collect()).unwrap_or_default();
                    format!("{} at kill {} · {}", r.get("item").and_then(|x| x.as_str()).filter(|x| !x.is_empty()).unwrap_or("the drop"),
                            r.get("kc").map(|x| x.to_string()).unwrap_or_else(|| "?".into()),
                            if winners.is_empty() { "no guesses".to_string() } else { format!("closest: {} ({} off)", winners.join(", "), r.get("off").map(|x| x.to_string()).unwrap_or_default()) })
                }
                None => format!("kill count now: {} · {} guessed{}",
                                g.get("kc").filter(|x| !x.is_null()).map(|x| x.to_string()).unwrap_or_else(|| "not heard yet (Dink's kill count, every kill)".into()),
                                g.get("count").and_then(|x| x.as_u64()).unwrap_or(0),
                                if open { "" } else { " · guesses closed, waiting for the drop" }),
            };
            ui.add(egui::Label::new(rich(&text, 14.0, TEXT())).wrap());
        });
        if let Some(m) = send { self.desk.send(m); }
        ui.add_space(6.0);
    }

    /// the banned, each with its unban
    /// Who's watching (11/05/2026, Jace: "add viewer list"): the relay's list, those with a spot first, then those in
    /// line, each by the name they gave (a viewer who hasn't given one is "visitor"). Right-click one to ban them.
    fn viewers_ui(&mut self, ui: &mut egui::Ui) {
        if !self.show_viewers {
            return;
        }
        let mut act: Option<(Value, String)> = None;
        let banned: Vec<i64> = self.banned.iter().map(|b| b.0).collect();
        egui::Frame::new().fill(FIELD()).stroke(Stroke::new(1.0, DIM())).corner_radius(3).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
            ui.set_width(ui.available_width());
            let watching: Vec<&Value> = self.viewer_list.iter().filter(|v| !v.get("waiting").and_then(|x| x.as_bool()).unwrap_or(false)).collect();
            let waiting: Vec<&Value> = self.viewer_list.iter().filter(|v| v.get("waiting").and_then(|x| x.as_bool()).unwrap_or(false)).collect();
            if self.viewer_list.is_empty() {
                ui.label(rich(if self.up { "nobody's watching" } else { "the relay isn't answering" }, 14.0, GREY()));
            }
            egui::ScrollArea::vertical().id_salt("viewers").max_height(150.0).auto_shrink([false, true]).show(ui, |ui| {
                for (group, list) in [("watching", &watching), ("in line for a spot", &waiting)] {
                    if list.is_empty() { continue; }
                    ui.label(rich(&format!("{} ({})", group, list.len()), 14.0, LIME()));
                    ui.horizontal_wrapped(|ui| {
                        for v in list.iter() {
                            let who = v.get("who").and_then(|x| x.as_i64()).unwrap_or(0);
                            let name = v.get("name").and_then(|x| x.as_str()).unwrap_or("visitor").to_string();
                            let r = ui.add(egui::Label::new(rich(&name, 14.0, CYAN())).sense(Sense::click()))
                                .on_hover_cursor(egui::CursorIcon::Default)
                                .on_hover_text(tip(&format!("viewer {} · right-click to ban them", who)));
                            r.context_menu(|ui| {
                                if banned.contains(&who) {
                                    if ui.button(format!("unban {}", name)).clicked() { act = Some((json!({"unban": who}), format!("unbanned {}", name))); ui.close(); }
                                } else if ui.button(format!("ban {} (every line of theirs taken back)", name)).clicked() {
                                    act = Some((json!({"ban": who}), format!("banned {}", name)));
                                    ui.close();
                                }
                            });
                        }
                    });
                }
            });
        });
        ui.add_space(6.0);
        if let Some((m, n)) = act {
            self.desk.send(m);
            self.note(n);
        }
    }

    fn banned_ui(&mut self, ui: &mut egui::Ui) {
        if self.banned.is_empty() {
            return;
        }
        let mut unban = None;
        egui::CollapsingHeader::new(rich(&format!("banned ({})", self.banned.len()), 14.0, GREY())).id_salt("banned").show(ui, |ui| {
            for (who, name) in &self.banned {
                ui.horizontal(|ui| {
                    ui.label(rich(name, 14.0, TEXT()));
                    if button(ui, "unban", true, None).clicked() { unban = Some((*who, name.clone())); }
                });
            }
        });
        if let Some((who, name)) = unban {
            self.desk.send(json!({"unban": who}));
            self.note(format!("unbanned {}", name));
        }
    }

    fn poll_ui(&mut self, ui: &mut egui::Ui) {
        if let Some(f) = &mut self.poll_form {
            let mut send = false;
            let mut drop = false;
            egui::Frame::new().stroke(Stroke::new(1.0, LIME())).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
                ui.label(rich("the question", 14.0, GREY()));
                field(ui, &mut f.question, ui.available_width(), "", false);
                ui.label(rich("the options, one a line (2 to 6)", 14.0, GREY()));
                ui.add(egui::TextEdit::multiline(&mut f.options).desired_rows(4).desired_width(f32::INFINITY));
                ui.horizontal(|ui| {
                    ui.label(rich("open for", 14.0, GREY()));
                    egui::ComboBox::from_id_salt("secs").selected_text(f.seconds.to_string()).show_ui(ui, |ui| {
                        for s in [30, 60, 90, 120, 180, 300, 600] { ui.selectable_value(&mut f.seconds, s, s.to_string()); }
                    });
                    ui.label(rich("seconds", 14.0, GREY()));
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
        egui::Frame::new().stroke(Stroke::new(1.0, if open { LIME() } else { DIM() })).inner_margin(egui::Margin::symmetric(8, 6)).show(ui, |ui| {
            ui.horizontal(|ui| {
                let q = p.get("question").and_then(|x| x.as_str()).filter(|q| !q.is_empty()).unwrap_or("a poll");
                ui.label(rich(&format!("{}{}", q, if open { "" } else { " · closed" }), 16.0, if open { LIME() } else { GREY() }));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if open {
                        if button(ui, "close it", true, None).clicked() { close = true; }
                        let left = self.poll_ends.saturating_duration_since(Instant::now()).as_secs();
                        ui.label(rich(&format!("{} s left", left), 14.0, GREY()));
                    } else if button(ui, "×", true, None).clicked() {
                        dismiss = true;
                    }
                });
            });
            for (o, n) in options.iter().zip(counts.iter()) {
                let won = !open && most > 0 && *n == most;
                ui.horizontal(|ui| {
                    let (r, _) = ui.allocate_exact_size(Vec2::new(150.0, 18.0), Sense::hover());
                    ui.painter().text(r.left_center(), egui::Align2::LEFT_CENTER, o, body(14.0), if won { PINK() } else { TEXT() });
                    let lit = (28.0 * *n as f64 / total as f64).round() as usize;
                    blocks(ui, 28, |i| if i < lit { if won { PINK() } else { LIME() } } else { ABYSS() }, 10.0);
                    ui.label(rich(&n.to_string(), 14.0, GREY()));
                });
            }
        });
        if close { self.desk.send(json!({"poll_close": true})); }
        if dismiss { self.poll = None; }
        ui.add_space(6.0);
    }

    fn close_modal(&mut self, ctx: &egui::Context) {
        egui::Modal::new(egui::Id::new("closing")).frame(egui::Frame::new().fill(ABYSS()).stroke(Stroke::new(1.0, LIME()))
            .inner_margin(egui::Margin::same(16))).show(ctx, |ui| {
            ui.label(rich("the portal is live.", 16.0, LIME()));
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

    /// The chat over the game: the newest lines over RuneLite (its top-left corner, or where it was moved), in a
    /// see-through window clicks pass through; "move it" makes it a box to drag, "done" or Enter keeps it there.
    fn over_ui(&mut self, ctx: &egui::Context) {
        let s = self.settings.get();
        if !s.game_chat && !self.moving_chat {
            return;
        }
        let Some((l, t, r, b)) = frame_rect(&s) else { return };
        if self.over.is_empty() && !self.moving_chat {
            return;
        }
        let ppp = ctx.pixels_per_point();
        let (wide, tall) = (560.0 / ppp * (s.game_chat_size / 20.0).max(0.6), 210.0 / ppp * (s.game_chat_size / 20.0).max(0.6));
        let (gx, gy) = match s.game_chat_at {
            Some([fx, fy]) => (l as f32 + fx as f32 * (r - l) as f32, t as f32 + fy as f32 * (b - t) as f32),
            None => (l as f32 + 12.0, t as f32 + 40.0),
        };
        // (kept on the game: never off its right or bottom edge)
        let x = gx.min((r as f32 - wide * ppp).max(l as f32));
        let y = gy.min((b as f32 - tall * ppp).max(t as f32));
        let moving = self.moving_chat;
        let mut builder = egui::ViewportBuilder::default().with_title("the portal ✼ chat over the game").with_decorations(false)
            .with_transparent(true).with_always_on_top().with_mouse_passthrough(!moving).with_taskbar(false)
            .with_resizable(false).with_inner_size(Vec2::new(wide, tall));
        if !moving {
            builder = builder.with_position(egui::pos2(x / ppp, y / ppp)).with_active(false);
        }
        let size = s.game_chat_size.clamp(10.0, 48.0);
        let lines: Vec<(f32, String, String, bool)> = self.over.iter().map(|o| (o.at.elapsed().as_secs_f32(), o.name.clone(), o.text.clone(), o.host)).collect();
        let mut done = false;
        let mut put: Option<(f32, f32)> = None;
        let id = egui::ViewportId::from_hash_of("over the game");
        ctx.show_viewport_immediate(id, builder, |ui, _| {
            let rect = ui.max_rect();
            let p = ui.painter();
            if moving {
                p.rect_filled(rect.shrink(1.0), 0.0, Color32::from_rgb(0x0b, 0x0f, 0x06));
                p.rect_stroke(rect.shrink(2.0), 0.0, Stroke::new(2.0, LIME()), egui::StrokeKind::Inside);
                p.text(rect.center() - Vec2::new(0.0, 12.0), egui::Align2::CENTER_CENTER, "drag the chat where you want it", body(18.0), LIME());
                p.text(rect.center() + Vec2::new(0.0, 14.0), egui::Align2::CENTER_CENTER, "Enter, or \"done\" in the portal's window, to keep it there", body(14.0), GREY());
                let resp = ui.interact(rect, ui.id().with("drag"), Sense::click_and_drag());
                if resp.drag_started() {
                    ui.ctx().send_viewport_cmd(egui::ViewportCommand::StartDrag);
                }
                if ui.input(|i| i.key_pressed(egui::Key::Enter)) { done = true; }
                if let Some(outer) = ui.input(|i| i.viewport().outer_rect) {
                    put = Some((outer.min.x * ppp, outer.min.y * ppp));
                }
                return;
            }
            // newest at the bottom, each with a dark rim; lit, then dim after 20 s, then gone
            let mut yy = rect.bottom() - 6.0;
            for (age, name, text, host) in lines.iter().rev() {
                let (cn, ct) = if *age < 20.0 { (if *host { LIME() } else { CYAN() }, Color32::from_rgb(0xf0, 0xf0, 0xf0)) } else { (theme::pal().lime_dim, Color32::from_rgb(0x6a, 0x6a, 0x6a)) };
                let mut j = egui::text::LayoutJob::default();
                append(&mut j, name, size, cn, false);
                append(&mut j, &format!("  {}", text), size, ct, false);
                j.wrap.max_width = rect.width() - 16.0;
                let mut shadow = j.clone();
                for sec in shadow.sections.iter_mut() { sec.format.color = Color32::from_rgb(10, 10, 10); }
                let g = ui.painter().layout_job(j);
                let gs = ui.painter().layout_job(shadow);
                let at = egui::pos2(rect.left() + 8.0, yy - g.size().y);
                for (dx, dy) in [(-1.0, 0.0), (1.0, 0.0), (0.0, -1.0), (0.0, 1.0), (2.0, 2.0)] {
                    p.galley(at + Vec2::new(dx, dy), gs.clone(), Color32::BLACK);
                }
                yy -= g.size().y + 4.0;
                p.galley(at, g, ct);
                if yy < rect.top() + 10.0 { break; }
            }
            crate::cursor::follow(ui.ctx(), crate::cursor::still());
        });
        if moving {
            if let Some((px, py)) = put {
                let fx = ((px - l as f32) / (r - l).max(1) as f32).clamp(0.0, 0.98);
                let fy = ((py - t as f32) / (b - t).max(1) as f32).clamp(0.0, 0.98);
                if s.game_chat_at.map_or(true, |a| (a[0] as f32 - fx).abs() > 0.001 || (a[1] as f32 - fy).abs() > 0.001) {
                    let _ = self.settings.update(|x| x.game_chat_at = Some([(fx as f64 * 1e4).round() / 1e4, (fy as f64 * 1e4).round() / 1e4]));
                }
            }
            if done { self.moving_chat = false; }
        }
    }

    /// Drawing your own masks, in a window laid over the game: drag on the game to cover a part of it, drag a box to
    /// move it, right-click one to take it away; Enter or "done" when they're right. Each is kept as a share of the
    /// game's size, so it stays put when RuneLite is resized.
    fn masks_ui(&mut self, ctx: &egui::Context) {
        if !self.editing_masks {
            return;
        }
        let s = self.settings.get();
        let Some((l, t, r, b)) = frame_rect(&s) else { self.editing_masks = false; return };
        let ppp = ctx.pixels_per_point();
        let (w, h) = ((r - l) as f32 / ppp, (b - t) as f32 / ppp);
        let builder = egui::ViewportBuilder::default().with_title("the portal ✼ masks").with_decorations(false)
            .with_transparent(true).with_always_on_top().with_taskbar(false).with_resizable(false)
            .with_position(egui::pos2(l as f32 / ppp, t as f32 / ppp)).with_inner_size(Vec2::new(w, h));
        let mut masks = s.masks.clone();
        let mut drag = self.mask_drag;
        let mut changed = false;
        let mut done = false;
        ctx.show_viewport_immediate(egui::ViewportId::from_hash_of("masks"), builder, |ui, _| {
            let rect = ui.max_rect();
            let share = |p: egui::Pos2| (((p.x - rect.left()) / rect.width()).clamp(0.0, 1.0), ((p.y - rect.top()) / rect.height()).clamp(0.0, 1.0));
            let to_rect = |m: &[f64; 4]| egui::Rect::from_min_size(
                egui::pos2(rect.left() + m[0] as f32 * rect.width(), rect.top() + m[1] as f32 * rect.height()),
                Vec2::new(m[2] as f32 * rect.width(), m[3] as f32 * rect.height()));
            let resp = ui.interact(rect, ui.id().with("masks"), Sense::click_and_drag());
            if resp.drag_started() {
                if let Some(p) = resp.interact_pointer_pos() {
                    drag = Some(match masks.iter().rposition(|m| to_rect(m).contains(p)) {
                        Some(i) => MaskDrag::Move(i, p - to_rect(&masks[i]).min),
                        None => MaskDrag::New(p),
                    });
                }
            }
            let now = ui.input(|i| i.pointer.interact_pos());
            if let (Some(d), Some(p)) = (drag, now) {
                match d {
                    MaskDrag::Move(i, off) if i < masks.len() => {
                        let (x, y) = share(p - off);
                        masks[i][0] = (x as f64).min(1.0 - masks[i][2]);
                        masks[i][1] = (y as f64).min(1.0 - masks[i][3]);
                    }
                    MaskDrag::New(start) => {
                        let r2 = egui::Rect::from_two_pos(start, p);
                        ui.painter().rect_filled(r2, 0.0, Color32::from_black_alpha(230));
                        ui.painter().rect_stroke(r2, 0.0, Stroke::new(2.0, PINK()), egui::StrokeKind::Inside);
                    }
                    _ => {}
                }
            }
            if resp.drag_stopped() {
                if let (Some(MaskDrag::New(start)), Some(p)) = (drag, now) {
                    let (a, b2) = (share(start), share(p));
                    let (x, y, mw, mh) = (a.0.min(b2.0), a.1.min(b2.1), (a.0 - b2.0).abs(), (a.1 - b2.1).abs());
                    if mw > 0.005 && mh > 0.005 {
                        masks.push([x as f64, y as f64, mw as f64, mh as f64].map(|v| (v * 1e4).round() / 1e4));
                    }
                }
                drag = None;
                changed = true;
            }
            if resp.secondary_clicked() {
                if let Some(p) = resp.interact_pointer_pos() {
                    if let Some(i) = masks.iter().rposition(|m| to_rect(m).contains(p)) {
                        masks.remove(i);
                        changed = true;
                    }
                }
            }
            if ui.input(|i| i.key_pressed(egui::Key::Enter) || i.key_pressed(egui::Key::Escape)) { done = true; }
            // what goes out: the masks black, a thin pink edge round each and round the game
            let p = ui.painter();
            p.rect_stroke(rect.shrink(1.0), 0.0, Stroke::new(2.0, LIME()), egui::StrokeKind::Inside);
            for m in &masks {
                let mr = to_rect(m);
                p.rect_filled(mr, 0.0, Color32::BLACK);
                p.rect_stroke(mr, 0.0, Stroke::new(1.0, PINK()), egui::StrokeKind::Inside);
            }
            let hint = "drag to cover what shouldn't go out · drag a box to move it · right-click one to take it away · Enter or \"done\" when they're right";
            let at = egui::pos2(rect.center().x, rect.top() + 18.0);
            let g = p.layout_no_wrap(hint.into(), body(16.0), LIME());
            p.rect_filled(egui::Rect::from_center_size(at, g.size() + Vec2::new(16.0, 8.0)), 0.0, Color32::from_black_alpha(220));
            p.galley(at - g.size() / 2.0, g, LIME());
            crate::cursor::follow(ui.ctx(), crate::cursor::still());
        });
        self.mask_drag = drag;
        if masks != s.masks && (changed || matches!(drag, Some(MaskDrag::Move(..)))) {
            let _ = self.settings.update(|x| x.masks = masks.clone());
        }
        if done {
            self.editing_masks = false;
            self.mask_drag = None;
        }
    }

    /// Each ✼ a viewer sends, rising over RuneLite with their name (as many as they like), in one see-through window
    /// over the game that clicks pass through.
    fn stars_ui(&mut self, ctx: &egui::Context) {
        if self.stars.is_empty() {
            return;
        }
        let Some((l, t, r, b)) = frame_rect(&self.settings.get()) else { return };
        let ppp = ctx.pixels_per_point();
        let (w, h) = ((r - l) as f32 / ppp, (b - t) as f32 / ppp);
        let builder = egui::ViewportBuilder::default().with_title("the portal ✼ signals").with_decorations(false)
            .with_transparent(true).with_always_on_top().with_mouse_passthrough(true).with_taskbar(false)
            .with_resizable(false).with_active(false).with_position(egui::pos2(l as f32 / ppp, t as f32 / ppp))
            .with_inner_size(Vec2::new(w, h));
        let stars: Vec<(f32, f32, String)> = self.stars.iter().map(|s| (s.at.elapsed().as_secs_f32(), s.x, s.name.clone())).collect();
        ctx.show_viewport_immediate(egui::ViewportId::from_hash_of("signals"), builder, |ui, _| {
            let rect = ui.max_rect();
            let p = ui.painter();
            for (age, x, name) in &stars {
                let k = (age / 1.2).min(1.0);
                let step = (k * 8.0).floor() / 8.0; // (in steps, the dither's way, not a glide)
                let at = egui::pos2(rect.left() + x * rect.width(), rect.top() + rect.height() * (0.78 - 0.45 * step));
                let colour = if step < 0.6 { LIME() } else if step < 0.9 { theme::pal().lime_dim } else { Color32::from_rgb(0x22, 0x33, 0x0a) };
                let big = 34.0 + 10.0 * (1.0 - step);
                p.text(at + Vec2::new(2.0, 2.0), egui::Align2::CENTER_CENTER, "✼", symbol_font(big), Color32::from_rgb(10, 10, 10));
                p.text(at, egui::Align2::CENTER_CENTER, "✼", symbol_font(big), colour);
                if !name.is_empty() && step < 0.7 {
                    p.text(at + Vec2::new(1.0, big * 0.6 + 1.0), egui::Align2::CENTER_TOP, name, body(16.0), Color32::from_rgb(10, 10, 10));
                    p.text(at + Vec2::new(0.0, big * 0.6), egui::Align2::CENTER_TOP, name, body(16.0), colour);
                }
            }
        });
    }

    /// A ring that grows over the spot, with the viewer's name, in a see-through window that clicks pass through.
    fn pings_ui(&mut self, ctx: &egui::Context) {
        self.over_ui(ctx);
        self.stars_ui(ctx);
        self.masks_ui(ctx);
        const WIDE: f32 = 520.0;
        const TALL: f32 = 160.0;
        let ppp = ctx.pixels_per_point();
        for p in &self.pings {
            let k = (p.at.elapsed().as_secs_f32() / 0.54).min(1.0); // about 0.6 s, in 6 steps of 90 ms
            let step = (k * 6.0).floor();
            let kk = step / 6.0;
            let colour = if step < 3.6 { LIME() } else { theme::pal().lime_dim };
            let name_colour = if step < 6.0 { LIME() } else { theme::pal().lime_dim };
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
