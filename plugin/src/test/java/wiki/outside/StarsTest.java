package wiki.outside;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Shooting stars (Stars, 11/08/2026): a star's tier, world and square, two minutes late, one report of it every 3
 *  minutes at most, and nothing from anywhere dangerous. */
public class StarsTest
{
	static final long TICK = FollowTest.TICK;

	/** Ticks from clock[0] for ms, the star at (x, y) seen at `tier` (0: not seen), the reports kept. */
	static java.util.List<JsonObject> watch(Stars s, long[] clock, long ms, int world, int x, int y, int tier, boolean safe)
	{
		java.util.List<JsonObject> got = new java.util.ArrayList<>();
		for (long end = clock[0] + ms; clock[0] < end; clock[0] += TICK)
		{
			if (tier > 0)
			{
				s.see(clock[0], world, x, y, tier, safe);
			}
			JsonObject o = s.due(clock[0]);
			if (o != null)
			{
				got.add(o.getAsJsonObject("star"));
			}
		}
		return got;
	}

	@Test
	public void eachTiersObjectFromRuneLitesIds()
	{
		// (RuneLite's gameval STAR_SIZE_ONE_STAR to STAR_SIZE_NINE_STAR; Shooting Star Info's TIER_IDS, the same)
		assertEquals(1, Stars.tier(41229));
		assertEquals(2, Stars.tier(41228));
		assertEquals(7, Stars.tier(41223));
		assertEquals(8, Stars.tier(41021));
		assertEquals(9, Stars.tier(41020));
		assertEquals(0, Stars.tier(41222));
		assertEquals(0, Stars.tier(0));
	}

	@Test
	public void twoMinutesLateItsSquareNeverItsTile()
	{
		Stars s = new Stars();
		long[] clock = {0};
		// (Rimmington mine's landing site)
		assertTrue(watch(s, clock, 119_000, 330, 2974, 3241, 6, true).isEmpty());
		java.util.List<JsonObject> got = watch(s, clock, 5_000, 330, 2974, 3241, 6, true);
		assertEquals(1, got.size());
		JsonObject star = got.get(0);
		assertEquals(4, star.size());
		assertEquals(330, star.get("world").getAsInt());
		assertEquals(2944 + 64, star.get("x").getAsInt());
		assertEquals(3200 + 64, star.get("y").getAsInt());
		assertEquals(6, star.get("tier").getAsInt());
		// (still there, the same tier: not again)
		assertTrue(watch(s, clock, 600_000, 330, 2974, 3241, 6, true).isEmpty());
	}

	@Test
	public void aTierMinedDownGoesAtMostEveryThreeMinutesNewestFirst()
	{
		Stars s = new Stars();
		long[] clock = {0};
		watch(s, clock, 121_000, 330, 2974, 3241, 6, true);
		// (mined to 5 and then 4 within the minute: once three minutes have gone since the last, the 4)
		watch(s, clock, 20_000, 330, 2974, 3241, 5, true);
		java.util.List<JsonObject> got = watch(s, clock, 160_000, 330, 2974, 3241, 4, true);
		assertEquals(1, got.size());
		assertEquals(4, got.get(0).get("tier").getAsInt());
		assertTrue(clock[0] >= 300_000);
	}

	@Test
	public void goneIsSaidOnlyOfAStarToldOf()
	{
		Stars s = new Stars();
		long[] clock = {0};
		watch(s, clock, 121_000, 330, 2974, 3241, 1, true);
		s.gone(clock[0], 330, 2974, 3241);
		java.util.List<JsonObject> got = watch(s, clock, 200_000, 330, 2974, 3241, 0, true);
		assertEquals(1, got.size());
		assertTrue(got.get(0).get("gone").getAsBoolean());
		assertFalse(got.get(0).has("tier"));
		// (a star seen and mined out within the two minutes: never told of at all)
		Stars t = new Stars();
		long[] c = {0};
		watch(t, c, 30_000, 331, 3296, 3298, 1, true);
		t.gone(c[0], 331, 3296, 3298);
		assertTrue(watch(t, c, 400_000, 331, 3296, 3298, 0, true).isEmpty());
	}

	@Test
	public void nothingFromAnywhereDangerousOrOffTheMap()
	{
		Stars s = new Stars();
		long[] clock = {0};
		// (seen from somewhere dangerous, in the Wilderness, off the map, in Zanaris, with no tier: nothing)
		assertTrue(watch(s, clock, 300_000, 330, 2974, 3241, 6, false).isEmpty());
		assertTrue(watch(s, clock, 300_000, 330, 3260, 6100, 6, true).isEmpty());
		assertTrue(watch(s, clock, 300_000, 330, 2412, 4434, 6, true).isEmpty());
		s.see(clock[0], 330, 2974, 3241, 10, true);
		s.see(clock[0], 0, 2974, 3241, 5, true);
		clock[0] += 300_000;
		assertNull(s.due(clock[0]));
	}

	@Test
	public void eachStarItsOwnOneReportATickAndForgotten()
	{
		Stars s = new Stars();
		long[] clock = {0};
		for (long end = 121_000; clock[0] < end; clock[0] += TICK)
		{
			s.see(clock[0], 330, 2974, 3241, 6, true);
			s.see(clock[0], 331, 2974, 3241, 9, true);
		}
		JsonObject a = s.due(clock[0]);
		JsonObject b = s.due(clock[0]);
		assertNotNull(a);
		assertNotNull(b);
		assertNull(s.due(clock[0]));
		assertEquals(330, a.getAsJsonObject("star").get("world").getAsInt());
		assertEquals(331, b.getAsJsonObject("star").get("world").getAsInt());
		// (logged out, or switched off: nothing waiting goes)
		Stars t = new Stars();
		t.see(0, 330, 2974, 3241, 6, true);
		t.forget();
		assertNull(t.due(200_000));
	}
}
