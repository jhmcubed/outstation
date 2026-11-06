//! The replay buffer and its clips (clip.py and the relay's replay buffer, rewritten; main's round two, 11/05/2026,
//! Jace: "add a scalable replay buffer in the settings, 0 to 30").
//!
//! The Python portal's relay kept the last seconds and wrote the clip; a relay on Cloudflare can't write a file to
//! anyone's PC, so here the client keeps them itself: the last `replay_seconds` (0 to 30; 0 keeps nothing) of exactly
//! what it sent, the frames (4 bytes when each was taken, then a JPEG) and the sound (the same clock, "AU", the bus,
//! Opus packets). The clip key, or "clip" in the window, writes them as an ordinary .mp4 with Windows' own encoders
//! (Media Foundation): H.264, the frames at their own times, and AAC, both sound busses mixed into one stereo track,
//! lined up with the frames by their stamps. Into `clip_folder` (Videos\outside-portal by default), named for when.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

const RATE: usize = 48000;

struct Buffer {
    items: Mutex<VecDeque<(Instant, Vec<u8>)>>,
    seconds: AtomicU32,
    writing: AtomicBool,
    note: Mutex<Option<String>>,
}

fn buffer() -> &'static Buffer {
    static B: OnceLock<Buffer> = OnceLock::new();
    B.get_or_init(|| Buffer { items: Mutex::new(VecDeque::new()), seconds: AtomicU32::new(0), writing: AtomicBool::new(false),
                              note: Mutex::new(None) })
}

/// How many seconds to keep (the settings' replay_seconds), set whenever the settings are read
pub fn set_seconds(s: u32) {
    let b = buffer();
    b.seconds.store(s.min(30), Ordering::Relaxed);
    if s == 0 {
        b.items.lock().unwrap().clear();
    }
}

/// What was just sent (a frame or a sound message), kept while the buffer is on
pub fn keep(msg: &[u8]) {
    let b = buffer();
    let secs = b.seconds.load(Ordering::Relaxed);
    if secs == 0 {
        return;
    }
    let now = Instant::now();
    let mut items = b.items.lock().unwrap();
    items.push_back((now, msg.to_vec()));
    while items.front().is_some_and(|f| now.duration_since(f.0).as_secs_f64() > secs as f64) {
        items.pop_front();
    }
}

/// The last thing worth saying about a clip (taken once)
pub fn take_note() -> Option<String> {
    buffer().note.lock().unwrap().take()
}

/// What's in the buffer now (for the page's copy: clipsup.rs).
pub fn snapshot() -> Vec<(Instant, Vec<u8>)> {
    buffer().items.lock().unwrap().iter().cloned().collect()
}

pub fn say(s: String) {
    crate::transmit::log(&s);
    *buffer().note.lock().unwrap() = Some(s);
}

pub fn default_folder() -> std::path::PathBuf {
    let home = std::env::var_os("USERPROFILE").map(std::path::PathBuf::from).unwrap_or_else(|| ".".into());
    home.join("Videos").join("outside-portal")
}

/// The clip key or "clip": the buffer saved as a clip, on a thread of its own.
pub fn save(folder: &str) {
    save_as(folder, None)
}

/// The buffer saved as a clip, named for what it caught (an auto clip: "clip <when> pet vorki.mp4"); None: "clip <when>.mp4".
pub fn save_as(folder: &str, what: Option<String>) {
    let b = buffer();
    if b.seconds.load(Ordering::Relaxed) == 0 {
        return say("no clip: the replay buffer is off (its seconds in the settings)".into());
    }
    if b.writing.swap(true, Ordering::SeqCst) {
        return say("no clip: one is still being written".into());
    }
    let items: Vec<(Instant, Vec<u8>)> = b.items.lock().unwrap().iter().cloned().collect();
    let folder = if folder.trim().is_empty() { default_folder() } else { folder.into() };
    let span = match (items.first(), items.last()) { (Some(a), Some(z)) => z.0.duration_since(a.0).as_secs_f64(), _ => 0.0 };
    say(format!("writing a clip of the last {:.1} s{}…", span, what.as_ref().map(|w| format!(" ({})", w)).unwrap_or_default()));
    let name = what.map(|w| format!("clip {} {}.mp4", stamp_now(), file_safe(&w)));
    std::thread::spawn(move || {
        let r = write(&items, &folder, name.as_deref());
        buffer().writing.store(false, Ordering::SeqCst);
        match r {
            Ok(p) => say(format!("clip saved: {}", p.display())),
            Err(e) => say(format!("no clip: {}", e)),
        }
    });
}

