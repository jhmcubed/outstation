package wiki.outside;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field reports from the plugin itself (11/15/2026, Jace: the plugin first; a player with no outstation, and so no Dink
 * going through one, should still fill their page's event log and the Array's feed on outside.wiki). Each is made in
 * the shape Dink posts (its type, a line, and the "extra" fields the relay reads: relay/src/text.ts's reportData), from
 * what the game itself says, as Dink reads it: its chat's kill counts, collection log slots, pets and clues, the stats
 * as they level, and a monster's loot. Sent to the relay's Dink route with from=plugin (docs/protocol.md, "Dink"),
 * which drops them while the outstation transmits (Dink's come through it then). Kept apart from RuneLite so it's
 * tested.
 */
final class Reports
{
	// (Dink's own patterns, simplified: KillCountNotifier, CollectionNotifier, PetNotifier, ClueNotifier)
	private static final Pattern KILL = Pattern.compile("^Your (?:completed )?(.+?) (?:(?:kill|chest|completion|harvest|success) )?count is: ?([\\d,]+)\\b.*$");
	private static final Pattern CLOG = Pattern.compile("^New item added to your collection log: (.+?)\\s*$");
	private static final Pattern CLUE = Pattern.compile("^You have completed ([\\d,]+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.?\\s*$",
		Pattern.CASE_INSENSITIVE);
	// (11/15/2026: Dink's DiaryNotifier, CombatTaskNotifier and SlayerNotifier patterns)
	private static final Pattern DIARY = Pattern.compile("^Congratulations! You have completed all of the (.+) tasks in the (.+) area\\..*$");
	private static final Pattern COMBAT = Pattern.compile("^Congratulations, you've completed an? (\\w+) combat task: (.+?)(?:\\s+\\(\\d+ points?\\))?\\.?$");
	private static final Pattern SLAYER_TASK = Pattern.compile("^You have completed your task! You killed ([\\d,]+ [^.]+)\\..*$");
	private static final Pattern SLAYER_DONE = Pattern.compile("^You've completed (?:at least )?([\\d,]+) (?:Wilderness |Mortimer )?tasks?(?: and received ([\\d,]+) points, giving you a total of ([\\d,]+))?.*$");
	private static final String[] PET = {
		"You have a funny feeling like you're being followed.",
		"You feel something weird sneaking into your backpack.",
		"You have a funny feeling like you would have been followed...",
	};

	private Reports()
	{
	}

	/** A game message as a report, or null when it isn't one. `who`: their name, for the line. */
	static JsonObject fromChat(String msg, String who)
	{
		if (msg == null)
		{
			return null;
		}
		String m = msg.trim();
		Matcher k = KILL.matcher(m);
		if (k.matches())
		{
			JsonObject x = new JsonObject();
			x.addProperty("boss", k.group(1));
			x.addProperty("count", number(k.group(2)));
			return report("KILL_COUNT", who + "'s " + k.group(1) + " kill count is " + k.group(2), x);
		}
		Matcher c = CLOG.matcher(m);
		if (c.matches())
		{
			JsonObject x = new JsonObject();
			x.addProperty("itemName", c.group(1));
			return report("COLLECTION", who + " added " + c.group(1) + " to the collection log", x);
		}
		Matcher t = CLUE.matcher(m);
		if (t.matches())
		{
			JsonObject x = new JsonObject();
			x.addProperty("clueType", t.group(2).toLowerCase());
			x.addProperty("numberCompleted", number(t.group(1)));
			return report("CLUE", who + " has completed " + t.group(1) + " " + t.group(2).toLowerCase() + " clues", x);
		}
		Matcher d = DIARY.matcher(m);
		if (d.matches())
		{
			JsonObject x = new JsonObject();
			x.addProperty("area", d.group(2));
			x.addProperty("difficulty", d.group(1).toUpperCase(java.util.Locale.ROOT));
			return report("ACHIEVEMENT_DIARY", who + " has completed the " + d.group(1).toLowerCase(java.util.Locale.ROOT) + " " + d.group(2) + " diary", x);
		}
		Matcher ca = COMBAT.matcher(m);
		if (ca.matches())
		{
			JsonObject x = new JsonObject();
			String tier = ca.group(1).substring(0, 1).toUpperCase(java.util.Locale.ROOT) + ca.group(1).substring(1).toLowerCase(java.util.Locale.ROOT);
			x.addProperty("tier", tier);
			x.addProperty("task", ca.group(2));
			return report("COMBAT_ACHIEVEMENT", who + " has completed the " + tier.toLowerCase(java.util.Locale.ROOT) + " combat task " + ca.group(2), x);
		}
		for (int i = 0; i < PET.length; i++)
		{
			if (m.equals(PET[i]))
			{
				JsonObject x = new JsonObject();
				x.addProperty("duplicate", i == 2);
				return report("PET", i == 2 ? who + " would have had a pet, if they hadn't one already" : who + " has a funny feeling like they're being followed", x);
			}
		}
		return null;
	}

	/** The task just finished, from the game's "You have completed your task!" line (the count and the monster), or null. */
	static String slayerTask(String msg)
	{
		Matcher t = msg == null ? null : SLAYER_TASK.matcher(msg.trim());
		return t != null && t.matches() ? t.group(1) : null;
	}

	/** The line after it, "You've completed N tasks…", as the slayer report for that task; null when it isn't. */
	static JsonObject slayerDone(String msg, String task, String who)
	{
		Matcher c = msg == null || task == null ? null : SLAYER_DONE.matcher(msg.trim());
		if (c == null || !c.matches())
		{
			return null;
		}
		JsonObject x = new JsonObject();
		x.addProperty("slayerTask", task);
		// (numbers, not the game's "1,450": the relay reads them with parseInt)
		x.addProperty("slayerCompleted", number(c.group(1)));
		x.addProperty("slayerPoints", c.group(2) == null ? 0 : number(c.group(2)));
		return report("SLAYER", who + " has completed a slayer task: " + task, x);
	}

	/** A quest done (11/15/2026: told by the quest list read again, Progress), with how many are done of how many. */
	static JsonObject quest(String name, int done, int total, String who)
	{
		JsonObject x = new JsonObject();
		x.addProperty("questName", name);
		x.addProperty("completedQuests", done);
		x.addProperty("totalQuests", total);
		return report("QUEST", who + " has completed a quest: " + name, x);
	}

	/** Levels gained (a skill's name -> its new level), as one report. */
	static JsonObject levels(Map<String, Integer> levelled, String who)
	{
		if (levelled.isEmpty())
		{
			return null;
		}
		JsonObject skills = new JsonObject();
		StringBuilder words = new StringBuilder();
		for (Map.Entry<String, Integer> e : levelled.entrySet())
		{
			skills.addProperty(e.getKey(), e.getValue());
			words.append(words.length() > 0 ? ", " : "").append(e.getKey()).append(" to ").append(e.getValue());
		}
		JsonObject x = new JsonObject();
		x.add("levelledSkills", skills);
		return report("LEVEL", who + " has levelled " + words, x);
	}

	/** One of a drop's items: its id, name, how many and its price each. */
	static final class Item
	{
		final int id;
		final String name;
		final int quantity;
		final long price;

		Item(int id, String name, int quantity, long price)
		{
			this.id = id;
			this.name = name;
			this.quantity = quantity;
			this.price = price;
		}
	}

	/** A monster's loot as a report, if it's worth `most` or more in all; else null. */
	static JsonObject loot(String source, List<Item> items, long most, String who)
	{
		long value = 0;
		JsonArray list = new JsonArray();
		Item best = null;
		for (Item i : items)
		{
			value += i.price * i.quantity;
			if (best == null || i.price * i.quantity > best.price * best.quantity)
			{
				best = i;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", i.id);
			o.addProperty("name", i.name);
			o.addProperty("quantity", i.quantity);
			o.addProperty("priceEach", i.price);
			list.add(o);
		}
		if (best == null || value < most)
		{
			return null;
		}
		JsonObject x = new JsonObject();
		x.add("items", list);
		x.addProperty("source", source);
		x.addProperty("category", "NPC");
		return report("LOOT", who + " has looted " + best.name + (items.size() > 1 ? " and more" : "") + " from " + source, x);
	}

	private static JsonObject report(String type, String line, JsonObject extra)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("content", line);
		p.add("extra", extra);
		return p;
	}

	private static long number(String s)
	{
		return Long.parseLong(s.replace(",", ""));
	}
}
