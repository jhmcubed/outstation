//! What's shown over RuneLite (11/17/2026, Jace: "I do not want to see any transparent windows pop up. People will also
//! think that is something concerning if they are using the program and randomly see the edges of a transparent window
//! pop up"): the pings, the ✼s and the chat over the game, each in a window of Windows' own "layered" kind, drawn here.
//!
//! Until now each was an egui window (ui.rs, `overlay`): a see-through window of its own, which Windows could show for a
//! moment, edges and shadow, before its first picture, and every attempt to hide that (made hidden, kept open while on
//! air) traded it for something else. A layered window is shown as exactly the picture it's handed (UpdateLayeredWindow),
//! no frame, border or shadow ever, nothing where the picture is clear, and clicks go through it all (WS_EX_TRANSPARENT).
//! So nothing about the window itself can be seen at any moment, only what's drawn.
//!
//! The window (ui.rs) says what's there (`set`, a `Scene`: the pings and ✼s with when they came, the chat's lines and
//! where it sits); a thread of this module's own draws it, at the screen's own pace while anything moves (DwmFlush), and
//! hands each window its picture: premultiplied BGRA, each window only as big as what's in it. The words are Windows'
//! own text drawing (GDI) in the chat's font, as the chat in the picture's are (chatpic.rs), read back as how much of a
//! pixel they cover. Moving the chat and drawing redactions stay egui windows: those are boxes he's asked for.

use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// A ping: when it came, the spot on the screen (its pixels), the viewer's name.
#[derive(Clone)]
pub struct Ping {
    pub at: Instant,
    pub x: f32,
    pub y: f32,
    pub name: String,
}

/// A ✼: when it came, where across the game (0 to 1), the viewer's name.
#[derive(Clone)]
pub struct Star {
    pub at: Instant,
    pub x: f32,
    pub name: String,
}

/// A line of the chat over the game: when it came (None: the size's sample, always lit), its name's colour.
#[derive(Clone, PartialEq)]
pub struct ChatLine {
    pub at: Option<Instant>,
    pub name: String,
    pub text: String,
    pub rgb: [u8; 3],
}

/// The chat over the game: its box on the screen (pixels), its text size (pixels), its scale (pixels a point), its lines
/// oldest first, and the settings' "fades after" (0: never).
#[derive(Clone, PartialEq)]
pub struct Chat {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    pub px: i32,
    pub sc: f32,
    pub fade: u32,
    pub lines: Vec<ChatLine>,
}

/// Everything over the game: the game's place on the screen, the screen's pixels a point, and what's in it.
#[derive(Clone, Default)]
pub struct Scene {
    pub game: Option<(i32, i32, i32, i32)>,
    pub ppp: f32,
    pub pings: Vec<Ping>,
    pub stars: Vec<Star>,
    pub chat: Option<Chat>,
    pub lime: [u8; 3],
    pub dim: [u8; 3],
}

struct Shared {
    scene: Mutex<Scene>,
    woken: Condvar,
}

static SHARED: std::sync::OnceLock<Arc<Shared>> = std::sync::OnceLock::new();

/// What's over the game now (the window, each of its frames). The drawing thread starts the first time.
pub fn set(scene: Scene) {
    let sh = SHARED.get_or_init(|| {
        let sh = Arc::new(Shared { scene: Mutex::new(Scene::default()), woken: Condvar::new() });
        let s2 = sh.clone();
        let _ = std::thread::Builder::new().name("over the game".into()).spawn(move || run(s2));
        sh
    });
    *sh.scene.lock().unwrap() = scene;
    sh.woken.notify_all();
}

// ---- the drawing: a picture of premultiplied BGRA, placed on the screen ----

/// How long a ping lasts, in seconds (ui.rs keeps its own list as long).
pub const PING_S: f32 = 1.1;
/// The ping's cells, in points: the ring in squares this size, each there or not by the dither.
const PING_CELL: f32 = 3.0;
/// How long a ✼ rises, in seconds.
pub const STAR_S: f32 = 1.3;
const DARK: [u8; 3] = [10, 10, 10];

/// A picture for one window: where its top-left corner is on the screen, its size, its premultiplied BGRA.
pub struct Canvas {
    pub x0: i32,
    pub y0: i32,
    pub w: i32,
    pub h: i32,
    pub px: Vec<u8>,
}

