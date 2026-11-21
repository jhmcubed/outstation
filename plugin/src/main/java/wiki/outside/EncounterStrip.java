package wiki.outside;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What leaves the computer when an encounter goes on the player's Outside page (11/21/2026; ghillie's
 * claude/outstation.md, "Encounters on Outside: the night's design"): ghillie's
 * tools/release-kit/prototypes/outside-splash/replay/strip.js, its strip() and summary(), in Java, so the plugin sends
 * exactly what the page would have sent from the same file:
 * <ul>
 * <li>other players' names go (their look and player_spawn lines', the tick lines' players', and in any text);</li>
 * <li>other people's public chat goes, and other players' overhead text;</li>
 * <li>exact XP goes from stat lines (the levels stay);</li>
 * <li>mouse presses and the interface layout go (press, ui_layout, ui_widgets lines; a click's mx, my and wb);</li>
 * <li>the player's own name stays (it's their page);</li>
 * <li>the player's own windows stay (window and window_close lines, 11/21/2026, round five): a window's picture is
 * never searched for names, its title is.</li>
 * </ul>
 * Checked line for line against strip.js run under node, on the four real recordings and on recordings this plugin
 * made in the local world (plugin/README.md, "Encounters", how it was checked): the same bytes out, the same counts and
 * the same summary. To be the same bytes, the lines are read and written as JavaScript's JSON.parse and JSON.stringify
 * do (Js below): every number a double, written as JavaScript writes one; an object's keys that are array indices first,
 * in order; strings escaped only where JavaScript escapes them (Gson would write < for "<"). Kept apart from
 * RuneLite so it's tested (EncounterStripTest).
 */
final class EncounterStrip
{
	private EncounterStrip()
	{
	}

	/** What strip() gives: the text to send, how many lines it kept, and what it took out (in the order first taken). */
	static final class Stripped
	{
		final String text;
		final int kept;
		final Map<String, Integer> removed;

		Stripped(String text, int kept, Map<String, Integer> removed)
		{
			this.text = text;
			this.kept = kept;
			this.removed = removed;
		}
	}

	/** The fight's facts for its row and its card (strip.js's summary(); `killed` is this plugin's, below). */
	static final class Summary
	{
		Object label = null;
		Object started = null;
		double ticks;
		/** who (strip.js's foes(), else the label, else "a fight"): a string but for a label that isn't one */
		Object foe;
		Object died = null;
		Object end = null;
		double taken;
		double dealt;
		final List<Object> hits = new ArrayList<>();
		/** (not strip.js's) whether a foe it names died in it: an npc_gone with dead true, or a death line, for one of them */
		boolean killed;
		/** (not strip.js's) the header's player, where this plugin wrote one */
		String player;
		/** (not strip.js's) the part before, for a part past the length limit (round three) */
		String follows;

		boolean diedIn()
		{
			return died != null && died != UNDEFINED;
		}

		/** who, as words */
		String who()
		{
			return Js.toStr(foe);
		}

		/** As strip.js's summary() object, for comparing with it: {label, started, ticks, foe, died, end, taken, dealt, hits}. */
		Map<String, Object> asJs()
		{
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("label", label);
			o.put("started", started);
			o.put("ticks", ticks);
			o.put("foe", foe);
			o.put("died", died);
			o.put("end", end);
			o.put("taken", taken);
			o.put("dealt", dealt);
			o.put("hits", hits);
			return o;
		}
	}

	/** JavaScript's undefined, where it differs from null (a missing field read). */
	static final Object UNDEFINED = new Object()
	{
		@Override
		public String toString()
		{
			return "undefined";
		}
	};

	// ---- strip()

	/** strip.js's plain(): a name as the game writes it, compared as plain lower-case words. */
	static String plain(String n)
	{
		String s = n.replaceAll("<[^>]+>", "").replaceAll("[ _-]", " ");
		s = Js.WS_RUN.matcher(s).replaceAll(" ");
		return Js.trim(s).toLowerCase(Locale.ROOT);
	}