/// what a clip caught, as part of a file's name: letters, numbers, spaces and a few marks, at most 40
pub fn file_safe(s: &str) -> String {
    let t: String = s.chars().map(|c| if c.is_alphanumeric() || matches!(c, ' ' | '-' | '_' | '.' | '\'' | '(' | ')') { c } else { ' ' }).collect();
    let t = t.split_whitespace().collect::<Vec<_>>().join(" ");
    t.trim_matches('.').chars().take(40).collect::<String>().trim().to_string()
}

/// a stamp in ms from base, on the 32-bit wrapping clock both the frames and the sound use
fn rel(stamp: u32, base: u32) -> i64 {
    let d = stamp.wrapping_sub(base) as i64;
    if d >= 1 << 31 { d - (1 << 32) } else { d }
}

/// The kept messages as an .mp4 in folder. Its path.
pub fn write(items: &[(Instant, Vec<u8>)], folder: &std::path::Path, name: Option<&str>) -> Result<std::path::PathBuf, String> {
    std::fs::create_dir_all(folder).map_err(|e| e.to_string())?;
    let file = match name {
        Some(n) => folder.join(n),
        None => folder.join(format!("clip {}.mp4", stamp_now())),
    };
    write_to(items, &file, None, None)?;
    Ok(file)
}

/// The kept messages as an .mp4 at `file`: at most `max_w` wide (scaled down, never up), at `bitrate` (bits a second:
/// None, as the clip key's). Its size and length (ms).
pub fn write_to(items: &[(Instant, Vec<u8>)], file: &std::path::Path, max_w: Option<u32>, bitrate: Option<u32>) -> Result<(u32, u32, i64), String> {
    let mut frames: Vec<(u32, &[u8])> = Vec::new();
    let mut sounds: Vec<(u32, &[u8])> = Vec::new();
    for (_, m) in items {
        if m.len() < 8 {
            continue;
        }
        let stamp = u32::from_be_bytes([m[0], m[1], m[2], m[3]]);
        if &m[4..6] == b"AU" { sounds.push((stamp, m)); } else if m[4] == 0xFF && m[5] == 0xD8 { frames.push((stamp, &m[4..])); }
    }
    if frames.is_empty() {
        return Err("no frames in the replay buffer (is it on, and is the portal live?)".into());
    }
    let base = frames[0].0;
    let mut pics: Vec<(i64, &[u8])> = frames.iter().map(|(s, j)| (rel(*s, base), *j)).collect();
    pics.sort_by_key(|p| p.0);
    let t_end = pics.last().unwrap().0 + 40;

    let t0 = Instant::now();
    // the picture: the frames' times (each decoded only as it's written, never all at once), never two at one time
    let first = decode(pics[0].1).ok_or("the first frame wouldn't decode")?;
    let (fw0, fh0) = match max_w { Some(m) if first.1 > m => (m, ((first.2 as f64) * m as f64 / first.1 as f64).round() as u32), _ => (first.1, first.2) };
    let (w, h) = (fw0 / 2 * 2, fh0 / 2 * 2); // (H.264 wants even sizes)
    let mut video: Vec<(i64, &[u8])> = Vec::new();
    let mut last = -1;
    for (t, jpeg) in &pics {
        if *t <= last { continue; }
        last = *t;
        video.push((*t, *jpeg));
    }

    let t1 = Instant::now();
    // the sound: every bus's packets decoded and laid on one timeline by their stamps, mixed
    let n = ((t_end.max(0) as usize) * RATE / 1000).max(1);
    let mut mix = vec![0i32; n * 2];
    let mut decoders: std::collections::HashMap<u8, opus::Decoder> = std::collections::HashMap::new();
    sounds.sort_by_key(|s| rel(s.0, base));
    let mut pcm = vec![0i16; 5760 * 2];
    for (stamp, m) in &sounds {
        let (bus, count) = (m[6], m[7] as usize);
        let dec = match decoders.entry(bus) {
            std::collections::hash_map::Entry::Occupied(e) => e.into_mut(),
            std::collections::hash_map::Entry::Vacant(e) => e.insert(opus::Decoder::new(RATE as u32, opus::Channels::Stereo).map_err(|e| e.to_string())?),
        };
        let t0 = rel(*stamp, base);
        let mut at = 8;
        for i in 0..count {
            if at + 2 > m.len() { break; }
            let ln = u16::from_be_bytes([m[at], m[at + 1]]) as usize;
            if at + 2 + ln > m.len() { break; }
            let packet = &m[at + 2..at + 2 + ln];
            at += 2 + ln;
            let Ok(got) = dec.decode(packet, &mut pcm, false) else { continue };
            let start = (t0 + i as i64 * 20) * RATE as i64 / 1000;
            for k in 0..got {
                let j = start + k as i64;
                if j >= 0 && (j as usize) < n {
                    mix[j as usize * 2] += pcm[k * 2] as i32;
                    mix[j as usize * 2 + 1] += pcm[k * 2 + 1] as i32;
                }
            }
        }
    }
    let audio: Vec<i16> = mix.into_iter().map(|x| x.clamp(-32768, 32767) as i16).collect();
    let t2 = Instant::now();

    mp4(file, w, h, &video, &audio, t_end, bitrate).map_err(|e| format!("Windows' encoder refused it ({})", e))?;
    crate::transmit::log(&format!("clip: {} frames {}x{}, {:.1} s of sound mixed in {:.1} s, written in {:.1} s ({:.1} s in all)",
                                  video.len(), w, h, t_end as f64 / 1000.0, (t2 - t1).as_secs_f64(),
                                  t2.elapsed().as_secs_f64(), t0.elapsed().as_secs_f64()));
    Ok((w, h, t_end))
}

