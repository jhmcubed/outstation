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
/// The most one transmission's pictures may take on the shared relay (a wss:// one, Cloudflare's), in Mbit/s: the
/// relay sends every observer a copy, and about 500 Mbit/s out of one channel (30 observers) was measured to keep up
/// (11/06/2026), so 20 observers at this stay under it. Frames past it wait for the next (fewer a second), never a
/// worse picture. A relay of one's own has no ceiling here: its own upload is the limit.
pub const SHARED_CEILING_MBIT: f64 = 20.0;
/// small frames for lookers: this wide, this often
pub const THUMB_WIDTH: u32 = 192;
pub const THUMB_EVERY: f64 = 1.0; // (11/07/2026, from 0.5: one less message a second; the relay counts one fresh for 3 s)

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
    /// A small frame for lookers (the open portals' tiles, the listening post, the live view): 192 px wide, the
    /// same 4-byte stamp, then "TH", then the JPEG
    pub fn thumb(&self, number: u32) -> Vec<u8> {
        let j = self.shrunk(THUMB_WIDTH).jpeg(number, 55);
        let mut out = j[..4].to_vec();
        out.extend_from_slice(b"TH");
        out.extend_from_slice(&j[4..]);
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
    /// rung as each kept frame lands in `latest`, so the sending side wakes for it at once (11/07/2026: it looked
    /// every 4 ms, after the jpeg, so at 60 a second a frame that came while it slept or encoded was often replaced
    /// before it was sent: 50 a second where 60 was asked)
    fresh: std::sync::Condvar,
    seq: Mutex<u64>,
    pub cost: Mutex<(f64, u64)>,
}

pub struct Handler {
    shared: Arc<GpuShared>,
    scratch: Vec<u8>,
    resizer: fr::Resizer,
    /// the copy texture the canvas is read back through, kept while its size holds (11/07/2026: the capture library
    /// makes a new one, the whole canvas's size, for every frame)
    staging: Option<(windows::Win32::Graphics::Direct3D11::ID3D11Texture2D, u32, u32)>,
}

