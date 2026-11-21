package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The goal items (11/17/2026, update 3; Goji Berries: "what if you could set a goal item from the plugin then it shows
 * your kc and dryness on the top of ur profile"): the items a player is after, each added from the game's own item
 * search (the side tab's "Add a goal item", or ::outsidegoal). Since 11/19/2026 more than one (Cranky: "I'd like to have
 * more than 1 goal item. maybe the button would be 'add a goal item' and it would just add to the list, and you'd just
 * have to x any you don't want anymore"; Jace: "choose ONE of the goal items to be displayed as a 3d model, while the
 * rest of the goal items are listed under the first and each other with their dryness bars"): MOST at most, one of them
 * shown (its 3D model on the page), the rest listed under it.
 *
 * Sent to their page's keep as the one shown, {"id", "name", "model"}, with the rest in "more" ({"id", "name"}, no
 * model); a goal the game's collection log line (or the loot) names has {"got": {"at", "counts"}}, the kill and clue
 * counts then (the page reads the kill count it took from those). The page works out how dry they are from the wiki's
 * drop rates.
 *
 * Or none shown as a model (11/19/2026, Jace: "add the option to NOT show a 3D model on your page for your goal/goals
 * list"): every goal listed with its picture, in the order they were added, nothing turning. Since 11/21/2026 (round
 * nine) the first then carries {"nomodel": true}: the page builds a goal's model from the game's own files when one
 * comes without it (an older plugin's goals mostly did), and this tells it they chose none. (A new key rather than
 * "model": false, which the relay before then refused, goal and all; it drops a key it doesn't know.)
 *
 * Kept for the RuneScape profile as one setting, LIST: {"shown": id, "model": false (none turning; left out: one is),
 * "list": [{"id", "got"?, "counts"?}, ...]}, in the order they were added. The one goal kept before (ITEM, GOT,
 * GOT_COUNTS) is read into it once, then let go.
 */
final class Goal
{
	private Goal()
	{
	}

	/** The RuneScape profile's own settings: the list (since 11/19/2026). */
	static final String LIST = "goals";
	/** Before 11/19/2026, the one goal: the item, and when it was got (0: not yet), with the counts then. */
	static final String ITEM = "goalItem";
	static final String GOT = "goalGot";
	static final String GOT_COUNTS = "goalGotCounts";
	/** How many goals at most (the relay takes 20; the page's column fits these). */
	static final int MOST = 10;

	/** One goal: the item, when it was got (0: not yet) and the counts then (null: none kept). */
	static final class One
	{
		final int id;
		long got;
		JsonObject counts;

		One(int id)
		{
			this.id = id;
		}
	}

	/** A player's goals: the list, the one shown (0: none), and whether it's shown as its model (false: none turning). */
	static final class Kept
	{
		final List<One> list = new ArrayList<>();
		int shown;
		boolean model = true;

		One find(int id)
		{
			for (One g : list)
			{
				if (g.id == id)
				{
					return g;
				}
			}
			return null;
		}

		/** The one shown, then the rest in the order they were added (none shown as a model: all in that order). */
		List<One> pageOrder()
		{
			if (!model)
			{
				return new ArrayList<>(list);
			}
			List<One> out = new ArrayList<>();
			One top = find(shown);
			if (top != null)
			{
				out.add(top);
			}
			for (One g : list)
			{
				if (g != top)
				{
					out.add(g);
				}
			}
			return out;
		}

		/** Added at the list's end (the first one added is shown); false when it's there already or the list is full. */
		boolean add(int id)
		{
			if (id <= 0 || find(id) != null || list.size() >= MOST)
			{
				return false;
			}
			list.add(new One(id));
			if (find(shown) == null)
			{
				shown = id;
			}
			return true;
		}

		/** Taken off; the one shown taken off, the first left is shown. */
		boolean forget(int id)
		{
			One g = find(id);
			if (g == null)
			{
				return false;
			}
			list.remove(g);
			if (shown == id)
			{
				shown = list.isEmpty() ? 0 : list.get(0).id;
			}
			return true;
		}

		/** This one shown as its model (none was: one is again). */
		boolean show(int id)
		{
			if (find(id) == null || (shown == id && model))
			{
				return false;
			}
			shown = id;
			model = true;
			return true;
		}

		/** None turning as a model: every goal listed with its picture. */
		boolean noModel()
		{
			if (!model)
			{
				return false;
			}
			model = false;
			return true;
		}

		String json(Gson gson)
		{
			JsonObject o = new JsonObject();
			o.addProperty("shown", shown);
			if (!model)
			{
				o.addProperty("model", false);
			}
			JsonArray a = new JsonArray();
			for (One g : list)
			{
				JsonObject e = new JsonObject();
				e.addProperty("id", g.id);
				if (g.got > 0)
				{
					e.addProperty("got", g.got);
					if (g.counts != null)
					{
						e.add("counts", g.counts);
					}
				}
				a.add(e);
			}
			o.add("list", a);
			return gson.toJson(o);
		}

		/** As kept (null or anything broken: none), each item once, MOST at most. */
		static Kept from(String json, Gson gson)
		{
			Kept k = new Kept();
			if (json == null || json.isEmpty())
			{
				return k;
			}
			try
			{
				JsonObject o = gson.fromJson(json, JsonObject.class);
				JsonArray a = o != null && o.has("list") && o.get("list").isJsonArray() ? o.getAsJsonArray("list") : new JsonArray();
				for (JsonElement el : a)
				{
					if (!el.isJsonObject() || !el.getAsJsonObject().has("id"))
					{
						continue;
					}
					JsonObject e = el.getAsJsonObject();
					int id = e.get("id").getAsInt();
					if (!k.add(id))
					{
						continue;
					}
					One g = k.find(id);
					g.got = e.has("got") ? e.get("got").getAsLong() : 0;
					g.counts = g.got > 0 && e.has("counts") && e.get("counts").isJsonObject() ? e.getAsJsonObject("counts") : null;
				}
				int shown = o != null && o.has("shown") ? o.get("shown").getAsInt() : 0;
				if (k.find(shown) != null)
				{
					k.shown = shown;
				}
				k.model = o == null || !o.has("model") || o.get("model").getAsBoolean();
			}
			catch (RuntimeException ex)
			{
				return new Kept();
			}
			return k;
		}

		/** The one goal kept before 11/19/2026, as a list of it (item 0 or none: empty). */
		static Kept fromOne(Integer item, Long got, String counts, Gson gson)
		{
			Kept k = new Kept();
			if (item == null || !k.add(item))
			{
				return k;
			}
			One g = k.find(item);
			g.got = got == null ? 0 : got;
			try
			{
				g.counts = g.got > 0 && counts != null ? gson.fromJson(counts, JsonObject.class) : null;
			}
			catch (RuntimeException ex)
			{
				g.counts = null;
			}
			return k;
		}
	}

	/** Whether the game's "New item added to your collection log" line is the goal's. */
	static boolean isIt(String added, String goalName)
	{
		return added != null && goalName != null && !goalName.isEmpty()
			&& added.trim().equalsIgnoreCase(goalName.trim());
	}

	/** What goes to the keep for one goal: the item, its model (null: none to send), and when it was got (0: not yet). */
	static JsonObject body(int id, String name, JsonObject model, long gotAt, JsonObject countsThen)
	{
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("name", name == null ? "" : name.replaceAll("[\\x00-\\x1f\\x7f]", " ").trim());
		if (model != null)
		{
			o.add("model", model);
		}
		if (gotAt > 0)
		{
			JsonObject got = new JsonObject();
			got.addProperty("at", gotAt);
			if (countsThen != null)
			{
				got.add("counts", countsThen);
			}
			o.add("got", got);
		}
		return o;
	}

	/** The whole goal for the keep: the one shown (with its model) and the rest under it as "more" (none: no "more"). */
	static JsonObject body(JsonObject shown, List<JsonObject> more)
	{
		return body(shown, more, true);
	}

	/** As above; model false (the side tab's No 3D model): the first marked "nomodel", so the page builds it none. */
	static JsonObject body(JsonObject shown, List<JsonObject> more, boolean model)
	{
		if (!model)
		{
			shown.remove("model");
			shown.addProperty("nomodel", true);
		}
		if (more != null && !more.isEmpty())
		{
			JsonArray a = new JsonArray();
			for (JsonObject m : more)
			{
				a.add(m);
			}
			shown.add("more", a);
		}
		return shown;
	}
}
