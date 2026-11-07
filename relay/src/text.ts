// Words: viewers' lines made safe, Dink's notifications made plain, and JSON written as relay.py writes it.

// control and direction characters (zero-width, bidi overrides and isolates)
const CONTROL = /[\x00-\x1f\x7f​-‏‪-‮⁦-⁩]/g;

// A viewer's (or the desk's) words: one line, no control or direction characters, at most `most` long.
export function oneLine(text: unknown, most: number): string {
  if (typeof text !== "string") return "";
  const s = text.replace(CONTROL, " ").replace(/\s+/g, " ").trim();
  return Array.from(s).slice(0, most).join("");
}

// Discord markdown to plain text
export function cleanDink(text: string): string {
  return text
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1") // [words](link) -> words
    .replace(/<a?:\w+:\d+>|<[@#!&]*\d+>|:\w+:/g, "") // emoji and mentions
    .replace(/[*_~`>|]+/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

// Dink's notification kinds that pass to viewers (none of them name another player,
// except a death in PvP, whose text is dropped)
export const REPORTS = new Set([
  "LEVEL", "XP_MILESTONE", "LOOT", "QUEST", "CLUE", "KILL_COUNT", "COLLECTION", "PET", "SLAYER", "SPEEDRUN",
  "COMBAT_ACHIEVEMENT", "ACHIEVEMENT_DIARY", "BARBARIAN_ASSAULT_GAMBLE", "DEATH", "LEAGUES_AREA", "LEAGUES_RELIC",
  "LEAGUES_TASK", "GRAND_EXCHANGE",
]);

const short = (v: unknown, most = 60): string | undefined =>
  typeof v === "string" || (typeof v === "number" && Number.isFinite(v)) ? Array.from(cleanDink(String(v))).slice(0, most).join("") : undefined;
const number = (v: unknown): number | undefined => {
  if (typeof v === "boolean" || v === null || v === undefined) return undefined;
  const n = typeof v === "number" ? v : parseInt(String(v));
  return Number.isFinite(n) ? Math.trunc(n) : undefined;
};

// The numbers a report carries for the portal's readings: only fields that can't name another player. Never "party",
// a player kill's source, or a dropper. (relay-py's report_data, the same.)
export function reportData(kind: string, extra: any): Record<string, unknown> {
  let d: Record<string, unknown> = {};
  if (kind === "KILL_COUNT") {
    d = { boss: short(extra.boss), count: number(extra.count), pb: !!extra.isPersonalBest };
  } else if (kind === "LEVEL") {
    const skills = extra.levelledSkills && typeof extra.levelledSkills === "object" ? extra.levelledSkills : {};
    const out: Record<string, number> = {};
    for (const [k, v] of Object.entries(skills).slice(0, 24)) {
      const name = short(k, 20), lv = number(v);
      if (name && lv !== undefined) out[name] = lv;
    }
    d.skills = out;
    const every = extra.allSkills && typeof extra.allSkills === "object" ? Object.values(extra.allSkills).map(number) : [];
    if (every.length && every.every((x) => x !== undefined)) d.total = (every as number[]).reduce((a, b) => a + b, 0);
    if (extra.combatLevel && typeof extra.combatLevel === "object" && number(extra.combatLevel.value) !== undefined) {
      d.combat = number(extra.combatLevel.value);
    }
  } else if (kind === "LOOT") {
    const items = (Array.isArray(extra.items) ? extra.items : []).filter((i: any) => i && typeof i === "object").slice(0, 12);
    // (each item's id too, for its picture: RuneLite's icons go by id; 11/06/2026)
    const list = items.filter((i: any) => short(i.name)).map((i: any) => {
      const it: any = { name: short(i.name), quantity: number(i.quantity) || 0, price: number(i.priceEach) || 0 };
      if (number(i.id) !== undefined) it.id = number(i.id);
      return it;
    });
    d.items = list;
    d.value = list.reduce((s: number, i: any) => s + i.quantity * i.price, 0);
    if (String(extra.category || "").toUpperCase() !== "PLAYER") d.source = short(extra.source);
  } else if (kind === "COLLECTION") {
    d = { item: short(extra.itemName), id: number(extra.itemId), done: number(extra.completedEntries), total: number(extra.totalEntries) };
  } else if (kind === "PET") {
    d = { pet: short(extra.petName), duplicate: !!extra.duplicate, milestone: short(extra.milestone) };
  } else if (kind === "QUEST") {
    d = { quest: short(extra.questName), done: number(extra.completedQuests), total: number(extra.totalQuests) };
  } else if (kind === "CLUE") {
    d = { tier: short(extra.clueType), count: number(extra.numberCompleted) };
  } else if (kind === "SLAYER") {
    d = { task: short(extra.slayerTask), done: number(extra.slayerCompleted), points: number(extra.slayerPoints) };
  } else if (kind === "COMBAT_ACHIEVEMENT") {
    d = { tier: short(extra.tier), task: short(extra.task) };
  } else if (kind === "ACHIEVEMENT_DIARY") {
    d = { area: short(extra.area), tier: short(extra.difficulty) };
  }
  for (const k of Object.keys(d)) {
    const v = d[k];
    if (v === undefined || v === null || (Array.isArray(v) && !v.length) || (typeof v === "object" && !Array.isArray(v) && !Object.keys(v as object).length)) delete d[k];
  }
  return d;
}

export function dinkReport(p: any): { type: string; text: string; at: number; data?: Record<string, unknown> } | null {
  if (!p || typeof p !== "object" || !REPORTS.has(p.type)) return null;
  let text = "";
  const embeds = Array.isArray(p.embeds) ? p.embeds : [];
  if (embeds.length && embeds[0] && typeof embeds[0] === "object") {
    text = embeds[0].description || embeds[0].title || "";
  }
  text = text || p.content || "";
  const extra = p.extra && typeof p.extra === "object" ? p.extra : {};
  if (p.type === "DEATH" && (extra.isPvp || extra.pvp || extra.killerName)) text = "";
  if (p.type === "LOOT" && String(extra.category || "").toUpperCase() === "PLAYER") text = ""; // (it names the player)
  const r: { type: string; text: string; at: number; data?: Record<string, unknown> } =
    { type: p.type, text: Array.from(cleanDink(String(text))).slice(0, 200).join(""), at: Math.floor(Date.now() / 1000) };
  const data = reportData(p.type, extra);
  if (Object.keys(data).length) r.data = data;
  return r;
}

// JSON as Python's json.dumps writes it (", " and ": ", everything past ASCII escaped), so a line from this
// relay reads exactly as one from relay.py. (11/07/2026: the same lines, written about 2.5 times as fast: plain
// loops in place of entries, filter, map and join, and the escaping only for words that need it. A status line went
// from 7 µs to 3; one went to each viewer in line for a seat every time anyone came or went.)
const PAST_ASCII = /[\u007f-￿]/, PAST_ASCII_ALL = /[\u007f-￿]/g;
export function pyjson(v: unknown): string {
  if (v === null || v === undefined) return "null";
  if (typeof v === "string") {
    const s = JSON.stringify(v);
    return PAST_ASCII.test(s) ? s.replace(PAST_ASCII_ALL, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0")) : s;
  }
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (Array.isArray(v)) {
    let out = "[";
    for (let i = 0; i < v.length; i++) out += (i ? ", " : "") + pyjson(v[i]);
    return out + "]";
  }
  if (typeof v === "object") {
    let out = "{", first = true;
    for (const k of Object.keys(v as object)) {
      const x = (v as Record<string, unknown>)[k];
      if (x === undefined) continue;
      out += (first ? "" : ", ") + pyjson(k) + ": " + pyjson(x);
      first = false;
    }
    return out + "}";
  }
  return "null";
}

// The attach code (the station key, in the client's words): "outstation1:" and base64url JSON {"relay", "channel",
// "key"}, which the client takes in one paste. The relay's own address is the one the request came to (wss:// on
// Cloudflare, ws:// under wrangler dev). (Here since 11/07/2026, so a channel's invites make one too: channel.ts.)
export function relayOf(requestUrl: string): string {
  const u = new URL(requestUrl);
  return (u.protocol === "https:" ? "wss://" : "ws://") + u.host;
}
export function attachCode(relay: string, channel: string, key: string): string {
  const json = JSON.stringify({ relay, channel, key });
  const b64 = btoa(String.fromCharCode(...new TextEncoder().encode(json)));
  return "outstation1:" + b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
