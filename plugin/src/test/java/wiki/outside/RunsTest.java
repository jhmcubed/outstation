package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The runs (11/21/2026, round six; ghillie's claude/outstation.md, "Encounters, round six"): the Gauntlet, the Corrupted
 *  Gauntlet and the Fortis Colosseum each one recording, started by entering its instance and ended as the game ends
 *  it. The game's side (the click, the map, the chest's inventory) in the local world: tools/local-world's ::testgauntlet
 *  and ::testcolosseum. */
public class RunsTest
{
	@Test
	public void eachRunIsItsInstanceOnly()
	{
		// (RuneLite's Screenshot plugin's GAUNTLET_REGION and CORRUPTED_GAUNTLET_REGION; Discord's Fortis Colosseum)
		assertSame(Encounters.GAUNTLET, Encounters.runAt(7512, true));
		assertSame(Encounters.CORRUPTED, Encounters.runAt(7768, true));
		assertSame(Encounters.COLOSSEUM, Encounters.runAt(7216, true));
		// (7216 not an instance is the city's ground outside the Colosseum; the lobbies are no run)
		assertNull(Encounters.runAt(7216, false));
		assertNull(Encounters.runAt(7316, true));
		assertNull(Encounters.runAt(12127, false));
		assertNull(Encounters.runAt(12850, true));
		assertEquals("The Gauntlet", Encounters.GAUNTLET.name);
		assertEquals("The Corrupted Gauntlet", Encounters.CORRUPTED.name);
		assertEquals("Fortis Colosseum", Encounters.COLOSSEUM.name);
	}

	@Test
	public void theWayInIsItsObjectAndOption()
	{
		// (revision 242's cache: 37340 shows as 36083 "Enter" or 36084 "Enter", "Enter-corrupted"; the arena's 50751)
		assertSame(Encounters.GAUNTLET, Encounters.runEntered(37340, "Enter"));
		assertSame(Encounters.CORRUPTED, Encounters.runEntered(37340, "Enter-corrupted"));
		assertSame(Encounters.CORRUPTED, Encounters.runEntered(36084, "Enter-corrupted"));
		assertSame(Encounters.COLOSSEUM, Encounters.runEntered(50751, "Enter"));
		assertNull(Encounters.runEntered(37340, "Examine"));
		// (the Colosseum's entrance from the city goes to the lobby, not the arena; the Gauntlet Portal to its lobby)
		assertNull(Encounters.runEntered(50749, "Enter"));
		assertNull(Encounters.runEntered(36081, "Enter"));
		assertTrue(Encounters.GAUNTLET_CHESTS.contains(37341));
		assertTrue(Encounters.GAUNTLET_CHESTS.contains(36087));
	}

	@Test
	public void theCountLinesAreReadWithTheGamesColourMacros()
	{
		assertTrue(Encounters.KILL_LINE.matcher("Your Gauntlet completion count is: 12.").matches());
		assertTrue(Encounters.KILL_LINE.matcher("Your Corrupted Gauntlet completion count is: 3.").matches());
		assertTrue(Encounters.KILL_LINE.matcher("Your Sol Heredit kill count is: 1.").matches());
		java.util.regex.Matcher m = Encounters.KILL_LINE.matcher("Your Vorkath kill count is: @mes_hl_red@1,418.");
		assertTrue(m.matches());
		assertEquals("Vorkath", m.group(1));
		assertEquals("1,418", m.group(2));
		// (a Colosseum wave's line is no count line: it never ends the run)
		assertTrue(!Encounters.KILL_LINE.matcher("Wave 3 completed! Wave duration: 0:42.60").matches());
	}

	private static String ends(Encounters.Run run, boolean in, boolean lobby, int out, int kill, int now, boolean looted,
		int endAt, String endWhy)
	{
		return Encounters.runEnding(run, in, lobby, out, kill, now, looted, endAt, endWhy);
	}

	@Test
	public void aGauntletEndsAtItsChestOrLeaving()
	{
		Encounters.Run g = Encounters.GAUNTLET;
		// (preparing: in the instance, nothing ends it)
		assertNull(ends(g, true, false, 0, -1, 900, false, -1, null));
		// (the platform or an escape before the kill: left, a few ticks out)
		assertNull(ends(g, false, true, Encounters.RUN_LEFT, -1, 900, false, -1, null));
		assertEquals("left", ends(g, false, true, Encounters.RUN_LEFT + 1, -1, 900, false, -1, null));
		// (the Hunllef killed: sent to the lobby, it waits for the chest)
		assertNull(ends(g, false, true, 40, 1000, 1040, false, -1, null));
		assertNull(ends(g, false, true, 400, 1000, 1400, false, -1, null));
		// (the chest's loot is loot()'s ending: the rule waits it out)
		assertNull(ends(g, false, true, 12, 1000, 1012, true, 1018, "looted"));
		// (killed, and the lobby left unlooted, or a long wait: completed)
		assertEquals("completed", ends(g, false, false, 200, 1000, 1200, false, -1, null));
		assertEquals("completed", ends(g, false, true, 500, 1000, 1000 + Encounters.RUN_CHEST_WAIT, false, -1, null));
		// (a death is onActorDeath's: an ending already due)
		assertNull(ends(g, false, true, 8, -1, 900, false, 906, "died"));
	}

	@Test
	public void aColosseumEndsAtTheClaimOrLeaving()
	{
		Encounters.Run c = Encounters.COLOSSEUM;
		// (every wave, and Sol Heredit's count: nothing ends it)
		assertNull(ends(c, true, false, 0, 3000, 3005, false, -1, null));
		// (the claim's window open: its ending is due, held till the window shuts)
		assertNull(ends(c, true, false, 0, 3000, 3100, true, 3150, "claimed"));
		// (claimed and out of the arena: ended now)
		assertEquals("claimed", ends(c, false, false, Encounters.RUN_LEFT + 1, 3000, 3120, true, 3150, "claimed"));
		// (out of the arena with nothing claimed: left; a death's ending isn't cut short)
		assertEquals("left", ends(c, false, false, Encounters.RUN_LEFT + 1, -1, 500, false, -1, null));
		assertNull(ends(c, false, false, Encounters.RUN_LEFT + 1, -1, 500, false, 506, "died"));
		assertEquals("the time limit", ends(c, true, false, 0, -1, Encounters.RAID_MOST, false, -1, null));
	}
}
