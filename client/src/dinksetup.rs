//! Dink set up as the listening post has it (11/06/2026, Jace: "do we have an easy way in the program for someone to
//! set up their portal live dink events and also keep their discord webhook with their current settings, just like
//! how i have it set up?"). His: Dink posts only to the portal, set generously, and the portal passes on to his
//! Discord what it got before (dink.rs, forward_wants), so the stream shows more than the Discord does.
//!
//! Done here from Dink's own export and import (pajlads/DinkPlugin, SettingsManager: `::dinkexport all` puts every
//! setting, webhook addresses too, on the clipboard as one JSON object; `::dinkimport` reads one back, replacing each
//! setting it names but adding to webhook lists rather than replacing them):
//!   1. `::dinkexport all` in RuneLite's chat.
//!   2. "read my Dink settings": the Discord rules made from the export (each notifier on or off as it is, loot's
//!      minimum value and allow list, kill counts' interval, first kills and personal bests, any notifier's own
//!      webhook), the export kept beside the settings (dink-before.json) to undo with, and an import for Dink put on
//!      the clipboard: the portal's address added to the primary webhook list (and to each notifier's own list that
//!      has one), every notifier the portal can show switched on, loot's minimum value no higher than the portal's,
//!      and every kill count sent.
//!   3. `::dinkimport` in RuneLite's chat.
//!   4. The one thing an import can't do: their Discord address deleted from Dink's lists (the portal sends to it now).
//! The cost, as for him: their Discord hears only while the portal's open.

use serde_json::{json, Map, Value};

/// Dink's report kinds, with the setting that switches each notifier on and its own webhook list (DinkPluginConfig).
pub const KINDS: &[(&str, &str, &str)] = &[
    ("LOOT", "lootEnabled", "lootWebhook"),
    ("KILL_COUNT", "killCountEnabled", "killCountWebhook"),
    ("PET", "petEnabled", "petWebhook"),
    ("COLLECTION", "collectionLogEnabled", "collectionWebhook"),
    ("LEVEL", "levelEnabled", "levelWebhook"),
    ("XP_MILESTONE", "levelEnabled", "levelWebhook"),
    ("QUEST", "questEnabled", "questWebhook"),
    ("CLUE", "clueEnabled", "clueWebhook"),
    ("DEATH", "deathEnabled", "deathWebhook"),
    ("COMBAT_ACHIEVEMENT", "combatTaskEnabled", "combatTaskWebhook"),
    ("ACHIEVEMENT_DIARY", "diaryEnabled", "diaryWebhook"),
    ("SLAYER", "slayerEnabled", "slayerWebhook"),
    ("SPEEDRUN", "speedrunEnabled", "speedrunWebhook"),
    ("BARBARIAN_ASSAULT_GAMBLE", "gambleEnabled", "gambleWebhook"),
    ("PLAYER_KILL", "pkEnabled", "pkWebhook"),
    ("GROUP_STORAGE", "groupStorageEnabled", "groupStorageWebhook"),
    ("GRAND_EXCHANGE", "notifyGrandExchange", "grandExchangeWebhook"),
    ("TRADE", "notifyTrades", "tradeWebhook"),
    ("CHAT", "notifyChat", "chatWebhook"),
    ("LEAGUES_AREA", "notifyLeagues", "leaguesWebhook"),
    ("LEAGUES_RELIC", "notifyLeagues", "leaguesWebhook"),
    ("LEAGUES_TASK", "notifyLeagues", "leaguesWebhook"),
    ("LEAGUES_MASTERY", "notifyLeagues", "leaguesWebhook"),
    ("EXTERNAL_PLUGIN", "notifyExternal", "externalWebhook"),
];

/// What reading the export made: the Discord rules, the import for Dink, and what to tell them.
#[derive(Debug)]
pub struct Plan {
    pub rules: Value,
    pub import: String,
    /// their Discord addresses (to delete from Dink's lists), and which of Dink's lists each is in
    pub discord: Vec<(String, Vec<String>)>,
    pub notes: Vec<String>,
}

fn lines(v: Option<&Value>) -> Vec<String> {
    v.and_then(|x| x.as_str()).unwrap_or("").lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect()
}

fn is_portal(url: &str) -> bool {
    url.starts_with("http://127.0.0.1:") // (the address the portal gives Dink: dink.rs)
}

fn num(v: Option<&Value>, or: i64) -> i64 {
    match v {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(or),
        Some(Value::String(s)) => s.trim().parse().unwrap_or(or),
        _ => or,
    }
}

