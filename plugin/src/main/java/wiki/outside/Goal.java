package wiki.outside;

import com.google.gson.JsonObject;

/**
 * The goal item (11/17/2026, update 3; Goji Berries: "what if you could set a goal item from the plugin then it shows
 * your kc and dryness on the top of ur profile"): the item a player is after, picked from the game's own item search
 * (the side tab's "Set my goal item", or ::outsidegoal), sent to their page's keep as {"id", "name", "model"}; when the
 * game's collection log line names it, {"got": {"at", "counts"}} with it, the kill and clue counts then (the page reads
 * the kill count it took from those). The page works out how dry they are from the wiki's drop rates.
 */
final class Goal
{
	private Goal()
	{
	}

	/** The RuneScape profile's own settings: the item, and when it was got (0: not yet), with the counts then. */
	static final String ITEM = "goalItem";
	static final String GOT = "goalGot";
	static final String GOT_COUNTS = "goalGotCounts";

	/** Whether the game's "New item added to your collection log" line is the goal's. */
	static boolean isIt(String added, String goalName)
	{
		return added != null && goalName != null && !goalName.isEmpty()
			&& added.trim().equalsIgnoreCase(goalName.trim());
	}

	/** What goes to the keep: the item, its model (null: none to send), and when it was got (0: not yet). */
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
}
