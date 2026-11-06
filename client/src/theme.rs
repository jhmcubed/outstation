//! The look: jute's and bale's (ghillie's `tools/synth/plugin/src/gui/theme.rs`, its browns and plates carried
//! over, 11/05/2026, Jace: "theme the relay client to fit into the same family as jute and bale"), so the portal
//! sits beside them: warm taupe plates with a lit top edge and small rivets, dark brown windows for what's live
//! (the chat, the live view, the sample), lime lettering, teal on hover, hot pink for what matters, never yellow.
//!
//! - **The tint** moves every brown along one gradient, from taupe (FL's skin, tint 0) to a deeper warm brown
//!   (tint 1), as jute's header strip does. Where jute is on this PC its tint is the starting point
//!   (Documents\ghillie graintable\tint.txt), until one is picked here.
//! - **Your own colours** (Jace: "allow for custom recoloring in case someone doesn't like the theming"): any role
//!   below can be given a colour of its own, which wins over the tint; "as jute" takes them all back.
//!
//! Read the colours through `pal()`; `set()` works them out again from the settings.

use eframe::egui::{self, epaint::Shadow, Color32, CornerRadius, Rect, Shape, Stroke, StrokeKind, Vec2};
use std::collections::BTreeMap;
use std::sync::RwLock;

#[derive(Clone, Copy)]
pub struct Palette {
    /// the window, the plates on it and their lit edge, the dark windows in them, the lines round things
    pub bg: Color32,
    pub plate: Color32,
    pub plate_hi: Color32,
    pub inset: Color32,
    pub line: Color32,
    /// the hover boxes' panel and border
    pub tip: Color32,
    pub tip_line: Color32,
    /// words: the main ones, the quiet ones, the quietest (times, hints)
    pub text: Color32,
    pub dim: Color32,
    pub faint: Color32,
    /// buttons' and boxes' own fill
    pub well: Color32,
    /// RuneScape's text shadow, under lime lettering
    pub shadow: Color32,
    pub lime: Color32,
    pub lime_dim: Color32,
    pub teal: Color32,
    pub pink: Color32,
    /// viewers' names in the chat
    pub cyan: Color32,
}

type Hsl = (f32, f32, f32);
/// (taupe, brown) as hsl for each brown, in `Palette`'s order: jute's own TONES (bg, plate, plate_hi, inset, line,
/// tip, tip_line, text, dim, faint, well, shadow).
const TONES: [(Hsl, Hsl); 12] = [
    ((33.0, 9.0, 34.0), (30.0, 16.0, 21.0)),
    ((33.0, 9.0, 41.0), (30.0, 16.0, 27.0)),
    ((33.0, 9.0, 49.0), (30.0, 24.0, 34.0)),
    ((32.0, 15.0, 15.0), (30.0, 16.0, 9.0)),
    ((32.0, 12.0, 26.0), (30.0, 20.0, 14.0)),
    ((33.0, 12.0, 22.0), (30.0, 16.0, 12.0)),
    ((33.0, 11.0, 38.0), (30.0, 32.0, 25.0)),
    ((38.0, 40.0, 93.0), (30.0, 22.0, 91.0)),
    ((35.0, 18.0, 80.0), (30.0, 11.0, 74.0)),
    ((33.0, 8.0, 60.0), (30.0, 9.0, 50.0)),
    ((33.0, 11.0, 28.0), (30.0, 18.0, 17.0)),
    ((30.0, 15.0, 10.0), (30.0, 16.0, 5.0)),
];

pub const LIME: Color32 = Color32::from_rgb(0xb9, 0xff, 0x00);
pub const LIME_DIM: Color32 = Color32::from_rgb(0x62, 0x82, 0x17);
pub const TEAL: Color32 = Color32::from_rgb(0x04, 0x94, 0xaa);
pub const PINK: Color32 = Color32::from_rgb(0xff, 0x2d, 0x95);
pub const CYAN: Color32 = Color32::from_rgb(0x5f, 0xe6, 0xf5);

/// The roles anyone may give a colour of their own: (its key in settings.json, its name, what it colours).
pub const ROLES: &[(&str, &str, &str)] = &[
    ("bg", "the window", "behind everything"),
    ("plate", "the plates", "the raised panels things sit on"),
    ("inset", "the dark windows", "the chat, the live view, the sample, the boxes you type in"),
    ("line", "the lines", "the edges round plates, boxes and buttons"),
    ("text", "the words", "the chat's lines and the main words"),
    ("dim", "the quiet words", "labels and explanations"),
    ("lime", "the lettering", "titles, values, buttons and what's on"),
    ("teal", "the hover", "whatever the hand is over"),
    ("pink", "the accent", "live, warnings and what matters"),
    ("cyan", "viewers' names", "names in the chat"),
];

static PAL: RwLock<Option<Palette>> = RwLock::new(None);