fn flag(v: Option<&Value>, or: bool) -> bool {
    match v {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => s == "true",
        _ => or,
    }
}

/// Dink's export (the clipboard's text) into the plan. `portal`: the portal's own address for Dink (dink.rs's
/// listening address); `loot_min`: the portal's own "a stack worth at least" (dink ▸).
pub fn plan(export: &str, portal: &str, loot_min: u64) -> Result<Plan, String> {
    let v: Value = serde_json::from_str(export.trim()).map_err(|_| {
        "the clipboard doesn't hold Dink's settings: type ::dinkexport all in RuneLite's chat first, then press this again".to_string()
    })?;
    let e = v.as_object().ok_or("the clipboard doesn't hold Dink's settings")?;
    if !e.contains_key("discordWebhook") && !KINDS.iter().any(|(_, on, _)| e.contains_key(*on)) {
        return Err("that isn't Dink's export (no notifier settings in it): type ::dinkexport all in RuneLite's chat".into());
    }
    if !e.contains_key("discordWebhook") {
        return Err("the export has no webhook addresses: it needs the word all, ::dinkexport all".into());
    }
    let primary: Vec<String> = lines(e.get("discordWebhook")).into_iter().filter(|u| !is_portal(u)).collect();
    let mut discord: Vec<(String, Vec<String>)> = primary.iter().map(|u| (u.clone(), vec!["Primary Webhook URLs".to_string()])).collect();
    let mut notes = Vec::new();
    let mut forward = Map::new();
    let mut import = Map::new();
    import.insert("discordWebhook".into(), json!(portal));
    let mut overrides_seen = std::collections::HashSet::new();
    for (kind, on_key, hook_key) in KINDS {
        let on = flag(e.get(*on_key), false);
        let own: Vec<String> = lines(e.get(*hook_key)).into_iter().filter(|u| !is_portal(u)).collect();
        let to = if own.is_empty() { primary.clone() } else { own.clone() };
        if !own.is_empty() && overrides_seen.insert(*hook_key) {
            import.insert(hook_key.to_string(), json!(portal));
            let label = format!("{} Webhook Override", words(hook_key.trim_end_matches("Webhook")));
            for u in &own {
                match discord.iter_mut().find(|(x, _)| x == u) {
                    Some((_, where_)) => where_.push(label.clone()),
                    None => discord.push((u.clone(), vec![label.clone()])),
                }
            }
        }
        let mut rule = match (*kind, on) {
            (_, false) => Value::Bool(false),
            ("LOOT", true) => json!({
                "minValue": num(e.get("minLootValue"), 0),
                "allow": lines(e.get("lootItemAllowlist")).iter().map(|x| x.to_lowercase()).collect::<Vec<_>>(),
            }),
            ("KILL_COUNT", true) => json!({
                "every": num(e.get("killCountInterval"), 50).max(1),
                "lapsEvery": num(e.get("killCountIntervalLaps"), 1000).max(1),
                "first": flag(e.get("killCountInitial"), true),
                "personalBest": flag(e.get("killCountPB"), true),
            }),
            (_, true) => Value::Bool(true),
        };
        // (a notifier with its own webhook sends there and not to the primary list: Dink's own rule)
        if on && !own.is_empty() {
            if let Value::Bool(_) = rule { rule = json!({}); }
            rule["webhooks"] = json!(to);
        }
        forward.insert(kind.to_string(), rule);
        // the portal hears everything it can show
        if crate::settings::REPORTS.contains(kind) && !import.contains_key(*on_key) {
            import.insert(on_key.to_string(), json!(true));
        }
    }
    let their_min = num(e.get("minLootValue"), 0).max(0) as u64;
    if their_min > loot_min {
        import.insert("minLootValue".into(), json!(loot_min));
    }
    if num(e.get("killCountInterval"), 50) != 1 {
        import.insert("killCountInterval".into(), json!(1));
    }
    if num(e.get("lootRarityThreshold"), 0) > 0 {
        notes.push("Dink's \"Rarity Override\" for loot isn't copied: a rare drop under the loot value reaches Discord only if it's on the allow list".into());
    }
    if discord.is_empty() {
        notes.push("there was no Discord address in Dink: the portal hears Dink, nothing goes on to Discord".into());
    }
    let rules = json!({"discord": primary, "forward": Value::Object(forward), "_from": "Dink's ::dinkexport all (dinksetup.rs)"});
    Ok(Plan { rules, import: Value::Object(import).to_string(), discord, notes })
}

