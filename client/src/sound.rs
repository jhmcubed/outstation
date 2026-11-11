//! The sound: the game's, and the rest of the computer's, beside the frames (sound.py and portal-sound, in one;
//! parity lines 14-18).
//!
//! Windows' process loopback hands over one program's sound (RuneLite's tree), or everything but it, at 48 kHz stereo,
//! taken before the mix. Each bus is cut into 20 ms Opus packets (libopus), five to a message, stamped with when it
//! was heard on the frames' own clock, and sent to the relay's sound lane. The relay holds them as long as the capture
//! holds frames, and lets them through only while the picture is open. The bus numbers are permanent (the page knows
//! them): 1 the game, 2 everything else.

use crate::clock;
use crate::net::{Got, Out, Socket, WAITS};
use crate::settings::Shared;
use crate::win;
use std::collections::{BTreeMap, VecDeque};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const MODES: [&str; 3] = ["off", "game", "all"];
const BUS_GAME: u8 = 1;
const BUS_REST: u8 = 2;
const RATE: usize = 48000;
const FRAME: usize = 960; // 20 ms
// 100 ms a message (11/07/2026, from 3: every message counts against the relay's requests, and a bus at 3 was
// about 17 a second; the page reads any number, and 100 ms still leaves the picture's 200 ms sound lead room)
const PER_MESSAGE: usize = 5;
const REANCHOR: f64 = 0.08; // the sound's own timing and the clock this far apart: start the reckoning over
const QUEUE: usize = 200; // ~6 s a bus: behind further than that, the oldest go

#[derive(Clone, Default)]
pub struct Status {
    pub mode: String,
    pub buses: Vec<u8>,
    pub note: String,
}

pub fn mode_of(s: &str) -> &'static str {
    MODES.iter().find(|m| **m == s).copied().unwrap_or("off")
}

pub fn next_mode(s: &str) -> &'static str {
    let i = MODES.iter().position(|m| *m == mode_of(s)).unwrap_or(0);
    MODES[(i + 1) % MODES.len()]
}

pub fn start(settings: Shared) -> Arc<Mutex<Status>> {
    let status = Arc::new(Mutex::new(Status::default()));
    {
        let status = status.clone();
        std::thread::spawn(move || run(settings, status));
    }
    status
}

pub fn label(m: &str) -> &'static str {
    match m {
        "game" => "the game",
        "all" => "the game + the desktop's sound",
        _ => "off",
    }
}

