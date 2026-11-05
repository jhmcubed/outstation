//! The capture: frames of RuneLite's window (capture.py, rewritten; its parity lines 1-13).
//!
//! Nothing here is a RuneLite plugin. It reads the window's pixels the way streaming software does, and never
//! touches the game. Windows Graphics Capture first: Windows hands over the window's frames from the GPU as they're
//! drawn, whatever covers the window. The canvas is cropped out on the GPU, then averaged down to the frame's size
//! (a box filter, as OpenCV's INTER_AREA). Without it, PrintWindow and a HALFTONE StretchBlt; if that comes back black,
//! the screen itself.

use crate::clock;
use fast_image_resize as fr;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use windows::Win32::Foundation::HWND;
use windows::Win32::Graphics::Gdi::*;
use windows::Win32::Storage::Xps::{PrintWindow, PRINT_WINDOW_FLAGS};
use windows_capture::capture::{CaptureControl, Context, GraphicsCaptureApiHandler};
use windows_capture::frame::Frame as GpuFrame;
use windows_capture::graphics_capture_api::InternalCaptureControl;
use windows_capture::settings::{
    ColorFormat, CursorCaptureSettings, DirtyRegionSettings, DrawBorderSettings, MinimumUpdateIntervalSettings,
    SecondaryWindowSettings, Settings as GpuSettings,
};
use windows_capture::window::Window as GpuWindow;

pub const FIXED: (f64, f64) = (765.0, 503.0);
// fixed mode, in the game's own pixels
pub const FIXED_VIEWPORT: (f64, f64, f64, f64) = (4.0, 4.0, 512.0, 334.0);
const FIXED_CHAT: (f64, f64, f64, f64) = (0.0, 338.0, 519.0, 165.0);
const FIXED_SIDE: (f64, f64, f64, f64) = (540.0, 200.0, 210.0, 280.0);
// resizable: corners, in the game's pixels
const RESIZABLE_CHAT: (f64, f64) = (520.0, 190.0);
const RESIZABLE_SIDE: (f64, f64) = (250.0, 410.0);
/// every frame waits this long before it goes, and is dropped if the gate shuts meanwhile
pub const HOLD_BACK: f64 = 0.5;
pub const MAX_FPS: u32 = 60;

/// A frame: BGRA pixels, when it was taken, and how.
pub struct Image {
    pub bgra: Vec<u8>,
    pub w: u32,
    pub h: u32,
    pub taken: Instant,
    pub seq: Option<u64>,
}

impl Image {
    pub fn jpeg(&self, number: u32, quality: u8) -> Vec<u8> {
        let mut out = number.to_be_bytes().to_vec();
        let enc = jpeg_encoder::Encoder::new(&mut out, quality.clamp(1, 100));
        let _ = enc.encode(&self.bgra, self.w as u16, self.h as u16, jpeg_encoder::ColorType::Bgra);
        out
    }
    pub fn black(&self) -> bool {
        self.bgra.chunks_exact(4).all(|p| p[0] == 0 && p[1] == 0 && p[2] == 0)
    }
    fn fill(&mut self, x0: i64, y0: i64, x1: i64, y1: i64) {
        let (x0, y0) = (x0.max(0) as u32, y0.max(0) as u32);
        let (x1, y1) = ((x1.max(0) as u32).min(self.w), (y1.max(0) as u32).min(self.h));
        for y in y0..y1 {
            for x in x0..x1 {
                let i = ((y * self.w + x) * 4) as usize;
                self.bgra[i..i + 3].copy_from_slice(&[0, 0, 0]);
            }
        }
    }
    pub fn png(&self, path: &str) -> std::io::Result<()> {
        let f = std::fs::File::create(path)?;
        let mut e = png::Encoder::new(std::io::BufWriter::new(f), self.w, self.h);
        e.set_color(png::ColorType::Rgb);
        e.set_depth(png::BitDepth::Eight);
        let mut w = e.write_header().map_err(std::io::Error::other)?;
        let rgb: Vec<u8> = self.bgra.chunks_exact(4).flat_map(|p| [p[2], p[1], p[0]]).collect();
        w.write_image_data(&rgb).map_err(std::io::Error::other)
    }
    /// shrunk (a box filter) to w wide
    pub fn shrunk(&self, w: u32) -> Image {
        let w = w.clamp(1, self.w);
        let h = ((self.h as f64 * w as f64 / self.w as f64).round() as u32).max(1);
        Image { bgra: resize(&self.bgra, self.w, self.h, w, h), w, h, taken: self.taken, seq: self.seq }
    }
}

