//! Your own emotes (11/14/2026, Jace: "allow for custom emotes, png/jpg and gifs, only in the broadcaster's own chat"):
//! pictures you drop on settings ▸ emotes go up to the relay under a name, and your Outside page's transmission chat
//! shows ":name:" as that picture (never the aether), with an emote menu on its chat bar. The relay keeps 30 at most,
//! each 256 KB at most, 4 MB in all (relay/src/emotes.ts; docs/protocol.md, "Emotes").

use crate::settings::{enc, Settings};
use serde_json::Value;
use std::sync::{Arc, Mutex};
use std::time::Instant;

pub const MOST: usize = 30;
pub const BYTES: usize = 256 * 1024;

#[derive(Clone, Debug)]
pub struct Emote {
    pub name: String,
    pub id: String,
    pub ext: String,
    pub size: u64,
    /// its picture, read once for the drawer (None: not yet, or it wouldn't read)
    pub picture: Option<Arc<Vec<u8>>>,
}

/// A name the relay takes: 2 to 24 letters, numbers or _.
pub fn name_ok(n: &str) -> bool {
    (2..=24).contains(&n.len()) && n.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// A file's name made into an emote's: its letters, numbers and _ (anything else dropped), at most 24.
pub fn name_from(file: &str) -> String {
    let stem = std::path::Path::new(file).file_stem().and_then(|s| s.to_str()).unwrap_or("");
    stem.chars().map(|c| if c == ' ' || c == '-' { '_' } else { c }).filter(|c| c.is_ascii_alphanumeric() || *c == '_').take(24).collect()
}

/// PNG, JPEG or GIF, by its own first bytes (as the relay tells them).
pub fn kind(b: &[u8]) -> Option<&'static str> {
    if b.starts_with(&[0x89, b'P', b'N', b'G']) { Some("png") }
    else if b.starts_with(&[0xff, 0xd8, 0xff]) { Some("jpg") }
    else if b.starts_with(b"GIF8") { Some("gif") }
    else { None }
}

pub struct Emotes {
    pub rows: Arc<Mutex<Option<Result<Vec<Emote>, String>>>>,
    pub busy: Arc<Mutex<bool>>,
    pub read_at: Option<Instant>,
}

fn why(status: u16, text: &str) -> String {
    serde_json::from_str::<Value>(text).ok().and_then(|v| v.get("error").and_then(|x| x.as_str()).map(|x| x.to_string()))
        .unwrap_or_else(|| format!("the relay answered {}", status))
}

impl Emotes {
    pub fn new() -> Emotes {
        Emotes { rows: Arc::new(Mutex::new(None)), busy: Arc::new(Mutex::new(false)), read_at: None }
    }

