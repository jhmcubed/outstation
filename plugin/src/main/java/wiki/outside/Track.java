package wiki.outside;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * The exact path, for the player's own year (11/10/2026, Jace: "your year" records the exact tile, privately;
 * docs/record.md, "The exact path"). With "Record my exact path for my year" on (OutsideConfig.recordPath, off by
 * default, behind its own warning), the tile the player's on goes as {"here": {"x", "y", "plane", "world"}}:
 * <ul>
 * <li>at once, not two minutes behind: the relay passes it only to the player's own outstation's desk ({"mine"}), for
 * their record, and never keeps it, shows it, or sends it anywhere else;</li>
 * <li>when it changes, at most one every MOVING_MS while they move, and once they stop (after STOPPED_MS since the last);
 * nothing more while they stand still;</li>
 * <li>as following places it (Follow.tile): on a boat, where the boat is at sea; an instance where its template lies or
 * at its entrance; Prifddinas where the world map draws it; underground as the tile above it;</li>
 * <li>never somewhere dangerous (following's rules: OutsidePlugin.globeSafe), nor off the map: there, and on logging
 * out or switching it off, {"here": null} goes once.</li>
 * </ul>
 */
class Track
{
	static final long MOVING_MS = 4_000, STOPPED_MS = 3_000;

	/** the tile last sent, {x, y, plane, world} (null: none out) */
	private int[] sent;
	/** the tile last tick (to tell moving from stopped) */
	private int[] last;
	private long sentAt = -1;

	/** A tick: where the player is (as here() reads it: x, y, plane), their world, and whether it's safe. What to send,
	 *  or null for nothing now. */
	JsonObject tick(long now, int x, int y, int plane, int world, boolean safe)
	{
		int[] at = safe ? Follow.tile(x, y) : null;
		if (at == null)
		{
			last = null;
			return clear();
		}
		int[] t = {at[0], at[1], plane, world};
		boolean moving = last != null && !Arrays.equals(last, t);
		last = t;
		if (Arrays.equals(sent, t) || (sentAt >= 0 && now - sentAt < (moving ? MOVING_MS : STOPPED_MS)))
		{
			return null;
		}
		sent = t;
		sentAt = now;
		JsonObject w = new JsonObject();
		w.addProperty("x", t[0]);
		w.addProperty("y", t[1]);
		w.addProperty("plane", t[2]);
		w.addProperty("world", t[3]);
		JsonObject o = new JsonObject();
		o.add("here", w);
		return o;
	}

	/** Somewhere dangerous or off the map, logged out, switched off: {"here": null} once, if a tile's out. */
	JsonObject clear()
	{
		if (sent == null)
		{
			return null;
		}
		sent = null;
		JsonObject o = new JsonObject();
		o.add("here", JsonNull.INSTANCE);
		return o;
	}

	/** A new connection: the relay has nothing, so the tile they're on goes again at the next tick. */
	void again()
	{
		sent = null;
		sentAt = -1;
	}

	/** Forget everything (switched off, logged out). */
	void forget()
	{
		sent = null;
		last = null;
		sentAt = -1;
	}
}
