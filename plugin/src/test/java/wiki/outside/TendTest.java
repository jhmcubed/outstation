package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TendTest
{
	@Test
	public void aTokenIsWhatTheRelaysTake()
	{
		SecureRandom r = new SecureRandom();
		String t = Tend.token(r);
		assertTrue(t, t.matches("^[A-Za-z0-9_-]{43}$"));
		assertNotEquals(t, Tend.token(r));
	}

	@Test
	public void theBodyCarriesTheToken()
	{
		Gson gson = new Gson();
		JsonObject o = gson.fromJson(Tend.body("abc", gson), JsonObject.class);
		assertEquals("abc", o.get("token").getAsString());
		assertEquals(1, o.size());
	}
}
