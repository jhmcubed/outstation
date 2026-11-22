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

	/** Round five (11/21/2026): a window's picture written as small as it goes and not a pixel changed: a palette for 256
	 *  colours or fewer (and 2, 4, 16), 24 bits past that, each read back the same. */
	@Test
	public void picturesAreWrittenSmallAndExact() throws Exception
	{
		for (int colours : new int[]{1, 2, 3, 16, 200, 256, 257, 5000})
		{
			java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(97, 61, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.util.Random rnd = new java.util.Random(colours);
			int[] pal = new int[colours];
			for (int i = 0; i < colours; i++)
			{
				pal[i] = rnd.nextInt(0x1000000);
			}
			for (int y = 0; y < img.getHeight(); y++)
			{
				for (int x = 0; x < img.getWidth(); x++)
				{
					img.setRGB(x, y, pal[(x * 7 + y * 13 + rnd.nextInt(3)) % colours]);
				}
			}
			byte[] png = Encounters.png(img);
			java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
			assertEquals(img.getWidth(), back.getWidth());
			assertEquals(img.getHeight(), back.getHeight());
			for (int y = 0; y < img.getHeight(); y++)
			{
				for (int x = 0; x < img.getWidth(); x++)
				{
					assertEquals("colours " + colours + " at " + x + "," + y, img.getRGB(x, y) & 0xffffff, back.getRGB(x, y) & 0xffffff);
				}
			}
			java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(img, "png", plain);
			assertTrue("colours " + colours + ": " + png.length + " against " + plain.size(), png.length <= plain.size());
		}
	}

	/** Round five (11/21/2026): which clicks are a cross, and which kind: the game's yellow for a walk, its red for an
	 *  option on something in the scene or a spell or item used on one; nothing for the interface's own. */
	@Test
	public void theClickCrossesKinds()
	{
		assertEquals("walk", Encounters.markKind("WALK"));
		assertEquals("act", Encounters.markKind("NPC_SECOND_OPTION"));
		assertEquals("act", Encounters.markKind("PLAYER_FIRST_OPTION"));
		assertEquals("act", Encounters.markKind("GAME_OBJECT_FIRST_OPTION"));
		assertEquals("act", Encounters.markKind("GROUND_ITEM_THIRD_OPTION"));
		assertEquals("act", Encounters.markKind("WIDGET_TARGET_ON_NPC"));
		assertEquals(null, Encounters.markKind("WIDGET_TARGET_ON_WIDGET"));
		assertEquals(null, Encounters.markKind("CC_OP"));
		assertEquals(null, Encounters.markKind("EXAMINE_NPC"));
		assertEquals(null, Encounters.markKind("CANCEL"));
		assertEquals(null, Encounters.markKind(null));
	}

	/** 11/22/2026 (Jace at the Giant Mole, update 6's build: "recording started when i entered moles lair (non-instanced
	 *  location with option of instance, i entered non-instanced location) ... people spend hours here so probably not
	 *  ideal"): an open boss area starts with the fight with its boss; an instance and a raid on entering, as before. */
	@Test
	public void anOpenBossAreaStartsWithItsBossFight()
	{
		Presence.Area mole = Presence.area(6993, 0);
		assertEquals("Giant Mole", mole.name);
		// (the lair itself: nothing on entering; the Mole fought, by any case of its name; not a baby mole)
		assertTrue(Encounters.openArea(mole, false));
		assertFalse(Encounters.startsOnEntry(mole, false));
		assertTrue(Encounters.startsByFight(mole, false, "Giant Mole"));
		assertTrue(Encounters.startsByFight(mole, false, "giant mole"));
		assertTrue(Encounters.startsByFight(mole, false, "Giant\u00A0Mole"));
		assertFalse(Encounters.startsByFight(mole, false, "Baby Mole"));
		assertFalse(Encounters.startsByFight(mole, false, null));
		// (its instance: on entering, as before, and a fight there starts nothing more)
		assertFalse(Encounters.openArea(mole, true));
		assertTrue(Encounters.startsOnEntry(mole, true));
		assertFalse(Encounters.startsByFight(mole, true, "Giant Mole"));
		// (a raid: on entering, never by a fight)
		Presence.Area cox = null;
		for (Presence.Area a : Presence.AREAS)
		{
			if ("Chambers of Xeric".equals(a.name))
			{
				cox = a;
			}
		}
		assertEquals(Presence.RAID, cox.kind);
		assertFalse(Encounters.openArea(cox, false));
		assertTrue(Encounters.startsOnEntry(cox, false));
		assertTrue(Encounters.startsOnEntry(cox, true));
		assertFalse(Encounters.startsByFight(cox, false, "Great Olm"));
		assertFalse(Encounters.startsOnEntry(null, false));
		assertFalse(Encounters.startsByFight(null, false, "Giant Mole"));
		// (areas whose boss has another name: each king, not the area's name)
		Presence.Area dks = Presence.area(11588, 0);
		assertEquals("Dagannoth Kings", dks.name);
		assertTrue(Encounters.startsByFight(dks, false, "Dagannoth Rex"));
		assertTrue(Encounters.startsByFight(dks, false, "Dagannoth Supreme"));
		assertFalse(Encounters.startsByFight(dks, false, "Dagannoth Kings"));
		assertFalse(Encounters.startsByFight(dks, false, "Dagannoth"));
		// (and every name in BOSS_NPCS is one of Discord's boss areas)
		for (String named : Encounters.BOSS_NPCS.keySet())
		{
			boolean found = false;
			for (Presence.Area a : Presence.AREAS)
			{
				found |= a.name.equals(named) && Presence.BOSS.equals(a.kind);
			}
			assertTrue(named, found);
		}
	}

	/** 11/22/2026 (Jace: "it looks like the recording is not stopping after i kill the mole"; another player's hit came
	 *  first, so no kill credit and no count line): the boss they fought dead ends it once they're out of combat a few
	 *  ticks; and in an open area, a fight given up. */
	@Test
	public void theBossDeadWithNoCountLineEndsIt()
	{
		// (no death: never)
		assertFalse(Encounters.bossDown(1000, -1, 0));
		// (dead at 100, their last hit the killing one: BOSS_DOWN after, and QUIET after the last hit)
		assertFalse(Encounters.bossDown(100, 100, 100));
		assertFalse(Encounters.bossDown(100 + Encounters.QUIET - 1, 100, 100));
		assertTrue(Encounters.bossDown(100 + Math.max(Encounters.BOSS_DOWN, Encounters.QUIET), 100, 100));
		// (still fighting after it: another boss, a king's brother, hitting them)
		assertFalse(Encounters.bossDown(130, 100, 125));
		assertTrue(Encounters.bossDown(125 + Encounters.QUIET, 100, 125));
		// (a short wait: under ten seconds)
		assertTrue(Math.max(Encounters.BOSS_DOWN, Encounters.QUIET) + Encounters.DROP_WAIT <= 20);

		// (Jace's first kill walked through: the Mole fought from tick 0, hit every 4 ticks, dead at 60 with no line)
		int dead = 60, endedAt = -1;
		for (int t = 0; t < 200 && endedAt < 0; t++)
		{
			int last = Math.min(t - t % 4, dead);
			if (Encounters.bossDown(t, t >= dead ? dead : -1, last))
			{
				endedAt = t;
			}
		}
		assertEquals(dead + Encounters.QUIET, endedAt);

		// (a fight given up in an open area: the Mole dug in and chased 40 s isn't; 90 s with no hitsplat is)
		assertFalse(Encounters.fightStopped(67, 0));
		assertFalse(Encounters.fightStopped(Encounters.OPEN_IDLE - 1, 0));
		assertTrue(Encounters.fightStopped(Encounters.OPEN_IDLE, 0));
	}

	/** What goes to the page by itself (11/22/2026, Jace: only a fight with a result; leaving once fought is a failure) */
	@Test
	public void sendsByItselfOnlyWithAResult()
	{
		for (String r : new String[]{"killed", "completed", "looted", "claimed", "died", "failed", "stopped by hand"})
		{
			assertTrue(r, Encounters.sendsByItself(r, true, true));
			assertTrue(r, Encounters.sendsByItself(r, true, false));
		}
		assertTrue(Encounters.sendsByItself("left", true, true));
		assertFalse(Encounters.sendsByItself("left", true, false));
		for (String r : new String[]{"the fight stopped", "watched NPCs gone", "the time limit", "logged out or hopped",
			"client closed", "plugin turned off"})
		{
			assertFalse(r, Encounters.sendsByItself(r, true, true));
		}
		// (Record now: however it ended, but never from anywhere dangerous or a part split at the length limit)
		assertTrue(Encounters.sendsByItself("logged out or hopped", false, false));
		assertTrue(Encounters.sendsByItself("client closed", false, false));
		assertFalse(Encounters.sendsByItself("the Wilderness or PvP", false, true));
		assertFalse(Encounters.sendsByItself("the Wilderness or PvP", true, true));
		assertFalse(Encounters.sendsByItself(Encounters.SPLIT, false, true));
	}
}
