package wiki.outside;

import java.util.Arrays;
import java.util.HashSet;
import net.runelite.api.ChatMessageType;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The recorder's own rules, apart from the game (11/21/2026): what's a restart, which friends-chat lines are kept and
 *  with whose names, which chat ends a fight, the NPC ids listed. In the game: the local world's test fight
 *  (plugin/README.md, "Encounters"). */
public class EncountersTest
{
	@Test
	public void aRestartIsAFrameBackShortOfTheEnd()
	{
		// (the punch three ticks running: frame 5 of 9 back to 0)
		assertTrue(Encounters.isRestart(5, 0, 8));
		// (a loop wraps from its last frame: not a restart)
		assertFalse(Encounters.isRestart(8, 0, 8));
		// (going on, or standing still)
		assertFalse(Encounters.isRestart(3, 4, 8));
		assertFalse(Encounters.isRestart(0, 0, 8));
		// (a loop back to a frame past its start: not one)
		assertFalse(Encounters.isRestart(6, 3, 8));
	}

	@Test
	public void theRaidsCompletionLinesAreKeptAndNothingElseOfTheFriendsChat()
	{
		assertEquals(Encounters.ChatKind.COMPLETION, Encounters.chatKind(ChatMessageType.FRIENDSCHATNOTIFICATION));
		assertTrue(Encounters.completionLine("Congratulations - your raid is complete!"));
		assertTrue(Encounters.completionLine("Team size: 3 players Duration: 24:12 (new personal best)"));
		assertFalse(Encounters.completionLine("Bob Smith has joined the channel."));
		assertFalse(Encounters.completionLine("Special loot: Bob Smith - Twisted bow"));
		assertEquals(Encounters.ChatKind.NEVER, Encounters.chatKind(ChatMessageType.PRIVATECHAT));
		assertEquals(Encounters.ChatKind.NEVER, Encounters.chatKind(ChatMessageType.CLAN_CHAT));
		assertEquals(Encounters.ChatKind.GAME, Encounters.chatKind(ChatMessageType.GAMEMESSAGE));
		assertEquals("Congratulations, <player> and <player>!", Encounters.scrub("Congratulations, Bob Smith and bob_ross!",
			new HashSet<>(Arrays.asList("Bob Smith", "Bob Ross"))));
	}

	@Test
	public void theKillIsTheGamesCountLine()
	{
		assertTrue(Encounters.KILL_LINE.matcher("Your Vorkath kill count is: 418.").matches());
		assertTrue(Encounters.KILL_LINE.matcher("Your completed Chambers of Xeric count is: 12.").matches());
		assertTrue(Encounters.KILL_LINE.matcher("Your Testfight kill count is: 418.").matches());
		assertFalse(Encounters.KILL_LINE.matcher("You have completed 152 hard Treasure Trails.").matches());
	}

	@Test
	public void theListedNpcs()
	{
		assertEquals(new HashSet<>(Arrays.asList(3106, 12077)), Encounters.parseIds("3106, 12077, nope"));
		assertTrue(Encounters.parseIds("").isEmpty());
	}

	/** (11/21/2026, round three) a raid whole: its own completion line told from a boss's inside it, Tombs of Amascut's
	 *  treasure room part of it, its lobby not; a corpse's loot is what the inventory gained */
	@Test
	public void aRaidIsWholeAndItsCompletionIsItsOwn()
	{
		assertTrue(Encounters.raidCompletion("Your completed Chambers of Xeric count is: 12.", "Chambers of Xeric", "Chambers of Xeric"));
		assertTrue(Encounters.raidCompletion("Your completed Fractured Archive count is: 58.", "Fractured Archive", "The Fractured Archive"));
		assertTrue(Encounters.raidCompletion("Your Theatre of Blood count is: 3.", "Theatre of Blood", "Theatre of Blood"));
		assertFalse(Encounters.raidCompletion("Your Ocellus kill count is: 3.", "Ocellus", "The Fractured Archive"));
		Presence.Area toa = Presence.area(14674, 0), room = Presence.area(14672, 0), lobby = Presence.area(13454, 0);
		assertEquals("Tombs of Amascut", toa.name);
		assertTrue(Encounters.sameArea(room, toa));
		assertFalse(Encounters.sameArea(lobby, toa));
		assertFalse(Encounters.sameArea(null, toa));
		assertTrue(Encounters.LOBBIES.contains(lobby.name));
		java.util.Map<Integer, Long> before = new java.util.HashMap<>();
		before.put(995, 100L);
		before.put(385, 3L);
		java.util.List<int[]> got = Encounters.gained(before, Arrays.asList(new int[]{995, 1100}, new int[]{385, 2}, new int[]{29790, 1}, new int[]{385, 1}));
		assertEquals(2, got.size());
		assertArrayEquals(new int[]{995, 1000}, got.get(0));
		assertArrayEquals(new int[]{29790, 1}, got.get(1));
	}

	/** every reward window RuneLite's Loot Tracker reads (1.13.7) is one this recorder reads */
	@Test
	public void theRewardWindowsAreTheLootTrackers()
	{
		java.util.Set<String> sources = new java.util.HashSet<>();
		for (Encounters.Chest c : Encounters.CHESTS)
		{
			sources.add(c.source);
		}
		assertEquals(new HashSet<>(Arrays.asList("Chambers of Xeric", "Theatre of Blood", "Tombs of Amascut", "The Fractured Archive",
			"Barrows", "Lunar Chest", "Fortis Colosseum", "Doom of Mokhaiotl")), sources);
	}

	/** (11/21/2026, round four) the game's pet lines, as Dink reads them, and the lines that can name it after */
	@Test
	public void thePetLines()
	{
		assertTrue(Encounters.PET_LINE.matcher("You have a funny feeling like you're being followed.").matches());
		assertTrue(Encounters.PET_LINE.matcher("You have a funny feeling like you would have been followed...").matches());
		assertTrue(Encounters.PET_LINE.matcher("You feel something weird sneaking into your backpack.").matches());
		assertFalse(Encounters.PET_LINE.matcher("Your Araxxor kill count is: 12.").matches());
		java.util.regex.Matcher m = Encounters.PET_NAMED.matcher("New item added to your collection log: Nid");
		assertTrue(m.matches());
		assertEquals("Nid", m.group(1));
		m = Encounters.PET_NAMED.matcher("Untradeable drop: Dom");
		assertTrue(m.matches());
		assertEquals("Dom", m.group(1));
	}

	/** (round four) the pet joins the stretch it came with, by name */
	@Test
	public void aPetJoinsItsStretch()
	{
		assertEquals("the kill, its drop and a pet", EncounterScene.andAPet("the kill and its drop"));
		assertEquals("the chest and a pet", EncounterScene.andAPet("the chest"));
		assertEquals("the kill, the corpse destroyed and a pet", EncounterScene.andAPet("the kill and the corpse destroyed"));
		assertEquals("the pet", EncounterScene.andAPet(null));
	}

	/** (round four) Doom of Mokhaiotl's forms start a delve */
	@Test
	public void doomsFormsAreADelve()
	{
		assertEquals(new HashSet<>(Arrays.asList(14707, 14708, 14709)), Encounters.DOOM_IDS);
	}
}