/// What a clip on the player's Outside page is made of (relay/src/clips.ts): the standard copy, the still and the
/// flipbook.
pub struct PageCopy {
    pub mp4: Vec<u8>,
    /// the moment, small (STILL_W wide), a JPEG
    pub still: Vec<u8>,
    /// the flipbook: `frames` small frames (the still's size) one under another in one JPEG, `fps` a second
    pub strip: Vec<u8>,
    pub frames: u32,
    pub fps: f64,
    pub dur: f64,
    pub w: u32,
    pub h: u32,
}

/// the standard copy: from BEFORE ms before the moment to AFTER ms after it, at most PAGE_W wide, at PAGE_BITRATE
pub const BEFORE: u64 = 12_000;
pub const AFTER: u64 = 5_000;
pub const PAGE_W: u32 = 768;
const PAGE_BITRATE: u32 = 1_500_000;
const PAGE_MOST: usize = 8 * 1024 * 1024;
/// the still and the flipbook's frames: this wide; the flipbook, the moment's few seconds, at most this many frames
const STILL_W: u32 = 160;
const STRIP_BEFORE: u64 = 1_500;
const STRIP_AFTER: u64 = 1_000;
const STRIP_MOST: usize = 20;

/// The buffer's frames (when each was sent, its JPEG), in order.
fn frames_of(items: &[(Instant, Vec<u8>)]) -> Vec<(Instant, &[u8])> {
    items.iter().filter(|(_, m)| m.len() > 8 && m[4] == 0xFF && m[5] == 0xD8).map(|(t, m)| (*t, &m[4..])).collect()
}

