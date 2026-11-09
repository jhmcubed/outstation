package wiki.outside;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
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
		// (alching after the fight: Magic is skilling once it's over)
		a.xp(62_000, "Magic", 500);
		a.xp(80_000, "Magic", 565);
		assertEquals("skilling Magic", label(a.now(80_000)));
	}

	@Test
	public void theBossesAreOnlyTheirLabelsAndNoneInTheWilderness()
	{
		assertTrue(Activity.bossLabels().contains("Grotesque Guardians"));
		assertTrue(Activity.bossLabels().contains("Moons of Peril"));
		for (String wild : new String[]{"Callisto", "Artio", "Vet'ion", "Calvar'ion", "Venenatis", "Spindel", "Scorpia",
			"Chaos Elemental", "Chaos Fanatic", "Crazy archaeologist"})
		{
			assertNull(wild, Activity.BOSSES.get(wild));
		}
		assertEquals(24, Activity.SKILLS.size());
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
		assertEquals("minigame the Fight Caves", label2(a.now(17_000)));
		a.region(6462, 0);                                               // (Wintertodt, with its Firemaking)
		a.xp(18_000, "Firemaking", 100);
		a.xp(19_000, "Firemaking", 200);
		assertEquals("minigame Wintertodt", label2(a.now(19_000)));
		a.region(12850, 0);                                              // (Lumbridge: no minigame)
		assertEquals("skilling Firemaking", label2(a.now(19_000)));
		// (the PvP minigames are none of them: nothing goes from them at all)
		for (int r : new int[]{13658, 14432, 9520, 9620, 8493, 9005, 9552, 13362, 12621})
		{
			assertTrue(Activity.PVP_REGIONS.contains(r));
			assertNull(Activity.MINIGAMES.get(r));
			assertNull(Activity.RAIDS.get(r));
		}
		// (Nightmare Zone above plane 0; the King Black Dragon's lair, the same region, on it)
		a.region(9033, 1);
		assertEquals("minigame Nightmare Zone", label2(a.now(100_000)));
		a.region(9033, 0);
		assertEquals("idle", label2(a.now(100_000)));
		assertEquals(4, Activity.raidLabels().size());
		assertEquals(24, Activity.minigameLabels().size());
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
}
