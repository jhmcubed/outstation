package wiki.outside;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** EncounterStrip against strip.js itself (11/21/2026): IN through ghillie's replay/strip.js under node gave OUT and
 *  FACTS (its strip()'s kept and removed, and summary() of what it kept), and the Java port must give the same bytes.
 *  IN has what a recording can hold that's awkward to copy: Gson's escapes (\\u003c for "<"), an object's array-index keys,
 *  numbers JavaScript writes its own way, other players' names in any text, a lone surrogate, broken and blank lines,
 *  lines that aren't objects. The four real recordings were compared the same way, outside the tests (they're a friend's,
 *  never in a repository): the same bytes each (plugin/README.md, "Encounters"). */
public class EncounterStripTest
{
	static final String IN = "{\"t\":0,\"c\":1,\"e\":\"header\",\"format\":6,\"label\":\"Ed\\u003cge\\u003e \\u0026 \\u0027it\\u0027 \\u003d 1\",\"startedMs\":1795196500541,\"recorder\":\"outside\",\"player\":\"Mr Me\",\"x\":1.5e-7,\"y\":123456789012345678901234,\"z\":0.1,\"w\":-0.0,\"v\":1e21,\"u\":100000000000000000000}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"me\"},\"name\":\"Mr\u00a0Me\"}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"player\",\"i\":5},\"name\":\"Bob Smith\",\"equip\":[1,2,3]}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"player_spawn\",\"who\":{\"k\":\"player\",\"i\":6},\"name\":\"bob_ross\"}\n"
		+ "{\"t\":1,\"c\":3,\"e\":\"tick\",\"players\":[{\"who\":{\"k\":\"player\",\"i\":7},\"name\":\"Zed-1\",\"x\":1},{\"who\":{\"k\":\"me\"},\"name\":\"mr me\"}],\"10\":\"ten\",\"2\":\"two\",\"a\":\"Bob  Smith said hi to bob ross and BOBROSS and Zed 1. \u2028 \\ud800 end\",\"\u00e9\":\"x\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"chat\",\"type\":\"GAMEMESSAGE\",\"text\":\"\\u003ccol\\u003dff0000\\u003ebob ross\\u003c/col\\u003e - Twisted bow \\u0026 \\u0027stuff\\u0027 \\u003d \\\"yes\\\"\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"chat\",\"type\":\"FRIENDSCHATNOTIFICATION\",\"text\":\"Congratulations - your raid is complete! Team: Zed 1, Mr Me\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"chat\",\"type\":\"PUBLICCHAT\",\"who\":{\"k\":\"player\",\"i\":5},\"text\":\"hello\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"chat\",\"type\":\"PUBLICCHAT\",\"who\":{\"k\":\"me\"},\"text\":\"hi Bob Smith\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"say\",\"who\":{\"k\":\"player\",\"i\":5},\"text\":\"yo\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"say\",\"who\":{\"k\":\"npc\",\"i\":1,\"id\":3106},\"text\":\"Grr, bob ross.\"}\n"
		+ "{\"t\":3,\"c\":5,\"e\":\"stat\",\"skill\":\"ATTACK\",\"xp\":123,\"level\":99,\"boosted\":99}\n"
		+ "{\"t\":3,\"c\":5,\"e\":\"click\",\"option\":\"Walk here\",\"target\":\"Bob Smith (level-3)\",\"mx\":1,\"my\":2,\"wb\":[1,2,3,4]}\n"
		+ "{\"t\":3,\"c\":5,\"e\":\"press\",\"mx\":1}\n"
		+ "{\"t\":3,\"c\":5,\"e\":\"ui_layout\",\"inv\":[]}\n"
		+ "this is broken\n"
		+ "   \n"
		+ "{\"t\":4,\"c\":6,\"e\":\"hit\",\"who\":{\"k\":\"me\"},\"type\":16,\"amount\":5}\n"
		+ "{\"t\":4,\"c\":6,\"e\":\"hit\",\"who\":{\"k\":\"me\"},\"type\":6,\"amount\":0}\n"
		+ "{\"t\":4,\"c\":6,\"e\":\"hit\",\"who\":{\"k\":\"npc\",\"i\":1,\"id\":3106},\"type\":16,\"amount\":7}\n"
		+ "{\"t\":4,\"c\":6,\"e\":\"npcdef\",\"def\":{\"id\":3106,\"name\":\"Man\"}}\n"
		+ "{\"t\":5,\"c\":6,\"e\":\"npc_gone\",\"who\":{\"k\":\"npc\",\"i\":1,\"id\":3106},\"dead\":true}\n"
		+ "{\"t\":5,\"c\":7,\"e\":\"obj_anim\",\"id\":1530,\"kind\":\"wall\",\"anim\":4321,\"x\":3222,\"y\":3218}\n"
		+ "[1,\"bob ross\",{\"a\":\"bob ross\"}]\n"
		+ "\"bob ross\"\n"
		+ "{\"t\":6.7,\"c\":7,\"e\":\"death\",\"who\":{\"k\":\"me\"}}\n"
		+ "{\"t\":7,\"c\":8,\"e\":\"end\",\"reason\":\"killed\",\"ticks\":7}\n";

