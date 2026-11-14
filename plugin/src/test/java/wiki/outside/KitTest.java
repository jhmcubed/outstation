package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KitTest
{
	@Test
	public void onlyWhatChangedGoesAgain()
	{
		Kit kit = new Kit();
		Map<String, JsonElement> now = new LinkedHashMap<>();
		now.put("world", new JsonPrimitive(302));
		now.put("inv", Kit.slots(new int[]{4151, -1}, new int[]{1, 0}, 28));
		JsonObject first = kit.changes(now);
		assertEquals(2, first.size());
		kit.took(first);
		assertEquals(0, kit.changes(now).size());
		now.put("world", new JsonPrimitive(330));
		JsonObject second = kit.changes(now);
		assertEquals(1, second.size());
		assertEquals(330, second.get("world").getAsInt());
		kit.forget();
		assertEquals(2, kit.changes(now).size());
	}

	@Test
	public void slotsKeepTheirPlacesAndItemsDont()
	{
		assertEquals("[[4151,1],null,null]", Kit.slots(new int[]{4151, -1}, new int[]{1, 0}, 3).toString());
		assertEquals("[[560,200],[565,50]]", Kit.items(new int[]{560, -1, 565}, new int[]{200, 0, 50}).toString());
		assertEquals(JsonNull.INSTANCE, Kit.slots(new int[0], new int[0], 28).get(27));
	}

	@Test
	public void eachItemIsNamedOnce()
	{
		Kit kit = new Kit();
		Map<String, JsonElement> now = new LinkedHashMap<>();
		now.put("inv", Kit.slots(new int[]{4151, 995}, new int[]{1, 1000}, 28));
		now.put("quiver", Kit.pair(28872, 300));
		Set<Integer> first = kit.unnamed(now);
		assertEquals(3, first.size());
		assertTrue(first.contains(28872));
		assertEquals(0, kit.unnamed(now).size());
		// (the seed box's and the chugging barrel's too, 11/14/2026)
		now.put("seeds", Kit.slots(new int[]{-1, 5295}, new int[]{0, 91}, 6));
		now.put("barrel", Kit.slots(new int[]{2434}, new int[]{55}, 5));
		Set<Integer> more = kit.unnamed(now);
		assertEquals(2, more.size());
		assertTrue(more.contains(5295) && more.contains(2434));
	}

	@Test
	public void aStationKeyReadsBackAsTheGameSocket()
	{
		String json = "{\"relay\": \"wss://relay.ghillie.workers.dev\", \"channel\": \"sburb\", \"key\": \"abcdefgh+/=ij\"}";
		String code = "outstation1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
		Gson gson = new Gson(); // (a test's own; the plugin uses RuneLite's)
		Attach a = Attach.parse("  " + code + " ", gson);
		assertEquals("sburb", a.getChannel());
		assertEquals("wss://relay.ghillie.workers.dev/game/sburb?key=abcdefgh%2B%2F%3Dij&v=0.1.0", a.gameUrl("0.1.0"));
		assertEquals("ws://127.0.0.1:8790/game/sburb?key=k&v=1",
			new Attach("http://127.0.0.1:8790/", "sburb", "k").gameUrl("1"));
		assertNull(Attach.parse("hello", gson));
		assertNull(Attach.parse("outstation1:!!!", gson));
	}

	@Test
	public void heldBackEmptiesWhatTheyCarried()
	{
		JsonObject h = Kit.held("wilderness");
		assertEquals("wilderness", h.get("paused").getAsString());
		assertEquals(0, h.getAsJsonArray("inv").size());
		assertEquals(0, h.getAsJsonArray("worn").size());
		assertEquals(0, h.getAsJsonArray("bag").size());
		assertEquals(0, h.getAsJsonArray("seeds").size());
		assertEquals(0, h.getAsJsonArray("barrel").size());
		assertTrue(h.get("quiver").isJsonNull());
	}

	@Test
	public void theWildernessByItsBounds()
	{
		assertTrue(OutsidePlugin.inWilderness(3100, 3530));
		assertTrue(OutsidePlugin.inWilderness(3050, 10000));
		assertTrue(!OutsidePlugin.inWilderness(3222, 3218));
		assertTrue(!OutsidePlugin.inWilderness(3100, 3519));
	}

	@Test
	public void accountTypes()
	{
		assertEquals("NORMAL", OutsidePlugin.accountType(0));
		assertEquals("HARDCORE_IRONMAN", OutsidePlugin.accountType(3));
	}
}
