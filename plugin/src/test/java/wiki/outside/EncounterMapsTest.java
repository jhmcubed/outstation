package wiki.outside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Round eight (11/21/2026; ghillie's claude/outstation.md, "Encounters, round eight"): Jace's Fractured Archive raids as
 * his own recordings show them. A moment never crosses a map load (his raid falls had the arena's last seconds over his
 * house's ground), every encounter gets one or says why not, a long raid's buffer lets go of what it no longer needs, and
 * the raid's first room and its Nexus count as the raid (its recordings started at the second boss and came up in two
 * halves).
 */
public class EncounterMapsTest
{
	// ---- the stretch cut to one map

	@Test
	public void aStretchIsCutAtAMapLoadKeepingTheAnchorsSide()
	{
		// (the fall at 718 in the arena, the lobby loaded at 721: the stretch 712-723 keeps 712-720)
		int[] ticks = {712, 713, 714, 715, 716, 717, 718, 719, 720, 721, 722, 723};
		int[] maps = {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1};
		assertArrayEquals(new int[]{712, 720, 719, 0}, EncounterScene.cutToMap(ticks, maps, 712, 723, 719));
		// (a chest opened after the map came: the stretch before the load is the other side, dropped)
		assertArrayEquals(new int[]{721, 723, 722, 1}, EncounterScene.cutToMap(ticks, maps, 715, 723, 722));
		// (nothing crossed: as it was)
		assertArrayEquals(new int[]{712, 718, 715, 0}, EncounterScene.cutToMap(ticks, maps, 712, 718, 715));
		// (a tick with frames of both maps, the load mid-tick: the anchor's is the last frame's at or before it)
		int[] t2 = {10, 11, 11, 12};
		int[] m2 = {3, 3, 4, 4};
		assertArrayEquals(new int[]{11, 12, 12, 4}, EncounterScene.cutToMap(t2, m2, 10, 12, 12));
		assertArrayEquals(new int[]{10, 10, 10, 3}, EncounterScene.cutToMap(t2, m2, 10, 10, 10));
		// (no frame in the stretch)
		assertNull(EncounterScene.cutToMap(ticks, maps, 900, 910, 905));
		assertNull(EncounterScene.cutToMap(new int[0], new int[0], 0, 5, 2));
	}

	@Test
	public void theStillSceneIsTheOneOnTheAnchorsMap()
	{
		EncounterScene.Still arena = still(0, 718), lobby = still(1, 740), chest = still(2, 7733);
		assertSame(arena, EncounterScene.pickStill(0, arena, lobby, chest));
		// (the anchor's own on another map: one taken on its map instead)
		assertSame(chest, EncounterScene.pickStill(2, lobby, arena, chest));
		// (none on that map)
		assertNull(EncounterScene.pickStill(5, arena, lobby, chest));
		assertNull(EncounterScene.pickStill(0, null, null, null));
	}

	// ---- finish, as his raids went

	/** E12y… and zlOv…: the fall in the arena (tick 249), the raid going on through the Nexus and Moia, the recording
	 *  ending later on another map. The moment is the fall, in the arena, its still scene the arena's. */
	@Test
	public void aRaidFallIsTheArenaItHappenedIn() throws Exception
	{
		EncounterScene s = scene();
		List<EncounterScene.Frame> fs = new ArrayList<>();
		for (int t = 240; t <= 290; t++)
		{
			fs.add(frame(t, 0));
		}
		for (int t = 348; t <= 360; t++)
		{
			fs.add(frame(t, 1));
		}
		for (int t = 734; t <= 785; t++)
		{
			fs.add(frame(t, 2));
		}
		frames(s, fs);
		set(s, "deathTick", 249);
		set(s, "tick", 785);
		set(s, "epoch", 2);
		EncounterScene.Still fell = still(0, 249);
		set(s, "stillFall", fell);
		EncounterScene.Taken t = s.finish(785);
		assertNull(t.none, t.none);
		assertEquals("the fall", t.why);
		assertEquals(243, t.from);
		assertEquals(254, t.to);
		assertSame(fell.meshes, t.still);
		for (EncounterScene.Frame f : t.frames)
		{
			assertEquals(0, f.epoch);
		}
		// (and it's made: the moment built, its tick the fall's)
		byte[] file = EncounterScene.build(t, base(), 7);
		EncounterMoment.Moment m = EncounterMoment.decode(file);
		assertTrue(m.count > 1000);
		assertEquals(250.0, EncounterMoment.num(m.meta.get("tick")), 0);
	}

	/** -fbj… and dn-E…: the fall, the raid failed, the lobby loaded a few ticks after: the stretch stops at the load. */
	@Test
	public void aFallJustBeforeTheMapGoesIsCutThere() throws Exception
	{
		EncounterScene s = scene();
		List<EncounterScene.Frame> fs = new ArrayList<>();
		for (int t = 710; t <= 720; t++)
		{
			fs.add(frame(t, 0));
		}
		for (int t = 721; t <= 741; t++)
		{
			fs.add(frame(t, 1));
		}
		frames(s, fs);
		set(s, "deathTick", 718);
		set(s, "tick", 741);
		set(s, "epoch", 1);
		set(s, "stillFall", still(0, 718));
		EncounterScene.Taken t = s.finish(741);
		assertNull(t.none, t.none);
		assertEquals(712, t.from);
		assertEquals(720, t.to);
		assertEquals(712, t.cutFrom);
		assertEquals(720, t.cutTo);
		assertNotNull(EncounterScene.build(t, base(), 7));
	}

	/** UV0R…: three raids, three falls, the last raid's chest: the chest's opening, on the chest room's map. */
	@Test
	public void aRaidWithAChestIsTheChestEvenAfterFalls() throws Exception
	{
		EncounterScene s = scene();
		List<EncounterScene.Frame> fs = new ArrayList<>();
		for (int t = 7484; t <= 7500; t++)
		{
			fs.add(frame(t, 30));
		}
		for (int t = 7696; t <= 7767; t++)
		{
			fs.add(frame(t, 31));
		}
		frames(s, fs);
		set(s, "deathTick", 7490);
		set(s, "lootTick", 7733);
		set(s, "window", new EncounterScene.Window("reward", "The Fractured Archive", 973, "The Fractured Archive", new ArrayList<>()));
		set(s, "tick", 7882);
		set(s, "epoch", 33);
		set(s, "stillFall", still(30, 7490));
		EncounterScene.Still room = still(31, 7733);
		set(s, "stillLoot", room);
		EncounterScene.Taken t = s.finish(7882);
		assertNull(t.none, t.none);
		assertEquals("the chest", t.why);
		assertSame(room.meshes, t.still);
		assertEquals(7727, t.from);
		assertEquals(7741, t.to);
	}

	/** When no moment can be made, the Taken says why (the upload carries it), never a moment of two maps. */
	@Test
	public void noMomentSaysWhy() throws Exception
	{
		EncounterScene s = scene();
		assertEquals("no frame of the scene was kept", s.finish(10).none);
		List<EncounterScene.Frame> fs = new ArrayList<>();
		for (int t = 0; t <= 20; t++)
		{
			fs.add(frame(t, 0));
		}
		frames(s, fs);
		set(s, "deathTick", 10);
		set(s, "tick", 20);
		set(s, "epoch", 1);
		// (the fall's still scene taken on another map: no mix, a reason)
		set(s, "stillFall", still(1, 25));
		EncounterScene.Taken t = s.finish(20);
		assertEquals("the map it happened on was gone before its still scene was read", t.none);
		assertNull(t.frames);
		// (the meta carries it)
		EncounterStrip.Summary sum = new EncounterStrip.Summarizer().finish();
		assertTrue(EncounterStrip.meta(sum, null, t.none).contains("\"moment_missing\":\"the map it happened on"));
		assertFalse(EncounterStrip.meta(sum, null, null).contains("moment_missing"));
		assertEquals(EncounterStrip.meta(sum, true), EncounterStrip.meta(sum, true, null));
	}

	/** A long raid's buffer: shapes no frame kept has are let go, and frames come again once it's under its size. */
	@Test
	public void theBufferLetsGoOfShapesNoFrameHas() throws Exception
	{
		EncounterScene s = scene();
		@SuppressWarnings("unchecked")
		Map<String, EncounterScene.Shape> shapes = (Map<String, EncounterScene.Shape>) get(s, "shapes");
		EncounterScene.Shape keep = null;
		long total = 0;
		for (int i = 0; i < 200; i++)
		{
			EncounterScene.Shape sh = new EncounterScene.Shape("j" + i, EncounterMoment.KIND_PROJ, new EncounterMoment.Mesh());
			sh.bytes = EncounterScene.BUFFER_MOST / 100;
			total += sh.bytes;
			shapes.put(sh.key, sh);
			if (i == 199)
			{
				keep = sh;
			}
		}
		EncounterScene.Frame f = frame(5, 0);
		f.samples.add(new EncounterScene.Sample(keep, new short[0], null));
		frames(s, Collections.singletonList(f));
		set(s, "bytes", total);
		set(s, "full", true);
		Method sweep = EncounterScene.class.getDeclaredMethod("sweep");
		sweep.setAccessible(true);
		sweep.invoke(s);
		assertEquals(1, shapes.size());
		assertSame(keep, shapes.get("j199"));
		assertEquals(keep.bytes, (long) (Long) get(s, "bytes"));
		assertFalse((Boolean) get(s, "full"));
	}

	// ---- the Fractured Archive's rooms

	@Test
	public void theArchivesFirstRoomAndNexusAreTheRaid()
	{
		Presence.Area tfa = Presence.area(16214, 0);
		assertEquals("The Fractured Archive", tfa.name);
		// (the Archive Guardian's room and the Nexus, region 16471 as an instance's template: the raid, so entering it starts
		// the recording and a wait there never ends it)
		assertSame(tfa, Encounters.areaAt(16471, 0, true));
		assertSame(tfa, Encounters.areaAt(16471, 1, true));
		// (Ocellus's inner vault and the chest room, Discord's own)
		assertSame(tfa, Encounters.areaAt(16472, 1, true));
		assertSame(tfa, Encounters.areaAt(16474, 2, true));
		// (the same region outside an instance, and the lobby: not the raid)
		assertNull(Encounters.areaAt(16471, 0, false));
		assertNull(Encounters.areaAt(16470, 0, false));
		// (his house after: another area, not the raid)
		assertEquals("Player Owned House", Encounters.areaAt(7534, 0, true).name);
	}

	@Test
	public void theLinesThatEndARaidForThePlayer()
	{
		assertTrue(Encounters.RAID_OVER_LINE.matcher("You failed to survive the Fractured Archive.").matches());
		assertTrue(Encounters.RAID_OVER_LINE.matcher("You were unable to rejoin your party.").matches());
		// (a death, the team going on, a room complete: not the end)
		assertFalse(Encounters.RAID_OVER_LINE.matcher("You have died. Total deaths: 2.").matches());
		assertFalse(Encounters.RAID_OVER_LINE.matcher("You will respawn when your party completes or fails the challenge.").matches());
		assertFalse(Encounters.RAID_OVER_LINE.matcher("a player has died. Total deaths: 4.").matches());
		assertFalse(Encounters.RAID_OVER_LINE.matcher("Challenge complete: Ezekiel. Duration: 3:19.20. Total: 6:49.20. Personal best: 3:04.20").matches());
	}

	// ---- helpers

	static EncounterScene scene()
	{
		Client c = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class}, (self, m, args) ->
		{
			throw new AssertionError("Client." + m.getName() + " wasn't expected");
		});
		return new EncounterScene(c, (id, qty) -> null, false);
	}

	static EncounterScene.Frame frame(int tick, int map)
	{
		EncounterScene.Frame f = new EncounterScene.Frame();
		f.tick = tick;
		f.cycle = tick * 30;
		f.epoch = map;
		return f;
	}

	static EncounterScene.Still still(int map, int tick)
	{
		return new EncounterScene.Still(new ArrayList<>(Collections.singletonList(world(EncounterMomentTest.grid(30, 0x5e40)))), map, tick, 0, 0);
	}

	static EncounterMoment.Mesh world(EncounterMoment.Mesh m)
	{
		m.cls = 0;
		m.terrain = true;
		m.world = true;
		return m;
	}

	static Map<String, Object> base()
	{
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("fight", null);
		b.put("foes", "Ezekiel, Moia, Undead binder");
		b.put("label", "The Fractured Archive");
		b.put("at", null);
		return b;
	}

	@SuppressWarnings("unchecked")
	static void frames(EncounterScene s, List<EncounterScene.Frame> fs) throws Exception
	{
		ArrayDeque<EncounterScene.Frame> q = (ArrayDeque<EncounterScene.Frame>) get(s, "frames");
		q.clear();
		q.addAll(fs);
		List<int[]> clock = (List<int[]>) get(s, "clock");
		clock.clear();
		for (EncounterScene.Frame f : fs)
		{
			clock.add(new int[]{f.tick, f.cycle});
		}
	}

	static Object get(Object o, String name) throws Exception
	{
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static void set(Object o, String name, Object v) throws Exception
	{
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, v);
	}
}
