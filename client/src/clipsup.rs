//! Clips on the player's Outside page (11/06/2026, Jace: "build the clip uploads that way"): with "put them on my
//! Outside page" on (dink ▸, off to begin with), a clip that saved itself (a pet, a new log slot, a 99, a big drop) goes to
//! the relay too, as a standard copy (clip.rs, page_copy: 17 s round the moment, at most 768 px wide), its still and
//! its flipbook (a dozen or so small frames, the hover on the page). The relay keeps 20 a channel for 30 days
//! (relay/src/clips.ts); anyone who opens the player's Outside page can watch them. The window lists them and takes
//! any one down. Nothing is sent with the switch off. docs/protocol.md, "Clips".

use crate::settings::{enc, Settings};
use base64::Engine;
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

/// The page's copy made from the buffer round `moment` and sent, on the caller's thread. What to say, either way.
pub fn upload(s: &Settings, what: &str, kind: &str, moment: Instant) -> Result<String, String> {
    if !s.attached() {
        return Err("not attached to a relay".into());
    }
    let items = crate::clip::snapshot();
    let page = crate::clip::page_copy(&items, moment)?;
    let at = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0).saturating_sub(moment.elapsed().as_secs());
    let meta = json!({
        "what": what, "kind": kind, "at": at, "dur": page.dur, "w": page.w, "h": page.h, "frames": page.frames, "fps": page.fps,
        "still": base64::engine::general_purpose::STANDARD.encode(&page.still),
    });
    let (body, ct) = multipart(&meta.to_string(), &page.strip, &page.mp4);
    let url = format!("{}/clips/{}?key={}", s.http_base(), enc(&s.channel), enc(&s.key));
    let r = ureq::post(&url).header("User-Agent", "outstation").header("Content-Type", &ct)
        .config().http_status_as_error(false).build().send(body.as_slice());
    let mut r = r.map_err(|e| format!("the relay is out of reach ({})", e))?;
    let status = r.status().as_u16();
    let text = r.body_mut().read_to_string().unwrap_or_default();
    if status == 201 {
        return Ok(format!("on your Outside page: {} ({:.1} MB)", what, page.mp4.len() as f64 / 1048576.0));
    }
    let why = serde_json::from_str::<Value>(&text).ok().and_then(|v| v.get("error").and_then(|x| x.as_str()).map(|x| x.to_string()));
    Err(why.unwrap_or_else(|| format!("the relay answered {}", status)))
}

/// The three parts as multipart/form-data: the body and its Content-Type.
pub fn multipart(meta: &str, strip: &[u8], mp4: &[u8]) -> (Vec<u8>, String) {
    let b = "outsideportalclip";
    let mut out = Vec::with_capacity(mp4.len() + strip.len() + meta.len() + 512);
    for (name, kind, data, file) in [("meta", "application/json", meta.as_bytes(), false), ("strip", "image/jpeg", strip, true),
                                     ("clip", "video/mp4", mp4, true)] {
        let fname = if file { format!("; filename=\"{}\"", name) } else { String::new() };
        out.extend_from_slice(format!("--{}\r\nContent-Disposition: form-data; name=\"{}\"{}\r\nContent-Type: {}\r\n\r\n", b, name, fname, kind).as_bytes());
        out.extend_from_slice(data);
        out.extend_from_slice(b"\r\n");
    }
    out.extend_from_slice(format!("--{}--\r\n", b).as_bytes());
    (out, format!("multipart/form-data; boundary={}", b))
}

#[derive(Clone, Debug, PartialEq)]
pub struct Row { pub id: String, pub what: String, pub at: u64, pub dur: f64, pub size: u64, pub kept: bool }

/// how many a channel may keep for good (the relay's KEPT_MOST)
pub const KEPT_MOST: usize = 3;

/// The window's list of what's on the page (read when the drawer asks, off its thread), and taking one down.
pub struct PageClips {
    pub rows: Arc<Mutex<Option<Result<Vec<Row>, String>>>>,
    busy: Arc<Mutex<bool>>,
    pub read_at: Option<Instant>,
}

impl PageClips {
    pub fn new() -> PageClips {
        PageClips { rows: Arc::new(Mutex::new(None)), busy: Arc::new(Mutex::new(false)), read_at: None }
    }

