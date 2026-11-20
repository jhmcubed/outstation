// One channel of the portal: its transmitter, its viewers, its desks (the chat window) and its sound.
//
// The same protocol as relay-py/relay.py (docs/protocol.md), as a Durable Object: one object per channel,
// using Cloudflare's WebSocket Hibernation, so a channel with only viewers and the chat waiting (off air)
// sleeps between messages and costs almost nothing. A sleeping object forgets what's in memory, so what
// must outlive a sleep (the key's hash, the watch code, the chat so far, the poll, the blocks, the reports)
// is in the object's own storage; each socket's own state is in its attachment. While a channel is live its
// frames keep it awake, so the last frame lives in memory only.
//
// The game (11/07/2026; game.ts, docs/protocol.md "The game"): the Outside plugin's socket (/game/<channel>?key=), one at
// a time, its fields kept and what changed passed to seated viewers, lookers and desks; saved at most once a minute while
// messages come, and as it closes, so a page shows the last of it while the player's away.
//
// The Array's events (11/08/2026; array.ts, docs/protocol.md "The Array's events"): what each field report adds (a kill,
// a level, a pet, a big drop, a log slot, a clue), told to the Directory only while the channel's live and listed.
//
// Settling (11/08/2026) went on 11/10/2026 (docs/protocol.md, "Settling"): a meta saved while it was here may still hold
// "home", "homeAt" and "settle"; they're dropped as the meta's read (the constructor), and gone from storage at its next
// save.
//
// The globe's from the plugin (11/08/2026; game.ts): following's square (and its world), and what they're doing, held
// in memory for the channel's row of GET /channels; and the shooting stars they find, passed to the Directory's list
// (stars.ts), which names the first listed outstation to find each (11/09/2026).
//
// Your year (11/10/2026; docs/record.md): the owner's own "where", "activity" and Array events go to this channel's
// desks, and nowhere else, as {"mine": {...}} lines (tellMine), so the app can keep its record; and, with the plugin's
// "Record my exact path for my year" on, "here", the exact tile, passed on at once and never kept (takeHere). The record
// itself is kept by record.ts's Records, never here.

import { DurableObject } from "cloudflare:workers";
import { aetherText, attachCode, dinkReport, dinkSafe, oneLine, pyjson, relayOf } from "./text";
import { mendNotes } from "./mends";
import { type Clip, fileKey, json, newId, readUpload, serveFile, trim, ID, KEPT_MOST } from "./clips";
import { type Fight, FIGHT_ID, FIGHTS_AN_HOUR, fightKey, fightNumbers, newFightId, readFight, trimFights } from "./fights";
import { nameFits, claimsFrom, judge, noteLook, noteSaid, readXp, reported, type Seen, WITNESS_LOOK, WITNESS_WAITS, type Xp } from "./witness";
import { USER_AGENT } from "./lookup";
import { type Activity, type Fields, GameState, gameActivity, gameFields, gameHere, gameStar, gameWhere, GAME_A_SECOND,
         GAME_MESSAGE, GAME_POSTS_A_MINUTE, gamePresence, type Here, HERE_EVERY_MS, type Presence, PRESENCE_FRESH_MS,
         SNAP_CLEARS, SNAP_FIELDS, type StarReport, type Where, WHERE_EVERY_MS } from "./game";
import { arrayEvents, snapshotEvents } from "./array";
import { ANIM, GEAR, KEEP_AN_HOUR, KEEP_MOST, keepShape, LIVE_MOST, MOTIONS_MOST, MOTION_NAME } from "./keep";
import { type Emote, EMOTE_BYTES, EMOTE_NAME, EMOTES_AN_HOUR, EMOTES_BYTES, EMOTES_MOST, emoteKey, emoteKind, EMOTE_TYPES } from "./emotes";

export interface Env {
  CHANNEL: DurableObjectNamespace<Channel>;
  DIRECTORY: DurableObjectNamespace;
  AETHER: DurableObjectNamespace; // the aether (aether.ts, 11/07/2026): one room for every outstation and visitor
  RECORD?: DurableObjectNamespace; // your year (record.ts, 11/10/2026): each channel's encrypted record and public summary
  LOOKUPS?: DurableObjectNamespace; // the lookups (lookup.ts, lookups.ts, 11/10/2026): Wise Old Man, RuneProfile and the icons, kept and shared
  WOM_UPDATE_EVERY_SECONDS?: string; // (tests) how often each of the Array's players is updated on Wise Old Man (index.ts, womTick)
  WOM_API_KEY?: string; // Wise Old Man's API key (11/11/2026): a secret (`wrangler secret put WOM_API_KEY`), sent only to api.wiseoldman.net (lookup.ts)
  LOOKUP_UPSTREAM?: string; // (tests) the lookups' sources all fetched under this address instead of the real hosts (lookup.ts's want)
  WITNESS_WAIT_S?: string; // (tests) the wait before the hiscores are asked again after a logout, every time (witness.ts's WITNESS_WAITS)
  WITNESS_LOOK_S?: string; // (tests) the wait before a look while logged in (witness.ts's WITNESS_LOOK)
  NOTES_PAGE?: string; // (tests) the register sends and pages this many notes (11/17/2026)
  RECORD_CHUNKS?: string; // (tests) a channel's record chunks at most (record.ts's RECORD_CHUNKS: 2000)
  ADMIN_TOKEN?: string;
  OPEN_CLAIM?: string; // "1": the first transmitter claims a channel, as relay.py does (local tests only)
  MAX_VIEWERS?: string; // a ceiling on any channel's viewers ("0" or unset: none beyond the channel's own cap)
  MIN_CLIENT?: string; // the oldest outstation client let in (its v=); older ones are told to update (4026)
  ASKS_AN_HOUR?: string; // (tests) requests for a portal from one address in an hour (index.ts's ASKS_AN_HOUR)
  REPORTS_AN_HOUR?: string; // (tests) anomaly reports from one address in an hour (index.ts's REPORTS_AN_HOUR)
  POLL_MIN_SECONDS?: string; // (tests: a poll that ends quickly)
  POLL_KEPT_SECONDS?: string;
  CLIPS?: R2Bucket; // the clips on players' Outside pages (clips.ts); unbound, the relay takes none
  CLIPS_MOST?: string; // a channel's clips at most (20)
  CLIPS_DAYS?: string; // and for how long (30 days)
  CLIPS_AN_HOUR?: string; // uploads a channel may make in an hour (30)
  FIGHTS?: string; // recorded fights on players' pages (fights.ts): "on", or off (every /fights route 404s)
  FIGHTS_PUBLIC?: string; // "1": a new fight is shown to anyone unless the player says otherwise; else kept to themselves
  FIGHTS_MOST?: string; // a page's fights at most (20)
  FIGHTS_DAYS?: string; // and for how long (30 days; 0: until taken down)
  PRESENCE_FRESH_SECONDS?: string; // (tests) how long the Hub's plugin counts as here after its last POST (game.ts's PRESENCE_FRESH_MS: 70 minutes)
  WHERE_FRESH_SECONDS?: string; // (tests) how long a "where" or an "activity" shows on GET /channels after it came (game.ts's WHERE_FRESH_MS: 240)
  ALMANAC_UPSTREAM?: string; // (tests) the almanac's sources all fetched under this address instead of the real hosts (almanac.ts's urls)
  TEST_FORGET?: string; // (tests) "1": POST /test/forget puts the Directory to sleep (index.ts), to see what its storage brings back;
                        // GET/POST /test/listed/<channel> shows what it stores of a row on the open list, and how often it's asked
  LISTING_CHECK_SECONDS?: string; // (tests) how old a live row on the open list may be before it's asked for again (listed.ts's LISTING_CHECK_MS: 300)
}

const MAX_MESSAGE = 512 * 1024; // a frame is 10-70 KB; anything bigger is thrown away
const VIEWER_TEXT = 2048; // anything longer from a viewer is thrown away unread
const CHAT_KEPT = 50;
const CHAT_SENT = 50;
const CHAT_PAGE = 50;
// the register (11/17/2026, Jace: "build it and call it the register"; then "yes, separate them": the page's guestbook,
// its notes kept apart from the chat's lines): NOTES_KEPT kept; someone just come gets the last NOTES_SENT, older ones
// as asked (GET /register/<channel>?before=<id>, NOTES_PAGE at a time; the tests' NOTES_PAGE sets both); NOTE_BURST a
// socket's notes in NOTE_WINDOW at most
const NOTES_KEPT = 500, NOTES_SENT = 50, NOTES_PAGE = 50, NOTE_BURST = 3, NOTE_WINDOW = 60_000;
const SAY_MAX = 300, SAY_BURST = 20, SAY_WINDOW = 10_000;
const NAME_MAX = 20;
const TITLE_MAX = 100; // the broadcast's title (11/06/2026, Jace: "the option to title broadcast")
const PING_EVERY = 500;
const POLL_OPTIONS = [2, 6], POLL_SECONDS = [10, 900];
const POLL_KEPT = 90_000;
const SOUND_HOLD = 500; // the capture's hold-back: a pause takes the last moment's sound back too
const REPORTS_KEPT = 8;
const THUMB_FRESH = 3000; // a small frame this recent, and lookers get those instead of the full ones
const LOOK_EVERY = 500; // a looker (the listening post's receiver) gets a frame at most this often
const SIGNAL_EVERY = 80; // a viewer's ✼: as often as a hand can press, a dozen a second at most (the flood guard)
const GUESS_MIN_VALUE = 1_000_000; // a drop from the guessed boss this big ends a guess round by itself
const GUESS_KEPT = 120_000; // a decided round's result stays this long
const WRONG_KEYS = 10; // wrong keys from one address in a minute, then refused for a minute
// tending the page's chat from the page itself (11/16/2026, Jace: "There is currently no way for someone who doesn't
// have the outstation to remove comments from people off of their profile"): a token the plugin makes and hands the
// relay with its key (POST /tend/<channel>?key=), good for TEND_MS, TENDS_MOST at once (the oldest goes); the page opens
// /desk/<channel>?tend=<token>, a socket that hears only the chat and the bans and may only take lines back, block and ban
const TEND_MS = 12 * 3_600_000, TENDS_MOST = 4, TEND_TOKEN = /^[A-Za-z0-9_-]{43}$/;
// messages between portals (11/06/2026, Jace: "allow for messaging friends directly"): to a channel's desk, or held
// for it till its portal next opens (relay.py's DMS_KEPT, DMS_DAYS, DMS_A_MINUTE)
const DMS_KEPT = 20, DMS_DAYS = 7, DMS_A_MINUTE = 20;
interface Dm { from: string; name: string; text: string; at: number }
// invites (11/07/2026, Jace: "each of my friends being able to pass out a few keys so that way the platform propagates
// easier", "people earning more invites through streaming"): an outstation hands out channels of its own, this many to
// begin with and one more every INVITE_EVERY_S on air; INVITES_AN_HOUR posts an hour at most (relay.py's, the same)
export const INVITES_START = 3, INVITE_EVERY_S = 36_000, INVITES_AN_HOUR = 5;
// a channel claimed by the Outside plugin itself (11/15/2026, docs/protocol.md "Claiming a name"): at most this many a
// day from one address, and this many a day in all, so names can't be taken in bulk
export const CLAIMS_AN_ADDRESS = 3, CLAIMS_A_DAY = 200;
// taking a name back (11/15/2026): challengers at once on one name, and how long one's kept
export const CHALLENGERS = 5, CHALLENGE_DAYS = 7;
// a channel asked for or invited: a player's name as the page has it (lower case, no spaces)
// (a player's name as a channel has it: witness.ts's channelForm, "_" for the game's spaces since 11/16/2026, never two
// together nor at either end)
export const PLAYER = /^(?=.{1,12}$)[a-z0-9]+(?:_[a-z0-9]+)*$/;

/** An outstation's invites, from its time on air in all (seconds) and the names it's invited (relay.py's
 *  invite_numbers, the same). hours: one decimal, rounded down, so it never says 10.0 before the invite at 10. */
export function inviteNumbers(seconds: number, used: string[]) {
  const s = Math.max(0, Math.floor(seconds)), earned = INVITES_START + Math.floor(s / INVITE_EVERY_S);
  return { left: Math.max(0, earned - used.length), earned, used, hours: Math.floor(s / 360) / 10,
           next_in_s: INVITE_EVERY_S - (s % INVITE_EVERY_S) };
}
const OPEN = 1; // WebSocket.OPEN
// Flow control: a viewer says {"got": <binary bytes received so far>} about once a second, and past this much
// sent and not yet acknowledged it's sent no more frames or sound until it catches up. (Cloudflare's server side
// shows no send buffer to read, and a viewer that stops reading would otherwise pile up in the object's memory.)
const WINDOW = 4 * 1024 * 1024;
// A looker never says {"got"} (the page's lookers and the client's don't), so it's taken to have read this much a
// second (11/07/2026: with nothing ever acknowledged, a looker's frames stopped for good after its first 4 MB, about a
// quarter of an hour of small frames). Small frames come far slower than this, so they never wait; one that's stopped
// reading piles up no faster than its frames come (LOOK_EVERY).
const LOOK_READS = 128 * 1024;
// this channel's row in the open portals' list (GET /channels), told to the Directory this long after it changes, so a
// few changes at once (a desk's settings, viewers arriving together) are told once
const LISTING_WAIT = 50;
// the acknowledged rate (tellUpload's "acked") over this long: pages acknowledge every 1-3 s since 11/07/2026
const ACKED_OVER = 5000;
// the game state (11/07/2026) into storage at most this often while the plugin's messages come (one a message would be
// up to 18,000 rows written an hour), and as its socket closes
const GAME_SAVE = 60_000;

type Role = "transmit" | "watch" | "desk" | "sound" | "look" | "game" | "prove" | "tend" | "refused";

// a party's proof (witness.ts): the session's start, the claims made at logout, when they're next asked and how often
// they have been; a challenger's own name and skills as its plugin last said them
interface Wit {
  name?: string; seen?: Record<string, Seen>; look?: number; // (this session: each skill as last said, the next look)
  claims?: Xp; next?: number; tries?: number; // (after a logout: what's claimed, the next ask, how many so far)
  said?: { name?: unknown; skills?: unknown }; // (a challenger's own name and skills)
  last?: string; // (what the proof last did, in words, for the plugin's side tab)
}

interface Flow { sent: number; got: number; at: number }

/** A channel's row in the open portals' list (GET /channels): only what's listed (no code, handed out) carries more. */
export interface Listing {
  channel: string; set: boolean; code: boolean; at: number;
  live?: boolean; open?: boolean; static?: boolean; viewers?: number; title?: string; doing?: { boss: string; kc: number } | null;
  game?: boolean; // (the Outside plugin's connected, 11/07/2026)
  pending?: boolean; // (an invite nobody's taken up yet, 11/08/2026: not listed, only counted under its inviter)
  where?: Where; whereAt?: number; // (following, 11/08/2026: only while live, and when it came; the Directory says if it's fresh)
  activity?: Activity; activityAt?: number; // (what they're doing, 11/08/2026: the same)
  // (what they're doing as Discord shows it, and when it last came; when the Hub's plugin last posted, 11/16/2026: the
  // Directory says if it's fresh, PRESENCE_FRESH_MS)
  presence?: Presence; presenceAt?: number; seenAt?: number;
  name?: string; // (the name as the game shows it, from the plugin, 11/16/2026: "Oran Berries" for oranberries; shownName)
}

interface Att {
  role: Role;
  tendUntil?: number; // a tending socket's token runs out then (ms)
  who?: number; // a viewer's number in the chat
  seq?: number; // when it came, for the order of the waiting line
  seated?: boolean;
  code?: string;
  device?: string;
  name?: string;
  says?: number[];
  notes?: number[]; // (when its last notes went, 11/17/2026)
  lastPing?: number;
  host?: string; // a desk's name in the chat
  talk?: boolean; // a desk that said &talk=1: the Outside plugin's chat in the game (11/16/2026)
  lastSignal?: number;
  lookFrames?: boolean; // a looker asking for frames
  lookAt?: number;
  prover?: string; // a challenger's plugin (11/15/2026): its key's hash
}

interface Guess {
  id: number;
  boss: string;
  what: string;
  open: boolean;
  guesses: Record<string, { kc: number; name: string }>;
  result?: { kc: number | null; item: string; winners: string[]; off: number | null; at: number };
}

interface Poll {
  id: number;
  question: string;
  options: string[];
  votes: Record<string, number>;
  ends: number; // epoch ms
  open: boolean;
  closed?: number;
}

interface Meta {
  keyHash: string | null;
  // claimed by the Outside plugin from the Plugin Hub (11/15/2026, docs/protocol.md "Claiming a name"), nobody vouching
  // for it: the page marks the name unproven (GET /proof). A keeper's or an invite's key clears it. `account`: a hash
  // of the account hash the plugin sent with the claim, for the proof to come (never shown, never sent back).
  unproven?: boolean;
  account?: string;
  // proven from the game itself (11/15/2026, witness.ts): when, and the account hash's hash it was proven with
  proven?: { at: number; account?: string };
  // those taking the name back (11/15/2026, docs/protocol.md "Taking a name back"): each a key of its own, opening only
  // a proving socket, and kept CHALLENGE_DAYS at most, CHALLENGERS at once (the oldest goes)
  challengers?: { keyHash: string; at: number; account?: string }[];
  // tending tokens (11/16/2026): each one's SHA-256 and when it runs out
  tends?: { hash: string; until: number }[];
  code: string;
  cap: number; // the channel's own cap on viewers (the desk's {"cap"}): 0 none
  chatId: number;
  noteId?: number; // (the register's, 11/17/2026)
  pollId: number;
  nextWho: number;
  seq: number;
  hostNames: string[];
  blocked: string[]; // "w:<who>" and "d:<device>"
  soundInfo: string | null;
  state: string; // the capture's last {"state"}: sound passes only while "open"
  bans?: { who: number; name: string; tags: string[] }[]; // banned from the desk: blocked, their lines taken back
  pingsOff?: boolean; // the desk's {"pings": false}: viewers' pings go nowhere
  chatOff?: boolean; // the desk's {"chat_on": false}: viewers' lines go nowhere (the host's still go out)
  title?: string; // the desk's {"title"}: the broadcast's title, in every status line
  client?: string; // the version the last transmitter said (v=), for the keeper's list ("" none said)
  liveSince?: number; // when this stretch on air began (ms), while it lasts
  liveSeen?: number; // the last moment it was seen on air (saved about once a minute), for a stretch that ended unseen
  liveMs?: Record<string, number>; // time on air by month ("2026-11"), the last few months, for the keeper's bill
  liveAll?: number; // time on air in all, in seconds, the stretch going on now not included (11/07/2026: invites)
  invited?: string[]; // the channels this one has invited, in order (11/07/2026)
  summary?: Summary | null; // this stream's summary (fold): the readings the same for everyone, and the recap off air
  guessId?: number;
  guessMin?: number; // the desk's {"guess_min_value"}
  kc?: Record<string, [string, number]>; // boss (lower case) -> (its name, the kill count Dink last told of)
  // an invite not taken up yet (11/08/2026): handed out by another outstation's invite, and its key never used since.
  // Until it is, the open outstations' list leaves it out and only counts it under its inviter ("pending"): the
  // inviter typed the name, and the friend hasn't agreed to anything. The first right key, from anything (a socket,
  // Dink, clips, invites), takes it up. A new key from the keeper leaves it as it was; taking it back forgets it.
  pending?: boolean;
}

