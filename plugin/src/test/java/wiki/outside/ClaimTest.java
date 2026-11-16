package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** A name as a channel has it, for claiming a page (11/15/2026, docs/protocol.md "Claiming a name"). */
public class ClaimTest
{
	@Test
	public void aNameAsAChannelHasIt()
	{
		assertEquals("sburb", OutsidePlugin.channelName("sburb"));
		assertEquals("ironman", OutsidePlugin.channelName("Iron Man"));
		// (the game's names carry a no-break space where a space was typed)
		assertEquals("ironman", OutsidePlugin.channelName("Iron Man"));
		assertEquals("zezima99", OutsidePlugin.channelName("Zezima_9-9"));
		assertEquals("abcdefghijkl", OutsidePlugin.channelName("abcdefghijkl"));
	}

	@Test
	public void notEveryNameCanBeOne()
	{
		assertNull(OutsidePlugin.channelName(null));
		assertNull(OutsidePlugin.channelName(""));
		assertNull(OutsidePlugin.channelName("   "));
		assertNull(OutsidePlugin.channelName("abcdefghijklm"));
		assertNull(OutsidePlugin.channelName("<img=2>sburb"));
	}
}
