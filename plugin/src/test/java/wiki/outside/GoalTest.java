package wiki.outside;

import com.google.gson.JsonObject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GoalTest
{
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
}
