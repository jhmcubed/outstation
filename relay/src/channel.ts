// One channel of the portal: its transmitter, its viewers, its desks (the chat window) and its sound.
//
// The same protocol as relay-py/relay.py (docs/protocol.md), as a Durable Object: one object per channel,
// using Cloudflare's WebSocket Hibernation, so a channel with only viewers and the chat waiting (off air)
// sleeps between messages and costs almost nothing. A sleeping object forgets what's in memory, so what
// must outlive a sleep (the key's hash, the watch code, the chat so far, the poll, the blocks, the reports)
// is in the object's own storage; each socket's own state is in its attachment. While a channel is live its
// frames keep it awake, so the last frame lives in memory only.

import { DurableObject } from "cloudflare:workers";
import { dinkReport, oneLine, pyjson } from "./text";
import { type Clip, fileKey, json, newId, readUpload, trim, ID, KEPT_MOST } from "./clips";

export interface Env {
  CHANNEL: DurableObjectNamespace<Channel>;
  DIRECTORY: DurableObjectNamespace;
  ADMIN_TOKEN?: string;
  OPEN_CLAIM?: string; // "1": the first transmitter claims a channel, as relay.py does (local tests only)
  MAX_VIEWERS?: string; // a ceiling on any channel's viewers ("0" or unset: none beyond the channel's own cap)
  MIN_CLIENT?: string; // the oldest outside-portal client let in (its v=); older ones are told to update (4026)
  ASKS_AN_HOUR?: string; // (tests) requests for a portal from one address in an hour (index.ts's ASKS_AN_HOUR)
  POLL_MIN_SECONDS?: string; // (tests: a poll that ends quickly)
  POLL_KEPT_SECONDS?: string;
  CLIPS?: R2Bucket; // the clips on players' Outside pages (clips.ts); unbound, the relay takes none
  CLIPS_MOST?: string; // a channel's clips at most (20)
  CLIPS_DAYS?: string; // and for how long (30 days)
  CLIPS_AN_HOUR?: string; // uploads a channel may make in an hour (30)
}

const MAX_MESSAGE = 512 * 1024; // a frame is 10-70 KB; anything bigger is thrown away
const VIEWER_TEXT = 2048; // anything longer from a viewer is thrown away unread
const CHAT_KEPT = 50;
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
const OPEN = 1; // WebSocket.OPEN
// Flow control: a viewer says {"got": <binary bytes received so far>} about once a second, and past this much
// sent and not yet acknowledged it's sent no more frames or sound until it catches up. (Cloudflare's server side
// shows no send buffer to read, and a viewer that stops reading would otherwise pile up in the object's memory.)
const WINDOW = 4 * 1024 * 1024;

type Role = "transmit" | "watch" | "desk" | "sound" | "look" | "refused";