pub fn pal() -> Palette {
    PAL.read().ok().and_then(|p| *p).unwrap_or_else(|| worked_out(0.0, &BTreeMap::new()))
}

fn hsl((h, s, l): Hsl) -> Color32 {
    let (s, l) = (s / 100.0, l / 100.0);
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let hp = h.rem_euclid(360.0) / 60.0;
    let x = c * (1.0 - (hp % 2.0 - 1.0).abs());
    let (r, g, b) = match hp as i32 {
        0 => (c, x, 0.0),
        1 => (x, c, 0.0),
        2 => (0.0, c, x),
        3 => (0.0, x, c),
        4 => (x, 0.0, c),
        _ => (c, 0.0, x),
    };
    let m = l - c / 2.0;
    let q = |v: f32| ((v + m) * 255.0).round().clamp(0.0, 255.0) as u8;
    Color32::from_rgb(q(r), q(g), q(b))
}

fn tone(k: usize, t: f32) -> Color32 {
    let (a, b) = TONES[k];
    let t = t.clamp(0.0, 1.0);
    hsl((a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t, a.2 + (b.2 - a.2) * t))
}

/// The plate's colour anywhere along the tint (the strip draws itself with it).
pub fn plate_at(t: f32) -> Color32 {
    tone(1, t)
}

/// "#rrggbb" (or "rrggbb") as a colour.
pub fn parse(s: &str) -> Option<Color32> {
    let h = s.trim().trim_start_matches('#');
    if h.len() != 6 || !h.chars().all(|c| c.is_ascii_hexdigit()) { return None; }
    let v = u32::from_str_radix(h, 16).ok()?;
    Some(Color32::from_rgb((v >> 16) as u8, (v >> 8) as u8, v as u8))
}

pub fn hex(c: Color32) -> String {
    format!("#{:02x}{:02x}{:02x}", c.r(), c.g(), c.b())
}

fn worked_out(tint: f32, own: &BTreeMap<String, String>) -> Palette {
    let c: [Color32; 12] = std::array::from_fn(|k| tone(k, tint));
    let mut p = Palette {
        bg: c[0], plate: c[1], plate_hi: c[2], inset: c[3], line: c[4], tip: c[5], tip_line: c[6], text: c[7],
        dim: c[8], faint: c[9], well: c[10], shadow: c[11], lime: LIME, lime_dim: LIME_DIM, teal: TEAL, pink: PINK,
        cyan: CYAN,
    };
    let get = |k: &str| own.get(k).and_then(|v| parse(v));
    if let Some(x) = get("bg") { p.bg = x; }
    if let Some(x) = get("plate") {
        p.plate = x;
        p.plate_hi = mix(x, Color32::WHITE, 0.14);
    }
    if let Some(x) = get("inset") {
        p.inset = x;
        p.well = mix(x, p.plate, 0.45);
    }
    if let Some(x) = get("line") { p.line = x; p.tip_line = mix(x, Color32::WHITE, 0.2); }
    if let Some(x) = get("text") { p.text = x; }
    if let Some(x) = get("dim") { p.dim = x; p.faint = mix(x, p.bg, 0.35); }
    if let Some(x) = get("lime") { p.lime = x; p.lime_dim = mix(x, p.bg, 0.5); }
    if let Some(x) = get("teal") { p.teal = x; }
    if let Some(x) = get("pink") { p.pink = x; }
    if let Some(x) = get("cyan") { p.cyan = x; }
    p
}

/// jute's tint on this PC, if jute is here (Documents\ghillie graintable\tint.txt): the portal's starting point.
pub fn jute_tint() -> Option<f32> {
    let docs = std::env::var("USERPROFILE").ok().map(|h| std::path::Path::new(&h).join("Documents"))?;
    let t = std::fs::read_to_string(docs.join("ghillie graintable").join("tint.txt")).ok()?;
    t.trim().parse::<f32>().ok().map(|t| t.clamp(0.0, 1.0))
}

/// The colours worked out again (the tint, then anyone's own), and egui told.
pub fn set(ctx: &egui::Context, tint: f32, own: &BTreeMap<String, String>) {
    if let Ok(mut w) = PAL.write() {
        *w = Some(worked_out(tint, own));
    }
    apply_visuals(ctx);
}

/// What the role is now (the tint's, or its own colour).
pub fn role(key: &str) -> Color32 {
    let p = pal();
    match key {
        "bg" => p.bg, "plate" => p.plate, "inset" => p.inset, "line" => p.line, "text" => p.text, "dim" => p.dim,
        "lime" => p.lime, "teal" => p.teal, "pink" => p.pink, "cyan" => p.cyan,
        _ => p.text,
    }
}

