//! The field station (the keeper's build: `cargo build --release --features station`, never a release).
//!
//! 11/06/2026, Jace: "rewrite the field station in rust so i have the same portal as everyone else + the rest of the
//! features of my special version of the portal ... and the rest of the field station features". ghillie's field
//! station was a tray launcher in Python (tools/launcher/launcher.py) beside the Python portal. This is it again, inside
//! the portal everyone else runs: the same window, capture, sound, chat and Dink, and in the tray, beside them, what the
//! station started and stopped. Nothing here is ghillie's own code: what it can start comes from the station's file,
//! ghillie's tools/launcher/launcher.json (`--station <file>`, else found up from where the exe is):
//!
//!   - **the services**: each with its light (● running, ○ not, · not on this computer) and, inside, start / stop, its
//!     page, its log. Something started elsewhere (Release Kit.bat) is seen by its port (Windows' table of listening
//!     ports) or its mutex, and can be opened but not stopped from here. Each runs hidden, its output in
//!     tools/launcher/out/<id>.log, from this checkout, the main one, or (an unshipped tool) a worktree.
//!   - **modes**: a set started together. "portal" in one means the portal itself: the relay on this PC (while the
//!     portal's relay is this PC's), the window, and going live.
//!   - **links**: pages and folders ({root}: the repo, {documents}: Documents).
//!   - **stop all I started**, and **close the station**: everything it started stopped, and the portal off air.
//!
//! Services marked `"only": "python"` (the Python portal, start.py) are the Python station's alone; `"only": "station"`
//! (the relay by itself) this one's alone.
//!
//! What it never does: open another program (it starts its own children and stops them with Windows' own taskkill),
//! press keys, read another program. tests/safety_check.py reads this file as it reads the rest of client/src.

use serde_json::Value;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum State { Mine, Elsewhere, Off, Missing }

impl State {
    pub fn light(self) -> &'static str {
        match self { State::Mine | State::Elsewhere => "●", State::Off => "○", State::Missing => "·" }
    }
}

pub struct Service {
    pub id: String,
    pub label: String,
    run: Vec<String>,
    pub find: String,
    port: Option<u16>,
    mutex: Option<String>,
    open: Option<String>,
    open_from_log: Option<String>,
    /// the checkout it runs from (None: not on this computer), and what to say about it (an unshipped worktree)
    pub cwd: Option<PathBuf>,
    pub note: String,
    child: Option<Child>,
    pub log: PathBuf,
}

impl Service {
    fn mine(&mut self) -> bool {
        match &mut self.child {
            Some(c) => matches!(c.try_wait(), Ok(None)),
            None => false,
        }
    }

    pub fn state(&mut self, ports: &[u16]) -> State {
        if self.cwd.is_none() { return State::Missing; }
        if self.mine() { return State::Mine; }
        if self.port.is_some_and(|p| ports.contains(&p)) || self.mutex.as_deref().is_some_and(mutex_held) {
            return State::Elsewhere;
        }
        State::Off
    }

    /// started hidden, its output to its log (begun again each start)
    pub fn start(&mut self, out: &Path) -> Result<(), String> {
        let Some(cwd) = self.cwd.clone() else { return Err(format!("{} isn't on this computer's checkouts", self.find)) };
        if self.run.is_empty() { return Err("nothing to run".into()); }
        let _ = std::fs::create_dir_all(out);
        let mut log = std::fs::File::create(&self.log).map_err(|e| format!("its log: {}", e))?;
        use std::io::Write;
        let _ = writeln!(log, "{}, started {} from {}\n", self.label, now_words(), cwd.display());
        let err = log.try_clone().map_err(|e| e.to_string())?;
        let mut cmd = Command::new(&self.run[0]);
        cmd.args(&self.run[1..]).current_dir(&cwd)
            .stdin(Stdio::null()).stdout(Stdio::from(log)).stderr(Stdio::from(err))
            .env("PYTHONUNBUFFERED", "1").env("PYTHONIOENCODING", "utf-8");
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        self.child = Some(cmd.spawn().map_err(|e| format!("{} wouldn't start: {}", self.label, e))?);
        Ok(())
    }

