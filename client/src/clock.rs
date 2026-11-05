//! The portal's clock: frames and sound are stamped on the same one, so the page can line them up.

use std::sync::OnceLock;
use std::time::Instant;

static START: OnceLock<Instant> = OnceLock::new();

pub fn start() -> Instant {
    *START.get_or_init(Instant::now)
}

/// ms since the client started, wrapping at 32 bits (the protocol's 4-byte stamp)
pub fn ms_at(t: Instant) -> u32 {
    (t.saturating_duration_since(start()).as_millis() as u64 & 0xffff_ffff) as u32
}

pub fn secs_at(t: Instant) -> f64 {
    t.saturating_duration_since(start()).as_secs_f64()
}
