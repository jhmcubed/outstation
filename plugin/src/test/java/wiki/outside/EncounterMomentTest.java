package wiki.outside;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The moment's writer against the browser's (11/21/2026): the same two moments encoded by ghillie's replay/3d/moment.js
 * under node (node 22, its encode, the gzip taken off: night/r2-plugin/golden.js) and by EncounterMoment, byte for byte;
 * then decode(encode(x)) = x, thin and sparse as moment.js has them, and the sampling's shape on a made-up scene.
 */
public class EncounterMomentTest
{
	/** moment.js's encode of the one-tick moment below (version 1), unzipped */
	static final String GOLDEN_1 = ""
		+ "4f4d4e5401002501050000007b226b696e64223a226f7574736964652d6d6f6d656e74222c2276223a312c226669676874223a6e756c6c"
		+ "2c227469636b223a31322c226379636c65223a333435362c22666f6573223a224769616e74204d6f6c65222c226c6162656c223a227465"
		+ "7374222c226174223a313730303030303030303030302c226d616465223a313730303030303030313030302c22706c616e65223a302c22"
		+ "74696c65223a5b35302c35315d2c2263616d657261223a7b22796177223a312e352c227069746368223a302e352c227a6f6f6d223a3531"
		+ "322c2248223a3530337d2c2267726964223a5b31322c3130305d2c2273706163696e67223a5b332e312c322e382c312e322c312e365d2c"
		+ "22636f7265223a392c227265616368223a31352c22636f756e74223a357dc85790ff1200027e7c00095a4f001200007d7d005849030912"
		+ "0202000000c8370407ff649c0410f532cf0419eb0102030000";
	/** and of the looping one (version 2: two tracks, one with shown planes) */
	static final String GOLDEN_2 = ""
		+ "4f4d4e5402014602050000007b226b696e64223a226f7574736964652d6d6f6d656e74222c2276223a322c226669676874223a6e756c6c"
		+ "2c227469636b223a31322c226379636c65223a333435362c22666f6573223a224769616e74204d6f6c65222c226c6162656c223a227465"
		+ "7374222c226174223a313730303030303030303030302c226d616465223a313730303030303030313030302c22706c616e65223a302c22"
		+ "74696c65223a5b35302c35315d2c2263616d657261223a7b22796177223a312e352c227069746368223a302e352c227a6f6f6d223a3531"
		+ "322c2248223a3530337d2c2267726964223a5b31322c3130305d2c2273706163696e67223a5b332e312c322e382c312e322c312e365d2c"
		+ "22636f7265223a392c227265616368223a31352c22636f756e74223a352c226672616d65223a312c226672616d6573223a7b22636f756e"
		+ "74223a332c226379636c6573223a322c227469636b30223a31302e352c226379636c6530223a333430307d2c2273747265746368223a7b"
		+ "2266726f6d223a31302c22746f223a31337d2c22776879223a22746865206b696c6c222c226b696c6c223a7b227469636b223a31322c22"
		+ "6672616d65223a312c226e7063223a224769616e74204d6f6c65227d2c2264726f70223a6e756c6c2c226c6162656c73223a5b7b227465"
		+ "7874223a22426f6e6573222c22636f6c6f72223a2223666666666666222c226174223a5b312c322c335d2c2266726f6d223a302c22746f"
		+ "223a332c226964223a3532362c22717479223a312c2276616c7565223a307d5d2c226265616d73223a5b5d7dc85790ff1200027e7c0009"
		+ "5a4f001200007d7d0058490309120202000000c8370407ff649c0410f532cf0419eb010203000002000000030000000000030003000000"
		+ "020000000100020009000000030301020103f60203f60303f600141d00000000141d00000000141d000000020200000000000001000000"
		+ "0000000000000202000000000000010000000000000000000015151515151515150715151515151515150e1515151515151515f3dedede"
		+ "de09dedede010000000003000000a9dededede09dedede0100000000030000005fdedede09dededede010000000300000000ff01e60101"
		+ "0101010101010300000000000000000101010101010101010000000000000000000101010101010101010000000000000000000f00";

	static double d(double v)
	{
		return v;
	}

	static List<Object> list(Object... o)
	{
		return new ArrayList<>(Arrays.asList(o));
	}

