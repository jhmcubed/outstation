package wiki.outside;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Quests, diaries, combat achievements and the slayer task (11/15/2026): the shapes docs/protocol.md gives them. */
public class ProgressTest
{
	@Test
	public void karamjaCountsAtTwo()
	{
		// (Karamja's first three: 0 not started, 1 started, 2 its tasks done; Dink's isComplete)
		assertFalse(Progress.diaryDone(VarbitID.ATJUN_EASY_DONE, 1));
		assertTrue(Progress.diaryDone(VarbitID.ATJUN_EASY_DONE, 2));
		assertFalse(Progress.diaryDone(VarbitID.ATJUN_HARD_DONE, 1));
		assertTrue(Progress.diaryDone(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, 1));
		assertTrue(Progress.diaryDone(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 1));
		assertFalse(Progress.diaryDone(VarbitID.VARROCK_DIARY_EASY_COMPLETE, 0));
	}

	@Test
	public void anArea()
	{
		JsonArray a = Progress.area(Progress.DIARY[5], new int[]{2, 1, 0, 0}, 23, 81);
		assertEquals("[1,0,0,0,23,81]", a.toString());
		assertEquals(12, Progress.AREAS.length);
		assertEquals(12, Progress.DIARY.length);
		assertEquals(12, Progress.AREA_IDS.length);
		assertEquals("Karamja", Progress.AREAS[5]);
	}

	@Test
	public void combat()
	{
		JsonObject c = Progress.combat(1234, new int[]{33, 41, 30, 12, 2, 0}, new int[]{33, 115, 304, 820, 1465, 2005});
		assertEquals(1234, c.get("points").getAsInt());
		assertEquals(6, c.getAsJsonArray("tiers").size());
		assertEquals("[33,33]", c.getAsJsonArray("tiers").get(0).toString());
		assertEquals("[0,2005]", c.getAsJsonArray("tiers").get(5).toString());
	}

	@Test
	public void quests()
	{
		JsonObject q = Progress.quests(201, 216, Arrays.asList("Cook's Assistant", "Dragon Slayer I"), Collections.singletonList("  Desert   Treasure II "), Collections.singletonList("Sins of the Father"));
		assertEquals(201, q.get("points").getAsInt());
		assertEquals(216, q.get("total").getAsInt());
		assertEquals(2, q.getAsJsonArray("done").size());
		assertEquals("Desert Treasure II", q.getAsJsonArray("started").get(0).getAsString());
		assertEquals("Sins of the Father", q.getAsJsonArray("todo").get(0).getAsString());
	}

	@Test
	public void slayer()
	{
		JsonObject s = Progress.slayer("Abyssal demons", 87, 180, "Catacombs of Kourend", 1450, 312);
		assertEquals("Abyssal demons", s.get("task").getAsString());
		assertEquals(87, s.get("left").getAsInt());
		assertEquals(180, s.get("of").getAsInt());
		assertEquals("Catacombs of Kourend", s.get("area").getAsString());
		// (no task: the points and the streak still go)
		JsonObject none = Progress.slayer(null, 0, 0, null, 1450, 312);
		assertTrue(none.get("task").isJsonNull());
		assertTrue(none.get("area").isJsonNull());
		assertEquals(0, none.get("left").getAsInt());
		assertEquals(312, none.get("streak").getAsInt());
		// (a task whose amount given reads under what's left: never "87 of 50")
		assertEquals(87, Progress.slayer("Hellhounds", 87, 50, null, 0, 0).get("of").getAsInt());
	}

	@Test
	public void questsGroupedAsTheGameHasThem()
	{
		// (11/16/2026: free, members and miniquests, and each one's points, from the game's quest table)
		JsonObject q = Progress.quests(3, Arrays.asList(
			new Progress.Q("Cook's Assistant", 2, false, false, 1),
			new Progress.Q("The Corsair Curse", 1, false, false, 2),
			new Progress.Q("Dragon Slayer II", 0, true, false, 5),
			new Progress.Q("Bear Your Soul", 1, true, true, 0)));
		assertEquals(4, q.get("total").getAsInt());
		assertEquals("[\"Cook's Assistant\"]", q.getAsJsonArray("done").toString());
		assertEquals("[\"Dragon Slayer II\"]", q.getAsJsonArray("started").toString());
		assertEquals("[\"The Corsair Curse\",\"Bear Your Soul\"]", q.getAsJsonArray("todo").toString());
		assertEquals("[\"Cook's Assistant\",\"The Corsair Curse\"]", q.getAsJsonArray("free").toString());
		assertEquals("[\"Bear Your Soul\"]", q.getAsJsonArray("mini").toString());
		assertEquals(5, q.getAsJsonObject("qp").get("Dragon Slayer II").getAsInt());
		assertTrue(!q.getAsJsonObject("qp").has("Bear Your Soul"));
	}

	@Test
	public void aQuestOfSubquestsHasTheirPoints()
	{
		assertEquals(2, Progress.ownOrParts(2, 10));
		assertEquals(10, Progress.ownOrParts(0, 10));
		assertEquals(0, Progress.ownOrParts(0, null));
	}
}
