package wiki.outside;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Deque;

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
 */
class Follow
{
	static final int SQUARE = 128;
	static final long BEHIND_MS = 120_000, EVERY_MS = 60_000, AGAIN_MS = 180_000;
	/** the main map's bounds (the surface; an underground area is 6400 tiles north of what's above it) */
	static final int X0 = 1024, X1 = 4096, Y0 = 2496, Y1 = 4224, UNDER = 6400;
	/** Zanaris (Jace, 11/08/2026: shown on the globe's moon), as one place whatever its tile: the fairy rings and the
	 *  shed both land in this box, and all of it goes as the one square ZANARIS, never more exactly */
	static final int ZX0 = 2368, ZX1 = 2496, ZY0 = 4352, ZY1 = 4480;
	static final int[] ZANARIS = {2368, 4416};

	private final Deque<long[]> seen = new ArrayDeque<>(); // [when, x, y], x < 0 for somewhere it mustn't say
	private long sentAt = -1;
	private int[] sent = null;
	private boolean cleared = true;

	/**
	 * A tick: where the player is (x, y on the map; safe false somewhere dangerous or in an instance). Gives what to send
	 * ({"where": {"x", "y"}} or {"where": null}), or null for nothing now.
	 */
	JsonObject tick(long now, int x, int y, boolean safe)
	{
		int[] sq = safe ? square(x, y) : null;
		seen.addLast(new long[]{now, sq == null ? -1 : sq[0], sq == null ? -1 : sq[1]});
		while (seen.size() > 1 && now - seen.peekFirst()[0] > BEHIND_MS + 2 * EVERY_MS)
		{
			seen.removeFirst();
		}
		// (somewhere it mustn't say: what was sent goes at once)
		if (sq == null && !safe)
		{
			return clear();
		}
		if (sentAt >= 0 && now - sentAt < EVERY_MS)
		{
			return null;
		}
		// where they were two minutes ago (the newest look at least that old)
		long[] then = null;
		for (long[] s : seen)
		{
			if (now - s[0] >= BEHIND_MS)
			{
				then = s;
			}
		}
		if (then == null || then[1] < 0)
		{
			return null;
		}
		// (anywhere dangerous since then: wait till they've been clear of it the whole two minutes)
		for (long[] s : seen)
		{
			if (s[0] >= then[0] && s[1] < 0)
			{
				return null;
			}
		}
		int[] at = {(int) then[1], (int) then[2]};
		if (sent != null && sent[0] == at[0] && sent[1] == at[1] && now - sentAt < AGAIN_MS)
		{
			return null;
		}
		sent = at;
		sentAt = now;
		cleared = false;
		JsonObject w = new JsonObject(), out = new JsonObject();
		w.addProperty("x", at[0]);
		w.addProperty("y", at[1]);
		out.add("where", w);
		return out;
	}

	/** Following turned off, or the connection's new: what was sent goes (once). */
	JsonObject clear()
	{
		if (cleared)
		{
			return null;
		}
		cleared = true;
		sent = null;
		sentAt = -1;
		JsonObject out = new JsonObject();
		out.add("where", JsonNull.INSTANCE);
		return out;
	}

	/** A new connection: the relay may not have it, so the next one due goes even if it's the same square. */
	void again()
	{
		sent = null;
		sentAt = -1;
	}

	/** Forget everything (following turned off): nothing old is sent once it's on again. */
	void forget()
	{
		seen.clear();
		sent = null;
		sentAt = -1;
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
