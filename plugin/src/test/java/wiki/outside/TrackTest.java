package wiki.outside;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The exact path for the player's own year (Track, 11/10/2026): the tile, at once, every few seconds while moving,
 *  once on stopping, never somewhere dangerous or off the map. */
public class TrackTest
{
	static int[] here(JsonObject o)
	{
		assertNotNull(o);
		JsonObject h = o.getAsJsonObject("here");
		assertEquals(4, h.size());
		return new int[]{h.get("x").getAsInt(), h.get("y").getAsInt(), h.get("plane").getAsInt(), h.get("world").getAsInt()};
	}

	static boolean cleared(JsonObject o)
	{
		return o != null && o.get("here").isJsonNull();
	}

	@Test
	public void theTileAtOnceThenEveryFewSecondsWhileMovingAndOnceOnStopping()
	{
		Track t = new Track();
		// (Lumbridge: the tile, at once)
		assertArrayEquals(new int[]{3222, 3218, 0, 302}, here(t.tick(0, 3222, 3218, 0, 302, true)));
		// (standing there: nothing more)
		for (long ms = 600; ms < 20_000; ms += 600)
		{
			assertNull(t.tick(ms, 3222, 3218, 0, 302, true));
		}
		// (walking a tile a tick: one every 4 s at most)
		int sends = 0;
		long ms = 20_000;
		long lastAt = -1;
		for (int i = 1; i <= 40; i++, ms += 600)
		{
			JsonObject o = t.tick(ms, 3222 + i, 3218, 0, 302, true);
			if (o != null)
			{
				assertTrue(lastAt < 0 || ms - lastAt >= Track.MOVING_MS);
				lastAt = ms;
				sends++;
			}
		}
		assertTrue(sends >= 5 && sends <= 7);
		// (stopping: the tile they stopped on goes once, 3 s after the last)
		int[] stop = {3222 + 41, 3218, 0, 302};
		JsonObject got = null;
		for (int i = 0; i < 10; i++, ms += 600)
		{
			JsonObject o = t.tick(ms, stop[0], stop[1], 0, 302, true);
			if (o != null)
			{
				assertNull(got);
				got = o;
			}
		}
		assertArrayEquals(stop, here(got));
		// (a staircase: the plane is part of the tile)
		ms += 4_000;
		assertArrayEquals(new int[]{stop[0], stop[1], 1, 302}, here(t.tick(ms, stop[0], stop[1], 1, 302, true)));
	}

	@Test
	public void somewhereDangerousClearsItOnceAndSendsNothing()
	{
		Track t = new Track();
		here(t.tick(0, 3094, 3490, 0, 302, true));                        // (Edgeville)
		assertTrue(cleared(t.tick(600, 3094, 3530, 0, 302, false)));       // (over the ditch: cleared at once)
		for (long ms = 1200; ms < 30_000; ms += 600)
		{
			assertNull(t.tick(ms, 3094 + (int) (ms / 600) % 5, 3530, 0, 302, false));
		}
		// (back out: the tile again)
		assertArrayEquals(new int[]{3094, 3490, 0, 302}, here(t.tick(30_000, 3094, 3490, 0, 302, true)));
		// (logged out or switched off: cleared once, then nothing)
		assertTrue(cleared(t.clear()));
		assertNull(t.clear());
	}

	@Test
	public void placedAsFollowingPlacesIt()
	{
		Track t = new Track();
		// (underground: the tile above it; at sea: the sea's tile; Zanaris as its one place; an instance off the map: its
		// entrance; a player-owned house, off the map: cleared)
		assertArrayEquals(new int[]{3222, 3218, 0, 302}, here(t.tick(0, 3222, 3218 + 6400, 0, 302, true)));
		assertArrayEquals(new int[]{3180, 2420, 0, 302}, here(t.tick(10_000, 3180, 2420, 0, 302, true)));
		assertArrayEquals(new int[]{2368, 4416, 0, 302}, here(t.tick(20_000, 2412, 4434, 0, 302, true)));
		assertArrayEquals(new int[]{2880, 3136, 0, 302}, here(t.tick(30_000, (9043 >> 8) << 6, (9043 & 255) << 6, 0, 302, true)));
		assertArrayEquals(new int[]{2240, 3328, 0, 302}, here(t.tick(40_000, 3264, 6080, 0, 302, true)));   // (Prifddinas)
		assertTrue(cleared(t.tick(50_000, 1900, 5700, 0, 302, true)));
		assertNull(t.tick(51_000, 1900, 5700, 0, 302, true));
	}

	@Test
	public void aNewConnectionSendsItAgain()
	{
		Track t = new Track();
		here(t.tick(0, 3222, 3218, 0, 302, true));
		assertNull(t.tick(600, 3222, 3218, 0, 302, true));
		t.again();
		assertArrayEquals(new int[]{3222, 3218, 0, 302}, here(t.tick(1200, 3222, 3218, 0, 302, true)));
	}
}
