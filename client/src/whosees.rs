//! Who sees the page (11/17/2026, Jace: "the who-sees-it fix"): the Outside plugin owns it ("Make it friends only" /
//! "Open it to anyone" in its side tab, PUT /code on the relay), so the outstation no longer keeps a watch code of its
//! own. It reads the page's code with its key (GET /code/<channel>?key=) once a minute while attached, and keeps it in
//! the settings' `code`, where the monitor, the clips drawer and the emotes drawer read it to see a friends-only page.
//! The transmitter sends no code (an empty one leaves the plugin's as it is). Nothing here is ever sent but the key, to
//! the relay this outstation is attached to.
use crate::settings::{enc, Shared};
use std::time::Duration;

/// How often the page's code is read again while attached.
const EVERY: Duration = Duration::from_secs(60);

pub fn start(settings: Shared) {
    std::thread::spawn(move || {
        let mut last: Option<(String, String, String)> = None;
        let mut next = std::time::Instant::now();
        loop {
            std::thread::sleep(Duration::from_secs(2));
            let s = settings.get();
            if !s.attached() || s.clips_only {
                last = None;
                continue;
            }
            let who = (s.relay.clone(), s.channel.clone(), s.key.clone());
            // (read at once on a new attachment, then once a minute)
            if last.as_ref() != Some(&who) { next = std::time::Instant::now(); last = Some(who); }
            if std::time::Instant::now() < next { continue; }
            next = std::time::Instant::now() + EVERY;
            let url = format!("{}/code/{}?key={}", s.http_base(), enc(&s.channel), enc(&s.key));
            if let Ok(code) = read(&url) {
                if code != s.code {
                    let _ = settings.update(|x| x.code = code.clone());
                    crate::transmit::log(if code.is_empty() { "who sees it: anyone (the Outside plugin's setting)" } else { "who sees it: friends only (the Outside plugin's setting)" });
                }
            }
        }
    });
}

/// The page's code, or why not.
fn read(url: &str) -> Result<String, String> {
    let r = ureq::get(url).header("User-Agent", "outstation").config().http_status_as_error(false).build().call();
    let mut r = r.map_err(|e| e.to_string())?;
    if r.status() != 200 {
        return Err(format!("the relay answered {}", r.status()));
    }
    let text = r.body_mut().read_to_string().unwrap_or_default();
    let v: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    parse(&v).ok_or_else(|| "no code in the answer".into())
}

/// {"code": "<it>"} (an empty one: anyone).
fn parse(v: &serde_json::Value) -> Option<String> {
    v.get("code").and_then(|c| c.as_str()).filter(|c| c.is_empty() || (c.len() <= 64 && c.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'))).map(|c| c.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_relays_answer_read() {
        assert_eq!(parse(&serde_json::json!({"code": "friends1"})).as_deref(), Some("friends1"));
        assert_eq!(parse(&serde_json::json!({"code": ""})).as_deref(), Some(""));
        assert_eq!(parse(&serde_json::json!({"code": "has space"})), None);
        assert_eq!(parse(&serde_json::json!({"nope": 1})), None);
    }
}