	static final String OUT = "{\"t\":0,\"c\":1,\"e\":\"header\",\"format\":6,\"label\":\"Ed<ge> & 'it' = 1\",\"startedMs\":1795196500541,\"recorder\":\"outside\",\"player\":\"Mr Me\",\"x\":1.5e-7,\"y\":1.2345678901234569e+23,\"z\":0.1,\"w\":0,\"v\":1e+21,\"u\":100000000000000000000}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"me\"},\"name\":\"Mr\u00a0Me\"}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"player\",\"i\":5},\"equip\":[1,2,3]}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"player_spawn\",\"who\":{\"k\":\"player\",\"i\":6}}\n"
		+ "{\"2\":\"two\",\"10\":\"ten\",\"t\":1,\"c\":3,\"e\":\"tick\",\"players\":[{\"who\":{\"k\":\"player\",\"i\":7},\"x\":1},{\"who\":{\"k\":\"me\"}}],\"a\":\"a player said hi to a player and BOBROSS and a player. \u2028 \\ud800 end\",\"\u00e9\":\"x\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"chat\",\"type\":\"GAMEMESSAGE\",\"text\":\"<col=ff0000>a player</col> - Twisted bow & 'stuff' = \\\"yes\\\"\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"chat\",\"type\":\"FRIENDSCHATNOTIFICATION\",\"text\":\"Congratulations - your raid is complete! Team: a player, Mr Me\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"chat\",\"type\":\"PUBLICCHAT\",\"who\":{\"k\":\"me\"},\"text\":\"hi a player\"}\n"
		+ "{\"t\":2,\"c\":4,\"e\":\"say\",\"who\":{\"k\":\"npc\",\"i\":1,\"id\":3106},\"text\":\"Grr, a player.\"}\n"
		+ "{\"t\":3,\"c\":5,\"e\":\"stat\",\"skill\":\"ATTACK\",\"level\":99,\"boosted\":99}\n"
		+ "{\"t\":3,\"c\":5,\"e\":\"click\",\"option\":\"Walk here\",\"target\":\"a player (level-3)\"}\n"
		+ "{\"t\":4,\"c\":6,\"e\":\"hit\",\"who\":{\"k\":\"me\"},\"type\":16,\"amount\":5}\n"
		+ "{\"t\":4,\"c\":6,\"e\":\"hit\",\"who\":{\"k\":\"me\"},\"type\":6,\"amount\":0}\n"
		+ "{\"t\":4,\"c\":6,\"e\":\"hit\",\"who\":{\"k\":\"npc\",\"i\":1,\"id\":3106},\"type\":16,\"amount\":7}\n"
		+ "{\"t\":4,\"c\":6,\"e\":\"npcdef\",\"def\":{\"id\":3106,\"name\":\"Man\"}}\n"
		+ "{\"t\":5,\"c\":6,\"e\":\"npc_gone\",\"who\":{\"k\":\"npc\",\"i\":1,\"id\":3106},\"dead\":true}\n"
		+ "{\"t\":5,\"c\":7,\"e\":\"obj_anim\",\"id\":1530,\"kind\":\"wall\",\"anim\":4321,\"x\":3222,\"y\":3218}\n"
		+ "[1,\"bob ross\",{\"a\":\"a player\"}]\n"
		+ "\"bob ross\"\n"
		+ "{\"t\":6.7,\"c\":7,\"e\":\"death\",\"who\":{\"k\":\"me\"}}\n"
		+ "{\"t\":7,\"c\":8,\"e\":\"end\",\"reason\":\"killed\",\"ticks\":7}\n";

