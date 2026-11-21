package wiki.outside;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kill counts and clue counts as the game's chat says them (11/16/2026, Jace: field reports for Hub players without
 * Dink): "Your Vorkath kill count is: 418", "You have completed 152 hard Treasure Trails." Kept per account on this
 * RuneLite's profile and sent with each update (OutsidePlugin.read, "kc"); the relay reports a count that's gone up.
 * The patterns are Dink's (github.com/pajlads/DinkPlugin: KillCountNotifier, ClueNotifier; BSD 2-Clause, Copyright (c)
 * 2022, pajlads), as the whole plugin's Reports reads them. Kept apart from RuneLite so it's tested.
 */
final class Counts
{
	private static final Pattern KILL = Pattern.compile("^Your (?:completed )?(.+?) (?:(?:kill|chest|completion|harvest|success) )?count is: ?(?:@[A-Za-z0-9_]+@)?([\\d,]+)\\b.*$");
	private static final Pattern CLUE = Pattern.compile("^You have completed ([\\d,]+) (beginner|easy|medium|hard|elite|master) Treasure Trails?\\.?\\s*$",
		Pattern.CASE_INSENSITIVE);
	/** at most this many bosses kept (the game has fewer) */
	static final int MOST = 250;
	/** the most a count can say */
	static final int TOP = 10_000_000;
	/** never kept or sent: a Wilderness boss's count says where they've been (the relay's WILDERNESS_BOSSES, the same) */
	private static final String[] WILDERNESS = {"artio", "callisto", "calvar'ion", "chaos elemental", "chaos fanatic",
		"crazy archaeologist", "scorpia", "spindel", "venenatis", "vet'ion", "king black dragon", "revenant"};

	private final Map<String, Integer> kills = new TreeMap<>();
	private final Map<String, Integer> clues = new TreeMap<>();

	/** A game message read: true when it changed a count. */
	synchronized boolean take(String line)
	{
		if (line == null)
		{
			return false;
		}
		String m = line.trim();
		Matcher k = KILL.matcher(m);
		if (k.matches())
		{
			String boss = k.group(1).trim();
			return !wilderness(boss) && put(kills, boss, number(k.group(2)));
		}
		Matcher c = CLUE.matcher(m);
		if (c.matches())
		{
			return put(clues, c.group(2).toLowerCase(Locale.ROOT), number(c.group(1)));
		}
		return false;
	}

	static boolean wilderness(String boss)
	{
		String b = boss.toLowerCase(Locale.ROOT);
		for (String w : WILDERNESS)
		{
			if (b.startsWith(w))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean put(Map<String, Integer> to, String name, int n)
	{
		if (name.isEmpty() || name.length() > 60 || n < 0 || n > TOP || (!to.containsKey(name) && to.size() >= MOST))
		{
			return false;
		}
		Integer was = to.put(name, n);
		return was == null || was != n;
	}

	private static int number(String s)
	{
		try
		{
			return Integer.parseInt(s.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** {"kills": {boss: n}, "clues": {tier: n}}, or null with none. */
	synchronized JsonObject json()
	{
		if (kills.isEmpty() && clues.isEmpty())
		{
			return null;
		}
		JsonObject o = new JsonObject(), k = new JsonObject(), c = new JsonObject();
		kills.forEach(k::addProperty);
		clues.forEach(c::addProperty);
		o.add("kills", k);
		o.add("clues", c);
		return o;
	}

	/** The counts as kept (json()'s shape); anything else leaves them empty. */
	synchronized void load(JsonObject o)
	{
		kills.clear();
		clues.clear();
		if (o == null)
		{
			return;
		}
		for (String part : new String[]{"kills", "clues"})
		{
			if (!o.has(part) || !o.get(part).isJsonObject())
			{
				continue;
			}
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(part).entrySet())
			{
				try
				{
					if (!part.equals("kills") || !wilderness(e.getKey()))
					{
						put(part.equals("kills") ? kills : clues, e.getKey(), e.getValue().getAsInt());
					}
				}
				catch (RuntimeException ignored)
				{
				}
			}
		}
	}
}