    /// what it started, and everything that started (taskkill's /T: py.exe's python under it too)
    pub fn stop(&mut self) {
        if let Some(mut c) = self.child.take() {
            if matches!(c.try_wait(), Ok(None)) {
                let mut k = Command::new("taskkill");
                k.args(["/T", "/F", "/PID", &c.id().to_string()]).stdout(Stdio::null()).stderr(Stdio::null());
                #[cfg(windows)]
                {
                    use std::os::windows::process::CommandExt;
                    k.creation_flags(CREATE_NO_WINDOW);
                }
                let _ = k.status();
                let end = Instant::now() + Duration::from_secs(5);
                while Instant::now() < end && matches!(c.try_wait(), Ok(None)) {
                    std::thread::sleep(Duration::from_millis(50));
                }
            }
        }
    }

    /// one of mine that ended by itself since it was last looked at: its exit code
    fn ended(&mut self) -> Option<Option<i32>> {
        let c = self.child.as_mut()?;
        match c.try_wait() {
            Ok(Some(status)) => {
                self.child = None;
                Some(status.code())
            }
            _ => None,
        }
    }

    /// its page: "open", or found in its log by "open_from_log" (read as words, then the address after them)
    pub fn page(&self) -> Option<String> {
        if let Some(o) = &self.open { return Some(o.clone()); }
        let pat = self.open_from_log.as_deref()?;
        let text = std::fs::read_to_string(&self.log).ok()?;
        page_in(&text, pat)
    }
}

/// launcher.json's "open_from_log" pattern, "<words>\s+(\S+)", without a regex engine: the words, then the first
/// thing after the spaces that follow them
pub fn page_in(text: &str, pattern: &str) -> Option<String> {
    let words = pattern.strip_suffix("\\s+(\\S+)")?.replace("\\", "");
    let at = text.find(&words)? + words.len();
    let rest = text[at..].trim_start();
    let end = rest.find(char::is_whitespace).unwrap_or(rest.len());
    (end > 0).then(|| rest[..end].to_string())
}

pub struct Link { pub label: String, pub target: String, pub ok: bool }

pub struct Mode { pub label: String, pub start: Vec<String> }

pub struct Station {
    pub out: PathBuf,
    pub services: Vec<Service>,
    pub modes: Vec<Mode>,
    pub links: Vec<Link>,
    /// what each service was when last looked at, and when that was
    pub states: Vec<State>,
    looked: Option<Instant>,
    /// the Python station is running too (it holds the same mutex)
    pub python_too: bool,
}

impl Station {
    /// The station's file: --station <file>, else tools/launcher/launcher.json up from the exe, else from here.
    pub fn find_file() -> Option<PathBuf> {
        let args: Vec<String> = std::env::args().collect();
        if let Some(p) = args.iter().position(|a| a == "--station").and_then(|i| args.get(i + 1)) {
            return Some(PathBuf::from(p));
        }
        let mut starts = Vec::new();
        if let Ok(exe) = std::env::current_exe() { starts.push(exe); }
        if let Ok(here) = std::env::current_dir() { starts.push(here); }
        for s in starts {
            for dir in s.ancestors() {
                let f = dir.join("tools").join("launcher").join("launcher.json");
                if f.exists() { return Some(f); }
                let f = dir.join("launcher.json");
                if f.exists() && dir.file_name().is_some_and(|n| n == "launcher") { return Some(f); }
            }
        }
        None
    }

