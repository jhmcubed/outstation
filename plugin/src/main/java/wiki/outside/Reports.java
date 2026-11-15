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
