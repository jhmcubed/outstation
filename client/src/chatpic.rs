//! The chat in the picture (11/07/2026, Jace: "add a toggle to capture chat on the broadcast screen"): the chat over
//! the game, drawn into each frame on this PC as it sits over the game, as big, lit and fading the same, so
//! observers, the tape and the clips all have it. Off to begin with (settings, chat_in_picture).
//!
//! The window (ui.rs) says what's to be drawn and where (`set`, each frame while there's a line); the capture draws
//! it into the frame after the redactions (`onto`). The words are drawn by Windows' own text drawing (GDI) in the
//! chat's own font (assets/fonts, taken in from memory for this program only), white on black, twice: once for the
//! dark rim round the words, once for the words, each read back as how much of a pixel is covered; the colours go on
//! as the frame is drawn into. Drawn again only when the lines or the size change; a fade only changes how strongly.

use crate::capture::Image;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

/// An emote's picture (RGBA, not premultiplied), for the chat drawn over the game and into the picture (11/17/2026,
/// Jace: "emotes also do not show in the overlay text").
pub struct Face {
    pub w: u32,
    pub h: u32,
    pub rgba: Vec<u8>,
}

/// The emotes by name; the same set while it's the same Arc (so a frame's check of it is a pointer's, never a picture's).
#[derive(Clone, Default)]
pub struct Faces(pub Arc<HashMap<String, Arc<Face>>>);

impl PartialEq for Faces {
    fn eq(&self, o: &Faces) -> bool {
        Arc::ptr_eq(&self.0, &o.0)
    }
}

/// What a piece of a row is: the name, words, or an emote (`:name:` among the faces).
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum Kind {
    Name,
    Words,
    Face,
}

pub(crate) type Key = (Vec<(String, String)>, i32, i32, i32);

/// What a drawing's drawn from: the lines' names and words, which emotes there are, the box and the text's size.
pub(crate) fn key_of(lines: &[Line], faces: &Faces, bw: i32, bh: i32, px: i32) -> Key {
    let mut v: Vec<(String, String)> = lines.iter().map(|l| (l.name.clone(), l.text.clone())).collect();
    let mut names: Vec<String> = faces.0.keys().cloned().collect();
    names.sort();
    v.push(("\u{0}faces".into(), names.join(",")));
    (v, bw, bh, px)
}

/// One line, as over the game: its name's colour, and how lit it is (1: lit, 0: gone).
#[derive(Clone, PartialEq)]
pub struct Line {
    pub name: String,
    pub text: String,
    pub name_rgb: [u8; 3],
    pub alpha: f32,
}

/// What's drawn, in the chat over the game's own measure: its points, and one point as a share of the game's width
/// (so the same in any size of frame); where its box sits, as shares of the game's width and height.
#[derive(Clone)]
pub struct Plan {
    pub fx: f32,
    pub fy: f32,
    pub w_pt: f32,
    pub h_pt: f32,
    pub size_pt: f32,
    pub pt: f32,
    /// oldest first, as over the game
    pub lines: Vec<Line>,
    /// the emotes, drawn where a word is `:name:`
    pub faces: Faces,
}

static PLAN: Mutex<Option<Plan>> = Mutex::new(None);

/// what was drawn last: what it was drawn for, and the drawing (the windows over the game draw their chat with it too,
/// overlay.rs)
pub(crate) struct Drawn {
    pub(crate) key: Key,
    pub(crate) w: i32,
    pub(crate) h: i32,
    /// how much of each pixel the rim, a name and the words cover (0–255)
    pub(crate) rim: Vec<u8>,
    pub(crate) name: Vec<u8>,
    pub(crate) words: Vec<u8>,
    /// each line's rows: its first and last, and which line it is
    pub(crate) rows: Vec<(i32, i32, usize)>,
    /// each emote: its box (x, y, w, h), which line it's in, its picture
    pub(crate) faces: Vec<(i32, i32, i32, i32, usize, Arc<Face>)>,
}

