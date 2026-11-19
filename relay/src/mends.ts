/* Mends (11/19/2026): a note on a register put right by the keeper, once, where its writer couldn't. Each is checked
   as the channel loads its notes (channel.ts's constructor, so on the first request after a deploy): when the note with
   that id still has that name and exactly the first `cut` rows of `text`, its words become the whole of `text`, and
   it's saved; its id, name, time, writer and device stay. Once mended it no longer matches, so it's never done twice,
   and a mend whose note is gone or changed does nothing. A mend can go once it's been applied (the page shows it).

   SaluteCFlagg's register, note 1: Ja's drawing, 17 rows, signed while a note kept 12 (text.ts's AETHER_ROWS, 32 since
   the same day), so its bottom five were cut. Ja couldn't take it down; SaluteCFlagg was okay with it being put right
   (Jace: "make sure the full ascii shows up on his page, just replace whats there with this full ascii i sent you").
   The first 12 rows below are the 12 the relay kept, character for character. */
import { aetherText, pyjson } from "./text";

export interface Mend { channel: string; id: number; name: string; cut: number; text: string }

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
  }
];

/** The channel's notes ([id, line, device], line its pyjson {"note": {...}}) with its mends applied: true when one was. */
export function mendNotes(channel: string, notes: [number, string, string][], mends: Mend[] = MENDS): boolean {
  let changed = false;
  for (const m of mends) {
    if (m.channel !== channel) continue;
    const n = notes.find(([id]) => id === m.id);
    if (!n) continue;
    let line: any;
    try { line = JSON.parse(n[1]); } catch { continue; }
    const note = line && line.note;
    const whole = aetherText(m.text);
    if (!note || note.name !== m.name || note.text !== whole.split("\n").slice(0, m.cut).join("\n") || note.text === whole) continue;
    note.text = whole;
    n[1] = pyjson({ note });
    changed = true;
  }
  return changed;
}