impl Canvas {
    pub fn new(x0: i32, y0: i32, w: i32, h: i32) -> Canvas {
        Canvas { x0, y0, w: w.max(1), h: h.max(1), px: vec![0; (w.max(1) * h.max(1) * 4) as usize] }
    }
    /// One pixel (screen coordinates) laid over with a colour, this much of it (0 to 1).
    #[inline]
    fn over(&mut self, sx: i32, sy: i32, rgb: [u8; 3], k: f32) {
        let (x, y) = (sx - self.x0, sy - self.y0);
        if k <= 0.0 || x < 0 || y < 0 || x >= self.w || y >= self.h {
            return;
        }
        let k = k.min(1.0);
        let i = ((y * self.w + x) * 4) as usize;
        let p = &mut self.px[i..i + 4];
        let keep = 1.0 - k;
        p[0] = (rgb[2] as f32 * k + p[0] as f32 * keep).round() as u8;
        p[1] = (rgb[1] as f32 * k + p[1] as f32 * keep).round() as u8;
        p[2] = (rgb[0] as f32 * k + p[2] as f32 * keep).round() as u8;
        p[3] = (255.0 * k + p[3] as f32 * keep).round() as u8;
    }
    fn rect(&mut self, x0: i32, y0: i32, x1: i32, y1: i32, rgb: [u8; 3], k: f32) {
        for y in y0.max(self.y0)..y1.min(self.y0 + self.h) {
            for x in x0.max(self.x0)..x1.min(self.x0 + self.w) {
                self.over(x, y, rgb, k);
            }
        }
    }
    /// A mask (words) laid on with its top-left at (sx, sy) on the screen.
    fn mask(&mut self, m: &Mask, sx: i32, sy: i32, rgb: [u8; 3], k: f32) {
        if k <= 0.0 {
            return;
        }
        for y in 0..m.h {
            for x in 0..m.w {
                let c = m.cov[(y * m.w + x) as usize];
                if c > 0 {
                    self.over(sx + x, sy + y, rgb, c as f32 / 255.0 * k);
                }
            }
        }
    }
    /// The same mask spread round itself by `r` pixels (its rim): what covers any pixel within r of it.
    fn rim(&mut self, m: &Mask, sx: i32, sy: i32, r: i32, rgb: [u8; 3], k: f32) {
        if k <= 0.0 {
            return;
        }
        let offs = [(-r, 0), (r, 0), (0, -r), (0, r), (-r, -r), (r, r), (-r, r), (r, -r)];
        let (w, h) = (m.w + 2 * r, m.h + 2 * r);
        let mut cov = vec![0u8; (w * h) as usize];
        for (dx, dy) in offs {
            for y in 0..m.h {
                for x in 0..m.w {
                    let c = m.cov[(y * m.w + x) as usize];
                    let at = ((y + r + dy) * w + x + r + dx) as usize;
                    if c > cov[at] {
                        cov[at] = c;
                    }
                }
            }
        }
        self.mask(&Mask { w, h, cov }, sx - r, sy - r, rgb, k);
    }
}

/// Words drawn as how much of each pixel they cover (0 to 255).
pub struct Mask {
    pub w: i32,
    pub h: i32,
    pub cov: Vec<u8>,
}

/// The 8 x 8 ordered dither's thresholds (Bayer's), 0 to 1: the site's own (outside-portal.js's BAYER).
fn bayer(x: i32, y: i32) -> f32 {
    const M: [u8; 64] = [0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26, 12, 44, 4, 36, 14, 46, 6, 38, 60, 28,
        52, 20, 62, 30, 54, 22, 3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25, 15, 47, 7, 39, 13, 45, 5, 37, 63,
        31, 55, 23, 61, 29, 53, 21];
    (M[(((y & 7) * 8) + (x & 7)) as usize] as f32 + 0.5) / 64.0
}

/// Which font a mask is drawn in: the chat's, or Windows' symbols (the ✼).
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub enum Font {
    Chat,
    Symbols,
}

/// The words a mask is made of, by whoever can draw them (Windows' GDI; nothing where there's none, as in a test).
pub trait Words {
    fn mask(&mut self, text: &str, px: i32, font: Font) -> Option<Arc<Mask>>;
}

