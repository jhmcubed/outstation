package wiki.outside;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Following's safeguards (Follow): two minutes behind, a square never a tile, never anywhere dangerous. */
public class FollowTest
{
	static final long TICK = 600;

	/** A walk: ticks every 600 ms from `from` to `to` (ms) at one place; what each tick gave, the last non-null kept. */
	static JsonObject walk(Follow f, long[] clock, long ms, int x, int y, boolean safe, int[] sends)
	{
		JsonObject last = null;
		for (long end = clock[0] + ms; clock[0] < end; clock[0] += TICK)
		{
			JsonObject o = f.tick(clock[0], x, y, safe);
			if (o != null)
			{
				last = o;
				sends[0]++;
			}
		}
		return last;
	}

	static int[] where(JsonObject o)
	{
		assertNotNull(o);
		assertTrue(o.get("where").isJsonObject());
		JsonObject w = o.getAsJsonObject("where");
		assertEquals(2, w.size());
		return new int[]{w.get("x").getAsInt(), w.get("y").getAsInt()};
	}

	@Test
	public void aSquareNeverATile()
	{
		// (Lumbridge's castle, and the cellar under it: the same square, its middle)
		assertArrayEquals(new int[]{3264, 3264}, Follow.square(3222, 3218));
		assertArrayEquals(new int[]{3264, 3264}, Follow.square(3222, 3218 + 6400));
		assertArrayEquals(new int[]{3264, 3264}, Follow.square(3200, 3200));
		assertArrayEquals(new int[]{3264, 3264}, Follow.square(3327, 3327));
		// (off the main map: an instance's copy, Prifddinas, Puro-Puro, nothing)
		assertNull(Follow.square(7000, 3200));
		assertNull(Follow.square(3260, 6100));
		assertNull(Follow.square(2560, 4300));
		assertNull(Follow.square(0, 0));
	}

	@Test
	public void zanarisIsOnePlace()
	{
		// (its fairy ring, its bank and its far corners: all the one square, the globe's moon)
		assertArrayEquals(new int[]{2368, 4416}, Follow.square(2412, 4434));
		assertArrayEquals(new int[]{2368, 4416}, Follow.square(2383, 4458));
		assertArrayEquals(new int[]{2368, 4416}, Follow.square(2368, 4352));
		assertArrayEquals(new int[]{2368, 4416}, Follow.square(2495, 4479));
		assertNull(Follow.square(2496, 4416));
		assertNull(Follow.square(2412, 4480));
	}

	@Test
	public void twoMinutesBehindAndAtMostOnceAMinute()
	{
		Follow f = new Follow();
		long[] clock = {1_000_000};
		int[] sends = {0};
		// (two minutes in Lumbridge: nothing yet)
		assertNull(walk(f, clock, 119_000, 3222, 3218, true, sends));
		assertEquals(0, sends[0]);
		// (then off to Varrock: what goes is still Lumbridge, two minutes behind)
		JsonObject o = walk(f, clock, 30_000, 3212, 3424, true, sends);
		assertArrayEquals(new int[]{3264, 3264}, where(o));
		assertEquals(1, sends[0]);
		// (not again within the minute, and Varrock only once they've been there two minutes)
		walk(f, clock, 50_000, 3212, 3424, true, sends);
		assertEquals(1, sends[0]);
		o = walk(f, clock, 60_000, 3212, 3424, true, sends);
		assertEquals(2, sends[0]);
		assertArrayEquals(new int[]{3264, 3392}, where(o));
	}

	@Test
	public void theSameSquareGoesAgainOnlyEveryThreeMinutes()
	{
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		walk(f, clock, 121_000, 3222, 3218, true, sends);
		assertEquals(1, sends[0]);
		walk(f, clock, 170_000, 3222, 3218, true, sends);
		assertEquals(1, sends[0]);
		walk(f, clock, 20_000, 3222, 3218, true, sends);
		assertEquals(2, sends[0]);
		// (a new connection: the next one due goes even though it's the same)
		f.again();
		walk(f, clock, TICK, 3222, 3218, true, sends);
		assertEquals(3, sends[0]);
	}

