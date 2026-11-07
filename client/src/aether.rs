//! The aether (11/07/2026, Jace: "a global chat… seeing people spam ascii art and stuff. reminds me of IRC and AOL
//! Instant Messenger. i want this to feel like a real network people are joining"): one room for every outstation and
//! every visitor, on the relay (docs/protocol.md, "The aether"). This outstation joins under its own channel, with its
//! key, the first time its tab is opened, and stays while the window's open. A line may be several rows (ASCII art),
//! drawn in a fixed-width font so it keeps its shape. Nothing here reaches the transmission's chat, or comes from it.

use crate::net::{Got, Socket};
use crate::settings::{enc, Shared, VERSION};
use serde_json::Value;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant};

/// the tab's name among the chat's tabs (a channel can't be this)
pub const TAB: &str = "\u{1}aether";
/// how many lines are kept here
const KEPT: usize = 200;

#[derive(Clone)]
pub struct Line {
    pub id: i64,
    pub name: String,
    pub text: String,
    pub outstation: bool,
    pub at: i64,
}

pub struct Aether {
    pub lines: Vec<Line>,
    /// who's there now, as the relay last said
    pub here: u32,
    pub unread: u32,
    /// sent too soon: when the next may go
    pub slow_until: Option<Instant>,
    /// the last thing to say about the connection (turned away, banned…)
    pub note: Option<String>,
    started: bool,
    out: Option<mpsc::Sender<String>>,
    inbox: Option<mpsc::Receiver<Value>>,
    notes: Option<mpsc::Receiver<String>>,
    up: Arc<AtomicBool>,
}

impl Aether {
    pub fn new() -> Self {
        Aether { lines: Vec::new(), here: 0, unread: 0, slow_until: None, note: None, started: false, out: None, inbox: None,
                 notes: None, up: Arc::new(AtomicBool::new(false)) }
    }

    pub fn up(&self) -> bool {
        self.up.load(Ordering::Relaxed)
    }

    /// Join (once; the tab's first opening).
    pub fn start(&mut self, settings: Shared, wake: impl Fn() + Send + 'static) {
        if self.started {
            return;
        }
        self.started = true;
        let (out, out_rx) = mpsc::channel::<String>();
        let (in_tx, inbox) = mpsc::channel::<Value>();
        let (note_tx, notes) = mpsc::channel::<String>();
        let up = self.up.clone();
        std::thread::spawn(move || run(settings, out_rx, in_tx, note_tx, up, wake));
        self.out = Some(out);
        self.inbox = Some(inbox);
        self.notes = Some(notes);
    }

    /// A line into the room (false: not connected, or too soon).
    pub fn say(&mut self, text: &str) -> bool {
        if self.slow_until.is_some_and(|t| Instant::now() < t) || !self.up() {
            return false;
        }
        let Some(out) = &self.out else { return false };
        out.send(serde_json::json!({ "say": text }).to_string()).is_ok()
    }

    /// What's come in, taken into the room as the window draws (`open`: its tab is showing, so nothing's unread).
    pub fn drain(&mut self, open: bool) {
        if let Some(n) = &self.notes {
            while let Ok(s) = n.try_recv() {
                self.note = Some(s);
            }
        }
        let Some(inbox) = &self.inbox else { return };
        let mut got = Vec::new();
        while let Ok(v) = inbox.try_recv() {
            got.push(v);
        }
        for v in got {
            self.take(&v, open);
        }
        if open {
            self.unread = 0;
        }
    }

    fn take(&mut self, v: &Value, open: bool) {
        if let Some(l) = v.get("aether").and_then(line_of) {
            if !self.lines.iter().any(|x| x.id == l.id) {
                self.lines.push(l);
                if !open {
                    self.unread += 1;
                }
            }
        } else if let Some(all) = v.get("aether_so_far").and_then(|x| x.as_array()) {
            self.lines = all.iter().filter_map(line_of).collect();
        } else if let Some(n) = v.get("aether_here").and_then(|x| x.as_u64()) {
            self.here = n as u32;
        } else if let Some(id) = v.get("aether_unsay").and_then(|x| x.as_i64()) {
            self.lines.retain(|l| l.id != id);
        } else if let Some(s) = v.get("aether_slow").and_then(|x| x.as_f64()) {
            self.slow_until = Some(Instant::now() + Duration::from_secs_f64(s.max(0.5)));
        }
        if self.lines.len() > KEPT {
            let extra = self.lines.len() - KEPT;
            self.lines.drain(..extra);
        }
    }
}

fn line_of(v: &Value) -> Option<Line> {
    Some(Line {
        id: v.get("id")?.as_i64()?,
        name: v.get("name")?.as_str()?.to_string(),
        text: v.get("text")?.as_str()?.to_string(),
        outstation: v.get("from").and_then(|x| x.as_str()) == Some("outstation"),
        at: v.get("at").and_then(|x| x.as_i64()).unwrap_or(0),
    })
}