    pub fn open(file: &Path) -> Result<Station, String> {
        let cfg: Value = serde_json::from_str(&std::fs::read_to_string(file).map_err(|e| format!("{}: {}", file.display(), e))?)
            .map_err(|e| format!("{}: {}", file.display(), e))?;
        let here = file.parent().unwrap_or(Path::new(".")).to_path_buf();
        // the repo: launcher.json is tools/launcher/launcher.json in it
        let root = here.parent().and_then(|p| p.parent()).map(|p| p.to_path_buf()).unwrap_or_else(|| here.clone());
        let out = here.join("out");
        let trees = checkouts(&root);
        let st = |v: &Value, k: &str| v.get(k).and_then(|x| x.as_str()).map(|s| s.to_string());
        let mut services = Vec::new();
        for s in cfg.get("services").and_then(|x| x.as_array()).cloned().unwrap_or_default() {
            if st(&s, "only").as_deref() == Some("python") { continue; }
            let id = st(&s, "id").unwrap_or_default();
            let find = st(&s, "find").unwrap_or_default();
            let (cwd, note) = trees.iter().find(|(p, _)| !find.is_empty() && p.join(&find).exists())
                .map(|(p, n)| (Some(p.clone()), n.clone())).unwrap_or((None, String::new()));
            services.push(Service {
                label: st(&s, "label").unwrap_or_else(|| id.clone()),
                run: s.get("run").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(|y| y.to_string())).collect()).unwrap_or_default(),
                port: s.get("port").and_then(|x| x.as_u64()).map(|p| p as u16),
                mutex: st(&s, "mutex"), open: st(&s, "open"), open_from_log: st(&s, "open_from_log"),
                log: out.join(format!("{}.log", id)),
                id, find, cwd, note, child: None,
            });
        }
        let modes = cfg.get("modes").and_then(|x| x.as_array()).cloned().unwrap_or_default().iter().map(|m| Mode {
            label: st(m, "label").unwrap_or_default(),
            start: m.get("start").and_then(|x| x.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(|y| y.to_string())).collect()).unwrap_or_default(),
        }).collect();
        let docs = std::env::var_os("USERPROFILE").map(|h| PathBuf::from(h).join("Documents")).unwrap_or_default();
        let links = cfg.get("links").and_then(|x| x.as_array()).cloned().unwrap_or_default().iter().map(|l| {
            let target = st(l, "open").unwrap_or_default()
                .replace("{root}", &root.display().to_string()).replace("{documents}", &docs.display().to_string());
            let ok = target.starts_with("http") || Path::new(&target).exists();
            Link { label: st(l, "label").unwrap_or_default(), target, ok }
        }).collect();
        let n = services.len();
        Ok(Station { out, services, modes, links, states: vec![State::Off; n], looked: None,
                     python_too: !crate::station::hold_mutex() })
    }

    /// Every service looked at again (at most every two seconds unless `now`): the states, and a note for each of mine
    /// that ended by itself.
    pub fn refresh(&mut self, now: bool) -> Vec<String> {
        if !now && self.looked.is_some_and(|t| t.elapsed() < Duration::from_secs(2)) { return Vec::new(); }
        self.looked = Some(Instant::now());
        let mut notes = Vec::new();
        for s in &mut self.services {
            if let Some(code) = s.ended() {
                notes.push(format!("{} stopped by itself{}: its log is in the tray's menu", s.label,
                                   code.map(|c| format!(" (code {})", c)).unwrap_or_default()));
            }
        }
        let ports = listening();
        self.states = self.services.iter_mut().map(|s| s.state(&ports)).collect();
        notes
    }

    pub fn index(&self, id: &str) -> Option<usize> {
        self.services.iter().position(|s| s.id == id)
    }

    pub fn start(&mut self, i: usize) -> Result<(), String> {
        let ports = listening();
        let out = self.out.clone();
        let s = &mut self.services[i];
        match s.state(&ports) {
            State::Off => s.start(&out),
            State::Missing => Err(format!("{} isn't on this computer's checkouts ({})", s.label, s.find)),
            _ => Ok(()),
        }
    }

    pub fn stop(&mut self, i: usize) {
        self.services[i].stop();
    }

    pub fn mine(&mut self) -> usize {
        let mut n = 0;
        for s in &mut self.services { if s.mine() { n += 1; } }
        n
    }

    pub fn stop_all(&mut self) {
        for s in &mut self.services { s.stop(); }
    }

    pub fn tooltip(&self) -> String {
        let on: Vec<String> = self.services.iter().zip(&self.states)
            .filter(|(_, st)| matches!(st, State::Mine | State::Elsewhere))
            .map(|(s, _)| s.label.split(" (").next().unwrap_or(&s.label).to_string()).collect();
        if on.is_empty() { "all quiet".into() } else { format!("on: {}", on.join(", ")) }
    }
}