/// One ping's extent on the screen, from its spot (for the window's size): the ring's reach, and the name's.
fn ping_box(p: &Ping, ppp: f32, words: &mut dyn Words) -> (i32, i32, i32, i32) {
    let reach = (70.0 * ppp).ceil() as i32;
    let (cx, cy) = (p.x.round() as i32, p.y.round() as i32);
    let name_w = if p.name.is_empty() { 0 } else {
        words.mask(&p.name, (32.0 * ppp).round() as i32, Font::Chat).map_or(0, |m| m.w) + (30.0 * ppp) as i32
    };
    (cx - reach, cy - reach, (cx + reach).max(cx + (24.0 * ppp) as i32 + name_w), cy + reach)
}

/// One ping (11/14/2026, Jace: "make it a smooth animation, add dither to it…"): a ring that springs out from the spot,
/// eased, drawn every frame in square cells the ordered dither thins into the black as it grows; a fainter ring a moment
/// behind it; a dithered dot at the spot that fades; the viewer's name surfacing beside it and fading at the end. The
/// cells sit on a grid fixed to the screen, so the dither holds still while the ring moves through it. Held still
/// (reduced motion): the ring whole at its middle size. The same in points as it was drawn by egui (ui.rs till today).
pub fn draw_ping(cv: &mut Canvas, p: &Ping, age: f32, ppp: f32, still: bool, lime: [u8; 3], dim: [u8; 3], words: &mut dyn Words) {
    let k = if still { 0.45 } else { (age / PING_S).clamp(0.0, 1.0) };
    let ease = |t: f32| 1.0 - (1.0 - t.clamp(0.0, 1.0)).powi(3);
    let cell = PING_CELL * ppp;
    let (cx, cy) = (p.x, p.y);
    let ring = |cv: &mut Canvas, radius: f32, width: f32, light: f32| {
        let (radius, width) = (radius * ppp, width * ppp);
        if light <= 0.0 || width <= 0.0 {
            return;
        }
        let (x0, x1) = (((cx - radius - width) / cell).floor() as i32, ((cx + radius + width) / cell).ceil() as i32);
        let (y0, y1) = (((cy - radius - width) / cell).floor() as i32, ((cy + radius + width) / cell).ceil() as i32);
        for gy in y0..=y1 {
            for gx in x0..=x1 {
                let (px, py) = (gx as f32 * cell + cell / 2.0, gy as f32 * cell + cell / 2.0);
                let d = ((px - cx).powi(2) + (py - cy).powi(2)).sqrt();
                let edge = 1.0 - (d - radius).abs() / width;
                if edge <= 0.0 {
                    continue;
                }
                let level = edge.powf(0.7) * light;
                if bayer(gx, gy) >= level {
                    continue;
                }
                let colour = if level > 0.55 { lime } else { dim };
                cv.rect((gx as f32 * cell).floor() as i32, (gy as f32 * cell).floor() as i32,
                        ((gx + 1) as f32 * cell).floor() as i32, ((gy + 1) as f32 * cell).floor() as i32, colour, 1.0);
            }
        }
    };
    let light = if still { 1.0 } else { (1.0 - k).powf(1.4) };
    ring(cv, 12.0 + 44.0 * ease(k * 1.15), 3.0 + 6.0 * (1.0 - k), light);
    // (the echo, a little behind and fainter)
    let k2 = ((k * PING_S - 0.16) / PING_S).max(0.0);
    if !still && k2 > 0.0 {
        ring(cv, 8.0 + 30.0 * ease(k2 * 1.2), 2.0 + 4.0 * (1.0 - k2), (1.0 - k2).powf(1.8) * 0.7);
    }
    // the spot: a small disk, dithered away
    ring(cv, 0.0, 7.0 * (1.0 - k * 0.5), if still { 1.0 } else { (1.0 - k * 1.3).max(0.0) });
    if !p.name.is_empty() {
        // the name: in over the first tenth, out over the last third, a dark rim round it first
        let a = if still { 1.0 } else { (k / 0.1).min(1.0) * ((1.0 - k) / 0.33).min(1.0) };
        if a > 0.0 {
            if let Some(m) = words.mask(&p.name, (32.0 * ppp).round() as i32, Font::Chat) {
                let (ax, ay) = (cx + 24.0 * ppp, cy + (-28.0 + 4.0 * (1.0 - (k / 0.15).min(1.0))) * ppp);
                let (sx, sy) = (ax.round() as i32, (ay - m.h as f32 / 2.0).round() as i32);
                cv.rim(&m, sx, sy, (2.0 * ppp).round().max(1.0) as i32, DARK, a);
                cv.mask(&m, sx, sy, lime, a);
            }
        }
    }
}

