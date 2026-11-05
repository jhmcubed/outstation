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

export function dinkReport(p: any): { type: string; text: string; at: number } | null {
  if (!p || typeof p !== "object" || !REPORTS.has(p.type)) return null;
  let text = "";
  const embeds = Array.isArray(p.embeds) ? p.embeds : [];
  if (embeds.length && embeds[0] && typeof embeds[0] === "object") {
    text = embeds[0].description || embeds[0].title || "";
  }
  text = text || p.content || "";
  const extra = p.extra && typeof p.extra === "object" ? p.extra : {};
  if (p.type === "DEATH" && (extra.isPvp || extra.pvp || extra.killerName)) text = "";
  return { type: p.type, text: Array.from(cleanDink(String(text))).slice(0, 200).join(""),
           at: Math.floor(Date.now() / 1000) };
}

// JSON as Python's json.dumps writes it (", " and ": ", everything past ASCII escaped), so a line from this
// relay reads exactly as one from relay.py
export function pyjson(v: unknown): string {
  if (v === null || v === undefined) return "null";
  if (typeof v === "string") {
    return JSON.stringify(v).replace(/[\u007f-￿]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));
  }
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (Array.isArray(v)) return "[" + v.map(pyjson).join(", ") + "]";
  if (typeof v === "object") {
    return "{" + Object.entries(v as Record<string, unknown>).filter(([, x]) => x !== undefined)
      .map(([k, x]) => pyjson(k) + ": " + pyjson(x)).join(", ") + "}";
  }
  return "null";
}