pub fn resize(src: &[u8], w: u32, h: u32, ow: u32, oh: u32) -> Vec<u8> {
    if (w, h) == (ow, oh) {
        return src.to_vec();
    }
    let from = fr::images::ImageRef::new(w, h, src, fr::PixelType::U8x4).expect("a frame's size");
    let mut to = fr::images::Image::new(ow, oh, fr::PixelType::U8x4);
    let _ = fr::Resizer::new().resize(&from, &mut to, &resize_options());
    to.into_vec()
}

/// An average of the pixels each frame pixel covers (a box filter), as OpenCV's INTER_AREA and Windows' HALFTONE.
fn resize_options() -> fr::ResizeOptions {
    fr::ResizeOptions::new().resize_alg(fr::ResizeAlg::Convolution(fr::FilterType::Box)).use_alpha(false)
}

// ---- PrintWindow, shrunk inside Windows ----

/// The window's own drawing (PrintWindow), shrunk to the frame's size inside Windows (StretchBlt, HALFTONE) before
/// it's copied out. The DCs and bitmaps are kept from frame to frame and made again when a size changes.
pub struct Grabber {
    key: Option<(isize, i32, i32, u32, u32)>,
    wdc: HDC,
    fdc: HDC,
    sdc: HDC,
    full: HBITMAP,
    small: HBITMAP,
    fold: HGDIOBJ,
    sold: HGDIOBJ,
    hwnd: HWND,
}

impl Grabber {
    pub fn new() -> Self {
        Grabber { key: None, wdc: HDC::default(), fdc: HDC::default(), sdc: HDC::default(), full: HBITMAP::default(),
                  small: HBITMAP::default(), fold: HGDIOBJ::default(), sold: HGDIOBJ::default(), hwnd: HWND::default() }
    }

    fn make(&mut self, hwnd: HWND, w: i32, h: i32, ow: u32, oh: u32) {
        self.close();
        unsafe {
            self.wdc = GetWindowDC(Some(hwnd));
            self.hwnd = hwnd;
            self.fdc = CreateCompatibleDC(Some(self.wdc));
            self.full = CreateCompatibleBitmap(self.wdc, w, h);
            self.fold = SelectObject(self.fdc, self.full.into());
            self.sdc = CreateCompatibleDC(Some(self.wdc));
            self.small = CreateCompatibleBitmap(self.wdc, ow as i32, oh as i32);
            self.sold = SelectObject(self.sdc, self.small.into());
            SetStretchBltMode(self.sdc, HALFTONE);
            let _ = SetBrushOrgEx(self.sdc, 0, 0, None);
        }
        self.key = Some((hwnd.0 as isize, w, h, ow, oh));
    }

    fn close(&mut self) {
        if self.key.is_none() {
            return;
        }
        unsafe {
            SelectObject(self.fdc, self.fold);
            SelectObject(self.sdc, self.sold);
            let _ = DeleteObject(self.full.into());
            let _ = DeleteObject(self.small.into());
            let _ = DeleteDC(self.fdc);
            let _ = DeleteDC(self.sdc);
            ReleaseDC(Some(self.hwnd), self.wdc);
        }
        self.key = None;
    }