impl Handler {
    /// The canvas (x0, y0 to x1, y1 of the frame) read back through the kept copy texture and shrunk to ow x oh in the
    /// same step: the same pixels as the library's own buffer_crop and the shrink after it. None: anything unusual (a
    /// format other than BGRA, a call refused), and the library's way is used instead.
    fn read_shrunk(&mut self, frame: &GpuFrame, x0: u32, y0: u32, x1: u32, y1: u32, ow: u32, oh: u32) -> Option<Vec<u8>> {
        use windows::Win32::Graphics::Direct3D11::{D3D11_BOX, D3D11_CPU_ACCESS_READ, D3D11_MAPPED_SUBRESOURCE,
                                                    D3D11_MAP_READ, D3D11_TEXTURE2D_DESC, D3D11_USAGE_STAGING};
        use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};
        let (w, h) = (x1 - x0, y1 - y0);
        if frame.desc().Format != DXGI_FORMAT_B8G8R8A8_UNORM || w == 0 || h == 0 {
            return None;
        }
        if self.staging.as_ref().map_or(true, |s| (s.1, s.2) != (w, h)) {
            self.staging = None;
            let desc = D3D11_TEXTURE2D_DESC {
                Width: w, Height: h, MipLevels: 1, ArraySize: 1, Format: DXGI_FORMAT_B8G8R8A8_UNORM,
                SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 }, Usage: D3D11_USAGE_STAGING, BindFlags: 0,
                CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32, MiscFlags: 0,
            };
            let mut tex = None;
            unsafe { frame.device().CreateTexture2D(&desc, None, Some(&mut tex)) }.ok()?;
            self.staging = Some((tex?, w, h));
        }
        let tex = self.staging.as_ref()?.0.clone();
        let ctx = frame.device_context();
        let bx = D3D11_BOX { left: x0, top: y0, front: 0, right: x1, bottom: y1, back: 1 };
        let mut m = D3D11_MAPPED_SUBRESOURCE::default();
        unsafe {
            ctx.CopySubresourceRegion(&tex, 0, 0, 0, 0, frame.as_raw_texture(), 0, Some(&bx));
            if ctx.Map(&tex, 0, D3D11_MAP_READ, 0, Some(&mut m)).is_err() {
                self.staging = None;
                return None;
            }
        }
        let pitch = m.RowPitch;
        let out = if pitch % 4 == 0 && pitch >= w * 4 && !m.pData.is_null() {
            // (rows pitch bytes apart, as the library reads them too: its as_slice)
            let raw = unsafe { std::slice::from_raw_parts(m.pData as *const u8, pitch as usize * h as usize) };
            if (w, h) == (ow, oh) {
                let mut v = Vec::with_capacity((w * h * 4) as usize);
                for y in 0..h as usize { v.extend_from_slice(&raw[y * pitch as usize..y * pitch as usize + w as usize * 4]); }
                Some(v)
            } else {
                fr::images::ImageRef::new(pitch / 4, h, raw, fr::PixelType::U8x4).ok().and_then(|from| {
                    let mut to = fr::images::Image::new(ow, oh, fr::PixelType::U8x4);
                    self.resizer.resize(&from, &mut to, &resize_options().crop(0.0, 0.0, w as f64, h as f64)).ok()
                        .map(|_| to.into_vec())
                })
            }
        } else { None };
        unsafe { ctx.Unmap(&tex, 0); }
        out
    }
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
        Ok(Handler { shared: ctx.flags, scratch: Vec::new(), resizer: fr::Resizer::new(), staging: None })
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
        if let Some(small) = self.read_shrunk(frame, x0 as u32, y0 as u32, x1 as u32, y1 as u32, ow, oh) {
            if std::env::var_os("PORTAL_STAGES").is_some() {
                eprintln!("stages: read back and shrink {:.2} ms (the kept copy texture)", ta.elapsed().as_secs_f64() * 1e3);
            }
            self.keep(small, ow, oh, arrived, t0);
            return Ok(());
        }
        let mut buf = frame.buffer_crop(x0 as u32, y0 as u32, x1 as u32, y1 as u32)?;
        let (cw, ch, pitch) = (buf.width(), buf.height(), buf.row_pitch());
        let tb = Instant::now();
        // (11/07/2026) the copy as the graphics card laid it out, rows padded, shrunk straight from there with the
        // padding cropped off in the resizer: the same pixels out, byte for byte (checked on four sizes), without
        // first copying the whole canvas (about 14 MB a frame at 1440p) to take the padding out
        let direct = if buf.has_padding() && pitch % 4 == 0 && (cw, ch) != (ow, oh) {
            let raw = buf.as_raw_buffer();
            match fr::images::ImageRef::new(pitch / 4, ch, raw, fr::PixelType::U8x4) {
                Ok(from) => {
                    let mut to = fr::images::Image::new(ow, oh, fr::PixelType::U8x4);
                    self.resizer.resize(&from, &mut to, &resize_options().crop(0.0, 0.0, cw as f64, ch as f64))
                        .ok().map(|_| to.into_vec())
                }
                Err(_) => None,
            }
        } else { None };
        let tc = Instant::now();
        let small = match direct {
            Some(s) => s,
            None => {
                let px = buf.as_nopadding_buffer(&mut self.scratch);
                shrink_with(&mut self.resizer, px, cw, ch, ow, oh)
            }
        };
        if std::env::var_os("PORTAL_STAGES").is_some() {
            eprintln!("stages: crop {:.2} ms, copy {:.2} ms, shrink {:.2} ms", (tb - ta).as_secs_f64() * 1e3,
                      (tc - tb).as_secs_f64() * 1e3, tc.elapsed().as_secs_f64() * 1e3);
        }
        self.keep(small, ow, oh, arrived, t0);
        Ok(())
    }
}

impl Handler {
    /// A kept frame into `latest`, the sending side woken for it, its cost counted.
    fn keep(&mut self, small: Vec<u8>, ow: u32, oh: u32, arrived: Instant, t0: Instant) {
        let mut seq = self.shared.seq.lock().unwrap();
        *seq += 1;
        *self.shared.latest.lock().unwrap() =
            Some(Arc::new(Image { bgra: small, w: ow, h: oh, taken: arrived, seq: Some(*seq) }));
        self.shared.fresh.notify_all();
        let mut c = self.shared.cost.lock().unwrap();
        c.0 += t0.elapsed().as_secs_f64() * 1000.0;
        c.1 += 1;
    }
}

pub struct Gpu {
    control: Option<CaptureControl<Handler, Box<dyn std::error::Error + Send + Sync>>>,
    hwnd: Option<isize>,
    /// whether the running capture draws the cursor (show_cursor): a change starts it again
    cursor: bool,
    pub failed: Option<String>,
    pub shared: Arc<GpuShared>,
}

impl Gpu {
    pub fn new() -> Self {
        Gpu { control: None, hwnd: None, cursor: false, failed: None, shared: Arc::new(GpuShared {
            geom: Mutex::new(None), every: Mutex::new(0.125), target: Mutex::new(None), latest: Mutex::new(None),
            fresh: std::sync::Condvar::new(), seq: Mutex::new(0), cost: Mutex::new((0.0, 0)) }) }
    }

