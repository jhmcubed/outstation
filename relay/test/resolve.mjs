// The Worker's source imports its own files without an extension ("./game"), as wrangler's bundler takes them; Node
// running a test as it is (it strips the types) needs the ".ts". This adds it for a relative import that has none
// (11/17/2026: the Python relay's unit tests of snapshotEvents, dinkSafe and the feed moved here when it was retired).
//   node --import ./test/resolve.mjs test/array_test.mts
import { register } from "node:module";
register("data:text/javascript," + encodeURIComponent(`
export async function resolve(spec, ctx, next) {
  if ((spec.startsWith("./") || spec.startsWith("../")) && !/\\.[cm]?[jt]s$/.test(spec)) {
    try { return await next(spec + ".ts", ctx); } catch {}
  }
  return next(spec, ctx);
}`));