    /// src: (x, y, w, h) in the window's own pixels; the frame is ow x oh.
    pub fn grab(&mut self, hwnd: HWND, src: (i32, i32, i32, i32), ow: u32, oh: u32) -> Image {
        let (l, t, r, b) = crate::win::rect(hwnd);
        let (w, h) = (r - l, b - t);
        if self.key != Some((hwnd.0 as isize, w, h, ow, oh)) {
            self.make(hwnd, w, h, ow, oh);
        }
        let taken = Instant::now();
        let mut buf = vec![0u8; (ow * oh * 4) as usize];
        unsafe {
            let _ = PrintWindow(hwnd, self.fdc, PRINT_WINDOW_FLAGS(2)); // PW_RENDERFULLCONTENT
            let _ = StretchBlt(self.sdc, 0, 0, ow as i32, oh as i32, Some(self.fdc), src.0, src.1, src.2, src.3, SRCCOPY);
            dib(self.sdc, self.small, ow, oh, &mut buf);
        }
        Image { bgra: buf, w: ow, h: oh, taken, seq: None }
    }
}

impl Drop for Grabber {
    fn drop(&mut self) {
        self.close();
    }
}

unsafe fn dib(dc: HDC, bmp: HBITMAP, w: u32, h: u32, buf: &mut [u8]) {
    let mut bmi = BITMAPINFO::default();
    bmi.bmiHeader = BITMAPINFOHEADER { biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32, biWidth: w as i32,
                                       biHeight: -(h as i32), biPlanes: 1, biBitCount: 32, ..Default::default() };
    unsafe {
        GetDIBits(dc, bmp, 0, h, Some(buf.as_mut_ptr() as *mut _), &mut bmi, DIB_RGB_COLORS);
    }
}

/// The screen itself (for a window whose own drawing comes back black), shrunk the same way.
fn from_screen(x: i32, y: i32, w: i32, h: i32, ow: u32, oh: u32) -> Image {
    let taken = Instant::now();
    let mut buf = vec![0u8; (ow * oh * 4) as usize];
    unsafe {
        let sdc = GetDC(None);
        let mdc = CreateCompatibleDC(Some(sdc));
        let bmp = CreateCompatibleBitmap(sdc, ow as i32, oh as i32);
        let old = SelectObject(mdc, bmp.into());
        SetStretchBltMode(mdc, HALFTONE);
        let _ = SetBrushOrgEx(mdc, 0, 0, None);
        let _ = StretchBlt(mdc, 0, 0, ow as i32, oh as i32, Some(sdc), x, y, w, h, SRCCOPY);
        dib(mdc, bmp, ow, oh, &mut buf);
        SelectObject(mdc, old);
        let _ = DeleteObject(bmp.into());
        let _ = DeleteDC(mdc);
        ReleaseDC(None, sdc);
    }
    Image { bgra: buf, w: ow, h: oh, taken, seq: None }
}

// ---- Windows Graphics Capture ----

/// (the window as drawn, in screen pixels; the canvas in screen pixels; the frame's size)
type Geom = ((i32, i32, i32, i32), (i32, i32, i32, i32), (u32, u32));

pub struct GpuShared {
    geom: Mutex<Option<Geom>>,
    every: Mutex<f64>,
    target: Mutex<Option<Instant>>,
    latest: Mutex<Option<Arc<Image>>>,
    seq: Mutex<u64>,
    pub cost: Mutex<(f64, u64)>,
}

pub struct Handler {
    shared: Arc<GpuShared>,
    scratch: Vec<u8>,
    resizer: fr::Resizer,
}

fn shrink_with(resizer: &mut fr::Resizer, src: &[u8], w: u32, h: u32, ow: u32, oh: u32) -> Vec<u8> {
    if (w, h) == (ow, oh) {
        return src.to_vec();
    }
    let from = fr::images::ImageRef::new(w, h, src, fr::PixelType::U8x4).expect("a frame's size");
    let mut to = fr::images::Image::new(ow, oh, fr::PixelType::U8x4);
    let _ = resizer.resize(&from, &mut to, &resize_options());
    to.into_vec()
}