	static final String FACTS = "{\"kept\":21,\"removed\":{\"other players' names\":13,\"other people's public chat\":1,\"other players' overhead text\":1,\"exact xp\":1,\"mouse presses and interface layout\":2,\"broken lines\":1},\"summary\":{\"label\":\"Ed<ge> & 'it' = 1\",\"started\":1795196500541,\"ticks\":7,\"foe\":\"Man\",\"died\":6.7,\"end\":\"killed\",\"taken\":5,\"dealt\":7,\"hits\":[[4,5]]},\"rawSummary\":{\"label\":\"Ed<ge> & 'it' = 1\",\"started\":1795196500541,\"ticks\":7,\"foe\":\"Man\",\"died\":6.7,\"end\":\"killed\",\"taken\":5,\"dealt\":7,\"hits\":[[4,5]]}}";

	@Test
	public void stripsAsStripJsDoes()
	{
		EncounterStrip.Stripped s = EncounterStrip.strip(IN);
		assertEquals(OUT, s.text);
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("kept", (double) s.kept);
		Map<String, Object> removed = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : s.removed.entrySet())
		{
			removed.put(e.getKey(), (double) e.getValue());
		}
		facts.put("removed", removed);
		facts.put("summary", EncounterStrip.summary(s.text).asJs());
		facts.put("rawSummary", EncounterStrip.summary(IN).asJs());
		assertEquals(FACTS, EncounterStrip.Js.stringify(facts));
	}

	@Test
	public void summarizesKillsAndDeaths()
	{
		EncounterStrip.Summary s = EncounterStrip.summary(IN);
		assertEquals("Man", s.who());
		assertTrue(s.killed);
		assertTrue(s.diedIn());
		assertEquals("Mr Me", s.player);
	}

	@Test
	public void namesFoesAsStripJsDoes()
	{
		Map<Object, Double> dealt = new LinkedHashMap<>();
		dealt.put("Eclipse Moon", 900.0);
		dealt.put("Sulphur Nagua", 40.0);
		dealt.put("Blue Moon", 1000.0);
		dealt.put("Blood Moon", 700.0);
		assertEquals("Eclipse Moon, Blue Moon, Blood Moon", EncounterStrip.foes(dealt));
		dealt.put("Zebak", 999.0);
		assertEquals("Eclipse Moon, Blue Moon and 2 more", EncounterStrip.foes(dealt));
		assertEquals("", EncounterStrip.foes(new LinkedHashMap<>()));
	}

	@Test
	public void writesNumbersAsJavaScriptDoes()
	{
		assertEquals("0", EncounterStrip.Js.number(-0.0));
		assertEquals("1e+21", EncounterStrip.Js.number(1e21));
		assertEquals("100000000000000000000", EncounterStrip.Js.number(1e20));
		assertEquals("36893488147419103000", EncounterStrip.Js.number(Math.pow(2, 65)));
		assertEquals("1.5e-7", EncounterStrip.Js.number(1.5e-7));
		assertEquals("0.000001", EncounterStrip.Js.number(1e-6));
		assertEquals("0.1", EncounterStrip.Js.number(0.1));
		assertEquals("1795196500541", EncounterStrip.Js.number(1795196500541.0));
	}

	@Test
	public void theMetaIsFightsJsPuts()
	{
		EncounterStrip.Summary s = EncounterStrip.summary(IN);
		assertEquals("{\"foe\":\"Man\",\"label\":" + EncounterStrip.Js.stringify(s.label) + ",\"at\":1795196500541,\"ticks\":7,"
			+ "\"died\":true,\"taken\":5,\"dealt\":7,\"public\":false}", EncounterStrip.meta(s, false));
	}

	/** Round five (11/21/2026): a window's picture is never searched for names (a short one could match its base64 and
	 *  break the PNG), its title is; the window and window_close lines kept. OUT from strip.js under node. */
	static final String WIN_IN = "{\"t\":0,\"c\":1,\"e\":\"header\",\"format\":6,\"label\":\"x\",\"player\":\"Mr Me\"}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"me\"},\"name\":\"Mr Me\"}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"player\",\"i\":5},\"name\":\"Bob\"}\n"
		+ "{\"t\":3,\"c\":9,\"e\":\"window\",\"n\":1,\"kind\":\"chest\",\"group\":973,\"title\":\"Bob's chest\",\"items\":[{\"id\":995,\"qty\":12,\"ge\":1,\"ha\":1}],\"box\":[138,101,488,300],\"canvas\":[765,503],\"picture\":\"iVBO+bob/Bob+AAA=\"}\n"
		+ "{\"t\":5,\"c\":19,\"e\":\"window_close\",\"n\":1}\n"
		+ "{\"t\":6,\"c\":29,\"e\":\"window\",\"n\":2,\"kind\":\"chat\",\"title\":\"You have a funny feeling like you're being followed. bob\",\"items\":[],\"picture\":\"AA+Bob/\"}\n"
		+ "{\"t\":6,\"c\":29,\"e\":\"chat\",\"type\":\"GAMEMESSAGE\",\"text\":\"bob hi\"}\n";
	static final String WIN_OUT = "{\"t\":0,\"c\":1,\"e\":\"header\",\"format\":6,\"label\":\"x\",\"player\":\"Mr Me\"}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"me\"},\"name\":\"Mr Me\"}\n"
		+ "{\"t\":0,\"c\":2,\"e\":\"look\",\"who\":{\"k\":\"player\",\"i\":5}}\n"
		+ "{\"t\":3,\"c\":9,\"e\":\"window\",\"n\":1,\"kind\":\"chest\",\"group\":973,\"title\":\"a player's chest\",\"items\":[{\"id\":995,\"qty\":12,\"ge\":1,\"ha\":1}],\"box\":[138,101,488,300],\"canvas\":[765,503],\"picture\":\"iVBO+bob/Bob+AAA=\"}\n"
		+ "{\"t\":5,\"c\":19,\"e\":\"window_close\",\"n\":1}\n"
		+ "{\"t\":6,\"c\":29,\"e\":\"window\",\"n\":2,\"kind\":\"chat\",\"title\":\"You have a funny feeling like you're being followed. a player\",\"items\":[],\"picture\":\"AA+Bob/\"}\n"
		+ "{\"t\":6,\"c\":29,\"e\":\"chat\",\"type\":\"GAMEMESSAGE\",\"text\":\"a player hi\"}\n";

	@Test
	public void keepsWindowPicturesAsStripJsDoes()
	{
		EncounterStrip.Stripped s = EncounterStrip.strip(WIN_IN);
		assertEquals(WIN_OUT, s.text);
		assertEquals(7, s.kept);
		assertEquals(Integer.valueOf(4), s.removed.get("other players' names"));
	}
}
