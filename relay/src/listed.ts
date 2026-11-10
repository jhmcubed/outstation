// The open outstations' list as the Directory keeps it (GET /channels; index.ts's Directory.openPortals).
//
// Each channel tells the Directory its row whenever the row changes (channel.ts, tellListing), and the Directory asks a
// channel for its row (/_listing) when it hasn't heard from it in a while. Until 11/10/2026 the rows were in the
// Directory's memory only: it holds no sockets or timers, so Cloudflare puts it to sleep about 10 seconds after its last
// request, and the first read after each sleep asked every channel handed out again, waking each. With the globe reading
// the list every 20 s, that was nearly every read: at 20 channels about 90,000 Durable Object requests a day, near the
// Free plan's 100,000. Now the rows are in its storage ("listed", one value: one row written per change, however many
// channels changed), read back once a wake (Jace's decision, 11/10/2026).
//
// What's never written: following's "where" and the "activity" (and when each came). SAFETY.md promises both are in
// memory only, so they're kept in memory beside the stored row and nowhere else (keptRow names what's stored, rather
// than what's left out, so a field added to Listing later isn't stored till it's named here). After a wake they're
// back with the next telling, or the one ask a wake makes of a channel that can have them (due, below).
//
// When a channel is asked (due):
//   - its row is missing (never heard, or a new key);
//   - its row is older than the check: LISTING_CHECK_MS for a row that says live, LISTING_DARK_MS for any other, in case
//     a telling was lost or the channel went without saying (a deploy or a reset closes its sockets without telling);
//   - once a wake, a row that says live with the plugin connected: only such a channel can have a "where" or an
//     "activity", which no sleep keeps, and asking it puts them back on the very read after the wake (the globe's
//     follow dot would otherwise blink out at nearly every read). A channel that's live is awake anyway; a dark one,
//     or a live one without the plugin, isn't asked.
// A row's "seen" (when the Directory last heard from the channel, told or asked) is stored with it, so a sleep doesn't
// make every row look old; it's written when the row changes, or when it's moved a whole check since it was written.

import type { Listing } from "./channel";

// a row that says live is asked for again when it's older than this; any other, when older than LISTING_DARK_MS (each
// channel tells its own as it changes: these are only in case a telling was lost, or a channel went without saying)
export const LISTING_CHECK_MS = 5 * 60_000, LISTING_DARK_MS = 6 * LISTING_CHECK_MS;

/** Never stored: following's square, the activity, and when each came (SAFETY.md, "Following", "What you're doing"). */
type MemoryOnly = "where" | "whereAt" | "activity" | "activityAt";

/** What of a row is written to storage: everything a row has but MemoryOnly (the type refuses one of those here). */
export const KEPT_FIELDS: readonly Exclude<keyof Listing, MemoryOnly>[] =
  ["channel", "set", "code", "at", "live", "open", "static", "viewers", "title", "doing", "game", "home", "pending"];

/** A row as it's stored: only KEPT_FIELDS, copied one by one (never the row spread, which would carry the rest). */
export function keptRow(row: Listing): Listing {
  const out: Record<string, unknown> = {};
  for (const k of KEPT_FIELDS) if (row[k] !== undefined) out[k] = row[k];
  return out as unknown as Listing;
}

/** The list as the Directory keeps it in storage ("listed"): each row (keptRow's) and when it was last heard. */
export type SavedListed = { rows: { row: Listing; seen: number }[] };

type Entry = {
  row: Listing; // as told or answered: "where" and "activity" with it, in memory only
  seen: number; // when the Directory last heard from the channel
  heard: boolean; // told or asked since this wake
  line: string; // what's stored of the row (keptRow), without "at", as last written
  savedSeen: number; // the "seen" last written
};

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const lineOf = (row: Listing) => JSON.stringify({ ...keptRow(row), at: 0 });

export class ListedRows {
  rows = new Map<string, Entry>();
  dropped = false; // a row taken out since the list was last written

  /** The list from what was saved (none, or not its shape: an empty one). Nothing in it has been heard this wake. */
  static from(saved: unknown): ListedRows {
    const l = new ListedRows(), s = isObj(saved) ? saved : {};
    for (const r of Array.isArray(s.rows) ? s.rows : []) {
      if (!isObj(r) || !isObj(r.row) || typeof r.row.channel !== "string" || typeof r.seen !== "number") continue;
      const row = keptRow(r.row as unknown as Listing), line = lineOf(row);
      l.rows.set(row.channel, { row, seen: r.seen, heard: false, line, savedSeen: r.seen });
    }
    return l;
  }

  /** What the Directory writes to storage: every row as keptRow has it, and when it was heard. Marks it all written. */
  saved(): SavedListed {
    this.dropped = false;
    const rows = [];
    for (const e of this.rows.values()) {
      const row = keptRow(e.row);
      e.line = JSON.stringify({ ...row, at: 0 });
      e.savedSeen = e.seen;
      rows.push({ row, seen: e.seen });
    }
    return { rows };
  }

  /** A channel's row, told or answered (an older one than what's held, by its "at", is ignored). */
  heard(row: Listing, now = Date.now()) {
    const had = this.rows.get(row.channel);
    if (had && had.row.at > row.at) return;
    this.rows.set(row.channel, { row, seen: now, heard: true, line: had?.line ?? "", savedSeen: had?.savedSeen ?? 0 });
  }

  /** A channel's row taken out (a new key, taken back, or no longer handed out); whether it had one. */
  drop(name: string): boolean {
    if (!this.rows.delete(name)) return false;
    this.dropped = true;
    return true;
  }

  /** The channels, of those named, whose rows should be asked for now (the file's head, "When a channel is asked"). */
  due(names: string[], now: number, check = LISTING_CHECK_MS): string[] {
    return names.filter((n) => {
      const e = this.rows.get(n);
      if (!e) return true;
      if (now - e.seen > (e.row.live ? check : check * (LISTING_DARK_MS / LISTING_CHECK_MS))) return true;
      return !e.heard && !!e.row.live && !!e.row.game;
    });
  }

  /** Whether the list should be written: a row's stored part changed, one was taken out, or a row's been heard a whole
   *  check (its own: live or not) since its "seen" was written. */
  changed(check = LISTING_CHECK_MS): boolean {
    if (this.dropped) return true;
    for (const e of this.rows.values()) {
      if (lineOf(e.row) !== e.line) return true;
      if (e.seen - e.savedSeen >= (e.row.live ? check : check * (LISTING_DARK_MS / LISTING_CHECK_MS))) return true;
    }
    return false;
  }
}
