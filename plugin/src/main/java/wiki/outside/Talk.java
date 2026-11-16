package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The page's chat in the game (11/16/2026; the plugin first, the last of what still needed the outstation: "Emotes and
 * the chat", the ghillie repository's claude/outstation.md): with "The page's chat in my game" on, the plugin opens a
 * desk socket of its own on the relay (/desk/<channel>?key=…&name=…&talk=1, docs/protocol.md "The desk"), the one the
 * outstation's chat window opens. What visitors say on the player's Outside page shows in the game's chat box, as
 * RuneLite's own Twitch plugin shows Twitch's; `::o <words>` or `/o <words>` answers them as the page's host; `::ochat off` / `on`
 * stops and starts visitors' lines (the desk's chat_on). The relay's status line says "talk" while it's open, and the
 * page shows its chat while nothing's transmitting. Kept apart from RuneLite so it's tested.
 */
final class Talk
{
	/** a line of the page's chat: who said it and what */
	static final class Line
	{
		final String name;
		final String text;

		Line(String name, String text)
		{
			this.name = name;
			this.text = text;
		}
	}

	/** what one line may say, as the relay's SAY_MAX */
	static final int SAY_MAX = 300;

	private Talk()
	{
	}

	/**
	 * A visitor's line from what the desk hears, or null: anything that isn't a chat line, the host's own lines (the
	 * player's, from here or the outstation), a line with no words, and the chat so far that a desk gets as it opens
	 * (anything said before `since`, in ms, less a few seconds' grace for clocks).
	 */
	static Line visitor(String message, long since, Gson gson)
	{
		JsonObject m;
		try
		{
			m = gson.fromJson(message, JsonObject.class);
		}
		catch (RuntimeException e)
		{
			return null;
		}
		JsonElement c = m == null ? null : m.get("chat");
		if (c == null || !c.isJsonObject())
		{
			return null;
		}
		JsonObject o = c.getAsJsonObject();
		if (o.has("host") && o.get("host").isJsonPrimitive() && o.get("host").getAsBoolean())
		{
			return null;
		}
		String text = str(o, "text"), name = str(o, "name");
		if (text.isEmpty())
		{
			return null;
		}
		if (o.has("at") && o.get("at").isJsonPrimitive() && o.get("at").getAsJsonPrimitive().isNumber() && o.get("at").getAsLong() < since - 5000)
		{
			return null;
		}
		return new Line(name.isEmpty() ? "visitor" : name, text);
	}

	/** The player's answer, as the desk says it: one line, SAY_MAX at most; null for nothing to say. */
	static String say(String words, Gson gson)
	{
		String one = words == null ? "" : words.replaceAll("\\s+", " ").trim();
		if (one.isEmpty())
		{
			return null;
		}
		JsonObject m = new JsonObject();
		m.addProperty("say", one.length() > SAY_MAX ? one.substring(0, SAY_MAX) : one);
		return gson.toJson(m);
	}

	/** The words after /o at the chat box's start (any case; "/o" with nothing after it gives ""), or null when it isn't. */
	static String prefixed(String typed)
	{
		if (typed == null)
		{
			return null;
		}
		String t = typed.trim();
		if (t.equalsIgnoreCase("/o"))
		{
			return "";
		}
		return t.length() > 3 && t.substring(0, 3).equalsIgnoreCase("/o ") ? t.substring(3) : null;
	}

	/** Visitors' lines on or off (the desk's chat_on). */
	static String chatOn(boolean on, Gson gson)
	{
		JsonObject m = new JsonObject();
		m.addProperty("chat_on", on);
		return gson.toJson(m);
	}

	private static String str(JsonObject o, String k)
	{
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString().replaceAll("\\s+", " ").trim() : "";
	}
}