	@Test
	public void somewhereDangerousClearsAtOnceAndWaitsTwoMinutesClearOfIt()
	{
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		walk(f, clock, 121_000, 3094, 3491, true, sends);
		assertEquals(1, sends[0]);
		// (into the Wilderness: cleared on the first tick there, and only once)
		JsonObject o = f.tick(clock[0], 3094, 3530, false);
		clock[0] += TICK;
		assertNotNull(o);
		assertTrue(o.get("where").isJsonNull());
		sends[0] = 0;
		walk(f, clock, 300_000, 3094, 3600, false, sends);
		assertEquals(0, sends[0]);
		// (back out to Edgeville: nothing till they've been clear of it two whole minutes, and never from before)
		walk(f, clock, 119_000, 3094, 3491, true, sends);
		assertEquals(0, sends[0]);
		o = walk(f, clock, 3_000, 3094, 3491, true, sends);
		assertEquals(1, sends[0]);
		assertArrayEquals(new int[]{3136, 3520}, where(o));
	}

	@Test
	public void aShortTripIntoDangerIsNeverSent()
	{
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		walk(f, clock, 121_000, 3094, 3491, true, sends);
		f.tick(clock[0], 3094, 3530, false);
		clock[0] += TICK;
		sends[0] = 0;
		// (an instance's tiles, or a safe-looking square read while somewhere dangerous, never go)
		JsonObject o = walk(f, clock, 10_000, 3222, 3218, false, sends);
		assertNull(o);
		walk(f, clock, 100_000, 3222, 3218, true, sends);
		assertEquals(0, sends[0]);
	}

	@Test
	public void offTheMapSendsNothingAndDoesntClear()
	{
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		walk(f, clock, 121_000, 3222, 3218, true, sends);
		assertEquals(1, sends[0]);
		// (the Abyss: not dangerous, not on the map; the last square stands. Prifddinas was the example till 11/10/2026,
		// when it went where the world map draws it)
		walk(f, clock, 400_000, 3039, 4834, true, sends);
		assertEquals(1, sends[0]);
	}

	@Test
	public void prifddinasIsWhereTheWorldMapDrawsIt()
	{
		// (11/10/2026: the city's tiles, off the map, go as the squares they lie over in Tirannwn, inside its gates)
		assertArrayEquals(Follow.square(2240, 3386), Follow.place(3264, 6135));
		assertArrayEquals(Follow.square(2240, 3269), Follow.place(3264, 6024));
		assertArrayEquals(Follow.square(2184, 3328), Follow.place(3208, 6080));
		assertArrayEquals(Follow.square(2295, 3328), Follow.place(3319, 6080));
		// (the Gauntlet's lobby, the Gauntlet itself and Zalcano's mine: the city's middle)
		int[] middle = Follow.square(2240, 3328);
		assertArrayEquals(middle, Follow.place(3032, 6116));
		assertArrayEquals(middle, Follow.place(1880, 5660));
		assertArrayEquals(middle, Follow.place(1950, 5660));
		assertArrayEquals(middle, Follow.place(3034, 6068));
		// (and it goes like anywhere else, two minutes behind)
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		JsonObject o = walk(f, clock, 121_000, 3264, 6080, true, sends);
		assertEquals(1, sends[0]);
		assertArrayEquals(Follow.square(2240, 3328), where(o));
	}