/// This checkout, the main one, then the worktrees (an unshipped tool says which), newest first.
fn checkouts(root: &Path) -> Vec<(PathBuf, String)> {
    let mut out: Vec<(PathBuf, String)> = Vec::new();
    let mut add = |p: PathBuf, note: String| {
        let key = p.to_string_lossy().to_lowercase();
        if p.is_dir() && !out.iter().any(|(q, _)| q.to_string_lossy().to_lowercase() == key) { out.push((p, note)); }
    };
    add(root.to_path_buf(), String::new());
    let mut git = Command::new("git");
    git.args(["rev-parse", "--path-format=absolute", "--git-common-dir"]).current_dir(root).stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        git.creation_flags(CREATE_NO_WINDOW);
    }
    let common = git.output().ok().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_default();
    if let Some(main) = (!common.is_empty()).then(|| PathBuf::from(&common)).and_then(|c| c.parent().map(|p| p.to_path_buf())) {
        add(main.clone(), String::new());
        if let Ok(rd) = std::fs::read_dir(main.join(".claude").join("worktrees")) {
            let mut trees: Vec<(PathBuf, std::time::SystemTime)> = rd.flatten()
                .map(|e| (e.path(), e.metadata().and_then(|m| m.modified()).unwrap_or(std::time::UNIX_EPOCH))).collect();
            trees.sort_by(|a, b| b.1.cmp(&a.1));
            for (t, _) in trees {
                let name = t.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                add(t, format!("unshipped, from {}", name));
            }
        }
    }
    out
}

fn now_words() -> String {
    #[cfg(windows)]
    unsafe {
        let t = windows::Win32::System::SystemInformation::GetLocalTime();
        return format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute, t.wSecond);
    }
    #[allow(unreachable_code)]
    String::new()
}

/// Every TCP port something on this PC listens on (IPv4 and IPv6), from Windows' own table (a millisecond: connecting
/// to each port cost 0.15 s for every closed one).
#[cfg(windows)]
pub fn listening() -> Vec<u16> {
    use windows::Win32::NetworkManagement::IpHelper::{GetExtendedTcpTable, MIB_TCP6ROW_OWNER_PID, MIB_TCPROW_OWNER_PID,
                                                       TCP_TABLE_OWNER_PID_LISTENER};
    let mut ports = Vec::new();
    for (family, row) in [(2u32, std::mem::size_of::<MIB_TCPROW_OWNER_PID>()), (23u32, std::mem::size_of::<MIB_TCP6ROW_OWNER_PID>())] {
        let mut size = 0u32;
        unsafe { GetExtendedTcpTable(None, &mut size, false, family, TCP_TABLE_OWNER_PID_LISTENER, 0); }
        for _ in 0..3 {
            let mut buf = vec![0u8; size as usize + 4096];
            size = buf.len() as u32;
            let r = unsafe { GetExtendedTcpTable(Some(buf.as_mut_ptr() as *mut _), &mut size, false, family, TCP_TABLE_OWNER_PID_LISTENER, 0) };
            if r == 0 {
                let n = u32::from_ne_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
                for i in 0..n {
                    let at = 4 + i * row;
                    // the local port: IPv4's third field, IPv6's after its 16-byte address and scope (network byte order)
                    let off = if family == 2 { at + 8 } else { at + 20 };
                    if off + 4 > buf.len() { break; }
                    let p = u32::from_ne_bytes([buf[off], buf[off + 1], buf[off + 2], buf[off + 3]]) & 0xFFFF;
                    ports.push((((p & 0xFF) << 8) | (p >> 8)) as u16);
                }
                break;
            }
        }
    }
    ports
}

#[cfg(not(windows))]
pub fn listening() -> Vec<u16> { Vec::new() }

#[cfg(windows)]
fn mutex_held(name: &str) -> bool {
    use windows::Win32::System::Threading::{OpenMutexW, SYNCHRONIZATION_SYNCHRONIZE};
    let w = crate::win::wide(name);
    unsafe {
        match OpenMutexW(SYNCHRONIZATION_SYNCHRONIZE, false, windows::core::PCWSTR(w.as_ptr())) {
            Ok(h) => { let _ = windows::Win32::Foundation::CloseHandle(h); true }
            Err(_) => false,
        }
    }
}