	static Map<String, Object> meta1()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", "outside-moment");
		m.put("v", 1.0);
		m.put("fight", null);
		m.put("tick", 12.0);
		m.put("cycle", 3456.0);
		m.put("foes", "Giant Mole");
		m.put("label", "test");
		m.put("at", 1700000000000.0);
		m.put("made", 1700000001000.0);
		m.put("plane", 0.0);
		m.put("tile", list(50.0, 51.0));
		Map<String, Object> cam = new LinkedHashMap<>();
		cam.put("yaw", 1.5);
		cam.put("pitch", 0.5);
		cam.put("zoom", 512.0);
		cam.put("H", 503.0);
		m.put("camera", cam);
		m.put("grid", list(12.0, 100.0));
		m.put("spacing", list(3.1, 2.8, 1.2, 1.6));
		m.put("core", 9.0);
		m.put("reach", 15.0);
		m.put("count", 5.0);
		return m;
	}

	static EncounterMoment.Moment one()
	{
		EncounterMoment.Moment m = new EncounterMoment.Moment();
		m.meta = meta1();
		m.count = 5;
		m.pos = new short[]{0, 0, 0, 100, -5, 300, -200, 40, 7, 16000, -16000, 5, 9, 9, 9};
		int[] c = {10, 20, 30, 0, 200, 100, 50, 1, 255, 0, 1, 2, 3, 4, 5, 3, 9, 9, 9, 0};
		m.col = new byte[c.length];
		for (int i = 0; i < c.length; i++)
		{
			m.col[i] = (byte) c[i];
		}
		return m;
	}

	static EncounterMoment.Moment two()
	{
		EncounterMoment.Moment m = one();
		Map<String, Object> meta = m.meta;
		meta.put("v", 2.0);
		meta.put("frame", 1.0);
		Map<String, Object> frames = new LinkedHashMap<>();
		frames.put("count", 3.0);
		frames.put("cycles", 2.0);
		frames.put("tick0", 10.5);
		frames.put("cycle0", 3400.0);
		meta.put("frames", frames);
		Map<String, Object> st = new LinkedHashMap<>();
		st.put("from", 10.0);
		st.put("to", 13.0);
		meta.put("stretch", st);
		meta.put("why", "the kill");
		Map<String, Object> kill = new LinkedHashMap<>();
		kill.put("tick", 12.0);
		kill.put("frame", 1.0);
		kill.put("npc", "Giant Mole");
		meta.put("kill", kill);
		meta.put("drop", null);
		Map<String, Object> label = new LinkedHashMap<>();
		label.put("text", "Bones");
		label.put("color", "#ffffff");
		label.put("at", list(1.0, 2.0, 3.0));
		label.put("from", 0.0);
		label.put("to", 3.0);
		label.put("id", 526.0);
		label.put("qty", 1.0);
		label.put("value", 0.0);
		meta.put("labels", list(label));
		meta.put("beams", list());
		m.frames = 3;
		m.tracks = new ArrayList<>();
		EncounterMoment.Track a = new EncounterMoment.Track();
		a.from = 0;
		a.len = 3;
		a.n = 3;
		a.cls = 2;
		a.kind = 0;
		a.col = new byte[]{1, 2, 3, 4, 5, 6, (byte) 250, (byte) 251, (byte) 252};
		a.pos = new short[]{0, 0, 0, 10, 10, 10, -5, -5, -5, 1, 0, 0, 11, 10, 10, -5, -6, -5, 2, 0, 0, 12, 10, 10, -5, -7, -5};
		m.tracks.add(a);
		EncounterMoment.Track b = new EncounterMoment.Track();
		b.from = 1;
		b.len = 2;
		b.n = 9;
		b.cls = 3;
		b.kind = 3;
		b.thin = 2;
		b.col = new byte[27];
		for (int i = 0; i < 27; i++)
		{
			b.col[i] = (byte) (i * 7);
		}
		b.pos = new short[54];
		for (int i = 0; i < 54; i++)
		{
			b.pos[i] = (short) ((i * 37) % 500 - 250);
		}
		b.vis = new byte[]{(byte) 0xff, 0x01, 0x0f, 0x00};
		m.tracks.add(b);
		return m;
	}

	static byte[] hex(String s)
	{
		byte[] b = new byte[s.length() / 2];
		for (int i = 0; i < b.length; i++)
		{
			b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return b;
	}

	@Test
	public void sameBytesAsMomentJs() throws Exception
	{
		assertArrayEquals(hex(GOLDEN_1), EncounterMoment.raw(one()));
		assertArrayEquals(hex(GOLDEN_2), EncounterMoment.raw(two()));
		// (and gzipped, the same bytes inside)
		byte[] gz = EncounterMoment.encode(two());
		try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(gz)))
		{
			assertArrayEquals(hex(GOLDEN_2), z.readAllBytes());
		}
	}

	@Test
	public void decodesWhatItWrites() throws Exception
	{
		EncounterMoment.Moment m = two();
		EncounterMoment.Moment back = EncounterMoment.decode(EncounterMoment.encode(m));
		assertEquals(5, back.count);
		assertEquals(3, back.frames);
		assertEquals(2, back.tracks.size());
		// (the still dots as a set: the file sorts them)
		assertEquals(set(m), set(back));
		for (int i = 0; i < 2; i++)
		{
			EncounterMoment.Track x = m.tracks.get(i), y = back.tracks.get(i);
			assertEquals(x.from, y.from);
			assertEquals(x.len, y.len);
			assertEquals(x.n, y.n);
			assertEquals(x.cls, y.cls);
			assertEquals(x.kind, y.kind);
			assertEquals(x.thin, y.thin);
			assertArrayEquals(x.col, y.col);
			assertArrayEquals(x.pos, y.pos);
			assertArrayEquals(x.vis, y.vis);
		}
		assertNull(back.tracks.get(0).vis);
		assertEquals("Bones", ((Map<?, ?>) ((List<?>) back.meta.get("labels")).get(0)).get("text"));
		// (a raw file reads too, and version 1)
		assertEquals(5, EncounterMoment.decode(EncounterMoment.raw(one())).count);
	}

	static List<String> set(EncounterMoment.Moment m)
	{
		List<String> s = new ArrayList<>();
		for (int i = 0; i < m.count; i++)
		{
			s.add(m.pos[i * 3] + "," + m.pos[i * 3 + 1] + "," + m.pos[i * 3 + 2] + "," + m.col[i * 4] + "," + m.col[i * 4 + 1] + ","
				+ m.col[i * 4 + 2] + "," + m.col[i * 4 + 3]);
		}
		s.sort(null);
		return s;
	}

	@Test
	public void thinAndSparseAsMomentJs()
	{
		EncounterMoment.Moment t = EncounterMoment.thin(two());
		assertEquals(2, t.frames);
		// (frames 0 and 2 kept of the first; frame 2 only of the second, from 1)
		assertEquals(0, t.tracks.get(0).from);
		assertEquals(2, t.tracks.get(0).len);
		assertEquals(1, t.tracks.get(1).from);
		assertEquals(1, t.tracks.get(1).len);
		assertEquals(4.0, EncounterMoment.num(((Map<?, ?>) t.meta.get("frames")).get("cycles")), 0);
		assertEquals(0.0, EncounterMoment.num(t.meta.get("frame")), 0);
		EncounterMoment.Moment s = EncounterMoment.sparse(two());
		assertTrue(s.tracks.get(1).n < 9);
		assertEquals(5, s.tracks.get(1).thin);
		// (a track of fewer than 4 dots keeps them)
		assertEquals(3, s.tracks.get(0).n);
	}

	@Test
	public void samplesAScene() throws Exception
	{
		// a flat green ground 30 tiles square, a grey wall on it, a red figure walking across it over 10 frames
		EncounterMoment.Mesh ground = grid(30, 0x5a7f);
		ground.terrain = true;
		ground.world = true;
		ground.cls = 0;
		EncounterMoment.Builder b = new EncounterMoment.Builder(0, 0, 0, id -> null, 0x6d6f6d);
		List<EncounterMoment.Mesh> still = new ArrayList<>();
		still.add(ground);
		b.still(still, 20000);
		assertTrue("about the dots asked for: " + b.count, b.count > 17000 && b.count < 23000);
		int far = 0;
		for (int i = 0; i < b.count; i++)
		{
			double r = Math.hypot(b.pos[i * 3], b.pos[i * 3 + 2]);
			assertTrue(r <= 15 * 128 + 1);
			far += r > 12 * 128 ? 1 : 0;
		}
		assertTrue("the edge thins", far > 0 && far < b.count / 8);
		EncounterMoment.Mesh fig = quad(-64, 0, -64, 128, 128, 0x0c7f);
		fig.cls = 2;
		List<EncounterMoment.Pose> poses = new ArrayList<>();
		for (int k = 0; k < 10; k++)
		{
			float[] x = fig.x.clone(), y = fig.y.clone(), z = fig.z.clone();
			for (int v = 0; v < x.length; v++)
			{
				x[v] += k * 20;
				y[v] += 100;
			}
			poses.add(new EncounterMoment.Pose(x, y, z, null));
		}
		EncounterMoment.Track tr = b.track(fig, EncounterMoment.KIND_ACTOR, 2, poses);
		assertEquals(2, tr.from);
		assertEquals(10, tr.len);
		assertEquals(2, tr.cls);
		// (each dot moves 20 units east a frame, the same dot all through)
		assertEquals(tr.pos[0] + 20, tr.pos[tr.n * 3], 1);
		EncounterMoment.Moment m = b.moment(meta1(), 12);
		byte[] file = EncounterMoment.encode(m);
		// (kept for the check under node: moment.js's decode of a file this wrote; claude/outstation.md, round two)
		java.nio.file.Files.createDirectories(java.nio.file.Paths.get("build"));
		java.nio.file.Files.write(java.nio.file.Paths.get("build", "moment-sample.bin"), file);
		EncounterMoment.Moment back = EncounterMoment.decode(file);
		assertEquals(b.count, back.count);
		assertEquals(1, back.tracks.size());
		assertArrayEquals(tr.pos, back.tracks.get(0).pos);
		// (reddish dots on the figure, greenish on the ground)
		int r = tr.col[0] & 0xff, g = tr.col[1] & 0xff;
		assertTrue("the figure's colour " + r + "," + g, r > g);
	}

	/** a flat ground of n by n tiles round the middle, two faces a tile (the game's tiles are this small: a face is
	 *  weighed by its corners' place, as moment.js weighs it) */
	static EncounterMoment.Mesh grid(int n, int hsl)
	{
		EncounterMoment.Mesh m = new EncounterMoment.Mesh();
		int v = n + 1;
		m.x = new float[v * v];
		m.y = new float[v * v];
		m.z = new float[v * v];
		for (int i = 0; i < v; i++)
		{
			for (int j = 0; j < v; j++)
			{
				m.x[i * v + j] = (j - n / 2f) * 128;
				m.z[i * v + j] = (i - n / 2f) * 128;
			}
		}
		int f = n * n * 2;
		m.fa = new int[f];
		m.fb = new int[f];
		m.fc = new int[f];
		m.c1 = new int[f];
		m.c2 = new int[f];
		m.c3 = new int[f];
		for (int i = 0, k = 0; i < n; i++)
		{
			for (int j = 0; j < n; j++, k += 2)
			{
				int a = i * v + j;
				m.fa[k] = a;
				m.fb[k] = a + 1;
				m.fc[k] = a + v + 1;
				m.fa[k + 1] = a;
				m.fb[k + 1] = a + v + 1;
				m.fc[k + 1] = a + v;
				m.c1[k] = m.c1[k + 1] = m.c2[k] = m.c2[k + 1] = hsl;
				m.c3[k] = m.c3[k + 1] = -1;
			}
		}
		return m;
	}

	/** a flat quad of two faces at height y, its colour one 16-bit HSL */
	static EncounterMoment.Mesh quad(float x0, float y, float z0, float w, float d, int hsl)
	{
		EncounterMoment.Mesh m = new EncounterMoment.Mesh();
		m.x = new float[]{x0, x0 + w, x0 + w, x0};
		m.y = new float[]{y, y, y, y};
		m.z = new float[]{z0, z0, z0 + d, z0 + d};
		m.fa = new int[]{0, 0};
		m.fb = new int[]{1, 2};
		m.fc = new int[]{2, 3};
		m.c1 = new int[]{hsl, hsl};
		m.c2 = new int[]{hsl, hsl};
		m.c3 = new int[]{-1, -1};
		return m;
	}

	/** (11/21/2026, round three) the loot's window as drawn rides after the motion block (flag bit 1), kept through thin
	 *  and sparse, with the chest's and the window's frames halved as the drop's */
	@Test
	public void thePictureBlock() throws Exception
	{
		EncounterMoment.Moment m = two();
		Map<String, Object> chest = new LinkedHashMap<>();
		chest.put("tick", d(12));
		chest.put("frame", d(1));
		chest.put("source", "Lunar Chest");
		chest.put("kind", "reward");
		m.meta.put("chest", chest);
		Map<String, Object> win = new LinkedHashMap<>();
		win.put("kind", "reward");
		win.put("from", d(1));
		m.meta.put("window", win);
		m.picture = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
		byte[] raw = EncounterMoment.raw(m);
		assertEquals(EncounterMoment.FLAG_MOTION | EncounterMoment.FLAG_PICTURE, raw[5]);
		EncounterMoment.Moment back = EncounterMoment.decode(EncounterMoment.encode(m));
		assertArrayEquals(m.picture, back.picture);
		assertEquals(3, back.frames);
		EncounterMoment.Moment t = EncounterMoment.thin(back);
		assertArrayEquals(m.picture, t.picture);
		assertEquals(1.0, EncounterMoment.num(((Map<?, ?>) t.meta.get("chest")).get("frame")), 0);
		assertArrayEquals(m.picture, EncounterMoment.sparse(back).picture);
		// (without one: the flag off, the bytes as before)
		assertArrayEquals(hex(GOLDEN_2), EncounterMoment.raw(two()));
	}
}
