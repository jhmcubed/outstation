//! Anomaly reports (11/06/2026, Jace: "build both routes, readme should give email for direct, trying to keep it as in
//! universe as possible", and "make the report bug button easy to find and obvious in the client"): something gone
//! wrong with the outstation, reported three ways, all from "report an anomaly" (at the foot of the main window, on
//! the first screen, and in settings ▸ more):
//!   - to the listening post: their words, the station log as "copy the station log" makes it (keys, codes and webhook
//!     addresses taken out: crash.rs, scrub), and this version, posted to the listening post's relay (`POST /reports`,
//!     relay/src/index.ts), only when "send to the listening post" is pressed; the keeper reads them in his station's
//!     drawer. With the outstation attached to that relay its channel and key go too, so the report says whose it is.
//!   - on GitHub: the station log on the clipboard, and the repository's "Something went wrong" form opened in the
//!     browser, for the log to be pasted in.
//!   - by email, to the listening post's address (shown, never sent from here).
//! A requisition (11/06/2026, Jace: "optionally add a different button for requesting features and those requests can
//! also come straight to my outstation client") goes the same three ways: something wanted, in their words, with no
//! station log, and on GitHub as the "A requisition" form.
//! Nothing goes anywhere until a button is pressed (SAFETY.md, "What leaves your PC").

use crate::settings::{enc, Settings};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

/// the listening post's address, for a report by email
pub const ADDRESS: &str = "research@ghillie.wiki";
/// the repository's form for one, opened in the browser (win.rs, open_page lets this one through)
pub const ISSUE_PAGE: &str = "https://github.com/jhmcubed/outstation/issues/new";

/// the form (for an anomaly or a requisition), its version filled in
pub fn issue_url(requisition: bool) -> String {
    format!("{}?template={}&version={}", ISSUE_PAGE, if requisition { "a-requisition.yml" } else { "something-went-wrong.yml" },
            enc(crate::settings::VERSION))
}

/// The report being written, and what became of the last one sent.
pub struct Report {
    pub note: String,
    /// a requisition (something wanted) rather than an anomaly (something gone wrong)
    pub requisition: bool,
    pub open: bool,
    /// what the last press of the GitHub or email route did
    pub hint: String,
    pub busy: Arc<Mutex<bool>>,
    pub said: Arc<Mutex<Option<Result<String, String>>>>,
}

impl Report {
    pub fn new() -> Report {
        Report { note: String::new(), requisition: false, open: false, hint: String::new(), busy: Arc::new(Mutex::new(false)), said: Arc::new(Mutex::new(None)) }
    }

    /// Send it to the listening post, off the window's thread.
    pub fn send(&mut self, s: &Settings, wake: impl Fn() + Send + 'static) {
        if *self.busy.lock().unwrap() { return; }
        let home = crate::ask::http_base(crate::ask::HOME_RELAY);
        let log = if self.requisition { String::new() } else { crate::crash::log_for_sending() };
        let mut body = body(s, &self.note, &log, &home);
        body["kind"] = json!(if self.requisition { "requisition" } else { "anomaly" });
        let what = if self.requisition { "requisition" } else { "anomaly" };
        let (busy, said) = (self.busy.clone(), self.said.clone());
        *busy.lock().unwrap() = true;
        *said.lock().unwrap() = None;
        std::thread::spawn(move || {
            let got = (|| -> Result<String, String> {
                let r = ureq::post(&format!("{}/reports", home)).header("User-Agent", "outstation").header("Content-Type", "application/json")
                    .config().http_status_as_error(false).build().send(body.to_string().as_bytes());
                let mut r = r.map_err(|e| format!("the listening post's relay is out of reach ({}): write to {} instead", e, ADDRESS))?;
                let status = r.status().as_u16();
                let v: Value = serde_json::from_str(&r.body_mut().read_to_string().unwrap_or_default()).unwrap_or(Value::Null);
                if status == 201 {
                    return Ok(format!("received at the listening post ({} {})", what, v.get("id").and_then(|x| x.as_str()).unwrap_or("?")));
                }
                Err(v.get("error").and_then(|x| x.as_str()).map(|x| x.to_string())
                    .unwrap_or_else(|| format!("the relay answered {}: write to {} instead", status, ADDRESS)))
            })();
            if got.is_ok() { crate::transmit::log(&format!("a report ({}) went to the listening post", what)); }
            *said.lock().unwrap() = Some(got);
            *busy.lock().unwrap() = false;
            wake();
        });
    }
}

/// What goes: their words, the station log (already without keys and codes), the version, the name they chat under;
/// and the channel and its key only when the outstation is attached to the listening post's own relay (`home`).
pub fn body(s: &Settings, note: &str, log: &str, home: &str) -> Value {
    let mut body = json!({
        "name": if s.chat_name.trim().is_empty() { s.channel.clone() } else { s.chat_name.trim().to_string() },
        "note": note.trim(), "log": log, "version": crate::settings::VERSION,
    });
    // (anywhere else they'd mean nothing, so they don't go)
    if s.attached() && s.http_base().trim_end_matches('/') == home.trim_end_matches('/') {
        body["channel"] = json!(s.channel);
        body["key"] = json!(s.key);
    }
    body
}

#[cfg(test)]
mod tests {
    #[test]
    fn its_channel_and_key_go_only_to_the_relay_that_gave_them() {
        let mut s: crate::settings::Settings = serde_json::from_str("{}").unwrap();
        s.relay = "wss://relay.ghillie.workers.dev".into();
        s.channel = "mossfern".into();
        s.key = "a-key-of-its-own-123".into();
        let b = super::body(&s, " the tape stopped \n", "log", "https://relay.ghillie.workers.dev");
        assert_eq!((b["channel"].as_str(), b["key"].as_str(), b["note"].as_str()), (Some("mossfern"), Some("a-key-of-its-own-123"), Some("the tape stopped")));
        s.relay = "ws://127.0.0.1:8786".into();
        let b = super::body(&s, "x", "log", "https://relay.ghillie.workers.dev");
        assert!(b.get("channel").is_none() && b.get("key").is_none());
        assert!(!b.to_string().contains("a-key-of-its-own"));
    }

    #[test]
    fn the_form_is_the_repositorys_own() {
        let u = super::issue_url(false);
        assert!(u.starts_with("https://github.com/jhmcubed/outstation/issues/new?template=something-went-wrong.yml&version="));
        assert!(super::issue_url(true).contains("template=a-requisition.yml"));
        assert!(crate::win::opens(&super::issue_url(true)));
        assert!(crate::win::opens(&u));
        assert!(!crate::win::opens("https://github.com/someone/else/issues/new"));
    }
}
