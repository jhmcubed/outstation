// Settling (11/08/2026; docs/protocol.md "Settling"): outside.wiki's front page shows every outstation as a light on a
// globe of Gielinor, and by default an outstation stands beside whoever invited it, so everything clusters round the one
// who began it. Settling lets an outstation's owner choose where it stands: a place on the globe, beside an outstation of
// their choosing, or back to the default.
//
//   POST <relay>/settle/<channel>?key=<key>&v=<version>   the outstation app: its key for a one-time code
//                                                          {"code", "expires"} (no CORS: a page never has the key)
//   GET  <relay>/settle/<channel>?code=<code>              the globe page, before it shows anything:
//                                                          {"channel", "home", "next"} (the code isn't used up)
//   POST <relay>/home/<channel>  {"code", "lon", "lat"} | {"code", "near"} | {"code", "clear": true}
//                                                          the page settles: {"home", "next"}, the code used up
//
// The station key never reaches a web page: the app trades it for a code that lasts SETTLE_CODE_MS, opens the globe page
// with only that, and the page settles with it, once. The first change is free; after it, one every SETTLE_EVERY_MS
// (a clear counts). The home is kept in the channel's meta (channel.ts), so it outlives a sleep, and shows in each row of
// GET /channels. Here: the numbers, the code, what a settle asks for, and the answers' headers. relay-py/relay.py's
// settle_wanted, two_places and Relay.settle, the same.

import { pyjson } from "./text";

// a code lasts SETTLE_CODE_MS (the Worker's SETTLE_CODE_SECONDS shortens it, for tests); after the first change, one every
// SETTLE_EVERY_MS; a POST's body at most SETTLE_BODY bytes; a place's lon within ±LON, its lat within ±LAT
export const SETTLE_CODE_MS = 10 * 60_000, SETTLE_EVERY_MS = 7 * 86_400_000, SETTLE_BODY = 2048;
const LON = 180, LAT = 85;
export const SETTLE_CODE = /^[a-z0-9]{12}$/; // a code, as the app was given it
const ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
const CHANNEL = /^[A-Za-z0-9_.~-]{1,64}$/; // a channel (near)

/** Where an outstation stands: a place (lon and lat, two decimals each) or beside another outstation. */
export type Home = { lon: number; lat: number } | { near: string };

/** The code kept with the channel: only its SHA-256, when it stops working, and whether a settle has used it. */
export interface SettleCode { hash: string; expires: number; used?: boolean }

/** A new code: SETTLE_CODE's 12 characters, each from a random byte (one past 251 is drawn again, so each of the 36 is as
 *  likely). About 62 bits: not to be guessed in its ten minutes. */
export function settleCode(): string {
  let out = "";
  while (out.length < 12) {
    for (const b of crypto.getRandomValues(new Uint8Array(16))) {
      if (b < 252 && out.length < 12) out += ALPHABET[b % 36];
    }
  }
  return out;
}

/** Two decimals: Math.round's (a half goes towards +infinity), as relay.py's two_places. */
export function twoPlaces(x: number): number {
  return Math.round(x * 100) / 100;
}

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const inRange = (v: unknown, most: number): v is number => typeof v === "number" && Number.isFinite(v) && v >= -most && v <= most;

export const SETTLE_SHAPE = "a settle is JSON: a code, and a place (lon and lat), an outstation (near) or clear";

/** What a POST /home body asks for: {home} (null: back to the default), or why not (a 400's reason). Exactly one of a
 *  place (lon, lat), near or clear; anything else in the body is left alone. A near is only looked at for its shape here:
 *  whether it's an outstation, and not this one, is the channel's to ask. */
export function settleWanted(body: unknown): { home: Home | null } | { error: string } {
  if (!isObj(body)) return { error: SETTLE_SHAPE };
  const place = "lon" in body || "lat" in body, near = "near" in body, clear = "clear" in body;
  if (Number(place) + Number(near) + Number(clear) !== 1) {
    return { error: "one of a place (lon and lat), an outstation (near) or clear" };
  }
  if (clear) return body.clear === true ? { home: null } : { error: "clear is true" };
  if (near) {
    return typeof body.near === "string" && CHANNEL.test(body.near) ? { home: { near: body.near } }
      : { error: "no outstation called that here" };
  }
  if (!inRange(body.lon, LON)) return { error: `lon is a number from -${LON} to ${LON}` };
  if (!inRange(body.lat, LAT)) return { error: `lat is a number from -${LAT} to ${LAT}` };
  return { home: { lon: twoPlaces(body.lon), lat: twoPlaces(body.lat) } };
}

// GET /settle and POST /home are a web page's on another origin, with a JSON body: they answer its preflight, and any
// page may read them. POST /settle is the app's alone, with the key in the address, and has no CORS.
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "content-type",
};

/** One of settling's answers (body null: none), never kept by a cache (it's a code's); page: with CORS. */
export function settleAnswer(body: unknown, status: number, page: boolean): Response {
  const headers: Record<string, string> = { "cache-control": "no-store", ...(page ? CORS : {}) };
  if (body !== null) headers["content-type"] = "application/json";
  return new Response(body === null ? null : pyjson(body), { status, headers });
}

/** The answer to a page's preflight (OPTIONS), on /settle and /home. */
export function settlePreflight(): Response {
  return new Response(null, { status: 204, headers: { ...CORS, "access-control-max-age": "86400" } });
}