	/** strip.js's esc(): a name as a pattern, its spaces matching any run of spaces, non-breaking spaces, _ or -. */
	private static String esc(String n)
	{
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < n.length(); i++)
		{
			char c = n.charAt(i);
			if (c == ' ')
			{
				b.append("[").append(Js.WS_CLASS).append("\\u00a0_-]+");
			}
			else if (".*+?^${}()|[]\\".indexOf(c) >= 0)
			{
				b.append('\\').append(c);
			}
			else if (c < 0x20 || c > 0x7e)
			{
				b.append(String.format("\\x{%x}", (int) c));
			}
			else
			{
				b.append(c);
			}
		}
		return b.toString();
	}

	/** Every other player's name a file holds (strip.js's first pass), line by line; then the pattern that finds them. */
	static final class Names
	{
		private final Set<String> names = new LinkedHashSet<>(), me = new LinkedHashSet<>();

		void line(String line)
		{
			if (!line.contains("\"name\""))
			{
				return;
			}
			Object o;
			try
			{
				o = Js.parse(line);
			}
			catch (IllegalArgumentException e)
			{
				return;
			}
			Object e = Js.get(o, "e");
			if ("look".equals(e) || "player_spawn".equals(e))
			{
				add(names, me, Js.get(o, "who"), Js.get(o, "name"));
			}
			Object players = Js.get(o, "players");
			if ("tick".equals(e) && players instanceof List)
			{
				for (Object p : (List<?>) players)
				{
					Object w = Js.get(p, "who");
					add(names, me, Js.truthy(w) ? w : p, Js.get(p, "name"));
				}
			}
		}

		/** The names as one pattern (strip.js's nameRe), or null when there are none. */
		Pattern pattern()
		{
			Set<String> names = new LinkedHashSet<>(this.names);
			names.removeAll(me);
			Pattern nameRe = null;
			if (!names.isEmpty())
			{
				List<String> sorted = new ArrayList<>();
				for (String n : names)
				{
					if (!n.isEmpty())
					{
						sorted.add(n);
					}
				}
				// (longest first, the rest as they came: JavaScript's sort is stable)
				sorted.sort((a, b) -> b.length() - a.length());
				List<String> parts = new ArrayList<>();
				boolean ascii = true;
				for (String n : sorted)
				{
					parts.add(esc(n));
					ascii &= n.chars().allMatch(ch -> ch < 0x80);
				}
				nameRe = Pattern.compile("(^|[^A-Za-z0-9])(" + String.join("|", parts) + ")(?![A-Za-z0-9])",
					Pattern.CASE_INSENSITIVE | (ascii ? 0 : Pattern.UNICODE_CASE));
			}
			return nameRe;
		}
	}

	/** strip.js's second pass, a line at a time: what's kept of each (null: dropped), what was taken out, how many kept. */
	static final class Stripper
	{
		private final Pattern nameRe;
		final Map<String, Integer> removed = new LinkedHashMap<>();
		int kept;

		Stripper(Pattern nameRe)
		{
			this.nameRe = nameRe;
		}

		String line(String line)
		{
			if (Js.trim(line).isEmpty())
			{
				return null;
			}
			Object o;
			try
			{
				o = Js.parse(line);
			}
			catch (IllegalArgumentException ex)
			{
				gone(removed, "broken lines");
				return null;
			}
			if (o == null)
			{
				// (strip.js throws reading a field of null: a file with a bare null line can't be stripped)
				throw new IllegalArgumentException("a line of the file is null");
			}
			Object e = Js.get(o, "e");
			Object who = Js.get(o, "who");
			boolean other = Js.truthy(who) && "player".equals(Js.get(who, "k"));
			if ("press".equals(e) || "ui_layout".equals(e) || "ui_widgets".equals(e))
			{
				gone(removed, "mouse presses and interface layout");
				return null;
			}
			if ("chat".equals(e) && Js.truthy(who) && !"me".equals(Js.get(who, "k")))
			{
				gone(removed, "other people's public chat");
				return null;
			}
			if ("say".equals(e) && other)
			{
				gone(removed, "other players' overhead text");
				return null;
			}
			if (o instanceof Map)
			{
				@SuppressWarnings("unchecked")
				Map<String, Object> m = (Map<String, Object>) o;
				if (("look".equals(e) || "player_spawn".equals(e)) && other && m.get("name") != null)
				{
					m.remove("name");
					gone(removed, "other players' names");
				}
				if ("stat".equals(e) && m.get("xp") != null)
				{
					m.remove("xp");
					gone(removed, "exact xp");
				}
				if ("click".equals(e))
				{
					m.remove("mx");
					m.remove("my");
					m.remove("wb");
				}
				Object players = m.get("players");
				if ("tick".equals(e) && players instanceof List)
				{
					for (Object p : (List<?>) players)
					{
						if (p instanceof Map && ((Map<?, ?>) p).get("name") != null)
						{
							((Map<?, ?>) p).remove("name");
							gone(removed, "other players' names");
						}
					}
				}
				if ("window".equals(e) && m.get("picture") instanceof String && nameRe != null)
				{
					// (a window's picture is never searched for names, its title is: strip.js's; round five)
					Object pic = m.get("picture");
					m.put("picture", "");
					scrubInPlace(m, nameRe, removed);
					m.put("picture", pic);
				}
				else if (!"header".equals(e) && nameRe != null)
				{
					scrubInPlace(m, nameRe, removed);
				}
			}
			else if (o instanceof List && !"header".equals(e) && nameRe != null)
			{
				// (strip.js maps an array to a new one it drops: a line that's an array goes as it was, but each
				// object in it is changed in place, and counted)
				for (Object v : (List<?>) o)
				{
					scrub(v, nameRe, removed);
				}
			}
			else if (o instanceof String && nameRe != null)
			{
				// (likewise a line that's a string: counted, sent as it was)
				scrub(o, nameRe, removed);
			}
			kept++;
			return Js.stringify(o);
		}
	}

	/** A text split as JavaScript's text.split("\n") splits it: at each \n only, the piece after the last one too. */
	static List<String> lines(String text)
	{
		return java.util.Arrays.asList(text.split("\n", -1));
	}

	static Stripped strip(String text)
	{
		Names names = new Names();
		for (String line : lines(text))
		{
			names.line(line);
		}
		Stripper st = new Stripper(names.pattern());
		List<String> out = new ArrayList<>();
		for (String line : lines(text))
		{
			String kept = st.line(line);
			if (kept != null)
			{
				out.add(kept);
			}
		}
		return new Stripped(String.join("\n", out) + "\n", st.kept, st.removed);
	}

	private static void add(Set<String> names, Set<String> me, Object w, Object n)
	{
		if (n instanceof String && !((String) n).isEmpty())
		{
			(Js.truthy(w) && "me".equals(Js.get(w, "k")) ? me : names).add(plain((String) n));
		}
	}

	static void gone(Map<String, Integer> removed, String what)
	{
		removed.merge(what, 1, Integer::sum);
	}

	/** strip.js's scrub(): a string with the names taken out (counted once a string that changed), an array's or
	 *  object's every value in turn; an object changed in place. */
	private static Object scrub(Object v, Pattern nameRe, Map<String, Integer> removed)
	{
		if (v instanceof String)
		{
			String s = (String) v;
			Matcher m = nameRe.matcher(s);
			StringBuffer b = new StringBuffer();
			boolean any = false;
			while (m.find())
			{
				any = true;
				m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + "a player"));
			}
			if (!any)
			{
				return s;
			}
			m.appendTail(b);
			String w = b.toString();
			if (!w.equals(s))
			{
				gone(removed, "other players' names");
			}
			return w;
		}
		if (v instanceof List)
		{
			@SuppressWarnings("unchecked")
			List<Object> list = (List<Object>) v;
			List<Object> copy = new ArrayList<>(list.size());
			for (Object x : list)
			{
				copy.add(scrub(x, nameRe, removed));
			}
			return copy;
		}
		if (v instanceof Map)
		{
			@SuppressWarnings("unchecked")
			Map<String, Object> m = (Map<String, Object>) v;
			scrubInPlace(m, nameRe, removed);
			return m;
		}
		return v;
	}

	private static void scrubInPlace(Map<String, Object> m, Pattern nameRe, Map<String, Integer> removed)
	{
		for (Map.Entry<String, Object> en : m.entrySet())
		{
			en.setValue(scrub(en.getValue(), nameRe, removed));
		}
	}

	// ---- summary()

	/** A fight's name from what was dealt to each foe (strip.js's foes()): every foe that took at least 30% of the most
	 *  any one took, in the order first hit; three at most named, then "and N more". */
	static final double FOE_SHARE = 0.3;

	/** The foes foes() names, in order (keys as strip.js has them: a name, or whatever the npcdef's name was). */
	static List<Object> foeList(Map<Object, Double> dealt)
	{
		double most = 0;
		for (double v : dealt.values())
		{
			most = Math.max(most, v);
		}
		List<Object> named = new ArrayList<>();
		if (most == 0 || Double.isNaN(most))
		{
			return named;
		}
		for (Map.Entry<Object, Double> en : dealt.entrySet())
		{
			Object n = en.getKey();
			if (Js.truthy(n) && !"null".equals(n) && en.getValue() >= most * FOE_SHARE)
			{
				named.add(n);
			}
		}
		return named;
	}

	static String foes(Map<Object, Double> dealt)
	{
		List<Object> named = foeList(dealt);
		List<String> words = new ArrayList<>();
		for (Object n : named)
		{
			words.add(Js.toStr(n));
		}
		return words.size() <= 3 ? String.join(", ", words)
			: words.get(0) + ", " + words.get(1) + " and " + (words.size() - 2) + " more";
	}

	/** strip.js's summary(), a line at a time: line() each, then finish(). */
	static final class Summarizer
	{
		private final Summary s = new Summary();
		private final Map<Object, Object> npcs = new LinkedHashMap<>();
		private final Map<Object, Double> dealtTo = new LinkedHashMap<>();
		// (this plugin's own: the NPCs that died in it, by id)
		private final Set<Object> deadIds = new LinkedHashSet<>();

		void line(String line)
		{
			if (Js.trim(line).isEmpty())
			{
				return;
			}
			Object o;
			try
			{
				o = Js.parse(line);
			}
			catch (IllegalArgumentException ex)
			{
				return;
			}
			if (o == null)
			{
				throw new IllegalArgumentException("a line of the file is null");
			}
			s.ticks = Math.max(s.ticks, Js.toInt32(Js.get(o, "t")));
			Object e = Js.get(o, "e");
			Object who = Js.get(o, "who");
			if ("header".equals(e))
			{
				s.label = Js.get(o, "label");
				s.started = Js.get(o, "startedMs");
				Object p = Js.get(o, "player");
				s.player = p instanceof String ? (String) p : null;
				// (round three: a part after the length limit names the part before; the parts go up joined)
				Object fw = Js.get(o, "follows");
				s.follows = fw instanceof String ? (String) fw : null;
			}
			else if ("npcdef".equals(e) && Js.truthy(Js.get(o, "def")))
			{
				Object def = Js.get(o, "def");
				npcs.put(key(Js.get(def, "id")), def);
			}
			else if ("end".equals(e))
			{
				s.end = Js.get(o, "reason");
			}
			else if ("death".equals(e) && Js.truthy(who) && "me".equals(Js.get(who, "k")) && (s.died == null || s.died == UNDEFINED))
			{
				s.died = Js.get(o, "t");
			}
			else if ("hit".equals(e) && Js.truthy(who))
			{
				Object k = Js.get(who, "k");
				Object amount = Js.get(o, "amount");
				if ("me".equals(k))
				{
					Object type = Js.get(o, "type");
					if (!(type instanceof Double && (Double) type == 6))
					{
						s.taken = s.taken + Js.toNumber(amount);
						List<Object> hit = new ArrayList<>();
						hit.add(Js.get(o, "t"));
						hit.add(amount);
						s.hits.add(hit);
					}
				}
				else if ("npc".equals(k) && Js.toNumber(amount) > 0)
				{
					s.dealt = s.dealt + Js.toNumber(amount);
					Object id = key(Js.get(who, "id"));
					Double was = dealtTo.get(id);
					dealtTo.put(id, (was == null || was == 0 || was.isNaN() ? 0 : was) + Js.toNumber(amount));
				}
			}
			if (("npc_gone".equals(e) && Boolean.TRUE.equals(Js.get(o, "dead")) || "death".equals(e)) && Js.truthy(who)
				&& "npc".equals(Js.get(who, "k")))
			{
				deadIds.add(key(Js.get(who, "id")));
			}
		}

		Summary finish()
		{
			// (by name, in the order first hit: several ids can be one foe)
			Map<Object, Double> byName = new LinkedHashMap<>();
			Map<Object, List<Object>> idsByName = new LinkedHashMap<>();
			for (Map.Entry<Object, Double> en : dealtTo.entrySet())
			{
				Object d = npcs.get(en.getKey());
				Object n = Js.truthy(d) ? Js.get(d, "name") : "npc " + Js.toStr(en.getKey());
				Double was = byName.get(n);
				byName.put(n, (was == null || was == 0 || was.isNaN() ? 0 : was) + en.getValue());
				idsByName.computeIfAbsent(n, x -> new ArrayList<>()).add(en.getKey());
			}
			String named = foes(byName);
			s.foe = !named.isEmpty() ? named : Js.truthy(s.label) ? s.label : "a fight";
			for (Object n : foeList(byName))
			{
				s.killed |= !Collections.disjoint(idsByName.get(n), deadIds);
			}
			return s;
		}
	}

	static Summary summary(String text)
	{
		Summarizer z = new Summarizer();
		for (String line : lines(text))
		{
			z.line(line);
		}
		return z.finish();
	}

	/** A Map key as JavaScript's Map has it (SameValueZero: -0 and 0 the same). */
	private static Object key(Object v)
	{
		return v instanceof Double && (Double) v == 0 ? (Object) 0.0 : v;
	}

	/** The upload's meta, as ghillie's fights.js put() makes it: {foe, label, at, ticks, died, taken, dealt, public};
	 *  without public when {@code shown} is null (11/21/2026: a send by itself the player hasn't chosen for, which the
	 *  relay then shows or keeps as its public_default says). */
	static String meta(Summary s, Boolean shown)
	{
		return meta(s, shown, null);
	}

	/** (round eight, 11/21/2026) with {@code noMoment}, why it goes up without a scene moment, as "moment_missing" */
	static String meta(Summary s, Boolean shown, String noMoment)
	{
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("foe", s.foe);
		o.put("label", Js.truthy(s.label) ? s.label : "");
		o.put("at", Js.truthy(s.started) ? s.started : 0.0);
		o.put("ticks", s.ticks);
		o.put("died", s.diedIn());
		o.put("taken", s.taken);
		o.put("dealt", s.dealt);
		if (shown != null)
		{
			o.put("public", shown);
		}
		if (noMoment != null)
		{
			o.put("moment_missing", noMoment);
		}
		return Js.stringify(o);
	}

	/**
	 * JSON as JavaScript reads and writes it (JSON.parse, JSON.stringify), for strip()'s lines to come out byte for byte
	 * as strip.js's: objects are LinkedHashMaps (JSON.parse's order, a repeated key keeping its first place), arrays
	 * ArrayLists, every number a Double, null null; a field that isn't there reads UNDEFINED.
	 */
	static final class Js
	{
		private Js()
		{
		}

		/** JavaScript's whitespace (String.prototype.trim and \s), as a regex class's inside. */
		static final String WS_CLASS = "\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";
		static final Pattern WS_RUN = Pattern.compile("[" + WS_CLASS + "]+");
		private static final Pattern TRIM = Pattern.compile("^[" + WS_CLASS + "]+|[" + WS_CLASS + "]+$");

		static String trim(String s)
		{
			return TRIM.matcher(s).replaceAll("");
		}

		static Object get(Object o, String k)
		{
			if (o instanceof Map)
			{
				Map<?, ?> m = (Map<?, ?>) o;
				return m.containsKey(k) ? m.get(k) : UNDEFINED;
			}
			return UNDEFINED;
		}

		static boolean truthy(Object v)
		{
			if (v == null || v == UNDEFINED)
			{
				return false;
			}
			if (v instanceof Boolean)
			{
				return (Boolean) v;
			}
			if (v instanceof Double)
			{
				double d = (Double) v;
				return d != 0 && !Double.isNaN(d);
			}
			if (v instanceof String)
			{
				return !((String) v).isEmpty();
			}
			return true;
		}

		static double toNumber(Object v)
		{
			if (v == null)
			{
				return 0;
			}
			if (v == UNDEFINED)
			{
				return Double.NaN;
			}
			if (v instanceof Double)
			{
				return (Double) v;
			}
			if (v instanceof Boolean)
			{
				return (Boolean) v ? 1 : 0;
			}
			if (v instanceof String)
			{
				String t = trim((String) v);
				if (t.isEmpty())
				{
					return 0;
				}
				try
				{
					if (t.matches("(?i)0x[0-9a-f]+"))
					{
						return new java.math.BigInteger(t.substring(2), 16).doubleValue();
					}
					if (t.matches("[+-]?Infinity"))
					{
						return t.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
					}
					if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?"))
					{
						return Double.NaN;
					}
					return Double.parseDouble(t);
				}
				catch (NumberFormatException e)
				{
					return Double.NaN;
				}
			}
			if (v instanceof List)
			{
				List<?> l = (List<?>) v;
				return l.isEmpty() ? 0 : l.size() == 1 ? toNumber(toStr(l.get(0))) : Double.NaN;
			}
			return Double.NaN;
		}

		/** JavaScript's x | 0. */
		static double toInt32(Object v)
		{
			double d = toNumber(v);
			if (Double.isNaN(d) || Double.isInfinite(d))
			{
				return 0;
			}
			return (int) (long) (d % 4294967296.0);
		}

		/** JavaScript's String(v), for what summary() names. */
		static String toStr(Object v)
		{
			if (v == null)
			{
				return "null";
			}
			if (v == UNDEFINED)
			{
				return "undefined";
			}
			if (v instanceof String)
			{
				return (String) v;
			}
			if (v instanceof Double)
			{
				return number((Double) v);
			}
			if (v instanceof Boolean)
			{
				return v.toString();
			}
			if (v instanceof List)
			{
				List<String> parts = new ArrayList<>();
				for (Object x : (List<?>) v)
				{
					parts.add(x == null || x == UNDEFINED ? "" : toStr(x));
				}
				return String.join(",", parts);
			}
			return "[object Object]";
		}

		// -- JSON.parse

		static Object parse(String s)
		{
			Parser p = new Parser(s);
			p.ws();
			Object v = p.value();
			p.ws();
			if (p.i != s.length())
			{
				throw new IllegalArgumentException("more after the value at " + p.i);
			}
			return v;
		}

		private static final class Parser
		{
			final String s;
			int i;

			Parser(String s)
			{
				this.s = s;
			}

			void ws()
			{
				while (i < s.length())
				{
					char c = s.charAt(i);
					if (c == ' ' || c == '\t' || c == '\n' || c == '\r')
					{
						i++;
					}
					else
					{
						break;
					}
				}
			}

			IllegalArgumentException bad()
			{
				return new IllegalArgumentException("not JSON at " + i);
			}

			Object value()
			{
				if (i >= s.length())
				{
					throw bad();
				}
				char c = s.charAt(i);
				switch (c)
				{
					case '{':
						return object();
					case '[':
						return array();
					case '"':
						return string();
					case 't':
						return word("true", Boolean.TRUE);
					case 'f':
						return word("false", Boolean.FALSE);
					case 'n':
						return word("null", null);
					default:
						if (c == '-' || (c >= '0' && c <= '9'))
						{
							return number();
						}
						throw bad();
				}
			}

			Object word(String w, Object v)
			{
				if (!s.startsWith(w, i))
				{
					throw bad();
				}
				i += w.length();
				return v;
			}

			Object number()
			{
				int start = i;
				if (s.charAt(i) == '-')
				{
					i++;
				}
				if (i >= s.length())
				{
					throw bad();
				}
				if (s.charAt(i) == '0')
				{
					i++;
				}
				else if (s.charAt(i) >= '1' && s.charAt(i) <= '9')
				{
					while (i < s.length() && Character.isDigit(s.charAt(i)) && s.charAt(i) < 0x80)
					{
						i++;
					}
				}
				else
				{
					throw bad();
				}
				if (i < s.length() && s.charAt(i) == '.')
				{
					i++;
					int d = i;
					while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9')
					{
						i++;
					}
					if (i == d)
					{
						throw bad();
					}
				}
				if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E'))
				{
					i++;
					if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-'))
					{
						i++;
					}
					int d = i;
					while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9')
					{
						i++;
					}
					if (i == d)
					{
						throw bad();
					}
				}
				return Double.parseDouble(s.substring(start, i));
			}

			String string()
			{
				i++;
				StringBuilder b = new StringBuilder();
				while (true)
				{
					if (i >= s.length())
					{
						throw bad();
					}
					char c = s.charAt(i++);
					if (c == '"')
					{
						return b.toString();
					}
					if (c < 0x20)
					{
						throw bad();
					}
					if (c != '\\')
					{
						b.append(c);
						continue;
					}
					if (i >= s.length())
					{
						throw bad();
					}
					char x = s.charAt(i++);
					switch (x)
					{
						case '"':
							b.append('"');
							break;
						case '\\':
							b.append('\\');
							break;
						case '/':
							b.append('/');
							break;
						case 'b':
							b.append('\b');
							break;
						case 'f':
							b.append('\f');
							break;
						case 'n':
							b.append('\n');
							break;
						case 'r':
							b.append('\r');
							break;
						case 't':
							b.append('\t');
							break;
						case 'u':
							if (i + 4 > s.length())
							{
								throw bad();
							}
							int v = 0;
							for (int k = 0; k < 4; k++)
							{
								int h = Character.digit(s.charAt(i + k), 16);
								if (h < 0 || s.charAt(i + k) > 0x7f)
								{
									throw bad();
								}
								v = v * 16 + h;
							}
							i += 4;
							b.append((char) v);
							break;
						default:
							throw bad();
					}
				}
			}

			List<Object> array()
			{
				i++;
				List<Object> list = new ArrayList<>();
				ws();
				if (i < s.length() && s.charAt(i) == ']')
				{
					i++;
					return list;
				}
				while (true)
				{
					ws();
					list.add(value());
					ws();
					if (i >= s.length())
					{
						throw bad();
					}
					char c = s.charAt(i++);
					if (c == ']')
					{
						return list;
					}
					if (c != ',')
					{
						throw bad();
					}
				}
			}

			Map<String, Object> object()
			{
				i++;
				Map<String, Object> m = new LinkedHashMap<>();
				ws();
				if (i < s.length() && s.charAt(i) == '}')
				{
					i++;
					return m;
				}
				while (true)
				{
					ws();
					if (i >= s.length() || s.charAt(i) != '"')
					{
						throw bad();
					}
					String k = string();
					ws();
					if (i >= s.length() || s.charAt(i++) != ':')
					{
						throw bad();
					}
					ws();
					m.put(k, value());
					ws();
					if (i >= s.length())
					{
						throw bad();
					}
					char c = s.charAt(i++);
					if (c == '}')
					{
						return m;
					}
					if (c != ',')
					{
						throw bad();
					}
				}
			}
		}

		// -- JSON.stringify

		static String stringify(Object v)
		{
			StringBuilder b = new StringBuilder();
			write(b, v);
			return b.toString();
		}

		/** An array index as a property key: a canonical whole number below 2^32 - 1. */
		private static boolean isIndex(String k)
		{
			if (k.isEmpty() || k.length() > 10 || (k.length() > 1 && k.charAt(0) == '0'))
			{
				return false;
			}
			for (int i = 0; i < k.length(); i++)
			{
				if (k.charAt(i) < '0' || k.charAt(i) > '9')
				{
					return false;
				}
			}
			return Long.parseLong(k) < 4294967295L;
		}

		private static void write(StringBuilder b, Object v)
		{
			if (v == null || v == UNDEFINED)
			{
				b.append("null");
			}
			else if (v instanceof String)
			{
				quote(b, (String) v);
			}
			else if (v instanceof Double)
			{
				double d = (Double) v;
				b.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : number(d));
			}
			else if (v instanceof Integer || v instanceof Long)
			{
				b.append(number(((Number) v).doubleValue()));
			}
			else if (v instanceof Boolean)
			{
				b.append(v.toString());
			}
			else if (v instanceof List)
			{
				b.append('[');
				boolean first = true;
				for (Object x : (List<?>) v)
				{
					if (!first)
					{
						b.append(',');
					}
					first = false;
					write(b, x);
				}
				b.append(']');
			}
			else if (v instanceof Map)
			{
				Map<?, ?> m = (Map<?, ?>) v;
				List<String> keys = new ArrayList<>();
				List<String> indices = new ArrayList<>();
				for (Object k : m.keySet())
				{
					(isIndex((String) k) ? indices : keys).add((String) k);
				}
				indices.sort((x, y) -> Long.compare(Long.parseLong(x), Long.parseLong(y)));
				indices.addAll(keys);
				b.append('{');
				boolean first = true;
				for (String k : indices)
				{
					Object x = m.get(k);
					if (x == UNDEFINED)
					{
						continue;
					}
					if (!first)
					{
						b.append(',');
					}
					first = false;
					quote(b, k);
					b.append(':');
					write(b, x);
				}
				b.append('}');
			}
			else
			{
				throw new IllegalArgumentException("not a JSON value: " + v.getClass());
			}
		}

		private static void quote(StringBuilder b, String s)
		{
			b.append('"');
			for (int i = 0; i < s.length(); i++)
			{
				char c = s.charAt(i);
				switch (c)
				{
					case '"':
						b.append("\\\"");
						break;
					case '\\':
						b.append("\\\\");
						break;
					case '\b':
						b.append("\\b");
						break;
					case '\f':
						b.append("\\f");
						break;
					case '\n':
						b.append("\\n");
						break;
					case '\r':
						b.append("\\r");
						break;
					case '\t':
						b.append("\\t");
						break;
					default:
						if (c < 0x20)
						{
							b.append(String.format("\\u%04x", (int) c));
						}
						else if (Character.isHighSurrogate(c))
						{
							if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
							{
								b.append(c).append(s.charAt(++i));
							}
							else
							{
								b.append(String.format("\\u%04x", (int) c));
							}
						}
						else if (Character.isLowSurrogate(c))
						{
							b.append(String.format("\\u%04x", (int) c));
						}
						else
						{
							b.append(c);
						}
				}
			}
			b.append('"');
		}

		/** A number as JavaScript writes it (Number.prototype.toString): the fewest digits that read back as it,
		 *  plain from 1e-7 up to 1e21, else with an exponent. */
		static String number(double x)
		{
			if (Double.isNaN(x))
			{
				return "NaN";
			}
			if (x == 0)
			{
				return "0";
			}
			if (Double.isInfinite(x))
			{
				return x > 0 ? "Infinity" : "-Infinity";
			}
			if (x == Math.rint(x) && Math.abs(x) < 9007199254740992.0)
			{
				return Long.toString((long) x);
			}
			String sign = x < 0 ? "-" : "";
			double a = Math.abs(x);
			BigDecimal exact = new BigDecimal(a);
			BigDecimal digits = null;
			for (int k = 1; k <= 17; k++)
			{
				BigDecimal r = exact.round(new MathContext(k, RoundingMode.HALF_EVEN));
				if (Double.parseDouble(r.toString()) == a)
				{
					digits = r;
					break;
				}
			}
			if (digits == null)
			{
				digits = exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
			}
			digits = digits.stripTrailingZeros();
			String ds = digits.unscaledValue().toString();
			int k = ds.length();
			int n = k - digits.scale();
			StringBuilder b = new StringBuilder(sign);
			if (k <= n && n <= 21)
			{
				b.append(ds);
				for (int i = 0; i < n - k; i++)
				{
					b.append('0');
				}
			}
			else if (0 < n && n <= 21)
			{
				b.append(ds, 0, n).append('.').append(ds.substring(n));
			}
			else if (-6 < n && n <= 0)
			{
				b.append("0.");
				for (int i = 0; i < -n; i++)
				{
					b.append('0');
				}
				b.append(ds);
			}
			else
			{
				int e = n - 1;
				b.append(ds.charAt(0));
				if (k > 1)
				{
					b.append('.').append(ds.substring(1));
				}
				b.append('e').append(e < 0 ? "-" : "+").append(Math.abs(e));
			}
			return b.toString();
		}
	}
}
