//! The friends list (11/06/2026, Jace: "a friends list that lets you add players to a shortlist in your client to see
//! if they are online/live or not straight from the client. allows you to click their name and go straight to their
//! portal/outside page", and "model it after the osrs friends list").
//!
//! As the game's own list: names down the left in the order they were added, each one's state on the right (live in
//! lime, off air in pink, no portal in grey), "add friend" and "del friend" with a prompt for the name, at most 200,
//! and a line in the chat when one goes live or off air ("mossfern has gone live.", as the game says "has logged
//! in."). A click on a name opens their Outside page in the browser; a right-click offers it and "delete".
//!
//! What it knows comes from the relay's open portals (`GET /channels`, docs/protocol.md): a channel there is live or
//! off air; one not there has no portal, or a friends-only one (a watch code). It's read every 20 s while the list is
//! open and every minute otherwise, and only while the list has someone on it. Nothing is sent but that request.

use crate::settings::Settings;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// as many as the game's own list holds
pub const MOST: usize = 200;

#[derive(Clone, Debug, PartialEq)]
pub struct Seen { pub live: bool, pub open: bool, pub static_on: bool, pub viewers: u64, pub title: String }

/// A friend's state: live (and how), off air, no portal (or a friends-only one), or not read yet.
#[derive(Clone, Debug, PartialEq)]
pub enum State { Live(Seen), OffAir, NoPortal, Unknown }

/// Their Outside page (where a click on their name goes).
pub fn page(channel: &str) -> String {
    format!("https://ghillie.wiki/outside?player={}", channel)
}

/// The relay to read the open portals from: the one this portal's attached to, else the listening post's.
fn relay_base(s: &Settings) -> String {
    if !s.relay.trim().is_empty() { crate::ask::http_base(&s.relay) } else { crate::ask::http_base(crate::ask::HOME_RELAY) }
}

/// The same relay as a socket's address, for a peek at a friend's portal (feed.rs, Peek).
pub fn relay_ws(s: &Settings) -> String {
    let b = relay_base(s);
    if let Some(x) = b.strip_prefix("https://") { format!("wss://{}", x) }
    else if let Some(x) = b.strip_prefix("http://") { format!("ws://{}", x) }
    else { b }
}

fn read(base: &str) -> Result<HashMap<String, Seen>, String> {
    if base.is_empty() { return Err("not attached yet: the list reads the relay your portal's attached to".into()); }
    let mut r = ureq::get(&format!("{}/channels", base)).header("User-Agent", "outside-portal").call()
        .map_err(|e| format!("the relay is out of reach ({})", e))?;
    let v: Value = serde_json::from_str(&r.body_mut().read_to_string().unwrap_or_default()).unwrap_or(Value::Null);
    let mut out = HashMap::new();
    for c in v.get("channels").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
        let Some(ch) = c.get("channel").and_then(|x| x.as_str()) else { continue };
        let b = |k: &str| c.get(k).and_then(|x| x.as_bool()).unwrap_or(false);
        out.insert(ch.to_string(), Seen { live: b("live"), open: b("open"), static_on: b("static"),
                                           viewers: c.get("viewers").and_then(|x| x.as_u64()).unwrap_or(0),
                                           title: c.get("title").and_then(|x| x.as_str()).unwrap_or("").to_string() });
    }
    Ok(out)
}

pub struct FriendList {
    seen: Arc<Mutex<Option<Result<HashMap<String, Seen>, String>>>>,
    busy: Arc<Mutex<bool>>,
    read_at: Option<Instant>,
    /// who was live at the last read (None: not read yet, so the first read tells nothing)
    was_live: Option<HashSet<String>>,
    /// adding (Some(true)) or deleting (Some(false)) a friend: the prompt is open
    pub prompt: Option<bool>,
    pub typed: String,
}

impl FriendList {
    pub fn new() -> FriendList {
        FriendList { seen: Arc::new(Mutex::new(None)), busy: Arc::new(Mutex::new(false)), read_at: None, was_live: None,
                     prompt: None, typed: String::new() }
    }

    /// Read the open portals again when it's time (now, if `now`), off the window's thread.
    pub fn tend(&mut self, s: &Settings, open: bool, now: bool, wake: impl Fn() + Send + 'static) {
        if s.friends.is_empty() || *self.busy.lock().unwrap() { return; }
        let every = Duration::from_secs(if open { 20 } else { 60 });
        if !now && self.read_at.is_some_and(|t| t.elapsed() < every) { return; }
        self.read_at = Some(Instant::now());
        let (seen, busy, base) = (self.seen.clone(), self.busy.clone(), relay_base(s));
        *busy.lock().unwrap() = true;
        std::thread::spawn(move || {
            let got = read(&base);
            *seen.lock().unwrap() = Some(got);
            *busy.lock().unwrap() = false;
            wake();
        });
    }

