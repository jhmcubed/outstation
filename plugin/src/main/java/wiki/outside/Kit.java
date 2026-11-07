package wiki.outside;

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

	/** Ids not named yet, from the item fields (worn, inv, bag, pouch, quiver), marked as named. */
	public Set<Integer> unnamed(Map<String, JsonElement> now)
	{
		Set<Integer> ids = new TreeSet<>();
		for (String f : new String[]{"worn", "inv", "bag", "pouch"})
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
		ids.removeAll(named);
		named.addAll(ids);
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

	public static JsonArray pair(long a, long b)
	{
		JsonArray p = new JsonArray();
		p.add(a);
		p.add(b);
		return p;
	}
}
