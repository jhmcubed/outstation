//! Requesting a station key (11/06/2026): from the attach screen, so nobody has to email the keeper for an attach code.
//!
//! The client asks the keeper's relay for its player's channel with a secret of its own (the pickup: 32 random bytes,
//! only its SHA-256 kept by the relay), then looks every 20 s whether the keeper has answered. Handed out, the attach
//! code comes back once and the client attaches itself, as if it had been pasted; turned down, it says so. Nothing is
//! sent until "ask" is pressed: the player's name as typed, the note, and the pickup (SAFETY.md, "What leaves your PC").
//! docs/protocol.md, "Asking for a station key", has the relay's side.

use crate::settings::{Asked, Shared};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// The keeper's relay on Cloudflare, where asking goes (its workers.dev address). Set at the deploy; while it's empty,
/// asking isn't open yet and the attach screen says so. (A new address here goes into tests/safety_check.py's
/// ADDRESSES and SAFETY.md too.)
pub const HOME_RELAY: &str = "https://relay.ghillie.workers.dev";

/// how often a request is looked at again while it waits
const EVERY: Duration = Duration::from_secs(20);

/// A player's name as its channel (the page's own rule: lower case, no spaces, - or _), or why not.
pub fn channel_of(player: &str) -> Result<String, String> {
    let raw = player.split_whitespace().collect::<Vec<_>>().join(" ");
    let ok = !raw.is_empty() && raw.chars().count() <= 12
        && raw.chars().all(|c| c.is_ascii_alphanumeric() || c == ' ' || c == '_' || c == '-');
    if !ok { return Err("a name is 1 to 12 letters, numbers, spaces, hyphens or underscores".into()); }
    let ch: String = raw.to_lowercase().chars().filter(|c| !matches!(c, ' ' | '_' | '-')).collect();
    if ch.is_empty() { return Err("a name needs a letter or number in it".into()); }
    Ok(ch)
}

/// A relay's address as http(s), for its routes (wss:// as https://; a bare address as https://).
pub fn http_base(relay: &str) -> String {
    let r = relay.trim().trim_end_matches('/');
    if let Some(x) = r.strip_prefix("wss://") { format!("https://{}", x) }
    else if let Some(x) = r.strip_prefix("ws://") { format!("http://{}", x) }
    else if r.is_empty() || r.contains("://") { r.to_string() }
    else { format!("https://{}", r) }
}

/// A new pickup: 32 random bytes, base64url.
pub fn new_pickup() -> Result<String, String> {
    use base64::Engine;
    use ring::rand::SecureRandom;
    let mut b = [0u8; 32];
    ring::rand::SystemRandom::new().fill(&mut b).map_err(|_| "Windows gave no random bytes".to_string())?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b))
}

#[derive(Clone, Debug, PartialEq)]
pub enum Answer {
    Waiting,
    Given(String),
    TurnedDown,
    /// no such request (it waited 14 days, or was taken back): ask again
    Gone,
}

fn now_s() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn error_of(e: ureq::Error, body: Option<String>) -> String {
    let said = body.and_then(|t| serde_json::from_str::<Value>(&t).ok())
        .and_then(|v| v.get("error").and_then(|x| x.as_str()).map(|s| s.to_string()));
    match (e, said) {
        (_, Some(s)) => s,
        (ureq::Error::StatusCode(404), None) | (ureq::Error::StatusCode(405), None) => "this relay doesn't take requests".into(),
        (ureq::Error::StatusCode(c), None) => format!("the relay answered {}", c),
        (e, None) => format!("the relay is out of reach ({})", e),
    }
}

/// Ask: the request as kept in the settings, and whether the channel is handed out already (to someone: maybe them).
pub fn ask(relay: &str, player: &str, note: &str) -> Result<(Asked, bool), String> {
    let channel = channel_of(player)?;
    let pickup = new_pickup()?;
    let url = format!("{}/requests/{}", http_base(relay), channel);
    let body = json!({ "player": player.trim(), "note": note.trim(), "pickup": pickup }).to_string();
    let r = ureq::post(&url).header("User-Agent", "outstation").header("Content-Type", "application/json")
        .config().http_status_as_error(false).build().send(body.as_bytes());
    let mut r = r.map_err(|e| error_of(e, None))?;
    let status = r.status().as_u16();
    let text = r.body_mut().read_to_string().unwrap_or_default();
    if status >= 400 { return Err(error_of(ureq::Error::StatusCode(status), Some(text))); }
    let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
    let given = v.get("given").and_then(|x| x.as_bool()).unwrap_or(false);
    Ok((Asked { relay: http_base(relay), channel, player: player.trim().to_string(), pickup, at: now_s() }, given))
}

/// What the keeper said, if anything yet (an answer is collected once: given or turned down, the relay forgets it).
pub fn check(a: &Asked) -> Result<Answer, String> {
    let url = format!("{}/requests/{}?pickup={}", a.relay, a.channel, crate::settings::enc(&a.pickup));
    let r = ureq::get(&url).header("User-Agent", "outstation").config().http_status_as_error(false).build().call();
    let mut r = r.map_err(|e| error_of(e, None))?;
    let status = r.status().as_u16();
    let text = r.body_mut().read_to_string().unwrap_or_default();
    let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
    let state = v.get("state").and_then(|x| x.as_str()).unwrap_or("");
    match (status, state) {
        (404, _) => Ok(Answer::Gone),
        (200, "waiting") => Ok(Answer::Waiting),
        (200, "turned down") => Ok(Answer::TurnedDown),
        (200, "given") => v.get("code").and_then(|x| x.as_str()).map(|c| Answer::Given(c.to_string()))
            .ok_or_else(|| "the relay said yes but gave no station key".to_string()),
        _ => Err(error_of(ureq::Error::StatusCode(status), Some(text))),
    }
}

