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

	@Test
	public void theBudgetFollowsTheClientsMemory()
	{
		long b = EncounterScene.trackBudget();
		assertTrue(b >= 16L << 20 && b <= EncounterScene.TRACK_BUDGET);
		long most = EncounterScene.bufferMost();
		assertTrue(most >= 16L << 20 && most <= EncounterScene.BUFFER_MOST);
	}
}
