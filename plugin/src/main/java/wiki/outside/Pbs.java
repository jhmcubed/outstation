package wiki.outside;

import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The personal bests RuneLite already keeps (update 7, 11/22/2026; Jace: "yes, add it to update 7"), so a page shows a
 * player's best times without fresh kills. RuneLite's Chat Commands plugin keeps each on the RuneLite profile, group
 * "personalbest", its key the boss's name lower-cased ("vorkath", a raid's by its team "chambers of xeric 3 players",
 * "theatre of blood solo"), its value seconds; it writes them from the kill and personal best lines, a raid's duration,
 * the Hallowed Sepulchre's floors and the house's Adventure Log's boss times (runelite/runelite's ChatCommandsPlugin,
 * setPb). Dink reads them the same way (pajlads/DinkPlugin, KillCountService.getPb: BSD 2-Clause, Copyright (c) 2022,
 * pajlads), and none when that plugin's switched off. Sent with the snapshot as "pbs", {key: seconds}, only when they've
 * changed; never a Wilderness boss's (Counts.wilderness, as the kill counts). Kept apart from RuneLite so it's tested.
 */
final class Pbs
{
	static final String GROUP = "personalbest";
	/** RuneLite's own config group and the Chat Commands plugin's key in it ("false": switched off) */
	static final String RUNELITE = "runelite", CHAT_COMMANDS = "chatcommandsplugin";
	/** at most this many (RuneLite keeps a few hundred at most: every boss, raid team size and Sepulchre floor) */
	static final int MOST = 400;
	/** a best of a day or more isn't one */
	static final double TOP = 86_400;

	/** The bests, by RuneLite's key, to the hundredth of a second; null with none. */
	static JsonObject read(Collection<String> keys, Function<String, Double> seconds)
	{
		Map<String, Double> m = new TreeMap<>();
		for (String key : keys)
		{
			String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
			if (k.isEmpty() || k.length() > 80 || Counts.wilderness(k))
			{
				continue;
			}
			Double s = seconds.apply(key);
			if (s != null && s > 0 && s < TOP)
			{
				m.put(k, Math.round(s * 100) / 100.0);
			}
		}
		if (m.isEmpty())
		{
			return null;
		}
		JsonObject o = new JsonObject();
		m.entrySet().stream().limit(MOST).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
		return o;
	}
}