interface Att {
  role: Role;
  who?: number; // a viewer's number in the chat
  seq?: number; // when it came, for the order of the waiting line
  seated?: boolean;
  code?: string;
  device?: string;
  name?: string;
  says?: number[];
  lastPing?: number;
  host?: string; // a desk's name in the chat
  lastSignal?: number;
  lookFrames?: boolean; // a looker asking for frames
  lookAt?: number;
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
  code: string;
  cap: number; // the channel's own cap on viewers (the desk's {"cap"}): 0 none
  chatId: number;
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
  summary?: Summary | null; // this stream's summary (fold): the readings the same for everyone, and the recap off air
  guessId?: number;
  guessMin?: number; // the desk's {"guess_min_value"}
  kc?: Record<string, [string, number]>; // boss (lower case) -> (its name, the kill count Dink last told of)
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
  ackedSeen: [number, number] = [0, Date.now()];
  outSeen: [number, number] = [0, Date.now()];
  wrong = new Map<string, number[]>();
  flow = new WeakMap<WebSocket, { sent: number; got: number }>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      const got = await ctx.storage.get(["name", "meta", "chat", "poll", "reports", "guess"]);
      this.guess = (got.get("guess") as Guess) || null;
      this.name = (got.get("name") as string) || "";
      this.meta = (got.get("meta") as Meta) || {
        keyHash: null, code: "", cap: 0, chatId: 0, pollId: 0, nextWho: 0, seq: 0,
        hostNames: [], blocked: [], soundInfo: null, state: "open",
      };
      this.chat = (got.get("chat") as [number, string][]) || [];
      this.poll = (got.get("poll") as Poll) || null;
      this.reports = (got.get("reports") as string[]) || [];
    });
  }

  // storage

  saveMeta() { this.ctx.storage.put("meta", this.meta); }
  saveChat() { this.ctx.storage.put("chat", this.chat); }
  savePoll() { this.poll ? this.ctx.storage.put("poll", this.poll) : this.ctx.storage.delete("poll"); }
  saveGuess() { this.guess ? this.ctx.storage.put("guess", this.guess) : this.ctx.storage.delete("guess"); }

  // who's here

  sockets(role: Role): WebSocket[] {
    return this.ctx.getWebSockets(role).filter((ws) => ws.readyState === OPEN);
  }
  att(ws: WebSocket): Att { return ws.deserializeAttachment() as Att; }
  seated(): WebSocket[] { return this.sockets("watch").filter((ws) => this.att(ws).seated); }
  waiting(): WebSocket[] {
    return this.sockets("watch").filter((ws) => !this.att(ws).seated)
      .sort((a, b) => (this.att(a).seq || 0) - (this.att(b).seq || 0));
  }
  live(): boolean { return this.sockets("transmit").length > 0; }
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
  // frames and sound: only as fast as the viewer takes them (WINDOW)
  sendBinary(ws: WebSocket, msg: ArrayBuffer): boolean {
    let f = this.flow.get(ws);
    if (!f) this.flow.set(ws, (f = { sent: 0, got: 0 }));
    if (f.sent - f.got > WINDOW) return false;
    if (!this.send(ws, msg)) return false;
    f.sent += msg.byteLength;
    return true;
  }

  close(ws: WebSocket, code: number, reason: string) {
    try { ws.close(code, reason); } catch { /* gone already */ }
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
    };
    if (place) { st.seat = false; st.place = place; }
    if (you !== undefined) st.you = you;
    return pyjson({ relay: st });
  }

  tellViewers() {
    const line = this.status();
    for (const ws of [...this.seated(), ...this.sockets("desk"), ...this.sockets("look"), ...this.sockets("transmit")]) this.send(ws, line);
    this.waiting().forEach((ws, i) => this.send(ws, this.status(i + 1, this.att(ws).who)));
    this.tellWatching();
  }

  /** Who's watching, for the desk (11/05/2026, Jace: "add viewer list"): each viewer's number, the name it gave in
   *  the chat ("visitor" until it gives one), and whether it's in line. Never a device or address. */
  viewerList(): string {
    const one = (ws: WebSocket, waiting: boolean) => {
      const a = this.att(ws);
      return { who: a.who || 0, name: a.name || "visitor", waiting };
    };
    const seated = this.seated().map((ws) => one(ws, false)).sort((x, y) => x.who - y.who);
    return pyjson({ viewers: [...seated, ...this.waiting().map((ws) => one(ws, true))] });
  }

  tellWatching() {
    const desks = this.sockets("desk");
    if (!desks.length) return;
    const line = this.viewerList();
    for (const d of desks) this.send(d, line);
  }

  broadcast(line: string) {
    for (const ws of [...this.sockets("watch"), ...this.sockets("desk")]) this.send(ws, line);
  }

  seat(ws: WebSocket) {
    const a = this.att(ws);
    a.seated = true;
    ws.serializeAttachment(a);
    this.send(ws, this.status(0, a.who));
    if (this.lastData) this.send(ws, this.lastData);
    if (this.lastFrame && this.live()) this.sendBinary(ws, this.lastFrame); // (off air, no old frame)
    for (const r of this.reports) this.send(ws, r);
    if (this.meta.summary) this.send(ws, this.summaryLine());
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
    for (const [, line] of this.chat) this.send(ws, line);
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

    if (role === "_mint") return this.mint();
    if (role === "_status") {
      const live = this.live();
      return Response.json({ channel: this.name, set: this.meta.keyHash !== null, code: !!this.meta.code, live,
                             open: live && this.meta.state === "open", static: live && this.meta.state === "static",
                             viewers: this.seated().length, title: this.meta.title || "", client: this.meta.client || "",
                             live_s: this.secondsThisMonth(), doing: this.doing() });
    }
    if (role === "_revoke") return this.revoke();
    if (role === "_notice") {
      // the keeper's notice, set or taken down: to every desk here now
      const line = pyjson(await request.json());
      for (const d of this.sockets("desk")) this.send(d, line);
      return new Response(null, { status: 204 });
    }
    if (role === "_dink") return this.dink(request, q.get("key") || "", ip);
    if (role === "_clips") return this.clips(request, q, ip, false);
    if (role === "_clips_admin") return this.clips(request, q, ip, true);

    // the client's version, then the key, so a refused socket never counts as the channel's, even for a moment
    // (a version turned away isn't a wrong key: it doesn't count towards the wrong-key limit)
    const min = this.env.MIN_CLIENT || "";
    const no: [number, string] | null = role === "watch" || role === "look" ? null
      : tooOld(q.get("v"), min) ? [4026, `this portal is too old for the relay: ${min} or newer is needed`]
      : await this.checkKey(q.get("key") || "", ip);
    const pair = new WebSocketPair();
    const [client, ws] = [pair[0], pair[1]];
    this.ctx.acceptWebSocket(ws, [no ? "refused" : role]);
    const a: Att = { role: (no ? "refused" : role) as Role };
    ws.serializeAttachment(a);

    if (no) {
      this.close(ws, no[0], no[1]);
    } else if (role === "watch") {
      this.openWatch(ws, a, q.get("code") || "");
    } else if (role === "look") {
      this.openLook(ws, a, q.get("code") || "");
    } else {
      if (role === "transmit") {
        this.openTransmit(ws, q.get("code") || "", q.get("v"));
      } else if (role === "desk") {
        this.openDesk(ws, a, q.get("name") || "");
        await this.tellNotice(ws);
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
    this.meta.code = code;
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
    // viewers whose code no longer matches go
    for (const v of this.sockets("watch")) {
      if ((this.att(v).code || "") !== code) this.close(v, 4003, "the watch code changed");
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
    ws.serializeAttachment(a);
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
    ws.serializeAttachment(a);
    this.send(ws, this.status());
  }

  openDesk(ws: WebSocket, a: Att, hostName: string) {
    const host = oneLine(hostName, NAME_MAX) || this.name;
    a.host = host;
    ws.serializeAttachment(a);
    if (!this.meta.hostNames.includes(host.toLowerCase())) {
      this.meta.hostNames.push(host.toLowerCase());
      this.saveMeta();
    }
    this.send(ws, this.status());
    this.lateJoiner(ws, "desk");
    this.send(ws, this.bannedLine());
    this.send(ws, this.viewerList());
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
    for (const d of this.sockets("desk")) this.send(d, line);
  }

  lastName(who: number): string {
    for (let i = this.chat.length - 1; i >= 0; i--) {
      const c = JSON.parse(this.chat[i][1]).chat;
      if (c.who === who) return c.name;
    }
    return "";
  }

  openSound(ws: WebSocket) {
    for (const old of this.sockets("sound")) {
      if (old !== ws) this.close(old, 4000, "replaced by a newer sound");
    }
  }

  // messages

  async webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer) {
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
      if (typeof msg === "string") this.fromDesk(a.host || this.name, msg);
    } else if (a.role === "sound") this.fromSound(msg);
    else if (a.role === "look" && typeof msg === "string" && msg.length <= VIEWER_TEXT) {
      let m: any;
      try { m = JSON.parse(msg); } catch { return; }
      if (m && typeof m.look === "object" && m.look) {
        a.lookFrames = m.look.frames === true;
        const fresh = this.lastThumb && Date.now() - this.thumbAt <= THUMB_FRESH ? this.lastThumb : null;
        if (a.lookFrames && (fresh || this.lastFrame) && this.live()) {
          this.sendBinary(ws, (fresh || this.lastFrame)!);
          a.lookAt = Date.now();
        }
        ws.serializeAttachment(a);
      }
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string) {
    this.close(ws, code === 1005 || code === 1006 ? 1000 : code, reason);
    this.gone(ws);
  }

  async webSocketError(ws: WebSocket) {
    this.gone(ws);
  }

  gone(ws: WebSocket) {
    const a = this.att(ws);
    if (a.role === "transmit") {
      if (!this.live()) this.offAir();
    } else if (a.role === "watch") {
      this.seatWaiting();
      this.tellViewers();
    } else if (a.role === "sound") {
      if (!this.sockets("sound").length) {
        this.soundHeld = [];
        this.meta.soundInfo = pyjson({ sound: { mode: "off", buses: [] } });
        this.saveMeta();
        for (const v of this.seated()) this.send(v, this.meta.soundInfo);
      }
    }
  }

  /** Time on air from `from` to `to` (ms) into its month's count, keeping the last four months. */
  addLive(from: number, to: number) {
    if (to <= from) return;
    const month = new Date(to).toISOString().slice(0, 7);
    const all = this.meta.liveMs || {};
    all[month] = (all[month] || 0) + (to - from);
    for (const m of Object.keys(all).sort().slice(0, -4)) delete all[m];
    this.meta.liveMs = all;
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
      if (this.meta.summary) {
        try { summed = fold(this.meta.summary, JSON.parse(msg).report); } catch { /* not a report after all */ }
        if (summed) this.saveMeta();
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
      if (va.lookFrames && now - (va.lookAt || 0) >= LOOK_EVERY && this.sendBinary(v, msg)) {
        va.lookAt = now;
        v.serializeAttachment(va);
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
      this.meta.soundInfo = msg;
      this.saveMeta();
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
    const [had, hadAt] = this.ackedSeen;
    this.ackedSeen = [this.acked, now];
    // acked: what viewers' pages say they've had, a second at a time (the client's live view)
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
      let f = this.flow.get(ws);
      if (!f) this.flow.set(ws, (f = { sent: 0, got: 0 }));
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
      ws.serializeAttachment(a);
      this.tellWatching();
      return;
    }
    // in line for a seat: chat and votes all the same; pings only once seated, with a frame to point at
    if (!a.seated && "ping" in m) return;
    const blocked = this.isBlocked(a);
    if ("say" in m) {
      const text = oneLine(m.say, SAY_MAX);
      const says = (a.says || []).filter((t) => now - t <= SAY_WINDOW);
      if (!text || blocked || this.meta.chatOff || says.length >= SAY_BURST) {
        a.says = says;
        ws.serializeAttachment(a);
        return;
      }
      says.push(now);
      a.says = says;
      ws.serializeAttachment(a);
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
      ws.serializeAttachment(a);
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
      ws.serializeAttachment(a);
      this.broadcast(pyjson({ ping: { x: Math.round(x * 1e4) / 1e4, y: Math.round(y * 1e4) / 1e4,
                                      name: a.name || "visitor", who: a.who, at: now } }));
    }
  }

  fromDesk(host: string, msg: string) {
    let m: any;
    try { m = JSON.parse(msg); } catch { return; }
    if (!m || typeof m !== "object" || Array.isArray(m)) return;
    if ("say" in m) {
      const text = oneLine(m.say, SAY_MAX * 2);
      if (text) this.say(host, text, true);
    } else if (Number.isInteger(m.hide)) {
      this.unsay(m.hide);
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
      this.meta.blocked = [...new Set([...this.meta.blocked, ...tags])];
      this.meta.bans = [...(this.meta.bans || []).filter((b) => b.who !== who), { who, name, tags: [...tags] }];
      this.saveMeta();
      for (const [id] of this.chat.filter(([, line]) => JSON.parse(line).chat.who === who)) this.unsay(id);
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
    this.schedule();
  }

  // Dink (a fallback: the client normally takes Dink's posts itself and sends reports as the transmitter)

  dinkSeen = new Map<string, number>(); // a Dink post -> when: the same one again within 10 s is dropped

  async dink(request: Request, key: string, ip: string): Promise<Response> {
    const no = await this.checkKey(key, ip);
    if (no) return new Response(null, { status: 403 });
    const payload = await dinkPayload(request);
    // (Dink posts each event once to each address it lists: listed twice, every event came twice)
    const now = Date.now(), seen = JSON.stringify(payload);
    for (const [k, t] of this.dinkSeen) if (now - t > 10_000) this.dinkSeen.delete(k);
    if (this.dinkSeen.has(seen)) return new Response(null, { status: 204 });
    this.dinkSeen.set(seen, now);
    const report = dinkReport(payload);
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

  // Jace's admin (index.ts checks the admin token before it reaches here)

  async mint(): Promise<Response> {
    const bytes = crypto.getRandomValues(new Uint8Array(18));
    const key = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    this.meta.keyHash = await this.hash(key);
    this.saveMeta();
    for (const role of ["transmit", "desk", "sound"] as Role[]) {
      for (const ws of this.sockets(role)) this.close(ws, 4003, "this channel has a new key");
    }
    this.offAir();
    return Response.json({ key });
  }

  async revoke(): Promise<Response> {
    this.meta.keyHash = null;
    this.saveMeta();
    for (const role of ["transmit", "desk", "sound"] as Role[]) {
      for (const ws of this.sockets(role)) this.close(ws, 4003, "this channel was taken back");
    }
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

