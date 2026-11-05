//! A WebSocket to the relay (wsmini.py's job): ws:// or wss://, a short read timeout so one thread can both send
//! and listen, and the relay's close codes.

use std::net::TcpStream;
use std::time::Duration;
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{Message, WebSocket};

pub struct Socket {
    ws: WebSocket<MaybeTlsStream<TcpStream>>,
}

pub enum Got {
    Text(String),
    Binary(Vec<u8>),
    /// the relay closed it, with its code
    Closed(u16, String),
    Nothing,
}

#[derive(Debug)]
pub enum OpenError {
    /// no relay there (try again later)
    Unreachable(String),
    /// the relay answered, but not with a WebSocket (its HTTP status)
    Refused(u16),
}

impl std::fmt::Display for OpenError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            OpenError::Unreachable(e) => write!(f, "{}", e),
            OpenError::Refused(s) => write!(f, "the relay refused it ({})", s),
        }
    }
}

pub fn tls_ready() {
    let _ = rustls::crypto::ring::default_provider().install_default();
}

impl Socket {
    pub fn open(url: &str) -> Result<Socket, OpenError> {
        tls_ready();
        match tungstenite::connect(url) {
            Ok((ws, _)) => {
                let s = Socket { ws };
                s.timeout(Duration::from_millis(2));
                Ok(s)
            }
            Err(tungstenite::Error::Http(r)) => Err(OpenError::Refused(r.status().as_u16())),
            Err(e) => Err(OpenError::Unreachable(e.to_string())),
        }
    }

    fn tcp(&self) -> &TcpStream {
        match self.ws.get_ref() {
            MaybeTlsStream::Plain(s) => s,
            MaybeTlsStream::Rustls(s) => s.get_ref(),
            _ => unreachable!("no other kind of stream is made"),
        }
    }

    pub fn timeout(&self, d: Duration) {
        let _ = self.tcp().set_read_timeout(Some(d));
    }

    pub fn text(&mut self, s: &str) -> Result<(), String> {
        self.ws.send(Message::text(s.to_string())).map_err(|e| e.to_string())
    }

    pub fn binary(&mut self, b: Vec<u8>) -> Result<(), String> {
        self.ws.send(Message::binary(b)).map_err(|e| e.to_string())
    }

    /// the next message, or Nothing once the read timeout passes
    pub fn recv(&mut self) -> Result<Got, String> {
        match self.ws.read() {
            Ok(Message::Text(t)) => Ok(Got::Text(t.to_string())),
            Ok(Message::Binary(b)) => Ok(Got::Binary(b.to_vec())),
            Ok(Message::Close(f)) => Ok(match f {
                Some(f) => Got::Closed(f.code.into(), f.reason.to_string()),
                None => Got::Closed(1005, String::new()),
            }),
            Ok(_) => Ok(Got::Nothing),
            Err(tungstenite::Error::Io(e)) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {
                Ok(Got::Nothing)
            }
            Err(tungstenite::Error::ConnectionClosed) | Err(tungstenite::Error::AlreadyClosed) => Ok(Got::Closed(1006, String::new())),
            Err(e) => Err(e.to_string()),
        }
    }

    pub fn close(&mut self) {
        let _ = self.ws.close(None);
        let _ = self.ws.flush();
    }
}

/// The waits between tries when the relay can't be reached (capture.py's 2, 5, 10, 30 s).
pub const WAITS: [u64; 4] = [2, 5, 10, 30];
