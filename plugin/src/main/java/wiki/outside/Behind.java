package wiki.outside;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Two minutes behind (11/08/2026): one of the globe's fields (following's "where", the activity's "activity") as it was
 * two minutes ago, at most once a minute, and never from anywhere dangerous. Following's safeguards (Follow), made one
 * field's so the activity keeps the same ones:
 * <ul>
 * <li>what's sent is what it was two minutes ago, at most once a minute (the same again every 3 minutes, so the relay
 * knows it still stands);</li>
 * <li>somewhere dangerous (the Wilderness, a PvP area, a PvP or Deadman world, an instance), nothing's recorded, and the
 * moment they're there what was sent is cleared ({field: null});</li>
 * <li>after anywhere it mustn't say, nothing goes till they've been clear of it the whole two minutes.</li>
 * </ul>
 * Only on the client's thread. tick() each game tick; what it gives back, if anything, is sent.
 */
class Behind
{
	static final long BEHIND_MS = 120_000, EVERY_MS = 60_000, AGAIN_MS = 180_000;

	private final String field;
	/** each tick's [when, what], what null for somewhere it mustn't say */
	private final Deque<Object[]> seen = new ArrayDeque<>();
	private long sentAt = -1;
	private JsonElement sent = null;
	private boolean cleared = true;

	Behind(String field)
	{
		this.field = field;
	}

	/**
	 * A tick: what the field is now (null for nothing to say here, as off the map), and whether it's safe (false
	 * somewhere dangerous or in an instance). Gives what to send ({field: value} or {field: null}), or null for nothing
	 * now.
	 */
	JsonObject tick(long now, JsonElement value, boolean safe)
	{
		JsonElement v = safe ? value : null;
		seen.addLast(new Object[]{now, v});
		while (seen.size() > 1 && now - (long) seen.peekFirst()[0] > BEHIND_MS + 2 * EVERY_MS)
		{
			seen.removeFirst();
		}
		// (somewhere it mustn't say: what was sent goes at once)
		if (!safe)
		{
			return clear();
		}
		if (sentAt >= 0 && now - sentAt < EVERY_MS)
		{
			return null;
		}
		// (what it was two minutes ago: the newest look at least that old)
		Object[] then = null;
		for (Object[] s : seen)
		{
			if (now - (long) s[0] >= BEHIND_MS)
			{
				then = s;
			}
		}
		if (then == null || then[1] == null)
		{
			return null;
		}
		// (anywhere it mustn't say since then: wait till they've been clear of it the whole two minutes)
		for (Object[] s : seen)
		{
			if ((long) s[0] >= (long) then[0] && s[1] == null)
			{
				return null;
			}
		}
		JsonElement at = (JsonElement) then[1];
		if (sent != null && sent.equals(at) && now - sentAt < AGAIN_MS)
		{
			return null;
		}
		sent = at;
		sentAt = now;
		cleared = false;
		JsonObject out = new JsonObject();
		out.add(field, at.deepCopy());
		return out;
	}

	/** Switched off, somewhere dangerous, or logged out: what was sent goes (once). */
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
		out.add(field, JsonNull.INSTANCE);
		return out;
	}

	/** A new connection: the relay may not have it, so the next one due goes even if it's the same. */
	void again()
	{
		sent = null;
		sentAt = -1;
	}

	/** Forget everything (switched off, or logged out): nothing old is sent once it's on again. */
	void forget()
	{
		seen.clear();
		sent = null;
		sentAt = -1;
	}
}