#[cfg(not(windows))]
fn mutex_held(_name: &str) -> bool { false }

/// One station at a time, the Python one included (launcher.py's own mutex): false if another holds it already.
#[cfg(windows)]
fn hold_mutex() -> bool {
    use windows::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows::Win32::System::Threading::CreateMutexW;
    let name = crate::win::wide("Local\\ghillie-field-station");
    unsafe {
        let m = CreateMutexW(None, false, windows::core::PCWSTR(name.as_ptr()));
        let first = GetLastError() != ERROR_ALREADY_EXISTS;
        std::mem::forget(m);
        first
    }
}

#[cfg(not(windows))]
fn hold_mutex() -> bool { true }

/// A page, a folder or a file, opened as Explorer would (the browser, a folder window, the log's own program).
#[cfg(windows)]
pub fn open_target(target: &str) {
    use windows::Win32::UI::Shell::ShellExecuteW;
    use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
    let (verb, what) = (crate::win::wide("open"), crate::win::wide(target));
    unsafe {
        ShellExecuteW(None, windows::core::PCWSTR(verb.as_ptr()), windows::core::PCWSTR(what.as_ptr()), None, None, SW_SHOWNORMAL);
    }
}

#[cfg(not(windows))]
pub fn open_target(_target: &str) {}

// ---- the tray ----

/// The station's part of the tray's menu: a submenu for each service, modes, links, friends' portals and stopping.
pub struct Tray {
    pub services: Vec<ServiceItems>,
    pub modes: Vec<tray_icon::menu::MenuItem>,
    pub links: Vec<tray_icon::menu::MenuItem>,
    pub friends: tray_icon::menu::MenuItem,
    pub stop_all: tray_icon::menu::MenuItem,
}

pub struct ServiceItems {
    pub menu: tray_icon::menu::Submenu,
    pub elsewhere: tray_icon::menu::MenuItem,
    pub start: tray_icon::menu::MenuItem,
    pub stop: tray_icon::menu::MenuItem,
    pub page: tray_icon::menu::MenuItem,
    pub log: tray_icon::menu::MenuItem,
}

/// What a click in the station's part of the menu asks for.
pub enum Ask { Start(usize), Stop(usize), Page(usize), Log(usize), Mode(usize), Link(usize), Friends, StopAll }

impl Tray {
    pub fn build(st: &Station, menu: &tray_icon::menu::Menu) -> Tray {
        use tray_icon::menu::{MenuItem, PredefinedMenuItem, Submenu};
        let _ = menu.append(&MenuItem::new("⊂λ,ｍ⊙ field station", false, None));
        let mut services = Vec::new();
        for s in &st.services {
            let sub = Submenu::new(format!("○  {}", s.label), true);
            let it = ServiceItems {
                elsewhere: MenuItem::new("running, started elsewhere", false, None),
                start: MenuItem::new("start", true, None),
                stop: MenuItem::new("stop", false, None),
                page: MenuItem::new("open its page", false, None),
                log: MenuItem::new("its log", false, None),
                menu: sub,
            };
            let _ = it.menu.append_items(&[&it.start, &it.stop, &it.page, &it.log]);
            if s.cwd.is_none() {
                let _ = it.menu.append(&MenuItem::new(format!("not on this computer's checkouts ({})", s.find), false, None));
            } else if !s.note.is_empty() {
                let _ = it.menu.append_items(&[&PredefinedMenuItem::separator(), &MenuItem::new(&s.note, false, None)]);
            }
            let _ = menu.append(&it.menu);
            services.push(it);
        }
        let modes_menu = Submenu::new("modes", true);
        let modes: Vec<MenuItem> = st.modes.iter().map(|m| MenuItem::new(&m.label, true, None)).collect();
        for m in &modes { let _ = modes_menu.append(m); }
        let links_menu = Submenu::new("links", true);
        let links: Vec<MenuItem> = st.links.iter().map(|l| MenuItem::new(&l.label, l.ok, None)).collect();
        for l in &links { let _ = links_menu.append(l); }
        let friends = MenuItem::new("friends' portals…", true, None);
        let stop_all = MenuItem::new("stop all I started (0)", false, None);
        let _ = menu.append_items(&[&modes_menu, &links_menu, &friends, &stop_all]);
        Tray { services, modes, links, friends, stop_all }
    }

