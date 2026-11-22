package wiki.outside;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/** The personal bests RuneLite keeps, read for the snapshot (Pbs; update 7, 11/22/2026). */
public class PbsTest
{
	@Test
	public void asRuneLiteKeepsThem()
	{
		Map<String, Double> kept = new HashMap<>();
		kept.put("vorkath", 63.6);
		kept.put("chambers of xeric 3 players", 1534.2049999);
		kept.put("theatre of blood solo", 1789.0);
		kept.put("callisto", 50.0);           // (a Wilderness boss: never)
		kept.put("zulrah", -1.0);             // (not a time)
		kept.put("tempoross", 90_000.0);      // (a day and more: not a time)
		List<String> keys = Arrays.asList("vorkath", "chambers of xeric 3 players", "theatre of blood solo", "callisto", "zulrah",
			"tempoross", "nothing kept");
		JsonObject o = Pbs.read(keys, kept::get);
		assertEquals(3, o.size());
		assertEquals(63.6, o.get("vorkath").getAsDouble(), 0);
		assertEquals(1534.2, o.get("chambers of xeric 3 players").getAsDouble(), 0);
		assertEquals(1789.0, o.get("theatre of blood solo").getAsDouble(), 0);
		assertFalse(o.has("callisto"));
		assertEquals("the same read, the same words (sent only when they change)", o.toString(), Pbs.read(keys, kept::get).toString());
	}

	@Test
	public void noneIsNothing()
	{
		assertNull(Pbs.read(Collections.emptyList(), k -> 1.0));
		assertNull(Pbs.read(Collections.singletonList("callisto"), k -> 30.0));
	}

	@Test
	public void atMostTheMost()
	{
		Map<String, Double> kept = new HashMap<>();
		for (int i = 0; i < Pbs.MOST + 50; i++)
		{
			kept.put("boss " + i, 60.0 + i);
		}
		assertEquals(Pbs.MOST, Pbs.read(kept.keySet(), kept::get).size());
	}
}