/// A frame made small (STILL_W wide) as RGB, its size.
fn small(jpeg: &[u8]) -> Option<(Vec<u8>, u32, u32)> {
    let (rgb, w, h) = decode(jpeg)?;
    let sw = STILL_W.min(w);
    let sh = (((h as f64) * sw as f64 / w as f64).round() as u32).max(2) / 2 * 2;
    let rgba: Vec<u8> = rgb.chunks_exact(3).flat_map(|p| [p[0], p[1], p[2], 255]).collect();
    let out = crate::capture::resize(&rgba, w, h, sw, sh);
    Some((out.chunks_exact(4).flat_map(|p| [p[0], p[1], p[2]]).collect(), sw, sh))
}

fn rgb_jpeg(rgb: &[u8], w: u32, h: u32, quality: u8) -> Vec<u8> {
    let mut out = Vec::new();
    let _ = jpeg_encoder::Encoder::new(&mut out, quality).encode(rgb, w as u16, h as u16, jpeg_encoder::ColorType::Rgb);
    out
}

/// The still and the flipbook from the frames round `moment`: (still, strip, its frames, their rate).
pub fn still_and_strip(items: &[(Instant, Vec<u8>)], moment: Instant) -> Result<(Vec<u8>, Vec<u8>, u32, f64), String> {
    use std::time::Duration;
    let frames = frames_of(items);
    if frames.is_empty() {
        return Err("no frames round the moment (was the portal live?)".into());
    }
    let near = |at: Instant| frames.iter().min_by_key(|(t, _)| if *t > at { *t - at } else { at - *t }).unwrap().1;
    let (srgb, sw, sh) = small(near(moment)).ok_or("the moment's frame wouldn't decode")?;
    let still = rgb_jpeg(&srgb, sw, sh, 70);
    let s_from = moment.checked_sub(Duration::from_millis(STRIP_BEFORE)).unwrap_or(moment);
    let s_to = moment + Duration::from_millis(STRIP_AFTER);
    let mut pick: Vec<(Instant, &[u8])> = frames.iter().filter(|(t, _)| *t >= s_from && *t <= s_to).cloned().collect();
    if pick.is_empty() { pick.push((moment, near(moment))); }
    if pick.len() > STRIP_MOST {
        let n = pick.len();
        pick = (0..STRIP_MOST).map(|i| pick[i * n / STRIP_MOST]).collect();
    }
    let mut strip_rgb = Vec::new();
    let mut n = 0u32;
    for (_, j) in &pick {
        if let Some((rgb, w, h)) = small(j) {
            if (w, h) == (sw, sh) { strip_rgb.extend_from_slice(&rgb); n += 1; }
        }
    }
    let span = pick.last().unwrap().0.duration_since(pick[0].0).as_secs_f64();
    let fps = if n > 1 && span > 0.0 { ((n - 1) as f64 / span).clamp(1.0, 30.0) } else { 4.0 };
    let strip = rgb_jpeg(&strip_rgb, sw, sh * n.max(1), 60);
    Ok((still, strip, n.max(1), (fps * 10.0).round() / 10.0))
}

/// The page's copy of what the buffer holds round `moment` (when Dink told of it), its still and its flipbook.
pub fn page_copy(items: &[(Instant, Vec<u8>)], moment: Instant) -> Result<PageCopy, String> {
    use std::time::Duration;
    let from = moment.checked_sub(Duration::from_millis(BEFORE)).unwrap_or(moment);
    let to = moment + Duration::from_millis(AFTER);
    let window: Vec<(Instant, Vec<u8>)> = items.iter().filter(|(t, _)| *t >= from && *t <= to).cloned().collect();
    let (still, strip, frames, fps) = still_and_strip(&window, moment)?;
    // the copy itself, through a file of its own in the temporary folder
    let tmp = std::env::temp_dir().join(format!("outside-portal-{}-{}.mp4", std::process::id(),
                                                 std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0)));
    let made = write_to(&window, &tmp, Some(PAGE_W), Some(PAGE_BITRATE));
    let bytes = std::fs::read(&tmp);
    let _ = std::fs::remove_file(&tmp);
    let (w, h, ms) = made?;
    let mp4 = bytes.map_err(|e| e.to_string())?;
    if mp4.len() > PAGE_MOST {
        return Err(format!("the page's copy came out at {:.1} MB, over the relay's 8", mp4.len() as f64 / 1048576.0));
    }
    Ok(PageCopy { mp4, still, strip, frames, fps, dur: (ms as f64 / 100.0).round() / 10.0, w, h })
}


