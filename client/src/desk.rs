//! The chat window's line to the relay, the desk (portal_chat.py's Line): the chat, polls, pings and status lines in;
//! your lines, polls, hides, blocks, the cap on viewers and the broadcast's title out.

use crate::net::{Got, Socket};
use crate::settings::{enc, Shared};
use serde_json::Value;
use std::sync::mpsc;
use std::time::{Duration, Instant};

pub enum In {
    Up(String),
    Down,
    Note(String),
    Msg(Value),
}

pub struct Desk {
    out: mpsc::Sender<String>,
    pub inbox: mpsc::Receiver<In>,
    up: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl Desk {
    pub fn start(settings: Shared, wake: impl Fn() + Send + 'static) -> Desk {
        let (out, out_rx) = mpsc::channel::<String>();
        let (in_tx, inbox) = mpsc::channel::<In>();
        let up = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let up2 = up.clone();
        std::thread::spawn(move || run(settings, out_rx, in_tx, up2, wake));
        Desk { out, inbox, up }
    }
    /// false when it isn't connected (nothing was sent)
    pub fn send(&self, v: Value) -> bool {
        if !self.up.load(std::sync::atomic::Ordering::Relaxed) {
            return false;
        }
        self.out.send(v.to_string()).is_ok()
    }
}

fn run(settings: Shared, out: mpsc::Receiver<String>, inbox: mpsc::Sender<In>,
       up: std::sync::Arc<std::sync::atomic::AtomicBool>, wake: impl Fn()) {
    use std::sync::atomic::Ordering;
    loop {
        let s = settings.get();
        if !s.attached() {
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }
        let name = s.host_name();
        let who = (s.relay.clone(), s.channel.clone(), s.key.clone(), name.clone());
        let url = s.socket_url("desk", &format!("key={}&name={}", enc(&s.key), enc(&name)));
        let mut ws = match Socket::open(&url) {
            Ok(ws) => ws,
            Err(crate::net::OpenError::Refused(code)) => {
                let _ = inbox.send(In::Note(format!("the relay turned the chat away ({})", code)));
                wake();
                std::thread::sleep(Duration::from_secs(10));
                continue;
            }
            Err(_) => {
                let _ = inbox.send(In::Down);
                wake();
                std::thread::sleep(Duration::from_secs(3));
                continue;
            }
        };
        ws.timeout(Duration::from_millis(30));
        while out.try_recv().is_ok() {} // (what was typed while it was down stays unsent)
        up.store(true, Ordering::Relaxed);
        let _ = inbox.send(In::Up(name.clone()));
        wake();
        let mut cap = s.max_viewers;
        let _ = ws.text(&format!("{{\"cap\": {}}}", cap));
        let (mut pings, mut guess_min, mut chat_on) = (s.pings_allowed, s.guess_min_value, s.chat_allowed);
        // the broadcast's title: on connecting (a relay that slept or restarted has it again), and whenever it changes
        let mut title = s.stream_title.trim().to_string();
        let _ = ws.text(&serde_json::json!({ "title": title }).to_string());
        let _ = ws.text(&format!("{{\"pings\": {}}}", pings));
        let _ = ws.text(&format!("{{\"chat_on\": {}}}", chat_on));
        let _ = ws.text(&format!("{{\"guess_min_value\": {}}}", guess_min));
        let mut checked = Instant::now();
        let ended = loop {
            match ws.recv() {
                Ok(Got::Text(t)) => {
                    if let Ok(v) = serde_json::from_str::<Value>(&t) {
                        let _ = inbox.send(In::Msg(v));
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
                if (s.relay.clone(), s.channel.clone(), s.key.clone(), s.host_name()) != who {
                    break None;
                }
                // the cap on viewers takes at once, with no restart
                if s.max_viewers != cap {
                    cap = s.max_viewers;
                    let _ = ws.text(&format!("{{\"cap\": {}}}", cap));
                }
                // viewers' pings, allowed or not, and what decides a drop guess: at once, too
                if s.pings_allowed != pings {
                    pings = s.pings_allowed;
                    let _ = ws.text(&format!("{{\"pings\": {}}}", pings));
                }
                if s.chat_allowed != chat_on {
                    chat_on = s.chat_allowed;
                    let _ = ws.text(&format!("{{\"chat_on\": {}}}", chat_on));
                }
                if s.stream_title.trim() != title {
                    title = s.stream_title.trim().to_string();
                    let _ = ws.text(&serde_json::json!({ "title": title }).to_string());
                }
                if s.guess_min_value != guess_min {
                    guess_min = s.guess_min_value;
                    let _ = ws.text(&format!("{{\"guess_min_value\": {}}}", guess_min));
                }
            }
        };
        up.store(false, Ordering::Relaxed);
        ws.close();
        if let Some((code, why)) = &ended {
            if *code == 4003 || *code == 4001 || *code == 4029 {
                let _ = inbox.send(In::Note(format!("the relay turned the chat away ({}: {})", code, why)));
                wake();
                // (it waits for new settings, or a minute)
                let end = Instant::now() + Duration::from_secs(60);
                while Instant::now() < end {
                    std::thread::sleep(Duration::from_millis(500));
                    let s = settings.get();
                    if (s.relay.clone(), s.channel.clone(), s.key.clone(), s.host_name()) != who { break; }
                }
                continue;
            }
        }
        let _ = inbox.send(In::Down);
        wake();
        std::thread::sleep(Duration::from_secs(2));
    }
}
