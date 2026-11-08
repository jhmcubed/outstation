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
		// (off the main map: an instance's copy, Prifddinas, Zanaris, nothing)
		assertNull(Follow.square(7000, 3200));
		assertNull(Follow.square(3260, 6100));
		assertNull(Follow.square(2412, 4434));
		assertNull(Follow.square(0, 0));
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
		// (Prifddinas: not dangerous, not on the map; the last square stands)
		walk(f, clock, 400_000, 3260, 6100, true, sends);
		assertEquals(1, sends[0]);
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
}