// One field report into a stream's summary (relay-py's fold, the same): what the stream's done so far, from Dink's numbers.
const SUMMARY_GAP = 15 * 60_000, SUMMARY_PETS = 5;
interface Summary {
  since: number; ended?: number; boss?: string;
  kc?: Record<string, { name: string; first: number; last: number; pb?: boolean }>;
  loot?: number; best?: { name: string; value: number; id?: number };
  levels?: Record<string, number>; total?: number;
  log?: { item?: string; id?: number; done?: number; total?: number };
  pets?: string[];
}
export function fold(sm: Summary, report: any): boolean {
  const d = report && report.data;
  if (!d || typeof d !== "object") return false;
  const kind = report.type;
  if (kind === "KILL_COUNT" && d.boss && Number.isInteger(d.count)) {
    const k = (sm.kc = sm.kc || {});
    const key = String(d.boss).toLowerCase();
    const b = (k[key] = k[key] || { name: d.boss, first: d.count - 1, last: d.count });
    b.last = Math.max(b.last, d.count);
    if (d.pb) b.pb = true;
    sm.boss = d.boss;
  } else if (kind === "LOOT" && Number.isInteger(d.value) && d.value > 0) {
    sm.loot = (sm.loot || 0) + d.value;
    for (const i of d.items || []) {
      const v = (i.quantity || 0) * (i.price || 0);
      if (v > (sm.best ? sm.best.value : 0)) sm.best = i.id ? { name: i.name, value: v, id: i.id } : { name: i.name, value: v };
    }
  } else if (kind === "LEVEL" && d.skills && typeof d.skills === "object") {
    sm.levels = Object.assign(sm.levels || {}, d.skills);
    if (d.total) sm.total = d.total;
  } else if (kind === "COLLECTION" && d.total) {
    const log: any = {};
    for (const k of ["item", "id", "done", "total"]) if (d[k] !== undefined && d[k] !== null) log[k] = d[k];
    sm.log = log;
  } else if (kind === "PET" && d.pet) {
    sm.pets = (sm.pets || []).slice(-(SUMMARY_PETS - 1)).concat([d.pet]);
  } else return false;
  return true;
}

export class Channel extends DurableObject<Env> {
  name = "";
  meta!: Meta;
  chat: [number, string][] = [];
  // the register's notes: id, its line, and the device it came from (never sent: what a ban holds on to)
  notes: [number, string, string][] = [];
  poll: Poll | null = null;
  guess: Guess | null = null;
  reports: string[] = [];
  lastData: string | null = null;
  lastFrame: ArrayBuffer | null = null;
  lastThumb: ArrayBuffer | null = null; // the newest small frame (4 bytes, "TH", a small JPEG): for lookers only
  thumbAt = 0;
  soundHeld: [number, ArrayBuffer][] = [];
  soundTimer: ReturnType<typeof setTimeout> | null = null;
  uploadTimer: ReturnType<typeof setInterval> | null = null;
  out = 0; // bytes sent to viewers (frames, sound, lines), for the desk's upload line
  acked = 0; // bytes viewers say they've had ({"got"}): proof the site is getting it
  // what viewers' pages said they'd had, a sample a second for the last 6 s (11/07/2026: the page acknowledges every
  // 1-3 s now, so a second at a time read 0 between acks; the rate is taken over the last 5 s or so)
  ackedSeen: [number, number][] = [[0, Date.now()]];
  outSeen: [number, number] = [0, Date.now()];
  wrong = new Map<string, number[]>();
  flow = new WeakMap<WebSocket, Flow>();
  // Who's here, kept between messages (11/07/2026): every frame, every status line and every sound release had asked
  // for the sockets again and read each one's attachment again, and a status line to each viewer in line did it once
  // more for each (150 viewers coming and going past a cap of 10 took 27 ms of CPU each, over the free plan's 10 ms).
  // Worked out again only when someone comes, goes or takes a seat (moved()); a sleep starts them empty.
  atts = new WeakMap<WebSocket, Att>();
  here = new Map<Role, WebSocket[]>();
  seatedNow: WebSocket[] | null = null;
  waitingNow: WebSocket[] | null = null;
  listingRow = ""; // the row last told to the Directory (tellListing)
  listingTimer: ReturnType<typeof setTimeout> | null = null;
  directory: DurableObjectStub | null = null; // (one stub, so what's told arrives in the order it was told)
  // the game (11/07/2026): what the Outside plugin last sent (kept through a sleep: saveGame), and when each game
  // socket's last messages were read (GAME_A_SECOND; in memory: a sleep forgets, which only loosens it)
  game = new GameState();
  gameReads = new WeakMap<WebSocket, number[]>();
  gameTimer: ReturnType<typeof setTimeout> | null = null;
  gameSavedAt = 0;
  gameDirty = false;
  // following (11/08/2026; game.ts, gameWhere): the square the plugin last said, when it came, and when the last one was
  // taken (for WHERE_EVERY_MS). In memory only, never saved: a sleep forgets it, and the plugin says it again.
  where: Where | null = null;
  whereAt = 0;
  whereTook = 0;
  // the exact path (11/10/2026; game.ts, gameHere): never kept, only when the last one was taken (for HERE_EVERY_MS) and
  // whether one's out to the desks (so a drop can say null)
  hereTook = 0;
  hereOut = false;
  // what they're doing (11/08/2026; game.ts, gameActivity): kept, taken and dropped exactly as "where" is
  activity: Activity | null = null;
  activityAt = 0;
  activityTook = 0;
  // the snapshot's plugin (11/16/2026; game.ts, gamePresence; docs/protocol.md "The snapshot"): what they're doing as
  // Discord shows it and when it last came, when the plugin last posted (0 once it's logged out), and each post's time
  // in the last minute (GAME_POSTS_A_MINUTE; in memory: a sleep forgets, which only loosens it). Saved ("presence"), as
  // a channel the plugin only posts to sleeps between its posts.
  presence: Presence | null = null;
  presenceAt = 0;
  seenAt = 0;
  posts: number[] = [];

