// Your year's test vectors (docs/record.md, "Encryption"): a fixed record key, device, day, nonce and plaintext, and
// every value made from them, computed by WebCrypto (Node's, the same as a browser's) and Node's zlib. The app's unit
// tests (client/src/recordfmt.rs), both relays' tests and the page check against what this writes.
//
//   node tests/fixtures/record/make_vectors.mjs > tests/fixtures/record/vectors.json
import { webcrypto as wc } from "node:crypto";
import { deflateRawSync } from "node:zlib";

const s = wc.subtle, utf8 = (t) => new TextEncoder().encode(t);
const hex = (b) => Buffer.from(b).toString("hex");
const b64u = (b) => Buffer.from(b).toString("base64url");

const key = Uint8Array.from({ length: 32 }, (_, i) => i);
const nonce = Uint8Array.from({ length: 12 }, (_, i) => 0xa0 + i);
const device = "0123456789abcdef", day = "2026-11-10", part = 0;
const lines = [
  { record: 1, device, day, part },
  { t: 1794268800000, live: true },
  { t: 1794268812345, where: { x: 3264, y: 3264, world: 302 } },
  { t: 1794268812345, activity: { kind: "skilling", skill: "Mining" } },
  { t: 1794268872400, where: { x: 3264, y: 3264, world: 302 }, activity: { kind: "skilling", skill: "Mining" } },
  { t: 1794067301000, event: { kind: "level", skill: "Mining", level: 72 } },
  { t: 1794067500000, where: null },
];
// (each line as relay.py and the app write JSON: ", " and ": ")
const py = (v) => JSON.stringify(v).replace(/,"/g, ', "').replace(/":/g, '": ').replace(/,\{/g, ", {");
const plaintext = lines.map(py).join("\n") + "\n";

const hmacKey = await s.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
const mac = async (t) => new Uint8Array(await s.sign("HMAC", hmacKey, utf8(t)));
const sealKey = await mac("outstation record seal v1");
const readToken = b64u(await mac("outstation record read v1"));
const writeToken = b64u(await mac("outstation record write v1"));
const chunkId = b64u((await mac(`outstation record chunk v1\n${device}\n${day}\n${part}`)).slice(0, 16));

const compressed = deflateRawSync(utf8(plaintext), { level: 9 });
const aes = await s.importKey("raw", sealKey, "AES-GCM", false, ["encrypt", "decrypt"]);
const ct = new Uint8Array(await s.encrypt({ name: "AES-GCM", iv: nonce, additionalData: utf8(chunkId), tagLength: 128 }, aes, compressed));
const sealed = new Uint8Array(1 + 12 + ct.length);
sealed[0] = 1;
sealed.set(nonce, 1);
sealed.set(ct, 13);
// (and back, to be sure)
const back = new Uint8Array(await s.decrypt({ name: "AES-GCM", iv: sealed.slice(1, 13), additionalData: utf8(chunkId), tagLength: 128 }, aes, sealed.slice(13)));
if (hex(back) !== hex(compressed)) throw new Error("didn't round-trip");

const sha = async (t) => hex(await s.digest("SHA-256", utf8(t)));
console.log(JSON.stringify({
  about: "docs/record.md's test vectors, made by tests/fixtures/record/make_vectors.mjs (WebCrypto and Node's zlib)",
  key_hex: hex(key), key_text: "record1:" + b64u(key), key_b64u: b64u(key),
  device, day, part, nonce_hex: hex(nonce), plaintext,
  seal_key_hex: hex(sealKey), read_token: readToken, write_token: writeToken,
  read_token_sha256: await sha(readToken), write_token_sha256: await sha(writeToken),
  chunk_id: chunkId, compressed_hex: hex(compressed), sealed_hex: hex(sealed),
}, null, 2));