/// A frame as H.264 wants it in: NV12 (a full-size Y plane, then U and V together at half size; BT.601, limited
/// range), w and h even. Converting here keeps Windows from slotting in a slow converter of its own.
fn nv12(rgb: &[u8], fw: u32, w: u32, h: u32) -> Vec<u8> {
    let (w, h, fw) = (w as usize, h as usize, fw as usize);
    let mut out = vec![0u8; w * h * 3 / 2];
    let (yp, uv) = out.split_at_mut(w * h);
    for y in 0..h {
        let row = &rgb[y * fw * 3..];
        for x in 0..w {
            let (r, g, b) = (row[x * 3] as i32, row[x * 3 + 1] as i32, row[x * 3 + 2] as i32);
            yp[y * w + x] = (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16).clamp(0, 255) as u8;
        }
    }
    for y in (0..h).step_by(2) {
        for x in (0..w).step_by(2) {
            let (mut r, mut g, mut b) = (0i32, 0i32, 0i32);
            for (dx, dy) in [(0, 0), (1, 0), (0, 1), (1, 1)] {
                let i = ((y + dy) * fw + x + dx) * 3;
                r += rgb[i] as i32;
                g += rgb[i + 1] as i32;
                b += rgb[i + 2] as i32;
            }
            let (r, g, b) = (r / 4, g / 4, b / 4);
            let j = (y / 2) * w + x;
            uv[j] = (((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
            uv[j + 1] = (((112 * r - 94 * g - 18 * b + 128) >> 8) + 128).clamp(0, 255) as u8;
        }
    }
    out
}

fn decode(jpeg: &[u8]) -> Option<(Vec<u8>, u32, u32)> {
    let mut d = jpeg_decoder::Decoder::new(jpeg);
    let px = d.decode().ok()?;
    let info = d.info()?;
    if info.pixel_format != jpeg_decoder::PixelFormat::RGB24 {
        return None;
    }
    Some((px, info.width as u32, info.height as u32))
}

fn stamp_now() -> String {
    use windows::Win32::System::SystemInformation::GetLocalTime;
    let t = unsafe { GetLocalTime() };
    format!("{:04}-{:02}-{:02} {:02}.{:02}.{:02}", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond)
}

/// H.264 and AAC into an .mp4, through Media Foundation's sink writer.
fn mp4(path: &std::path::Path, w: u32, h: u32, video: &[(i64, &[u8])], audio: &[i16], t_end_ms: i64, bitrate: Option<u32>) -> windows::core::Result<()> {
    use windows::core::HSTRING;
    use windows::Win32::Media::MediaFoundation::*;
    use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED};
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        MFStartup(0x0002_0070, MFSTARTUP_FULL)?;
        let result = (|| -> windows::core::Result<()> {
            let _ = std::fs::remove_file(path);
            // (the graphics card's encoder where there is one; and no waiting on the writer's own pacing)
            let mut attrs: Option<IMFAttributes> = None;
            MFCreateAttributes(&mut attrs, 2)?;
            let attrs = attrs.unwrap();
            attrs.SetUINT32(&MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, 1)?;
            attrs.SetUINT32(&MF_SINK_WRITER_DISABLE_THROTTLING, 1)?;
            let writer = MFCreateSinkWriterFromURL(&HSTRING::from(path.as_os_str()), None, &attrs)?;
            let size = ((w as u64) << 32) | h as u64;
            let ratio = |a: u32, b: u32| ((a as u64) << 32) | b as u64;

            let vo = MFCreateMediaType()?;
            vo.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            vo.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
            vo.SetUINT32(&MF_MT_AVG_BITRATE, bitrate.unwrap_or(((w * h) as f64 * 30.0 * 0.15).clamp(1_000_000.0, 20_000_000.0) as u32))?;
            vo.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            vo.SetUINT64(&MF_MT_FRAME_SIZE, size)?;
            vo.SetUINT64(&MF_MT_FRAME_RATE, ratio(30, 1))?;
            vo.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, ratio(1, 1))?;
            let vs = writer.AddStream(&vo)?;
            let vi = MFCreateMediaType()?;
            vi.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
            vi.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
            vi.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
            vi.SetUINT64(&MF_MT_FRAME_SIZE, size)?;
            vi.SetUINT64(&MF_MT_FRAME_RATE, ratio(30, 1))?;
            vi.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, ratio(1, 1))?;
            vi.SetUINT32(&MF_MT_DEFAULT_STRIDE, w)?;
            writer.SetInputMediaType(vs, &vi, None)?;

            let ao = MFCreateMediaType()?;
            ao.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Audio)?;
            ao.SetGUID(&MF_MT_SUBTYPE, &MFAudioFormat_AAC)?;
            ao.SetUINT32(&MF_MT_AUDIO_BITS_PER_SAMPLE, 16)?;
            ao.SetUINT32(&MF_MT_AUDIO_SAMPLES_PER_SECOND, RATE as u32)?;
            ao.SetUINT32(&MF_MT_AUDIO_NUM_CHANNELS, 2)?;
            ao.SetUINT32(&MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 24000)?;
            let aus = writer.AddStream(&ao)?;
            let ai = MFCreateMediaType()?;
            ai.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Audio)?;
            ai.SetGUID(&MF_MT_SUBTYPE, &MFAudioFormat_PCM)?;
            ai.SetUINT32(&MF_MT_AUDIO_BITS_PER_SAMPLE, 16)?;
            ai.SetUINT32(&MF_MT_AUDIO_SAMPLES_PER_SECOND, RATE as u32)?;
            ai.SetUINT32(&MF_MT_AUDIO_NUM_CHANNELS, 2)?;
            ai.SetUINT32(&MF_MT_AUDIO_BLOCK_ALIGNMENT, 4)?;
            ai.SetUINT32(&MF_MT_AUDIO_AVG_BYTES_PER_SECOND, (RATE * 4) as u32)?;
            writer.SetInputMediaType(aus, &ai, None)?;
            writer.BeginWriting()?;

            let sample = |bytes: &[u8], t100: i64, d100: i64| -> windows::core::Result<IMFSample> {
                let buf = MFCreateMemoryBuffer(bytes.len() as u32)?;
                let mut p: *mut u8 = std::ptr::null_mut();
                buf.Lock(&mut p, None, None)?;
                std::ptr::copy_nonoverlapping(bytes.as_ptr(), p, bytes.len());
                buf.Unlock()?;
                buf.SetCurrentLength(bytes.len() as u32)?;
                let s = MFCreateSample()?;
                s.AddBuffer(&buf)?;
                s.SetSampleTime(t100)?;
                s.SetSampleDuration(d100)?;
                Ok(s)
            };
            // the picture and the sound written together, in time order: an .mp4 interleaves them, and given all the
            // picture first the writer would hold it all back waiting for the sound (the slow clip, 11/05/2026)
            let step = 1024usize;
            let bytes: &[u8] = std::slice::from_raw_parts(audio.as_ptr() as *const u8, audio.len() * 2);
            let frames = audio.len() / 2;
            let (mut vi_at, mut k) = (0usize, 0usize);
            while vi_at < video.len() || k < frames {
                let vt = video.get(vi_at).map(|v| v.0 * 10_000);
                let at = (k as i64) * 10_000_000 / RATE as i64;
                if vt.is_some_and(|vt| k >= frames || vt <= at) {
                    let (t, jpeg) = video[vi_at];
                    let next = video.get(vi_at + 1).map(|v| v.0).unwrap_or(t_end_ms);
                    vi_at += 1;
                    let Some((rgb, fw, fh)) = decode(jpeg) else { continue };
                    // (as it is when it's the size, or a pixel over: H.264's even sizes; else scaled to it)
                    let fits = fw >= w && fh >= h && fw - w <= 1 && fh - h <= 1;
                    let rgb = if fits { rgb } else {
                        let bgra: Vec<u8> = rgb.chunks_exact(3).flat_map(|p| [p[2], p[1], p[0], 255]).collect();
                        crate::capture::resize(&bgra, fw, fh, w, h).chunks_exact(4).flat_map(|p| [p[2], p[1], p[0]]).collect()
                    };
                    let stride = if fits { fw } else { w };
                    let px = nv12(&rgb, stride, w, h);
                    writer.WriteSample(vs, &sample(&px, t * 10_000, (next - t).max(1) * 10_000)?)?;
                } else {
                    let m = step.min(frames - k);
                    writer.WriteSample(aus, &sample(&bytes[k * 4..(k + m) * 4], at, (m as i64) * 10_000_000 / RATE as i64)?)?;
                    k += m;
                }
            }
            writer.Finalize()?;
            Ok(())
        })();
        let _ = MFShutdown();
        result
    }
}