/// egui's own colours, from the palette (jute's apply_visuals, with the portal's spacing).
pub fn apply_visuals(ctx: &egui::Context) {
    let p = pal();
    let mut v = egui::Visuals::dark();
    v.panel_fill = p.bg;
    v.window_fill = p.tip;
    v.extreme_bg_color = p.inset;
    v.faint_bg_color = p.plate_hi;
    v.override_text_color = Some(p.text);
    v.selection.bg_fill = p.lime_dim;
    v.selection.stroke = Stroke::new(1.0, p.text);
    v.hyperlink_color = p.teal;
    v.text_cursor.stroke = Stroke::new(2.0, p.lime);
    v.window_stroke = Stroke::new(1.0, p.tip_line);
    v.window_corner_radius = CornerRadius::same(4);
    v.menu_corner_radius = CornerRadius::same(4);
    for w in [&mut v.widgets.noninteractive, &mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active,
              &mut v.widgets.open] {
        w.corner_radius = CornerRadius::same(2);
        w.fg_stroke = Stroke::new(1.0, p.text);
    }
    v.widgets.noninteractive.bg_fill = p.plate;
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0, p.line);
    v.widgets.inactive.bg_fill = p.well;
    v.widgets.inactive.weak_bg_fill = p.well;
    v.widgets.inactive.bg_stroke = Stroke::new(1.0, p.line);
    let hover = mix(p.well, p.teal, 0.35);
    v.widgets.hovered.bg_fill = hover;
    v.widgets.hovered.weak_bg_fill = hover;
    v.widgets.hovered.bg_stroke = Stroke::new(1.0, p.teal);
    v.widgets.hovered.fg_stroke = Stroke::new(1.0, Color32::WHITE);
    v.widgets.active.bg_fill = p.lime_dim;
    v.widgets.active.weak_bg_fill = p.lime_dim;
    v.widgets.active.bg_stroke = Stroke::new(1.0, p.lime);
    v.widgets.open.bg_fill = p.well;
    v.widgets.open.weak_bg_fill = p.well;
    // (a checkbox's tick, lime)
    v.widgets.inactive.fg_stroke = Stroke::new(1.5, p.lime);
    v.widgets.hovered.fg_stroke = Stroke::new(1.5, Color32::WHITE);
    v.widgets.active.fg_stroke = Stroke::new(1.5, p.lime);
    // the hover boxes' depth: a soft shadow (the site's 0 6px 22px, scaled down)
    v.popup_shadow = Shadow { offset: [0, 4], blur: 14, spread: 0, color: Color32::from_black_alpha(110) };
    v.window_shadow = v.popup_shadow;
    // (the click-anywhere cursor: every clickable thing asks for the hand, which cursor.rs draws as the lime star)
    v.interact_cursor = Some(egui::CursorIcon::PointingHand);
    // (the portal is always its own dark look, whatever Windows' light or dark mode says: egui keeps a style for each,
    // and following a PC in light mode left the boxes you type in white, with the light words in them nearly unreadable)
    ctx.set_theme(egui::ThemePreference::Dark);
    ctx.set_visuals_of(egui::Theme::Dark, v.clone());
    ctx.set_visuals_of(egui::Theme::Light, v);
}

/// A plate (jute's): a panel with a little depth under it, a lit top edge and a shaded bottom one, and small
/// rivets. As shapes, for under something already laid out.
pub fn plate(r: Rect) -> Shape {
    let p = pal();
    let mut v = vec![Shadow { offset: [0, 3], blur: 10, spread: 0, color: Color32::from_black_alpha(70) }.as_shape(r, CornerRadius::same(4)).into()];
    v.push(Shape::rect_filled(r, 4.0, p.plate));
    v.push(Shape::rect_stroke(r, 4.0, Stroke::new(1.0, p.line), StrokeKind::Inside));
    v.push(Shape::line_segment([r.left_top() + Vec2::new(3.0, 1.0), r.right_top() + Vec2::new(-3.0, 1.0)], Stroke::new(1.0, p.plate_hi)));
    v.push(Shape::line_segment([r.left_bottom() + Vec2::new(3.0, -1.0), r.right_bottom() + Vec2::new(-3.0, -1.0)], Stroke::new(1.0, p.inset)));
    for c in [r.left_top() + Vec2::new(5.0, 5.0), r.right_top() + Vec2::new(-5.0, 5.0), r.left_bottom() + Vec2::new(5.0, -5.0),
              r.right_bottom() + Vec2::new(-5.0, -5.0)] {
        v.push(Shape::circle_filled(c, 1.6, p.faint));
        v.push(Shape::circle_filled(c + Vec2::new(-0.4, -0.4), 0.7, p.plate_hi));
    }
    Shape::Vec(v)
}

pub fn mix(a: Color32, b: Color32, t: f32) -> Color32 {
    let t = t.clamp(0.0, 1.0);
    let l = |x: u8, y: u8| (x as f32 + (y as f32 - x as f32) * t) as u8;
    Color32::from_rgb(l(a.r(), b.r()), l(a.g(), b.g()), l(a.b(), b.b()))
}
