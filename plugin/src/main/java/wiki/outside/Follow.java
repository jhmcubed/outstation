package wiki.outside;

import com.google.gson.JsonObject;

/**
 * Following (11/08/2026, Jace: "add plugin following with those safeguards"): while it's on, the rough area the player
 * was in goes to the relay, for their outstation's light on outside.wiki's globe to travel to. The safeguards:
 * <ul>
 * <li>only a square of the map 128 tiles a side (about a town and its surroundings), never the tile;</li>
 * <li>two minutes behind: what's sent is where they were two minutes ago, at most once a minute;</li>
 * <li>never somewhere dangerous: in the Wilderness, a PvP area, on a PvP or Deadman world or in an instance (raids,
 * boss rooms), nothing's recorded, and the moment they're there the area already sent is cleared;</li>
 * <li>never anywhere off the main map (an underground area is counted where it lies under; Zanaris as one place, the
 * globe's moon; anything else, nothing).</li>
 * </ul>
 * Off by default (OutsideConfig.follow), and the relay shows it only while their outstation's transmitting and open.
 * The client's thread calls tick() each game tick with where the player is; what it gives back, if anything, is sent.
 * With "and my world" on too (OutsideConfig.followWorld, 11/08/2026), the world they were on goes with the square.
 * The timing and the clearing are Behind's (11/08/2026), shared with the activity.
 */
class Follow
{
	static final int SQUARE = 128;
	static final long BEHIND_MS = Behind.BEHIND_MS, EVERY_MS = Behind.EVERY_MS, AGAIN_MS = Behind.AGAIN_MS;
	/** the main map's bounds (the surface; an underground area is 6400 tiles north of what's above it) */
	static final int X0 = 1024, X1 = 4096, Y0 = 2496, Y1 = 4224, UNDER = 6400;
	/** Zanaris (Jace, 11/08/2026: shown on the globe's moon), as one place whatever its tile: the fairy rings and the
	 *  shed both land in this box, and all of it goes as the one square ZANARIS, never more exactly */
	static final int ZX0 = 2368, ZX1 = 2496, ZY0 = 4352, ZY1 = 4480;
	static final int[] ZANARIS = {2368, 4416};

	/** the two minutes behind, the minute between, the clearing (Behind: the activity keeps the same ones) */
	private final Behind behind = new Behind("where");

	/** A tick, without the world (OutsideConfig.followWorld off). */
	JsonObject tick(long now, int x, int y, boolean safe)
	{
		return tick(now, x, y, 0, safe);
	}

	/**
	 * A tick: where the player is (x, y on the map; safe false somewhere dangerous or in an instance), and the world
	 * they're on (0 to leave it out: "and my world", 11/08/2026, is off). Gives what to send ({"where": {"x", "y"}},
	 * with "world" when it's given, or {"where": null}), or null for nothing now. The world is part of the place, so
	 * the square and the world that go are always from the same moment, two minutes ago.
	 */
	JsonObject tick(long now, int x, int y, int world, boolean safe)
	{
		int[] sq = safe ? square(x, y) : null;
		JsonObject w = null;
		if (sq != null)
		{
			w = new JsonObject();
			w.addProperty("x", sq[0]);
			w.addProperty("y", sq[1]);
			if (world > 0)
			{
				w.addProperty("world", world);
			}
		}
		return behind.tick(now, w, safe);
	}

	/** Following turned off, or the connection's new: what was sent goes (once). */
	JsonObject clear()
	{
		return behind.clear();
	}

	/** A new connection: the relay may not have it, so the next one due goes even if it's the same square. */
	void again()
	{
		behind.again();
	}

	/** Forget everything (following turned off): nothing old is sent once it's on again. */
	void forget()
	{
		behind.forget();
	}

	/** The middle of the square a point's in, on the main map, or Zanaris as one place; null anywhere else. */
	static int[] square(int x, int y)
	{
		if (x >= ZX0 && x < ZX1 && y >= ZY0 && y < ZY1)
		{
			return ZANARIS.clone();
		}
		if (y >= Y0 + UNDER && y < Y1 + UNDER)
		{
			y -= UNDER;
		}
		if (x < X0 || x >= X1 || y < Y0 || y >= Y1)
		{
			return null;
		}
		return new int[]{(x / SQUARE) * SQUARE + SQUARE / 2, (y / SQUARE) * SQUARE + SQUARE / 2};
	}
}