impl GraphicsCaptureApiHandler for Handler {
    type Flags = Arc<GpuShared>;
    type Error = Box<dyn std::error::Error + Send + Sync>;

    fn new(ctx: Context<Self::Flags>) -> Result<Self, Self::Error> {
        Ok(Handler { shared: ctx.flags, scratch: Vec::new(), resizer: fr::Resizer::new() })
    }

    fn on_frame_arrived(&mut self, frame: &mut GpuFrame, _c: InternalCaptureControl) -> Result<(), Self::Error> {
        let Some(((bx, by, bw, bh), (sx, sy, mut sw, mut sh), (ow, oh))) = *self.shared.geom.lock().unwrap() else {
            return Ok(());
        };
        let arrived = Instant::now();
        // kept to a schedule, one every `every`, each the first at (or within a tenth of a frame before) its moment;
        // the rest go without being touched
        {
            let ev = *self.shared.every.lock().unwrap();
            let mut target = self.shared.target.lock().unwrap();
            if let Some(due) = *target {
                if arrived < due - Duration::from_secs_f64(ev * 0.1) {
                    return Ok(());
                }
                *target = Some(if arrived.saturating_duration_since(due).as_secs_f64() > ev { arrived } else { due }
                    + Duration::from_secs_f64(ev));
            } else {
                *target = Some(arrived + Duration::from_secs_f64(ev));
            }
        }
        let t0 = Instant::now();
        let (fw, fh) = (frame.width() as i32, frame.height() as i32);
        // the canvas within the frame: the frame is the window as drawn (bx, by its corner)
        let (mut x0, mut y0) = (sx - bx, sy - by);
        if fw != bw || fh != bh {
            let (kx, ky) = (fw as f64 / bw.max(1) as f64, fh as f64 / bh.max(1) as f64);
            x0 = (x0 as f64 * kx) as i32;
            y0 = (y0 as f64 * ky) as i32;
            sw = (sw as f64 * kx) as i32;
            sh = (sh as f64 * ky) as i32;
        }
        let (x1, y1) = ((x0 + sw).min(fw), (y0 + sh).min(fh));
        let (x0, y0) = (x0.max(0), y0.max(0));
        if x1 - x0 < 8 || y1 - y0 < 8 {
            return Ok(());
        }
        let ta = Instant::now();
        let buf = frame.buffer_crop(x0 as u32, y0 as u32, x1 as u32, y1 as u32)?;
        let (cw, ch) = (buf.width(), buf.height());
        let tb = Instant::now();
        let px = buf.as_nopadding_buffer(&mut self.scratch);
        let tc = Instant::now();
        let small = shrink_with(&mut self.resizer, px, cw, ch, ow, oh);
        if std::env::var_os("PORTAL_STAGES").is_some() {
            eprintln!("stages: crop {:.2} ms, copy {:.2} ms, shrink {:.2} ms", (tb - ta).as_secs_f64() * 1e3,
                      (tc - tb).as_secs_f64() * 1e3, tc.elapsed().as_secs_f64() * 1e3);
        }
        let mut seq = self.shared.seq.lock().unwrap();
        *seq += 1;
        *self.shared.latest.lock().unwrap() =
            Some(Arc::new(Image { bgra: small, w: ow, h: oh, taken: arrived, seq: Some(*seq) }));
        let mut c = self.shared.cost.lock().unwrap();
        c.0 += t0.elapsed().as_secs_f64() * 1000.0;
        c.1 += 1;
        Ok(())
    }
}

pub struct Gpu {
    control: Option<CaptureControl<Handler, Box<dyn std::error::Error + Send + Sync>>>,
    hwnd: Option<isize>,
    pub failed: Option<String>,
    pub shared: Arc<GpuShared>,
}