static DRAWN: Mutex<Option<Drawn>> = Mutex::new(None);

/// The window's word on what to draw (None: nothing).
pub fn set(p: Option<Plan>) {
    *PLAN.lock().unwrap() = p;
}

pub(crate) const WORDS_RGB: [u8; 3] = [0xf0, 0xf0, 0xf0];

/// The chat drawn into a frame, if there's any to draw.
pub fn onto(img: &mut Image) {
    let Some(p) = PLAN.lock().unwrap().clone() else { return };
    if p.lines.iter().all(|l| l.alpha <= 0.0) {
        return;
    }
    // (the frame's pixels for one of the chat's points)
    let sc = p.pt * img.w as f32;
    let (bw, bh) = ((p.w_pt * sc).round() as i32, (p.h_pt * sc).round() as i32);
    let px = (p.size_pt * sc).round() as i32;
    if bw < 16 || bh < 8 || px < 5 {
        return;
    }
    let key = key_of(&p.lines, &p.faces, bw, bh, px);
    let mut drawn = DRAWN.lock().unwrap();
    if drawn.as_ref().is_none_or(|d| d.key != key) {
        *drawn = draw(&p.lines, &p.faces, bw, bh, px, sc, key);
    }
    let Some(d) = drawn.as_ref() else { return };
    let (ox, oy) = ((p.fx * img.w as f32).round() as i32, (p.fy * img.h as f32).round() as i32);
    for &(y0, y1, i) in &d.rows {
        let Some(line) = p.lines.get(i) else { continue };
        let a = line.alpha.clamp(0.0, 1.0);
        if a <= 0.0 {
            continue;
        }
        for y in y0.max(0)..y1.min(d.h) {
            let fy = oy + y;
            if fy < 0 || fy >= img.h as i32 {
                continue;
            }
            for x in 0..d.w {
                let fx = ox + x;
                if fx < 0 || fx >= img.w as i32 {
                    continue;
                }
                let k = (y * d.w + x) as usize;
                let (r, n, w) = (d.rim[k], d.name[k], d.words[k]);
                if r == 0 && n == 0 && w == 0 {
                    continue;
                }
                let at = ((fy as u32 * img.w + fx as u32) * 4) as usize;
                let px = &mut img.bgra[at..at + 4];
                // (BGRA: the rim's dark, then the name's colour, then the words', each as strongly as it covers)
                blend(px, [10, 10, 10], r as f32 / 255.0 * a);
                blend(px, line.name_rgb, n as f32 / 255.0 * a);
                blend(px, WORDS_RGB, w as f32 / 255.0 * a);
            }
        }
    }
    for (x0, y0, w, h, i, face) in &d.faces {
        let a = p.lines.get(*i).map_or(0.0, |l| l.alpha.clamp(0.0, 1.0));
        each_face_pixel(face, *w, *h, |x, y, rgb, k| {
            let (fx, fy) = (ox + x0 + x, oy + y0 + y);
            if fx >= 0 && fy >= 0 && fx < img.w as i32 && fy < img.h as i32 {
                let at = ((fy as u32 * img.w + fx as u32) * 4) as usize;
                blend(&mut img.bgra[at..at + 4], rgb, k * a);
            }
        });
    }
}

/// An emote's picture shrunk (or grown) to w x h, nearest pixel: each pixel's place in the box, its colour and how
/// much of it there is.
pub(crate) fn each_face_pixel(face: &Face, w: i32, h: i32, mut f: impl FnMut(i32, i32, [u8; 3], f32)) {
    if w <= 0 || h <= 0 || face.w == 0 || face.h == 0 || face.rgba.len() < (face.w * face.h * 4) as usize {
        return;
    }
    for y in 0..h {
        let sy = ((y as u64 * face.h as u64) / h as u64) as u32;
        for x in 0..w {
            let sx = ((x as u64 * face.w as u64) / w as u64) as u32;
            let i = ((sy * face.w + sx) * 4) as usize;
            let k = face.rgba[i + 3] as f32 / 255.0;
            if k > 0.0 {
                f(x, y, [face.rgba[i], face.rgba[i + 1], face.rgba[i + 2]], k);
            }
        }
    }
}

