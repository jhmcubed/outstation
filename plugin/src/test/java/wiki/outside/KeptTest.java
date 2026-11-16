package wiki.outside;

// hub:cut model,bank
import com.google.gson.JsonObject;
// hub:end model,bank
// hub:cut bank
import java.util.LinkedHashMap;
// hub:end bank
// hub:cut bank
import java.util.Map;
// hub:end bank
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

	// hub:cut bank
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
	// hub:end bank

	// hub:cut model
	// hub:cut deathbank
	@Test
	public void theDeathbankWhereAndWhetherLocked()
	{
		assertEquals("Hespori", Kept.deathbankWhere(15)[0]);
		assertEquals(true, Kept.deathbankWhere(15)[1]);
		assertEquals(false, Kept.deathbankWhere(16)[1]);
		// (Zulrah's unlocked value is 0; -1 is no deathbank's window)
		assertEquals("Zulrah", Kept.deathbankWhere(0)[0]);
		assertNull(Kept.deathbankWhere(-1));
		assertEquals("Unknown", Kept.deathbankWhere(999)[0]);
	}

	@Test
	public void theDeathbankItsItemsAndEmptiedNone()
	{
		Map<Integer, Object[]> names = new LinkedHashMap<>();
		names.put(4151, new Object[]{"Abyssal whip", 1_500_000L});
		JsonObject d = Kept.deathbank("Hespori", true, new int[]{4151, -1, 995}, new int[]{1, 0, 25000}, names);
		assertEquals("{\"where\":\"Hespori\",\"locked\":true,\"items\":[[4151,1],[995,25000]],\"names\":{\"4151\":[\"Abyssal whip\",1500000]}}", d.toString());
		assertNull(Kept.deathbank("Hespori", false, new int[]{-1, -1}, new int[]{0, 0}, names));
	}

	@Test
	public void theDeathbankAtADeathByTheGamesLineOrTheRegion()
	{
		assertEquals("Hespori", Kept.deathbankAt("You have items stored in an item retrieval service. Speak to Arno at the Farming Guild.", -1));
		assertEquals("Phosani's Nightmare", Kept.deathbankAt("... Sister Senga ...", 15515));
		assertEquals("Grotesque Guardians", Kept.deathbankAt(Kept.DEATHBANK_LINE + ".", 6727));
		assertEquals("Unknown", Kept.deathbankAt(Kept.DEATHBANK_LINE + ".", 12850));
	}

	@Test
	public void whatADeathTookLessWhatWasKept()
	{
		// (a whip, a defender and a fire cape kept; the sharks, the coins and a second ring went)
		int[][] lost = Kept.lost(
			new int[]{4151, 385, 385, 995, 12954, 6570, 2550, 2550, -1},
			new int[]{1, 1, 1, 30000, 1, 1, 1, 1, 0},
			new int[]{4151, 12954, 6570, 2550, -1},
			new int[]{1, 1, 1, 1, 0});
		assertEquals("[385, 995, 2550]", java.util.Arrays.toString(lost[0]));
		assertEquals("[2, 30000, 1]", java.util.Arrays.toString(lost[1]));
		JsonObject d = Kept.deathbank("Hespori", true, lost[0], lost[1], new LinkedHashMap<>(), true);
		assertEquals("death", d.get("from").getAsString());
		assertFalse(Kept.deathbank("Hespori", true, lost[0], lost[1], new LinkedHashMap<>()).has("from"));
	}
	// hub:end deathbank

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

	// (11/15/2026: effects and the ground)
	@Test
	public void theEffectsMergedAfterThePlayersOwnAreFound()
	{
		// the player's 3 vertices, then a 2-vertex effect merged after them
		float[] x = {0, 1, 2, 10, 11}, z = {0, 0, 0, 5, 6};
		java.util.List<float[]> ex = java.util.Arrays.asList(new float[]{10, 11}), ez = java.util.Arrays.asList(new float[]{5, 6});
		assertEquals(3, Kept.ownVertices(x, z, 5, ex, ez, new int[]{2}));
		// not where the effect is: all the player's
		assertEquals(5, Kept.ownVertices(x, z, 5, ex, java.util.Arrays.asList(new float[]{5, 7}), new int[]{2}));
		// not merged at all (the model's no bigger than the effect): all the player's
		assertEquals(2, Kept.ownVertices(new float[]{10, 11}, new float[]{5, 6}, 2, ex, ez, new int[]{2}));
		assertEquals(5, Kept.ownVertices(x, z, 5, java.util.Collections.emptyList(), java.util.Collections.emptyList(), new int[]{}));
	}

	@Test
	public void aModelTrimmedToThePlayersOwn()
	{
		JsonObject m = Kept.model(new float[]{0, 1, 0, 9, 9, 9}, new float[]{0, 0, 1, 0, 0, 0}, new float[]{0, 0, 0, 0, 1, 2}, 6,
			new int[]{0, 3}, new int[]{1, 4}, new int[]{2, 5}, 2, new int[]{100, 200}, null, new int[]{-1, -1}, null, null, null, null, null, null, null, null);
		JsonObject t = Kept.trim(m, 3);
		assertEquals("[0,0,0,1,0,0,0,1,0]", t.get("v").toString());
		assertEquals("[0,1,2]", t.get("f").toString());
		assertEquals("[100]", t.get("c").toString());
		assertEquals("[100,100,100]", t.get("g").toString());
		assertEquals(m, Kept.trim(m, 6));   // (nothing past it: as it was)
	}

	@Test
	public void theGroundAsAModel()
	{
		int[] plain = {0, 0, 0, 128, 0, 0, 0, -10, 128, 100, 101, 102, -1, 0, 0, 4096, 0, 0, 4096};
		int[] hidden = plain.clone();
		hidden[10] = Kept.TILE_HIDDEN;
		int[] textured = plain.clone();
		textured[12] = 5;
		textured[9] = 0x1234 | 70;
		JsonObject g = Kept.ground(java.util.Arrays.asList(plain, hidden, textured));
		assertEquals("[0,1,2,3,4,5]", g.get("f").toString());
		assertEquals("[100,-1]", g.get("c").toString());
		assertEquals(6 * 3, g.getAsJsonArray("v").size());
		assertEquals((0x1234 | 70) & 127, g.getAsJsonArray("g").get(3).getAsInt());   // (a textured corner: its lightness)
		assertEquals("[-1,5]", g.get("t").toString());
		assertEquals("[0,0,0,0,0,0,0,0,4096,0,0,4096]", g.get("uv").toString());
	}
	// hub:end model
}