impl Gpu {
    pub fn new() -> Self {
        Gpu { control: None, hwnd: None, failed: None, shared: Arc::new(GpuShared {
            geom: Mutex::new(None), every: Mutex::new(0.125), target: Mutex::new(None), latest: Mutex::new(None),
            seq: Mutex::new(0), cost: Mutex::new((0.0, 0)) }) }
    }

    pub fn running(&self, hwnd: HWND) -> bool {
        self.failed.is_none() && self.hwnd == Some(hwnd.0 as isize) && self.control.as_ref().is_some_and(|c| !c.is_finished())
    }

    /// interval_ms: the time between the frames kept (the portal's frame rate)
    pub fn ensure(&mut self, hwnd: HWND, interval_ms: f64) -> bool {
        if self.failed.is_some() {
            return false;
        }
        *self.shared.every.lock().unwrap() = (interval_ms / 1000.0).max(0.004);
        if self.running(hwnd) {
            return true;
        }
        self.stop();
        // every frame Windows draws, so the one kept for each moment of the schedule is within a refresh of it
        let settings = GpuSettings::new(
            GpuWindow::from_raw_hwnd(hwnd.0),
            CursorCaptureSettings::WithoutCursor,
            DrawBorderSettings::WithoutBorder,
            SecondaryWindowSettings::Default,
            MinimumUpdateIntervalSettings::Custom(Duration::from_millis(1)),
            DirtyRegionSettings::Default,
            ColorFormat::Bgra8,
            self.shared.clone(),
        );
        match Handler::start_free_threaded(settings) {
            Ok(c) => {
                self.control = Some(c);
                self.hwnd = Some(hwnd.0 as isize);
                *self.shared.latest.lock().unwrap() = None;
                true
            }
            Err(e) => {
                self.failed = Some(format!("Windows Graphics Capture wouldn't start ({:?})", e));
                false
            }
        }
    }

    pub fn stop(&mut self) {
        if let Some(c) = self.control.take() {
            let _ = c.stop();
        }
        self.hwnd = None;
    }

    pub fn set_geom(&self, g: Geom) {
        *self.shared.geom.lock().unwrap() = Some(g);
    }

    pub fn newest(&self, max_age: f64) -> Option<Arc<Image>> {
        let got = self.shared.latest.lock().unwrap().clone()?;
        (got.taken.elapsed().as_secs_f64() <= max_age).then_some(got)
    }
}

impl Drop for Gpu {
    fn drop(&mut self) {
        self.stop();
    }
}

// ---- a frame ----

pub struct Options {
    pub view: String,
    pub mask: String,
    pub width: u32,
    pub method: String,
    pub interval_ms: f64,
    /// your own masks, while they're on: [x, y, w, h] as shares of the frame
    pub boxes: Vec<[f64; 4]>,
}

impl Options {
    pub fn of(s: &crate::settings::Settings) -> Options {
        Options { view: s.view.clone(), mask: s.mask.clone(), width: s.width.max(16),
                  method: s.method.clone(), interval_ms: 1000.0 / s.fps.clamp(1, MAX_FPS) as f64,
                  boxes: if s.masks_on { s.masks.clone() } else { Vec::new() } }
    }
}

pub struct Times {
    pub grab: f64,
    pub scale: f64,
    pub source: String,
}