fn blend(px: &mut [u8], rgb: [u8; 3], k: f32) {
    if k <= 0.0 {
        return;
    }
    let mix = |a: u8, b: u8| (a as f32 + (b as f32 - a as f32) * k).round() as u8;
    px[0] = mix(px[0], rgb[2]);
    px[1] = mix(px[1], rgb[1]);
    px[2] = mix(px[2], rgb[0]);
}

/// Words cut into rows no wider than `room`, by the pixels `measure` gives: the name first (never cut), then the
/// words, a word that's an emote (`face` gives its width) its picture's width. Each row: its pieces, each with where it
/// starts, what it says and what it is.
fn rows_of(name: &str, text: &str, room: i32, measure: &dyn Fn(&str) -> i32, face: &dyn Fn(&str) -> Option<i32>)
           -> Vec<Vec<(i32, String, Kind)>> {
    let mut rows: Vec<Vec<(i32, String, Kind)>> = vec![Vec::new()];
    let mut x = 0;
    if !name.is_empty() {
        rows[0].push((0, name.to_string(), Kind::Name));
        x = measure(&format!("{}  ", name));
    }
    let space = measure(" ");
    for word in text.split_whitespace() {
        let (w, kind) = match face(word) { Some(w) => (w, Kind::Face), None => (measure(word), Kind::Words) };
        if x > 0 && x + w > room {
            rows.push(Vec::new());
            x = 0;
        }
        rows.last_mut().unwrap().push((x, word.to_string(), kind));
        x += w + space;
    }
    rows
}

/// A word's emote, if it's `:name:` for one of the faces.
fn face_of<'a>(word: &str, faces: &'a Faces) -> Option<&'a Arc<Face>> {
    let n = word.strip_prefix(':')?.strip_suffix(':')?;
    if !crate::emotes::name_ok(n) {
        return None;
    }
    faces.0.get(n)
}

/// The chat's font for this program only, from the copy built into it, taken in once (GDI's "RuneScape Chat '07").
#[cfg(windows)]
pub(crate) fn chat_font() {
    use windows::Win32::Graphics::Gdi::AddFontMemResourceEx;
    static FONT: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    FONT.get_or_init(|| unsafe {
        let bytes: &'static [u8] = include_bytes!("../assets/fonts/runescape-chat.ttf");
        let n = 0u32;
        let _ = AddFontMemResourceEx(bytes.as_ptr() as *const _, bytes.len() as u32, None, &n);
    });
}