/// A ✼ rising over the game from near its foot, in eight steps (the dither's way, not a glide), lime, then dim, then
/// all but gone, its name under it till near the end; a dark shadow behind both.
pub fn draw_star(cv: &mut Canvas, s: &Star, age: f32, game: (i32, i32, i32, i32), ppp: f32, lime: [u8; 3], dim: [u8; 3],
                 words: &mut dyn Words) {
    let (l, t, r, b) = game;
    let k = (age / 1.2).min(1.0);
    let step = (k * 8.0).floor() / 8.0;
    let (ax, ay) = (l as f32 + s.x * (r - l) as f32, t as f32 + (b - t) as f32 * (0.78 - 0.45 * step));
    let colour = if step < 0.6 { lime } else if step < 0.9 { dim } else { [0x22, 0x33, 0x0a] };
    let big = 34.0 + 10.0 * (1.0 - step);
    if let Some(m) = words.mask("\u{273C}", (big * ppp).round() as i32, Font::Symbols) {
        let (sx, sy) = ((ax - m.w as f32 / 2.0).round() as i32, (ay - m.h as f32 / 2.0).round() as i32);
        let o = (2.0 * ppp).round() as i32;
        cv.mask(&m, sx + o, sy + o, DARK, 1.0);
        cv.mask(&m, sx, sy, colour, 1.0);
    }
    if !s.name.is_empty() && step < 0.7 {
        if let Some(m) = words.mask(&s.name, (16.0 * ppp).round() as i32, Font::Chat) {
            let (sx, sy) = ((ax - m.w as f32 / 2.0).round() as i32, (ay + big * 0.6 * ppp).round() as i32);
            let o = ppp.round().max(1.0) as i32;
            cv.mask(&m, sx + o, sy + o, DARK, 1.0);
            cv.mask(&m, sx, sy, colour, 1.0);
        }
    }
}

/// How lit a line of the chat over the game is now (ui.rs's own rule: lit for the settings' time, then out in steps).
fn chat_alpha(l: &ChatLine, fade: u32) -> f32 {
    match l.at {
        None => 1.0,
        Some(at) => crate::ui::over_alpha(at.elapsed().as_secs_f32(), fade),
    }
}

// ---- the thread: what's there, drawn, handed to the windows ----

/// Each window's picture as last handed over, by what it was drawn from (so a still chat isn't drawn again).
#[derive(Default)]
struct Last {
    chat: Option<(Chat, Vec<u8>)>,
}

