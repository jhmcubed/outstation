//! Settling (11/08/2026, Jace: "i think people would like the idea of being able to place their outstation wherever
//! they'd like if they want to try building a small little cluster/network on the globe of their own"): where this
//! outstation stands on outside.wiki's globe of Gielinor. By default it stands beside whoever invited it; its owner can
//! settle it at a place of their choosing, beside another outstation, or back beside their inviter. The station key never
//! goes to a web page: the app trades it with the relay for a one-time code (10 minutes, one use: docs/protocol.md,
//! "Settling") and opens the globe in the browser with only that code, where the owner chooses. After the first, a move
//! is allowed once a week (the relay keeps that).

use crate::settings::{enc, Settings, VERSION};
use serde_json::Value;
use std::sync::Mutex;
use std::time::Instant;

/// Where the globe is. OUTSTATION_GLOBE, if set, opens another copy instead (a local one, to try it).
pub const GLOBE_PAGE: &str = "https://outside.wiki/";

static STATE: Mutex<Option<(Instant, Result<String, String>)>> = Mutex::new(None);
static BUSY: Mutex<bool> = Mutex::new(false);

/// The globe's address for a code: the channel and the code, and the relay when it isn't the usual one.
pub fn page(globe: &str, relay_http: &str, channel: &str, code: &str) -> String {
    let sep = if globe.contains('?') { '&' } else { '?' };
    let mut u = format!("{}{}settle={}.{}", globe, sep, enc(channel), enc(code));
    if relay_http.trim_end_matches('/') != crate::ask::HOME_RELAY {
        u.push_str(&format!("&relay={}", enc(relay_http.trim_end_matches('/'))));
    }
    u
}

/// Ask the relay for a code with this outstation's key, then open the globe with it. What came of it is in status().
pub fn begin(s: &Settings, wake: impl Fn() + Send + 'static) {
    {
        let mut b = BUSY.lock().unwrap();
        if *b { return; }
        *b = true;
    }
    let ask_url = format!("{}/settle/{}?key={}&v={}", s.http_base(), enc(&s.channel), enc(&s.key), enc(VERSION));
    let (base, channel) = (s.http_base(), s.channel.clone());
    let globe = std::env::var("OUTSTATION_GLOBE").ok().filter(|g| !g.trim().is_empty()).unwrap_or_else(|| GLOBE_PAGE.to_string());
    std::thread::spawn(move || {
        let got = ask(&ask_url).and_then(|code| {
            let p = page(&globe, &base, &channel, &code);
            if crate::win::open_page(&p) { Ok(p) } else { Err("the browser wouldn't open the globe".into()) }
        });
        *STATE.lock().unwrap() = Some((Instant::now(), got));
        *BUSY.lock().unwrap() = false;
        wake();
    });
}

/// What the last try came to (when, and the page it opened or why not), and whether one's under way.
pub fn status() -> Option<(Instant, Result<String, String>)> { STATE.lock().unwrap().clone() }
pub fn busy() -> bool { *BUSY.lock().unwrap() }

fn ask(url: &str) -> Result<String, String> {
    let r = ureq::post(url).header("User-Agent", "outstation").config().http_status_as_error(false).build().send("".as_bytes());
    let mut r = r.map_err(|e| format!("the relay is out of reach ({})", e))?;
    let status = r.status().as_u16();
    let text = r.body_mut().read_to_string().unwrap_or_default();
    let v: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
    if status != 200 { return Err(why(&v, status)); }
    v.get("code").and_then(|x| x.as_str()).map(String::from).ok_or_else(|| "the relay sent no code".into())
}

fn why(v: &Value, status: u16) -> String {
    match v.get("error").and_then(|x| x.as_str()) {
        Some(e) => e.to_string(),
        None => match status {
            401 | 403 => "the relay doesn't know this outstation's key".into(),
            404 => "this relay can't settle outstations yet (an older relay)".into(),
            429 => "too many tries at once: wait a minute".into(),
            s => format!("the relay answered {}", s),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_globe_gets_the_code_and_never_the_key() {
        let p = page(GLOBE_PAGE, crate::ask::HOME_RELAY, "sburb", "abc123def456");
        assert_eq!(p, "https://outside.wiki/?settle=sburb.abc123def456");
        assert!(!p.contains("key"));
    }

    #[test]
    fn another_relay_is_named_and_a_local_copy_keeps_its_own_query() {
        let p = page("http://localhost:8897/tools/release-kit/prototypes/outside-splash/login.html?bg=globe", "http://127.0.0.1:8790/", "a b", "c");
        assert_eq!(p, "http://localhost:8897/tools/release-kit/prototypes/outside-splash/login.html?bg=globe&settle=a%20b.c&relay=http%3A%2F%2F127.0.0.1%3A8790");
    }
}