/// One frame, scaled (and masked if asked): (image, what it is), or why not.
pub fn frame(hwnd: HWND, o: &Options, gpu: &mut Gpu, gdi: &mut Grabber, wait: f64, times: Option<&mut Times>)
             -> Result<(Image, String), String> {
    let t0 = Instant::now();
    let (l, t, r, b) = crate::win::rect(hwnd);
    if r - l <= 0 || b - t <= 0 {
        return Err("the window has no size".into());
    }
    let canvas = crate::win::find_canvas(hwnd);
    let (cx, cy, cw, ch) = match canvas {
        Some(c) => (c.0 - l, c.1 - t, c.2 - c.0, c.3 - c.1),
        None => (0, 0, r - l, b - t),
    };
    if cw <= 0 || ch <= 0 {
        return Err("the game canvas has no size".into());
    }
    // the canvas in the game's own pixels: fixed mode is 765 x 503 times some scale
    let k = cw as f64 / FIXED.0;
    let fixed = (ch as f64 - FIXED.1 * k).abs() <= 2.0 + 0.01 * ch as f64;
    let (mut sx, mut sy, mut sw, mut sh) = (cx, cy, cw, ch);
    if fixed && o.view == "viewport" {
        let (vx, vy, vw, vh) = FIXED_VIEWPORT;
        sx = cx + (vx * k) as i32;
        sy = cy + (vy * k) as i32;
        sw = (vw * k) as i32;
        sh = (vh * k) as i32;
    }
    let ow = o.width.min(sw as u32).max(1);
    let oh = ((sh as f64 * ow as f64 / sw as f64).round() as u32).max(1);
    let mut img: Option<Image> = None;
    let mut source = String::new();
    if o.method != "gdi" && gpu.ensure(hwnd, o.interval_ms) {
        let fb = crate::win::frame_bounds(hwnd);
        gpu.set_geom(((fb.0, fb.1, fb.2 - fb.0, fb.3 - fb.1), (l + sx, t + sy, sw, sh), (ow, oh)));
        let end = Instant::now() + Duration::from_secs_f64(wait);
        let mut got = gpu.newest(1.0);
        while got.is_none() && Instant::now() < end {
            std::thread::sleep(Duration::from_millis(10));
            got = gpu.newest(1.0);
        }
        if let Some(g) = got.filter(|g| g.w == ow && g.h == oh) {
            img = Some(Image { bgra: g.bgra.clone(), w: g.w, h: g.h, taken: g.taken, seq: g.seq });
            source = "Windows Graphics Capture".into();
        }
    }
    let mut img = match img {
        Some(i) => i,
        None => {
            source = format!("the window's own drawing{}", match (&o.method[..], &gpu.failed) {
                ("gdi", _) | (_, None) => String::new(),
                (_, Some(f)) => format!(" ({})", f),
            });
            gdi.grab(hwnd, (sx, sy, sw, sh), ow, oh)
        }
    };
    if canvas.is_some() && img.black() {
        // drew nothing through PrintWindow (some GPU paths): take it off the screen instead
        img = from_screen(l + sx, t + sy, sw, sh, ow, oh);
        source = "the screen (the window's own drawing came back black)".into();
    }
    let t1 = Instant::now();
    // the masks, in the frame's own pixels: s is frame pixels per game pixel
    let (chat, side) = (o.mask == "chat" || o.mask == "both", o.mask == "side" || o.mask == "both");
    let f = ow as f64 / sw as f64;
    let mut black = |x: f64, y: f64, w: f64, h: f64, s: f64| {
        img.fill((x * s) as i64 - 1, (y * s) as i64 - 1, ((x + w) * s) as i64 + 2, ((y + h) * s) as i64 + 2);
    };
    let mut layout = if fixed && o.view == "viewport" {
        "fixed, the 3D view only".to_string()
    } else if fixed {
        if chat { let (a, b, c, d) = FIXED_CHAT; black(a, b, c, d, k * f); }
        if side { let (a, b, c, d) = FIXED_SIDE; black(a, b, c, d, k * f); }
        "fixed".to_string()
    } else {
        let d = crate::win::dpi_scale(hwnd);
        let (gw, gh) = (cw as f64 / d, ch as f64 / d);
        if chat { black(0.0, gh - RESIZABLE_CHAT.1, RESIZABLE_CHAT.0, RESIZABLE_CHAT.1, d * f); }
        if side { black(gw - RESIZABLE_SIDE.0, gh - RESIZABLE_SIDE.1, RESIZABLE_SIDE.0, RESIZABLE_SIDE.1, d * f); }
        "resizable".to_string()
    };
    // your own masks: blacked out here, so nothing under them ever leaves this PC
    let (fw, fh) = (img.w as f64, img.h as f64);
    for m in &o.boxes {
        img.fill((m[0] * fw).floor() as i64, (m[1] * fh).floor() as i64, ((m[0] + m[2]) * fw).ceil() as i64, ((m[1] + m[3]) * fh).ceil() as i64);
    }
    if !o.boxes.is_empty() {
        layout += &format!(", {} of your own masks", o.boxes.len());
    }
    layout += &format!(", {}x{} canvas, {}", cw, ch,
                       if o.mask == "none" { "nothing blacked out".to_string() } else { format!("{} blacked out", o.mask) });
    if let Some(tm) = times {
        tm.grab += (t1 - t0).as_secs_f64() * 1000.0;
        tm.scale += t1.elapsed().as_secs_f64() * 1000.0;
        tm.source = source;
    }
    Ok((img, layout))
}