#[cfg(windows)]
pub(crate) fn draw(lines: &[Line], faces: &Faces, bw: i32, bh: i32, px: i32, sc: f32, key: Key) -> Option<Drawn> {
    use windows::Win32::Foundation::{COLORREF, SIZE};
    use windows::Win32::Graphics::Gdi::*;
    use windows::core::w;
    chat_font();
    unsafe {
        let dc = CreateCompatibleDC(None);
        if dc.is_invalid() {
            return None;
        }
        let mut bmi = BITMAPINFO::default();
        bmi.bmiHeader = BITMAPINFOHEADER {
            biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32, biWidth: bw, biHeight: -bh, biPlanes: 1,
            biBitCount: 32, biCompression: BI_RGB.0, ..Default::default()
        };
        let mut bits: *mut core::ffi::c_void = std::ptr::null_mut();
        let Ok(bmp) = CreateDIBSection(Some(dc), &bmi, DIB_RGB_COLORS, &mut bits, None, 0) else {
            let _ = DeleteDC(dc);
            return None;
        };
        let old_bmp = SelectObject(dc, bmp.into());
        let font = CreateFontW(-px, 0, 0, 0, FW_NORMAL.0 as i32, 0, 0, 0, DEFAULT_CHARSET, OUT_TT_PRECIS,
                               CLIP_DEFAULT_PRECIS, ANTIALIASED_QUALITY, (DEFAULT_PITCH.0 | FF_DONTCARE.0) as u32,
                               w!("RuneScape Chat '07"));
        let old_font = SelectObject(dc, font.into());
        let _ = SetBkMode(dc, TRANSPARENT);
        let _ = SetTextColor(dc, COLORREF(0x00FF_FFFF));
        let mut tm = TEXTMETRICW::default();
        let _ = GetTextMetricsW(dc, &mut tm);
        let lh = tm.tmHeight.max(1);
        // (an emote a little taller than the words, as in the outstation's own chat; a row with one as tall as it)
        let eh = (lh as f32 * 1.3).round() as i32;
        let face_w = |word: &str| face_of(word, faces).map(|f| ((eh as f32 * f.w as f32 / f.h.max(1) as f32).round() as i32).clamp(1, eh * 3));
        let measure = |s: &str| -> i32 {
            let w: Vec<u16> = s.encode_utf16().collect();
            let mut size = SIZE::default();
            let _ = GetTextExtentPoint32W(dc, &w, &mut size);
            size.cx
        };
        let pixels = (bw * bh) as usize;
        let read = || -> Vec<u8> {
            let _ = GdiFlush();
            let all = std::slice::from_raw_parts(bits as *const u8, pixels * 4);
            (0..pixels).map(|i| all[i * 4 + 1]).collect()
        };
        let clear = || std::ptr::write_bytes(bits as *mut u8, 0, pixels * 4);
        // as over the game: 8 points in, 6 up from the bottom, 4 between lines, newest at the bottom, none above 10
        let (left, room) = ((8.0 * sc).round() as i32, bw - (16.0 * sc).round() as i32);
        let mut placed: Vec<(i32, Vec<Vec<(i32, String, Kind)>>, Vec<i32>, usize)> = Vec::new();
        let mut yy = bh - (6.0 * sc).round() as i32;
        for (i, l) in lines.iter().enumerate().rev() {
            let rows = rows_of(&l.name, &l.text, room.max(1), &measure, &face_w);
            let hs: Vec<i32> = rows.iter().map(|r| if r.iter().any(|p| p.2 == Kind::Face) { eh.max(lh) } else { lh }).collect();
            let top = yy - hs.iter().sum::<i32>();
            if top < (10.0 * sc).round() as i32 && !placed.is_empty() {
                break;
            }
            placed.push((top, rows, hs, i));
            yy = top - (4.0 * sc).round() as i32;
            if yy < (10.0 * sc).round() as i32 {
                break;
            }
        }
        let out = |dc: HDC, which: &dyn Fn(Kind) -> bool, offsets: &[(i32, i32)]| {
            for (top, rows, hs, _) in &placed {
                let mut ry = *top;
                for (r, row) in rows.iter().enumerate() {
                    let ty = ry + (hs[r] - lh) / 2;
                    for (x, piece, kind) in row {
                        if *kind == Kind::Face || !which(*kind) {
                            continue;
                        }
                        let w: Vec<u16> = piece.encode_utf16().collect();
                        for (dx, dy) in offsets {
                            let _ = TextOutW(dc, left + x + dx, ty + dy, &w);
                        }
                    }
                    ry += hs[r];
                }
            }
        };
        let rim_at: Vec<(i32, i32)> = {
            let o = ((sc * 1.0).round() as i32).max(1);
            vec![(-o, 0), (o, 0), (0, -o), (0, o), (2 * o, 2 * o)]
        };
        clear();
        out(dc, &|_| true, &rim_at);
        let rim = read();
        clear();
        out(dc, &|k| k == Kind::Name, &[(0, 0)]);
        let name = read();
        clear();
        out(dc, &|k| k == Kind::Words, &[(0, 0)]);
        let words = read();
        let rows = placed.iter().map(|(top, _, hs, i)| (top - 3, top + hs.iter().sum::<i32>() + 3, *i)).collect();
        let mut drawn_faces = Vec::new();
        for (top, rows, hs, i) in &placed {
            let mut ry = *top;
            for (r, row) in rows.iter().enumerate() {
                for (x, piece, kind) in row {
                    if let (Kind::Face, Some(f)) = (kind, face_of(piece, faces)) {
                        let w = face_w(piece).unwrap_or(eh);
                        drawn_faces.push((left + x, ry + (hs[r] - eh) / 2, w, eh, *i, f.clone()));
                    }
                }
                ry += hs[r];
            }
        }
        SelectObject(dc, old_font);
        SelectObject(dc, old_bmp);
        let _ = DeleteObject(font.into());
        let _ = DeleteObject(bmp.into());
        let _ = DeleteDC(dc);
        Some(Drawn { key, w: bw, h: bh, rim, name, words, rows, faces: drawn_faces })
    }
}

