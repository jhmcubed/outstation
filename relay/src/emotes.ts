// A broadcaster's own emotes (11/14/2026, Jace: "allow for custom emotes, png/jpg and gifs, only in the broadcaster's
// own chat… the photos should just be an emote menu on the chatbar for that specific outstation"). The outstation
// uploads them (settings ▸ emotes); the page shows them in that outstation's transmission chat only (never the aether),
// where ":name:" is the emote, and lists them in the chat bar's emote menu.
//
//   GET    /emotes/<channel>?code=<watch code>           {"emotes": [{"name", "id", "ext", "size", "at"}]}, oldest first
//   GET    /emotes/<channel>/<id>.<png|jpg|gif>          the picture, straight from R2 (its address can't be guessed)
//   POST   /emotes/<channel>?key=<key>&name=<name>       the picture itself as the body (PNG, JPEG or GIF, by its own
//                                                        first bytes, whatever it's called), a name already there replaced
//   DELETE /emotes/<channel>/<name>?key=<key>            taken away
//
// How many (Jace: "choose whichever amount seems best… based on a decent amount of people using the website and how
// much storage they would use collectively"): EMOTES_MOST (30) an outstation, each at most EMOTE_BYTES (256 KB, as
// Discord's), all of them at most EMOTES_BYTES (4 MB). A thousand outstations full to the brim would be 4 GB in the
// CLIPS bucket, under R2's free 10 GB beside the clips; most hold a handful of small ones. The files are in the CLIPS
// bucket under emotes/<channel>/<id>.<ext>; the list in the channel's own storage. A change goes to the page's viewers
// and desks as {"emotes": [the list]}.

export const EMOTES_MOST = 30, EMOTE_BYTES = 256 * 1024, EMOTES_BYTES = 4 * 1024 * 1024, EMOTES_AN_HOUR = 60;
export const EMOTE_NAME = /^[A-Za-z0-9_]{2,24}$/;
export const EMOTE_ID = /^[A-Za-z0-9_-]{16,32}$/;

export interface Emote { name: string; id: string; ext: "png" | "jpg" | "gif"; size: number; at: number }

export function emoteKey(channel: string, id: string, ext: string): string {
  return `emotes/${channel}/${id}.${ext}`;
}

/** The picture's kind by its own first bytes, or null: not a PNG, a JPEG or a GIF. */
export function emoteKind(b: Uint8Array): "png" | "jpg" | "gif" | null {
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return "png";
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "jpg";
  if (b.length >= 6 && b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38) return "gif";
  return null;
}

export const EMOTE_TYPES: Record<string, string> = { png: "image/png", jpg: "image/jpeg", gif: "image/gif" };
