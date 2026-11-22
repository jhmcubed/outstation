package wiki.outside;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The collection log read by Outside itself (Clog; update 7, 11/22/2026). */
public class ClogTest
{
	/** A cache of two tabs: Bosses (Vorkath: 22106 Vorki, 11286 Draconic visage, 22111 the replaced 22109) and Raids
	 *  (one page sharing the visage), and an Other tab whose two pages name the same item two ways. */
	static Clog.Cache cache()
	{
		Map<Integer, int[]> enums = new HashMap<>();
		Map<Integer, Integer> ints = new HashMap<>();
		Map<Integer, String> strs = new HashMap<>();
		Map<Integer, String> names = new HashMap<>();
		enums.put(Clog.TABS, new int[]{100, 200, 300});
		ints.put(100, 1000);
		ints.put(200, 2000);
		ints.put(300, 3000);
		enums.put(1000, new int[]{101});
		enums.put(2000, new int[]{201});
		enums.put(3000, new int[]{301, 302});
		ints.put(101, 1001);
		ints.put(201, 2001);
		ints.put(301, 3001);
		ints.put(302, 3002);
		enums.put(1001, new int[]{22106, 11286, 22109});
		enums.put(2001, new int[]{11286, 20997});
		enums.put(3001, new int[]{7000});
		enums.put(3002, new int[]{7001});
		strs.put(101, "Vorkath");
		strs.put(201, "Chambers of Xeric");
		strs.put(301, "Rooftop Agility");
		strs.put(302, "Barbarian Assault");
		names.put(22106, "Vorki");
		names.put(11286, "Draconic visage");
		names.put(22111, "Skeletal visage");
		names.put(20997, "Twisted bow");
		names.put(7000, "Decorative helm");
		names.put(7001, "Decorative helm");
		return new Clog.Cache()
		{
			@Override
			public int[] ints(int id)
			{
				return enums.getOrDefault(id, new int[0]);
			}

			@Override
			public int structInt(int struct, int param)
			{
				assertTrue(param == Clog.TAB_PAGES || param == Clog.PAGE_ITEMS);
				return ints.get(struct);
			}

			@Override
			public String structString(int struct, int param)
			{
				assertEquals(Clog.PAGE_NAME, param);
				return strs.get(struct);
			}

			@Override
			public int replaced(int item)
			{
				return item == 22109 ? 22111 : item;
			}

			@Override
			public String name(int item)
			{
				return names.get(item);
			}
		};
	}

	@Test
	public void theLogsPagesFromTheCache()
	{
		JsonObject l = Clog.layout(cache());
		JsonArray tabs = l.getAsJsonArray("tabs");
		assertEquals(3, tabs.size());
		assertEquals("Bosses", tabs.get(0).getAsJsonObject().get("name").getAsString());
		assertEquals("Raids", tabs.get(1).getAsJsonObject().get("name").getAsString());
		JsonObject vorkath = tabs.get(0).getAsJsonObject().getAsJsonArray("pages").get(0).getAsJsonObject();
		assertEquals("Vorkath", vorkath.get("name").getAsString());
		assertEquals("[22106,11286,22111]", vorkath.get("items").toString());   // (the replaced item as the log has it)
		assertEquals("Draconic visage", l.getAsJsonObject("names").get("11286").getAsString());
		assertEquals(6, l.getAsJsonObject("names").size());   // (each item once, on however many pages)
	}

	@Test
	public void namesTheChatCanSay()
	{
		Map<String, Integer> by = Clog.byName(Clog.layout(cache()));
		assertEquals(Integer.valueOf(22106), by.get("Vorki"));
		assertNull("two items of one name: neither", by.get("Decorative helm"));
		assertNull(by.get("Coins"));
	}

	@Test
	public void slotsTakenOnceQuiet()
	{
		Clog c = new Clog();
		assertNull(c.due(5));
		c.took(22106, 1, 10);
		c.took(11286, 3, 11);
		c.took(0, 1, 11);   // (no item: not a slot)
		assertTrue(c.reading(12));
		assertNull("still coming", c.due(12));
		assertFalse(c.reading(14));
		Map<Integer, Integer> had = c.due(14);
		assertEquals(2, had.size());
		assertEquals(Integer.valueOf(3), had.get(11286));
		assertNull("taken: the next read starts empty", c.due(20));
		c.took(22106, 1, 30);
		c.forget();
		assertNull(c.due(40));
	}

	@Test
	public void wholeOnlyWhenTheReadHoldsTheGamesCount()
	{
		JsonObject l = Clog.layout(cache());
		Map<Integer, Integer> had = new LinkedHashMap<>();
		had.put(22106, 1);
		had.put(11286, 2);
		JsonObject whole = Clog.body(had, 2, 1699, l, 1_800_000_000_000L);
		assertEquals("[[22106,1],[11286,2]]", whole.get("had").toString());
		assertEquals(2, whole.get("count").getAsInt());
		assertEquals(1699, whole.get("max").getAsInt());
		assertEquals(1_800_000_000_000L, whole.get("read").getAsLong());
		assertTrue(whole.has("tabs") && whole.has("names"));
		assertFalse(whole.has("add"));
		// (fewer than the game counts: the search wasn't answered; only additions, so nothing's taken off the page)
		JsonObject part = Clog.body(had, 40, 1699, l, 1L);
		assertEquals("[[22106,1],[11286,2]]", part.get("add").toString());
		assertFalse(part.has("had") || part.has("tabs"));
		// (no layout read, or the game's count not known: additions too)
		assertTrue(Clog.body(had, 2, 1699, null, 1L).has("add"));
		assertTrue(Clog.body(had, 0, 0, l, 1L).has("add"));
	}

	@Test
	public void heldBodiesMadeOne()
	{
		Map<Integer, Integer> had = new LinkedHashMap<>();
		had.put(22106, 1);
		JsonObject whole = Clog.body(had, 1, 1699, Clog.layout(cache()), 1L);
		assertEquals(Clog.added(20997), Clog.merge(null, Clog.added(20997)));
		JsonObject adds = Clog.merge(Clog.added(20997), Clog.added(11286));
		assertEquals("[[20997,1],[11286,1]]", adds.get("add").toString());
		assertEquals("the same slot once", adds, Clog.merge(adds, Clog.added(11286)));
		JsonObject more = Clog.merge(whole, Clog.added(20997));
		assertEquals("[[22106,1],[20997,1]]", more.get("had").toString());
		assertEquals(2, more.get("count").getAsInt());
		assertEquals("[[22106,1]]", whole.get("had").toString());   // (what was held is left as it was)
		assertEquals("a whole read replaces what's held", whole, Clog.merge(adds, whole));
	}

	@Test
	public void howManyThePageHas()
	{
		Map<Integer, Integer> had = new LinkedHashMap<>();
		had.put(22106, 1);
		had.put(11286, 1);
		assertEquals(2, Clog.synced(Clog.body(had, 2, 9, Clog.layout(cache()), 1L), 40));
		assertEquals(41, Clog.synced(Clog.added(20997), 40));
		assertEquals(1, Clog.synced(Clog.added(20997), -1));
		assertTrue(Clog.behind(5, -1));
		assertTrue(Clog.behind(42, 41));
		assertFalse(Clog.behind(41, 41));
		assertFalse("the game's count not known", Clog.behind(0, -1));
	}
}