fn run(settings: Shared, status: Arc<Mutex<Status>>) {
    let (tx, rx) = mpsc::sync_channel::<Vec<u8>>(QUEUE * 2);
    let mut buses: BTreeMap<u8, Bus> = BTreeMap::new();
    let (mut pid, mut pid_at) = (None::<u32>, Instant::now() - Duration::from_secs(5));
    let mut failures = 0usize;
    let mut last_note = String::new();
    loop {
        let s = settings.get();
        // (dark but recording: the sound onto the tape too, nothing sent; 11/07/2026)
        let taping = (!s.attached() || !s.on_air) && s.taping_dark();
        if (!s.attached() || !s.on_air) && !taping {
            for (_, b) in std::mem::take(&mut buses) { b.stop(); }
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }
        let who = (s.relay.clone(), s.channel.clone(), s.key.clone());
        let opened = if taping { Ok(Out::Tape) } else {
            Socket::open(&s.socket_url("sound", &format!("key={}", crate::settings::enc(&s.key)))).map(Out::Relay)
        };
        let mut ws = match opened {
            Ok(ws) => ws,
            Err(e) => {
                let wait = WAITS[failures.min(WAITS.len() - 1)];
                failures += 1;
                crate::transmit::log(&format!("sound: no relay at {} ({}); again in {} s", s.relay, e, wait));
                std::thread::sleep(Duration::from_secs(wait));
                continue;
            }
        };
        failures = 0;
        let mut queue: VecDeque<Vec<u8>> = VecDeque::new();
        let (mut told, mut told_at) = (String::new(), Instant::now() - Duration::from_secs(10));
        let why = loop {
            let s = settings.get();
            if taping {
                if (s.on_air && s.attached()) || !s.taping_dark() {
                    break "the tape gave way".to_string();
                }
            } else if !s.on_air || (s.relay.clone(), s.channel.clone(), s.key.clone()) != who {
                break "the stream ended".to_string();
            }
            let mode = mode_of(&s.sound);
            if pid_at.elapsed().as_secs_f64() > 1.0 {
                pid = win::runelite_pid(&s.title);
                pid_at = Instant::now();
            }
            let mut want: BTreeMap<u8, (u32, bool)> = BTreeMap::new();
            if let Some(p) = pid {
                if mode == "game" || mode == "all" { want.insert(BUS_GAME, (p, true)); }
                if mode == "all" { want.insert(BUS_REST, (p, false)); }
            }
            for b in buses.keys().cloned().collect::<Vec<_>>() {
                let bus = &buses[&b];
                if want.get(&b) != Some(&(bus.pid, bus.include)) || !bus.alive() {
                    if !bus.alive() {
                        if let Some(e) = bus.error.lock().unwrap().clone() {
                            crate::transmit::log(&format!("sound: bus {} stopped ({})", b, e));
                        }
                    }
                    buses.remove(&b).unwrap().stop();
                }
            }
            for (b, (p, inc)) in &want {
                if !buses.contains_key(b) {
                    buses.insert(*b, Bus::start(*b, *p, *inc, s.sound_kbps, tx.clone()));
                }
            }
            let note = if pid.is_some() || mode == "off" { format!("sound: {}", mode) } else { format!("sound: {}, waiting for RuneLite", mode) };
            if note != last_note {
                crate::transmit::log(&note);
                last_note = note.clone();
            }
            {
                let mut st = status.lock().unwrap();
                st.mode = mode.into();
                st.buses = buses.keys().cloned().collect();
                st.note = note;
            }
            let numbers: Vec<String> = buses.keys().map(|b| b.to_string()).collect();
            let info = format!("{{\"sound\": {{\"mode\": \"{}\", \"buses\": [{}]}}}}", mode, numbers.join(", "));
            if info != told || told_at.elapsed().as_secs_f64() > 5.0 {
                if let Err(e) = ws.text(&info) { break e; }
                told = info;
                told_at = Instant::now();
            }
            // what the buses made, oldest first; never more than QUEUE behind
            let end = Instant::now() + Duration::from_millis(250);
            let mut failed = None;
            while Instant::now() < end {
                match rx.recv_timeout(Duration::from_millis(20)) {
                    Ok(m) => {
                        queue.push_back(m);
                        while queue.len() > QUEUE { queue.pop_front(); }
                    }
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                    Err(_) => {}
                }
                while let Some(m) = queue.pop_front() {
                    crate::clip::keep(&m);
                    // (nobody seated, as the relay last told the transmitter: kept for clips, not sent, 11/10/2026;
                    // the tape sends nothing anyway)
                    if !taping && !crate::transmit::watched() { continue; }
                    if let Err(e) = ws.binary(m) { failed = Some(e); break; }
                }
                if failed.is_some() { break; }
            }
            if let Some(e) = failed { break e; }
            match ws.recv() {
                Ok(Got::Closed(4026, why)) => break format!("too old: {}", why),
                Ok(Got::Closed(code, _)) => break format!("the relay closed it ({})", code),
                Err(e) => break e,
                _ => {}
            }
        };
        ws.close();
        if let Some(reason) = why.strip_prefix("too old: ") {
            // older than the relay allows: no sound until the settings change (the picture says why)
            crate::transmit::log(&format!("sound: {}", crate::settings::too_old_note(reason)));
            for (_, b) in std::mem::take(&mut buses) { b.stop(); }
            while { let s = settings.get(); (s.relay.clone(), s.channel.clone(), s.key.clone()) == who } {
                std::thread::sleep(Duration::from_millis(500));
            }
            continue;
        }
        if taping {
            // (the tape gave way to a transmission, or was turned off: at once, no wait)
            while rx.try_recv().is_ok() {}
            continue;
        }
        crate::transmit::log(&format!("sound: the relay went ({}); again in 2 s", why));
        while rx.try_recv().is_ok() {}
        std::thread::sleep(Duration::from_secs(if why == "the stream ended" { 0 } else { 2 }));
    }
}

// ---- one bus ----

struct Bus {
    pid: u32,
    include: bool,
    running: Arc<AtomicBool>,
    error: Arc<Mutex<Option<String>>>,
}

impl Bus {
    fn start(number: u8, pid: u32, include: bool, kbps: u32, send: mpsc::SyncSender<Vec<u8>>) -> Bus {
        let running = Arc::new(AtomicBool::new(true));
        let error = Arc::new(Mutex::new(None));
        let (r, e) = (running.clone(), error.clone());
        std::thread::spawn(move || {
            if let Err(why) = listen(number, pid, include, kbps, &r, send) {
                *e.lock().unwrap() = Some(why);
            }
            r.store(false, Ordering::Relaxed);
        });
        Bus { pid, include, running, error }
    }
    fn alive(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }
    fn stop(self) {
        self.running.store(false, Ordering::Relaxed);
    }
}

/// Packets into messages: 4 bytes when it was heard (ms, the frames' clock), "AU", the bus, how many packets, then
/// each as a 2-byte length and the packet.
struct Grouper {
    number: u8,
    group: Vec<Vec<u8>>,
    group_t: f64,
    group_next: f64,
}