    /// The lights and what can be done, from the states last looked at.
    pub fn sync(&self, st: &mut Station) {
        let mine = st.mine();
        for (i, it) in self.services.iter().enumerate() {
            let state = st.states.get(i).copied().unwrap_or(State::Off);
            let s = &st.services[i];
            it.menu.set_text(format!("{}  {}", state.light(), s.label));
            it.start.set_enabled(state == State::Off);
            it.stop.set_enabled(state == State::Mine);
            it.page.set_enabled(matches!(state, State::Mine | State::Elsewhere) && s.page().is_some());
            it.log.set_enabled(s.log.exists());
            let shown = it.menu.items().iter().any(|x| x.id() == it.elsewhere.id());
            if state == State::Elsewhere && !shown {
                let _ = it.menu.prepend(&it.elsewhere);
            } else if state != State::Elsewhere && shown {
                let _ = it.menu.remove(&it.elsewhere);
            }
        }
        self.stop_all.set_text(format!("stop all I started ({})", mine));
        self.stop_all.set_enabled(mine > 0);
    }

    pub fn ask(&self, id: &tray_icon::menu::MenuId) -> Option<Ask> {
        for (i, it) in self.services.iter().enumerate() {
            if id == it.start.id() { return Some(Ask::Start(i)); }
            if id == it.stop.id() { return Some(Ask::Stop(i)); }
            if id == it.page.id() { return Some(Ask::Page(i)); }
            if id == it.log.id() { return Some(Ask::Log(i)); }
        }
        if let Some(i) = self.modes.iter().position(|m| id == m.id()) { return Some(Ask::Mode(i)); }
        if let Some(i) = self.links.iter().position(|m| id == m.id()) { return Some(Ask::Link(i)); }
        if id == self.friends.id() { return Some(Ask::Friends); }
        if id == self.stop_all.id() { return Some(Ask::StopAll); }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_page_is_found_in_a_log_as_launcher_json_says() {
        let log = "the portal\n\nwatch on this PC:  http://127.0.0.1:8787/?channel=sburb&code=x\nfor friends: ...";
        assert_eq!(page_in(log, "watch on this PC:\\s+(\\S+)").as_deref(), Some("http://127.0.0.1:8787/?channel=sburb&code=x"));
        assert_eq!(page_in(log, "nowhere:\\s+(\\S+)"), None);
        assert_eq!(page_in(log, "an odd (pattern)"), None);
    }

    #[test]
    fn the_station_file_reads_and_skips_the_python_stations_own() {
        let dir = std::env::temp_dir().join(format!("station-test-{}", std::process::id()));
        let launcher = dir.join("tools").join("launcher");
        std::fs::create_dir_all(&launcher).unwrap();
        std::fs::write(dir.join("serve.py"), "").unwrap();
        std::fs::write(launcher.join("launcher.json"), r#"{
            "services": [
              {"id": "portal", "label": "the portal", "run": ["py", "start.py"], "find": "start.py", "only": "python"},
              {"id": "preview", "label": "the site, on this PC", "run": ["py", "serve.py"], "find": "serve.py", "port": 8895, "open": "http://localhost:8895/"},
              {"id": "gone", "label": "not here", "run": ["py", "x.py"], "find": "x.py"}
            ],
            "modes": [{"label": "working on the site", "start": ["preview"]}],
            "links": [{"label": "the repo's folder", "open": "{root}"}, {"label": "nowhere", "open": "{root}/nope"}]
        }"#).unwrap();
        let st = Station::open(&launcher.join("launcher.json")).unwrap();
        assert_eq!(st.services.iter().map(|s| s.id.as_str()).collect::<Vec<_>>(), ["preview", "gone"]);
        assert!(st.services[0].cwd.is_some() && st.services[1].cwd.is_none());
        assert_eq!(st.services[0].page().as_deref(), Some("http://localhost:8895/"));
        assert_eq!(st.modes[0].start, ["preview"]);
        assert!(st.links[0].ok && !st.links[1].ok);
        let _ = std::fs::remove_dir_all(dir);
    }
}