	@Test
	public void aHouseTripOnlyPausesTheClock()
	{
		// (11/10/2026, Jace: "i go through my player owned house a TON". The house is off the main map but safe: it
		// pauses the two minutes rather than starting them again)
		Follow f = new Follow();
		long[] clock = {0};
		java.util.List<int[]> went = new java.util.ArrayList<>();
		// (Lumbridge for 90 s, the house for 20, Varrock for 70, the house for 20, Falador for 3 minutes: never two
		// whole minutes clear of the house till the end)
		int[][] trip = {{3222, 3218, 90}, {1900, 5700, 20}, {3212, 3424, 70}, {1900, 5700, 20}, {2965, 3380, 180}};
		for (int[] leg : trip)
		{
			for (long end = clock[0] + leg[2] * 1000L; clock[0] < end; clock[0] += TICK)
			{
				JsonObject o = f.tick(clock[0], leg[0], leg[1], true);
				if (o != null)
				{
					went.add(where(o));
				}
			}
		}
		// (each place on the map goes in turn, two minutes behind; with the house starting the clock again, nothing
		// would have gone before Falador)
		assertEquals(3, went.size());
		assertArrayEquals(new int[]{3264, 3264}, went.get(0));
		assertArrayEquals(new int[]{3264, 3392}, went.get(1));
		assertArrayEquals(new int[]{3008, 3392}, went.get(2));
	}

	@Test
	public void theHouseNeverSendsAndNeverClears()
	{
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		walk(f, clock, 121_000, 3222, 3218, true, sends);
		assertEquals(1, sends[0]);
		// (a long stay in the house: nothing new, nothing cleared; the last square on the map is what stands)
		for (long end = clock[0] + 600_000; clock[0] < end; clock[0] += TICK)
		{
			assertNull(f.tick(clock[0], 1900, 5700, true));
		}
	}

	@Test
	public void switchedOffItClearsOnceAndForgets()
	{
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		assertNull(f.clear());
		walk(f, clock, 121_000, 3222, 3218, true, sends);
		JsonObject o = f.clear();
		assertNotNull(o);
		assertTrue(o.get("where").isJsonNull());
		assertNull(f.clear());
		f.forget();
		// (on again: the two minutes start over, nothing old goes)
		walk(f, clock, 119_000, 3212, 3424, true, sends);
		assertEquals(1, sends[0]);
	}

	@Test
	public void theWorldGoesWithTheSquareFromTheSameMoment()
	{
		// ("and my world", 11/08/2026: the world they were on two minutes ago, with the square from then)
		Follow f = new Follow();
		long[] clock = {0};
		JsonObject last = null;
		for (long end = 121_000; clock[0] < end; clock[0] += TICK)
		{
			JsonObject o = f.tick(clock[0], 3222, 3218, clock[0] < 60_000 ? 302 : 330, true);
			last = o == null ? last : o;
		}
		assertNotNull(last);
		JsonObject w = last.getAsJsonObject("where");
		assertEquals(3, w.size());
		assertEquals(3264, w.get("x").getAsInt());
		assertEquals(302, w.get("world").getAsInt());
		// (a hop is a new place: it goes once the minute's up, two minutes after it)
		int[] sends = {0};
		JsonObject o = null;
		for (long end = clock[0] + 60_000; clock[0] < end; clock[0] += TICK)
		{
			JsonObject got = f.tick(clock[0], 3222, 3218, 330, true);
			if (got != null)
			{
				o = got;
				sends[0]++;
			}
		}
		assertEquals(1, sends[0]);
		assertEquals(330, o.getAsJsonObject("where").get("world").getAsInt());
	}

	@Test
	public void theWorldNeverGoesAloneOrFromAnywhereDangerous()
	{
		Follow f = new Follow();
		long[] clock = {0};
		int[] sends = {0};
		// (off the map: nothing, the world included)
		for (long end = 300_000; clock[0] < end; clock[0] += TICK)
		{
			assertNull(f.tick(clock[0], 3039, 4834, 302, true));
		}
		for (long end = clock[0] + 121_000; clock[0] < end; clock[0] += TICK)
		{
			if (f.tick(clock[0], 3222, 3218, 302, true) != null)
			{
				sends[0]++;
			}
		}
		assertEquals(1, sends[0]);
		// (a PvP world: cleared at once, square and world together)
		JsonObject o = f.tick(clock[0], 3222, 3218, 325, false);
		assertNotNull(o);
		assertTrue(o.get("where").isJsonNull());
	}