    pub fn read(&mut self, s: &Settings, wake: impl Fn() + Send + 'static) {
        if *self.busy.lock().unwrap() || !s.attached() { return; }
        self.read_at = Some(Instant::now());
        let (rows, busy) = (self.rows.clone(), self.busy.clone());
        let url = format!("{}/clips/{}?code={}", s.http_base(), enc(&s.channel), enc(&s.code));
        let base = s.http_base();
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let got = (|| -> Result<Vec<Row>, String> {
                let mut r = ureq::get(&url).header("User-Agent", "outstation").call().map_err(|e| match e {
                    ureq::Error::StatusCode(404) => "this relay doesn't keep clips".to_string(),
                    ureq::Error::StatusCode(c) => format!("couldn't read what's on your page: the relay answered {}", c),
                    _ => format!("couldn't read what's on your page: the relay ({}) isn't answering", base),
                })?;
                let v: Value = serde_json::from_str(&r.body_mut().read_to_string().unwrap_or_default()).unwrap_or(Value::Null);
                Ok(v.get("clips").and_then(|x| x.as_array()).cloned().unwrap_or_default().iter().map(|c| Row {
                    id: c.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                    what: c.get("what").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                    at: c.get("at").and_then(|x| x.as_u64()).unwrap_or(0),
                    dur: c.get("dur").and_then(|x| x.as_f64()).unwrap_or(0.0),
                    size: c.get("size").and_then(|x| x.as_u64()).unwrap_or(0),
                    kept: c.get("kept").and_then(|x| x.as_bool()).unwrap_or(false),
                }).filter(|r| !r.id.is_empty()).collect())
            })();
            *rows.lock().unwrap() = Some(got);
            *busy.lock().unwrap() = false;
            wake();
        });
    }

    /// Keep one for good (never in the 20, never too old), or let it go again.
    pub fn keep(&mut self, s: &Settings, id: &str, keep: bool, wake: impl Fn() + Send + 'static) {
        let url = format!("{}/clips/{}/{}?key={}&keep={}", s.http_base(), enc(&s.channel), enc(id), enc(&s.key), if keep { 1 } else { 0 });
        let (rows, busy, id) = (self.rows.clone(), self.busy.clone(), id.to_string());
        let failed = if keep { "couldn't file the clip" } else { "couldn't let the clip go" };
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let r = ureq::post(&url).header("User-Agent", "outstation").config().http_status_as_error(false).build().send(&[] as &[u8]);
            match r {
                Ok(r) if r.status().as_u16() == 200 => {
                    if let Some(Ok(list)) = &mut *rows.lock().unwrap() {
                        for x in list.iter_mut() { if x.id == id { x.kept = keep; } }
                    }
                }
                Ok(mut r) => {
                    let t = r.body_mut().read_to_string().unwrap_or_default();
                    let why = serde_json::from_str::<Value>(&t).ok().and_then(|v| v.get("error").and_then(|x| x.as_str()).map(|x| x.to_string()));
                    crate::clip::say(format!("{}: {}", failed, why.unwrap_or_else(|| format!("the relay answered {}", r.status().as_u16()))));
                }
                Err(e) => crate::clip::say(format!("{} ({})", failed, e)),
            }
            *busy.lock().unwrap() = false;
            wake();
        });
    }

    /// Take one down, then read the list again.
    pub fn delete(&mut self, s: &Settings, id: &str, wake: impl Fn() + Send + 'static) {
        let url = format!("{}/clips/{}/{}?key={}", s.http_base(), enc(&s.channel), enc(id), enc(&s.key));
        let (rows, busy, id) = (self.rows.clone(), self.busy.clone(), id.to_string());
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let r = ureq::delete(&url).header("User-Agent", "outstation").call();
            if let Some(Ok(list)) = &mut *rows.lock().unwrap() {
                if r.is_ok() { list.retain(|x| x.id != id); }
            }
            if let Err(e) = r { crate::clip::say(format!("couldn't take the clip down ({})", e)); }
            *busy.lock().unwrap() = false;
            wake();
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_upload_is_three_parts() {
        let (body, ct) = multipart("{\"what\":\"pet vorki\"}", b"\xff\xd8strip", b"\0\0\0\x18ftypmp42");
        assert_eq!(ct, "multipart/form-data; boundary=outsideportalclip");
        let t = String::from_utf8_lossy(&body);
        assert!(t.starts_with("--outsideportalclip\r\nContent-Disposition: form-data; name=\"meta\"\r\nContent-Type: application/json\r\n\r\n{\"what\":\"pet vorki\"}\r\n"));
        assert!(t.contains("name=\"strip\"; filename=\"strip\"\r\nContent-Type: image/jpeg"));
        assert!(t.contains("name=\"clip\"; filename=\"clip\"\r\nContent-Type: video/mp4"));
        assert!(t.ends_with("ftypmp42\r\n--outsideportalclip--\r\n"));
    }
}