fn run(sh: Arc<Shared>) {
    #[cfg(windows)]
    let mut wins = native::Windows::new();
    #[cfg(windows)]
    let mut words = native::Gdi::new();
    #[cfg(not(windows))]
    let mut words = NoWords;
    let mut last = Last::default();
    let mut chat_cache: Option<crate::chatpic::Drawn> = None;
    loop {
        #[cfg(windows)]
        native::pump();
        let scene = sh.scene.lock().unwrap().clone();
        let still = crate::cursor::still();
        let ppp = if scene.ppp > 0.0 { scene.ppp } else { 1.0 };
        let mut moving = false;
        // the pings
        let pings: Vec<(Ping, f32)> = scene.pings.iter().map(|p| (p.clone(), p.at.elapsed().as_secs_f32()))
            .filter(|(_, a)| *a < PING_S).collect();
        let pic = if pings.is_empty() { None } else {
            moving = true;
            let mut bx = (i32::MAX, i32::MAX, i32::MIN, i32::MIN);
            for (p, _) in &pings {
                let b = ping_box(p, ppp, &mut words);
                bx = (bx.0.min(b.0), bx.1.min(b.1), bx.2.max(b.2), bx.3.max(b.3));
            }
            let mut cv = Canvas::new(bx.0, bx.1, bx.2 - bx.0, bx.3 - bx.1);
            for (p, age) in &pings {
                draw_ping(&mut cv, p, *age, ppp, still, scene.lime, scene.dim, &mut words);
            }
            Some(cv)
        };
        #[cfg(windows)]
        wins.put(0, pic.as_ref());
        // the ✼s
        let stars: Vec<(Star, f32)> = scene.stars.iter().map(|s| (s.clone(), s.at.elapsed().as_secs_f32()))
            .filter(|(_, a)| *a < STAR_S).collect();
        let pic = match (stars.is_empty(), scene.game) {
            (false, Some(g)) => {
                moving = true;
                let m = (60.0 * ppp) as i32;
                let mut cv = Canvas::new(g.0 - m, g.1 - m, g.2 - g.0 + 2 * m, g.3 - g.1 + 2 * m);
                for (s, age) in &stars {
                    draw_star(&mut cv, s, *age, g, ppp, scene.lime, scene.dim, &mut words);
                }
                Some(cv)
            }
            _ => None,
        };
        #[cfg(windows)]
        wins.put(1, pic.as_ref());
        // the chat over the game: drawn again only when its lines, place or how lit they are change
        match &scene.chat {
            Some(c) if !c.lines.is_empty() => {
                let alphas: Vec<u8> = c.lines.iter().map(|l| (chat_alpha(l, c.fade) * 255.0).round() as u8).collect();
                if alphas.iter().any(|&a| a > 0 && a < 255) {
                    moving = true;
                }
                if last.chat.as_ref().is_none_or(|(lc, la)| lc != c || *la != alphas) {
                    let pic = draw_chat(c, &alphas, &mut chat_cache);
                    #[cfg(windows)]
                    wins.put(2, pic.as_ref());
                    let _ = pic;
                    last.chat = Some((c.clone(), alphas));
                }
            }
            _ => {
                if last.chat.take().is_some() {
                    #[cfg(windows)]
                    wins.put(2, None);
                }
            }
        }
        if moving {
            // (the next frame on the screen's own refresh, so what moves moves smoothly)
            #[cfg(windows)]
            native::next_frame();
            #[cfg(not(windows))]
            std::thread::sleep(Duration::from_millis(8));
        } else {
            // (nothing moving: woken by the next change, or a look now and then for a line's fade to begin)
            let g = sh.scene.lock().unwrap();
            let _ = sh.woken.wait_timeout(g, Duration::from_millis(if scene.chat.is_some() { 100 } else { 250 }));
        }
    }
}

/// The chat over the game as the chat in the picture draws it (chatpic.rs: the rim, the names, the words), each line as
/// lit as it is now.
fn draw_chat(c: &Chat, alphas: &[u8], cache: &mut Option<crate::chatpic::Drawn>) -> Option<Canvas> {
    if alphas.iter().all(|&a| a == 0) || c.w < 16 || c.h < 8 || c.px < 5 {
        return None;
    }
    let lines: Vec<crate::chatpic::Line> = c.lines.iter().map(|l| crate::chatpic::Line {
        name: l.name.clone(), text: l.text.clone(), name_rgb: l.rgb, alpha: 1.0,
    }).collect();
    let key = (c.lines.iter().map(|l| (l.name.clone(), l.text.clone())).collect::<Vec<_>>(), c.w, c.h, c.px);
    if cache.as_ref().is_none_or(|d| d.key != key) {
        *cache = crate::chatpic::draw(&lines, c.w, c.h, c.px, c.sc, key);
    }
    let d = cache.as_ref()?;
    let mut cv = Canvas::new(c.x, c.y, d.w, d.h);
    for &(y0, y1, i) in &d.rows {
        let (Some(l), Some(&a)) = (c.lines.get(i), alphas.get(i)) else { continue };
        let a = a as f32 / 255.0;
        if a <= 0.0 {
            continue;
        }
        for y in y0.max(0)..y1.min(d.h) {
            for x in 0..d.w {
                let k = (y * d.w + x) as usize;
                let (r, n, w) = (d.rim[k], d.name[k], d.words[k]);
                if r == 0 && n == 0 && w == 0 {
                    continue;
                }
                cv.over(c.x + x, c.y + y, DARK, r as f32 / 255.0 * a);
                cv.over(c.x + x, c.y + y, l.rgb, n as f32 / 255.0 * a);
                cv.over(c.x + x, c.y + y, crate::chatpic::WORDS_RGB, w as f32 / 255.0 * a);
            }
        }
    }
    Some(cv)
}

#[cfg(not(windows))]
struct NoWords;
#[cfg(not(windows))]
impl Words for NoWords {
    fn mask(&mut self, _: &str, _: i32, _: Font) -> Option<Arc<Mask>> {
        None
    }
}