    pub fn running(&self, hwnd: HWND) -> bool {
        self.failed.is_none() && self.hwnd == Some(hwnd.0 as isize) && self.control.as_ref().is_some_and(|c| !c.is_finished())
    }

    /// interval_ms: the time between the frames kept (the portal's frame rate); cursor: whether Windows draws the
    /// mouse pointer into the frames (11/06/2026, Jace: "a toggle to show or hide cursor"). Windows Graphics Capture
    /// draws it itself, where it is over RuneLite: the portal never reads where the pointer is.
    pub fn ensure(&mut self, hwnd: HWND, interval_ms: f64, cursor: bool) -> bool {
        if self.failed.is_some() {
            return false;
        }
        *self.shared.every.lock().unwrap() = (interval_ms / 1000.0).max(0.004);
        if self.running(hwnd) && self.cursor == cursor {
            return true;
        }
        self.stop();
        // every frame Windows draws, so the one kept for each moment of the schedule is within a refresh of it
        let settings = GpuSettings::new(
            GpuWindow::from_raw_hwnd(hwnd.0),
            if cursor { CursorCaptureSettings::WithCursor } else { CursorCaptureSettings::WithoutCursor },
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
                self.cursor = cursor;
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

    /// Wait (at most `most`) for a frame newer than `seen`: back as soon as one lands.
    pub fn wait_newer(&self, seen: Option<u64>, most: Duration) {
        let end = Instant::now() + most;
        let mut g = self.shared.latest.lock().unwrap();
        loop {
            if g.as_ref().is_some_and(|i| i.seq != seen) {
                return;
            }
            let left = end.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return;
            }
            g = self.shared.fresh.wait_timeout(g, left).unwrap().0;
        }
    }

    /// The newest frame, taken out (the next look waits for a new one): handed over whole when nothing else holds
    /// it, so a 60-a-second transmission isn't copying every picture (11/07/2026: about 1.3 MB a frame at 768 px).
    pub fn take_newest(&self, max_age: f64) -> Option<Image> {
        let got = self.shared.latest.lock().unwrap().take()?;
        if got.taken.elapsed().as_secs_f64() > max_age {
            return None;
        }
        Some(Arc::try_unwrap(got).unwrap_or_else(|a| Image { bgra: a.bgra.clone(), w: a.w, h: a.h, taken: a.taken, seq: a.seq }))
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
    /// the mouse pointer in the picture (Windows Graphics Capture only: PrintWindow and the screen never have it)
    pub cursor: bool,
    /// the chat over the game drawn into the picture (chatpic.rs; 11/07/2026), and whether it's over the game on
    /// this PC too (then a frame taken off the screen has it already)
    pub chat: bool,
    pub chat_over: bool,
}

impl Options {
    pub fn of(s: &crate::settings::Settings) -> Options {
        // (the fixed-size "blacked out" boxes are retired, 11/05/2026, Jace: a fixed size whatever the client's; your
        // own masks do it. An old settings file's "mask" is kept as it was but no longer used.)
        Options { view: s.view.clone(), mask: "none".into(), width: s.width.max(16),
                  method: s.method.clone(), interval_ms: 1000.0 / s.fps.clamp(1, MAX_FPS) as f64,
                  boxes: if s.masks_on { s.masks.clone() } else { Vec::new() }, cursor: s.show_cursor,
                  chat: s.chat_in_picture, chat_over: s.game_chat }
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
    if o.method != "gdi" && gpu.ensure(hwnd, o.interval_ms, o.cursor) {
        let fb = crate::win::frame_bounds(hwnd);
        gpu.set_geom(((fb.0, fb.1, fb.2 - fb.0, fb.3 - fb.1), (l + sx, t + sy, sw, sh), (ow, oh)));
        let end = Instant::now() + Duration::from_secs_f64(wait);
        // (the transmission, which never waits, takes each frame; a sample or the bench, which wait, look at a copy and
        // leave it for the transmission)
        let look = |gpu: &Gpu| if wait == 0.0 { gpu.take_newest(1.0) } else {
            gpu.newest(1.0).map(|a| Image { bgra: a.bgra.clone(), w: a.w, h: a.h, taken: a.taken, seq: a.seq })
        };
        let mut got = look(gpu);
        while got.is_none() && Instant::now() < end {
            gpu.wait_newer(None, Duration::from_millis(10));
            got = look(gpu);
        }
        if let Some(g) = got.filter(|g| g.w == ow && g.h == oh) {
            img = Some(g);
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
    // the chat over the game, into the picture (over the redactions), unless a frame off the screen has it already
    if o.chat && !(o.chat_over && source.starts_with("the screen")) {
        crate::chatpic::onto(&mut img);
        layout += ", the chat in it";
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