/// why nothing goes out while the static key is down (viewers see static, and the sound goes on)
pub const STATIC_WHY: &str = "static (the static key)";

/// None when a frame may go, else why not.
pub fn gate(hwnd: Option<HWND>, title: &str, s: &crate::settings::Settings, paused: bool) -> Option<String> {
    let Some(h) = hwnd else { return Some("no RuneLite window".into()) };
    if crate::win::minimised(h) {
        return Some("minimised".into());
    }
    if paused {
        return Some(STATIC_WHY.into());
    }
    if s.only_logged_in && !crate::win::logged_in(title, &s.title) {
        return Some("not logged in".into());
    }
    None
}

/// The frame's number: the moment it was taken, in ms on the portal's clock, wrapping.
pub fn number(img: &Image) -> u32 {
    clock::ms_at(img.taken)
}

// ---- --once and --bench ----

pub fn once(s: &crate::settings::Settings, path: &str) -> Result<String, String> {
    let (hwnd, title) = crate::win::find_window(&s.title).ok_or("nothing captured: no RuneLite window")?;
    if let Some(why) = gate(Some(hwnd), &title, s, false) {
        return Err(format!("nothing captured: {}", why));
    }
    let mut o = Options::of(s);
    o.interval_ms = 125.0;
    let (img, note) = frame(hwnd, &o, &mut Gpu::new(), &mut Grabber::new(), 1.5, None)?;
    img.png(path).map_err(|e| e.to_string())?;
    Ok(format!("{}: {}x{} ({}), {} KB as a frame", path, img.w, img.h, note, img.jpeg(0, s.quality).len() / 1024))
}

/// One frame at the game's full size, for the settings' sample.
pub fn sample(s: &crate::settings::Settings) -> Result<Image, String> {
    let (hwnd, _) = crate::win::find_window(&s.title).ok_or("no RuneLite window")?;
    if crate::win::minimised(hwnd) {
        return Err("RuneLite is minimised".into());
    }
    let mut o = Options::of(s);
    o.width = 4096;
    o.interval_ms = 125.0;
    frame(hwnd, &o, &mut Gpu::new(), &mut Grabber::new(), 1.5, None).map(|x| x.0)
}

