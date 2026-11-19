package wiki.outside;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.runelite.client.eventbus.Subscribe;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** RuneLite's event bus won't start a plugin whose listener isn't named "on" and its event's name (11/19/2026: update 4
 *  went to the Hub with goalOnNpcLoot and wouldn't switch on). Every @Subscribe here, as the event bus checks it. */
public class SubscribeTest
{
	@Test
	public void listenersAreNamedAsTheEventBusRequires()
	{
		Set<String> seen = new HashSet<>();
		int n = 0;
		for (Method m : OutsidePlugin.class.getDeclaredMethods())
		{
			if (m.getAnnotation(Subscribe.class) == null)
			{
				continue;
			}
			n++;
			assertEquals(m + ": one parameter, the event", 1, m.getParameterCount());
			assertEquals(m + ": named for its event", "on" + m.getParameterTypes()[0].getSimpleName(), m.getName());
			assertTrue(m + ": one listener an event", seen.add(m.getName()));
		}
		assertTrue("the plugin listens to something", n > 0);
	}
}