/// Two seconds of made-up frames (a lime block moving) and a tone on bus 1, through write() (--clip-selftest DIR)
pub fn selftest(folder: &std::path::Path) -> Result<std::path::PathBuf, String> {
    let mut items = Vec::new();
    let t = 1000u32;
    let now = Instant::now();
    // (CLIP_TEST="seconds,width": a clip the size of a real one, for timing)
    let (secs, width): (u32, u32) = std::env::var("CLIP_TEST").ok().and_then(|v| {
        let mut p = v.split(',').map(|x| x.trim().parse::<u32>().ok());
        Some((p.next()??, p.next()??))
    }).unwrap_or((2, 384));
    let frames = secs * 30;
    for i in 0..frames {
        let (w, h) = (width, width * 9 / 16);
        let mut px = vec![0u8; (w * h * 4) as usize];
        for y in 0..h {
            for x in 0..w {
                let j = ((y * w + x) * 4) as usize;
                let bx = (i * 5) % (w - 50);
                let block = x >= 10 + bx && x < 46 + bx && (90..126).contains(&y);
                px[j..j + 4].copy_from_slice(if block { &[0, 255, 185, 255] } else { &[25, 40, 20, 255] });
            }
        }
        let img = crate::capture::Image { bgra: px, w, h, taken: now, seq: None };
        items.push((now, img.jpeg(t + i * 33, 80)));
    }
    let packets_n = (secs as usize) * 50;
    let mut enc = opus::Encoder::new(RATE as u32, opus::Channels::Stereo, opus::Application::Audio).map_err(|e| e.to_string())?;
    let mut packets = Vec::new();
    let mut buf = vec![0u8; 4000];
    for f in 0..packets_n {
        let frame: Vec<i16> = (0..960).flat_map(|k| {
            let s = (0.2 * (2.0 * std::f64::consts::PI * 440.0 * ((f * 960 + k) as f64) / RATE as f64).sin() * 32767.0) as i16;
            [s, s]
        }).collect();
        let n = enc.encode(&frame, &mut buf).map_err(|e| e.to_string())?;
        packets.push(buf[..n].to_vec());
    }
    for (g, group) in packets.chunks(3).enumerate() {
        let mut m = (t + g as u32 * 60).to_be_bytes().to_vec();
        m.extend_from_slice(b"AU");
        m.push(1);
        m.push(group.len() as u8);
        for p in group {
            m.extend_from_slice(&(p.len() as u16).to_be_bytes());
            m.extend_from_slice(p);
        }
        items.push((now, m));
    }
    write(&items, folder, Some("selftest.mp4"))
}

