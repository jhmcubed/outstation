package wiki.outside;

import com.google.gson.JsonObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Kill counts and clue counts from the game's chat (Counts; 11/16/2026). */
public class CountsTest
{
	@Test
	public void killsAndCluesAsTheChatSaysThem()
	{
		Counts c = new Counts();
		assertNull(c.json());
		assertTrue(c.take("Your Vorkath kill count is: 418."));
		assertTrue(c.take("Your completed Chambers of Xeric count is: 54."));
		assertTrue(c.take("You have completed 1,152 hard Treasure Trails."));
		assertFalse(c.take("Your Vorkath kill count is: 418."));   // (the same again: nothing changed)
		assertFalse(c.take("You feel something weird sneaking into your backpack."));
		JsonObject o = c.json();
		assertEquals(418, o.getAsJsonObject("kills").get("Vorkath").getAsInt());
		assertEquals(54, o.getAsJsonObject("kills").get("Chambers of Xeric").getAsInt());
		assertEquals(1152, o.getAsJsonObject("clues").get("hard").getAsInt());
	}

	@Test
	public void neverAWildernessBoss()
	{
		Counts c = new Counts();
		assertFalse(c.take("Your Callisto kill count is: 40."));
		assertFalse(c.take("Your Vet'ion kill count is: 3."));
		assertNull(c.json());
		JsonObject kept = new JsonObject(), k = new JsonObject();
		k.addProperty("Venenatis", 9);
		kept.add("kills", k);
		c.load(kept);
		assertNull(c.json());
	}

	@Test
	public void keptAndBroughtBack()
	{
		Counts a = new Counts(), b = new Counts();
		a.take("Your Zulrah kill count is: 12.");
		b.load(a.json());
		assertEquals(12, b.json().getAsJsonObject("kills").get("Zulrah").getAsInt());
		b.load(null);
		assertNull(b.json());
	}
}
