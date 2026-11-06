//! When something goes wrong (11/06/2026): the release is a windowed program with no console, so a crash would
//! otherwise close it without a word. A crash (a panic, in any of the portal's threads) is written into portal.log
//! with where it happened, a box says so and where the log is, and the next start says it happened (crash.txt beside
//! the log, read once). "copy my log" (the settings, under more) puts the log's last lines on the clipboard to send, with keys
//! and webhook addresses taken out.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

static LOG_PATH: OnceLock<PathBuf> = OnceLock::new();
static SHOWN: AtomicBool = AtomicBool::new(false);

fn crash_file() -> Option<PathBuf> {
    LOG_PATH.get().map(|p| p.with_file_name("crash.txt"))
}

/// Catch crashes from here on: into the log (and crash.txt), and a box the first time.
pub fn install(log_path: PathBuf) {
    let _ = LOG_PATH.set(log_path);
    std::panic::set_hook(Box::new(|info| {
        let what = info.payload().downcast_ref::<&str>().map(|s| s.to_string())
            .or_else(|| info.payload().downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "something went wrong".into());
        let at = info.location().map(|l| format!("{}:{}", l.file(), l.line())).unwrap_or_default();
        let thread = std::thread::current().name().unwrap_or("a helper").to_string();
        // (where it happened is the line in the portal's own code: a release has no symbols for a backtrace)
        crate::transmit::log(&format!("CRASH ({} thread, version {}): {} at {}", thread, crate::settings::VERSION, what, at));
        if let Some(f) = crash_file() {
            let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
            let _ = std::fs::write(f, format!("{}\n{} at {}", t, one_line(&what), at));
        }
        if !SHOWN.swap(true, Ordering::SeqCst) {
            let log = LOG_PATH.get().map(|p| p.display().to_string()).unwrap_or_else(|| "portal.log".into());
            message(&format!("the portal hit a fault and part of it stopped:\n\n{}\n\nclose it and open it again. \
                              what happened is written in\n{}\n\n\"copy my log\" (in the settings, under more) puts it on the clipboard, to send to the listening post.",
                             one_line(&what), log));
        }
    }));
}

fn one_line(s: &str) -> String {
    let t: String = s.split_whitespace().collect::<Vec<_>>().join(" ");
    t.chars().take(300).collect()
}

/// A crash the last time it ran (what and where), read once: the window says so as it opens.
pub fn last_time() -> Option<String> {
    let f = crash_file()?;
    let text = std::fs::read_to_string(&f).ok()?;
    let _ = std::fs::rename(&f, f.with_extension("txt.seen"));
    text.lines().nth(1).map(|s| s.to_string())
}

/// What "copy my log" copies: the version, then the log's last lines (the older log first if this one is short),
/// with keys, codes and webhook addresses taken out.
pub fn log_for_sending() -> String {
    let Some(p) = LOG_PATH.get() else { return String::new() };
    let mut text = std::fs::read_to_string(p.with_extension("log.old")).unwrap_or_default();
    text.push_str(&std::fs::read_to_string(p).unwrap_or_default());
    let lines: Vec<&str> = text.lines().collect();
    let tail = &lines[lines.len().saturating_sub(400)..];
    format!("outside-portal {} · its log, the last {} lines\n{}", crate::settings::VERSION, tail.len(), scrub(&tail.join("\n")))
}

/// Keys, watch codes, pickups and Discord webhook addresses out of a text meant to be sent to someone.
pub fn scrub(text: &str) -> String {
    let value_end = |c: char| c == '&' || c.is_whitespace() || c == ')' || c == '"';
    let word_end = |c: char| c.is_whitespace() || c == ')' || c == '"';
    let mut t = text.to_string();
    for k in ["key=", "code=", "pickup=", "token="] { t = blank_after(&t, k, value_end); }
    for k in ["discord.com/api/webhooks/", "discordapp.com/api/webhooks/", "portal1:"] { t = blank_after(&t, k, word_end); }
    t
}

/// Every `mark` in `text` with what follows it (up to `stop`) put as "…".
fn blank_after(text: &str, mark: &str, stop: impl Fn(char) -> bool) -> String {
    let (mut out, mut rest) = (String::with_capacity(text.len()), text);
    while let Some(i) = rest.find(mark) {
        out.push_str(&rest[..i + mark.len()]);
        let after = &rest[i + mark.len()..];
        let end = after.find(|c: char| stop(c)).unwrap_or(after.len());
        if end > 0 { out.push('…'); }
        rest = &after[end..];
    }
    out.push_str(rest);
    out
}

fn message(text: &str) {
    use windows::core::PCWSTR;
    use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONWARNING, MB_OK};
    let w: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    let t: Vec<u16> = "⊂λ,ｍ⊙ ✼ the portal".encode_utf16().chain(std::iter::once(0)).collect();
    unsafe { MessageBoxW(None, PCWSTR(w.as_ptr()), PCWSTR(t.as_ptr()), MB_OK | MB_ICONWARNING); }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keys_codes_and_webhooks_never_go_out_in_a_log() {
        let s = scrub("transmit ws://r/transmit/ja?key=abcdef123&code=moss&v=0.1.0 (4003)\n\
                       forwarded to https://discord.com/api/webhooks/123/SECRETSECRET ok\n\
                       pasted portal1:eyJyZWxheSI6 and token=xyz\n");
        assert!(!s.contains("abcdef123") && !s.contains("moss") && !s.contains("SECRETSECRET") && !s.contains("eyJyZWxheSI6") && !s.contains("xyz"), "{}", s);
        assert!(s.contains("key=…&code=…&v=0.1.0"), "{}", s);
        assert!(s.contains("webhooks/… ok"), "{}", s);
    }
}