/// The page's copy from 20 s of made-up frames (moving noise, the hardest for the encoder: 1280 x 720, 8 a second) and
/// a tone, the moment at 15 s: page.mp4, still.jpg and strip.jpg into DIR, and their sizes (--page-selftest DIR).
pub fn page_selftest(folder: &std::path::Path) -> Result<String, String> {
    use std::time::Duration;
    let t0 = Instant::now();
    let (w, h) = (1280u32, 720u32);
    let mut items = Vec::new();
    let mut seed = 0x9e3779b9u32;
    for i in 0..160u32 {
        let mut px = vec![0u8; (w * h * 4) as usize];
        for y in 0..h {
            for x in 0..w {
                seed ^= seed << 13; seed ^= seed >> 17; seed ^= seed << 5;
                let j = ((y * w + x) * 4) as usize;
                let g = ((x + i * 7) % 256) as u8 / 2 + (seed & 63) as u8;
                px[j..j + 4].copy_from_slice(&[g / 2, g, g / 3, 255]);
            }
        }
        let img = crate::capture::Image { bgra: px, w, h, taken: t0, seq: None };
        items.push((t0 + Duration::from_millis(i as u64 * 125), img.jpeg(1000 + i * 125, 60)));
    }
    let mut enc = opus::Encoder::new(RATE as u32, opus::Channels::Stereo, opus::Application::Audio).map_err(|e| e.to_string())?;
    let mut buf = vec![0u8; 4000];
    for g in 0..(20 * 50 / 3) as u32 {
        let mut m = (1000 + g * 60).to_be_bytes().to_vec();
        m.extend_from_slice(b"AU");
        m.push(1);
        m.push(3);
        for f in 0..3u32 {
            let frame: Vec<i16> = (0..960).flat_map(|k| {
                let s = (0.2 * (2.0 * std::f64::consts::PI * 440.0 * (((g * 3 + f) * 960 + k) as f64) / RATE as f64).sin() * 32767.0) as i16;
                [s, s]
            }).collect();
            let n = enc.encode(&frame, &mut buf).map_err(|e| e.to_string())?;
            m.extend_from_slice(&(n as u16).to_be_bytes());
            m.extend_from_slice(&buf[..n]);
        }
        items.push((t0 + Duration::from_millis(g as u64 * 60), m));
    }
    let started = Instant::now();
    let page = page_copy(&items, t0 + Duration::from_secs(15))?;
    std::fs::create_dir_all(folder).map_err(|e| e.to_string())?;
    for (name, data) in [("page.mp4", &page.mp4), ("still.jpg", &page.still), ("strip.jpg", &page.strip)] {
        std::fs::write(folder.join(name), data).map_err(|e| e.to_string())?;
    }
    Ok(format!("page.mp4 {} bytes, {:.1} s, {}x{}; still.jpg {} bytes; strip.jpg {} bytes, {} frames at {} a second; made in {:.1} s",
               page.mp4.len(), page.dur, page.w, page.h, page.still.len(), page.strip.len(), page.frames, page.fps,
               started.elapsed().as_secs_f64()))
}