    /// A friend's state, by their name as it's kept.
    pub fn state(&self, name: &str) -> State {
        let Ok(ch) = crate::ask::channel_of(name) else { return State::NoPortal };
        match &*self.seen.lock().unwrap() {
            Some(Ok(m)) => match m.get(&ch) {
                Some(x) if x.live => State::Live(x.clone()),
                Some(_) => State::OffAir,
                None => State::NoPortal,
            },
            _ => State::Unknown,
        }
    }

    /// Why the list couldn't be read, if it couldn't.
    pub fn trouble(&self) -> Option<String> {
        match &*self.seen.lock().unwrap() { Some(Err(e)) => Some(e.clone()), _ => None }
    }

    /// Who went live (true) or off air (false) since the last read, as their names are kept.
    pub fn changes(&mut self, s: &Settings) -> Vec<(String, bool)> {
        let live: HashSet<String> = match &*self.seen.lock().unwrap() {
            Some(Ok(m)) => s.friends.iter().filter(|n| crate::ask::channel_of(n).ok().and_then(|c| m.get(&c)).is_some_and(|x| x.live)).cloned().collect(),
            _ => return Vec::new(),
        };
        let mut out = Vec::new();
        if let Some(was) = &self.was_live {
            for n in &s.friends {
                if live.contains(n) && !was.contains(n) { out.push((n.clone(), true)); }
                if !live.contains(n) && was.contains(n) { out.push((n.clone(), false)); }
            }
        }
        self.was_live = Some(live);
        out
    }

    pub fn live_count(&self, s: &Settings) -> usize {
        s.friends.iter().filter(|n| matches!(self.state(n), State::Live(_))).count()
    }
}

/// A change as the chat says it, as the game says "has logged in.": "<name> has gone live." or "<name> has gone off air."
pub fn line(c: &(String, bool)) -> String {
    format!("{} has gone {}.", c.0, if c.1 { "live" } else { "off air" })
}

/// Why a name can't be added (the game's own words, near enough), or the name as it's kept.
pub fn check_add(s: &Settings, typed: &str) -> Result<String, String> {
    let name = typed.split_whitespace().collect::<Vec<_>>().join(" ");
    let ch = crate::ask::channel_of(&name)?;
    if !s.channel.is_empty() && ch == s.channel { return Err("you can't add yourself to your own friends list.".into()); }
    if s.friends.iter().any(|f| crate::ask::channel_of(f).ok().as_deref() == Some(ch.as_str())) {
        return Err(format!("{} is already on your friends list.", name));
    }
    if s.friends.len() >= MOST { return Err(format!("your friends list is full ({} at most).", MOST)); }
    Ok(name)
}

/// The friend a typed name means, for deleting (matched as a channel, so "moss fern" finds "Moss Fern").
pub fn find(s: &Settings, typed: &str) -> Option<String> {
    let ch = crate::ask::channel_of(typed).ok()?;
    s.friends.iter().find(|f| crate::ask::channel_of(f).ok().as_deref() == Some(ch.as_str())).cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adding_as_the_game_does() {
        let mut s = Settings::default();
        s.channel = "lichenring".into();
        s.friends = vec!["Moss Fern".into()];
        assert_eq!(check_add(&s, "  Birch   Bark ").unwrap(), "Birch Bark");
        assert!(check_add(&s, "moss_fern").unwrap_err().contains("already on your friends list"));
        assert!(check_add(&s, "Lichen Ring").unwrap_err().contains("yourself"));
        assert!(check_add(&s, "no/slash").is_err());
        assert_eq!(find(&s, "MOSS-FERN").as_deref(), Some("Moss Fern"));
        assert_eq!(page("mossfern"), "https://ghillie.wiki/outside?player=mossfern");
    }

    #[test]
    fn the_chat_hears_who_went_live_after_the_first_read() {
        let mut s = Settings::default();
        s.friends = vec!["Moss Fern".into(), "Birch Bark".into()];
        let mut f = FriendList::new();
        let seen = |live: bool| Seen { live, open: live, static_on: false, viewers: 1, title: String::new() };
        *f.seen.lock().unwrap() = Some(Ok(HashMap::from([("mossfern".to_string(), seen(true))])));
        assert!(f.changes(&s).is_empty()); // (the first read tells nothing)
        *f.seen.lock().unwrap() = Some(Ok(HashMap::from([("mossfern".to_string(), seen(false)), ("birchbark".to_string(), seen(true))])));
        assert_eq!(f.changes(&s), vec![("Moss Fern".to_string(), false), ("Birch Bark".to_string(), true)]);
        assert_eq!(line(&("Birch Bark".to_string(), true)), "Birch Bark has gone live.");
        assert_eq!(line(&("Moss Fern".to_string(), false)), "Moss Fern has gone off air.");
        assert_eq!(f.state("Birch Bark"), State::Live(seen(true)));
        assert_eq!(f.state("Moss Fern"), State::OffAir);
        assert_eq!(f.state("Nobody Here"), State::NoPortal);
    }
}
