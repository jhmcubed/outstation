/* Mends (11/19/2026): a note on a register put right by the keeper, once, where its writer couldn't. Each is checked
   as the channel loads its notes (channel.ts's constructor, so on the first request after a deploy): when the note with
   that id still has that name and exactly the first `cut` rows of `text`, its words become the whole of `text`, and
   it's saved; its id, name, time, writer and device stay. A mend with `drop` takes its note away instead, when it still
   has that name and exactly `text`. Once done it no longer matches, so it's never done twice, and a mend whose note is
   gone or changed does nothing. A mend can go once it's been applied (the page shows it).

   SaluteCFlagg's register, note 1: Ja's drawing, 17 rows, signed while a note kept 12 (text.ts's AETHER_ROWS, 32 since
   the same day), so its bottom five were cut. Ja couldn't take it down; SaluteCFlagg was okay with it being put right
   (Jace: "make sure the full ascii shows up on his page, just replace whats there with this full ascii i sent you").
   The first 12 rows below are the 12 the relay kept, character for character. And note 2, the smaller drawing of
   the same boots Ja signed five minutes later to fit the 12 rows, taken away (Jace: "ja wants to delete the smaller
   one", "keep the larger boots that he posted originally"). */
import { aetherText, pyjson } from "./text";

export interface Mend { channel: string; id: number; name: string; cut?: number; drop?: boolean; text: string }