/// The room's address for this outstation: its relay's /aether, with its channel and key.
pub fn url(relay: &str, channel: &str, key: &str) -> String {
    format!("{}/aether?channel={}&key={}&v={}", relay.trim_end_matches('/'), enc(channel), enc(key), enc(VERSION))
}

/// A line made ready to go: its rows' ends trimmed, empty rows at the start and end dropped, at most 12 rows of 80
/// (as the relay keeps them; it cuts the same, this only spares a line it would refuse).
pub fn tidy(text: &str) -> String {
    let rows: Vec<String> = text.replace("\r\n", "\n").split('\n')
        .map(|r| r.chars().filter(|c| !c.is_control()).take(80).collect::<String>().trim_end().to_string()).collect();
    let first = rows.iter().position(|r| !r.trim().is_empty());
    let last = rows.iter().rposition(|r| !r.trim().is_empty());
    match (first, last) {
        (Some(a), Some(b)) => rows[a..=b].iter().take(12).cloned().collect::<Vec<_>>().join("\n"),
        _ => String::new(),
    }
}

fn run(settings: Shared, out: mpsc::Receiver<String>, inbox: mpsc::Sender<Value>, notes: mpsc::Sender<String>,
       up: Arc<AtomicBool>, wake: impl Fn()) {
    loop {
        let s = settings.get();
        if !s.attached() {
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }
        let who = (s.relay.clone(), s.channel.clone(), s.key.clone());
        let mut ws = match Socket::open(&url(&s.relay, &s.channel, &s.key)) {
            Ok(ws) => ws,
            Err(_) => {
                std::thread::sleep(Duration::from_secs(5));
                continue;
            }
        };
        ws.timeout(Duration::from_millis(50));
        while out.try_recv().is_ok() {}
        up.store(true, Ordering::Relaxed);
        let _ = notes.send(String::new());
        wake();
        let mut checked = Instant::now();
        let ended = loop {
            match ws.recv() {
                Ok(Got::Text(t)) => {
                    if let Ok(v) = serde_json::from_str::<Value>(&t) {
                        let _ = inbox.send(v);
                        wake();
                    }
                }
                Ok(Got::Closed(code, why)) => break Some((code, why)),
                Ok(_) => {}
                Err(_) => break None,
            }
            while let Ok(m) = out.try_recv() {
                if ws.text(&m).is_err() { break; }
            }
            if checked.elapsed() > Duration::from_millis(500) {
                checked = Instant::now();
                let s = settings.get();
                if (s.relay.clone(), s.channel.clone(), s.key.clone()) != who {
                    break None;
                }
            }
        };
        up.store(false, Ordering::Relaxed);
        ws.close();
        wake();
        let wait = match &ended {
            Some((4033, _)) => {
                let _ = notes.send("the listening post has closed the aether to this outstation".into());
                Duration::from_secs(600)
            }
            Some((code, why)) if [4001, 4003, 4026, 4029].contains(code) => {
                let _ = notes.send(format!("the relay turned the aether away ({}: {})", code, why));
                Duration::from_secs(60)
            }
            _ => Duration::from_secs(3),
        };
        let end = Instant::now() + wait;
        while Instant::now() < end {
            std::thread::sleep(Duration::from_millis(500));
            let s = settings.get();
            if (s.relay.clone(), s.channel.clone(), s.key.clone()) != who { break; }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_line_is_tidied_as_the_relay_keeps_it() {
        assert_eq!(tidy("\n\n  /\\_/\\  \n ( o.o )\r\n  > ^ <\n\n"), "  /\\_/\\\n ( o.o )\n  > ^ <");
        assert_eq!(tidy("   \n \n"), "");
        assert_eq!(tidy(&"x".repeat(100)).len(), 80);
        assert_eq!(tidy(&"row\n".repeat(20)).split('\n').count(), 12);
    }

    #[test]
    fn lines_come_in_once_and_go_when_unsaid() {
        let mut a = Aether::new();
        let line = serde_json::json!({"aether": {"id": 7, "name": "moss", "text": "hi", "from": "visitor", "at": 1}});
        a.take(&line, false);
        a.take(&line, false);
        assert_eq!((a.lines.len(), a.unread), (1, 1));
        assert!(!a.lines[0].outstation);
        a.take(&serde_json::json!({"aether_unsay": 7}), false);
        assert!(a.lines.is_empty());
        a.take(&serde_json::json!({"aether_here": 12}), true);
        assert_eq!(a.here, 12);
        assert_eq!(url("wss://r.example/", "sburb", "k e y"), format!("wss://r.example/aether?channel=sburb&key=k%20e%20y&v={}", VERSION));
    }
}
