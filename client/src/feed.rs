//! The live view (11/05/2026, Jace: "add a tiny live feed to relay client to show that live feed is working"): the
//! portal as the site gets it, a small picture in the window. It watches its own channel through the relay as the
//! listening post does (a looker: no seat, not counted as watching, two frames a second at most), so what it shows
//! has gone out and come back. Off ("the live view" in the window), it doesn't connect at all and costs nothing.

use crate::net::{Got, Socket};
use crate::settings::{enc, Shared};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Default)]
pub struct Feed {
    /// the newest frame as RGB, its size, and when it came
    pub frame: Option<(Vec<u8>, u32, u32, Instant)>,
    /// frames in the last few seconds (when each came)
    pub came: Vec<Instant>,
    pub connected: bool,
}

pub fn start(settings: Shared, wake: impl Fn() + Send + 'static) -> Arc<Mutex<Feed>> {
    let feed = Arc::new(Mutex::new(Feed::default()));
    let f = feed.clone();
    std::thread::spawn(move || loop {
        let s = settings.get();
        if !s.live_view || !s.attached() {
            f.lock().unwrap().connected = false;
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }
        let who = (s.relay.clone(), s.channel.clone(), s.code.clone());
        let Ok(mut ws) = Socket::open(&s.socket_url("watch", &format!("look=1&code={}", enc(&s.code)))) else {
            std::thread::sleep(Duration::from_secs(5));
            continue;
        };
        pump(&mut ws, &f, &wake, || {
            let s = settings.get();
            s.live_view && (s.relay.clone(), s.channel.clone(), s.code.clone()) == who
        });
        ws.close();
        f.lock().unwrap().connected = false;
        wake();
        std::thread::sleep(Duration::from_secs(2));
    });
    feed
}

/// A looker's frames into `f` while `go` says so, till the socket closes.
fn pump(ws: &mut Socket, f: &Arc<Mutex<Feed>>, wake: &impl Fn(), go: impl Fn() -> bool) {
    ws.timeout(Duration::from_millis(200));
    let _ = ws.text("{\"look\": {\"frames\": true}}");
    f.lock().unwrap().connected = true;
    while go() {
        match ws.recv() {
            // a small frame ("TH", then its JPEG), or a full one from a relay or capture without them
            Ok(Got::Binary(b)) if b.len() > 8 && ((b[4] == b'T' && b[5] == b'H') || (b[4] == 0xFF && b[5] == 0xD8)) => {
                let at = if b[4] == b'T' { 6 } else { 4 };
                let mut d = jpeg_decoder::Decoder::new(&b[at..]);
                if let (Ok(px), Some(info)) = (d.decode(), d.info()) {
                    if info.pixel_format == jpeg_decoder::PixelFormat::RGB24 {
                        let mut g = f.lock().unwrap();
                        g.frame = Some((px, info.width as u32, info.height as u32, Instant::now()));
                        g.came.push(Instant::now());
                        g.came.retain(|t| t.elapsed() < Duration::from_secs(4));
                    }
                }
                wake();
            }
            Ok(Got::Closed(..)) | Err(_) => break,
            _ => {}
        }
    }
}

/// A peek at a friend's portal (11/06/2026, Jace: "peeks in the friends list"): their live view, small, while the
/// hand is over their name in the friends list. A looker as the live view is (no seat, not counted as watching, two
/// small frames a second at most), connected only while asked for: the hand gone a second and a half, it closes.
/// Only a public portal can be peeked at (one without a watch code: the same ones the list knows are live).
pub struct Peek {
    /// the relay (its ws:// or wss:// address) and the channel asked for, and when it was last asked
    want: Arc<Mutex<Option<(String, String, Instant)>>>,
    pub feed: Arc<Mutex<Feed>>,
    /// the channel the frames in `feed` are of
    pub of: Arc<Mutex<String>>,
}

/// how long a peek stays open after the hand's gone
const PEEK_STAYS: Duration = Duration::from_millis(1500);

impl Peek {
    pub fn start(wake: impl Fn() + Send + 'static) -> Peek {
        let p = Peek { want: Arc::new(Mutex::new(None)), feed: Arc::new(Mutex::new(Feed::default())), of: Arc::new(Mutex::new(String::new())) };
        let (want, f, of) = (p.want.clone(), p.feed.clone(), p.of.clone());
        std::thread::spawn(move || loop {
            let asked = want.lock().unwrap().clone().filter(|(_, _, at)| at.elapsed() < PEEK_STAYS);
            let Some((relay, ch, _)) = asked else {
                std::thread::sleep(Duration::from_millis(100));
                continue;
            };
            if *of.lock().unwrap() != ch {
                *f.lock().unwrap() = Feed::default();
                *of.lock().unwrap() = ch.clone();
            }
            let url = format!("{}/watch/{}?look=1&v={}", relay.trim_end_matches('/'), enc(&ch), enc(crate::settings::VERSION));
            let Ok(mut ws) = Socket::open(&url) else {
                std::thread::sleep(Duration::from_secs(2));
                continue;
            };
            pump(&mut ws, &f, &wake, || {
                want.lock().unwrap().as_ref().is_some_and(|(r, c, at)| *r == relay && *c == ch && at.elapsed() < PEEK_STAYS)
            });
            ws.close();
            f.lock().unwrap().connected = false;
            wake();
        });
        p
    }

    /// The hand's over this friend: keep their peek open (or open it).
    pub fn ask(&self, relay: &str, channel: &str) {
        *self.want.lock().unwrap() = Some((relay.to_string(), channel.to_string(), Instant::now()));
    }
}