	/** A point in the middle of a region (RuneLite's, 64 tiles a side), as an instance's template would give it. */
	static int[] in(int region)
	{
		return new int[]{(region >> 8) * 64 + 32, (region & 255) * 64 + 32};
	}

	static int[] place(int region)
	{
		int[] p = in(region);
		assertEquals(region, Follow.region(p[0], p[1]));
		return Follow.place(p[0], p[1]);
	}

	@Test
	public void anInstanceGoesWhereItsOpened()
	{
		// (11/09/2026, Jace: "allow instances": each as its template lies, by RuneLite's region ids)
		assertArrayEquals(new int[]{2240, 4032}, place(9023));                  // (Vorkath: Ungael)
		assertArrayEquals(new int[]{2240, 3008}, place(9007));                  // (Zulrah)
		assertArrayEquals(new int[]{1856, 3136}, place(7216));                  // (the Colosseum, in Varlamore)
		assertArrayEquals(new int[]{3904, 3520}, place(15515));                 // (the Nightmare, under Slepe)
		assertArrayEquals(new int[]{1088, 3392}, place(4405));                  // (Vardorvis)
		assertArrayEquals(new int[]{1216, 3648}, place(5021));                  // (Hespori, under the Farming Guild)
		assertArrayEquals(new int[]{3008, 2880}, place(12076));                 // (Tempoross)
		assertArrayEquals(new int[]{3392, 2752}, place(13454));                 // (the Tombs' way in, under the pyramid)
		// (off the map: their entrances)
		assertArrayEquals(new int[]{2880, 3136}, place(9043));                  // (the Inferno: the Karamja volcano)
		assertArrayEquals(new int[]{2880, 3136}, place(9551));                  // (the Fight Caves)
		assertArrayEquals(new int[]{1216, 3520}, place(13137));                 // (the Chambers of Xeric)
		assertArrayEquals(new int[]{3648, 3264}, place(12613));                 // (the Theatre of Blood)
		assertArrayEquals(new int[]{3392, 2752}, place(15700));                 // (the Tombs of Amascut)
		assertArrayEquals(new int[]{3392, 3520}, place(6727));                  // (the Grotesque Guardians)
		assertArrayEquals(new int[]{2880, 3776}, place(11347));                 // (General Graardor's room)
		assertArrayEquals(Follow.square(2240, 3328), place(7512));              // (the Gauntlet: Prifddinas, 11/10/2026)
		assertArrayEquals(Follow.square(2240, 3328), place(7768));              // (the Corrupted Gauntlet)
		// (off the map with no entrance on it: nothing)
		assertNull(place(8291));                                                // (the Leviathan)
		assertNull(place(10595));                                               // (the Whisperer)
		assertNull(place(12132));                                               // (Duke Sucellus)
		assertNull(place(11330));                                               // (the Phantom Muspah)
		assertNull(place(16214));                                               // (the Fractured Archive)
		// (each entrance is on the main map, and none is in the Wilderness)
		for (int[] at : Follow.ENTRANCES.values())
		{
			assertArrayEquals(at, Follow.square(at[0], at[1]));
			assertTrue(!OutsidePlugin.inWilderness(at[0], at[1]));
		}
	}

	@Test
	public void anInstancesSquareGoesAsAnyOther()
	{
		// (into the Theatre: its entrance's square, two minutes behind, as anywhere)
		Follow f = new Follow();
		long[] clock = {0};
		int[] tob = in(12613);
		int[] sends = {0};
		JsonObject o = walk(f, clock, 121_000, tob[0], tob[1], true, sends);
		assertArrayEquals(new int[]{3648, 3264}, where(o));
	}
}
