package wiki.outside;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The collection log read by Outside itself (update 7, 11/22/2026), for players who don't use RuneProfile. The game
 * hands a plugin the log's slots only while the log's open, one script call a slot had (TRANSMIT: the item and how
 * many), and only for the page shown, unless its search is opened, which has the game send every slot had: as
 * RuneProfile's plugin (CollectionLogWidgetSubscriber), WikiSync (WikiSyncPlugin, SyncButtonManager) and TempleOSRS
 * (CollectionLogManager, CollectionLogAutoSyncManager) read it. They press the search themselves (Client.menuAction);
 * Outside leaves it to the player. The log's own pages come from the game's cache, as
 * WikiSync and TempleOSRS read them (the tabs' enum, each page's name, its items' enum, and the enum of items replaced).
 * Kept apart from RuneLite so it's tested.
 */
final class Clog
{
	/** the game's script that hands the open log one slot had: (item, how many) */
	static final int TRANSMIT = 4100;
	/** run as the log opens */
	static final int SETUP = 7797;
	/** the log's tabs (each a struct), each tab's pages (TAB_PAGES: an enum of structs), each page's name and items */
	static final int TABS = 2102, TAB_PAGES = 683, PAGE_NAME = 689, PAGE_ITEMS = 690, REPLACED = 3721;
	static final String[] TAB_NAMES = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
	/** the slots read are taken this many ticks after the last one came */
	static final int QUIET = 3;
	/** at most this many slots (the game has about 1,700) */
	static final int MOST = 4000;

	/** What the plugin reads the cache through (the client, or a test's stand-in). */
	interface Cache
	{
		int[] ints(int enumId);

		int structInt(int struct, int param);

		String structString(int struct, int param);

		/** the item that stands in for this one in the log (enum REPLACED), or the item itself */
		int replaced(int item);

		String name(int item);
	}

	private final Map<Integer, Integer> had = new LinkedHashMap<>();
	private int last = -1;

	synchronized void forget()
	{
		had.clear();
		last = -1;
	}

	/** A slot the game handed over, on tick `tick`. */
	synchronized void took(int item, int count, int tick)
	{
		if (item > 0 && (had.size() < MOST || had.containsKey(item)))
		{
			had.put(item, Math.max(1, count));
		}
		last = tick;
	}

	/** Whether slots are coming in now (the last within QUIET ticks). */
	synchronized boolean reading(int tick)
	{
		return last >= 0 && tick - last < QUIET;
	}

	/** The slots read, once QUIET ticks have gone by since the last; null till then, or with none. */
	synchronized Map<Integer, Integer> due(int tick)
	{
		if (last < 0 || tick - last < QUIET)
		{
			return null;
		}
		Map<Integer, Integer> out = new LinkedHashMap<>(had);
		forget();
		return out.isEmpty() ? null : out;
	}

	/** The log's tabs and pages from the cache: {"tabs": [{"name", "pages": [{"name", "items": [ids]}]}], "names": {id: name}}. */
	static JsonObject layout(Cache c)
	{
		JsonArray tabs = new JsonArray();
		Map<Integer, String> names = new LinkedHashMap<>();
		int[] tabStructs = c.ints(TABS);
		for (int t = 0; t < tabStructs.length; t++)
		{
			JsonArray pages = new JsonArray();
			for (int page : c.ints(c.structInt(tabStructs[t], TAB_PAGES)))
			{
				Set<Integer> ids = new LinkedHashSet<>();
				for (int item : c.ints(c.structInt(page, PAGE_ITEMS)))
				{
					ids.add(c.replaced(item));
				}
				JsonArray items = new JsonArray();
				for (int id : ids)
				{
					items.add(id);
					names.computeIfAbsent(id, c::name);
				}
				JsonObject p = new JsonObject();
				p.addProperty("name", c.structString(page, PAGE_NAME));
				p.add("items", items);
				pages.add(p);
			}
			JsonObject tab = new JsonObject();
			tab.addProperty("name", t < TAB_NAMES.length ? TAB_NAMES[t] : "Tab " + (t + 1));
			tab.add("pages", pages);
			tabs.add(tab);
		}
		JsonObject n = new JsonObject();
		names.forEach((id, name) -> n.addProperty(String.valueOf(id), name));
		JsonObject o = new JsonObject();
		o.add("tabs", tabs);
		o.add("names", n);
		return o;
	}

	/** Each name in the log's layout to its item, for the chat's line; a name two items share isn't there (as
	 *  RuneProfile's ignored names: the decorative armour, the graceful pieces). */
	static Map<String, Integer> byName(JsonObject layout)
	{
		Map<String, Integer> out = new HashMap<>();
		Set<String> twice = new LinkedHashSet<>();
		for (Map.Entry<String, JsonElement> e : layout.getAsJsonObject("names").entrySet())
		{
			String n = e.getValue().getAsString();
			if (out.put(n, Integer.parseInt(e.getKey())) != null)
			{
				twice.add(n);
			}
		}
		twice.forEach(out::remove);
		return out;
	}

	/** What goes up after a read: the whole log ({"read", "count", "max", "tabs", "names", "had"}) when the read holds as
	 *  many slots as the game's count says they have; else only what it found, as additions ({"add"}), so a read that
	 *  missed some (the search not answered) never takes slots off the page. */
	static JsonObject body(Map<Integer, Integer> had, int count, int max, JsonObject layout, long now)
	{
		JsonArray pairs = pairs(had);
		JsonObject o = new JsonObject();
		if (layout == null || count <= 0 || had.size() < count)
		{
			o.add("add", pairs);
			return o;
		}
		o.addProperty("read", now);
		o.addProperty("count", count);
		o.addProperty("max", max);
		o.add("tabs", layout.get("tabs"));
		o.add("names", layout.get("names"));
		o.add("had", pairs);
		return o;
	}

	/** One slot the chat named: {"add": [[item, 1]]}. */
	static JsonObject added(int item)
	{
		Map<Integer, Integer> m = new LinkedHashMap<>();
		m.put(item, 1);
		JsonObject o = new JsonObject();
		o.add("add", pairs(m));
		return o;
	}

	/** Two bodies held for the same update made one: a whole read replaces what came before it; additions go into
	 *  what's held (a slot already there keeps its count). */
	static JsonObject merge(JsonObject held, JsonObject next)
	{
		if (held == null || next.has("had"))
		{
			return next;
		}
		JsonObject out = held.deepCopy();
		JsonArray into = out.has("had") ? out.getAsJsonArray("had") : out.getAsJsonArray("add");
		Set<Integer> there = new LinkedHashSet<>();
		for (JsonElement p : into)
		{
			there.add(p.getAsJsonArray().get(0).getAsInt());
		}
		for (JsonElement p : next.getAsJsonArray("add"))
		{
			if (there.add(p.getAsJsonArray().get(0).getAsInt()) && into.size() < MOST)
			{
				into.add(p);
			}
		}
		if (out.has("had") && out.has("count"))
		{
			out.addProperty("count", Math.max(out.get("count").getAsInt(), into.size()));
		}
		return out;
	}

	/** How many slots the page has once this body's gone, given how many it had before (-1: not known). */
	static int synced(JsonObject body, int before)
	{
		if (body.has("had"))
		{
			return body.getAsJsonArray("had").size();
		}
		return Math.max(0, before) + body.getAsJsonArray("add").size();
	}

	/** Whether the side tab asks them to open their log: the game's count past what the page has (never sent: -1). */
	static boolean behind(int count, int synced)
	{
		return count > 0 && count > synced;
	}

	private static JsonArray pairs(Map<Integer, Integer> had)
	{
		JsonArray a = new JsonArray();
		had.forEach((id, n) ->
		{
			JsonArray p = new JsonArray();
			p.add(id);
			p.add(n);
			a.add(p);
		});
		return a;
	}
}