pub fn bench(s: &crate::settings::Settings, n: usize) -> Result<(), String> {
    let (hwnd, _) = crate::win::find_window(&s.title).ok_or("no RuneLite window")?;
    let mut gpu = Gpu::new();
    let mut gdi = Grabber::new();
    let mut o = Options::of(s);
    if s.method != "gdi" {
        // let it run flat out (60 a second asked) for a few seconds: how fast frames come, and what each costs
        o.interval_ms = 1000.0 / 60.0;
        o.method = "gpu".into();
        let _ = frame(hwnd, &o, &mut gpu, &mut gdi, 2.0, None);
        *gpu.shared.cost.lock().unwrap() = (0.0, 0);
        let t0 = Instant::now();
        let mut tm = Times { grab: 0.0, scale: 0.0, source: String::new() };
        let (mut sizes, mut jpeg_ms, mut note, mut size) = (Vec::new(), 0.0, String::new(), (0, 0));
        while t0.elapsed().as_secs_f64() < 3.0 {
            let (img, nt) = frame(hwnd, &o, &mut gpu, &mut gdi, 0.0, Some(&mut tm)).map_err(|e| format!("nothing captured: {}", e))?;
            let t = Instant::now();
            sizes.push(img.jpeg(0, s.quality).len());
            jpeg_ms += t.elapsed().as_secs_f64() * 1000.0;
            note = nt;
            size = (img.w, img.h);
            std::thread::sleep(Duration::from_secs_f64(1.0 / 30.0));
        }
        let span = t0.elapsed().as_secs_f64();
        let (cb_ms, cb_n) = *gpu.shared.cost.lock().unwrap();
        gpu.stop();
        if tm.source == "Windows Graphics Capture" {
            let k = sizes.len() as f64;
            let main_ms = (tm.grab + tm.scale + jpeg_ms) / k;
            let each_cb = cb_ms / (cb_n.max(1) as f64);
            let kb = sizes.iter().sum::<usize>() as f64 / k / 1024.0;
            println!("{}", note);
            println!("from Windows Graphics Capture: {:.0} frames a second arrived (60 asked for)", cb_n as f64 / span);
            println!("a frame: crop and shrink {:.1} ms (its own thread) + {:.1} ms here, of it jpeg {:.1} ms", each_cb, main_ms, jpeg_ms / k);
            println!("{}x{}, {:.1} KB a frame at quality {}", size.0, size.1, kb, s.quality);
            for fps in [8, 12, 20, 30, 60] {
                println!("  at {:2} a second: {:5.0} KB/s up ({:.1} Mbit/s), {:3.0}% of one core", fps, kb * fps as f64,
                         kb * fps as f64 * 8.0 / 1024.0, (each_cb + main_ms) * fps as f64 / 10.0);
            }
            return Ok(());
        }
        println!("Windows Graphics Capture gave nothing ({}); timing PrintWindow instead",
                 gpu.failed.clone().unwrap_or_else(|| "no frames".into()));
    }
    o.method = "gdi".into();
    let mut tm = Times { grab: 0.0, scale: 0.0, source: String::new() };
    let (mut sizes, mut jpeg_ms, mut note, mut size) = (Vec::new(), 0.0, String::new(), (0, 0));
    for i in 0..n.max(1) {
        let (img, nt) = frame(hwnd, &o, &mut gpu, &mut gdi, 0.0, Some(&mut tm)).map_err(|e| format!("nothing captured: {}", e))?;
        let t = Instant::now();
        sizes.push(img.jpeg(i as u32, s.quality).len());
        jpeg_ms += t.elapsed().as_secs_f64() * 1000.0;
        note = nt;
        size = (img.w, img.h);
    }
    let k = sizes.len() as f64;
    let (g, sc, j) = (tm.grab / k, tm.scale / k, jpeg_ms / k);
    let total = g + sc + j;
    let kb = sizes.iter().sum::<usize>() as f64 / k / 1024.0;
    println!("{}", note);
    println!("from {}", tm.source);
    println!("a frame: grab and shrink {:.1} ms, masks {:.1} ms, jpeg {:.1} ms = {:.1} ms (one core could make {} a second)",
             g, sc, j, total, (1000.0 / total.max(0.1)) as u32);
    println!("{}x{}, {:.1} KB a frame at quality {}", size.0, size.1, kb, s.quality);
    for fps in [8, 12, 15, 20, 30] {
        println!("  at {:2} a second: {:4.0} KB/s up ({:.1} Mbit/s), {:3.0}% of one core", fps, kb * fps as f64,
                 kb * fps as f64 * 8.0 / 1024.0, total * fps as f64 / 10.0);
    }
    Ok(())
}
