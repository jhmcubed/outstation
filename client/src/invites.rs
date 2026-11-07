//! Invites (11/07/2026, Jace: "each of my friends being able to pass out a few keys so that way the platform propagates
//! easier", earning "more invites through streaming"): an outstation with a channel makes a station key for a friend
//! itself, at once, from the relay's invites (docs/protocol.md, "Invites"): 3 to begin with, one more for every 10
//! hours on air. The new outstation is "established through" this one. The friends drawer shows what's left and makes
//! them; the key comes back here to send, and nowhere else.

use crate::settings::{enc, Settings};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

#[derive(Clone, Default)]
pub struct Status {
    pub left: u32,
    pub earned: u32,
    pub used: Vec<String>,
    pub hours: f64,
    pub next_in_s: u64,
}

/// The drawer's state: what the relay said, the invite just made, and what's under way.
pub struct Invites {
    pub status: Arc<Mutex<Option<Result<Status, String>>>>,
    pub made: Arc<Mutex<Option<Result<(String, String), String>>>>, // (their channel, the station key) or why not
    pub busy: Arc<Mutex<bool>>,
    pub read_at: Option<std::time::Instant>,
    pub name: String,
}

impl Invites {
    pub fn new() -> Self {
        Invites { status: Arc::new(Mutex::new(None)), made: Arc::new(Mutex::new(None)), busy: Arc::new(Mutex::new(false)),
                  read_at: None, name: String::new() }
    }

    /// What's left, read from the relay (at once when asked, else at most every 30 s while the drawer's open).
    pub fn tend(&mut self, s: &Settings, now: bool, wake: impl Fn() + Send + 'static) {
        if !s.attached() || (!now && self.read_at.is_some_and(|t| t.elapsed().as_secs() < 30)) {
            return;
        }
        self.read_at = Some(std::time::Instant::now());
        let (st, url) = (self.status.clone(), format!("{}/invites/{}?key={}", s.http_base(), enc(&s.channel), enc(&s.key)));
        std::thread::spawn(move || {
            *st.lock().unwrap() = Some(read(&url));
            wake();
        });
    }

    /// An invite for `player` (their name as they'd type it).
    pub fn make(&mut self, s: &Settings, player: &str, wake: impl Fn() + Send + 'static) {
        let ch = match crate::ask::channel_of(player) {
            Ok(c) => c,
            Err(e) => { *self.made.lock().unwrap() = Some(Err(e)); return; }
        };
        if *self.busy.lock().unwrap() { return; }
        *self.busy.lock().unwrap() = true;
        let (made, busy, st) = (self.made.clone(), self.busy.clone(), self.status.clone());
        let url = format!("{}/invites/{}", s.http_base(), enc(&s.channel));
        let read_url = format!("{}/invites/{}?key={}", s.http_base(), enc(&s.channel), enc(&s.key));
        let body = json!({ "key": s.key, "player": ch }).to_string();
        std::thread::spawn(move || {
            let r = post(&url, &body);
            *made.lock().unwrap() = Some(r.map(|code| (ch, code)));
            *st.lock().unwrap() = Some(read(&read_url));
            *busy.lock().unwrap() = false;
            wake();
        });
    }
}

fn read(url: &str) -> Result<Status, String> {
    let r = ureq::get(url).header("User-Agent", "outstation").config().http_status_as_error(false).build().call();
    let mut r = r.map_err(|e| format!("the relay is out of reach ({})", e))?;
    let status = r.status().as_u16();
    let text = r.body_mut().read_to_string().unwrap_or_default();
    let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
    if status != 200 {
        return Err(why(&v, status));
    }
    let n = |k: &str| v.get(k).and_then(|x| x.as_u64()).unwrap_or(0);
    Ok(Status {
        left: n("left") as u32, earned: n("earned") as u32, next_in_s: n("next_in_s"),
        hours: v.get("hours").and_then(|x| x.as_f64()).unwrap_or(0.0),
        used: v.get("used").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default(),
    })
}

fn post(url: &str, body: &str) -> Result<String, String> {
    let r = ureq::post(url).header("User-Agent", "outstation").header("Content-Type", "application/json")
        .config().http_status_as_error(false).build().send(body.as_bytes());
    let mut r = r.map_err(|e| format!("the relay is out of reach ({})", e))?;
    let status = r.status().as_u16();
    let text = r.body_mut().read_to_string().unwrap_or_default();
    let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
    if status != 200 {
        return Err(why(&v, status));
    }
    v.get("code").and_then(|x| x.as_str()).map(String::from).ok_or_else(|| "the relay made it but sent no station key".into())
}

fn why(v: &Value, status: u16) -> String {
    match v.get("error").and_then(|x| x.as_str()) {
        Some(e) => e.to_string(),
        None => match status {
            403 => "the relay doesn't know this outstation's key".into(),
            404 => "this relay has no invites (an older relay, or a relay of your own without them)".into(),
            429 => "too many invites at once: try again in an hour".into(),
            s => format!("the relay answered {}", s),
        },
    }
}

/// What to send the friend: the key, where the outstation comes from, and what to do with it.
pub fn message(code: &str) -> String {
    format!("an outstation for you, from mine (⊂λ,ｍ⊙ outstation):\n{}\n\nget the outstation from its only official page, https://{}, open it, and paste the key on its first screen.",
            code, crate::settings::RELEASES)
}

/// "one more every 10 hours on air", in words: how long to the next.
pub fn next_words(s: &Status) -> String {
    let h = s.next_in_s as f64 / 3600.0;
    if h >= 1.0 { format!("one more in {:.0} h on air", h.ceil()) } else { format!("one more in {} min on air", (s.next_in_s / 60).max(1)) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_message_carries_the_key_and_the_only_official_download() {
        let m = message("outstation1:abc");
        assert!(m.contains("outstation1:abc"));
        assert!(m.contains("https://github.com/jhmcubed/outstation/releases"));
    }

    #[test]
    fn the_next_invite_in_words() {
        let st = |s: u64| Status { next_in_s: s, ..Default::default() };
        assert_eq!(next_words(&st(36000)), "one more in 10 h on air");
        assert_eq!(next_words(&st(5400)), "one more in 2 h on air");
        assert_eq!(next_words(&st(600)), "one more in 10 min on air");
        assert_eq!(next_words(&st(5)), "one more in 1 min on air");
    }
}