export const MENDS: Mend[] = [
  {
    channel: "salutecflagg", id: 1, name: "Ja", cut: 12,
    text: [
      "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⣠⠃⠀⠀⠀⠀⠀⠀⠀⢨⡀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
      "⠀⠀⠀⠀⠀⠀⠀⠀⡤⠂⠚⣠⠪⢃⠡⠑⠐⠠⠀⠸⡣⠈⠣⡄⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀",
      "⠀⠀⠀⠀⠀⠀⢰⢹⣿⣷⡔⠅⠁⠂⠐⠈⠄⠡⠀⠀⠠⡠⠀⢉⣈⠈⠈⠈⠈⠲⣀⠀⠀⠀",
      "⠀⠀⠀⠀⠀⠀⢐⠰⣿⣿⠫⠀⢸⣶⣧⠀⡈⡀⡔⡜⡜⠐⡈⠝⠛⠄⠐⠐⠐⠀⠈⠲⡀⠀",
      "⠀⠀⠀⠀⠀⠀⠨⠤⡙⣿⣧⡄⢸⣿⡿⣻⣿⣇⠣⠃⠂⡁⠄⠂⠄⠨⠀⡁⢀⠀⠀⠀⡇⠀",
      "⠀⠀⠀⠀⠀⠀⠀⠀⠉⠎⢻⣿⡈⡻⠃⣸⣿⣷⣆⠁⠅⠐⣶⣖⠈⡰⡐⡀⠀⠈⠀⠀⠣⠤",
      "⠀⠀⠀⠀⠀⠀⠀⠀⡖⢣⣿⣿⣿⣿⢖⠈⠛⢟⣇⡄⢠⣤⣿⣿⣀⠐⡀⡢⡁⠀⠀⠀⠀⠨",
      "⠀⠀⠀⠀⠀⠀⢀⡤⣣⢜⣿⣿⢟⠅⠅⠂⠀⢘⢿⢏⢘⠿⡻⠫⠃⡑⢜⠬⡀⠀⠀⠀⠀⠨",
      "⠀⠀⠀⢀⣖⣒⣺⣾⣏⣿⡟⡝⡔⡡⠁⠄⣐⢵⡫⣿⣿⣷⡖⡌⠄⠀⢸⡱⠀⠀⠀⠀⡤⠥",
      "⠀⢀⢬⣵⣿⡿⡯⣣⡳⣧⣗⡇⡓⢦⡱⠀⢊⣽⣽⣻⢟⢌⠂⠂⠀⠀⢕⡕⡁⠀⠀⠀⡇⠀",
      "⠂⣻⣾⣿⣿⣿⣿⣯⢯⡳⡱⢑⠈⠐⣩⣮⣖⡽⣿⡳⡡⡂⢅⠡⠀⠀⢈⠪⠀⠀⠀⠀⡇⠀",
      "⠀⢸⣿⣿⣿⣿⣳⣝⢕⢕⢜⠐⠠⣴⣾⢿⣣⣯⣶⡕⠕⠕⢁⠈⠄⠀⠐⠀⠀⠀⠀⠀⡇⠀",
      "⡀⡸⣿⣿⡿⣯⢷⢕⡝⣜⠐⠁⣨⣿⣿⣷⣷⢿⡓⢍⠐⠀⠵⡵⣢⡢⠀⠀⠀⠂⠀⠀⡇⠀",
      "⠀⠉⠦⡋⠋⠊⠈⠁⠁⠑⢀⣴⣿⣿⣿⡿⡽⣕⢕⠕⡈⠀⠀⠈⠙⠙⠀⠀⡀⠀⢄⡕⠃⠀",
      "⠀⠀⠀⠉⢦⢀⣀⢄⢄⡀⣸⢿⣿⣿⣟⡯⡯⡪⡂⠅⡠⠀⠠⠀⠀⡠⡠⣐⠜⠉⠉⠀⠀⠀",
      "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠈⢎⡛⠋⠊⠑⠉⠈⠊⠈⠀⠁⠀⡀⡴⠚⠀⠀⠀⠀⠀⠀⠀⠀",
      "⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠀⠉⡃⣀⢀⢀⢀⢀⢀⢀⠄⡐⡎⠁⠀⠀⠀⠀⠀⠀⠀⠀⠀"
    ].join("\n")
  },
  {
    channel: "salutecflagg", id: 2, name: "Ja", drop: true,
    text: [
      "⠀⠀⠀⠀⠀⠀⠀⢠⢜⢀⢀⠀⠀⢀⡣⡄⠀⠀⠀⠀⠀⠀⠀⠀",
      "⠀⠀⠀⠀⠠⢼⣭⡑⡊⠄⠂⡈⠄⠀⢁⠈⠳⠤⠤⠤⢤⠀⠀⠀",
      "⠀⠀⠀⠀⢨⢸⣿⡣⠀⣶⣆⣀⡐⡤⠣⠁⠚⠓⠀⠄⠂⠙⢦⠀",
      "⠀⠀⠀⠀⠀⠓⢽⢷⡔⣿⠏⣿⣇⠅⠡⢈⡄⢊⢀⠂⠀⠀⢸⢀",
      "⠀⠀⠀⠀⠀⢠⠺⣼⣷⣶⡂⠛⣿⣀⢁⣼⣿⡀⡁⡢⠀⠀⠀⠐",
      "⠀⠀⠀⣀⢠⣮⢮⣿⢟⢑⠀⠀⣜⢯⣘⣛⠩⠁⠪⡊⠀⠀⠀⡈",
      "⠀⣀⣶⣷⡾⡫⣯⢧⢣⢣⡈⠘⣮⡿⡿⡋⠅⠀⢌⢇⠁⠀⢸⠉",
      "⠒⣿⣿⣿⣾⢿⢝⢍⢂⠈⣮⣮⣻⢻⢔⡐⡈⡀⠀⡃⠀⠀⢸⠀",
      "⣀⣿⣿⣿⢯⡳⡱⡡⠀⣾⣿⣵⢿⠧⠁⠢⣄⢄⠀⠀⢀⠀⢸⠀",
      "⠀⠛⣝⠑⠉⠊⠊⢠⣼⣿⣿⡯⡳⡡⠡⠀⠁⠛⠁⠀⣀⣠⠞⠀",
      "⠀⠀⠈⠓⠒⠒⠒⢺⣻⡿⡻⢪⠣⠊⠄⠄⠀⣠⠖⠚⠁⠀⠀⠀",
      "⠀⠀⠀⠀⠀⠀⠀⠀⠘⢢⢀⠀⡀⡀⡀⡀⡖⠃⠀⠀⠀⠀⠀⠀"
    ].join("\n")
  }
];

/** The channel's notes ([id, line, device], line its pyjson {"note": {...}}) with its mends applied, in place: true when
 *  one was. */
export function mendNotes(channel: string, notes: [number, string, string][], mends: Mend[] = MENDS): boolean {
  let changed = false;
  for (const m of mends) {
    if (m.channel !== channel) continue;
    const at = notes.findIndex(([id]) => id === m.id);
    if (at < 0) continue;
    let line: any;
    try { line = JSON.parse(notes[at][1]); } catch { continue; }
    const note = line && line.note, whole = aetherText(m.text);
    if (!note || note.name !== m.name) continue;
    if (m.drop) {
      if (note.text !== whole) continue;
      notes.splice(at, 1);
    } else {
      if (note.text !== whole.split("\n").slice(0, m.cut || 0).join("\n") || note.text === whole) continue;
      note.text = whole;
      notes[at][1] = pyjson({ note });
    }
    changed = true;
  }
  return changed;
}
