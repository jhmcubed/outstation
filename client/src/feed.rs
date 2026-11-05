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
        ws.timeout(Duration::from_millis(200));
        let _ = ws.text("{\"look\": {\"frames\": true}}");
        f.lock().unwrap().connected = true;
        loop {
            let s = settings.get();
            if !s.live_view || (s.relay.clone(), s.channel.clone(), s.code.clone()) != who {
                break;
            }
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
        ws.close();
        f.lock().unwrap().connected = false;
        wake();
        std::thread::sleep(Duration::from_secs(2));
    });
    feed
}
