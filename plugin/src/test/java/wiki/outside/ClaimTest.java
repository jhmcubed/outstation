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
		// (since 11/16/2026 the game's spaces stay, as "_": Iron Man and IronMan are two players)
		assertEquals("iron_man", OutsidePlugin.channelName("Iron Man"));
		// (the game's names carry a no-break space where a space was typed)
		assertEquals("iron_man", OutsidePlugin.channelName("Iron Man"));
		assertEquals("zezima_9_9", OutsidePlugin.channelName("Zezima_9-9"));
		assertEquals("ironman", OutsidePlugin.channelName("IronMan"));
		assertEquals("oran_berries", OutsidePlugin.channelName(" Oran -Berries "));
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

	@Test
	public void aKeyIsItsOwnPlayersOrAPageMadeBeforeUnderTheOldForm()
	{
		org.junit.Assert.assertTrue(OutsidePlugin.isOwn("oran_berries", "oran_berries"));
		org.junit.Assert.assertTrue(OutsidePlugin.isOwn("oranberries", "oran_berries"));   // (made before 11/16/2026)
		org.junit.Assert.assertTrue(OutsidePlugin.isOwn("OranBerries", "oranberries"));
		org.junit.Assert.assertFalse(OutsidePlugin.isOwn("oran_berries", "oranberries"));
		org.junit.Assert.assertFalse(OutsidePlugin.isOwn("sburb", "oran_berries"));
		org.junit.Assert.assertFalse(OutsidePlugin.isOwn(null, "sburb"));
	}

	@Test
	public void theGamesLineForANewLogSlot()
	{
		assertEquals("Bandos chaps", OutsidePlugin.clogAdded("New item added to your collection log: Bandos chaps"));
		assertNull(OutsidePlugin.clogAdded("New item added to your collection log:"));
		assertNull(OutsidePlugin.clogAdded("You have a funny feeling like you're being followed."));
		assertNull(OutsidePlugin.clogAdded(null));
	}
}
