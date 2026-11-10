// Your year (src/record.ts, docs/record.md): the known-key vectors opened with WebCrypto as the page will open them (the
// public summary's check is tests/protocol_test.py's YourYear, against both relays). Node runs it as it is (it strips the types):
//
//   node test/record_test.mts   (or npm test)
import { ok, strictEqual } from "node:assert";
import { readFileSync } from "node:fs";
import { inflateRawSync } from "node:zlib";

const v = JSON.parse(readFileSync(new URL("../../tests/fixtures/record/vectors.json", import.meta.url), "utf8"));
const s = crypto.subtle, utf8 = (t: string) => new TextEncoder().encode(t);
const b64u = (b: Uint8Array) => Buffer.from(b).toString("base64url");
let passed = 0;
const tests: [string, () => Promise<void> | void][] = [];
const test = (name: string, f: () => Promise<void> | void) => tests.push([name, f]);

test("the vectors open as the page opens them: tokens, id, seal key, AES-GCM with the id, raw deflate", async () => {
  const key = Buffer.from(v.key_hex, "hex");
  strictEqual(v.key_text, "record1:" + b64u(key));
  const hk = await s.importKey("raw", key, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = async (t: string) => new Uint8Array(await s.sign("HMAC", hk, utf8(t)));
  strictEqual(b64u(await mac("outstation record read v1")), v.read_token);
  strictEqual(b64u(await mac("outstation record write v1")), v.write_token);
  strictEqual(b64u((await mac(`outstation record chunk v1\n${v.device}\n${v.day}\n${v.part}`)).slice(0, 16)), v.chunk_id);
  const seal = await mac("outstation record seal v1");
  strictEqual(Buffer.from(seal).toString("hex"), v.seal_key_hex);
  const sealed = Buffer.from(v.sealed_hex, "hex");
  strictEqual(sealed[0], 1);
  const aes = await s.importKey("raw", seal, "AES-GCM", false, ["decrypt"]);
  const open = (id: string) => s.decrypt({ name: "AES-GCM", iv: sealed.subarray(1, 13), additionalData: utf8(id), tagLength: 128 }, aes, sealed.subarray(13));
  const plain = inflateRawSync(new Uint8Array(await open(v.chunk_id))).toString("utf8");
  strictEqual(plain, v.plaintext);
  strictEqual(JSON.parse(plain.split("\n")[0]).device, v.device);
  // (under another name, it won't open: the id is its additional data)
  let refused = false;
  try { await open("A".repeat(22)); } catch { refused = true; }
  ok(refused);
});

for (const [name, f] of tests) {
  try { await f(); passed++; console.log("ok   " + name); }
  catch (e) { console.log("FAIL " + name); console.log(e); process.exitCode = 1; }
}
console.log(`${passed} of ${tests.length} passed`);
