package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GoalTest
{
	/** A goal's row dragged up or down (11/22/2026): the list reordered, the page's order following it under the one shown. */
	@Test
	public void movesAGoal()
	{
		Goal.Kept k = new Goal.Kept();
		k.add(1); k.add(2); k.add(3); k.add(4);
		assertTrue(k.move(4, 0));
		assertEquals(4, k.list.get(0).id);
		assertEquals(1, k.list.get(1).id);
		assertTrue(k.move(4, 9));
		assertEquals(4, k.list.get(3).id);
		assertFalse(k.move(4, 3));
		assertFalse(k.move(99, 0));
		assertTrue(k.move(3, 0));
		List<Integer> page = new ArrayList<>();
		for (Goal.One g : k.pageOrder())
		{
			page.add(g.id);
		}
		// (1 shown first, the rest in the list's order)
		assertEquals(java.util.Arrays.asList(1, 3, 2, 4), page);
	}

	@Test
	public void theLogLineNamesTheGoalWhateverItsCase()
	{
		assertTrue(Goal.isIt("Abyssal whip", "Abyssal whip"));
		assertTrue(Goal.isIt(" abyssal WHIP ", "Abyssal whip"));
		assertFalse(Goal.isIt("Abyssal dagger", "Abyssal whip"));
		assertFalse(Goal.isIt(null, "Abyssal whip"));
		assertFalse(Goal.isIt("Abyssal whip", ""));
	}

	@Test
	public void itsBodyHasTheItemAndWhenItWasGotOnlyOnceGot()
	{
		JsonObject b = Goal.body(4151, "Abyssal whip", null, 0, null);
		assertEquals(4151, b.get("id").getAsInt());
		assertEquals("Abyssal whip", b.get("name").getAsString());
		assertFalse(b.has("model"));
		assertFalse(b.has("got"));
		JsonObject counts = new JsonObject();
		counts.add("kills", new JsonObject());
		JsonObject got = Goal.body(4151, "Abyssal\nwhip", new JsonObject(), 1700000000000L, counts);
		assertEquals("Abyssal whip", got.get("name").getAsString());
		assertTrue(got.has("model"));
		assertEquals(1700000000000L, got.getAsJsonObject("got").get("at").getAsLong());
		assertTrue(got.getAsJsonObject("got").has("counts"));
	}

	// ---- the list (11/19/2026, Cranky: "add a goal item ... and you'd just have to x any you don't want anymore") ----

	@Test
	public void theFirstAddedIsShownTheRestFollowInTheirOrderEachOnce()
	{
		Goal.Kept k = new Goal.Kept();
		assertTrue(k.add(4151));
		assertTrue(k.add(11286));
		assertTrue(k.add(13576));
		assertFalse(k.add(11286));
		assertFalse(k.add(0));
		assertEquals(4151, k.shown);
		assertTrue(k.show(13576));
		assertFalse(k.show(13576));
		assertFalse(k.show(99999));
		List<Integer> order = new ArrayList<>();
		for (Goal.One g : k.pageOrder())
		{
			order.add(g.id);
		}
		assertEquals(List.of(13576, 4151, 11286), order);
	}

	@Test
	public void atMostTen()
	{
		Goal.Kept k = new Goal.Kept();
		for (int i = 1; i <= Goal.MOST; i++)
		{
			assertTrue(k.add(1000 + i));
		}
		assertFalse(k.add(5000));
		assertEquals(Goal.MOST, k.list.size());
	}

	@Test
	public void theOneShownTakenOffTheFirstLeftIsShown()
	{
		Goal.Kept k = new Goal.Kept();
		k.add(4151);
		k.add(11286);
		k.add(13576);
		k.show(11286);
		assertTrue(k.forget(11286));
		assertEquals(4151, k.shown);
		assertTrue(k.forget(13576));
		assertEquals(4151, k.shown);
		assertFalse(k.forget(13576));
		assertTrue(k.forget(4151));
		assertEquals(0, k.shown);
		assertTrue(k.list.isEmpty());
		assertTrue(k.add(11286));
		assertEquals(11286, k.shown);
	}

	@Test
	public void keptAndReadBackWithWhenEachWasGot()
	{
		Gson gson = new Gson();
		Goal.Kept k = new Goal.Kept();
		k.add(4151);
		k.add(11286);
		k.show(11286);
		JsonObject counts = new JsonObject();
		counts.add("kills", new JsonObject());
		k.find(4151).got = 1700000000000L;
		k.find(4151).counts = counts;
		Goal.Kept back = Goal.Kept.from(k.json(gson), gson);
		assertEquals(11286, back.shown);
		assertEquals(2, back.list.size());
		assertEquals(4151, back.list.get(0).id);
		assertEquals(1700000000000L, back.find(4151).got);
		assertTrue(back.find(4151).counts.has("kills"));
		assertEquals(0, back.find(11286).got);
		assertNull(back.find(11286).counts);
		// (anything broken reads as none; a shown that isn't in the list, the first)
		assertTrue(Goal.Kept.from("{nope", gson).list.isEmpty());
		assertTrue(Goal.Kept.from(null, gson).list.isEmpty());
		Goal.Kept odd = Goal.Kept.from("{\"shown\": 5, \"list\": [{\"id\": 4151}, {\"id\": 4151}, {\"x\": 1}, {\"id\": 13576}]}", gson);
		assertEquals(2, odd.list.size());
		assertEquals(4151, odd.shown);
	}

	@Test
	public void theOneGoalKeptBeforeIsTheListsFirst()
	{
		Gson gson = new Gson();
		Goal.Kept k = Goal.Kept.fromOne(4151, 1700000000000L, "{\"kills\": {\"Abyssal Sire\": 412}}", gson);
		assertEquals(1, k.list.size());
		assertEquals(4151, k.shown);
		assertEquals(1700000000000L, k.find(4151).got);
		assertEquals(412, k.find(4151).counts.getAsJsonObject("kills").get("Abyssal Sire").getAsInt());
		assertTrue(Goal.Kept.fromOne(null, null, null, gson).list.isEmpty());
		Goal.Kept notGot = Goal.Kept.fromOne(4151, null, null, gson);
		assertEquals(0, notGot.find(4151).got);
	}

	@Test
	public void theWholeGoalHasTheRestAsMoreOnlyWhenThereAreAny()
	{
		JsonObject top = Goal.body(4151, "Abyssal whip", new JsonObject(), 0, null);
		List<JsonObject> more = new ArrayList<>();
		assertFalse(Goal.body(top, more).has("more"));
		more.add(Goal.body(11286, "Draconic visage", null, 0, null));
		JsonObject whole = Goal.body(Goal.body(4151, "Abyssal whip", new JsonObject(), 0, null), more);
		assertEquals(1, whole.getAsJsonArray("more").size());
		assertFalse(whole.getAsJsonArray("more").get(0).getAsJsonObject().has("model"));
		assertTrue(whole.has("model"));
	}

	// (11/21/2026, round nine: the page builds a goal's model from the game's files when it comes without one, so No 3D
	// model is said, not left out)
	@Test
	public void noModelChosenTheFirstSaysSoAndOnlyIt()
	{
		List<JsonObject> more = new ArrayList<>();
		more.add(Goal.body(11286, "Draconic visage", null, 0, null));
		JsonObject none = Goal.body(Goal.body(4151, "Abyssal whip", null, 0, null), more, false);
		assertTrue(none.get("nomodel").getAsBoolean());
		assertFalse(none.has("model"));
		assertFalse(none.getAsJsonArray("more").get(0).getAsJsonObject().has("nomodel"));
		// (one shown as its model: no mark, as before)
		JsonObject shown = Goal.body(Goal.body(4151, "Abyssal whip", new JsonObject(), 0, null), new ArrayList<>(), true);
		assertFalse(shown.has("nomodel"));
		assertTrue(shown.has("model"));
		assertFalse(Goal.body(Goal.body(4151, "Abyssal whip", null, 0, null), null).has("nomodel"));
	}

	@Test
	public void noneTurningListsThemAllInTheirOrderAndPickingOneTurnsItAgain()
	{
		Gson gson = new Gson();
		Goal.Kept k = new Goal.Kept();
		k.add(4151);
		k.add(11286);
		k.show(11286);
		assertTrue(k.noModel());
		assertFalse(k.noModel());
		assertEquals(4151, k.pageOrder().get(0).id);
		Goal.Kept back = Goal.Kept.from(k.json(gson), gson);
		assertFalse(back.model);
		assertEquals(11286, back.shown);
		assertTrue(back.show(11286));
		assertTrue(back.model);
		assertEquals(11286, back.pageOrder().get(0).id);
		// (kept before this, with no "model": one turns)
		assertTrue(Goal.Kept.from("{\"shown\": 4151, \"list\": [{\"id\": 4151}]}", gson).model);
	}
}
