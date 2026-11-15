package wiki.outside;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The plugin's own field reports (11/15/2026): the game's lines read as Dink reads them, in Dink's shape. */
public class ReportsTest
{
	@Test
	public void killCounts()
	{
		JsonObject r = Reports.fromChat("Your Giant Mole kill count is: 2,770.", "sburb");
		assertEquals("KILL_COUNT", r.get("type").getAsString());
		assertEquals("Giant Mole", r.getAsJsonObject("extra").get("boss").getAsString());
		assertEquals(2770, r.getAsJsonObject("extra").get("count").getAsLong());
		assertEquals("Chambers of Xeric", Reports.fromChat("Your completed Chambers of Xeric count is: 150.", "a").getAsJsonObject("extra").get("boss").getAsString());
		assertEquals("Barrows", Reports.fromChat("Your Barrows chest count is: 41.", "a").getAsJsonObject("extra").get("boss").getAsString());
	}

	@Test
	public void logSlotsCluesAndPets()
	{
		JsonObject c = Reports.fromChat("New item added to your collection log: Baby mole", "sburb");
		assertEquals("COLLECTION", c.get("type").getAsString());
		assertEquals("Baby mole", c.getAsJsonObject("extra").get("itemName").getAsString());
		JsonObject t = Reports.fromChat("You have completed 184 medium Treasure Trails.", "sburb");
		assertEquals("CLUE", t.get("type").getAsString());
		assertEquals("medium", t.getAsJsonObject("extra").get("clueType").getAsString());
		assertEquals(184, t.getAsJsonObject("extra").get("numberCompleted").getAsLong());
		JsonObject p = Reports.fromChat("You have a funny feeling like you're being followed.", "sburb");
		assertEquals("PET", p.get("type").getAsString());
		assertTrue(Reports.fromChat("You have a funny feeling like you would have been followed...", "a").getAsJsonObject("extra").get("duplicate").getAsBoolean());
	}

	@Test
	public void otherLinesAreNone()
	{
		assertNull(Reports.fromChat("Welcome to Old School RuneScape.", "a"));
		assertNull(Reports.fromChat("Your reward is: 3 x Law rune.", "a"));
		assertNull(Reports.fromChat(null, "a"));
	}

	@Test
	public void levelsAndLoot()
	{
		Map<String, Integer> lv = new LinkedHashMap<>();
		lv.put("Hunter", 99);
		JsonObject l = Reports.levels(lv, "sburb");
		assertEquals("LEVEL", l.get("type").getAsString());
		assertEquals(99, l.getAsJsonObject("extra").getAsJsonObject("levelledSkills").get("Hunter").getAsInt());
		assertNull(Reports.levels(new LinkedHashMap<>(), "a"));
		JsonObject d = Reports.loot("Vorkath", Arrays.asList(new Reports.Item(22006, "Skeletal visage", 1, 6_000_000), new Reports.Item(536, "Dragon bones", 2, 2_500)),
			100_000, "sburb");
		assertEquals("LOOT", d.get("type").getAsString());
		assertEquals("Vorkath", d.getAsJsonObject("extra").get("source").getAsString());
		assertEquals(2, d.getAsJsonObject("extra").getAsJsonArray("items").size());
		assertNull(Reports.loot("Cow", Arrays.asList(new Reports.Item(526, "Bones", 1, 100)), 100_000, "a"));
	}
}
