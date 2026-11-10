package wiki.outside;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What they're doing (Activity, 11/08/2026): one coarse label, from their own experience and their own fight; and it
 *  two minutes behind, once a minute, cleared somewhere dangerous (Behind, as following's square). */
public class ActivityTest
{
	static String label(JsonObject a)
	{
		String kind = a.get("kind").getAsString();
		if (kind.equals("skilling"))
		{
			assertEquals(2, a.size());
			return "skilling " + a.get("skill").getAsString();
		}
		if (kind.equals("boss"))
		{
			assertEquals(2, a.size());
			return "boss " + a.get("boss").getAsString();
		}
		assertEquals(1, a.size());
		return kind;
	}

	@Test
	public void idleTillTheirOwnExperienceGoesUpAndTheFirstLookIsOnlyAStart()
	{
		Activity a = new Activity();
		assertEquals("idle", label(a.now(0)));
		// (logging in: each skill's experience as it is, not a drop)
		a.xp(1000, "Mining", 5_000_000);
		a.xp(1000, "Fishing", 300_000);
		assertEquals("idle", label(a.now(1000)));
		a.xp(5000, "Mining", 5_000_035);
		assertEquals("skilling Mining", label(a.now(5000)));
		// (the newest drop's skill; about a minute after the last, idle)
		a.xp(20_000, "Fishing", 300_050);
		assertEquals("skilling Fishing", label(a.now(20_000)));
		assertEquals("skilling Fishing", label(a.now(79_000)));
		assertEquals("idle", label(a.now(80_000)));
		// (the total, a name that isn't a skill, or no drop: nothing)
		a.xp(90_000, "Overall", 99_000_000);
		a.xp(90_000, "Overall", 99_000_100);
		a.xp(90_000, "Fishing", 300_050);
		assertEquals("idle", label(a.now(90_000)));
	}

	@Test
	public void aFightIsFightingOrItsBossAndItsExperienceIsntSkilling()
	{
		Activity a = new Activity();
		a.xp(0, "Strength", 1000);
		a.xp(0, "Hitpoints", 1000);
		a.hit(1000, "Goblin");
		a.xp(1000, "Strength", 1040);
		a.xp(1000, "Hitpoints", 1013);
		assertEquals("fighting", label(a.now(1000)));
		// (fifteen seconds after the last hit, the fight's over: its experience wasn't skilling)
		assertEquals("fighting", label(a.now(15_999)));
		assertEquals("idle", label(a.now(16_000)));
		a.hit(20_000, "General Graardor");
		assertEquals("boss General Graardor", label(a.now(20_000)));
		// (a hit on them whose NPC isn't known, in the boss fight: still the boss)
		a.hit(25_000, null);
		assertEquals("boss General Graardor", label(a.now(25_000)));
		// (several NPCs, one boss: the Barrows brothers, the Royal Titans)
		a.hit(60_000, "Dharok the Wretched");
		assertEquals("boss Barrows", label(a.now(60_000)));
		a.hit(61_000, "Eldric the Ice King");
		assertEquals("boss Royal Titans", label(a.now(61_000)));
		// (a boss fight holds a minute after its last hit, 11/10/2026: the Giant Mole digs away and is chased)
		a.xp(62_000, "Magic", 500);
		a.xp(80_000, "Magic", 565);
		assertEquals("boss Royal Titans", label(a.now(80_000)));
		assertEquals("boss Royal Titans", label(a.now(120_999)));
		// (alching after the fight: Magic is skilling once it's over)
		a.xp(121_000, "Magic", 630);
		assertEquals("skilling Magic", label(a.now(121_000)));
	}

	@Test
	public void theMolesDiggingDoesntEndItsFight()
	{
		// (11/10/2026, at the Giant Mole: hits, then 40 s of it dug in and chased, then hits again)
		Activity a = new Activity();
		a.hit(0, "Giant Mole");
		a.hit(5_000, "Giant Mole");
		for (long t = 5_000; t <= 45_000; t += 5_000)
		{
			assertEquals("boss Giant Mole", label(a.now(t)));
		}
		a.hit(45_000, "Giant Mole");
		assertEquals("boss Giant Mole", label(a.now(104_999)));
		assertEquals("idle", label(a.now(105_000)));
		// (any other fight still ends fifteen seconds after its last hit)
		a.hit(200_000, "Goblin");
		assertEquals("fighting", label(a.now(214_999)));
		assertEquals("idle", label(a.now(215_000)));
	}

	@Test
	public void theBossesAreOnlyTheirLabelsTheWildernessesToo()
	{
		assertTrue(Activity.bossLabels().contains("Grotesque Guardians"));
		assertTrue(Activity.bossLabels().contains("Moons of Peril"));
		// (11/10/2026: the Wilderness's bosses are on the list; OutsidePlugin.activitySafe says when they go)
		for (String wild : new String[]{"Callisto", "Artio", "Vet'ion", "Calvar'ion", "Venenatis", "Spindel", "Scorpia",
			"Chaos Elemental", "Chaos Fanatic", "Crazy archaeologist"})
		{
			assertEquals(wild, Activity.BOSSES.get(wild));
			assertTrue(wild, Activity.WILDERNESS_BOSSES.contains(wild));
		}
		assertEquals(10, Activity.WILDERNESS_BOSSES.size());
		assertTrue(Activity.bossLabels().containsAll(Activity.WILDERNESS_BOSSES));
		assertEquals(24, Activity.SKILLS.size());
	}

	@Test
	public void theNewBossesAndTheOnesOfSeveralNames()
	{
		// (11/10/2026, from RuneLite's HiscoreSkill and NpcID: every boss the hiscores count but the raids and the three
		// minigames)
		Activity a = new Activity();
		String[][] fights = {{"Maggot King", "Maggot King"}, {"Mad Angel", "Mad Angel"}, {"Brutus", "Brutus"},
			{"Demonic Brutus", "Brutus"}, {"Doom of Mokhaiotl (Burrowed)", "Doom of Mokhaiotl"},
			{"Shellbane gryphon", "Shellbane gryphon"}, {"Deranged archaeologist", "Deranged archaeologist"},
			{"The Mimic", "The Mimic"}, {"Hueycoatl tail", "The Hueycoatl"}, {"Enraged Blood Moon", null},
			{"Crystalline Hunllef", "the Gauntlet"}, {"Corrupted Hunllef", "the Corrupted Gauntlet"},
			{"Callisto", "Callisto"}, {"Vet'ion", "Vet'ion"}};
		long t = 0;
		for (String[] f : fights)
		{
			t += 200_000;
			a.hit(t, f[0]);
			assertEquals(f[0], f[1] == null ? "fighting" : "boss " + f[1], label(a.now(t)));
		}
		assertEquals(62, Activity.bossLabels().size());
		for (String gone : new String[]{"Zalcano", "Tempoross", "Wintertodt"})
		{
			assertNull(gone, Activity.BOSSES.get(gone));
			assertTrue(gone, Activity.minigameLabels().contains(gone));
		}
	}

	@Test
	public void theGauntletIsItsBossTheWholeTimeInside()
	{
		Activity a = new Activity();
		a.region(7512, 0);                                               // (the Gauntlet's instance: preparing)
		assertEquals("boss the Gauntlet", label(a.now(0)));
		a.xp(0, "Mining", 100);
		a.xp(1000, "Mining", 150);                                       // (gathering in it)
		assertEquals("boss the Gauntlet", label(a.now(1000)));
		a.hit(2000, "Crystalline rat");                                  // (its creatures)
		assertEquals("boss the Gauntlet", label(a.now(2000)));
		a.hit(300_000, "Crystalline Hunllef");                           // (and the Hunllef)
		assertEquals("boss the Gauntlet", label(a.now(300_000)));
		a.forget();
		a.region(7768, 0);                                               // (the Corrupted Gauntlet's)
		assertEquals("boss the Corrupted Gauntlet", label(a.now(400_000)));
		a.region(12127, 0);                                              // (the lobby in Prifddinas, the two's: neither)
		assertEquals("idle", label(a.now(400_000)));
		assertNull(Activity.MINIGAMES.get(7512));
		assertNull(Activity.MINIGAMES.get(7768));
		assertNull(Activity.MINIGAMES.get(12127));
		assertTrue(Activity.bossLabels().containsAll(Activity.BOSS_REGIONS.values()));
		assertTrue(Activity.BOSSES.values().containsAll(Activity.BOSS_REGIONS.values()));
	}

	@Test
	public void twoMinutesBehindAtMostOnceAMinuteAndClearedSomewhereDangerous()
	{
		Activity a = new Activity();
		Behind b = new Behind("activity");
		long t = 0;
		int sends = 0;
		JsonObject last = null;
		a.xp(0, "Mining", 100);
		// (two minutes mining, then a fight: what goes is still the mining, two minutes later)
		for (; t < 120_000; t += FollowTest.TICK)
		{
			if (t % 3000 == 0)
			{
				a.xp(t, "Mining", (int) (200 + t));
			}
			JsonObject o = b.tick(t, a.now(t), true);
			assertNull(o);
		}
		for (; t < 150_000; t += FollowTest.TICK)
		{
			a.hit(t, "Goblin");
			JsonObject o = b.tick(t, a.now(t), true);
			if (o != null)
			{
				last = o;
				sends++;
			}
		}
		assertEquals(1, sends);
		assertEquals("skilling Mining", label(last.getAsJsonObject("activity")));
		// (into the Wilderness: cleared at once, and nothing while there)
		JsonObject o = b.tick(t, a.now(t), false);
		assertNotNull(o);
		assertTrue(o.get("activity").isJsonNull());
		for (t += FollowTest.TICK; t < 400_000; t += FollowTest.TICK)
		{
			assertNull(b.tick(t, a.now(t), false));
		}
		// (out again: two whole minutes clear of it first)
		for (long end = t + 119_000; t < end; t += FollowTest.TICK)
		{
			assertNull(b.tick(t, a.now(t), true));
		}
		for (long end = t + 2_000; t < end; t += FollowTest.TICK)
		{
			JsonObject got = b.tick(t, a.now(t), true);
			last = got == null ? last : got;
		}
		assertEquals("idle", label(last.getAsJsonObject("activity")));
	}

	@Test
	public void aRaidOrMinigameByWhereTheyAreAndABossFightBeforeEither()
	{
		Activity a = new Activity();
		a.region(13137, 0);
		assertEquals("raid Chambers of Xeric", label2(a.now(0)));
		a.hit(1000, "Great Olm");                                     // (not on the boss list: still the raid)
		assertEquals("raid Chambers of Xeric", label2(a.now(1000)));
		a.region(14672, 0);                                              // (Osmumten's Burial Chamber: the Tombs')
		assertEquals("raid Tombs of Amascut", label2(a.now(1000)));
		a.region(9551, 0);                                               // (the Fight Caves, and Jad)
		assertEquals("minigame the Fight Caves", label2(a.now(1000)));
		a.hit(2000, "TzTok-Jad");
		assertEquals("boss TzTok-Jad", label2(a.now(2000)));
		// (the boss holds a minute after its last hit, 11/10/2026; then the minigame it's in)
		assertEquals("boss TzTok-Jad", label2(a.now(17_000)));
		assertEquals("minigame the Fight Caves", label2(a.now(62_000)));
		a.region(12126, 0);                                              // (Zalcano: a minigame, 11/10/2026, its boss too)
		a.hit(63_000, "Zalcano");
		assertEquals("minigame Zalcano", label2(a.now(63_000)));
		a.region(6462, 0);                                               // (Wintertodt, with its Firemaking)
		a.xp(78_000, "Firemaking", 100);
		a.xp(79_000, "Firemaking", 200);
		assertEquals("minigame Wintertodt", label2(a.now(79_000)));
		a.region(12850, 0);                                              // (Lumbridge: no minigame)
		assertEquals("skilling Firemaking", label2(a.now(79_000)));
		// (the PvP minigames are minigames like the rest, 11/10/2026)
		String[][] pvp = {{"13658", "Last Man Standing"}, {"14432", "Last Man Standing"}, {"9520", "Castle Wars"},
			{"9620", "Castle Wars"}, {"8493", "Soul Wars"}, {"9005", "Soul Wars"}, {"9552", "the Fight Pits"},
			{"13362", "Emir's Arena"}, {"12621", "Clan Wars"}, {"14156", "Clan Wars"}};
		for (String[] p : pvp)
		{
			int r = Integer.parseInt(p[0]);
			assertTrue(Activity.PVP_MINIGAME_REGIONS.contains(r));
			a.region(r, 0);
			assertEquals("minigame " + p[1], label2(a.now(79_000)));
		}
		// (Nightmare Zone above plane 0; the King Black Dragon's lair, the same region, on it)
		a.region(9033, 1);
		assertEquals("minigame Nightmare Zone", label2(a.now(160_000)));
		a.region(9033, 0);
		assertEquals("idle", label2(a.now(160_000)));
		assertEquals(4, Activity.raidLabels().size());
		assertEquals(30, Activity.minigameLabels().size());
		assertTrue(Activity.minigameLabels().contains("Barracuda Trials"));
		assertEquals(6, Activity.PVP_MINIGAMES.size());
		assertTrue(Activity.minigameLabels().containsAll(Activity.PVP_MINIGAMES));
	}

	/** The plugin's rules for a tile, from what it reads there (a risky world, the game's in-the-Wilderness and
	 *  PvP-area flags): {safe for the globe, the activity safe with "In the Wilderness too" off, and on, the stream
	 *  held}. A risky world here is a PvP one too. */
	static boolean[] rules(boolean risky, int x, int y, boolean insideFlag, boolean pvpFlag)
	{
		int region = ((x >> 6) << 8) | (y >> 6);
		boolean wild = OutsidePlugin.wilderness(x, y, insideFlag), pvp = OutsidePlugin.pvpArea(x, y, region, pvpFlag);
		return new boolean[]{OutsidePlugin.globeSafe(risky, wild, pvp), OutsidePlugin.activitySafe(risky, wild, pvp, false),
			OutsidePlugin.activitySafe(risky, wild, pvp, true), OutsidePlugin.held(risky, wild, pvp)};
	}

	static String rules(boolean[] r)
	{
		return (r[0] ? "globe " : "") + (r[1] ? "activity " : "") + (r[2] ? "activity-too " : "") + (r[3] ? "held" : "going");
	}

	@Test
	public void thePvpMinigamesAreSafeForTheGlobeAndTheStreamKeepsGoing()
	{
		// (11/10/2026: the game's PvP-area flag, up inside a PvP minigame, holds back neither the globe nor the stream)
		for (int r : Activity.PVP_MINIGAME_REGIONS)
		{
			int x = (r >> 8) << 6, y = (r & 255) << 6;
			for (boolean flag : new boolean[]{false, true})
			{
				assertEquals(r + "", "globe activity activity-too going", rules(rules(false, x + 32, y + 32, false, flag)));
			}
			// (none of them in the Wilderness: each corner of the region outside its bounds)
			for (int[] c : new int[][]{{x, y}, {x + 63, y}, {x, y + 63}, {x + 63, y + 63}})
			{
				assertFalse(r + "", OutsidePlugin.inWilderness(c[0], c[1]));
			}
			// (a PvP or other risky world still holds everything back, in a PvP minigame too)
			assertEquals(r + "", "held", rules(rules(true, x + 32, y + 32, false, true)));
		}
		assertEquals(46, Activity.PVP_MINIGAME_REGIONS.size());
		// (elsewhere the flag still holds both back: Lumbridge with the flag up, say)
		assertEquals("held", rules(rules(false, 3222, 3218, false, true)));
		assertEquals("globe activity activity-too going", rules(rules(false, 3222, 3218, false, false)));
	}

	@Test
	public void feroxEnclaveIsAHavenAndTheWildernessRoundItIsnt()
	{
		// (11/10/2026, Jace: "Ferox Enclave should be included too because it is a safe area/haven in the wilderness":
		// its town, as Shortest Path draws it, whatever the game's flags say there)
		int[][] town = {{3130, 3630}, {3123, 3622}, {3140, 3639}, {3155, 3645}, {3147, 3619}, {3150, 3640}};
		for (int[] t : town)
		{
			assertTrue(t[0] + "," + t[1], OutsidePlugin.inWilderness(t[0], t[1]));       // (inside the Wilderness's bounds)
			assertTrue(t[0] + "," + t[1], OutsidePlugin.inFerox(t[0], t[1]));
			for (boolean inside : new boolean[]{false, true})
			{
				for (boolean pvp : new boolean[]{false, true})
				{
					assertEquals(t[0] + "," + t[1], "globe activity activity-too going",
						rules(rules(false, t[0], t[1], inside, pvp)));
				}
			}
			assertEquals("held", rules(rules(true, t[0], t[1], false, false)));       // (a PvP world: still dangerous)
		}
		// (just outside its barriers, the Wilderness proper: the stream holds, only the activity with the setting)
		for (int[] t : new int[][]{{3122, 3630}, {3130, 3616}, {3130, 3640}, {3156, 3640}, {3141, 3646}, {3148, 3620}})
		{
			assertFalse(t[0] + "," + t[1], OutsidePlugin.inFerox(t[0], t[1]));
			assertEquals(t[0] + "," + t[1], "activity-too held", rules(rules(false, t[0], t[1], true, true)));
		}
		// (the dungeon under it isn't the town)
		assertFalse(OutsidePlugin.inFerox(3158, 10027));
		// (following there sends its own square; walking out into the Wilderness clears it at once)
		Follow f = new Follow();
		JsonObject sent = null;
		long t = 0;
		for (; t < 200_000; t += FollowTest.TICK)
		{
			JsonObject o = f.tick(t, 3133, 3631, rules(false, 3133, 3631, true, true)[0]);
			sent = o == null ? sent : o;
		}
		assertEquals(3136, sent.getAsJsonObject("where").get("x").getAsInt());
		assertEquals(3648, sent.getAsJsonObject("where").get("y").getAsInt());   // (the whole town in this one square)
		JsonObject out = f.tick(t, 3133, 3660, rules(false, 3133, 3660, true, true)[0]);
		assertNotNull(out);
		assertTrue(out.get("where").isJsonNull());
	}

	@Test
	public void aWildernessBossGoesOnlyWithInTheWildernessTooAndNeverWhereTheyAre()
	{
		// (11/10/2026, Jace: the activity from the Wilderness only with "In the Wilderness too" on; following and the
		// stars never there, whatever the setting)
		for (boolean too : new boolean[]{false, true})
		{
			Activity a = new Activity();
			Behind doing = new Behind("activity");
			Follow follow = new Follow();
			Stars stars = new Stars();
			JsonObject said = null;
			int places = 0;
			for (long t = 0; t < 400_000; t += FollowTest.TICK)
			{
				// (the Wilderness: its bounds, the game's PvP-area flag up; on an ordinary world)
				boolean[] r = rules(false, 3290, 3850, true, true);
				boolean globe = r[0], act = too ? r[2] : r[1];
				assertTrue(r[3]);                                            // (the stream holds there, the setting off)
				a.hit(t, "Callisto");
				JsonObject o = doing.tick(t, a.now(t), act);
				said = o == null ? said : o;
				if (follow.tick(t, 3290, 3850, 301, globe) != null)
				{
					places++;
				}
				stars.see(t, 301, 3290, 3850, 6, globe && !OutsidePlugin.inWilderness(3290, 3850));
				assertFalse(globe);
				assertNull(stars.due(t, true));
			}
			assertEquals(0, places);
			if (too)
			{
				assertEquals("boss Callisto", label(said.getAsJsonObject("activity")));
			}
			else
			{
				assertNull(said);
			}
		}
		// (never on a PvP, Deadman or other risky world, setting or not; never a PvP area outside the Wilderness)
		assertEquals("held", rules(rules(true, 3290, 3850, true, true)));
		assertEquals("held", rules(rules(false, 3222, 3218, false, true)));
		assertEquals("activity-too held", rules(rules(false, 3290, 3850, false, false)));   // (its bounds alone)
		assertEquals("activity-too held", rules(rules(false, 1760, 11540, true, true)));    // (the game's flag alone)
	}

	static String label2(JsonObject a)
	{
		String kind = a.get("kind").getAsString();
		if (kind.equals("raid") || kind.equals("minigame"))
		{
			assertEquals(2, a.size());
			return kind + " " + a.get(kind).getAsString();
		}
		return label(a);
	}

	@Test
	public void aBarracudaTrialIsAMinigameWhileItsRun()
	{
		// (11/10/2026: Sailing's obstacle courses, told by the game's own flag wherever the course is; a boss fight still
		// comes first, and once the trial's over it's skilling Sailing again)
		Activity a = new Activity();
		a.xp(0, "Sailing", 1000);
		a.xp(1_000, "Sailing", 1200);
		assertEquals("skilling Sailing", label2(a.now(2_000)));
		a.trial(true);
		assertEquals("minigame Barracuda Trials", label2(a.now(3_000)));
		a.region(12850, 0);                                                     // (anywhere at sea, or not)
		assertEquals("minigame Barracuda Trials", label2(a.now(4_000)));
		a.trial(false);
		assertEquals("skilling Sailing", label2(a.now(5_000)));
		a.trial(true);
		a.forget();
		assertEquals("idle", label2(a.now(6_000)));
	}
}