// ---- Windows' own: the layered windows, and GDI's words ----

#[cfg(windows)]
mod native {
    use super::*;
    use windows::core::{w, PCWSTR};
    use windows::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, POINT, SIZE, WPARAM};
    use windows::Win32::Graphics::Gdi::*;
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::WindowsAndMessaging::*;

    unsafe extern "system" fn proc_(h: HWND, m: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
        // (never a target for the mouse, whatever's drawn there)
        if m == WM_NCHITTEST {
            return LRESULT(-1); // HTTRANSPARENT
        }
        unsafe { DefWindowProcW(h, m, wp, lp) }
    }

    /// Messages for this thread's windows taken and handled, so Windows never thinks them hung.
    pub fn pump() {
        unsafe {
            let mut msg = MSG::default();
            while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
    }

    /// Till the screen's next refresh (Windows' compositor), or a 120th of a second if it won't say.
    pub fn next_frame() {
        if unsafe { windows::Win32::Graphics::Dwm::DwmFlush() }.is_err() {
            std::thread::sleep(Duration::from_millis(8));
        }
    }

    struct Win {
        hwnd: HWND,
        shown: bool,
        dc: HDC,
        bmp: HBITMAP,
        old: HGDIOBJ,
        bits: *mut u8,
        cap: (i32, i32),
    }

    /// The three windows (the pings, the ✼s, the chat), each made the first time it has something to show and kept.
    pub struct Windows {
        class: bool,
        wins: [Option<Win>; 3],
    }

    impl Windows {
        pub fn new() -> Windows {
            Windows { class: false, wins: [None, None, None] }
        }

        fn make(&mut self, which: usize) -> Option<Win> {
            unsafe {
                let inst = GetModuleHandleW(PCWSTR::null()).ok()?;
                let name = w!("outstation over the game");
                if !self.class {
                    let wc = WNDCLASSEXW {
                        cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
                        lpfnWndProc: Some(proc_),
                        hInstance: inst.into(),
                        lpszClassName: name,
                        ..Default::default()
                    };
                    RegisterClassExW(&wc);
                    self.class = true;
                }
                let title = [w!("outstation ✼ pings"), w!("outstation ✼ signals"), w!("outstation ✼ chat over the game")][which];
                // (layered: shown only as the picture it's handed; click-through; above other windows; never in the
                // taskbar or Alt+Tab; never takes the focus. Not visible till its first picture is in)
                let hwnd = CreateWindowExW(
                    WS_EX_LAYERED | WS_EX_TRANSPARENT | WS_EX_TOPMOST | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE,
                    name, title, WS_POPUP, 0, 0, 1, 1, None, None, Some(inst.into()), None).ok()?;
                let dc = CreateCompatibleDC(None);
                Some(Win { hwnd, shown: false, dc, bmp: HBITMAP::default(), old: HGDIOBJ::default(), bits: std::ptr::null_mut(), cap: (0, 0) })
            }
        }

        /// A window's picture handed over (None: nothing there, so it's hidden).
        pub fn put(&mut self, which: usize, pic: Option<&Canvas>) {
            let Some(pic) = pic else {
                if let Some(w) = self.wins[which].as_mut() {
                    if w.shown {
                        unsafe { let _ = ShowWindow(w.hwnd, SW_HIDE); }
                        w.shown = false;
                    }
                }
                return;
            };
            if self.wins[which].is_none() {
                self.wins[which] = self.make(which);
            }
            let Some(w) = self.wins[which].as_mut() else { return };
            unsafe {
                // the bitmap kept, made bigger when a picture's bigger than it
                if pic.w > w.cap.0 || pic.h > w.cap.1 {
                    let (cw, ch) = (pic.w.max(w.cap.0), pic.h.max(w.cap.1));
                    let mut bmi = BITMAPINFO::default();
                    bmi.bmiHeader = BITMAPINFOHEADER {
                        biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32, biWidth: cw, biHeight: -ch, biPlanes: 1,
                        biBitCount: 32, biCompression: BI_RGB.0, ..Default::default()
                    };
                    let mut bits: *mut core::ffi::c_void = std::ptr::null_mut();
                    let Ok(bmp) = CreateDIBSection(Some(w.dc), &bmi, DIB_RGB_COLORS, &mut bits, None, 0) else { return };
                    let old = SelectObject(w.dc, bmp.into());
                    if !w.bmp.is_invalid() {
                        let _ = DeleteObject(w.bmp.into());
                    } else {
                        w.old = old;
                    }
                    w.bmp = bmp;
                    w.bits = bits as *mut u8;
                    w.cap = (cw, ch);
                }
                let stride = (w.cap.0 * 4) as usize;
                let row = (pic.w * 4) as usize;
                for y in 0..pic.h as usize {
                    std::ptr::copy_nonoverlapping(pic.px.as_ptr().add(y * row), w.bits.add(y * stride), row);
                }
                let _ = GdiFlush();
                let blend = BLENDFUNCTION { BlendOp: AC_SRC_OVER as u8, BlendFlags: 0, SourceConstantAlpha: 255, AlphaFormat: AC_SRC_ALPHA as u8 };
                let _ = UpdateLayeredWindow(w.hwnd, None, Some(&POINT { x: pic.x0, y: pic.y0 }), Some(&SIZE { cx: pic.w, cy: pic.h }),
                                            Some(w.dc), Some(&POINT { x: 0, y: 0 }), COLORREF(0), Some(&blend), ULW_ALPHA);
                if !w.shown {
                    let _ = ShowWindow(w.hwnd, SW_SHOWNOACTIVATE);
                    let _ = SetWindowPos(w.hwnd, Some(HWND_TOPMOST), 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
                    w.shown = true;
                }
            }
        }
    }

    impl Drop for Windows {
        fn drop(&mut self) {
            for w in self.wins.iter_mut().flatten() {
                unsafe {
                    if !w.old.is_invalid() {
                        SelectObject(w.dc, w.old);
                    }
                    if !w.bmp.is_invalid() {
                        let _ = DeleteObject(w.bmp.into());
                    }
                    let _ = DeleteDC(w.dc);
                    let _ = DestroyWindow(w.hwnd);
                }
            }
        }
    }

    /// GDI's words: drawn white on black into a bitmap of their own size and read back as coverage; kept by what they
    /// are, so a name's drawn once a size.
    pub struct Gdi {
        dc: HDC,
        kept: HashMap<(String, i32, Font), Arc<Mask>>,
    }

    impl Gdi {
        pub fn new() -> Gdi {
            crate::chatpic::chat_font();
            Gdi { dc: unsafe { CreateCompatibleDC(None) }, kept: HashMap::new() }
        }
    }

    impl Words for Gdi {
        fn mask(&mut self, text: &str, px: i32, font: Font) -> Option<Arc<Mask>> {
            let key = (text.to_string(), px, font);
            if let Some(m) = self.kept.get(&key) {
                return Some(m.clone());
            }
            if self.kept.len() > 300 {
                self.kept.clear();
            }
            if px < 4 || text.is_empty() || self.dc.is_invalid() {
                return None;
            }
            let m = unsafe {
                let face = match font { Font::Chat => w!("RuneScape Chat '07"), Font::Symbols => w!("Segoe UI Symbol") };
                // (the cell's height, as egui sizes its text: a font's ascent and descent, not its em)
                let f = CreateFontW(px, 0, 0, 0, FW_NORMAL.0 as i32, 0, 0, 0, DEFAULT_CHARSET, OUT_TT_PRECIS,
                                    CLIP_DEFAULT_PRECIS, ANTIALIASED_QUALITY, (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32, face);
                let old_f = SelectObject(self.dc, f.into());
                let wide: Vec<u16> = text.encode_utf16().collect();
                let mut size = SIZE::default();
                let _ = GetTextExtentPoint32W(self.dc, &wide, &mut size);
                // (room round the words for a glyph that reaches past its advance)
                let pad = (px / 4).max(2);
                let (bw, bh) = (size.cx + 2 * pad, size.cy.max(px) + pad);
                let mut bmi = BITMAPINFO::default();
                bmi.bmiHeader = BITMAPINFOHEADER {
                    biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32, biWidth: bw, biHeight: -bh, biPlanes: 1,
                    biBitCount: 32, biCompression: BI_RGB.0, ..Default::default()
                };
                let mut bits: *mut core::ffi::c_void = std::ptr::null_mut();
                let out = match CreateDIBSection(Some(self.dc), &bmi, DIB_RGB_COLORS, &mut bits, None, 0) {
                    Ok(bmp) => {
                        let old_b = SelectObject(self.dc, bmp.into());
                        std::ptr::write_bytes(bits as *mut u8, 0, (bw * bh * 4) as usize);
                        let _ = SetBkMode(self.dc, TRANSPARENT);
                        let _ = SetTextColor(self.dc, COLORREF(0x00FF_FFFF));
                        let _ = TextOutW(self.dc, pad, 0, &wide);
                        let _ = GdiFlush();
                        let all = std::slice::from_raw_parts(bits as *const u8, (bw * bh * 4) as usize);
                        let cov: Vec<u8> = (0..(bw * bh) as usize).map(|i| all[i * 4 + 1]).collect();
                        SelectObject(self.dc, old_b);
                        let _ = DeleteObject(bmp.into());
                        // (trimmed to what's drawn, left and right, so a name's place is where its letters are)
                        let cols: Vec<bool> = (0..bw).map(|x| (0..bh).any(|y| cov[(y * bw + x) as usize] > 0)).collect();
                        match (cols.iter().position(|&c| c), cols.iter().rposition(|&c| c)) {
                            (Some(a), Some(b)) => {
                                let (a, b) = (a as i32, b as i32);
                                let w = b - a + 1;
                                let mut t = vec![0u8; (w * bh) as usize];
                                for y in 0..bh {
                                    let s = (y * bw + a) as usize;
                                    t[(y * w) as usize..((y + 1) * w) as usize].copy_from_slice(&cov[s..s + w as usize]);
                                }
                                Some(Arc::new(Mask { w, h: bh, cov: t }))
                            }
                            _ => None,
                        }
                    }
                    Err(_) => None,
                };
                SelectObject(self.dc, old_f);
                let _ = DeleteObject(f.into());
                out
            };
            if let Some(m) = &m {
                self.kept.insert(key, m.clone());
            }
            m
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Boxes;
    impl Words for Boxes {
        fn mask(&mut self, text: &str, px: i32, _: Font) -> Option<Arc<Mask>> {
            let w = (text.chars().count() as i32 * px / 2).max(1);
            Some(Arc::new(Mask { w, h: px, cov: vec![255; (w * px) as usize] }))
        }
    }

    #[test]
    fn a_ping_is_drawn_inside_its_own_box_and_nothing_else_is_lit() {
        let p = Ping { at: Instant::now(), x: 500.0, y: 400.0, name: "a visitor".into() };
        for ppp in [1.0, 1.5, 2.0] {
            let b = ping_box(&p, ppp, &mut Boxes);
            for age in [0.0, 0.1, 0.3, 0.6, 0.9, 1.05] {
                // (a canvas twice as big round it: nothing may land outside the box)
                let (w, h) = (b.2 - b.0, b.3 - b.1);
                let mut cv = Canvas::new(b.0 - w / 2, b.1 - h / 2, w * 2, h * 2);
                draw_ping(&mut cv, &p, age, ppp, false, [0xb9, 0xff, 0], [0x62, 0x85, 0x06], &mut Boxes);
                let mut lit = 0;
                for y in 0..cv.h {
                    for x in 0..cv.w {
                        if cv.px[((y * cv.w + x) * 4 + 3) as usize] > 0 {
                            lit += 1;
                            let (sx, sy) = (cv.x0 + x, cv.y0 + y);
                            assert!(sx >= b.0 && sx < b.2 && sy >= b.1 && sy < b.3, "ppp {} age {}: ({}, {}) outside {:?}", ppp, age, sx, sy, b);
                        }
                    }
                }
                assert!(lit > 0 || age > 1.0, "nothing drawn at {}", age);
            }
        }
    }

    #[test]
    fn premultiplied_pixels_never_have_more_colour_than_cover() {
        let mut cv = Canvas::new(0, 0, 4, 1);
        cv.over(0, 0, [255, 255, 255], 0.5);
        cv.over(0, 0, [0xb9, 0xff, 0], 0.3);
        cv.over(1, 0, [10, 10, 10], 1.0);
        for x in 0..2 {
            let p = &cv.px[x * 4..x * 4 + 4];
            assert!(p[0] <= p[3] && p[1] <= p[3] && p[2] <= p[3], "{:?}", p);
        }
        assert_eq!(&cv.px[8..16], &[0; 8]);
    }
}
