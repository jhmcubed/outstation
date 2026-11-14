package wiki.outside;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * (11/14/2026) What the plugin reads for the player's page beyond what's worn and carried, kept apart from RuneLite so
 * it's tested: an item's charges from the game's own chat line, the bank as the relay keeps it, and the character's
 * model as the page draws it (the relay's keep.ts has the shapes; docs/protocol.md, "Kept for the page").
 */
public final class Kept
{
	private Kept()
	{
	}

	// ---- charges (Jace: "show charges on the items that have charges like my tonalztics of ralos and archivist's
	// folio, if you can collect that info from the client"): the game says them only in its chat, when an item's
	// checked ("Your tonalztics of ralos has 1,234 charges left.") or as it's used, so they're read from there, as the
	// Hub's charge plugins read them

	/** "1,234 charges", "1 charge", "has 50 charges left", "(57 charges)", "uses remaining: 12" … */
	private static final Pattern CHARGES = Pattern.compile(
		"(?i)(?:\\b([0-9][0-9,]*)\\s+(?:charges?|uses?|scrolls?|doses?)\\b)|(?:(?:charges?|uses?)(?:\\s+(?:left|remaining))?\\s*[:]?\\s+([0-9][0-9,]*)\\b)");
	/** "none", "no charges left", "has run out" */
	private static final Pattern NONE = Pattern.compile("(?i)\\b(no charges|no more charges|has run out of charges|has no charges|is out of charges)\\b");

	/** The charges a game message tells of, or null: none told. 0 for "no charges". */
	public static Integer charges(String message)
	{
		if (message == null)
		{
			return null;
		}
		if (NONE.matcher(message).find())
		{
			return 0;
		}
		Matcher m = CHARGES.matcher(message);
		if (!m.find())
		{
			return null;
		}
		String n = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
		try
		{
			long v = Long.parseLong(n);
			return v > Integer.MAX_VALUE ? null : (int) v;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** An item's name as the game says it in chat: lower case, without its "(charged)", "(e)", "(4)" or the like. */
	public static String spoken(String name)
	{
		return name == null ? "" : name.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim().toLowerCase(Locale.ROOT);
	}

	/** Whether a message is about this item (its spoken name in it), its name at least 5 letters so a short word ("ring") never does. */
	public static boolean about(String message, String name)
	{
		String n = spoken(name);
		return n.length() >= 5 && message != null && message.toLowerCase(Locale.ROOT).contains(n);
	}

	// ---- the bank (Jace: "an option for people to capture their entire bank, just as the liveloadout twitch
	// extension/plugin offers"): every slot in the bank's own order, a placeholder as [its item, 0], the tabs' counts
	// (the game's BANK_TAB_1 to 9; the main tab is what's left), and each item's name and price

	/** {"items", "tabs", "names"} as the relay keeps it. */
	public static JsonObject bank(int[] ids, int[] quantities, int[] tabs, Map<Integer, Object[]> names)
	{
		JsonObject o = new JsonObject();
		JsonArray items = new JsonArray();
		for (int i = 0; i < ids.length && i < quantities.length; i++)
		{
			if (ids[i] > 0 && quantities[i] >= 0)
			{
				items.add(Kit.pair(ids[i], quantities[i]));
			}
		}
		o.add("items", items);
		JsonArray t = new JsonArray();
		for (int n : tabs)
		{
			t.add(Math.max(0, n));
		}
		o.add("tabs", t);
		JsonObject nm = new JsonObject();
		for (Map.Entry<Integer, Object[]> e : names.entrySet())
		{
			JsonArray np = new JsonArray();
			np.add((String) e.getValue()[0]);
			np.add(Math.max(0, ((Number) e.getValue()[1]).longValue()));
			nm.add(String.valueOf(e.getKey()), np);
		}
		o.add("names", nm);
		return o;
	}

	// ---- the model (Jace: "upload a captured model of their character onto their profile page just as RuneProfile
	// does; but we can make ours look cooler and rendered to our liking/in our own style"): only its shape and its
	// colours, as RuneProfile's exporter reads them (its ModelMeshBuilder, BSD 2-Clause: plugin/README.md, "Credits"),
	// the faces the game never draws left out

	/** A face the game never draws (its third colour), and a face shaded flat (one colour, not one a corner). */
	static final int HIDDEN = -2;

	/**
	 * {"v": vertices (x, y, z, the game's units, rounded), "f": faces (three vertices' places each), "c": each face's
	 * colour (the game's packed HSL unlit, or -1 for a textured face, drawn grey)}: every face drawn but the hidden and the
	 * wholly see-through ones. Null arrays are taken as none.
	 */
	public static JsonObject model(float[] x, float[] y, float[] z, int vertices, int[] a, int[] b, int[] c, int faces,
		int[] colors1, int[] colors3, short[] unlit, byte[] transparencies, short[] textures)
	{
		JsonObject o = new JsonObject();
		JsonArray v = new JsonArray();
		for (int i = 0; i < vertices; i++)
		{
			v.add(clamp(Math.round(x[i])));
			v.add(clamp(Math.round(y[i])));
			v.add(clamp(Math.round(z[i])));
		}
		JsonArray f = new JsonArray();
		JsonArray col = new JsonArray();
		for (int i = 0; i < faces; i++)
		{
			if (colors3 != null && colors3[i] == HIDDEN)
			{
				continue;
			}
			if (transparencies != null && (transparencies[i] & 0xff) == 255)
			{
				continue;
			}
			if (a[i] < 0 || b[i] < 0 || c[i] < 0 || a[i] >= vertices || b[i] >= vertices || c[i] >= vertices)
			{
				continue;
			}
			f.add(a[i]);
			f.add(b[i]);
			f.add(c[i]);
			boolean textured = textures != null && textures[i] != -1;
			int hsl = unlit != null ? unlit[i] & 0xffff : (colors1 != null ? colors1[i] & 0xffff : 0);
			col.add(textured ? -1 : hsl);
		}
		o.add("v", v);
		o.add("f", f);
		o.add("c", col);
		return o;
	}

	private static int clamp(int n)
	{
		return Math.max(-8192, Math.min(8192, n));
	}
}