    /// The list on the relay, each one's picture with it.
    pub fn read(&mut self, s: &Settings, wake: impl Fn() + Send + 'static) {
        if *self.busy.lock().unwrap() || !s.attached() { return; }
        self.read_at = Some(Instant::now());
        let (rows, busy) = (self.rows.clone(), self.busy.clone());
        let base = format!("{}/emotes/{}", s.http_base(), enc(&s.channel));
        let url = format!("{}?code={}", base, enc(&s.code));
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let got = (|| -> Result<Vec<Emote>, String> {
                let mut r = ureq::get(&url).header("User-Agent", "outstation").call().map_err(|e| match e {
                    ureq::Error::StatusCode(404) => "this relay doesn't keep emotes".to_string(),
                    ureq::Error::StatusCode(c) => format!("couldn't read your emotes: the relay answered {}", c),
                    _ => "couldn't read your emotes: the relay isn't answering".to_string(),
                })?;
                let v: Value = serde_json::from_str(&r.body_mut().read_to_string().unwrap_or_default()).unwrap_or(Value::Null);
                let mut list: Vec<Emote> = v.get("emotes").and_then(|x| x.as_array()).cloned().unwrap_or_default().iter().map(|e| Emote {
                    name: e.get("name").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                    id: e.get("id").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                    ext: e.get("ext").and_then(|x| x.as_str()).unwrap_or("").to_string(),
                    size: e.get("size").and_then(|x| x.as_u64()).unwrap_or(0),
                    picture: None,
                }).filter(|e| !e.id.is_empty() && name_ok(&e.name)).collect();
                for e in list.iter_mut() {
                    let u = format!("{}/{}.{}", base, enc(&e.id), enc(&e.ext));
                    if let Ok(mut r) = ureq::get(&u).header("User-Agent", "outstation").call() {
                        if let Ok(b) = r.body_mut().with_config().limit(BYTES as u64 + 1024).read_to_vec() { e.picture = Some(Arc::new(b)); }
                    }
                }
                Ok(list)
            })();
            *rows.lock().unwrap() = Some(got);
            *busy.lock().unwrap() = false;
            wake();
        });
    }

    /// One up under `name` (one already of that name replaced), then the list read again. What to say, either way.
    pub fn upload(&mut self, s: &Settings, name: &str, bytes: Vec<u8>, wake: impl Fn() + Send + Clone + 'static) {
        let url = format!("{}/emotes/{}?key={}&name={}", s.http_base(), enc(&s.channel), enc(&s.key), enc(name));
        let ct = match kind(&bytes) { Some("png") => "image/png", Some("gif") => "image/gif", _ => "image/jpeg" };
        let (rows, busy, s2, name) = (self.rows.clone(), self.busy.clone(), s.clone(), name.to_string());
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let r = ureq::post(&url).header("User-Agent", "outstation").header("Content-Type", ct)
                .config().http_status_as_error(false).build().send(bytes.as_slice());
            match r {
                Ok(r) if r.status().as_u16() == 200 => crate::clip::say(format!("the emote :{}: is up: your chat shows it", name)),
                Ok(mut r) => { let t = r.body_mut().read_to_string().unwrap_or_default(); crate::clip::say(format!("the emote didn't go up: {}", why(r.status().as_u16(), &t))); }
                Err(e) => crate::clip::say(format!("the emote didn't go up ({})", e)),
            }
            *busy.lock().unwrap() = false;
            let mut again = Emotes { rows, busy: Arc::new(Mutex::new(false)), read_at: None };
            again.read(&s2, wake);
        });
    }

    /// One taken away, then the list without it.
    pub fn delete(&mut self, s: &Settings, name: &str, wake: impl Fn() + Send + 'static) {
        let url = format!("{}/emotes/{}/{}?key={}", s.http_base(), enc(&s.channel), enc(name), enc(&s.key));
        let (rows, busy, name) = (self.rows.clone(), self.busy.clone(), name.to_string());
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let r = ureq::delete(&url).header("User-Agent", "outstation").call();
            match r {
                Ok(_) => { if let Some(Ok(list)) = &mut *rows.lock().unwrap() { list.retain(|e| e.name != name); } }
                Err(e) => crate::clip::say(format!("couldn't take the emote away ({})", e)),
            }
            *busy.lock().unwrap() = false;
            wake();
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_files_name_becomes_an_emotes() {
        assert_eq!(name_from("C:\\pics\\YEP face.png"), "YEP_face");
        assert_eq!(name_from("pog-champ!.gif"), "pog_champ");
        assert!(name_ok("YEP"));
        assert!(!name_ok("a"));
        assert!(!name_ok("no spaces"));
    }

    #[test]
    fn pictures_by_their_first_bytes() {
        assert_eq!(kind(&[0x89, b'P', b'N', b'G', 0x0d, 0x0a]), Some("png"));
        assert_eq!(kind(&[0xff, 0xd8, 0xff, 0xe0]), Some("jpg"));
        assert_eq!(kind(b"GIF89a"), Some("gif"));
        assert_eq!(kind(b"<svg"), None);
    }
}