#[cfg(not(windows))]
pub(crate) fn draw(_: &[Line], _: &Faces, _: i32, _: i32, _: i32, _: f32, _: Key) -> Option<Drawn> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_words_go_into_rows_no_wider_than_the_room() {
        // (a pixel a letter, a space a pixel)
        let m = |s: &str| s.chars().count() as i32;
        let none = |_: &str| None;
        let rows = rows_of("moss", "one two three four", 14, &m, &none);
        assert_eq!(rows[0][0], (0, "moss".to_string(), Kind::Name));
        assert_eq!(rows[0][1], (6, "one".to_string(), Kind::Words));
        assert_eq!(rows[0][2], (10, "two".to_string(), Kind::Words));
        assert_eq!(rows[1][0], (0, "three".to_string(), Kind::Words));
        assert_eq!(rows[1][1], (6, "four".to_string(), Kind::Words));
        // one word longer than the room has a row of its own, as it is
        let rows = rows_of("", "a enormousword b", 5, &m, &none);
        assert_eq!(rows.iter().map(|r| r.len()).collect::<Vec<_>>(), vec![1, 1, 1]);
    }

    #[test]
    fn an_emote_is_a_picture_as_wide_as_it_is_drawn() {
        let m = |s: &str| s.chars().count() as i32;
        let mut map = HashMap::new();
        map.insert("cat".to_string(), Arc::new(Face { w: 2, h: 1, rgba: vec![255; 8] }));
        let faces = Faces(Arc::new(map));
        let fw = |w: &str| face_of(w, &faces).map(|_| 4);
        let rows = rows_of("a", "hi :cat: :dog: x:cat:", 40, &m, &fw);
        let kinds: Vec<Kind> = rows[0].iter().map(|p| p.2).collect();
        assert_eq!(kinds, vec![Kind::Name, Kind::Words, Kind::Face, Kind::Words, Kind::Words]);
        // (the picture's 4 wide, then a space: the next word 5 on)
        assert_eq!(rows[0][3].0 - rows[0][2].0, 5);
        // a picture drawn into a box twice its size: each of its pixels twice across, twice down
        let mut n = 0;
        each_face_pixel(&faces.0["cat"], 4, 2, |_, _, rgb, k| { assert_eq!((rgb, k), ([255, 255, 255], 1.0)); n += 1; });
        assert_eq!(n, 8);
    }

    #[test]
    fn a_pixel_is_blended_as_strongly_as_it_is_covered() {
        let mut px = [0u8, 0, 0, 255];
        blend(&mut px, [255, 128, 0], 1.0);
        assert_eq!(px, [0, 128, 255, 255]);
        let mut px = [100u8, 100, 100, 255];
        blend(&mut px, [200, 200, 200], 0.5);
        assert_eq!(px, [150, 150, 150, 255]);
        blend(&mut px, [0, 0, 0], 0.0);
        assert_eq!(px, [150, 150, 150, 255]);
    }
}