/// Take a request back.
pub fn cancel(a: &Asked) {
    let url = format!("{}/requests/{}?pickup={}", a.relay, a.channel, crate::settings::enc(&a.pickup));
    let _ = ureq::delete(&url).header("User-Agent", "outstation").call();
}

/// The attach screen's side of it: the form, and the look every 20 s while a request waits (off the window's thread).
pub struct Asker {
    pub open: bool,
    pub player: String,
    pub note: String,
    /// the last thing to say (an error, or "turned down"), and whether it's bad news
    pub said: Arc<Mutex<Option<(String, bool)>>>,
    busy: Arc<Mutex<bool>>,
    looked: Option<Instant>,
}

impl Asker {
    pub fn new() -> Asker {
        Asker { open: false, player: String::new(), note: String::new(), said: Arc::new(Mutex::new(None)),
                busy: Arc::new(Mutex::new(false)), looked: None }
    }

    pub fn busy(&self) -> bool { *self.busy.lock().unwrap() }

    /// Ask now (the "ask" button). The request goes into the settings, so it outlives closing the portal.
    pub fn ask(&mut self, settings: Shared, wake: impl Fn() + Send + 'static) {
        if self.busy() { return; }
        let (player, note, said, busy) = (self.player.clone(), self.note.clone(), self.said.clone(), self.busy.clone());
        *busy.lock().unwrap() = true;
        *said.lock().unwrap() = None;
        self.looked = Some(Instant::now());
        std::thread::spawn(move || {
            match ask(HOME_RELAY, &player, &note) {
                Ok((asked, given)) => {
                    let ch = asked.channel.clone();
                    let _ = settings.update(|s| s.asked = Some(asked));
                    if given {
                        *said.lock().unwrap() = Some((format!("{} has an outstation already: if it's yours and you lost its station key, the listening post can re-key it (the old key stops working)", ch), false));
                    }
                }
                Err(e) => *said.lock().unwrap() = Some((e, true)),
            }
            *busy.lock().unwrap() = false;
            wake();
        });
    }

    /// Take the request back (the "never mind" button).
    pub fn cancel(&mut self, settings: &Shared) {
        if let Some(a) = settings.get().asked.clone() {
            std::thread::spawn(move || cancel(&a));
        }
        let _ = settings.update(|s| s.asked = None);
        *self.said.lock().unwrap() = None;
    }

    /// Called as the window draws: while a request waits (and nothing's attached), look again every 20 s. Handed out,
    /// the code is taken as if pasted; `attached` gets the channel, for the window's note.
    pub fn tend(&mut self, settings: Shared, attached: Arc<Mutex<Option<String>>>, wake: impl Fn() + Send + 'static) {
        let s = settings.get();
        let Some(a) = s.asked.clone() else { return };
        if s.attached() { let _ = settings.update(|x| x.asked = None); return; }
        if self.busy() || self.looked.is_some_and(|t| t.elapsed() < EVERY) { return; }
        self.looked = Some(Instant::now());
        let (said, busy) = (self.said.clone(), self.busy.clone());
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            match check(&a) {
                Ok(Answer::Waiting) => {}
                Ok(Answer::Given(code)) => match crate::settings::parse_attach(&code) {
                    Ok((relay, channel, key)) => {
                        let _ = settings.update(|s| { s.relay = relay; s.channel = channel.clone(); s.key = key; s.asked = None; s.setup_step = Some(0); });
                        *attached.lock().unwrap() = Some(channel);
                    }
                    Err(e) => *said.lock().unwrap() = Some((e, true)),
                },
                Ok(Answer::TurnedDown) => {
                    let _ = settings.update(|s| s.asked = None);
                    *said.lock().unwrap() = Some((format!("the listening post denied the request for {}", a.player), true));
                }
                Ok(Answer::Gone) => {
                    let _ = settings.update(|s| s.asked = None);
                    *said.lock().unwrap() = Some(("the request isn't there any more (nobody answers after 14 days): request again".into(), true));
                }
                Err(e) => *said.lock().unwrap() = Some((format!("couldn't look at the request: {}", e), true)),
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
    fn a_players_name_as_its_channel() {
        assert_eq!(channel_of("  Moss  Fern ").unwrap(), "mossfern");
        assert_eq!(channel_of("ja_ck-1").unwrap(), "jack1");
        assert!(channel_of("").is_err());
        assert!(channel_of("- _").is_err());
        assert!(channel_of("thirteen chars").is_err());
        assert!(channel_of("no/slash").is_err());
    }

    #[test]
    fn pickups_are_long_and_new_each_time() {
        let (a, b) = (new_pickup().unwrap(), new_pickup().unwrap());
        assert_eq!(a.len(), 43);
        assert_ne!(a, b);
        assert_eq!(http_base("wss://r.example/"), "https://r.example");
        assert_eq!(http_base("r.example"), "https://r.example");
        assert_eq!(http_base("http://127.0.0.1:8790"), "http://127.0.0.1:8790");
    }
}