#[cfg(test)]
mod page_tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn the_still_is_the_moment_and_the_flipbook_its_few_seconds() {
        // 10 s of frames at 8 a second, 768 x 432, each a different grey
        let t0 = Instant::now();
        let items: Vec<(Instant, Vec<u8>)> = (0..80u32).map(|i| {
            let v = (i * 3) as u8;
            let img = crate::capture::Image { bgra: [v, v, v, 255].repeat(768 * 432), w: 768, h: 432, taken: t0, seq: None };
            (t0 + Duration::from_millis(i as u64 * 125), img.jpeg(i, 80))
        }).collect();
        let moment = t0 + Duration::from_millis(5000);
        let (still, strip, n, fps) = still_and_strip(&items, moment).unwrap();
        let (srgb, sw, sh) = decode(&still).unwrap();
        assert_eq!((sw, sh), (160, 90));
        assert!((srgb[0] as i32 - 120).abs() <= 3, "the frame at 5 s (grey 120), not {}", srgb[0]); // (frame 40)
        // 1.5 s before to 1 s after at 8 a second: 21 frames, thinned to 20
        assert_eq!(n, 20);
        assert!((fps - 7.6).abs() < 0.5, "{}", fps);
        let (_, w, h) = decode(&strip).unwrap();
        assert_eq!((w, h), (160, 90 * 20));
        assert!(still.len() < 12 * 1024 && strip.len() < 256 * 1024, "{} {}", still.len(), strip.len());
    }
}
