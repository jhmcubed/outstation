package wiki.outside;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Charges from the game's chat, the bank and the model as the relay keeps them (Kept, 11/14/2026). */
public class KeptTest
{
	@Test
	public void chargesAsTheGameSaysThem()
	{
		assertEquals(Integer.valueOf(1234), Kept.charges("Your tonalztics of ralos has 1,234 charges left."));
		assertEquals(Integer.valueOf(1), Kept.charges("Your ring of recoil has 1 charge left."));
		assertEquals(Integer.valueOf(57), Kept.charges("Your archivist's folio has 57 charges remaining."));
		assertEquals(Integer.valueOf(12), Kept.charges("Uses remaining: 12"));
		assertEquals(Integer.valueOf(0), Kept.charges("Your blowpipe has run out of charges."));
		assertNull(Kept.charges("You eat the shark."));
		assertNull(Kept.charges(null));
	}

	@Test
	public void anItemByTheNameTheGameSays()
	{
		assertEquals("tonalztics of ralos", Kept.spoken("Tonalztics of ralos (charged)"));
		assertTrue(Kept.about("Your tonalztics of ralos has 50 charges.", "Tonalztics of ralos (charged)"));
		assertFalse(Kept.about("Your ring has 5 charges.", "Ring (i)"));   // (too short a name to tell by)
		assertFalse(Kept.about("Your blowpipe has 5 charges.", "Shark"));
	}

	@Test
	public void theBankItsTabsAndNames()
	{
		Map<Integer, Object[]> names = new LinkedHashMap<>();
		names.put(995, new Object[]{"Coins", 1L});
		JsonObject b = Kept.bank(new int[]{995, -1, 4151}, new int[]{1000, 0, 0}, new int[]{2, 0, 0, 0, 0, 0, 0, 0, 0}, names);
		assertEquals("[[995,1000],[4151,0]]", b.get("items").toString());   // (a placeholder: none of it)
		assertEquals("[2,0,0,0,0,0,0,0,0]", b.get("tabs").toString());
		assertEquals("{\"995\":[\"Coins\",1]}", b.get("names").toString());
	}

	@Test
	public void theModelLeavesOutWhatTheGameNeverDraws()
	{
		float[] x = {0, 10.4f, 0, 9000}, y = {0, 0, -10, 0}, z = {0, 0, 0, 0};
		int[] a = {0, 0, 1}, b = {1, 1, 2}, c = {2, 2, 3};
		int[] c1 = {100, 200, 300}, c3 = {-1, Kept.HIDDEN, 5};
		short[] tex = {-1, -1, 7};
		JsonObject m = Kept.model(x, y, z, 4, a, b, c, 3, c1, c3, null, new byte[]{0, 0, 0}, tex);
		assertEquals("[0,0,0,10,0,0,0,-10,0,8192,0,0]", m.get("v").toString());   // (rounded, kept in range)
		assertEquals("[0,1,2,1,2,3]", m.get("f").toString());                     // (the hidden face left out)
		assertEquals("[100,-1]", m.get("c").toString());                           // (textured: -1)
	}

	@Test
	public void theModelAsTheGameLitItWithItsTextures()
	{
		float[] x = {0, 128, 0}, y = {0, 0, 128}, z = {0, 0, 0};
		int[] a = {0, 0}, b = {1, 1}, c = {2, 2};
		int[] c1 = {100, 50}, c2 = {200, 60}, c3 = {Kept.FLAT, 70};
		short[] tex = {-1, 7};
		JsonObject m = Kept.model(x, y, z, 3, a, b, c, 2, c1, c2, c3, null, new byte[]{0, 127}, tex,
			new byte[]{0, 2}, new byte[]{0, 0}, new int[]{0}, new int[]{1}, new int[]{2});
		assertEquals("[100,100,100,50,60,70]", m.get("g").toString());   // (flat: one colour; textured: lightnesses)
		assertEquals("[255,128]", m.get("a").toString());
		assertEquals("[0,2]", m.get("p").toString());
		assertEquals("[-1,7]", m.get("t").toString());
		assertEquals("[0,0,0,0,0,0,0,0,4096,0,0,4096]", m.get("uv").toString());   // (the texture triangle is the face)
	}

	@Test
	public void aMotionsFramesAsLittleEndianNumbers()
	{
		String f = Kept.frame(new float[]{1, -2}, new float[]{3, 9000}, new float[]{0, 256}, 2);
		byte[] b = java.util.Base64.getDecoder().decode(f);
		assertEquals(12, b.length);
		java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		assertEquals(1, bb.getShort());
		assertEquals(3, bb.getShort());
		assertEquals(0, bb.getShort());
		assertEquals(-2, bb.getShort());
		assertEquals(8192, bb.getShort());   // (kept in range)
		assertEquals(256, bb.getShort());
		JsonObject o = Kept.frames(java.util.Arrays.asList(f, f), 12.5);
		assertEquals("{\"fps\":12.5,\"v\":[\"" + f + "\",\"" + f + "\"]}", o.toString());
	}

	@Test
	public void aMotionsNameFromTheCommand()
	{
		assertEquals("dance 2", OutsidePlugin.motionName(new String[]{"Dance", "2"}));
		assertNull(OutsidePlugin.motionName(new String[]{}));
		assertNull(OutsidePlugin.motionName(new String[]{"no!"}));
		assertNull(OutsidePlugin.motionName(new String[]{"aaaaaaaaaaaaaaaaaaaaaaaaa"}));
	}
}
