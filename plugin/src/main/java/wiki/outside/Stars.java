package wiki.outside;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.ObjectID;

/**
 * Shooting stars they find (11/08/2026, Jace's choice for the globe): a crashed star the player sees goes to the relay,
 * which keeps a list of the stars about, with the outstation that found each first (11/09/2026; docs/protocol.md,
 * "Shooting stars"). A star is a world object, never another player. The safeguards:
 * <ul>
 * <li>only its tier, its world, the middle of the 128-tile square it's in (Follow.square), and the name of its landing
 * site when it's one of Shooting Star Info's (Sites, 11/09/2026), never its tile;</li>
 * <li>two minutes late, so it never says the finder's standing there now;</li>
 * <li>at most one report of a star every 3 minutes (the newest of what's two minutes old: its tier, or that it's
 * gone);</li>
 * <li>nothing from anywhere dangerous: the plugin only tells of a star seen while it's safe (not the Wilderness, a PvP
 * area, world or minigame, Deadman), and never of one lying in the Wilderness (Jace, 11/09/2026: never reported) or off the
 * main map (Prifddinas's; Zanaris included: no star lands there).</li>
 * </ul>
 * Off by default (OutsideConfig.stars). The plugin tells it what's in the scene each tick (see) and when a star went
 * while it watched (gone); due() gives the next report to send. Only on the client's thread.
 */
class Stars
{
	static final long BEHIND_MS = 120_000, EVERY_MS = 180_000;
	/** a report not sent this long after it was seen (the relay out of reach meanwhile) is dropped */
	static final long STALE_MS = 600_000;
	/** a star not seen for this long is forgotten (a star lasts about two hours) */
	static final long FORGET_MS = 2 * 3_600_000;

	/**
	 * The crashed star's object for each tier, smallest first (RuneLite's gameval ObjectID, STAR_SIZE_ONE_STAR to
	 * STAR_SIZE_NINE_STAR; the same ids, 41229 to 41020, as Shooting Star Info's Star.TIER_IDS).
	 */
	static final int[] TIER_IDS = {ObjectID.STAR_SIZE_ONE_STAR, ObjectID.STAR_SIZE_TWO_STAR,
		ObjectID.STAR_SIZE_THREE_STAR, ObjectID.STAR_SIZE_FOUR_STAR, ObjectID.STAR_SIZE_FIVE_STAR,
		ObjectID.STAR_SIZE_SIX_STAR, ObjectID.STAR_SIZE_SEVEN_STAR, ObjectID.STAR_SIZE_EIGHT_STAR,
		ObjectID.STAR_SIZE_NINE_STAR};

	/** A star: where (its world and square), and what's been seen of it, [when, tier], 0 for gone. */
	private static class Star
	{
		final int world, x, y;
		String site;
		final Deque<long[]> seen = new ArrayDeque<>();
		long lastLook;
		long sentTier = -1;
		long sentAt = -1;

		Star(int world, int x, int y)
		{
			this.world = world;
			this.x = x;
			this.y = y;
		}
	}

	private final Map<String, Star> stars = new LinkedHashMap<>();

	/** A star's tier from its object's id, 1 to 9; 0 for anything that isn't one. */
	static int tier(int objectId)
	{
		for (int i = 0; i < TIER_IDS.length; i++)
		{
			if (TIER_IDS[i] == objectId)
			{
				return i + 1;
			}
		}
		return 0;
	}

	/** Where a star goes as (its world and square's middle), or null for one it mustn't tell of. */
	private static String key(int world, int[] sq)
	{
		return world + ":" + sq[0] + ":" + sq[1];
	}

	private static int[] place(int x, int y)
	{
		int[] sq = Follow.square(x, y);
		// (Zanaris is following's moon, not somewhere a star lands)
		if (sq == null || (sq[0] == Follow.ZANARIS[0] && sq[1] == Follow.ZANARIS[1]))
		{
			return null;
		}
		return sq;
	}

	/**
	 * A star in the scene this tick (its world, its tile and tier), and whether it's safe to tell of: the player
	 * somewhere safe, the star not in the Wilderness (the plugin works both out). Only a change is kept.
	 */
	void see(long now, int world, int x, int y, int tier, boolean safe)
	{
		int[] sq = place(x, y);
		if (!safe || sq == null || tier < 1 || tier > 9 || world <= 0)
		{
			return;
		}
		Star s = stars.computeIfAbsent(key(world, sq), k -> new Star(world, sq[0], sq[1]));
		s.lastLook = now;
		String site = Sites.name(x, y);
		if (site != null)
		{
			s.site = site;
		}
		long[] last = s.seen.peekLast();
		if (last == null || last[1] != tier)
		{
			s.seen.addLast(new long[]{now, tier});
		}
	}

	/** A star they'd seen went while they watched (mined out: not a hop or a new scene, which the plugin leaves out). */
	void gone(long now, int world, int x, int y)
	{
		int[] sq = place(x, y);
		Star s = sq == null ? null : stars.get(key(world, sq));
		if (s == null)
		{
			return;
		}
		long[] last = s.seen.peekLast();
		if (last != null && last[1] != 0)
		{
			s.seen.addLast(new long[]{now, 0});
		}
	}

	/** The next report due ({"star": {"world", "x", "y", "tier"}} or {..., "gone": true}, with "site" when it's known),
	 *  one at most; or null. */
	JsonObject due(long now)
	{
		JsonObject out = null;
		for (Iterator<Star> it = stars.values().iterator(); it.hasNext(); )
		{
			Star s = it.next();
			// (what it was two minutes ago: the newest look at least that old; older ones are done with)
			long[] then = null;
			while (!s.seen.isEmpty() && now - s.seen.peekFirst()[0] >= BEHIND_MS)
			{
				then = s.seen.removeFirst();
			}
			if (then != null)
			{
				s.seen.addFirst(then);
			}
			boolean done = then != null && then[1] == 0 && (s.sentTier == -1 || s.sentTier == 0) && s.seen.size() == 1;
			if (done || now - s.lastLook > FORGET_MS)
			{
				// (gone, and said so or never told of; or long unseen: forgotten)
				it.remove();
				continue;
			}
			// (a tier from before it went: not worth telling, the gone goes in its time)
			boolean went = !s.seen.isEmpty() && s.seen.peekLast()[1] == 0 && then != null && then[1] != 0;
			if (out != null || went || then == null || then[1] == s.sentTier || now - then[0] > STALE_MS
				|| (s.sentAt >= 0 && now - s.sentAt < EVERY_MS))
			{
				continue;
			}
			s.sentTier = then[1];
			s.sentAt = now;
			JsonObject star = new JsonObject();
			star.addProperty("world", s.world);
			star.addProperty("x", s.x);
			star.addProperty("y", s.y);
			if (s.site != null)
			{
				star.addProperty("site", s.site);
			}
			if (then[1] == 0)
			{
				star.addProperty("gone", true);
			}
			else
			{
				star.addProperty("tier", then[1]);
			}
			out = new JsonObject();
			out.add("star", star);
		}
		return out;
	}

	/** Forget everything (switched off, or logged out). */
	void forget()
	{
		stars.clear();
	}
}