/// "killCount" as "Kill Count" (near enough Dink's own names for its boxes)
fn words(key: &str) -> String {
    let mut out = String::new();
    for (i, c) in key.chars().enumerate() {
        if i == 0 { out.extend(c.to_uppercase()); } else if c.is_uppercase() { out.push(' '); out.push(c); } else { out.push(c); }
    }
    out
}

/// A webhook address shown without its secret part (the token after the id): enough to find the line in Dink.
pub fn short(url: &str) -> String {
    match url.find("/webhooks/") {
        Some(i) => {
            let rest = &url[i + 10..];
            let id = rest.split('/').next().unwrap_or("");
            format!("{}{}/…", &url[..i + 10], id)
        }
        None => url.chars().take(40).collect::<String>() + "…",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HOOK: &str = "https://discord.com/api/webhooks/123456/secret-token";

    #[test]
    fn the_rules_are_what_discord_got_and_the_import_opens_dink_to_the_portal() {
        let export = json!({
            "discordWebhook": HOOK, "lootEnabled": true, "minLootValue": 1000000, "lootItemAllowlist": "Awakener's orb\nchromium ingot",
            "killCountEnabled": true, "killCountInterval": 25, "killCountIntervalLaps": 1000, "killCountInitial": true, "killCountPB": true,
            "petEnabled": true, "slayerEnabled": false, "levelEnabled": true, "levelInterval": 1,
            "collectionLogEnabled": true, "collectionWebhook": "https://discord.com/api/webhooks/777/other",
        }).to_string();
        let p = plan(&export, "http://127.0.0.1:8796/dink", 0).unwrap();
        let f = &p.rules["forward"];
        assert_eq!(p.rules["discord"], json!([HOOK]));
        assert_eq!(f["LOOT"], json!({"minValue": 1000000, "allow": ["awakener's orb", "chromium ingot"]}));
        assert_eq!(f["KILL_COUNT"], json!({"every": 25, "lapsEvery": 1000, "first": true, "personalBest": true}));
        assert_eq!((f["PET"].clone(), f["SLAYER"].clone(), f["LEVEL"].clone()), (json!(true), json!(false), json!(true)));
        assert_eq!(f["COLLECTION"], json!({"webhooks": ["https://discord.com/api/webhooks/777/other"]}));
        let i: Value = serde_json::from_str(&p.import).unwrap();
        assert_eq!(i["discordWebhook"], json!("http://127.0.0.1:8796/dink"));
        assert_eq!(i["collectionWebhook"], json!("http://127.0.0.1:8796/dink"));
        assert_eq!((i["slayerEnabled"].clone(), i["minLootValue"].clone(), i["killCountInterval"].clone()), (json!(true), json!(0), json!(1)));
        assert!(i.get("lootWebhook").is_none());
        assert_eq!(p.discord.len(), 2);
        assert_eq!(p.discord[1].1, vec!["Collection Webhook Override".to_string()]);
        // the rules work with the forwarding as it is
        let loot = |v: i64| json!({"type": "LOOT", "extra": {"items": [{"name": "x", "quantity": 1, "priceEach": v}]}});
        assert!(crate::dink::forward_wants(&p.rules, &loot(2_000_000)) && !crate::dink::forward_wants(&p.rules, &loot(5)));
        assert_eq!(crate::dink::forward_urls(&p.rules, "COLLECTION"), vec!["https://discord.com/api/webhooks/777/other".to_string()]);
        assert_eq!(crate::dink::forward_urls(&p.rules, "PET"), vec![HOOK.to_string()]);
    }

    #[test]
    fn what_isnt_an_export_is_said_so() {
        assert!(plan("portal1:abc", "http://127.0.0.1:8796/dink", 0).unwrap_err().contains("::dinkexport all"));
        assert!(plan("{\"lootEnabled\": true}", "http://127.0.0.1:8796/dink", 0).unwrap_err().contains("all"));
        assert_eq!(short(HOOK), "https://discord.com/api/webhooks/123456/…");
        // the portal's own address already in Dink isn't taken for a Discord one
        let p = plan(&json!({"discordWebhook": format!("{}\nhttp://127.0.0.1:8796/dink", HOOK), "petEnabled": true}).to_string(),
                     "http://127.0.0.1:8796/dink", 0).unwrap();
        assert_eq!(p.rules["discord"], json!([HOOK]));
    }
}