  // proving the name (11/15/2026; witness.ts): the session's start on the hiscores, and the claims made at logout with
  // when they're next asked and how often they have been
  wit: Wit = {};
  cwit: Record<string, Wit> = {}; // (each challenger's, by its key's hash)

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      const got = await ctx.storage.get(["name", "meta", "chat", "notes", "poll", "reports", "guess", "game", "witness", "cwit", "presence"]);
      const pr = got.get("presence") as { presence: Presence | null; at: number; seen: number } | undefined;
      if (pr) { this.presence = pr.presence; this.presenceAt = pr.at || 0; this.seenAt = pr.seen || 0; }
      this.wit = (got.get("witness") as Wit) || {};
      this.cwit = (got.get("cwit") as Record<string, Wit>) || {};
      this.guess = (got.get("guess") as Guess) || null;
      this.name = (got.get("name") as string) || "";
      this.meta = (got.get("meta") as Meta) || {
        keyHash: null, code: "", cap: 0, chatId: 0, pollId: 0, nextWho: 0, seq: 0,
        hostNames: [], blocked: [], soundInfo: null, state: "open",
      };
      // settling's, gone (11/10/2026): its home, when that changed, and its code, never read again; written out of
      // storage with the meta's next save
      const old = this.meta as Meta & { home?: unknown; homeAt?: unknown; settle?: unknown };
      delete old.home;
      delete old.homeAt;
      delete old.settle;
      this.chat = (got.get("chat") as [number, string][]) || [];
      this.notes = (got.get("notes") as [number, string, string][]) || [];
      // (a note put right by the keeper, once: mends.ts)
      if (mendNotes(this.name, this.notes)) this.ctx.storage.put("notes", this.notes);
      // (the chat kept 500 lines for the register's few hours: back to its own 50)
      if (this.chat.length > CHAT_KEPT) this.chat = this.chat.slice(-CHAT_KEPT);
      this.poll = (got.get("poll") as Poll) || null;
      this.reports = (got.get("reports") as string[]) || [];
      this.game = new GameState(got.get("game") as { kept: Fields; at: number; snap?: number } | undefined);
    });
  }

  // storage

  saveMeta() { this.ctx.storage.put("meta", this.meta); }
  saveChat() { this.ctx.storage.put("chat", this.chat); }
  saveNotes() { this.ctx.storage.put("notes", this.notes); }
  notesPage(): number { const n = parseInt(this.env.NOTES_PAGE || "", 10); return n > 0 ? n : NOTES_PAGE; }
  notesSent(): number { const n = parseInt(this.env.NOTES_PAGE || "", 10); return n > 0 ? n : NOTES_SENT; }
  savePoll() { this.poll ? this.ctx.storage.put("poll", this.poll) : this.ctx.storage.delete("poll"); }
  saveGuess() { this.guess ? this.ctx.storage.put("guess", this.guess) : this.ctx.storage.delete("guess"); }

  // who's here

  // (the lists handed out are kept: never changed in place, only dropped by moved())
  sockets(role: Role): WebSocket[] {
    let got = this.here.get(role);
    if (!got) this.here.set(role, (got = this.ctx.getWebSockets(role).filter((ws) => ws.readyState === OPEN)));
    return got;
  }
  /** Someone came, went or took a seat: who's here is worked out again when next asked. */
  moved() {
    this.here.clear();
    this.seatedNow = this.waitingNow = null;
  }
  att(ws: WebSocket): Att {
    let a = this.atts.get(ws);
    if (!a) this.atts.set(ws, (a = ws.deserializeAttachment() as Att));
    return a;
  }
  /** A socket's attachment written (what must outlive a sleep), and kept in memory as it is. */
  keep(ws: WebSocket, a: Att) {
    ws.serializeAttachment(a);
    this.atts.set(ws, a);
  }
  seated(): WebSocket[] {
    return (this.seatedNow ??= this.sockets("watch").filter((ws) => this.att(ws).seated));
  }
  waiting(): WebSocket[] {
    return (this.waitingNow ??= this.sockets("watch").filter((ws) => !this.att(ws).seated)
      .sort((a, b) => (this.att(a).seq || 0) - (this.att(b).seq || 0)));
  }
  live(): boolean { return this.sockets("transmit").length > 0; }
  /** Lookers asking for frames (the status line's "lookers"). */
  lookers(): number { return this.sockets("look").filter((ws) => this.att(ws).lookFrames).length; }
  cap(): number {
    const ceiling = parseInt(this.env.MAX_VIEWERS || "0") || 0, own = this.meta.cap || 0;
    return ceiling && own ? Math.min(ceiling, own) : ceiling || own;
  }

  send(ws: WebSocket, msg: string | ArrayBuffer): boolean {
    try {
      ws.send(msg);
      return true;
    } catch {
      return false;
    }
  }
  flowOf(ws: WebSocket): Flow {
    let f = this.flow.get(ws);
    if (!f) this.flow.set(ws, (f = { sent: 0, got: 0, at: Date.now() }));
    return f;
  }

  // frames and sound: only as fast as the viewer takes them (WINDOW); a looker, as fast as it's taken to read (LOOK_READS)
  sendBinary(ws: WebSocket, msg: ArrayBuffer, looker = false): boolean {
    const f = this.flowOf(ws);
    if (looker) {
      const now = Date.now();
      f.got = Math.min(f.sent, f.got + ((now - f.at) * LOOK_READS) / 1000);
      f.at = now;
    }
    if (f.sent - f.got > WINDOW) return false;
    if (!this.send(ws, msg)) return false;
    f.sent += msg.byteLength;
    return true;
  }

  close(ws: WebSocket, code: number, reason: string) {
    try { ws.close(code, reason); } catch { /* gone already */ }
    this.moved();
  }

  status(place = 0, you?: number): string {
    const live = this.live();
    const st: Record<string, unknown> = {
      channel: this.name, live, viewers: this.seated().length, max: this.cap(),
      waiting: this.waiting().length, open: live && this.meta.state === "open",
      static: live && this.meta.state === "static",
      set: this.meta.keyHash !== null, // (the channel was handed out: Outside says "no portal here yet" when it wasn't)
      pings: !this.meta.pingsOff,
      chat: !this.meta.chatOff,
      title: this.meta.title || "",
      game: this.sockets("game").length > 0, // (the Outside plugin's connected, 11/07/2026)
      // (a desk that said &talk=1 is open, the Outside plugin's chat in the game: the page's chat shows while the
      // channel's dark, 11/16/2026)
      talk: this.sockets("desk").some((d) => !!this.att(d).talk),
      // (lookers asking for frames (11/10/2026): with viewers, what the transmitter needs to know of who's watching, so
      // with neither it sends a frame now and then and no sound, and with no lookers no small frames: docs/protocol.md
      // "Who's watching")
      lookers: this.lookers(),
    };
    if (place) { st.seat = false; st.place = place; }
    if (you !== undefined) st.you = you;
    return pyjson({ relay: st });
  }

  tellViewers() {
    const line = this.status();
    for (const ws of [...this.seated(), ...this.sockets("desk"), ...this.sockets("look"), ...this.sockets("transmit")]) this.send(ws, line);
    // those in line: the same line with their place and number on the end (status(place, you), put together from it
    // rather than written out again for each)
    const head = line.slice(0, -2);
    this.waiting().forEach((ws, i) => {
      const who = this.att(ws).who;
      this.send(ws, head + ', "seat": false, "place": ' + (i + 1) + (who !== undefined ? ', "you": ' + pyjson(who) : "") + "}}");
    });
    this.tellWatching();
    this.tellListing();
  }

  /** This channel's row in the open portals' list (GET /channels): the same as it was read from /_status before. */
  listing(): Listing {
    const set = this.meta.keyHash !== null, code = !!this.meta.code, at = Date.now();
    if (set && this.meta.pending) return { channel: this.name, set, code, at, pending: true }; // (only counted: its inviter's)
    if (!set || code) return { channel: this.name, set, code, at }; // (not listed: nothing more to tell)
    const live = this.live(), game = this.sockets("game").length > 0;
    // (the plugin first, 11/15/2026: where they are and what they're doing go on the list while the plugin's connected
    // too, not only while the outstation transmits; the plugin's own switches and safeguards still decide what comes)
    const shown = live || game;
    return { channel: this.name, set, code, at, live, open: live && this.meta.state === "open",
             static: live && this.meta.state === "static", viewers: this.seated().length, title: this.meta.title || "",
             doing: this.doing(), game,
             ...(shown && this.where ? { where: this.where, whereAt: this.whereAt } : {}),
             ...(shown && this.activity ? { activity: this.activity, activityAt: this.activityAt } : {}),
             ...(this.seenAt ? { seenAt: this.seenAt } : {}),
             ...(this.presence && (shown || this.seenAt) ? { presence: this.presence, presenceAt: this.presenceAt } : {}),
             ...(this.shownName() ? { name: this.shownName() as string } : {}) };
  }

  /** The player's name as the game shows it (11/16/2026, Jace: "make it show his name everywhere as his in-game name
   *  'Oran Berries', they shouldn't be mismatched"): the last the plugin said, its no-break spaces as spaces, only when it
   *  comes down to this channel (channelForm) and is a name the game allows; else null, and pages show the channel. */
  shownName(): string | null {
    const n = this.game.kept.name;
    if (typeof n !== "string") return null;
    const s = n.replace(/[\s\u00a0]+/g, " ").trim();
    return /^[A-Za-z0-9 _-]{1,12}$/.test(s) && nameFits(s, this.name) ? s : null;
  }

  /** The row told to the Directory when it's changed (11/07/2026): GET /channels had asked every channel handed out for
   *  its status, waking each, every time it was read (the outstations page every 20 s while open, each friend's
   *  client every minute); now it's answered from what each channel last told. */
  tellListing() {
    if (this.listingTimer || !this.name) return;
    this.listingTimer = setTimeout(() => {
      this.listingTimer = null;
      const row = this.listing(), line = JSON.stringify({ ...row, at: 0 });
      if (line === this.listingRow) return;
      this.listingRow = line;
      this.ctx.waitUntil(this.tellDirectory("/listed", JSON.stringify(row))
        .then((r) => { if (!r.ok) this.listingRow = ""; }, () => { this.listingRow = ""; })); // (told again at the next change)
    }, LISTING_WAIT);
  }

  /** The Directory's stub, kept so what's told arrives in the order it was told. */
  dir(): DurableObjectStub {
    return (this.directory ??= this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("directory")));
  }

  /** Something told to the Directory (a PUT: a listing row, a report's events, a star). A stub whose request fails is
   *  broken for good once the Directory's been reset (11/10/2026: seen under `wrangler dev`; a deploy, or Cloudflare
   *  moving the object, resets it), and a live channel never sleeps, so every telling after it failed too while it stayed
   *  on air. The stub's dropped and the telling sent once more on a new one; it rejects only if that fails too. */
  async tellDirectory(path: string, body: string): Promise<Response> {
    try {
      return await this.dir().fetch("https://dir" + path, { method: "PUT", body });
    } catch {
      this.directory = null;
    }
    try {
      return await this.dir().fetch("https://dir" + path, { method: "PUT", body });
    } catch (e) {
      this.directory = null;
      throw e;
    }
  }

  /** A field report's events (array.ts) told to the Directory, for the Array's feed (11/08/2026): only while this
   *  channel's live or its plugin's connected (11/15/2026, the plugin first) and listed (handed out, no watch code: on
   *  GET /channels), so a coded outstation's reports, and a dark one's with no plugin, never show. The Directory keeps
   *  them, with their ids, and drops what's past a channel's ARRAY_A_MINUTE. */
  tellArray(report: unknown) {
    // (11/16/2026: or the Hub's plugin's heard from, its posts: so Dink posting to the page puts its reports on the
    // Array, as a connected plugin's do; a dark outstation with no plugin, still never)
    if (!this.name || (!this.live() && !this.heard()) || this.meta.keyHash === null || this.meta.code) return;
    const events = arrayEvents(report);
    if (!events.length) return;
    this.ctx.waitUntil(this.tellDirectory("/array", JSON.stringify({ channel: this.name, events }))
      .then(() => undefined, () => undefined)); // (one lost is one less "+1")
  }

  /** A snapshot's events (snapshotEvents) told to the Directory as a field report's are, while the plugin's heard from
   *  (the Hub's plugin keeps no socket: its posts) or it's live, and listed (11/16/2026). */
  tellEvents(events: ReturnType<typeof snapshotEvents>) {
    if (!events.length || !this.name || (!this.live() && !this.heard()) || this.meta.keyHash === null || this.meta.code) return;
    this.ctx.waitUntil(this.tellDirectory("/array", JSON.stringify({ channel: this.name, events }))
      .then(() => undefined, () => undefined)); // (one lost is one less "+1")
  }

  /** Who's watching, for the desk (11/05/2026, Jace: "add viewer list"): each viewer's number, the name it gave in
   *  the chat ("visitor" until it gives one), and whether it's in line. Never a device or address. */
  viewerList(): string {
    // (written out here, the same as pyjson writes it, 11/07/2026: it goes to the desk each time anyone comes, goes or
    // says hello, and the whole list through pyjson was most of what a viewer's coming cost with a long line)
    const one = (ws: WebSocket, waiting: boolean) => {
      const a = this.att(ws);
      return '{"who": ' + pyjson(a.who || 0) + ', "name": ' + pyjson(a.name || "visitor") + ', "waiting": ' + waiting + "}";
    };
    const seated = [...this.seated()].sort((x, y) => (this.att(x).who || 0) - (this.att(y).who || 0));
    return '{"viewers": [' + [...seated.map((ws) => one(ws, false)), ...this.waiting().map((ws) => one(ws, true))].join(", ") + "]}";
  }

  tellWatching() {
    const desks = this.sockets("desk");
    if (!desks.length) return;
    const line = this.viewerList();
    for (const d of desks) this.send(d, line);
  }

  broadcast(line: string) {
    for (const ws of [...this.sockets("watch"), ...this.sockets("desk"), ...this.sockets("tend")]) this.send(ws, line);
  }

  seat(ws: WebSocket) {
    const a = this.att(ws);
    a.seated = true;
    this.keep(ws, a);
    this.seatedNow = this.waitingNow = null;
    this.send(ws, this.status(0, a.who));
    if (this.lastData) this.send(ws, this.lastData);
    if (this.lastFrame && this.live()) this.sendBinary(ws, this.lastFrame); // (off air, no old frame)
    for (const r of this.reports) this.send(ws, r);
    if (this.meta.summary) this.send(ws, this.summaryLine());
    const game = this.game.wholeLine();
    if (game) this.send(ws, game);
    this.lateJoiner(ws, "watch");
  }

  seatWaiting(): boolean {
    const cap = this.cap();
    let moved = false, n = this.seated().length;
    for (const ws of this.waiting()) {
      if (cap && n >= cap) break;
      this.seat(ws);
      n++;
      moved = true;
    }
    return moved;
  }

  lateJoiner(ws: WebSocket, role: Role) {
    for (const [, line] of this.chat.slice(-CHAT_SENT)) this.send(ws, line);
    for (const [, line] of this.notes.slice(-this.notesSent())) this.send(ws, line);
    if (this.poll) this.send(ws, this.pollLine());
    if (this.guess) this.send(ws, this.guessLine());
    if (this.meta.soundInfo && role !== "desk") this.send(ws, this.meta.soundInfo);
  }

  // keys

  async hash(key: string): Promise<string> {
    const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key));
    return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
  }

  // "ok", or why not (a close code and its reason)
  async checkKey(key: string, ip: string): Promise<[number, string] | null> {
    const now = Date.now(), tries = (this.wrong.get(ip) || []).filter((t) => now - t < 60_000);
    if (tries.length >= WRONG_KEYS) return [4029, "too many wrong keys: try again in a minute"];
    if (key.length < 8) return [4001, "a transmit key needs at least 8 characters"];
    const h = await this.hash(key);
    if (this.meta.keyHash === null) {
      if (this.env.OPEN_CLAIM === "1") {
        this.meta.keyHash = h;
        this.saveMeta();
        return null;
      }
      tries.push(now);
      this.wrong.set(ip, tries);
      return [4003, "no such channel: ask for an attach code"];
    }
    if (this.meta.keyHash !== h) {
      tries.push(now);
      this.wrong.set(ip, tries);
      return [4003, "wrong key for this channel"];
    }
    if (this.meta.pending) {
      // (an invite taken up: the friend has the key. On the open outstations' list from now on, no longer counted)
      delete this.meta.pending;
      this.saveMeta();
      this.tellListing();
    }
    return null;
  }

  // the door

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const name = request.headers.get("x-channel") || "";
    if (name && this.name !== name) {
      this.name = name;
      this.ctx.storage.put("name", name);
      if (!this.meta.hostNames.includes(name.toLowerCase())) {
        this.meta.hostNames.push(name.toLowerCase());
        this.saveMeta();
      }
    }
    const ip = request.headers.get("cf-connecting-ip") || "";
    const q = url.searchParams;
    let [, role] = url.pathname.split("/");
    if (role === "watch" && q.get("look") === "1") role = "look";
    if (role === "desk" && q.has("tend")) role = "tend";
    if (role === "_tend") return this.tendToken(request, q, ip);

    if (role === "_mint") return this.mint(q.get("new") === "1", q.get("claim") === "1", request.headers.get("x-account") || "");
    if (role === "_challenge") return this.challenge(request.headers.get("x-account") || "");
    if (role === "_proof") {
      // (who stands behind the name: "unproven", claimed by the plugin alone; "vouched", a keeper's or an invite's key;
      // "proven" from the game itself; and to a challenger's own key, "challenging")
      if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
      const k = q.get("key");
      // (to the page's own key, 11/17/2026: how many visitors' lines the register has after `seen`, the newest's id, for
      // the plugin's side tab, "3 new notes in your register")
      if (k && !this.meta.unproven && (await this.hash(k)) === this.meta.keyHash) {
        return json({ proof: this.meta.proven ? "proven" : "vouched", notes: this.notesSince(q.get("seen")) });
      }
      if (k && this.meta.unproven) {
        // (to the key's own, what its proof last did, in words: the plugin's side tab says it)
        const h = await this.hash(k);
        if ((this.meta.challengers || []).some((c) => c.keyHash === h)) {
          return json({ proof: "challenging", ...(this.cwit[h]?.last ? { why: this.cwit[h].last } : {}) });
        }
        if (h === this.meta.keyHash) return json({ proof: "unproven", ...(this.wit.last ? { why: this.wit.last } : {}), notes: this.notesSince(q.get("seen")) });
      }
      return json({ proof: this.meta.proven ? "proven" : this.meta.unproven ? "unproven" : "vouched" });
    }
    if (role === "_status") {
      const live = this.live();
      return Response.json({ channel: this.name, set: this.meta.keyHash !== null, code: !!this.meta.code, live,
                             open: live && this.meta.state === "open", static: live && this.meta.state === "static",
                             viewers: this.seated().length, title: this.meta.title || "", client: this.meta.client || "",
                             live_s: this.secondsThisMonth(), doing: this.doing() });
    }
    if (role === "_listing") return Response.json(this.listing()); // (the Directory, for a row it hasn't been told)
    if (role === "_revoke") return this.revoke();
    if (role === "_notice") {
      // the keeper's notice, set or taken down: to every desk here now
      const line = pyjson(await request.json());
      for (const d of this.sockets("desk")) this.send(d, line);
      return new Response(null, { status: 204 });
    }
    if (role === "_dink") return this.dink(request, q.get("key") || "", ip, q.get("from") === "plugin");
    // (an anomaly report's channel and key, checked: index.ts's /reports)
    if (role === "_keyok") return Response.json({ ok: this.meta.keyHash !== null && (await this.checkKey(q.get("key") || "", ip)) === null });
    // (an outstation joining the aether, aether.ts: its key checked as its desk's would be, and why not if not)
    if (role === "_key") return Response.json({ no: await this.checkKey(q.get("key") || "", ip) });
    if (role === "_dm") return this.takeDm(request);
    if (role === "_invites") return this.invites(request, q, ip);
    if (role === "_game") return this.readGame(q);
    if (role === "_chat") return this.readChat(q);
    if (role === "_register") return this.readRegister(q);
    if (role === "_gamepost") return this.postGame(request, q, ip);
    if (role === "_clips") return this.clips(request, q, ip, false);
    if (role === "_clips_admin") return this.clips(request, q, ip, true);
    if (role === "_fights") return this.fights(request, q, ip);
    if (role === "_keep") return this.kept(request, q, ip);
    if (role === "_emotes") return this.emotes(request, q, ip);
    if (role === "_takedown") return this.takedown(request, q, ip);
    if (role === "_code") return this.setCode(request, q, ip);

    // the client's version, then the key, so a refused socket never counts as the channel's, even for a moment
    // (a version turned away isn't a wrong key: it doesn't count towards the wrong-key limit). The game's v= is the
    // Outside plugin's own version, which MIN_CLIENT never looks at.
    const min = this.env.MIN_CLIENT || "";
    // (a challenger's key on the game's address: a proving socket, its own; 11/15/2026)
    let prover = "";
    if (role === "game" && this.meta.unproven && (this.meta.challengers || []).length) {
      const h = await this.hash(q.get("key") || "");
      if ((this.meta.challengers || []).some((c) => c.keyHash === h && Date.now() - c.at < CHALLENGE_DAYS * 86_400_000)) { prover = h; role = "prove"; }
    }
    const tendUntil = role === "tend" ? await this.tendUntil(q.get("tend") || "") : 0;
    const no: [number, string] | null = role === "watch" || role === "look" || role === "prove" ? null
      : role === "tend" ? (tendUntil ? null : [4003, "this link to tend the chat has run out: open it again from the plugin"])
      : role !== "game" && tooOld(q.get("v"), min) ? [4026, `this outstation is too old for the relay: ${min} or newer is needed`]
      : await this.checkKey(q.get("key") || "", ip);
    const pair = new WebSocketPair();
    const [client, ws] = [pair[0], pair[1]];
    this.ctx.acceptWebSocket(ws, [no ? "refused" : role]);
    const a: Att = { role: (no ? "refused" : role) as Role, ...(prover && !no ? { prover } : {}), ...(tendUntil && !no ? { tendUntil } : {}) };
    this.keep(ws, a);
    this.moved();

    if (no) {
      this.close(ws, no[0], no[1]);
    } else if (role === "watch") {
      this.openWatch(ws, a, q.get("code") || "");
    } else if (role === "look") {
      this.openLook(ws, a, q.get("code") || "");
    } else if (role === "tend") {
      this.openTend(ws);
    } else {
      if (role === "transmit") {
        this.openTransmit(ws, q.get("code") || "", q.get("v"));
      } else if (role === "desk") {
        this.openDesk(ws, a, q.get("name") || "", q.get("talk") === "1");
        await this.tellNotice(ws);
        await this.tellDms(ws);
      } else if (role === "game") {
        this.openGame(ws);
      } else if (role === "prove") {
        // (one at a time for a challenger, as the game's)
        for (const old of this.sockets("prove")) if (old !== ws && this.att(old).prover === prover) this.close(old, 4000, "replaced by a newer game");
      } else {
        this.openSound(ws);
      }
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  openTransmit(ws: WebSocket, code: string, version: string | null) {
    for (const old of this.sockets("transmit")) {
      if (old !== ws) this.close(old, 4000, "replaced by a newer transmitter");
    }
    // (11/17/2026, the who-sees-it fix: the plugin owns the watch code (PUT /code). A transmitter's empty one no longer
    // clears it, so a page made friends only stays so when an outstation goes live; an outstation from before the fix
    // that has a code of its own still sets it, never opening a page.)
    if (code) this.meta.code = code;
    this.meta.client = oneLine(version || "", 32);
    // time on air: a stretch left open by an object that went before its transmitter said goodbye ends where it
    // was last seen; then a new stretch begins (unless another transmitter is already on)
    if (this.meta.liveSince && !this.sockets("transmit").some((t) => t !== ws)) {
      this.addLive(this.meta.liveSince, this.meta.liveSeen || this.meta.liveSince);
      this.meta.liveSince = undefined;
    }
    if (!this.meta.liveSince) { this.meta.liveSince = Date.now(); this.meta.liveSeen = Date.now(); }
    // this stream's summary: a new one, unless it went off air only a little while ago
    const sm = this.meta.summary;
    if (!sm || (sm.ended && Date.now() - sm.ended > SUMMARY_GAP)) this.meta.summary = { since: Date.now() };
    else delete sm.ended;
    this.tellSummary();
    this.meta.state = "open";
    this.saveMeta();
    // viewers whose code no longer matches the page's go (11/17/2026, the review before the outstation's v0.2.0: the
    // page's own code, not the transmitter's, which a v0.2.0 outstation never sends: going live had closed every friend
    // watching a friends-only page)
    if (this.meta.code) {
      for (const v of this.sockets("watch")) {
        if ((this.att(v).code || "") !== this.meta.code) this.close(v, 4003, "the watch code changed");
      }
    }
    this.tellViewers();
    if (!this.uploadTimer) {
      this.outSeen = [this.out, Date.now()];
      this.uploadTimer = setInterval(() => this.tellUpload(), 1000);
    }
  }

  openWatch(ws: WebSocket, a: Att, code: string) {
    if (this.meta.code && code !== this.meta.code) {
      this.close(ws, 4003, "wrong watch code");
      return;
    }
    this.meta.nextWho++;
    this.meta.seq++;
    Object.assign(a, { code, who: this.meta.nextWho, seq: this.meta.seq, seated: false, device: "",
                       name: "visitor", says: [], lastPing: 0 });
    this.saveMeta();
    this.keep(ws, a);
    this.moved();
    const cap = this.cap();
    if (cap && (this.seated().length >= cap || this.waiting().length > 1)) {
      this.lateJoiner(ws, "watch"); // in line for a seat: the chat so far all the same
    } else {
      this.seat(ws);
    }
    this.tellViewers();
  }

  // A looker (the listening post's receiver tuned to the portal, portal-feed.js): no seat, not counted as watching. It
  // hears the status lines, and frames only while it asks ({"look": {"frames": true}}), at most one every LOOK_EVERY.
  openLook(ws: WebSocket, a: Att, code: string) {
    if (this.meta.code && code !== this.meta.code) {
      this.close(ws, 4003, "wrong watch code");
      return;
    }
    a.lookFrames = false;
    a.lookAt = 0;
    this.keep(ws, a);
    this.send(ws, this.status());
    const game = this.game.wholeLine();
    if (game) this.send(ws, game);
  }

  /** A tending socket (11/16/2026): the status line, the chat so far and who's banned; after that every line, every
   *  line taken back and every change to the bans, as a desk hears them; nothing else of the channel's. */
  openTend(ws: WebSocket) {
    this.send(ws, this.status());
    for (const [, line] of this.chat) this.send(ws, line);
    for (const [, line] of this.notes) this.send(ws, line);   // (every note: it's the register they're tending)
    this.send(ws, this.bannedLine());
  }

  /** When a tending token runs out (ms), or 0 for none that's good (a bad shape, unknown, or run out). */
  async tendUntil(token: string): Promise<number> {
    if (!TEND_TOKEN.test(token)) return 0;
    const h = await this.hash(token), now = Date.now();
    const t = (this.meta.tends || []).find((x) => x.hash === h && x.until > now);
    return t ? t.until : 0;
  }

  /** POST /tend/<channel>?key= {"token"} (index.ts; 11/16/2026): a tending token from the plugin, 43 base64url
   *  characters, kept as its SHA-256 for TEND_MS. 200 {"until"}; 400 a bad token; 403 the wrong key; 404 nobody's. */
  async tendToken(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    if (request.method !== "POST") return new Response(null, { status: 405 });
    if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
    const no = await this.checkKey(q.get("key") || "", ip);
    if (no) return json({ error: no[1] }, 403);
    let token: unknown;
    try { token = ((await request.json()) as { token?: unknown }).token; } catch { token = undefined; }
    if (typeof token !== "string" || !TEND_TOKEN.test(token)) return json({ error: "a token: 43 base64url characters" }, 400);
    const now = Date.now(), until = now + TEND_MS, h = await this.hash(token);
    this.meta.tends = [...(this.meta.tends || []).filter((x) => x.until > now && x.hash !== h), { hash: h, until }].slice(-TENDS_MOST);
    this.saveMeta();
    return json({ until });
  }

  /** What a tending socket said: one of the desk's take-back, block and ban lines, nothing else; and none once its
   *  token has run out (the socket's closed then). */
  fromTend(ws: WebSocket, a: Att, msg: string) {
    if (!a.tendUntil || Date.now() > a.tendUntil) { this.close(ws, 4003, "this link to tend the chat has run out: open it again from the plugin"); return; }
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m)) return;
    for (const k of ["hide", "hide_note", "block", "unblock", "ban", "unban"]) {
      if (Number.isInteger(m[k])) { this.fromDesk(this.name, JSON.stringify({ [k]: m[k] })); return; }
    }
  }

  openDesk(ws: WebSocket, a: Att, hostName: string, talk = false) {
    const host = oneLine(hostName, NAME_MAX) || this.name;
    a.host = host;
    if (talk) a.talk = true;
    this.keep(ws, a);
    if (talk) this.tellViewers();
    else this.tellApp();
    if (!this.meta.hostNames.includes(host.toLowerCase())) {
      this.meta.hostNames.push(host.toLowerCase());
      this.saveMeta();
    }
    this.send(ws, this.status());
    this.lateJoiner(ws, "desk");
    const game = this.game.wholeLine();
    if (game) this.send(ws, game);
    this.send(ws, this.bannedLine());
    this.send(ws, this.viewerList());
    // (your year: what's held now, each with when it came)
    if (this.where) this.send(ws, pyjson({ mine: { where: this.where, at: this.whereAt } }));
    if (this.activity) this.send(ws, pyjson({ mine: { activity: this.activity, at: this.activityAt } }));
  }

  /** Your year (11/10/2026; docs/record.md): one of the owner's own things, {"mine": {...}}, to this channel's desks
   *  only (the app's chat window, which only the station key opens): never a viewer, a looker or anything else. */
  /** (Unused since 11/17/2026: the outstation no longer keeps a year, and v0.2.0 throws these away, desk.rs. Kept for
   *  now, as the protocol tests read the owner's own feed through them; only ever the owner's own desks.) */
  tellMine(m: Record<string, unknown>) {
    const desks = this.sockets("desk");
    if (!desks.length) return;
    const line = pyjson({ mine: m });
    for (const d of desks) this.send(d, line);
  }

  // messages between portals: this portal's to another channel's (through that channel's object), and another's held
  // here till this portal's desk opens

  dmsSent: number[] = [];

  async sendDm(ws: WebSocket, host: string, dm: any) {
    const to = typeof dm?.to === "string" ? dm.to.trim().toLowerCase() : "";
    const text = oneLine(dm?.text, SAY_MAX);
    const now = Date.now();
    this.dmsSent = this.dmsSent.filter((t) => now - t < 60_000);
    const fail = (why: string) => this.send(ws, pyjson({ dm_failed: { to, text, why } }));
    if (!text || !/^[A-Za-z0-9_.~-]{1,64}$/.test(to)) return fail("a message needs a channel and some words");
    if (to === this.name) return fail("that's your own outstation");
    if (this.dmsSent.length >= DMS_A_MINUTE) return fail(`${DMS_A_MINUTE} messages a minute at most: wait a moment`);
    this.dmsSent.push(now);
    const d: Dm = { from: this.name, name: host, text, at: now };
    try {
      const r = await this.env.CHANNEL.get(this.env.CHANNEL.idFromName(to)).fetch(new Request("https://channel/_dm", {
        method: "POST", headers: { "x-channel": to, "content-type": "application/json" }, body: JSON.stringify(d) }));
      if (r.status === 404) return fail(`no outstation called ${to} on this relay`);
      if (!r.ok) return fail("the relay couldn't pass it on");
      const got = (await r.json()) as { held: boolean };
      this.send(ws, pyjson({ dm_sent: { to, text, at: now, held: got.held } }));
    } catch {
      fail("the relay couldn't pass it on");
    }
  }

  async takeDm(request: Request): Promise<Response> {
    if (this.meta.keyHash === null) return new Response(null, { status: 404 });
    const d = (await request.json()) as Dm;
    const desks = this.sockets("desk");
    if (desks.length) {
      for (const w of desks) this.send(w, pyjson({ dm: d }));
    } else {
      const now = Date.now();
      const kept = ((await this.ctx.storage.get<Dm[]>("dms")) || []).filter((x) => now - x.at <= DMS_DAYS * 86_400_000);
      await this.ctx.storage.put("dms", [...kept.slice(-(DMS_KEPT - 1)), d]);
    }
    return Response.json({ held: !desks.length });
  }

  /** Messages that came while this portal was closed, to its desk as it connects. */
  async tellDms(ws: WebSocket) {
    const now = Date.now();
    const kept = ((await this.ctx.storage.get<Dm[]>("dms")) || []).filter((x) => now - x.at <= DMS_DAYS * 86_400_000);
    for (const d of kept) this.send(ws, pyjson({ dm: d }));
    if (kept.length || (await this.ctx.storage.get("dms"))) await this.ctx.storage.delete("dms");
  }

  /** The keeper's notice, if there is one, to a desk that's just connected. */
  async tellNotice(ws: WebSocket) {
    try {
      const r = await this.env.DIRECTORY.get(this.env.DIRECTORY.idFromName("directory")).fetch("https://dir/notice");
      const got = (await r.json()) as { notice: unknown };
      if (got.notice) this.send(ws, pyjson(got));
    } catch { /* (no notice, then) */ }
  }

  bannedLine(): string {
    return pyjson({ banned: (this.meta.bans || []).map((b) => ({ who: b.who, name: b.name })) });
  }

  tellBanned() {
    const line = this.bannedLine();
    for (const d of [...this.sockets("desk"), ...this.sockets("tend")]) this.send(d, line);
  }

  lastName(who: number): string {
    for (let i = this.chat.length - 1; i >= 0; i--) {
      const c = JSON.parse(this.chat[i][1]).chat;
      if (c.who === who) return c.name;
    }
    for (let i = this.notes.length - 1; i >= 0; i--) {
      const c = JSON.parse(this.notes[i][1]).note;
      if (c.who === who) return c.name;
    }
    return "";
  }

  openSound(ws: WebSocket) {
    for (const old of this.sockets("sound")) {
      if (old !== ws) this.close(old, 4000, "replaced by a newer sound");
    }
  }

  // the game (11/07/2026; game.ts): the Outside plugin's socket, one at a time, a newer one replacing the older

  openGame(ws: WebSocket) {
    for (const old of this.sockets("game")) {
      if (old !== ws) this.close(old, 4000, "replaced by a newer game");
    }
    this.tellViewers(); // (the status line's "game")
    this.tellApp(ws);
  }

  /** Whether the outstation's app is here (a desk not the plugin's chat), to the plugin's game socket as {"app": bool}
   *  (11/16/2026, the permissions review): the exact path goes only while it is, since only the app takes it (the plugin
   *  keeps its own record). To one socket just opened, or to every game socket as a desk comes or goes. */
  /** (Unused since 11/17/2026: the plugin no longer sends its exact tile, which this told it it could.) */
  tellApp(only?: WebSocket) {
    const line = pyjson({ app: this.sockets("desk").some((d) => !this.att(d).talk) });
    for (const g of only ? [only] : this.sockets("game")) this.send(g, line);
  }

  /** A {"game"} message: GAME_A_SECOND a second at most and GAME_MESSAGE long at most (the rest dropped, with no
   *  answer), into the kept state, and what it changed on to seated viewers, lookers and desks. */
  fromGame(ws: WebSocket, msg: string) {
    if (ws.readyState !== OPEN) return; // (one replaced, or cut off by a new key: what it still had on the way)
    const now = Date.now(), read = (this.gameReads.get(ws) || []).filter((t) => now - t < 1000);
    this.gameReads.set(ws, read);
    if (read.length >= GAME_A_SECOND) return;
    read.push(now);
    if (msg.length > GAME_MESSAGE) return;
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m) || !m.game || typeof m.game !== "object" || Array.isArray(m.game)) return;
    const fields = gameFields(m.game);
    this.witnessGame(fields);
    this.takeWhere(gameWhere(m.game), fields, now);
    this.takeActivity(gameActivity(m.game), fields, now);
    if (fields.off === true && this.seenAt) { this.seenAt = 0; this.savePresence(); this.tellListing(); } // (logged out)
    this.takePresence(gamePresence(m.game), fields, now);
    this.takeHere(gameHere(m.game), fields, now);
    this.takeStar(gameStar(m.game), fields);
    const was = this.shownName();
    const got = this.game.apply(fields); // (never "where", "activity" or "star": gameFields leaves them out)
    if (this.shownName() !== was) this.tellListing(); // (the list's name, as the game shows it)
    if (fields.off === true) this.witnessOff(); // (after the message is taken: its last skills with it)
    if (!got) return;
    const line = pyjson({ game: { ...got, at: this.game.at } });
    for (const v of [...this.seated(), ...this.sockets("look"), ...this.sockets("desk")]) this.send(v, line);
    this.saveGameSoon();
  }

  /** Following (game.ts, gameWhere): a message's "where" taken (one every WHERE_EVERY_MS at most; more are ignored), or
   *  the one held dropped (null, whenever it comes). Logged out ({"off": true}) or held for the player's safety
   *  ({"paused": "wilderness"}), it's dropped, whatever the message says of it. Only the open outstations' list hears
   *  of it (listing). */
  takeWhere(w: Where | null | undefined, fields: Fields, now: number) {
    if (fields.off === true || typeof fields.paused === "string" || w === null) return this.dropWhere();
    if (!w || now - this.whereTook < WHERE_EVERY_MS) return;
    this.where = w;
    this.whereAt = this.whereTook = now;
    this.tellListing();
    this.tellMine({ where: w, at: now });
  }

  dropWhere() {
    if (!this.where) return;
    this.where = null;
    this.whereAt = 0;
    this.tellListing();
    this.tellMine({ where: null, at: Date.now() });
  }

  /** The exact path (game.ts, gameHere): passed at once to this channel's desks only ({"mine": {"here", "at"}}), one
   *  every HERE_EVERY_MS at most (more are ignored), and never kept. null, logging out ({"off": true}) or holding for the
   *  player's safety ({"paused"}) passes null, if one was out. */
  takeHere(h: Here | null | undefined, fields: Fields, now: number) {
    if (fields.off === true || typeof fields.paused === "string" || h === null) return this.dropHere();
    if (!h || now - this.hereTook < HERE_EVERY_MS) return;
    this.hereTook = now;
    this.hereOut = true;
    this.tellMine({ here: h, at: now });
  }

  dropHere() {
    if (!this.hereOut) return;
    this.hereOut = false;
    this.tellMine({ here: null, at: Date.now() });
  }

  /** What they're doing (game.ts, gameActivity): taken and dropped exactly as takeWhere takes "where", one every
   *  WHERE_EVERY_MS at most, null whenever it comes; logged out or held for the player's safety, dropped. */
  takeActivity(a: Activity | null | undefined, fields: Fields, now: number) {
    if (fields.off === true || typeof fields.paused === "string" || a === null) return this.dropActivity();
    if (!a || now - this.activityTook < WHERE_EVERY_MS) return;
    this.activity = a;
    this.activityAt = this.activityTook = now;
    this.tellListing();
    this.tellMine({ activity: a, at: now });
  }

  dropActivity() {
    if (!this.activity) return;
    this.activity = null;
    this.activityAt = 0;
    this.tellListing();
    this.tellMine({ activity: null, at: Date.now() });
  }

  /** What they're doing as Discord shows it (game.ts, gamePresence; 11/16/2026): taken whenever it comes (the POSTs are
   *  limited already), its time refreshed even when it's the same; null, logging out ({"off": true}) or holding for the
   *  player's safety ({"paused"}) drops it. Saved, with when the plugin last posted. */
  takePresence(p: Presence | null | undefined, fields: Fields, now: number) {
    if (fields.off === true || typeof fields.paused === "string" || p === null) return this.dropPresence();
    if (!p) return;
    const same = !!this.presence && pyjson(this.presence) === pyjson(p);
    this.presence = p;
    this.presenceAt = now;
    this.savePresence();
    if (!same) this.tellListing();
  }

  dropPresence() {
    if (!this.presence) return;
    this.presence = null;
    this.presenceAt = 0;
    this.savePresence();
    this.tellListing();
  }

  savePresence() {
    this.ctx.storage.put("presence", { presence: this.presence, at: this.presenceAt, seen: this.seenAt });
  }

  /** Whether the plugin's here, as the page and the globe count it: its socket's connected, or it posted within
   *  PRESENCE_FRESH_MS and hasn't logged out since. */
  heard(now = Date.now()): boolean {
    const fresh = (parseFloat(this.env.PRESENCE_FRESH_SECONDS || "") * 1000) || PRESENCE_FRESH_MS;
    return this.sockets("game").length > 0 || (!!this.seenAt && now - this.seenAt <= fresh);
  }

  /** POST /game/<channel>?key= (11/16/2026, the snapshot; docs/protocol.md "The snapshot"): the Hub's plugin, which
   *  keeps no socket. Its {"game"} taken as a socket's message is (the proof's figures, the fields kept and passed on,
   *  "presence"), but never "where", "activity", "here" or "star"; one carrying SNAP_FIELDS is a snapshot. A challenger's
   *  key: only its own proof, as a proving socket's. 204 taken; 400 not a {"game"} object; 403 a wrong key; 413 too
   *  long; 429 past GAME_POSTS_A_MINUTE a minute, or too many wrong keys. */
  async postGame(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    const n = parseInt(request.headers.get("content-length") || "0") || 0;
    if (n > GAME_MESSAGE) return json({ error: "too long" }, 413);
    const key = q.get("key") || "";
    let party = "";
    if (this.meta.unproven && (this.meta.challengers || []).length) {
      const h = await this.hash(key);
      if ((this.meta.challengers || []).some((c) => c.keyHash === h && Date.now() - c.at < CHALLENGE_DAYS * 86_400_000)) party = h;
    }
    if (!party) {
      const no = await this.checkKey(key, ip);
      if (no) return json({ error: no[1] }, no[0] === 4029 ? 429 : 403);
    }
    const now = Date.now();
    this.posts = this.posts.filter((t) => now - t < 60_000);
    if (this.posts.length >= GAME_POSTS_A_MINUTE) return json({ error: "too often: wait a minute" }, 429);
    this.posts.push(now);
    const text = await request.text();
    if (text.length > GAME_MESSAGE) return json({ error: "too long" }, 413);
    let m: any;
    try { m = JSON.parse(text); } catch { m = null; }
    if (!m || typeof m !== "object" || Array.isArray(m) || !m.game || typeof m.game !== "object" || Array.isArray(m.game)) {
      return json({ error: "send {\"game\": {...}}" }, 400);
    }
    const fields = gameFields(m.game);
    // (11/17/2026: a post's skills are looked at at once (no socket's stream to wait out; the hiscores can't have this
    // session's experience while they're logged in), and a plugin that asks (&proof=1) hears in the answer what its
    // proof did, "ready" meaning that logging out now proves it: two friends played for hours on the Hub's plugin and
    // stayed unproven, their last update's figures out of date by logout and nothing to tell them)
    const proofNow = async (p: string): Promise<Response> => {
      if (fields.skills && this.witOpen(p) && this.witOf(p).look) {
        await Promise.race([this.witLook(p), new Promise((r) => setTimeout(r, 6000))]);
        this.saveWit();
        this.schedule();
      }
      if (q.get("proof") !== "1" || !this.witOpen(p)) return new Response(null, { status: 204 });
      const why = this.witOf(p).last;
      return json({ proof: p ? "challenging" : "unproven", ...(why ? { why } : {}) });
    };
    if (party) {
      if (this.witOpen(party)) {
        this.witStart(party, fields);
        const w = this.witOf(party);
        w.said = { ...(w.said || {}), ...(typeof fields.name === "string" ? { name: fields.name } : {}), ...(fields.skills ? { skills: fields.skills } : {}) };
        if (fields.off === true) this.witOff(party);
      }
      return proofNow(party);
    }
    this.witnessGame(fields);
    this.seenAt = fields.off === true ? 0 : now;
    this.takePresence(gamePresence(m.game), fields, now);
    this.savePresence();
    // (the list's "heard from" moves with every post, not only when what Discord shows changes: an hourly post with
    // the same presence kept them lit past PRESENCE_FRESH_MS only here, and the globe read them as dark. Told at most
    // once a LISTING_WAIT, and the posts come once a minute at most)
    this.tellListing();
    const snap = SNAP_FIELDS.some((f) => f in fields);
    if (snap) this.game.snap = now;
    // (the whole plugin's own fields, left from its socket, cleared: SNAP_CLEARS)
    if (snap) for (const f of SNAP_CLEARS) if (this.game.kept[f] != null) fields[f] = null;
    // (what a snapshot adds to the Array, against the last kept: array.ts's snapshotEvents, 11/16/2026)
    const before: Record<string, unknown> = {};
    if (snap) for (const f of ["skills", "quests", "diaries", "combat", "slayer", "clog", "counts"]) if (this.game.kept[f] != null) before[f] = JSON.parse(JSON.stringify(this.game.kept[f]));
    const got = this.game.apply(fields, now);
    if (snap && fields.off !== true) {
      let news: ReturnType<typeof snapshotEvents> = [];
      try { news = snapshotEvents(before, this.game.kept as Record<string, unknown>); } catch { /* not one it can read */ }
      this.tellEvents(news);
    }
    if (fields.off === true) this.witnessOff();
    if (got || snap) {
      this.game.line = null;
      const line = pyjson({ game: { ...(got || {}), at: this.game.at, ...(this.game.snap ? { snap: this.game.snap } : {}) } });
      for (const v of [...this.seated(), ...this.sockets("look"), ...this.sockets("desk")]) this.send(v, line);
      this.gameDirty = true;
      this.saveGame(); // (now: the object may sleep before a timer would)
    }
    this.tellListing(); // (seenAt moved: the Directory's row says when the plugin was last heard)
    return proofNow("");
  }

  /** A shooting star the plugin found (game.ts, gameStar): to the Directory's list (stars.ts), which counts this
   *  channel's reports (STARS_AN_HOUR) and names it as the star's finder only if it's on the open outstations' list
   *  (handed out, no watch code, not an invite waiting). Not from a message that logs out or holds for the player's
   *  safety. */
  takeStar(star: StarReport | undefined, fields: Fields) {
    if (!star || !this.name || fields.off === true || typeof fields.paused === "string") return;
    const listed = this.meta.keyHash !== null && !this.meta.code && !this.meta.pending;
    this.ctx.waitUntil(this.tellDirectory("/stars", JSON.stringify({ channel: this.name, star, listed }))
      .then(() => undefined, () => undefined)); // (one lost: the next outstation to see it tells of it)
  }

  /** The game state into storage, at most once every GAME_SAVE while messages come: soon after the first, then a minute
   *  after the last save. (A timer waiting should keep the object from hibernating, so a sleep loses nothing since the
   *  last save; if it doesn't, or the object's evicted by a deploy or a restart, at most that minute is lost.) */
  saveGameSoon() {
    this.gameDirty = true;
    if (this.gameTimer) return;
    this.gameTimer = setTimeout(() => { this.gameTimer = null; this.saveGame(); },
                                Math.max(0, this.gameSavedAt + GAME_SAVE - Date.now()));
  }

  /** Saved now, if anything's changed since the last time (and as the game socket closes). */
  saveGame() {
    if (this.gameTimer) clearTimeout(this.gameTimer);
    this.gameTimer = null;
    if (!this.gameDirty) return;
    this.gameDirty = false;
    this.gameSavedAt = Date.now();
    this.ctx.storage.put("game", { kept: this.game.kept, at: this.game.at, snap: this.game.snap });
  }

  /** Forgotten (a new key from the keeper, or the channel taken back). */
  clearGame() {
    this.dropWhere(); // (following: the new key's holder starts with none)
    this.whereTook = 0;
    this.dropActivity(); // (what they're doing, the same)
    this.activityTook = 0;
    this.dropHere(); // (the exact path, the same)
    this.hereTook = 0;
    this.dropPresence(); // (what they're doing as Discord shows it, the same)
    this.seenAt = 0;
    this.ctx.storage.delete("presence");
    if (this.gameTimer) clearTimeout(this.gameTimer);
    this.gameTimer = null;
    this.gameDirty = false;
    this.game = new GameState();
    this.ctx.storage.delete("game");
    this.ctx.storage.delete(["keep:bank", "keep:model", "keep:encounter", "keep:goal"]); // (the bank, the model, the encounter and the goal, keep.ts)
    this.ctx.waitUntil(this.env.CLIPS?.delete(this.keepKey("encounter")) ?? Promise.resolve());
    this.ctx.waitUntil(this.env.CLIPS?.delete(this.keepKey("goal")) ?? Promise.resolve());
    this.ctx.waitUntil(this.env.CLIPS?.delete(this.keepKey("model")) ?? Promise.resolve());
    this.ctx.waitUntil(this.ctx.storage.get<{ anims: { anim: string }[] }>("keep:live").then((l) => {
      this.ctx.storage.delete("keep:live");
      return l ? this.env.CLIPS?.delete([this.keepKey("live/model"), ...l.anims.map((a) => this.keepKey("live/" + a.anim))]) : undefined;
    }));
    this.ctx.waitUntil(this.ctx.storage.get<{ name: string }[]>("keep:motions").then((l) => {
      this.ctx.storage.delete("keep:motions");
      return l && l.length ? this.env.CLIPS?.delete(l.map((m) => this.keepKey("motion/" + m.name))) : undefined;
    }));
  }

  /** GET /game/<channel>?code= (index.ts): the game state kept, for a page while the outstation's dark, and whether the
   *  plugin's connected now; the watch code needed when the channel has one, as watching. */
  /** The register's older lines (11/17/2026; docs/protocol.md "The register"): up to CHAT_PAGE before the line `before`
   *  (its id), oldest first, as a socket has them ({"chat": {...}}'s insides), and whether there are older still. The
   *  watch code as watching. */
  readChat(q: URLSearchParams): Response {
    if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
    if (this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
    const before = parseInt(q.get("before") || "", 10);
    const older = Number.isFinite(before) ? this.chat.filter(([id]) => id < before) : this.chat;
    const page = older.slice(-CHAT_PAGE);
    return json({ lines: page.map(([, line]) => JSON.parse(line).chat), more: older.length > page.length });
  }

  /** The register's older notes (11/17/2026; docs/protocol.md "The register"): up to NOTES_PAGE before the note `before`
   *  (its id), oldest first, as a socket has them ({"note": {...}}'s insides), and whether there are older still. */
  readRegister(q: URLSearchParams): Response {
    if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
    if (this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
    const before = parseInt(q.get("before") || "", 10);
    const older = Number.isFinite(before) ? this.notes.filter(([id]) => id < before) : this.notes;
    const page = older.slice(-this.notesPage());
    return json({ notes: page.map(([, line]) => JSON.parse(line).note), more: older.length > page.length });
  }

  /** The register's notes after the note `seen` (its id; none, all of them), and the newest's id. */
  notesSince(seen: string | null): { n: number; last: number } {
    const after = parseInt(seen || "", 10) || 0;
    let n = 0, last = 0;
    for (const [id] of this.notes) {
      last = id;
      if (id > after) n++;
    }
    return { n, last };
  }

  readGame(q: URLSearchParams): Response {
    if (this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
    if (this.game.empty) return json({ error: "no game state for that outstation" }, 404);
    const heard = this.heard();
    return json({ game: this.game.kept, at: this.game.at, connected: this.sockets("game").length > 0,
                  ...(this.game.snap ? { snap: this.game.snap } : {}),
                  ...(this.seenAt ? { seen: this.seenAt } : {}),
                  ...(heard && this.presence ? { presence: this.presence } : {}) });
  }

  // messages

  // (not async: nothing here waits, and a frame needn't make a promise)
  webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer) {
    const size = typeof msg === "string" ? msg.length : msg.byteLength;
    if (size > MAX_MESSAGE) return;
    const a = this.att(ws);
    if (a.role === "transmit") {
      this.deliver(msg);
      // (seen on air: saved about once a minute, so a stretch that ends unseen still counts up to here)
      const now = Date.now();
      if (this.meta.liveSince && now - (this.meta.liveSeen || 0) > 60_000) { this.meta.liveSeen = now; this.saveMeta(); }
    } else if (a.role === "watch") {
      if (typeof msg === "string" && msg.length <= VIEWER_TEXT) this.fromViewer(ws, a, msg);
    } else if (a.role === "desk") {
      if (typeof msg === "string") this.fromDesk(a.host || this.name, msg, ws);
    } else if (a.role === "tend") {
      if (typeof msg === "string" && msg.length <= VIEWER_TEXT) this.fromTend(ws, a, msg);
    } else if (a.role === "sound") this.fromSound(msg);
    else if (a.role === "game") {
      if (typeof msg === "string") this.fromGame(ws, msg);
    } else if (a.role === "prove") {
      if (typeof msg === "string") this.fromProve(ws, a, msg);
    } else if (a.role === "look" && typeof msg === "string" && msg.length <= VIEWER_TEXT) {
      let m: any;
      try { m = JSON.parse(msg); } catch { return; }
      if (m && typeof m.look === "object" && m.look) {
        const was = !!a.lookFrames;
        a.lookFrames = m.look.frames === true;
        const fresh = this.lastThumb && Date.now() - this.thumbAt <= THUMB_FRESH ? this.lastThumb : null;
        if (a.lookFrames && (fresh || this.lastFrame) && this.live()) {
          this.sendBinary(ws, (fresh || this.lastFrame)!, true);
          a.lookAt = Date.now();
        }
        this.keep(ws, a);
        if (was !== a.lookFrames) this.tellViewers(); // (the transmitter's "lookers")
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string) {
    this.close(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
    this.gone(ws);
  }

  async webSocketError(ws: WebSocket) {
    this.moved();
    this.gone(ws);
  }

  gone(ws: WebSocket) {
    const a = this.att(ws);
    if (a.role === "transmit") {
      if (!this.live()) this.offAir();
    } else if (a.role === "watch") {
      this.seatWaiting();
      this.tellViewers();
    } else if (a.role === "look") {
      if (a.lookFrames) this.tellViewers(); // (the transmitter's "lookers": one fewer)
    } else if (a.role === "desk") {
      if (a.talk) this.tellViewers(); // (the plugin's chat gone: the page's chat goes with it while dark)
      else this.tellApp();
    } else if (a.role === "sound") {
      if (!this.sockets("sound").length) {
        this.soundHeld = [];
        this.meta.soundInfo = pyjson({ sound: { mode: "off", buses: [] } });
        this.saveMeta();
        for (const v of this.seated()) this.send(v, this.meta.soundInfo);
      }
    } else if (a.role === "prove" && a.prover) {
      if (!this.sockets("prove").some((o) => o !== ws && this.att(o).prover === a.prover)) this.witOff(a.prover);
    } else if (a.role === "game") {
      if (!this.sockets("game").length) {
        this.witnessOff(); // (the plugin gone while they were logged in: a logout, as far as the proof goes)
        this.saveGame(); // (the last of it, for a page while the player's away)
        this.dropWhere(); // (following: only while the plugin's connected)
        this.dropActivity(); // (what they're doing, the same)
        this.dropHere(); // (the exact path, the same)
        this.tellViewers();
      }
    }
  }

  /** Time on air from `from` to `to` (ms) into its month's count, keeping the last four months, and into the count of
   *  it all (11/07/2026, for invites). */
  addLive(from: number, to: number) {
    if (to <= from) return;
    const before = this.lifetime(); // (first: a channel from before starts from the months it keeps, this one not yet in)
    const month = new Date(to).toISOString().slice(0, 7);
    const all = this.meta.liveMs || {};
    all[month] = (all[month] || 0) + (to - from);
    for (const m of Object.keys(all).sort().slice(0, -4)) delete all[m];
    this.meta.liveMs = all;
    this.meta.liveAll = before + (to - from) / 1000;
  }

  /** Seconds on air in all, the stretch going on now not included. A channel from before it was counted (11/07/2026)
   *  starts from the months it still keeps, the first time it's asked. */
  lifetime(): number {
    if (this.meta.liveAll === undefined) {
      this.meta.liveAll = Object.values(this.meta.liveMs || {}).reduce((a, b) => a + b, 0) / 1000;
      this.saveMeta();
    }
    return this.meta.liveAll;
  }

  /** Seconds on air in all, the stretch going on now included. */
  secondsInAll(): number {
    return this.lifetime() + (this.meta.liveSince && this.live() ? (Date.now() - this.meta.liveSince) / 1000 : 0);
  }

  /** Seconds on air this month (UTC), the stretch going on now included. */
  secondsThisMonth(): number {
    const now = Date.now(), month = new Date(now).toISOString().slice(0, 7);
    const ms = (this.meta.liveMs || {})[month] || 0;
    const ongoing = this.meta.liveSince && this.live() ? now - Math.max(this.meta.liveSince, Date.parse(month + "-01T00:00:00Z")) : 0;
    return Math.floor((ms + ongoing) / 1000);
  }

  // the transmitter gone (or cut off): viewers hear it, and the upload line stops
  offAir() {
    if (this.live()) return;
    if (this.meta.summary) { this.meta.summary.ended = Date.now(); this.tellSummary(); }
    if (this.meta.liveSince) {
      this.addLive(this.meta.liveSince, Date.now());
      this.meta.liveSince = undefined;
      this.meta.liveSeen = undefined;
      this.saveMeta();
    }
    if (this.uploadTimer) clearInterval(this.uploadTimer);
    this.uploadTimer = null;
    this.soundHeld = [];
    this.tellViewers();
  }

  deliver(msg: string | ArrayBuffer) {
    if (typeof msg === "string" && msg.startsWith('{"clip"')) return; // (the clip key: the client clips for itself)
    if (typeof msg !== "string" && msg.byteLength > 8) {
      const b = new Uint8Array(msg, 4, 2);
      if (b[0] === 0x54 && b[1] === 0x48) { // "TH": a small frame, for lookers only, never a viewer
        this.lastThumb = msg;
        this.thumbAt = Date.now();
        this.toLookers(msg);
        return;
      }
    }
    let summed = false;
    if (typeof msg === "string" && msg.startsWith('{"report"')) {
      this.reports.push(msg);
      this.reports = this.reports.slice(-REPORTS_KEPT);
      this.ctx.storage.put("reports", this.reports);
      let report: unknown = null;
      try { report = JSON.parse(msg).report; } catch { /* not a report after all */ }
      if (this.meta.summary) {
        try { summed = fold(this.meta.summary, report); } catch { /* not one it can read */ }
        if (summed) this.saveMeta();
      }
      this.tellArray(report);
      // (your year: the owner's own events, live or dark, listed or not, to its desks only)
      if (this.sockets("desk").length) {
        let events: ReturnType<typeof arrayEvents> = [];
        try { events = arrayEvents(report); } catch { /* not one it can read */ }
        const at = Date.now();
        for (const event of events) this.tellMine({ event, at });
      }
    } else if (typeof msg === "string") {
      this.lastData = msg;
      if (msg.startsWith('{"state"')) {
        let state = this.meta.state;
        try { state = JSON.parse(msg).state || "open"; } catch { /* keep it */ }
        if (state !== this.meta.state) {
          this.meta.state = state;
          this.saveMeta();
          this.tellViewers(); // (the desks' live light: paused, or back)
        }
      }
    } else {
      this.lastFrame = msg;
    }
    if (typeof msg === "string") {
      for (const v of this.seated()) if (this.send(v, msg)) this.out += msg.length;
      if (summed) this.tellSummary(); // (after the report itself)
    } else {
      for (const v of this.seated()) if (this.sendBinary(v, msg)) this.out += msg.byteLength;
      // (lookers get full frames only from a transmitter that sends no small ones)
      if (Date.now() - this.thumbAt > THUMB_FRESH) this.toLookers(msg);
    }
  }

  summaryLine(): string { return pyjson({ summary: this.meta.summary || null }); }

  tellSummary() {
    if (!this.meta.summary) return;
    const line = this.summaryLine();
    for (const v of [...this.seated(), ...this.sockets("desk"), ...this.sockets("look")]) this.send(v, line);
    this.tellListing(); // (what it's doing: the boss and its count)
  }

  /** What the stream's at now, for the open portals: the boss of the last kill count and its count (live only). */
  doing(): { boss: string; kc: number } | null {
    const sm = this.meta.summary;
    const b = sm && sm.kc && sm.boss ? sm.kc[sm.boss.toLowerCase()] : undefined;
    return b && this.live() ? { boss: b.name, kc: b.last } : null;
  }

  toLookers(msg: ArrayBuffer) {
    const now = Date.now();
    for (const v of this.sockets("look")) {
      const va = this.att(v);
      if (va.lookFrames && now - (va.lookAt || 0) >= LOOK_EVERY && this.sendBinary(v, msg, true)) {
        va.lookAt = now; // (in memory only: frames come only while live, and live keeps the object awake)
        this.out += msg.byteLength;
      }
    }
  }

  fromSound(msg: string | ArrayBuffer) {
    if (typeof msg !== "string") {
      const b = new Uint8Array(msg);
      if (b.length > 8 && b[4] === 0x41 && b[5] === 0x55) { // "AU"
        this.soundHeld.push([Date.now() + SOUND_HOLD, msg]);
        if (!this.soundTimer) this.soundTimer = setTimeout(() => this.releaseSound(), SOUND_HOLD);
      }
    } else if (msg.startsWith('{"sound"') && msg.length <= VIEWER_TEXT) {
      // (kept only when it's changed, 11/07/2026: the client says it every 5 s, and each had been a row written, about
      // 700 an hour while the sound was on; viewers still hear each one)
      if (msg !== this.meta.soundInfo) {
        this.meta.soundInfo = msg;
        this.saveMeta();
      }
      for (const v of this.seated()) this.send(v, msg);
    }
  }

  releaseSound() {
    this.soundTimer = null;
    const now = Date.now();
    while (this.soundHeld.length && this.soundHeld[0][0] <= now) {
      const msg = this.soundHeld.shift()![1];
      // only while the picture is live and open (a pause or a minimise takes it too)
      if (!this.live() || (this.meta.state !== "open" && this.meta.state !== "static")) continue; // (static: the sound goes on)
      for (const v of this.seated()) if (this.sendBinary(v, msg)) this.out += msg.byteLength;
    }
    if (this.soundHeld.length) {
      this.soundTimer = setTimeout(() => this.releaseSound(), Math.max(1, this.soundHeld[0][0] - now));
    }
  }

  tellUpload() {
    const now = Date.now(), [was, at] = this.outSeen;
    this.outSeen = [this.out, now];
    const desks = this.sockets("desk");
    if (!desks.length) return;
    this.ackedSeen.push([this.acked, now]);
    while (this.ackedSeen.length > 2 && now - this.ackedSeen[1][1] >= ACKED_OVER) this.ackedSeen.shift();
    const [had, hadAt] = this.ackedSeen[0];
    // acked: what viewers' pages say they've had, over about the last 5 s (the client's live view)
    const line = pyjson({ upload: { bps: Math.floor(((this.out - was) * 8) / Math.max(0.001, (now - at) / 1000)),
                                    viewers: this.seated().length,
                                    acked: Math.floor(((this.acked - had) * 8) / Math.max(0.001, (now - hadAt) / 1000)) } });
    for (const d of desks) this.send(d, line);
  }

  // the chat

  say(name: string, text: string, host = false, who = 0, queued = false) {
    this.meta.chatId++;
    const id = this.meta.chatId;
    const line = pyjson({ chat: { id, name, text, host, who, queued, at: Date.now() } });
    this.chat.push([id, line]);
    this.chat = this.chat.slice(-CHAT_KEPT);
    this.saveMeta();
    this.saveChat();
    this.broadcast(line);
  }

  note(name: string, text: string, who: number, device: string) {
    this.meta.noteId = (this.meta.noteId || 0) + 1;
    const id = this.meta.noteId;
    const line = pyjson({ note: { id, name, text, who, at: Date.now() } });
    this.notes.push([id, line, device]);
    this.notes = this.notes.slice(-NOTES_KEPT);
    this.saveMeta();
    this.saveNotes();
    this.broadcast(line);
  }

  unnote(id: number) {
    const before = this.notes.length;
    this.notes = this.notes.filter(([i]) => i !== id);
    if (this.notes.length !== before) {
      this.saveNotes();
      this.broadcast(pyjson({ unnote: id }));
    }
  }

  unsay(id: number) {
    const before = this.chat.length;
    this.chat = this.chat.filter(([i]) => i !== id);
    if (this.chat.length !== before) {
      this.saveChat();
      this.broadcast(pyjson({ unsay: id }));
    }
  }

  isBlocked(a: Att): boolean {
    return this.meta.blocked.includes("w:" + a.who) || (!!a.device && this.meta.blocked.includes("d:" + a.device));
  }

  fromViewer(ws: WebSocket, a: Att, msg: string) {
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m)) return;
    const now = Date.now();
    if (typeof m.got === "number" && Number.isFinite(m.got)) {
      const f = this.flowOf(ws);
      if (m.got > f.got) this.acked += m.got - f.got;
      f.got = m.got;
      if (f.got > f.sent) f.sent = f.got; // (the object slept and woke since: start the count again from theirs)
      return;
    }
    if (m.hello && typeof m.hello === "object") {
      const dev = m.hello.device;
      if (typeof dev === "string" && dev.length >= 8 && dev.length <= 64 && !a.device) a.device = dev;
      const name = oneLine(m.hello.name, NAME_MAX);
      a.name = name && !this.meta.hostNames.includes(name.toLowerCase()) ? name : "visitor";
      this.keep(ws, a);
      this.tellWatching();
      return;
    }
    // in line for a seat: chat and votes all the same; pings only once seated, with a frame to point at
    if (!a.seated && "ping" in m) return;
    const blocked = this.isBlocked(a);
    if ("note" in m) {
      // (the register, 11/17/2026: a note signed on the page, NOTE_BURST a NOTE_WINDOW at most; never while banned)
      // (as the aether's lines are, 11/17/2026: up to AETHER_ROWS rows of AETHER_WIDTH, so a drawing signs it whole;
      // one line of the chat's had made Jace's ASCII dog one row, cut off at 300)
      const text = aetherText(m.note);
      const notes = (a.notes || []).filter((t) => now - t <= NOTE_WINDOW);
      if (!text || blocked || notes.length >= NOTE_BURST) { a.notes = notes; this.keep(ws, a); return; }
      notes.push(now);
      a.notes = notes;
      this.keep(ws, a);
      this.note(a.name || "visitor", text, a.who || 0, a.device || "");
      return;
    }
    if ("say" in m) {
      const text = oneLine(m.say, SAY_MAX);
      const says = (a.says || []).filter((t) => now - t <= SAY_WINDOW);
      if (!text || blocked || this.meta.chatOff || says.length >= SAY_BURST) {
        a.says = says;
        this.keep(ws, a);
        return;
      }
      says.push(now);
      a.says = says;
      this.keep(ws, a);
      this.say(a.name || "visitor", text, false, a.who, !a.seated);
    } else if (m.vote && typeof m.vote === "object") {
      const p = this.poll, pick = m.vote.pick;
      if (p && p.open && m.vote.poll === p.id && Number.isInteger(pick) && pick >= 0 && pick < p.options.length) {
        p.votes[a.device || "who:" + a.who] = pick;
        this.savePoll();
        this.tellPoll();
      }
    } else if (m.signal === true) {
      // the signal button: a ✼ over everyone's frame (and a count in the client), once a second at most
      if (blocked || now - (a.lastSignal || 0) < SIGNAL_EVERY) return;
      a.lastSignal = now;
      this.keep(ws, a);
      this.broadcast(pyjson({ signal: { name: a.name || "visitor", who: a.who } }));
    } else if (m.guess && typeof m.guess === "object") {
      const g = this.guess, kc = m.guess.kc;
      if (!g || !g.open || m.guess.id !== g.id || blocked || !Number.isInteger(kc)) return;
      const nowKc = this.nowKc(g.boss) || 0;
      if (!(nowKc < kc && kc <= nowKc + 100000)) return; // (a kill still to come)
      g.guesses[a.device || "who:" + a.who] = { kc, name: a.name || "visitor" };
      this.saveGuess();
      this.tellGuess();
    } else if (m.ping && typeof m.ping === "object") {
      const { x, y } = m.ping;
      const ok = (c: unknown) => typeof c === "number" && c >= 0 && c <= 1;
      if (blocked || this.meta.pingsOff || now - (a.lastPing || 0) < PING_EVERY || !ok(x) || !ok(y)) return;
      a.lastPing = now;
      this.keep(ws, a);
      this.broadcast(pyjson({ ping: { x: Math.round(x * 1e4) / 1e4, y: Math.round(y * 1e4) / 1e4,
                                      name: a.name || "visitor", who: a.who, at: now } }));
    }
  }

  fromDesk(host: string, msg: string, ws?: WebSocket) {
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m)) return;
    if ("dm" in m && ws) {
      this.ctx.waitUntil(this.sendDm(ws, host, m.dm));
    } else if ("say" in m) {
      const text = oneLine(m.say, SAY_MAX * 2);
      if (text) this.say(host, text, true);
    } else if (Number.isInteger(m.hide)) {
      this.unsay(m.hide);
    } else if (Number.isInteger(m.hide_note)) {
      this.unnote(m.hide_note);
    } else if (Number.isInteger(m.block) || Number.isInteger(m.unblock)) {
      const who = Number.isInteger(m.block) ? m.block : m.unblock;
      const tags = new Set(["w:" + who]);
      for (const v of this.sockets("watch")) {
        const va = this.att(v);
        if (va.who === who && va.device) tags.add("d:" + va.device);
      }
      if ("block" in m) this.meta.blocked = [...new Set([...this.meta.blocked, ...tags])];
      else this.meta.blocked = this.meta.blocked.filter((t) => !tags.has(t));
      this.saveMeta();
    } else if (m.poll && typeof m.poll === "object") {
      const p = m.poll;
      const question = oneLine(p.question, 120);
      const options = (Array.isArray(p.options) ? p.options : [])
        .filter((o: unknown) => typeof o === "string").map((o: string) => oneLine(o, 60))
        .filter((o: string) => o).slice(0, POLL_OPTIONS[1]);
      if (options.length < POLL_OPTIONS[0]) return;
      let seconds = parseInt(p.seconds ?? 60);
      if (!Number.isFinite(seconds)) seconds = 60;
      const least = parseFloat(this.env.POLL_MIN_SECONDS || "") || POLL_SECONDS[0];
      seconds = Math.max(least, Math.min(POLL_SECONDS[1], seconds));
      this.meta.pollId++;
      this.saveMeta();
      this.poll = { id: this.meta.pollId, question, options, votes: {}, ends: Date.now() + seconds * 1000, open: true };
      this.savePoll();
      this.tellPoll();
      this.schedule();
    } else if (m.poll_close && this.poll && this.poll.open) {
      this.closePoll();
    } else if (Number.isInteger(m.ban)) {
      // banned: blocked (their device too), and every line of theirs taken back off everyone's page
      const who = m.ban as number;
      const tags = new Set(["w:" + who]);
      let name = "";
      for (const v of this.sockets("watch")) {
        const va = this.att(v);
        if (va.who === who) {
          if (va.device) tags.add("d:" + va.device);
          name = va.name || name;
        }
      }
      name = name || this.lastName(who) || "visitor";
      // (the register's notes are kept for days: the device each came from holds the ban after their socket's gone)
      for (const [, line, dev] of this.notes) if (dev && JSON.parse(line).note.who === who) tags.add("d:" + dev);
      this.meta.blocked = [...new Set([...this.meta.blocked, ...tags])];
      this.meta.bans = [...(this.meta.bans || []).filter((b) => b.who !== who), { who, name, tags: [...tags] }];
      this.saveMeta();
      for (const [id] of this.chat.filter(([, line]) => JSON.parse(line).chat.who === who)) this.unsay(id);
      for (const [id] of this.notes.filter(([, line]) => JSON.parse(line).note.who === who)) this.unnote(id);
      this.tellBanned();
    } else if (Number.isInteger(m.unban)) {
      const gone = (this.meta.bans || []).find((b) => b.who === m.unban);
      const tags = new Set(gone ? gone.tags : ["w:" + m.unban]);
      this.meta.blocked = this.meta.blocked.filter((t) => !tags.has(t));
      this.meta.bans = (this.meta.bans || []).filter((b) => b.who !== m.unban);
      this.saveMeta();
      this.tellBanned();
    } else if (m.guess_open && typeof m.guess_open === "object") {
      const boss = oneLine(m.guess_open.boss, 40);
      if (!boss) return;
      this.meta.guessId = (this.meta.guessId || 0) + 1;
      this.saveMeta();
      this.guess = { id: this.meta.guessId, boss, what: oneLine(m.guess_open.what, 60) || "the next good drop", open: true, guesses: {} };
      this.saveGuess();
      this.tellGuess();
    } else if (m.guess_close && this.guess && this.guess.open) {
      this.guess.open = false; // (no more guesses; still waiting for the drop)
      this.saveGuess();
      this.tellGuess();
    } else if (m.guess_drop && this.guess && !this.guess.result) {
      this.decideGuess(this.nowKc(this.guess.boss), oneLine(typeof m.item === "string" ? m.item : "", 60));
    } else if (m.guess_off && this.guess) {
      const gone = this.guess.id;
      this.guess = null;
      this.saveGuess();
      this.broadcast(pyjson({ guess_gone: gone }));
    } else if (Number.isInteger(m.guess_min_value) && m.guess_min_value >= 0) {
      this.meta.guessMin = m.guess_min_value;
      this.saveMeta();
    } else if (typeof m.pings === "boolean") {
      if (!m.pings !== !!this.meta.pingsOff) {
        this.meta.pingsOff = !m.pings;
        this.saveMeta();
        this.tellViewers();
      }
    } else if (typeof m.chat_on === "boolean") {
      // (11/05/2026, Jace: "add ability to toggle chat off")
      if (!m.chat_on !== !!this.meta.chatOff) {
        this.meta.chatOff = !m.chat_on;
        this.saveMeta();
        this.tellViewers();
      }
    } else if (typeof m.title === "string") {
      // the broadcast's title (11/06/2026): one line, TITLE_MAX long; every status line carries it
      const title = oneLine(m.title, TITLE_MAX);
      if (title !== (this.meta.title || "")) {
        this.meta.title = title;
        this.saveMeta();
        this.tellViewers();
      }
    } else if (Number.isInteger(m.cap) && m.cap >= 0 && m.cap <= 10_000) {
      // (an addition to relay.py's protocol: the channel's own cap on viewers, from the client's settings)
      this.meta.cap = m.cap;
      this.saveMeta();
      this.seatWaiting();
      this.tellViewers();
    }
  }

  pollLine(): string {
    const p = this.poll!;
    const counts = p.options.map(() => 0);
    for (const pick of Object.values(p.votes)) counts[pick]++;
    return pyjson({ poll: { id: p.id, question: p.question, options: p.options, counts, open: p.open,
                            left: p.open ? Math.max(0, Math.round((p.ends - Date.now()) / 1000)) : 0 } });
  }

  tellPoll() { if (this.poll) this.broadcast(this.pollLine()); }

  // the drop guesses: he opens a round for a boss, and viewers guess the kill count the next good drop lands on (one
  // guess a device). Dink's kill counts keep the count; a drop from that boss worth guess_min_value, or his "it
  // dropped", decides it, the closest guess winning. (relay.py's, the same.)
  nowKc(boss: string): number | null {
    const got = (this.meta.kc || {})[String(boss).toLowerCase()];
    return got ? got[1] : null;
  }

  guessLine(): string {
    const g = this.guess!;
    return pyjson({ guess: { id: g.id, boss: g.boss, what: g.what, open: g.open, kc: this.nowKc(g.boss),
                             count: Object.keys(g.guesses).length,
                             result: g.result ? { kc: g.result.kc, item: g.result.item, winners: g.result.winners, off: g.result.off } : null } });
  }

  tellGuess() { if (this.guess) this.broadcast(this.guessLine()); }

  decideGuess(kc: number | null, item: string) {
    const g = this.guess;
    if (!g || g.result) return;
    const picks = kc === null ? [] : Object.values(g.guesses).map((v) => ({ off: Math.abs(v.kc - kc), name: v.name }));
    const best = picks.length ? Math.min(...picks.map((p) => p.off)) : null;
    const winners = best === null ? [] : [...new Set(picks.filter((p) => p.off === best).map((p) => p.name))].sort();
    g.open = false;
    g.result = { kc, item, winners, off: best, at: Date.now() };
    this.saveGuess();
    this.tellGuess();
    if (winners.length) {
      this.say("✼", `${item || "the drop"} at kill ${kc}: ${winners.join(", ")} guessed closest (${best} off)`, true);
    }
    this.schedule();
  }

  // Dink's kill counts and loot, for the guesses: the count kept per boss, and a big enough drop deciding the round
  dinkCounts(p: any) {
    if (!p || typeof p !== "object") return;
    const extra = p.extra && typeof p.extra === "object" ? p.extra : {};
    if (p.type === "KILL_COUNT") {
      const boss = String(extra.boss || "").trim(), count = parseInt(extra.count) || 0;
      if (boss && count > 0) {
        this.meta.kc = { ...(this.meta.kc || {}), [boss.toLowerCase()]: [boss, count] };
        this.saveMeta();
        if (this.guess && this.guess.boss.toLowerCase() === boss.toLowerCase()) this.tellGuess();
      }
    } else if (p.type === "LOOT" && this.guess && !this.guess.result) {
      const source = String(extra.source || "").trim().toLowerCase();
      if (source !== this.guess.boss.toLowerCase()) return;
      const least = this.meta.guessMin ?? GUESS_MIN_VALUE;
      let best: [number, string] | null = null;
      for (const i of Array.isArray(extra.items) ? extra.items : []) {
        const worth = (parseInt(i?.quantity) || 0) * (parseInt(i?.priceEach) || 0);
        if (worth >= least && (!best || worth > best[0])) best = [worth, String(i?.name || "a drop")];
      }
      if (best) {
        const kc = Number.isInteger(parseInt(extra.killCount)) ? parseInt(extra.killCount) : this.nowKc(source);
        this.decideGuess(kc, best[1]);
      }
    }
  }

  closePoll() {
    this.poll!.open = false;
    this.poll!.closed = Date.now();
    this.savePoll();
    this.tellPoll();
    this.schedule();
  }

  pollKept(): number { return (parseFloat(this.env.POLL_KEPT_SECONDS || "") * 1000) || POLL_KEPT; }

  // the poll's ending and its going, by alarm (an alarm outlasts a sleep)
  guessKept(): number { return (parseFloat(this.env.POLL_KEPT_SECONDS || "") * 1000) || GUESS_KEPT; }

  schedule() {
    const due: number[] = [];
    const p = this.poll, g = this.guess;
    if (p) due.push(p.open ? p.ends : (p.closed || 0) + this.pollKept());
    if (g && g.result) due.push(g.result.at + this.guessKept());
    due.push(...this.witDue());
    if (due.length) this.ctx.storage.setAlarm(Math.min(...due));
  }

  async alarm() {
    const p = this.poll, g = this.guess, now = Date.now();
    if (p && p.open && now >= p.ends - 5) this.closePoll();
    else if (p && !p.open && now >= (p.closed || 0) + this.pollKept() - 5) {
      this.poll = null;
      this.savePoll();
      this.broadcast(pyjson({ poll_gone: p.id }));
    }
    if (g && g.result && now >= g.result.at + this.guessKept() - 5) {
      this.guess = null;
      this.saveGuess();
      this.broadcast(pyjson({ guess_gone: g.id }));
    }
    if (this.witDue().some((t) => now >= t - 5)) await this.witnessCheck();
    this.schedule();
  }

  // proving the name from the game itself (11/15/2026; witness.ts, docs/protocol.md "Proving a name"), for each party:
  // "" the page's holder (what the plugin sends on its key), or a challenger (11/15/2026, "Taking a name back"), by
  // its key's hash, whose plugin's messages feed only its own proof

  saveWit() { this.ctx.storage.put("witness", this.wit); this.ctx.storage.put("cwit", this.cwit); }

  /** A party's proof state. */
  witOf(party: string): Wit { return party ? (this.cwit[party] ??= {}) : this.wit; }

  /** A party's name and skills as its plugin last said them: the page's kept state for the holder, else its own. */
  witSaid(party: string): { name?: unknown; skills?: unknown } {
    return party ? (this.cwit[party]?.said || {}) : { name: this.game.kept.name, skills: this.game.kept.skills };
  }

  /** Whether a party's proof is still to be made: the page unproven, and (a challenger) still one. */
  witOpen(party: string): boolean {
    if (!this.meta.unproven || this.meta.proven) return false;
    return !party || (this.meta.challengers || []).some((c) => c.keyHash === party);
  }

  /** The holder's game message, for the proof (its logout, once the message is taken: fromGame). */
  witnessGame(fields: Fields) { this.witStart("", fields); }
  witnessOff() { this.witOff(""); }

  /** A party's game message: the name it's logged in as (only the page's own counts) and each skill's experience as
   *  it changes; a look is due WITNESS_LOOK after the last change. */
  witStart(party: string, fields: Fields) {
    if (!this.witOpen(party) || fields.off === true) return;
    const w = this.witOf(party);
    const n = typeof fields.name === "string" ? fields.name : this.witSaid(party).name;
    if (typeof n !== "string" || !nameFits(n, this.name)) return;
    if (w.name !== n) { w.name = n; w.seen = {}; }
    if (!fields.skills) return;
    const seen = (w.seen ??= {});
    if (noteSaid(seen, reported(fields.skills), Date.now())) {
      w.look = Date.now() + this.witnessLook();
      this.saveWit();
      this.schedule();
    }
  }

  /** A look, while they're logged in: what the hiscores don't show yet is hidden, and the side tab is told whether
   *  logging out now would prove it. (11/17/2026: a post's look is taken at once, postGame; the socket's still
   *  WITNESS_LOOK after its last change.) Left due if the hiscores can't be read, so the alarm tries again. */
  async witLook(party: string) {
    const w = this.witOf(party);
    if (!w.look || !w.name || !w.seen || !this.witOpen(party)) { w.look = undefined; return; }
    const at = Date.now();
    const h = await readXp(w.name, true, this.env.LOOKUP_UPSTREAM || null, USER_AGENT).catch(() => null);
    if (!w.seen) return;
    if (!h) { w.last = "the hiscores couldn't be read just now; trying again in a minute"; w.look = Math.max(w.look || 0, Date.now() + 60_000); return; }
    w.look = undefined;
    noteLook(w.seen, h, at);
    const hidden = Object.values(w.seen).filter((v) => v.hidden).length;
    w.last = hidden ? `ready: ${hidden} skill${hidden > 1 ? "s" : ""} gained that the hiscores don't have yet. Log out now, without`
      + " gaining more experience, and it's checked within a few minutes"
      : "nothing in this update is new to the hiscores yet: gain some experience, stop, then press Update my page";
  }

  witnessLook(): number {
    const t = parseFloat(this.env.WITNESS_LOOK_S || "");
    return t > 0 ? t * 1000 : WITNESS_LOOK;
  }

  /** A party logged out (or its plugin's gone): its hidden figures are claimed, and asked of the hiscores after a while. */
  async witOff(party: string) {
    const w = this.witOf(party);
    if (!this.witOpen(party) || !w.name || !w.seen) return;
    const claims = claimsFrom(w.seen), n = Object.keys(claims).length;
    const keep = party ? { said: w.said } : {};
    // (11/17/2026: each way it can end said apart, with what to do; the agent's read of two friends left unproven)
    const again = "gain some experience, stop, press Update my page, then log out once the side tab says ready";
    const last = n ? `logged out: ${n} skill${n > 1 ? "s" : ""} gained that the hiscores didn't have yet; asking them in a minute`
      : w.look ? "logged out before the hiscores were looked at: next time, wait a minute after your last experience (or, with"
        + " the Hub's plugin, till the side tab says ready) before logging out"
      : Object.keys(w.seen).length ? "logged out with nothing new to the hiscores in what your page last heard: " + again
      : "no update reached your page while you played: " + again;
    const next: Wit = n ? { ...keep, name: w.name, claims, tries: 0, next: Date.now() + this.witnessWait(0), last } : { ...keep, last };
    if (party) { if (this.witOpen(party)) this.cwit[party] = next; } else this.wit = next;
    this.saveWit();
    this.schedule();
  }

  witnessWait(i: number): number {
    const t = parseFloat(this.env.WITNESS_WAIT_S || "");
    return t > 0 ? t * 1000 : WITNESS_WAITS[Math.min(i, WITNESS_WAITS.length - 1)];
  }

  /** The parties' next looks and asks, for the alarm. */
  witDue(): number[] {
    const out: number[] = [];
    for (const w of [this.wit, ...Object.values(this.cwit)]) {
      if (w.look) out.push(w.look);
      if (w.claims && w.next) out.push(w.next);
    }
    return out;
  }

  /** Every party whose ask is due: the hiscores asked again; a claim found there exactly proves it, one not there yet
   *  is asked again later. */
  async witnessCheck() {
    const now = Date.now();
    for (const party of ["", ...Object.keys(this.cwit)]) {
      const w = this.witOf(party);
      if (w.look && now >= w.look - 5) await this.witLook(party);
      if (!w.claims || !w.next || now < w.next - 5) continue;
      if (!w.name || !this.witOpen(party)) { this.witClear(party); continue; }
      const h1 = await readXp(w.name, true, this.env.LOOKUP_UPSTREAM || null, USER_AGENT).catch(() => null);
      const said = h1 ? judge(w.claims, h1) : "wait";
      if (said === "proven" && this.witOpen(party)) { this.witProven(party); return; }
      if (said === "wait" && (w.tries || 0) + 1 < WITNESS_WAITS.length) {
        Object.assign(w, { tries: (w.tries || 0) + 1, next: Date.now() + this.witnessWait((w.tries || 0) + 1),
                           last: h1 ? "the hiscores haven't caught up yet; asking again later" : "the hiscores couldn't be read; asking again later" });
      } else {
        this.witClear(party);
        this.witOf(party).last = said === "no" ? "the hiscores went past what your page last heard: you gained experience after"
          + " your last update (or played somewhere else since). Next time: gain some, stop, press Update my page, then log out"
          + " once the side tab says ready"
          : "the hiscores never showed what was claimed: gain some experience, stop, press Update my page, then log out once"
          + " the side tab says ready";
      }
    }
    this.saveWit();
  }

  witClear(party: string) {
    if (party) { if (this.cwit[party]) this.cwit[party] = { said: this.cwit[party].said, last: this.cwit[party].last }; }
    else this.wit = { last: this.wit.last };
  }

  /** A party proven. The holder: the page is theirs, and every challenge is dropped. A challenger: the name is taken
   *  back (11/15/2026, Jace: "a proven player's claim replaces an unproven holder, and that holder's key stops
   *  working"): its key becomes the page's, the holder's stops, what the holder's plugin sent goes, and the
   *  challenger's plugin is told to come again (4012), when its key opens the page as the holder's. */
  witProven(party: string) {
    const ch = party ? (this.meta.challengers || []).find((c) => c.keyHash === party) : null;
    if (party && !ch) return;
    this.meta.proven = { at: Date.now(), ...((ch ? ch.account : this.meta.account) ? { account: ch ? ch.account : this.meta.account } : {}) };
    delete this.meta.unproven;
    delete this.meta.challengers;
    this.wit = {};
    this.cwit = {};
    if (ch) {
      this.meta.keyHash = ch.keyHash;
      if (ch.account) this.meta.account = ch.account; else delete this.meta.account;
      // (the holder's links to tend the chat go with their key: 11/16/2026, the review's second pass)
      delete this.meta.tends;
      for (const role of ["transmit", "desk", "sound", "game", "tend"] as Role[]) {
        for (const ws of this.sockets(role)) this.close(ws, 4003, "this name was proven by another: its key no longer opens it");
      }
      this.clearGame();
      this.offAir();
    }
    for (const ws of this.sockets("prove")) {
      const a = this.att(ws);
      if (ch && a.prover === party) this.close(ws, 4012, "proven: this page is yours now; connect again");
      else this.close(ws, 4003, "this name was proven by another");
    }
    this.saveMeta();
    this.saveWit();
  }

  // a challenger's plugin (11/15/2026; docs/protocol.md "Taking a name back"): its {"game"} messages kept only for its
  // own proof, never on the page or to anyone watching

  fromProve(ws: WebSocket, a: Att, msg: string) {
    if (ws.readyState !== OPEN || !a.prover) return;
    const now = Date.now(), read = (this.gameReads.get(ws) || []).filter((t) => now - t < 1000);
    this.gameReads.set(ws, read);
    if (read.length >= GAME_A_SECOND || msg.length > GAME_MESSAGE) return;
    read.push(now);
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || !m.game || typeof m.game !== "object" || Array.isArray(m.game)) return;
    const fields = gameFields(m.game), party = a.prover;
    if (!this.witOpen(party)) return;
    this.witStart(party, fields);
    const w = this.witOf(party);
    w.said = { ...(w.said || {}), ...(typeof fields.name === "string" ? { name: fields.name } : {}), ...(fields.skills ? { skills: fields.skills } : {}) };
    if (fields.off === true) this.witOff(party);
  }

  // Dink (a fallback: the client normally takes Dink's posts itself and sends reports as the transmitter)

  dinkSeen = new Map<string, number>(); // a Dink post -> when: the same one again within 10 s is dropped

  // (fromPlugin, 11/15/2026: the Outside plugin's own reports. Till 11/17/2026 they were dropped while the outstation
  // transmitted, as Dink's came through it then; since the Dink trim the outstation passes nothing on, so they're kept)
  async dink(request: Request, key: string, ip: string, fromPlugin = false): Promise<Response> {
    const no = await this.checkKey(key, ip);
    if (no) return new Response(null, { status: 403 });
    void fromPlugin;
    const payload = await dinkPayload(request);
    // (an outstation from before v0.2.0 passing Dink's posts on, trimmed, with its own "at" and without the region the
    // Wilderness is told by: dropped, as it can't be checked; Dink's own posts never carry "at". 11/17/2026, the review
    // before v0.2.0. The player's Dink pointed at the page itself, by the plugin's Copy my address for Dink, reports all)
    if (payload && typeof payload === "object" && typeof (payload as any).at === "number" && (payload as any).regionId === undefined) {
      return new Response(null, { status: 204 });
    }
    // (Dink posts each event once to each address it lists: listed twice, every event came twice)
    const now = Date.now(), seen = JSON.stringify(payload);
    for (const [k, t] of this.dinkSeen) if (now - t > 10_000) this.dinkSeen.delete(k);
    if (this.dinkSeen.has(seen)) return new Response(null, { status: 204 });
    this.dinkSeen.set(seen, now);
    // (nothing from the Wilderness or PvP, whatever Dink sends: text.ts's dinkSafe; 11/16/2026)
    const report = dinkSafe(payload) ? dinkReport(payload) : null;
    if (report) this.deliver(pyjson({ report }));
    this.dinkCounts(payload);
    return new Response(null, { status: 204 });
  }

  // clips on the player's Outside page (clips.ts): the upload and the taking down (its key, or the keeper's: admin,
  // which index.ts has already let through), the list (its watch code, if it has one)

  clipsUploads: number[] = []; // (in memory: a sleep forgets, which only loosens it)

  async clipList(): Promise<Clip[]> {
    const all = await this.ctx.storage.list<Clip>({ prefix: "clip:" });
    const most = parseInt(this.env.CLIPS_MOST || "") || 20, days = parseFloat(this.env.CLIPS_DAYS || "") || 30;
    const { kept, gone } = trim([...all.values()], most, days);
    if (gone.length) await this.dropClips(gone);
    return kept;
  }

  async dropClips(gone: Clip[]) {
    await this.ctx.storage.delete(gone.map((c) => "clip:" + c.id));
    await this.env.CLIPS?.delete(gone.flatMap((c) => [fileKey(this.name, c.id, "mp4"), fileKey(this.name, c.id, "jpg")]));
    for (const c of gone) this.tellClip({ clip_gone: c.id });
  }

  tellClip(msg: object) {
    const line = pyjson(msg);
    for (const v of [...this.seated(), ...this.sockets("desk"), ...this.sockets("look")]) this.send(v, line);
  }

  async clips(request: Request, q: URLSearchParams, ip: string, admin: boolean): Promise<Response> {
    const id = q.get("id") || "";
    if (request.method === "GET") {
      if (!admin && this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
      return json({ clips: await this.clipList() });
    }
    if (!admin) {
      const no = await this.checkKey(q.get("key") || "", ip);
      if (no) return json({ error: no[1] }, 403);
    }
    if (request.method === "DELETE") {
      if (!ID.test(id)) return json({ error: "which clip?" }, 400);
      const c = await this.ctx.storage.get<Clip>("clip:" + id);
      if (!c) return json({ error: "no such clip" }, 404);
      await this.dropClips([c]);
      return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*" } });
    }
    if (request.method !== "POST" || admin) return new Response(null, { status: 405 });
    if (id) {
      // kept for good, or let go again
      if (!ID.test(id)) return json({ error: "which clip?" }, 400);
      const c = await this.ctx.storage.get<Clip>("clip:" + id);
      if (!c) return json({ error: "no such clip" }, 404);
      const keep = q.get("keep") === "1";
      if (keep && !c.kept && (await this.clipList()).filter((x) => x.kept).length >= KEPT_MOST) {
        return json({ error: `${KEPT_MOST} kept at most: let one go first` }, 409);
      }
      if (keep) c.kept = true; else delete c.kept;
      await this.ctx.storage.put("clip:" + id, c);
      this.tellClip({ clip_kept: { id, kept: keep } });
      await this.clipList(); // (let go, the oldest past the most go now)
      return json({ id, kept: keep });
    }
    if (!this.env.CLIPS) return json({ error: "this relay doesn't keep clips" }, 503);
    const now = Date.now(), most = parseInt(this.env.CLIPS_AN_HOUR || "") || 30;
    this.clipsUploads = this.clipsUploads.filter((t) => now - t < 3_600_000);
    if (this.clipsUploads.length >= most) return json({ error: `${most} clips an hour at most: try again later` }, 429);
    const got = await readUpload(request);
    if (Array.isArray(got)) return json({ error: got[1] }, got[0]);
    this.clipsUploads.push(now);
    const clip: Clip = { id: newId(), ...got.meta };
    await this.env.CLIPS.put(fileKey(this.name, clip.id, "mp4"), got.clip, { httpMetadata: { contentType: "video/mp4" } });
    await this.env.CLIPS.put(fileKey(this.name, clip.id, "jpg"), got.strip, { httpMetadata: { contentType: "image/jpeg" } });
    await this.ctx.storage.put("clip:" + clip.id, clip);
    const kept = await this.clipList(); // (the oldest past the most go, and are told gone)
    if (kept.some((c) => c.id === clip.id)) this.tellClip({ clip });
    return json({ id: clip.id }, 201);
  }

  // Recorded fights on the player's page (fights.ts; 11/20/2026). The list in this object's storage ("fight:<id>"), the
  // files in the CLIPS bucket (fights/<channel>/<id>.jsonl.gz), every file served through here so one kept to themselves
  // stays theirs.

  fightsUploads: number[] = []; // (in memory: a sleep forgets, which only loosens it)

  async fightList(): Promise<Fight[]> {
    const all = await this.ctx.storage.list<Fight>({ prefix: "fight:" });
    const { most, days } = fightNumbers(this.env);
    const { kept, gone } = trimFights([...all.values()], most, days);
    if (gone.length) await this.dropFights(gone);
    return kept;
  }

  async dropFights(gone: Fight[]) {
    await this.ctx.storage.delete(gone.map((f) => "fight:" + f.id));
    await this.env.CLIPS?.delete(gone.map((f) => fightKey(this.name, f.id)));
  }

  /** Whether a request is the page's owner: a tending token (Authorization: Bearer) that's good, or the key (?key=).
   *  A key given and wrong is refused (checkKey counts it); none given is just not the owner. */
  async fightOwner(request: Request, q: URLSearchParams, ip: string): Promise<boolean | [number, string]> {
    const auth = request.headers.get("authorization") || "";
    if (auth.startsWith("Bearer ")) {
      if (await this.tendUntil(auth.slice(7).trim())) return true;
      return [403, "this link to your page has run out: open it again from the plugin"];
    }
    if (!q.has("key")) return false;
    const no = await this.checkKey(q.get("key") || "", ip);
    return no ? [403, no[1]] : true;
  }

  /** /fights/<channel>[/<id>] (index.ts, which has checked the switch): the list, an upload, a file, shown or kept, taken
   *  down. fights.ts has the routes. */
  async fights(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
    const owner = await this.fightOwner(request, q, ip);
    if (Array.isArray(owner)) return json({ error: owner[1] }, owner[0]);
    const id = q.get("id") || "";
    if (request.method === "GET" || request.method === "HEAD") {
      if (!owner && this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
      const list = await this.fightList();
      if (q.get("file") === "1") {
        const f = FIGHT_ID.test(id) ? list.find((x) => x.id === id) : undefined;
        if (!f || (!f.public && !owner)) return json({ error: "no such fight" }, 404);
        const r = await serveFile(this.env.CLIPS, fightKey(this.name, f.id), request);
        // (a fight kept to themselves, or on a friends-only page, never kept by a cache along the way; a shown one five
        // minutes, so one hidden again or taken down stops being served soon after)
        const h = new Headers(r.headers);
        h.set("cache-control", !f.public || this.meta.code ? "private, no-store" : "public, max-age=300");
        return new Response(r.body, { status: r.status, headers: h });
      }
      return json({ fights: owner ? list : list.filter((f) => f.public), owner, ...fightNumbers(this.env) });
    }
    if (!owner) return json({ error: "only the page's own" }, 403);
    if (request.method === "DELETE") {
      if (!FIGHT_ID.test(id)) return json({ error: "which fight?" }, 400);
      const f = await this.ctx.storage.get<Fight>("fight:" + id);
      if (!f) return json({ error: "no such fight" }, 404);
      await this.dropFights([f]);
      return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*" } });
    }
    if (request.method !== "POST") return new Response(null, { status: 405 });
    if (id) {
      if (!FIGHT_ID.test(id)) return json({ error: "which fight?" }, 400);
      const f = await this.ctx.storage.get<Fight>("fight:" + id);
      if (!f) return json({ error: "no such fight" }, 404);
      if (q.get("public") !== "1" && q.get("public") !== "0") return json({ error: "public=1 or public=0" }, 400);
      f.public = q.get("public") === "1";
      await this.ctx.storage.put("fight:" + id, f);
      return json({ id, public: f.public });
    }
    if (!this.env.CLIPS) return json({ error: "this relay doesn't keep fights" }, 503);
    const now = Date.now(), most = FIGHTS_AN_HOUR;
    this.fightsUploads = this.fightsUploads.filter((t) => now - t < 3_600_000);
    if (this.fightsUploads.length >= most) return json({ error: `${most} fights an hour at most: try again later` }, 429);
    const got = await readFight(request);
    if (Array.isArray(got)) return json({ error: got[1] }, got[0]);
    this.fightsUploads.push(now);
    const fight: Fight = { id: newFightId(), ...got.meta, public: got.public ?? fightNumbers(this.env).public_default, up: now };
    await this.env.CLIPS.put(fightKey(this.name, fight.id), got.file, { httpMetadata: { contentType: "application/gzip" } });
    await this.ctx.storage.put("fight:" + fight.id, fight);
    await this.fightList(); // (the oldest past the most go)
    return json({ fight }, 201);
  }

  // Invites (11/07/2026; docs/protocol.md, "Invites"): this outstation hands out channels of its own, as the keeper does
  // (index.ts's mint, the same steps), INVITES_START to begin with and one more every INVITE_EVERY_S on air.
  //   GET  ?key=              {"left", "earned", "used", "hours", "next_in_s"}
  //   POST {"key", "player"}  {"channel", "key", "code", "left"}: a new channel for that player, its row in the
  //                           Directory saying it came from this one ("by")
  // An invite is counted only once the new channel has its key, so nothing that fails loses one. While one's being made
  // its name is held here (inviting), so two posts at once can't both take the last invite: this object handles one
  // request at a time, but another can come in while this one waits on the Directory or the new channel.

  invitePosts: number[] = []; // when this outstation last posted for an invite (in memory: a sleep forgets, which only loosens it)
  inviting = new Set<string>();

  async invites(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
    if (request.method !== "GET" && request.method !== "POST") return new Response(null, { status: 405 });
    const body = request.method === "POST" ? ((await request.json().catch(() => null)) as { key?: unknown; player?: unknown } | null) : null;
    const key = request.method === "GET" ? q.get("key") || "" : typeof body?.key === "string" ? body.key : "";
    const no = await this.keyRefusal(key, ip);
    if (no) return json({ error: no[1] }, no[0]);
    const used = () => this.meta.invited || [];
    if (request.method === "GET") return json(inviteNumbers(this.secondsInAll(), used()));
    const now = Date.now();
    this.invitePosts = this.invitePosts.filter((t) => now - t < 3_600_000);
    if (this.invitePosts.length >= INVITES_AN_HOUR) return json({ error: `${INVITES_AN_HOUR} invites an hour at most: try again later` }, 429);
    this.invitePosts.push(now);
    const player = typeof body?.player === "string" ? body.player : "";
    if (!PLAYER.test(player)) return json({ error: "a player's name: 1 to 12 letters, numbers and _, lower case" }, 400);
    if (player === this.name.toLowerCase()) return json({ error: "that's your own outstation" }, 400);
    if (inviteNumbers(this.secondsInAll(), used()).left - this.inviting.size <= 0) {
      return json({ error: `no invites left: one more every ${INVITE_EVERY_S / 3600} hours on air` }, 409);
    }
    const taken = () => json({ error: "that name has an outstation already" }, 409);
    if (this.inviting.has(player)) return taken();
    this.inviting.add(player);
    try {
      const dir = this.dir();
      const row = "https://dir/?name=" + encodeURIComponent(player);
      // the name in the Directory first (only if nobody has it), then the new channel's key; the row taken back if the
      // key can't be made
      let r: Response;
      try {
        r = await dir.fetch(row + "&by=" + encodeURIComponent(this.name), { method: "PUT" });
      } catch {
        this.directory = null; // (a stub that failed: a new one next time, tellDirectory's; not sent again here, as the
        return json({ error: "the invite didn't go through: try again" }, 503); // name may have been taken by it)
      }
      if (r.status === 409) return taken();
      if (!r.ok) return json({ error: "the invite didn't go through: try again" }, 503);
      let minted: Response | null = null;
      try {
        minted = await this.env.CHANNEL.get(this.env.CHANNEL.idFromName(player))
          .fetch(new Request(new URL("/_mint?new=1", request.url).toString(), { method: "POST", headers: { "x-channel": player } }));
      } catch { /* (below) */ }
      const got = minted && minted.ok ? ((await minted.json()) as { key?: string }) : null;
      if (!got?.key) {
        await dir.fetch(row, { method: "DELETE" }).catch(() => null);
        return minted?.status === 409 ? taken() : json({ error: "the invite didn't go through: try again" }, 503);
      }
      this.meta.invited = [...used(), player];
      this.saveMeta();
      return json({ channel: player, key: got.key, code: attachCode(relayOf(request.url), player, got.key),
                    left: inviteNumbers(this.secondsInAll(), used()).left });
    } finally {
      this.inviting.delete(player);
    }
  }

  /** Why an HTTP route's key won't do for this channel, as [its status, its reason], or null: the invites' check. 404
   *  for a channel nobody's handed out; 403 for a key under 8 characters, the wrong key, or past the wrong-key limit
   *  (checkKey's reasons). */
  async keyRefusal(key: string, ip: string): Promise<[number, string] | null> {
    if (this.meta.keyHash === null) return [404, "no outstation called that here"];
    const no = await this.checkKey(key, ip);
    return no ? [403, no[1]] : null;
  }

  // Jace's admin (index.ts checks the admin token before it reaches here)

  // (onlyNew: an invite's, which never takes a channel that has a key already, whatever the Directory says)
  /** Taking a name back (11/15/2026; docs/protocol.md "Taking a name back"): a claim on a name held unproven gives a
   *  challenger's key, which opens only a proving socket; proven, it becomes the page's (witProven). 409 on a name
   *  that's proven, vouched for, or nobody's. */
  async challenge(account: string): Promise<Response> {
    if (this.meta.keyHash === null || !this.meta.unproven || this.meta.proven) return json({ error: "that name has an outstation already" }, 409);
    const bytes = crypto.getRandomValues(new Uint8Array(18));
    const key = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    const now = Date.now(), keyHash = await this.hash(key);
    const list = (this.meta.challengers || []).filter((c) => now - c.at < CHALLENGE_DAYS * 86_400_000);
    for (const gone of list.splice(0, Math.max(0, list.length - (CHALLENGERS - 1)))) delete this.cwit[gone.keyHash];
    list.push({ keyHash, at: now, ...(account ? { account: await this.hash("account:" + account) } : {}) });
    this.meta.challengers = list;
    this.saveMeta();
    this.saveWit();
    return json({ key });
  }

  // (claim: the plugin's own, unproven; anything else (the keeper's, an invite's) is vouched for and clears it)
  async mint(onlyNew = false, claim = false, account = ""): Promise<Response> {
    if (onlyNew && this.meta.keyHash !== null) return json({ error: "that name has an outstation already" }, 409);
    const bytes = crypto.getRandomValues(new Uint8Array(18));
    const key = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    this.meta.keyHash = await this.hash(key);
    delete this.meta.proven;
    delete this.meta.challengers;
    this.wit = {};
    this.cwit = {};
    this.ctx.storage.delete(["witness", "cwit"]);
    if (claim) {
      this.meta.unproven = true;
      if (account) this.meta.account = await this.hash("account:" + account);
    } else {
      delete this.meta.unproven;
      delete this.meta.account;
    }
    if (onlyNew) this.meta.pending = true; // (an invite's: not taken up till its key's used; the keeper's leaves it as it was)
    this.saveMeta();
    delete this.meta.tends; // (the old key's links to tend the chat go with it)
    this.saveMeta();
    for (const role of ["transmit", "desk", "sound", "game", "tend"] as Role[]) {
      for (const ws of this.sockets(role)) this.close(ws, 4003, "this channel has a new key");
    }
    this.clearGame(); // (whoever has the new key starts with none)
    this.offAir();
    return Response.json({ key });
  }

  // kept for the player's page (keep.ts): the bank and the model, one of each, in the channel's own storage

  keepUploads: number[] = []; // (in memory, as clipsUploads)

  async kept(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    const what = q.get("what") || "";
    if (what === "motions" || what === "motion") return this.motions(request, q, ip, what);
    if (what === "live" || what === "livemotion" || what === "livemotions") return this.liveKeep(request, q, ip, what);
    if (!(what in KEEP_MOST)) return json({ error: "kept: bank, deathbank or model" }, 404);
    if (request.method === "GET") {
      if (this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
      const got = await this.ctx.storage.get<{ data?: unknown; at: number; r2?: boolean }>("keep:" + what);
      if (!got) return json({ error: "nothing kept" }, 404);
      if (got.r2) {
        // (a model, with its motion: in the bucket, past what one stored value holds; 11/15/2026)
        const o = await this.env.CLIPS?.get(this.keepKey(what));
        if (!o) return json({ error: "nothing kept" }, 404);
        return new Response('{"' + what + '": ' + (await o.text()) + ', "at": ' + got.at + "}",
                            { headers: { "content-type": "application/json", "cache-control": "no-cache", "access-control-allow-origin": "*" } });
      }
      return json({ [what]: got.data, at: got.at });
    }
    const no = await this.checkKey(q.get("key") || "", ip);
    if (no) return json({ error: no[1] }, 403);
    if (request.method === "DELETE") {
      await this.ctx.storage.delete("keep:" + what);
      await this.env.CLIPS?.delete(this.keepKey(what));
      this.tellClip({ kept: { what, at: null } });
      return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*" } });
    }
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const now = Date.now();
    this.keepUploads = this.keepUploads.filter((t) => now - t < 3_600_000);
    if (this.keepUploads.length >= KEEP_AN_HOUR * 2) return json({ error: `${KEEP_AN_HOUR * 2} an hour at most, every kind together: try again later` }, 429);
    const raw = await request.text();
    if (raw.length > KEEP_MOST[what]) return json({ error: `at most ${KEEP_MOST[what]} bytes` }, 413);
    let d: unknown;
    try { d = JSON.parse(raw); } catch { return json({ error: "not JSON" }, 400); }
    const shaped = keepShape(what, d);
    if (typeof shaped === "string") return json({ error: shaped }, 400);
    this.keepUploads.push(now);
    if ((what === "model" || what === "encounter" || what === "goal") && this.env.CLIPS) {
      await this.env.CLIPS.put(this.keepKey(what), JSON.stringify(shaped), { httpMetadata: { contentType: "application/json" } });
      await this.ctx.storage.put("keep:" + what, { at: now, r2: true });
    } else {
      if (raw.length > 1900 * 1024) return json({ error: "too big for this relay" }, 413);
      await this.ctx.storage.put("keep:" + what, { data: shaped, at: now });
    }
    this.tellClip({ kept: { what, at: now } });
    return json({ what, at: now });
  }

  keepKey(what: string): string { return `keep/${this.name}/${what}.json`; }

  // the live view (keep.ts, 11/15/2026): the model in their gear now, and a live motion an animation in that gear, in the
  // bucket; the list (keep:live: the gear, and each motion's animation and when) in the channel's storage
  //   POST /keep/<ch>/live?key=&gear=          GET /keep/<ch>/live?code=          {"live", "gear", "at"}
  //   POST /keep/<ch>/livemotion?key=&gear=&anim=   GET /keep/<ch>/livemotion?anim=&code=   {"livemotion", "anim", "at"}
  //   GET /keep/<ch>/livemotions?code=          {"gear", "anims": [{"anim", "at", "frames"}]}
  async liveKeep(request: Request, q: URLSearchParams, ip: string, what: string): Promise<Response> {
    type Live = { gear: string; at: number; anims: { anim: string; at: number; frames: number }[] };
    const state = (await this.ctx.storage.get<Live>("keep:live")) || { gear: "", at: 0, anims: [] };
    const headers = { "content-type": "application/json", "cache-control": "no-cache", "access-control-allow-origin": "*" };
    if (request.method === "GET") {
      if (this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
      if (what === "livemotions") return json({ gear: state.gear, anims: state.anims });
      if (what === "live") {
        const o = state.gear ? await this.env.CLIPS?.get(this.keepKey("live/model")) : null;
        if (!o) return json({ error: "nothing live" }, 404);
        return new Response('{"live": ' + (await o.text()) + ', "gear": ' + JSON.stringify(state.gear) + ', "at": ' + state.at + "}", { headers });
      }
      const anim = q.get("anim") || "", row = state.anims.find((a) => a.anim === anim);
      const o = row ? await this.env.CLIPS?.get(this.keepKey("live/" + anim)) : null;
      if (!row || !o) return json({ error: "no such live motion" }, 404);
      return new Response('{"livemotion": ' + (await o.text()) + ', "anim": ' + JSON.stringify(anim) + ', "at": ' + row.at + "}", { headers });
    }
    if (request.method !== "POST" || what === "livemotions") return new Response(null, { status: 405 });
    const no = await this.checkKey(q.get("key") || "", ip);
    if (no) return json({ error: no[1] }, 403);
    if (!this.env.CLIPS) return json({ error: "this relay doesn't keep the live view" }, 503);
    const gear = q.get("gear") || "", anim = q.get("anim") || "";
    if (!GEAR.test(gear)) return json({ error: "gear: the gear's mark" }, 400);
    if (what === "livemotion" && !ANIM.test(anim)) return json({ error: "anim: the animation's id, or idle" }, 400);
    const now = Date.now();
    this.liveUploads = this.liveUploads.filter((t) => now - t < 3_600_000);
    if (this.liveUploads.length >= 240) return json({ error: "too many live uploads this hour" }, 429);
    const raw = await request.text();
    if (raw.length > KEEP_MOST[what]) return json({ error: `at most ${KEEP_MOST[what]} bytes` }, 413);
    let d: unknown;
    try { d = JSON.parse(raw); } catch { return json({ error: "not JSON" }, 400); }
    const shaped = keepShape(what, d);
    if (typeof shaped === "string") return json({ error: shaped }, 400);
    this.liveUploads.push(now);
    if (what === "live") {
      // (a new gear: its live motions start over, the old gear's let go)
      if (gear !== state.gear && state.anims.length) await this.env.CLIPS.delete(state.anims.map((a) => this.keepKey("live/" + a.anim)));
      await this.env.CLIPS.put(this.keepKey("live/model"), JSON.stringify(shaped), { httpMetadata: { contentType: "application/json" } });
      const next: Live = { gear, at: now, anims: gear === state.gear ? state.anims : [] };
      await this.ctx.storage.put("keep:live", next);
      this.tellClip({ kept: { what: "live", gear, at: now } });
      return json({ what, gear, at: now });
    }
    if (gear !== state.gear) return json({ error: "that gear isn't the live one" }, 409);
    let anims = state.anims.filter((a) => a.anim !== anim);
    // (the oldest let go past LIVE_MOST)
    while (anims.length >= LIVE_MOST) {
      const old = anims.reduce((x, y) => (y.at < x.at ? y : x));
      await this.env.CLIPS.delete(this.keepKey("live/" + old.anim));
      anims = anims.filter((a) => a !== old);
    }
    await this.env.CLIPS.put(this.keepKey("live/" + anim), JSON.stringify(shaped), { httpMetadata: { contentType: "application/json" } });
    const frames = ((shaped as Record<string, { v: unknown[] }>).frames?.v || []).length;
    anims.push({ anim, at: now, frames });
    await this.ctx.storage.put("keep:live", { ...state, anims });
    this.tellClip({ kept: { what: "livemotion", gear, anim, at: now } });
    return json({ what, gear, anim, at: now });
  }

  liveUploads: number[] = [];

  // the motion library (keep.ts, 11/15/2026): each motion in the bucket under its name, the list in the channel's storage
  async motions(request: Request, q: URLSearchParams, ip: string, what: string): Promise<Response> {
    const list = (await this.ctx.storage.get<{ name: string; at: number; frames: number }[]>("keep:motions")) || [];
    if (request.method === "GET") {
      if (this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
      // ("first": null, never chosen: the page plays "idle" if there is one)
      if (what === "motions") { const first = await this.ctx.storage.get<string>("keep:motionsFirst"); return json({ motions: list, first: first ?? null }); }
      const name = (q.get("name") || "").toLowerCase(), row = list.find((m) => m.name === name);
      const o = row ? await this.env.CLIPS?.get(this.keepKey("motion/" + name)) : null;
      if (!row || !o) return json({ error: "no such motion" }, 404);
      return new Response('{"motion": ' + (await o.text()) + ', "name": ' + JSON.stringify(name) + ', "at": ' + row.at + "}",
                          { headers: { "content-type": "application/json", "cache-control": "no-cache", "access-control-allow-origin": "*" } });
    }
    const no = await this.checkKey(q.get("key") || "", ip);
    if (no) return json({ error: no[1] }, 403);
    // (11/15/2026, Jace: "allow them to have a static set of gear/animation of their choosing"): the one their page shows
    // first ("" the still model); POST /keep/<channel>/motions?key=&first=<name>
    if (what === "motions") {
      if (request.method !== "POST") return new Response(null, { status: 405 });
      const first = (q.get("first") || "").trim().toLowerCase();
      if (first && !list.some((m) => m.name === first)) return json({ error: "no such motion" }, 404);
      await this.ctx.storage.put("keep:motionsFirst", first);
      this.tellClip({ kept: { what: "motion", name: first, at: Date.now() } });
      return json({ first });
    }
    const name = (q.get("name") || "").trim().toLowerCase();
    if (!MOTION_NAME.test(name)) return json({ error: "a motion's name: 1 to 24 letters, numbers, spaces, - or _" }, 400);
    if (!this.env.CLIPS) return json({ error: "this relay doesn't keep motions" }, 503);
    if (request.method === "DELETE") {
      if (!list.some((m) => m.name === name)) return json({ error: "no such motion" }, 404);
      await this.env.CLIPS.delete(this.keepKey("motion/" + name));
      const rest = list.filter((m) => m.name !== name);
      await this.ctx.storage.put("keep:motions", rest);
      this.tellClip({ kept: { what: "motion", name, at: null } });
      return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*" } });
    }
    if (request.method !== "POST") return new Response(null, { status: 405 });
    const now = Date.now();
    this.keepUploads = this.keepUploads.filter((t) => now - t < 3_600_000);
    if (this.keepUploads.length >= KEEP_AN_HOUR * 2) return json({ error: `${KEEP_AN_HOUR * 2} an hour at most, every kind together: try again later` }, 429);
    const others = list.filter((m) => m.name !== name);
    if (others.length >= MOTIONS_MOST) return json({ error: `${MOTIONS_MOST} motions at most: ::outsideforget one first` }, 409);
    const raw = await request.text();
    if (raw.length > KEEP_MOST.motion) return json({ error: `at most ${KEEP_MOST.motion} bytes` }, 413);
    let d: unknown;
    try { d = JSON.parse(raw); } catch { return json({ error: "not JSON" }, 400); }
    const shaped = keepShape("motion", d);
    if (typeof shaped === "string") return json({ error: shaped }, 400);
    this.keepUploads.push(now);
    await this.env.CLIPS.put(this.keepKey("motion/" + name), JSON.stringify(shaped), { httpMetadata: { contentType: "application/json" } });
    const frames = ((shaped as Record<string, { v: unknown[] }>).frames?.v || []).length;
    await this.ctx.storage.put("keep:motions", [...others, { name, at: now, frames }]);
    this.tellClip({ kept: { what: "motion", name, at: now } });
    return json({ what: "motion", name, at: now });
  }

  // the broadcaster's own emotes (emotes.ts): the list in storage, the pictures in the CLIPS bucket

  emoteUploads: number[] = [];

  async emoteList(): Promise<Emote[]> {
    return (await this.ctx.storage.get<Emote[]>("emotes")) || [];
  }

  async dropEmotes() {
    const all = await this.emoteList();
    if (!all.length) return;
    await this.env.CLIPS?.delete(all.map((e) => emoteKey(this.name, e.id, e.ext)));
    await this.ctx.storage.delete("emotes");
    this.tellClip({ emotes: [] });
  }

  async emotes(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    if (request.method === "GET") {
      if (this.meta.code && (q.get("code") || "") !== this.meta.code) return json({ error: "wrong watch code" }, 403);
      return json({ emotes: await this.emoteList() });
    }
    const no = await this.checkKey(q.get("key") || "", ip);
    if (no) return json({ error: no[1] }, 403);
    const name = q.get("name") || "";
    if (!EMOTE_NAME.test(name)) return json({ error: "a name of 2 to 24 letters, numbers or _" }, 400);
    let list = await this.emoteList();
    const had = list.find((e) => e.name.toLowerCase() === name.toLowerCase());
    if (request.method === "DELETE") {
      if (!had) return json({ error: "no such emote" }, 404);
      await this.env.CLIPS?.delete(emoteKey(this.name, had.id, had.ext));
      list = list.filter((e) => e !== had);
      await this.ctx.storage.put("emotes", list);
      this.tellClip({ emotes: list });
      return new Response(null, { status: 204, headers: { "access-control-allow-origin": "*" } });
    }
    if (request.method !== "POST") return new Response(null, { status: 405 });
    if (!this.env.CLIPS) return json({ error: "this relay doesn't keep emotes" }, 503);
    const now = Date.now();
    this.emoteUploads = this.emoteUploads.filter((t) => now - t < 3_600_000);
    if (this.emoteUploads.length >= EMOTES_AN_HOUR) return json({ error: `${EMOTES_AN_HOUR} uploads an hour at most: try again later` }, 429);
    const body = new Uint8Array(await request.arrayBuffer());
    if (body.length > EMOTE_BYTES) return json({ error: `an emote is ${EMOTE_BYTES / 1024} KB at most` }, 413);
    const ext = emoteKind(body);
    if (!ext) return json({ error: "a PNG, a JPEG or a GIF" }, 415);
    const rest = list.filter((e) => e !== had);
    if (rest.length >= EMOTES_MOST) return json({ error: `${EMOTES_MOST} emotes at most: take one away first` }, 409);
    if (rest.reduce((n, e) => n + e.size, 0) + body.length > EMOTES_BYTES) {
      return json({ error: `${EMOTES_BYTES / 1024 / 1024} MB of emotes at most: take one away first` }, 409);
    }
    this.emoteUploads.push(now);
    const e: Emote = { name, id: newId(), ext, size: body.length, at: now };
    await this.env.CLIPS.put(emoteKey(this.name, e.id, ext), body, { httpMetadata: { contentType: EMOTE_TYPES[ext], cacheControl: "public, max-age=31536000, immutable" } });
    if (had) await this.env.CLIPS.delete(emoteKey(this.name, had.id, had.ext));
    list = [...rest, e];
    await this.ctx.storage.put("emotes", list);
    this.tellClip({ emotes: list });
    return json(e);
  }

  /** DELETE /channels/<channel>?key= (index.ts; 11/16/2026, the permissions review: a player with only the plugin can
   *  take their own page down): everything the channel holds goes, as taking it back does, and with it what taking it
   *  back leaves for the keeper (its clips, chat, reports, summary, time on air, proof). The name is free again
   *  (index.ts drops its row and its year). */
  async takedown(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    if (request.method !== "DELETE") return new Response(null, { status: 405 });
    if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
    const no = await this.checkKey(q.get("key") || "", ip);
    if (no) return json({ error: no[1] }, 403);
    for (const role of ["watch", "look", "tend"] as Role[]) {
      for (const ws of this.sockets(role)) this.close(ws, 4003, "this page was taken down");
    }
    await this.revoke();
    const clips = await this.ctx.storage.list<Clip>({ prefix: "clip:" });
    if (clips.size) await this.dropClips([...clips.values()]);
    const fights = await this.ctx.storage.list<Fight>({ prefix: "fight:" });
    if (fights.size) await this.dropFights([...fights.values()]);
    await this.ctx.storage.deleteAll();
    this.meta = { keyHash: null, code: "", cap: 0, chatId: 0, pollId: 0, nextWho: 0, seq: 0,
                  hostNames: [], blocked: [], soundInfo: null, state: "open" };
    this.chat = [];
    this.notes = [];
    this.poll = null;
    this.reports = [];
    this.guess = null;
    this.wit = {};
    this.cwit = {};
    if (this.name) this.ctx.storage.put("name", this.name);
    return new Response(null, { status: 204 });
  }

  /** PUT /code/<channel>?key= {"code"} (index.ts; 11/16/2026, the permissions review): friends only from the plugin: a
   *  watch code ("" for anyone), viewers whose code no longer matches let go. Answers {"code": true | false}.
   *  (11/17/2026, Jace: "the who-sees-it fix"; the plugin owns who sees the page.) GET, with the key: {"code": "<it>"},
   *  so the outstation reads the code the plugin set rather than keeping one of its own. A transmitter's empty code no
   *  longer clears it (openTransmit). */
  async setCode(request: Request, q: URLSearchParams, ip: string): Promise<Response> {
    if (request.method !== "PUT" && request.method !== "GET") return new Response(null, { status: 405 });
    if (this.meta.keyHash === null) return json({ error: "no outstation called that here" }, 404);
    const no = await this.checkKey(q.get("key") || "", ip);
    if (no) return json({ error: no[1] }, 403);
    if (request.method === "GET") return json({ code: this.meta.code || "" });
    let code: unknown;
    try { code = ((await request.json()) as { code?: unknown }).code; } catch { code = undefined; }
    if (typeof code !== "string" || code.length > 64 || (code !== "" && !/^[A-Za-z0-9_-]{4,64}$/.test(code))) {
      return json({ error: "a watch code: 4 to 64 letters, numbers, - or _ (or none)" }, 400);
    }
    this.meta.code = code;
    this.saveMeta();
    for (const v of [...this.sockets("watch"), ...this.sockets("look")]) {
      if ((this.att(v).code || "") !== code) this.close(v, 4003, "the watch code changed");
    }
    this.tellListing();
    this.tellViewers();
    return json({ code: code !== "" });
  }

  async revoke(): Promise<Response> {
    this.meta.keyHash = null;
    delete this.meta.pending; // (handed out again later, it's pending only if an invite hands it out)
    this.saveMeta();
    delete this.meta.tends;
    for (const role of ["transmit", "desk", "sound", "game", "tend"] as Role[]) {
      for (const ws of this.sockets(role)) this.close(ws, 4003, "this channel was taken back");
    }
    this.clearGame();
    await this.dropEmotes(); // (its emotes go with it: emotes.ts)
    this.offAir();
    return new Response(null, { status: 204 });
  }
}

// Dink's JSON: the payload_json part of a multipart post, or a plain JSON body
/** "0.10.2" as [0, 10, 2]; anything after a "-" or "+" (a pre-release) is left out, a part that isn't a number is 0. */
export function versionNumbers(v: string): number[] {
  const core = v.trim().replace(/^[vV]/, "").split(/[-+]/, 1)[0];
  const parts = core.split(".").slice(0, 3).map((p) => (/^\d+$/.test(p) ? parseInt(p, 10) : 0));
  while (parts.length < 3) parts.push(0);
  return parts;
}

/** Whether a client saying version v is older than min (no min, or no version said: never). */
export function tooOld(v: string | null, min: string): boolean {
  if (!min || v === null) return false;
  const a = versionNumbers(v), b = versionNumbers(min);
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] < b[i];
  return false;
}

async function dinkPayload(request: Request): Promise<unknown> {
  try {
    const type = (request.headers.get("content-type") || "").toLowerCase();
    if (type.startsWith("multipart/")) {
      const form = await request.formData();
      const part = form.get("payload_json");
      if (part === null) return null;
      return JSON.parse(typeof part === "string" ? part : await (part as Blob).text());
    }
    return JSON.parse(await request.text());
  } catch {
    return null;
  }
}

