package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the plugin knows of the game, field by field (the relay's names: docs/protocol.md, "The game"), and what's
 * changed since it was last sent: only those fields go, each whole. Kept apart from RuneLite so it can be tested.
 */
public class Kit
{
	/** the fields as last sent */
	private final Map<String, JsonElement> sent = new LinkedHashMap<>();
	/** the item ids whose name and price have gone already */
	private final Set<Integer> named = new TreeSet<>();

	/**
	 * The writer for the relay (the game socket's, the snapshot's; 11/16/2026, Jace: his goblin task never reached his page): RuneLite's Gson, which
	 * plugins are given, leaves out an object's null members as it writes, so a field's null that clears it (a part
	 * switched off, the slayer task's "area": null) had been going as nothing, or as a field the relay refuses. The same
	 * Gson, with nulls kept; RuneLite's own otherwise untouched.
	 */
	static Gson wire(Gson runelite)
	{
		return runelite.newBuilder().serializeNulls().create();
	}

	/** Everything goes again (a new connection, or logging in). */
	public void forget()
	{
		sent.clear();
		named.clear();
	}

	/** The fields of `now` that differ from what was last sent (and, once taken, count as sent). */
	public JsonObject changes(Map<String, JsonElement> now)
	{
		JsonObject out = new JsonObject();
		for (Map.Entry<String, JsonElement> e : now.entrySet())
		{
			JsonElement last = sent.get(e.getKey());
			if (last == null || !last.equals(e.getValue()))
			{
				out.add(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	/** What `changes` gave is now sent. */
	public void took(JsonObject changes)
	{
		for (Map.Entry<String, JsonElement> e : changes.entrySet())
		{
			if (!e.getKey().equals("items"))
			{
				sent.put(e.getKey(), e.getValue());
			}
		}
	}

	/** Ids not named yet, from the item fields (worn, inv, bag, pouch, seeds, barrel, quiver), marked as named. */
	public Set<Integer> unnamed(Map<String, JsonElement> now)
	{
		Set<Integer> ids = ids(now);
		ids.removeAll(named);
		named.addAll(ids);
		return ids;
	}

	/** Every item id in the item fields (worn, inv, bag, pouch, seeds, barrel, quiver): a snapshot names them all
	 *  (11/16/2026). */
	public static Set<Integer> ids(Map<String, JsonElement> now)
	{
		Set<Integer> ids = new TreeSet<>();
		for (String f : new String[]{"worn", "inv", "bag", "pouch", "seeds", "barrel"})
		{
			JsonElement a = now.get(f);
			if (a != null && a.isJsonArray())
			{
				for (JsonElement slot : a.getAsJsonArray())
				{
					if (slot != null && slot.isJsonArray() && slot.getAsJsonArray().size() > 0)
					{
						ids.add(slot.getAsJsonArray().get(0).getAsInt());
					}
				}
			}
		}
		JsonElement q = now.get("quiver");
		if (q != null && q.isJsonArray() && q.getAsJsonArray().size() > 0)
		{
			ids.add(q.getAsJsonArray().get(0).getAsInt());
		}
		return ids;
	}

	/** A container's slots as [id, quantity] or null for an empty one, `size` of them (or as many as there are). */
	public static JsonArray slots(int[] ids, int[] quantities, int size)
	{
		JsonArray a = new JsonArray();
		int n = size > 0 ? size : ids.length;
		for (int i = 0; i < n; i++)
		{
			int id = i < ids.length ? ids[i] : -1;
			int q = i < quantities.length ? quantities[i] : 0;
			if (id <= 0 || q <= 0)
			{
				a.add(JsonNull.INSTANCE);
			}
			else
			{
				a.add(pair(id, q));
			}
		}
		return a;
	}

	/** A container's items only (no empty slots): the looting bag, the rune pouch. */
	public static JsonArray items(int[] ids, int[] quantities)
	{
		JsonArray a = new JsonArray();
		for (int i = 0; i < ids.length && i < quantities.length; i++)
		{
			if (ids[i] > 0 && quantities[i] > 0)
			{
				a.add(pair(ids[i], quantities[i]));
			}
		}
		return a;
	}

	/**
	 * Held back somewhere dangerous: "paused", and what was worn and carried emptied with it, so the relay keeps
	 * nothing of what they took in (it keeps the last of each field otherwise), nor the vitals and prayers.
	 */
	/** Everything taken off the page (11/16/2026, the permissions review): sent as "Send to my Outside page" goes off, so
	 *  the page doesn't go on showing what was last sent. */
	public static JsonObject cleared()
	{
		JsonObject o = held("x");
		o.remove("paused");
		o.addProperty("off", true);
		o.add("skills", new JsonObject());
		o.add("charges", new JsonObject());
		o.add("quests", JsonNull.INSTANCE);
		o.add("diaries", JsonNull.INSTANCE);
		o.add("combat", JsonNull.INSTANCE);
		o.add("slayer", JsonNull.INSTANCE);
		o.add("delve", JsonNull.INSTANCE);
		o.add("delve_lost", JsonNull.INSTANCE);
		o.add("presence", JsonNull.INSTANCE);   // (what they're doing as Discord shows it, 11/16/2026)
		return o;
	}

	public static JsonObject held(String why)
	{
		JsonObject o = new JsonObject();
		o.addProperty("paused", why);
		o.add("worn", new JsonArray());
		o.add("inv", new JsonArray());
		o.add("bag", new JsonArray());
		o.add("pouch", new JsonArray());
		o.add("seeds", new JsonArray());
		o.add("barrel", new JsonArray());
		o.add("quiver", JsonNull.INSTANCE);
		o.add("vitals", new JsonObject());
		o.add("prayers", new JsonArray());
		o.add("anim", JsonNull.INSTANCE);   // (the live view's animation, 11/15/2026)
		return o;
	}

	public static JsonArray pair(long a, long b)
	{
		JsonArray p = new JsonArray();
		p.add(a);
		p.add(b);
		return p;
	}
}
