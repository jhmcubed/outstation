package wiki.outside;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The moment's memory (11/23/2026; ghillie's claude/outstation.md, "The moment's memory"): SaluteCFlagg's Doom of
 * Mokhaiotl delves, eight of ten put up with "it couldn't be made: Java heap space". A delve's stretch has dozens of
 * moving models (Doom, his projectiles, the falling rocks, the shields, the effects) in reach for most of its 34 ticks;
 * the moment's tracks had been made whole, every model at 25 frames a second, before being made smaller, and the frames'
 * corners copied into floats as well. Here, a scene as heavy as that: built within the track budget, the frames taken
 * further apart only as far as the budget needs, and a light scene built as it always was.
 */
public class EncounterMemoryTest
{
	/** a scene of {@code models} large moving models over ticks 0..40, a frame every 2 cycles (15 a tick), the kill at
	 *  tick 8 and its drop at 30: the stretch 2..35, 34 ticks, as long as one goes */
	static EncounterScene.Taken heavy(int models, int size) throws Exception
	{
		EncounterScene s = EncounterMapsTest.scene();
		List<EncounterScene.Shape> shapes = new ArrayList<>();
		for (int i = 0; i < models; i++)
		{
			EncounterMoment.Mesh m = EncounterMomentTest.grid(size, 0x2a50 + i);
			m.cls = 2;
			shapes.add(new EncounterScene.Shape("n" + i, EncounterMoment.KIND_ACTOR, m));
		}
		List<EncounterScene.Frame> fs = new ArrayList<>();
		List<int[]> clock = new ArrayList<>();
		for (int t = 0; t <= 40; t++)
		{
			clock.add(new int[]{t, t * 30});
			for (int k = 0; k < 15; k++)
			{
				EncounterScene.Frame f = new EncounterScene.Frame();
				f.tick = t;
				f.cycle = t * 30 + k * 2;
				f.h = 500;
				f.zoom = 512;
				for (int i = 0; i < models; i++)
				{
					EncounterMoment.Mesh m = shapes.get(i).mesh;
					int n = m.x.length;
					short[] xyz = new short[n * 3];
					// (each model stood up and moving round the middle, within a few tiles of it)
					double a = i * 0.7 + f.cycle * 0.01, cx = Math.cos(a) * (200 + i * 20), cz = Math.sin(a) * (200 + i * 20);
					for (int v = 0; v < n; v++)
					{
						xyz[v * 3] = (short) Math.round(cx + m.x[v] * 0.3);
						xyz[v * 3 + 1] = (short) Math.round(m.z[v] * 0.3 - 300);
						xyz[v * 3 + 2] = (short) Math.round(cz + (i % 3) * 30);
					}
					f.samples.add(new EncounterScene.Sample(shapes.get(i), xyz, null));
				}
				fs.add(f);
			}
		}
		EncounterMapsTest.frames(s, fs);
		@SuppressWarnings("unchecked")
		List<int[]> c = (List<int[]>) EncounterMapsTest.get(s, "clock");
		c.clear();
		c.addAll(clock);
		EncounterMapsTest.set(s, "killTick", 8);
		EncounterMapsTest.set(s, "dropTick", 30);
		EncounterMapsTest.set(s, "tick", 40);
		EncounterMapsTest.set(s, "stillKill", EncounterMapsTest.still(0, 8));
		EncounterMapsTest.set(s, "stillLoot", EncounterMapsTest.still(0, 30));
		EncounterScene.Taken t = s.finish(40);
		assertNull(t.none, t.none);
		return t;
	}

	@Test
	public void aDelvesWorthOfModelsIsBuiltWithinTheBudget() throws Exception
	{
		EncounterScene.Taken t = heavy(60, 16);
		long budget = 32L << 20;
		byte[] file = EncounterScene.build(t, EncounterMapsTest.base(), 7, budget);
		// (what it would have held: far past the budget; what it held: within it, the frames taken further apart)
		assertTrue("the tracks would have held " + (t.trackBytes >> 20) + " MB", t.trackBytes > 4 * budget);
		assertTrue("built holding " + (t.trackBytesBuilt >> 20) + " MB", t.trackBytesBuilt <= budget);
		assertTrue(t.spacing > 1);
		// (a moment all the same: its frames, its tracks, its anchor's tick, at most MOST)
		assertTrue(file.length <= EncounterMoment.MOST);
		EncounterMoment.Moment m = EncounterMoment.decode(file);
		assertTrue(m.frames > 1);
		assertTrue(m.tracks.size() > 0);
		assertEquals(31.0, EncounterMoment.num(m.meta.get("tick")), 0);
	}

