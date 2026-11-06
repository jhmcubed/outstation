// Clips on a player's Outside page (11/06/2026, Jace: "store automatically taken clips on people's individual Outside
// pages"). A portal that's been told to ("put my clips on my Outside page", off to begin with) uploads the clip it
// saved by itself (a pet, a new log slot, a 99, a big drop): a standard copy (17 s round the moment, at most 768 px wide),
// its still, and its flipbook (a dozen small frames in one JPEG, for the hover). docs/protocol.md, "Clips".
//
//   POST   /clips/<channel>?key=<key>         multipart: "meta" (JSON), "strip" (image/jpeg), "clip" (video/mp4)
//   GET    /clips/<channel>?code=<watch code> the list, newest first: {"clips": [{id, what, kind, at, dur, w, h, still,
//                                             frames, fps, size}]}
//   GET    /clips/<channel>/<id>.mp4          the clip (ranges too, as a video wants)   } straight from R2: their
//   GET    /clips/<channel>/<id>.jpg          its flipbook                              } addresses can't be guessed
//   DELETE /clips/<channel>/<id>?key=<key>    its own portal takes one down
//   GET    /admin/clips/<channel>, DELETE /admin/clips/<channel>/<id>                       the keeper's
//
// The files are in the CLIPS bucket under clips/<channel>/<id>; the list is in the channel's own storage. At most
// CLIPS_MOST (20) a channel and CLIPS_DAYS (30) days each, the oldest going first; each upload at most MAX_CLIP.

export const MAX_CLIP = 8 * 1024 * 1024;
export const MAX_STRIP = 256 * 1024;
export const MAX_STILL = 16 * 1024; // (base64, in the list)
export const MAX_DUR = 20;
export const ID = /^[A-Za-z0-9_-]{16,32}$/;

export interface Clip {
  id: string;
  what: string; // "pet vorki", "drop 4.2m skeletal visage"
  kind: string; // Dink's: PET, COLLECTION, LOOT
  at: number; // when Dink told of it (s)
  dur: number; // its length (s)
  w: number;
  h: number;
  still: string; // a small JPEG, base64: the moment
  frames: number; // the flipbook's frames (one under another in its JPEG, each the still's size)
  fps: number; // and how fast they go
  size: number; // the clip's bytes
}

export function fileKey(channel: string, id: string, ext: "mp4" | "jpg"): string {
  return `clips/${channel}/${id}.${ext}`;
}

export function newId(): string {
  const b = crypto.getRandomValues(new Uint8Array(16));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const CORS = { "access-control-allow-origin": "*" };

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-cache", ...CORS } });
}

// A clip's file, straight from the bucket: the whole of it or the range asked for (a video's player asks in ranges).
export async function serveFile(bucket: R2Bucket | undefined, key: string, request: Request): Promise<Response> {
  if (!bucket) return new Response(null, { status: 404, headers: CORS });
  const ranged = request.headers.has("range");
  const obj = await bucket.get(key, ranged ? { range: request.headers } : {});
  if (!obj) return new Response(null, { status: 404, headers: CORS });
  const h = new Headers(CORS);
  obj.writeHttpMetadata(h);
  h.set("etag", obj.httpEtag);
  h.set("accept-ranges", "bytes");
  h.set("cache-control", "public, max-age=31536000, immutable");
  const r = (obj as R2ObjectBody & { range?: { offset?: number; length?: number; suffix?: number } }).range;
  if (ranged && r) {
    const offset = r.suffix !== undefined ? Math.max(0, obj.size - r.suffix) : r.offset || 0;
    const length = r.suffix !== undefined ? obj.size - offset : r.length ?? obj.size - offset;
    h.set("content-range", `bytes ${offset}-${offset + length - 1}/${obj.size}`);
    h.set("content-length", String(length));
    return new Response(request.method === "HEAD" ? null : obj.body, { status: 206, headers: h });
  }
  h.set("content-length", String(obj.size));
  return new Response(request.method === "HEAD" ? null : obj.body, { status: 200, headers: h });
}

function isMp4(b: Uint8Array): boolean {
  return b.length > 12 && b[4] === 0x66 && b[5] === 0x74 && b[6] === 0x79 && b[7] === 0x70; // "ftyp"
}
function isJpeg(b: Uint8Array): boolean {
  return b.length > 4 && b[0] === 0xff && b[1] === 0xd8;
}

// An upload's parts, checked: the clip's entry (without its id) and its two files, or why not (a status and words).
export async function readUpload(request: Request): Promise<{ meta: Omit<Clip, "id">; clip: Uint8Array; strip: Uint8Array } | [number, string]> {
  const n = parseInt(request.headers.get("content-length") || "0") || 0;
  if (n > MAX_CLIP + MAX_STRIP + 64 * 1024) return [413, "a clip is 8 MB at most"];
  let form: FormData;
  try { form = await request.formData(); } catch { return [400, "an upload is multipart: meta, strip and clip"]; }
  const clipF = form.get("clip"), stripF = form.get("strip"), metaS = form.get("meta");
  if (!(clipF instanceof File) || !(stripF instanceof File) || typeof metaS !== "string") return [400, "an upload is multipart: meta, strip and clip"];
  if (clipF.size > MAX_CLIP) return [413, "a clip is 8 MB at most"];
  if (stripF.size > MAX_STRIP) return [413, "a flipbook is 256 KB at most"];
  const clip = new Uint8Array(await clipF.arrayBuffer()), strip = new Uint8Array(await stripF.arrayBuffer());
  if (!isMp4(clip)) return [415, "the clip isn't an .mp4"];
  if (!isJpeg(strip)) return [415, "the flipbook isn't a JPEG"];
  let m: Record<string, unknown>;
  try { m = JSON.parse(metaS); } catch { return [400, "meta isn't JSON"]; }
  const num = (v: unknown, lo: number, hi: number) => (typeof v === "number" && isFinite(v) ? Math.min(hi, Math.max(lo, v)) : lo);
  const text = (v: unknown, most: number) => (typeof v === "string" ? v.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, most) : "");
  const still = text(m.still, MAX_STILL + 1);
  if (still.length > MAX_STILL || !/^[A-Za-z0-9+/=]*$/.test(still)) return [413, "the still is 16 KB at most, base64"];
  const now = Date.now() / 1000;
  const meta = {
    what: text(m.what, 60) || "a clip", kind: text(m.kind, 24).toUpperCase(), at: Math.round(num(m.at, now - 86400, now + 60)),
    dur: Math.round(num(m.dur, 0, MAX_DUR) * 10) / 10, w: Math.round(num(m.w, 0, 4096)), h: Math.round(num(m.h, 0, 4096)),
    still, frames: Math.round(num(m.frames, 1, 60)), fps: Math.round(num(m.fps, 1, 30) * 10) / 10, size: clip.length,
  };
  if (!meta.dur) return [400, "a clip needs its length (dur, up to 20 s)"];
  return { meta, clip, strip };
}

// The list without what's too old or past the most, and what was dropped (whose files go too).
export function trim(list: Clip[], most: number, days: number, now = Date.now()): { kept: Clip[]; gone: Clip[] } {
  const sorted = [...list].sort((a, b) => b.at - a.at);
  const kept: Clip[] = [], gone: Clip[] = [];
  for (const c of sorted) {
    if (kept.length < most && now / 1000 - c.at <= days * 86400) kept.push(c);
    else gone.push(c);
  }
  return { kept, gone };
}