impl Grouper {
    fn flush(&mut self, send: &mpsc::SyncSender<Vec<u8>>) {
        if self.group.is_empty() {
            return;
        }
        let ms = ((self.group_t * 1000.0) as i64 as u64 & 0xffff_ffff) as u32;
        let mut m = ms.to_be_bytes().to_vec();
        m.extend_from_slice(b"AU");
        m.push(self.number);
        m.push(self.group.len() as u8);
        for p in self.group.drain(..) {
            m.extend_from_slice(&(p.len() as u16).to_be_bytes());
            m.extend_from_slice(&p);
        }
        let _ = send.try_send(m);
    }
    fn add(&mut self, packet: Vec<u8>, t: f64, send: &mpsc::SyncSender<Vec<u8>>) {
        if !self.group.is_empty() && ((t - self.group_next).abs() > 0.002 || self.group.len() >= PER_MESSAGE) {
            self.flush(send);
        }
        if self.group.is_empty() {
            self.group_t = t;
        }
        self.group.push(packet);
        self.group_next = t + FRAME as f64 / RATE as f64;
    }
}

fn listen(number: u8, pid: u32, include: bool, kbps: u32, running: &AtomicBool, send: mpsc::SyncSender<Vec<u8>>)
          -> Result<(), String> {
    use wasapi::*;
    let e = |x: Box<dyn std::error::Error>| x.to_string();
    initialize_mta().ok().map_err(|x| x.to_string())?;
    let format = WaveFormat::new(16, 16, &SampleType::Int, RATE, 2, None);
    let mut client = AudioClient::new_application_loopback_client(pid, include).map_err(|x| e(x.into()))?;
    let mode = StreamMode::EventsShared { autoconvert: true, buffer_duration_hns: 200_000 };
    client.initialize_client(&format, &Direction::Capture, &mode).map_err(|x| e(x.into()))?;
    let event = client.set_get_eventhandle().map_err(|x| e(x.into()))?;
    let capture = client.get_audiocaptureclient().map_err(|x| e(x.into()))?;
    client.start_stream().map_err(|x| e(x.into()))?;
    crate::transmit::log(&format!("sound: bus {} listening ({} {})", number, if include { "only" } else { "all but" }, pid));
    let mut enc = opus::Encoder::new(RATE as u32, opus::Channels::Stereo, opus::Application::Audio).map_err(|x| x.to_string())?;
    enc.set_bitrate(opus::Bitrate::Bits((kbps as i32) * 1000)).map_err(|x| x.to_string())?;
    enc.set_vbr(true).map_err(|x| x.to_string())?;
    let mut grouper = Grouper { number, group: Vec::new(), group_t: 0.0, group_next: 0.0 };
    let mut packet = vec![0u8; 4000];
    let mut pcm: Vec<i16> = Vec::new(); // interleaved stereo, not yet encoded
    let mut pcm_t: Option<f64> = None; // when its first sample was heard (the clock's seconds)
    let mut raw: VecDeque<u8> = VecDeque::new();
    let mut encode = |frame: &[i16], t: f64, grouper: &mut Grouper| {
        if let Ok(n) = enc.encode(frame, &mut packet) {
            grouper.add(packet[..n].to_vec(), t, &send);
        }
    };
    while running.load(Ordering::Relaxed) {
        // a quiet program sends no periods at all: waiting out the timeout is normal
        let _ = event.wait_for_event(1000);
        loop {
            let frames = capture.get_next_packet_size().map_err(|x| e(x.into()))?.unwrap_or(0);
            if frames == 0 { break; }
            capture.read_from_device_to_deque(&mut raw).map_err(|x| e(x.into()))?;
        }
        if raw.is_empty() {
            continue;
        }
        let bytes: Vec<u8> = raw.drain(..).collect();
        let samples: Vec<i16> = bytes.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect();
        let now = clock::secs_at(Instant::now());
        let start = now - (samples.len() / 2) as f64 / RATE as f64;
        let reckoned = pcm_t.map(|t| t + (pcm.len() / 2) as f64 / RATE as f64);
        if reckoned.map_or(true, |r| (start - r).abs() > REANCHOR) {
            // a gap (nothing played) or the clocks apart: what's left goes out padded, then start again here
            if let Some(t) = pcm_t {
                if !pcm.is_empty() {
                    pcm.resize(FRAME * 2, 0);
                    encode(&pcm, t, &mut grouper);
                    grouper.flush(&send);
                }
            }
            pcm.clear();
            pcm_t = Some(start);
        }
        pcm.extend_from_slice(&samples);
        while pcm.len() >= FRAME * 2 {
            let frame: Vec<i16> = pcm.drain(..FRAME * 2).collect();
            let t = pcm_t.unwrap();
            encode(&frame, t, &mut grouper);
            pcm_t = Some(t + FRAME as f64 / RATE as f64);
        }
        if grouper.group.len() >= PER_MESSAGE {
            grouper.flush(&send);
        }
    }
    grouper.flush(&send);
    let _ = client.stop_stream();
    Ok(())
}