	@Test
	public void aLightSceneIsBuiltAsBefore() throws Exception
	{
		EncounterScene.Taken t = heavy(2, 4);
		byte[] file = EncounterScene.build(t, EncounterMapsTest.base(), 7, 32L << 20);
		assertEquals(1, t.spacing);
		assertEquals(t.trackBytes, t.trackBytesBuilt);
		EncounterMoment.Moment m = EncounterMoment.decode(file);
		@SuppressWarnings("unchecked")
		java.util.Map<String, Object> fr = (java.util.Map<String, Object>) m.meta.get("frames");
		assertEquals(2.0, EncounterMoment.num(fr.get("cycles")), 0);
	}

	/**
	 * (11/26/2026, Vaerric's two Fractured Archive raids: "it couldn't be made: Java heap space", the same raids his
	 * teammate's client made moments of) A client short of memory gets a smaller moment, never none: each try that runs
	 * out lets the next, smaller one begin (the tracks an eighth of the budget and half the still dots; then the anchor's
	 * frame alone with a quarter of them), and only when every try has run out does the error go on to be said.
	 */
	@Test
	public void aClientShortOfMemoryGetsASmallerMomentNotNone() throws Exception
	{
		java.util.Map<String, Object> base = EncounterMapsTest.base();
		// (as ever when nothing runs out)
		EncounterScene.Taken t = heavy(60, 16);
		EncounterMoment.Moment whole = EncounterMoment.decode(EncounterScene.buildSmaller(t, base, 7, null));
		assertEquals(0, t.smaller);
		assertNull(((java.util.Map<?, ?>) whole.meta.get("taken")).get("smaller"));
		long wholeHeld = t.trackBytesBuilt;
		// (the first try out of memory: still moving, its tracks made for an eighth of the budget (each model keeps 200
		// dots at the least, so a crowd this big holds a little over it), fewer still dots)
		t = heavy(60, 16);
		EncounterMoment.Moment light = EncounterMoment.decode(EncounterScene.buildSmaller(t, base, 7, k -> k < 1));
		assertEquals(1, t.smaller);
		assertTrue("built holding " + (t.trackBytesBuilt >> 10) + " KB, the whole one " + (wholeHeld >> 10), t.trackBytesBuilt * 4 < wholeHeld);
		assertEquals(EncounterScene.SPACING_MOST, t.spacing);
		assertTrue(light.frames > 1);
		assertTrue(light.count < whole.count);
		assertEquals(1.0, EncounterMoment.num(((java.util.Map<?, ?>) light.meta.get("taken")).get("smaller")), 0);
		assertEquals(31.0, EncounterMoment.num(light.meta.get("tick")), 0);
		// (the second too: the anchor's frame alone, its models standing in it, the tick the same)
		t = heavy(60, 16);
		EncounterMoment.Moment one = EncounterMoment.decode(EncounterScene.buildSmaller(t, base, 7, k -> k < 2));
		assertEquals(2, t.smaller);
		assertEquals(1, one.frames);
		assertTrue(one.tracks.size() >= 60);
		assertTrue(one.count < light.count);
		assertEquals(31.0, EncounterMoment.num(one.meta.get("tick")), 0);
		// (every try out: the error, which the shelf notes as why there's none)
		try
		{
			EncounterScene.buildSmaller(heavy(2, 4), base, 7, k -> true);
			org.junit.Assert.fail("every try ran out of memory");
		}
		catch (OutOfMemoryError e)
		{
			assertEquals("Java heap space", e.getMessage());
		}
	}

	@Test
	public void theBudgetsAreFixed()
	{
		// (the client's memory isn't read: java.lang.management is forbidden on the Hub, rsbfox on #21195)
		assertEquals(32L << 20, EncounterScene.trackBudget());
		assertEquals(64L << 20, EncounterScene.bufferMost());
	}
}
