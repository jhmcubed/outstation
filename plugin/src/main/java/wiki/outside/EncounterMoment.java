package wiki.outside;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * An encounter's scene moment (11/21/2026; ghillie's claude/outstation.md, "Encounters, round two"): the scene the
 * player's own game drew round the kill, as coloured dots, written in the moment file exactly as ghillie's
 * replay/3d/MOMENT-FORMAT.md says ("OMNT", versions 1 and 2), the file the profile's tiles and the fight page's stage
 * draw. Its browser twin is replay/3d/moment.js (encode, decode, capture, captureStretch, fit): this follows it in our own
 * Java, part for part, so a file from either reads the same.
 *
 * <p>Two halves, both free of RuneLite so the tests run them alone:
 * <ul>
 * <li><b>The file</b>: {@link #raw} / {@link #encode} (the bytes, then gzip), {@link #decode} (either version, gzip or
 * not), and {@link #fit} (the frames thinned and the moving dots halved, as moment.js's fit, until the file is at most
 * {@link #MOST}). Checked byte for byte against moment.js's own encode of the same moment (EncounterMomentTest's
 * golden vectors, made under node 11/21/2026), and the other way: a file this writes decoded by moment.js under node
 * and its decode(encode(x)) the same (claude/outstation.md, "Encounters, round two": how it was checked).</li>
 * <li><b>The sampling</b> ({@link Builder}): the still scene's triangles (the ground's and the scenery's that stand
 * still) sampled into dots in proportion to their area, people five times as dense and effects and items three, full
 * within {@link #CORE} tiles of the camera's followed point and thinning smoothly to none at {@link #REACH}; a dot's
 * colour its face's lit colour there (the corners' colours through the client's palette, blended), or its texel times
 * its light, a see-through texel no dot; the ground under floor scenery left out, as the game's drawing hides it. Then
 * each moving model a track: its dots placed once on its faces where it first shows, followed frame by frame, each
 * shown while its face is solid enough and it's within reach. The same steps, numbers and order of chances as
 * moment.js's capture and captureStretch.</li>
 * </ul>
 * The positions are the moment's: whole units (128 a tile) from the followed point on the ground under it, x east, y up,
 * z south.
 */
final class EncounterMoment
{
	static final int MAGIC = 0x544e4d4f;   // "OMNT", little-endian
	static final int FLAG_MOTION = 1;
	/** (round three, 11/21/2026) a picture block follows: the loot's window as the player's client drew it, a PNG */
	static final int FLAG_PICTURE = 2;
	/** (round four, 11/21/2026) a second picture block follows: the pet's chat line as the player's client drew it, a PNG
	 *  (after the first picture block when there's one, else right after the motion block) */
	static final int FLAG_CHAT_PICTURE = 4;
	/** dots a moment (the still scene), tiles of full density and where it's thinned to none: moment.js's SET */
	static final int POINTS = 200_000, CORE = 9, REACH = 15;
	/** dots per area, by class: the ground, scenery, players and NPCs, effects and items */
	static final double[] WEIGHT = {0.8, 1, 5, 3};
	/** a moving model's dots at most (a bigger one made sparser, its dots drawn larger) */
	static final int TRACK_MOST = 6000;
	/** a file at most, gzipped (the relay takes 3 MB) */
	static final int MOST = (int) Math.floor(2.9 * 1024 * 1024);
	/** what a track is (the file's kind byte): a player or NPC, a graphic on one, a graphic on a tile, a projectile, an
	 *  item on the ground, scenery that moves, anything else; and the class each one's dots are drawn as */
	static final int KIND_ACTOR = 0, KIND_SPOT = 1, KIND_GFX = 2, KIND_PROJ = 3, KIND_ITEM = 4, KIND_OBJECT = 5, KIND_OTHER = 6;
	static final int[] KIND_CLASS = {2, 3, 3, 3, 3, 1, 3};

	private EncounterMoment()
	{
	}

	// ---- the moment as the file holds it

	/** A moving model's dots over the frames it's in. */
	static final class Track
	{
		int from, len, n, cls, kind, thin;
		/** n RGB triples */
		byte[] col;
		/** len frames of n (x, y, z) */
		short[] pos;
		/** len frames of ceil(n / 8) bytes, or null: every dot shows in every frame */
		byte[] vis;
	}

	/** A moment: its meta, the still dots (positions, and RGB and class), and for version 2 its frames and tracks. */
	static final class Moment
	{
		Map<String, Object> meta;
		int count;
		short[] pos;
		/** 4 a dot: R, G, B, class */
		byte[] col;
		int frames;
		List<Track> tracks;
		/** the loot's window as drawn (PNG bytes), or null (round three; version 2 only) */
		byte[] picture;
		/** the pet's chat line as drawn (PNG bytes), or null (round four; version 2 only) */
		byte[] chat;

		boolean moving()
		{
			return tracks != null && frames > 0;
		}
	}

	// ---- the file (MOMENT-FORMAT.md)

	static int zigzag(int d)
	{
		return ((d << 1) ^ (d >> 31)) & 0xffff;
	}

	static int unzigzag(int z)
	{
		return (z >>> 1) ^ -(z & 1);
	}

	/** m10: a value's low 10 bits spread three apart (the Morton code's) */
	static int morton10(int v)
	{
		v &= 1023;
		v = (v | (v << 16)) & 0x030000ff;
		v = (v | (v << 8)) & 0x0300f00f;
		v = (v | (v << 4)) & 0x030c30c3;
		v = (v | (v << 2)) & 0x09249249;
		return v;
	}

	/** The file's bytes before gzip: version 1 with no frames, 2 with them. */
	static byte[] raw(Moment m)
	{
		int n = m.count;
		boolean mo = m.moving();
		// (the still dots in Morton order of 8-unit cells, ties in the order they were made: each step from the last small)
		long[] keys = new long[n];
		for (int i = 0; i < n; i++)
		{
			int code = morton10((m.pos[i * 3] + 4096) >> 3) | (morton10((m.pos[i * 3 + 1] + 4096) >> 3) << 1)
				| (morton10((m.pos[i * 3 + 2] + 4096) >> 3) << 2);
			keys[i] = ((code & 0xffffffffL) << 20) | i;
		}
		Arrays.sort(keys);
		int version = mo ? 2 : 1;
		Map<String, Object> meta = new LinkedHashMap<>(m.meta);
		meta.put("v", (double) version);
		meta.put("count", (double) n);
		byte[] metaBytes = EncounterStrip.Js.stringify(meta).getBytes(StandardCharsets.UTF_8);
		if (metaBytes.length > 65535)
		{
			throw new IllegalArgumentException("the moment's meta is too long");
		}
		long motionLen = 0;
		if (mo)
		{
			motionLen = 8 + m.tracks.size() * 12L;
			for (Track tr : m.tracks)
			{
				motionLen += tr.n * 3L + (long) tr.len * tr.n * 6 + (tr.vis != null ? (long) tr.len * ((tr.n + 7) >> 3) : 0);
			}
		}
		int head = 12 + metaBytes.length;
		boolean pic = mo && m.picture != null && m.picture.length > 0;
		boolean chat = mo && m.chat != null && m.chat.length > 0;
		long total = head + n * 10L + motionLen + (pic ? 4L + m.picture.length : 0) + (chat ? 4L + m.chat.length : 0);
		if (total > Integer.MAX_VALUE - 16)
		{
			throw new IllegalArgumentException("the moment is too big");
		}
		byte[] out = new byte[(int) total];
		le32(out, 0, MAGIC);
		out[4] = (byte) version;
		out[5] = (byte) ((mo ? FLAG_MOTION : 0) | (pic ? FLAG_PICTURE : 0) | (chat ? FLAG_CHAT_PICTURE : 0));
		le16(out, 6, metaBytes.length);
		le32(out, 8, n);
		System.arraycopy(metaBytes, 0, out, 12, metaBytes.length);
		int[] prev = new int[6];
		for (int j = 0; j < n; j++)
		{
			int i = (int) (keys[j] & 0xfffff);
			for (int a = 0; a < 3; a++)
			{
				int v = m.pos[i * 3 + a], z = zigzag(v - prev[a]);
				prev[a] = v;
				out[head + a * 2 * n + j] = (byte) z;
				out[head + (a * 2 + 1) * n + j] = (byte) (z >> 8);
			}
			for (int c = 0; c < 3; c++)
			{
				int v = m.col[i * 4 + c] & 0xff;
				out[head + (6 + c) * n + j] = (byte) (v - prev[3 + c]);
				prev[3 + c] = v;
			}
			out[head + 9 * n + j] = m.col[i * 4 + 3];
		}
		if (mo)
		{
			int q = head + n * 10;
			le32(out, q, m.tracks.size());
			le16(out, q + 4, m.frames);
			le16(out, q + 6, 0);
			q += 8;
			for (Track tr : m.tracks)
			{
				if (tr.from + tr.len > m.frames || tr.len < 1 || tr.n < 1 || tr.n > 65535)
				{
					throw new IllegalArgumentException("a track past the moment's frames");
				}
				le16(out, q, tr.from);
				le16(out, q + 2, tr.len);
				le32(out, q + 4, tr.n);
				out[q + 8] = (byte) tr.cls;
				out[q + 9] = (byte) tr.kind;
				out[q + 10] = (byte) (tr.vis != null ? 1 : 0);
				out[q + 11] = (byte) Math.min(255, tr.thin);
				q += 12;
			}
			for (Track tr : m.tracks)
			{
				int tn = tr.n, nb = (tn + 7) >> 3;
				for (int c = 0; c < 3; c++)
				{
					int p = 0;
					for (int i = 0; i < tn; i++)
					{
						int v = tr.col[i * 3 + c] & 0xff;
						out[q + i] = (byte) (v - p);
						p = v;
					}
					q += tn;
				}
				for (int j = 0; j < tr.len; j++)
				{
					int base = j * tn * 3, was = (j - 1) * tn * 3;
					for (int a = 0; a < 3; a++)
					{
						int p = 0;
						for (int i = 0; i < tn; i++)
						{
							int v = tr.pos[base + i * 3 + a];
							int z = zigzag(v - (j > 0 ? tr.pos[was + i * 3 + a] : p));
							p = v;
							out[q + a * 2 * tn + i] = (byte) z;
							out[q + (a * 2 + 1) * tn + i] = (byte) (z >> 8);
						}
					}
					q += tn * 6;
					if (tr.vis != null)
					{
						System.arraycopy(tr.vis, j * nb, out, q, nb);
						q += nb;
					}
				}
			}
			if (pic)
			{
				le32(out, q, m.picture.length);
				System.arraycopy(m.picture, 0, out, q + 4, m.picture.length);
				q += 4 + m.picture.length;
			}
			if (chat)
			{
				le32(out, q, m.chat.length);
				System.arraycopy(m.chat, 0, out, q + 4, m.chat.length);
			}
		}
		return out;
	}

	/** The file: gzip of {@link #raw}. */
	static byte[] encode(Moment m) throws IOException
	{
		return gzip(raw(m));
	}

	static byte[] gzip(byte[] bytes) throws IOException
	{
		ByteArrayOutputStream b = new ByteArrayOutputStream(bytes.length / 3 + 64);
		try (GZIPOutputStream z = new GZIPOutputStream(b, 1 << 16))
		{
			z.write(bytes);
		}
		return b.toByteArray();
	}

	/** Either version, gzipped or not (moment.js's decode, every frame kept). */
	@SuppressWarnings("unchecked")
	static Moment decode(byte[] bytes) throws IOException
	{
		if (bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b)
		{
			try (GZIPInputStream z = new GZIPInputStream(new ByteArrayInputStream(bytes)))
			{
				bytes = z.readAllBytes();
			}
		}
		if (bytes.length < 12 || rd32(bytes, 0) != MAGIC)
		{
			throw new IOException("not a moment");
		}
		if (bytes[4] < 1 || bytes[4] > 2)
		{
			throw new IOException("a moment from a newer page");
		}
		int ml = rd16(bytes, 6), n = rd32(bytes, 8), head = 12 + ml;
		if (n < 0 || bytes.length < head + (long) n * 10)
		{
			throw new IOException("this moment is cut short");
		}
		Moment m = new Moment();
		Object meta = EncounterStrip.Js.parse(new String(bytes, 12, ml, StandardCharsets.UTF_8));
		m.meta = meta instanceof Map ? (Map<String, Object>) meta : new LinkedHashMap<>();
		m.count = n;
		m.pos = new short[n * 3];
		m.col = new byte[n * 4];
		int[] prev = new int[6];
		for (int j = 0; j < n; j++)
		{
			for (int a = 0; a < 3; a++)
			{
				prev[a] = (short) (prev[a] + unzigzag((bytes[head + a * 2 * n + j] & 0xff) | (bytes[head + (a * 2 + 1) * n + j] & 0xff) << 8));
				m.pos[j * 3 + a] = (short) prev[a];
			}
			for (int c = 0; c < 3; c++)
			{
				prev[3 + c] = (prev[3 + c] + bytes[head + (6 + c) * n + j]) & 255;
				m.col[j * 4 + c] = (byte) prev[3 + c];
			}
			m.col[j * 4 + 3] = bytes[head + 9 * n + j];
		}
		if (bytes[4] >= 2 && (bytes[5] & FLAG_MOTION) != 0)
		{
			int q = head + n * 10;
			if (bytes.length < q + 8)
			{
				throw new IOException("this moment is cut short");
			}
			int t = rd32(bytes, q);
			m.frames = rd16(bytes, q + 4);
			q += 8;
			m.tracks = new ArrayList<>();
			for (int i = 0; i < t; i++, q += 12)
			{
				Track tr = new Track();
				tr.from = rd16(bytes, q);
				tr.len = rd16(bytes, q + 2);
				tr.n = rd32(bytes, q + 4);
				tr.cls = bytes[q + 8] & 0xff;
				tr.kind = bytes[q + 9] & 0xff;
				tr.vis = (bytes[q + 10] & 1) != 0 ? new byte[0] : null;
				tr.thin = bytes[q + 11] & 0xff;
				m.tracks.add(tr);
			}
			for (Track tr : m.tracks)
			{
				int tn = tr.n, nb = (tn + 7) >> 3;
				long size = tn * 3L + (long) tr.len * (tn * 6L + (tr.vis != null ? nb : 0));
				if (bytes.length < q + size)
				{
					throw new IOException("this moment is cut short");
				}
				tr.col = new byte[tn * 3];
				for (int c = 0; c < 3; c++)
				{
					int p = 0;
					for (int i = 0; i < tn; i++)
					{
						p = (p + bytes[q + i]) & 255;
						tr.col[i * 3 + c] = (byte) p;
					}
					q += tn;
				}
				tr.pos = new short[tr.len * tn * 3];
				byte[] vis = tr.vis != null ? new byte[tr.len * nb] : null;
				for (int j = 0; j < tr.len; j++)
				{
					int base = j * tn * 3, was = (j - 1) * tn * 3;
					for (int a = 0; a < 3; a++)
					{
						int p = 0;
						for (int i = 0; i < tn; i++)
						{
							int d = unzigzag((bytes[q + a * 2 * tn + i] & 0xff) | (bytes[q + (a * 2 + 1) * tn + i] & 0xff) << 8);
							int v = (short) ((j > 0 ? tr.pos[was + i * 3 + a] : p) + d);
							tr.pos[base + i * 3 + a] = (short) v;
							p = v;
						}
					}
					q += tn * 6;
					if (vis != null)
					{
						System.arraycopy(bytes, q, vis, j * nb, nb);
						q += nb;
					}
				}
				tr.vis = vis;
			}
			// (the picture block after the motion block: the loot's window as drawn; round three)
			if ((bytes[5] & FLAG_PICTURE) != 0 && bytes.length >= q + 4)
			{
				int len = rd32(bytes, q);
				if (len > 0 && bytes.length >= q + 4L + len)
				{
					m.picture = Arrays.copyOfRange(bytes, q + 4, q + 4 + len);
				}
				q += 4 + Math.max(0, len);
			}
			// (the second picture block: the pet's chat line as drawn; round four)
			if ((bytes[5] & FLAG_CHAT_PICTURE) != 0 && bytes.length >= q + 4)
			{
				int len = rd32(bytes, q);
				if (len > 0 && bytes.length >= q + 4L + len)
				{
					m.chat = Arrays.copyOfRange(bytes, q + 4, q + 4 + len);
				}
			}
		}
		return m;
	}

	// ---- made smaller (moment.js's thin, sparse and fit)

	/** Every other frame dropped (the frames twice as far apart). */
	@SuppressWarnings("unchecked")
	static Moment thin(Moment m)
	{
		if (!m.moving())
		{
			return m;
		}
		int f2 = (m.frames + 1) / 2;
		List<Track> tracks = new ArrayList<>();
		for (Track tr : m.tracks)
		{
			List<Integer> keep = new ArrayList<>();
			for (int j = 0; j < tr.len; j++)
			{
				if ((tr.from + j) % 2 == 0)
				{
					keep.add(j);
				}
			}
			if (keep.isEmpty())
			{
				continue;
			}
			int n = tr.n, nb = (n + 7) >> 3;
			Track t = copyHead(tr);
			t.from = (tr.from + keep.get(0)) / 2;
			t.len = keep.size();
			t.col = tr.col;
			t.pos = new short[keep.size() * n * 3];
			t.vis = tr.vis != null ? new byte[keep.size() * nb] : null;
			for (int q = 0; q < keep.size(); q++)
			{
				int j = keep.get(q);
				System.arraycopy(tr.pos, j * n * 3, t.pos, q * n * 3, n * 3);
				if (t.vis != null)
				{
					System.arraycopy(tr.vis, j * nb, t.vis, q * nb, nb);
				}
			}
			tracks.add(t);
		}
		Map<String, Object> meta = (Map<String, Object>) EncounterStrip.Js.parse(EncounterStrip.Js.stringify(m.meta));
		Map<String, Object> frames = (Map<String, Object>) meta.get("frames");
		frames.put("count", (double) f2);
		frames.put("cycles", num(frames.get("cycles")) * 2);
		meta.put("frame", (double) Math.floor(num(meta.get("frame")) / 2));
		if (meta.get("kill") instanceof Map)
		{
			Map<String, Object> k = (Map<String, Object>) meta.get("kill");
			k.put("frame", (double) Math.floor(num(k.get("frame")) / 2));
		}
		if (meta.get("fall") instanceof Map)
		{
			Map<String, Object> k = (Map<String, Object>) meta.get("fall");
			k.put("frame", (double) Math.floor(num(k.get("frame")) / 2));
		}
		if (meta.get("drop") instanceof Map)
		{
			Map<String, Object> d = (Map<String, Object>) meta.get("drop");
			d.put("frame", Math.ceil(num(d.get("frame")) / 2));
		}
		// (round three: the chest's opening and its window, as the drop's)
		if (meta.get("chest") instanceof Map)
		{
			Map<String, Object> d = (Map<String, Object>) meta.get("chest");
			d.put("frame", Math.ceil(num(d.get("frame")) / 2));
		}
		if (meta.get("window") instanceof Map)
		{
			Map<String, Object> d = (Map<String, Object>) meta.get("window");
			d.put("from", Math.ceil(num(d.get("from")) / 2));
		}
		// (round four: the pet's message and its arrival, and its chat line's window, as the drop's)
		if (meta.get("pet") instanceof Map)
		{
			Map<String, Object> d = (Map<String, Object>) meta.get("pet");
			d.put("frame", Math.ceil(num(d.get("frame")) / 2));
			if (d.get("seenFrame") instanceof Number)
			{
				d.put("seenFrame", Math.ceil(num(d.get("seenFrame")) / 2));
			}
			if (d.get("window") instanceof Map)
			{
				Map<String, Object> w = (Map<String, Object>) d.get("window");
				w.put("from", Math.ceil(num(w.get("from")) / 2));
			}
		}
		for (String list : new String[]{"labels", "beams"})
		{
			if (meta.get(list) instanceof List)
			{
				for (Object o : (List<Object>) meta.get(list))
				{
					Map<String, Object> l = (Map<String, Object>) o;
					double from = Math.ceil(num(l.get("from")) / 2);
					l.put("from", from);
					l.put("to", Math.max(from + 1, Math.ceil(num(l.get("to")) / 2)));
				}
			}
		}
		Moment out = shallow(m);
		out.meta = meta;
		out.frames = f2;
		out.tracks = tracks;
		return out;
	}

	/** Half of every moving model's dots dropped (an even half, by a hash of each dot's number), each drawn larger. */
	static Moment sparse(Moment m)
	{
		if (!m.moving())
		{
			return m;
		}
		List<Track> tracks = new ArrayList<>();
		for (Track tr : m.tracks)
		{
			List<Integer> keep = new ArrayList<>();
			for (int i = 0; i < tr.n; i++)
			{
				if ((((i + 1) * 0x9E3779B1) >>> 0 & 1023) < 512 || tr.n < 4)
				{
					keep.add(i);
				}
			}
			if (keep.size() == tr.n || keep.isEmpty())
			{
				tracks.add(tr);
				continue;
			}
			int n = keep.size(), nb0 = (tr.n + 7) >> 3, nb = (n + 7) >> 3;
			Track t = copyHead(tr);
			t.n = n;
			t.thin = Math.min(255, (1 + tr.thin) * 2 - 1);
			t.col = new byte[n * 3];
			t.pos = new short[tr.len * n * 3];
			t.vis = tr.vis != null ? new byte[tr.len * nb] : null;
			for (int q = 0; q < n; q++)
			{
				System.arraycopy(tr.col, keep.get(q) * 3, t.col, q * 3, 3);
			}
			for (int j = 0; j < tr.len; j++)
			{
				for (int q = 0; q < n; q++)
				{
					int i = keep.get(q);
					System.arraycopy(tr.pos, (j * tr.n + i) * 3, t.pos, (j * n + q) * 3, 3);
					if (t.vis != null && ((tr.vis[j * nb0 + (i >> 3)] >> (i & 7)) & 1) != 0)
					{
						t.vis[j * nb + (q >> 3)] |= (byte) (1 << (q & 7));
					}
				}
			}
			tracks.add(t);
		}
		Moment out = shallow(m);
		out.tracks = tracks;
		return out;
	}

	/** The file made smaller until it's at most {@code most} bytes: the frames twice as far apart, the moving dots
	 *  halved, again each (moment.js's fit, the same steps in the same order). Returns the bytes; {@code into[0]} the
	 *  moment they hold. */
	static byte[] fit(Moment m, int most, Moment[] into) throws IOException
	{
		byte[] bytes = encode(m);
		for (int step = 0; step < 5 && bytes.length > most && m.moving(); step++)
		{
			m = step == 0 || step == 3 ? thin(m) : sparse(m);
			bytes = encode(m);
		}
		if (into != null)
		{
			into[0] = m;
		}
		return bytes;
	}

	private static Track copyHead(Track tr)
	{
		Track t = new Track();
		t.from = tr.from;
		t.len = tr.len;
		t.n = tr.n;
		t.cls = tr.cls;
		t.kind = tr.kind;
		t.thin = tr.thin;
		return t;
	}

	private static Moment shallow(Moment m)
	{
		Moment o = new Moment();
		o.meta = m.meta;
		o.count = m.count;
		o.pos = m.pos;
		o.col = m.col;
		o.frames = m.frames;
		o.tracks = m.tracks;
		o.picture = m.picture;
		o.chat = m.chat;
		return o;
	}

	static double num(Object o)
	{
		return o instanceof Number ? ((Number) o).doubleValue() : 0;
	}

	private static void le16(byte[] b, int o, int v)
	{
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
	}

	private static void le32(byte[] b, int o, int v)
	{
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	private static int rd16(byte[] b, int o)
	{
		return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
	}

	private static int rd32(byte[] b, int o)
	{
		return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
	}

	// ---- colours: the client's palette (as replay/3d/shade.js builds it, rs-map-viewer's buildPalette), at the
	// replay's brightness, 0.8

	static final double BRIGHTNESS = 0.8;
	private static int[] palette;

	/** 0xRRGGBB for one of the game's 16-bit HSL colours. */
	static int rgb(int hsl)
	{
		int[] p = palette;
		if (p == null)
		{
			p = palette = buildPalette(BRIGHTNESS);
		}
		return p[hsl & 0xffff];
	}

	private static int[] buildPalette(double brightness)
	{
		int[] pal = new int[65536];
		int k = 0;
		for (int hs = 0; hs < 512; hs++)
		{
			double hue = (hs >> 3) / 64.0 + 0.0078125, sat = (hs & 7) / 8.0 + 0.0625;
			for (int l = 0; l < 128; l++)
			{
				double lum = l / 128.0, r = lum, g = lum, b = lum;
				if (sat != 0)
				{
					double q = lum < 0.5 ? lum * (1 + sat) : lum + sat - lum * sat, p = 2 * lum - q;
					r = hue2(p, q, hue + 1 / 3.0);
					g = hue2(p, q, hue);
					b = hue2(p, q, hue - 1 / 3.0);
				}
				int c = ((int) (r * 256) << 16) + ((int) (g * 256) << 8) + (int) (b * 256);
				pal[k++] = brighten(c, brightness);
			}
		}
		return pal;
	}

	private static double hue2(double p, double q, double t)
	{
		if (t > 1)
		{
			t--;
		}
		if (t < 0)
		{
			t++;
		}
		if (6 * t < 1)
		{
			return p + (q - p) * 6 * t;
		}
		if (2 * t < 1)
		{
			return q;
		}
		if (3 * t < 2)
		{
			return p + (q - p) * (2 / 3.0 - t) * 6;
		}
		return p;
	}

	private static int brighten(int rgb, double brightness)
	{
		double r = Math.pow((rgb >> 16) / 256.0, brightness), g = Math.pow(((rgb >> 8) & 255) / 256.0, brightness),
			b = Math.pow((rgb & 255) / 256.0, brightness);
		return ((int) (r * 256) << 16) | ((int) (g * 256) << 8) | (int) (b * 256);
	}

	// ---- the sampling (moment.js's capture and captureStretch, in our own Java)

	/**
	 * Triangles as the game's models hand them over: corners already placed in the moment's axes (x east, y up, z south;
	 * game units, not yet measured from the origin), each face's three lit colours as the client keeps them
	 * (Model.getFaceColors1/2/3: a 16-bit HSL each, or for a textured face its light 0..127; the third -1 for one flat
	 * colour, -2 for a face the client never draws), its texture (-1 none) and texture places (u, v at each corner), its
	 * see-through-ness (0 solid .. 255), and the class its dots are drawn as.
	 */
	static final class Mesh
	{
		float[] x, y, z;
		int[] fa, fb, fc;
		int[] c1, c2, c3;
		short[] tex;
		float[] uv;
		byte[] alpha;
		int cls;
		/** the ground itself (tile paints and tile models): its dots go where scenery lies on it */
		boolean terrain;
		/** still scenery (an object of the scene, not a moving model): its flat faces near the ground count as ground */
		boolean world;

		int faces()
		{
			return fa.length;
		}

		/** a face's see-through-ness as moment.js has it: 1 solid, 0 never drawn */
		double alpha(int f)
		{
			if (c3[f] == -2)
			{
				return 0;
			}
			int t = alpha == null ? 0 : alpha[f] & 0xff;
			return t >= 254 ? 0 : 1 - t / 255.0;
		}
	}

	/** A texture's pixels as RuneLite hands them over (TextureProvider: 0xRRGGBB, 0 see-through), square. */
	interface Textures
	{
		int[] pixels(int id);
	}

	/** A face's colour at a place on it (its corners' weights a0, u, v), 0xRRGGBB; -1 where its texel is see-through, -2
	 *  where its texture isn't there. */
	static int colourAt(Mesh m, int f, double a0, double u, double v, Textures tex)
	{
		int c1 = m.c1[f], c2 = m.c3[f] == -1 ? c1 : m.c2[f], c3 = m.c3[f] == -1 ? c1 : m.c3[f];
		int t = m.tex == null ? -1 : m.tex[f];
		if (t < 0)
		{
			int p = rgb(c1), q = rgb(c2), r = rgb(c3);
			int rr = (int) Math.round(a0 * (p >> 16 & 255) + u * (q >> 16 & 255) + v * (r >> 16 & 255));
			int gg = (int) Math.round(a0 * (p >> 8 & 255) + u * (q >> 8 & 255) + v * (r >> 8 & 255));
			int bb = (int) Math.round(a0 * (p & 255) + u * (q & 255) + v * (r & 255));
			return clamp8(rr) << 16 | clamp8(gg) << 8 | clamp8(bb);
		}
		int[] px = tex == null ? null : tex.pixels(t);
		if (px == null || px.length == 0)
		{
			return -2;
		}
		int size = (int) Math.round(Math.sqrt(px.length));
		double tu = 0, tv = 0;
		if (m.uv != null)
		{
			tu = a0 * m.uv[f * 6] + u * m.uv[f * 6 + 2] + v * m.uv[f * 6 + 4];
			tv = a0 * m.uv[f * 6 + 1] + u * m.uv[f * 6 + 3] + v * m.uv[f * 6 + 5];
		}
		int ix = Math.max(0, Math.min(size - 1, (int) Math.floor(tu * size)));
		int iy = ((int) Math.floor((tv - Math.floor(tv)) * size)) & (size - 1);
		int texel = px[iy * size + ix];
		if ((texel & 0xffffff) == 0)
		{
			return -1;
		}
		double light = Math.max(0, Math.min(1, (a0 * (c1 & 127) + u * (c2 & 127) + v * (c3 & 127)) / 127.0));
		return clamp8((int) Math.round((texel >> 16 & 255) * light)) << 16 | clamp8((int) Math.round((texel >> 8 & 255) * light)) << 8
			| clamp8((int) Math.round((texel & 255) * light));
	}

	private static int clamp8(int v)
	{
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	/** moment.js's rng (mulberry32), so the chances fall alike */
	static final class Rng
	{
		private int a;

		Rng(int seed)
		{
			a = seed;
		}

		double next()
		{
			a += 0x6d2b79f5;
			int t = a;
			t = (t ^ (t >>> 15)) * (t | 1);
			t ^= t + (t ^ (t >>> 7)) * (t | 61);
			return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
		}
	}

	private static final double R2A = 0.7548776662466927, R2B = 0.5698402909980532;

	/** A moving model in one frame: where its corners are (the moment's axes, game units) and how see-through each face. */
	static final class Pose
	{
		final float[] x, y, z;
		final byte[] alpha;

		Pose(float[] x, float[] y, float[] z, byte[] alpha)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.alpha = alpha;
		}
	}

	/**
	 * A moment built: the still scene sampled once (from the origin and the still meshes), then each moving model's
	 * frames as tracks. {@link #still} first, then {@link #track} for each moving model's run of frames, then
	 * {@link #moment}.
	 */
	static final class Builder
	{
		final double ox, oy, oz, core, reach;
		final Textures tex;
		final Rng rand;
		double density;
		short[] pos;
		byte[] col;
		int count, texMiss, covered;
		final List<Track> tracks = new ArrayList<>();

		Builder(double ox, double oy, double oz, Textures tex, int seed)
		{
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.core = CORE * 128.0;
			this.reach = REACH * 128.0;
			this.tex = tex;
			this.rand = new Rng(seed);
		}

		/** the edge's thinning at a place: 1 within core, smoothly to 0 at reach */
		double radial(double x, double z)
		{
			double r = Math.hypot(x - ox, z - oz);
			if (r <= core)
			{
				return 1;
			}
			if (r >= reach)
			{
				return 0;
			}
			double k = (reach - r) / (reach - core);
			return k * k * (3 - 2 * k);
		}

		private static double area(Mesh m, int f, float[] x, float[] y, float[] z)
		{
			int a = m.fa[f], b = m.fb[f], c = m.fc[f];
			double ux = x[b] - x[a], uy = y[b] - y[a], uz = z[b] - z[a], vx = x[c] - x[a], vy = y[c] - y[a], vz = z[c] - z[a];
			return 0.5 * Math.sqrt(sq(uy * vz - uz * vy) + sq(uz * vx - ux * vz) + sq(ux * vy - uy * vx));
		}

		private static double sq(double v)
		{
			return v * v;
		}

		/** a still scenery face lying flat near the ground counts as ground (moment.js's flatLow) */
		private boolean flatLow(Mesh m, int f)
		{
			int a = m.fa[f], b = m.fb[f], c = m.fc[f];
			double ux = m.x[b] - m.x[a], uy = m.y[b] - m.y[a], uz = m.z[b] - m.z[a];
			double vx = m.x[c] - m.x[a], vy = m.y[c] - m.y[a], vz = m.z[c] - m.z[a];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			return Math.abs(ny) > 0.92 * Math.sqrt(nx * nx + ny * ny + nz * nz)
				&& Math.abs((m.y[a] + m.y[b] + m.y[c]) / 3.0 - oy) < 256;
		}

		private int classOf(Mesh m, int f)
		{
			return m.cls == 1 && m.world && flatLow(m, f) ? 0 : m.cls;
		}

		/** The still scene: about {@code points} dots over the still meshes, as moment.js's capture. */
		void still(List<Mesh> meshes, int points)
		{
			double[][] faceW = new double[meshes.size()][];
			double est = 0;
			for (int b = 0; b < meshes.size(); b++)
			{
				Mesh m = meshes.get(b);
				double[] fw = faceW[b] = new double[m.faces()];
				for (int f = 0; f < m.faces(); f++)
				{
					double a = m.alpha(f);
					if (!(a >= 0.02))
					{
						continue;
					}
					int cls = classOf(m, f);
					int i0 = m.fa[f], i1 = m.fb[f], i2 = m.fc[f];
					double w0 = radial(m.x[i0], m.z[i0]), w1 = radial(m.x[i1], m.z[i1]), w2 = radial(m.x[i2], m.z[i2]);
					double wm = Math.max(w0, Math.max(w1, w2));
					if (wm <= 0)
					{
						continue;
					}
					double w = area(m, f, m.x, m.y, m.z) * Math.min(1, a) * WEIGHT[cls] * wm;
					fw[f] = w;
					est += w * ((w0 + w1 + w2 + radial((m.x[i0] + m.x[i1] + m.x[i2]) / 3.0, (m.z[i0] + m.z[i1] + m.z[i2]) / 3.0)) / 4) / wm;
				}
			}
			if (est <= 0)
			{
				throw new IllegalStateException("nothing drawn near the middle");
			}
			density = points / est;
			ShortList p = new ShortList();
			ByteList c = new ByteList();
			ByteList src = new ByteList();
			for (int b = 0; b < meshes.size(); b++)
			{
				Mesh m = meshes.get(b);
				double[] fw = faceW[b];
				for (int f = 0; f < m.faces(); f++)
				{
					if (fw[f] == 0)
					{
						continue;
					}
					int cls = classOf(m, f);
					double want = fw[f] * density;
					int n = (int) Math.floor(want);
					if (rand.next() < want - n)
					{
						n++;
					}
					if (n == 0)
					{
						continue;
					}
					int i0 = m.fa[f], i1 = m.fb[f], i2 = m.fc[f];
					double wm = Math.max(radial(m.x[i0], m.z[i0]), Math.max(radial(m.x[i1], m.z[i1]), radial(m.x[i2], m.z[i2])));
					double u0 = rand.next(), v0 = rand.next(), jit = 0.6 / Math.sqrt(2.0 * n);
					for (int k = 0; k < n; k++)
					{
						double u = (u0 + k * R2A + (rand.next() - 0.5) * jit + 1) % 1, v = (v0 + k * R2B + (rand.next() - 0.5) * jit + 1) % 1;
						if (u + v > 1)
						{
							u = 1 - u;
							v = 1 - v;
						}
						double a0 = 1 - u - v;
						double x = a0 * m.x[i0] + u * m.x[i1] + v * m.x[i2], z = a0 * m.z[i0] + u * m.z[i1] + v * m.z[i2];
						if (radial(x, z) < rand.next() * wm)
						{
							continue;
						}
						double y = a0 * m.y[i0] + u * m.y[i1] + v * m.y[i2];
						int rgb = colourAt(m, f, a0, u, v, tex);
						if (rgb == -2)
						{
							texMiss++;
							continue;
						}
						if (rgb == -1)
						{
							continue;
						}
						p.add(unit(x - ox));
						p.add(unit(y - oy));
						p.add(unit(z - oz));
						c.add((byte) (rgb >> 16));
						c.add((byte) (rgb >> 8));
						c.add((byte) rgb);
						c.add((byte) cls);
						src.add((byte) (m.terrain ? 0 : m.world ? 1 : 2));
					}
				}
			}
			// the ground where scenery lies on it (a floor's decoration, a platform): its dots go, as the game's drawing
			// hides it: a ground dot with a scenery dot within 12 units across and up to 48 above it
			int total = src.size;
			Map<Long, IntList> near = new HashMap<>();
			for (int i = 0; i < total; i++)
			{
				if (src.a[i] == 1)
				{
					near.computeIfAbsent(cell(Math.floorDiv(p.a[i * 3], 8), Math.floorDiv(p.a[i * 3 + 2], 8)), k -> new IntList()).add(i);
				}
			}
			boolean[] drop = new boolean[total];
			for (int i = 0; i < total; i++)
			{
				if (src.a[i] != 0)
				{
					continue;
				}
				int x = p.a[i * 3], y = p.a[i * 3 + 1], z = p.a[i * 3 + 2], cx = Math.floorDiv(x, 8), cz = Math.floorDiv(z, 8);
				search:
				for (int a = -1; a <= 1; a++)
				{
					for (int cc = -1; cc <= 1; cc++)
					{
						IntList l = near.get(cell(cx + a, cz + cc));
						if (l == null)
						{
							continue;
						}
						for (int q = 0; q < l.size; q++)
						{
							int j = l.a[q];
							int dy = p.a[j * 3 + 1] - y;
							if (dy >= -1 && dy <= 48 && Math.abs(p.a[j * 3] - x) <= 12 && Math.abs(p.a[j * 3 + 2] - z) <= 12)
							{
								drop[i] = true;
								covered++;
								break search;
							}
						}
					}
				}
			}
			count = total - covered;
			pos = new short[count * 3];
			col = new byte[count * 4];
			for (int i = 0, o = 0; i < total; i++)
			{
				if (drop[i])
				{
					continue;
				}
				System.arraycopy(p.a, i * 3, pos, o * 3, 3);
				System.arraycopy(c.a, i * 4, col, o * 4, 4);
				o++;
			}
		}

		private static long cell(int cx, int cz)
		{
			return ((long) (cx + 4096) << 20) | (cz + 4096);
		}

		static short unit(double v)
		{
			return (short) Math.max(-16383, Math.min(16383, Math.round(v)));
		}

		/**
		 * A moving model's run of frames, from frame {@code from}: its dots placed on its faces at the first frame, then
		 * followed (moment.js's newTrack and its frames). Its faces, colours and textures are {@code m}'s (its corners
		 * there unused); each pose its corners in one frame. A model wholly past the edge in its first frames starts where
		 * it first comes within reach. Returns the track, or null for none (nothing within reach, or no face to show).
		 */
		Track track(Mesh m, int kind, int from, List<Pose> poses)
		{
			int cls = KIND_CLASS[kind];
			// (where it first comes within reach)
			int start = 0;
			for (; start < poses.size(); start++)
			{
				Pose p = poses.get(start);
				boolean in = false;
				for (int v = 0; v < p.x.length && !in; v++)
				{
					in = radial(p.x[v], p.z[v]) > 0;
				}
				if (in)
				{
					break;
				}
			}
			if (start >= poses.size())
			{
				return null;
			}
			Pose first = poses.get(start);
			Mesh at = new Mesh();
			at.fa = m.fa;
			at.fb = m.fb;
			at.fc = m.fc;
			at.c1 = m.c1;
			at.c2 = m.c2;
			at.c3 = m.c3;
			at.tex = m.tex;
			at.uv = m.uv;
			at.alpha = first.alpha != null ? first.alpha : m.alpha;
			double area = 0;
			for (int f = 0; f < m.faces(); f++)
			{
				if (at.alpha(f) >= 0.02)
				{
					area += area(m, f, first.x, first.y, first.z);
				}
			}
			int thinBy = (int) Math.min(255, Math.max(0, Math.ceil(area * WEIGHT[cls] * density / TRACK_MOST) - 1));
			double dens = density / (1 + thinBy);
			IntList fs = new IntList();
			List<double[]> uvs = new ArrayList<>();
			ByteList col = new ByteList();
			for (int f = 0; f < m.faces(); f++)
			{
				if (!(at.alpha(f) >= 0.02))
				{
					continue;
				}
				double want = area(m, f, first.x, first.y, first.z) * WEIGHT[cls] * dens;
				int n = (int) Math.floor(want);
				if (rand.next() < want - n)
				{
					n++;
				}
				if (n == 0)
				{
					continue;
				}
				double u0 = rand.next(), v0 = rand.next(), jit = 0.6 / Math.sqrt(2.0 * n);
				for (int k = 0; k < n; k++)
				{
					double u = (u0 + k * R2A + (rand.next() - 0.5) * jit + 1) % 1, v = (v0 + k * R2B + (rand.next() - 0.5) * jit + 1) % 1;
					if (u + v > 1)
					{
						u = 1 - u;
						v = 1 - v;
					}
					int rgb = colourAt(at, f, 1 - u - v, u, v, tex);
					if (rgb < 0)
					{
						continue;
					}
					fs.add(f);
					uvs.add(new double[]{u, v, 0.02 + rand.next() * 0.97, rand.next()});
					col.add((byte) (rgb >> 16));
					col.add((byte) (rgb >> 8));
					col.add((byte) rgb);
				}
			}
			int n = fs.size;
			if (n == 0 || n > 65535)
			{
				return null;
			}
			int len = poses.size() - start, nb = (n + 7) >> 3;
			Track tr = new Track();
			tr.from = from + start;
			tr.len = len;
			tr.n = n;
			tr.cls = cls;
			tr.kind = kind;
			tr.thin = thinBy;
			tr.col = Arrays.copyOf(col.a, n * 3);
			tr.pos = new short[len * n * 3];
			byte[] vis = new byte[len * nb];
			boolean hidden = false;
			for (int j = 0; j < len; j++)
			{
				Pose p = poses.get(start + j);
				at.alpha = p.alpha != null ? p.alpha : m.alpha;
				for (int i = 0; i < n; i++)
				{
					int f = fs.a[i];
					double[] d = uvs.get(i);
					double u = d[0], v = d[1], a0 = 1 - u - v;
					int i0 = m.fa[f], i1 = m.fb[f], i2 = m.fc[f];
					double x = a0 * p.x[i0] + u * p.x[i1] + v * p.x[i2], y = a0 * p.y[i0] + u * p.y[i1] + v * p.y[i2],
						z = a0 * p.z[i0] + u * p.z[i1] + v * p.z[i2];
					int o = (j * n + i) * 3;
					tr.pos[o] = unit(x - ox);
					tr.pos[o + 1] = unit(y - oy);
					tr.pos[o + 2] = unit(z - oz);
					if (at.alpha(f) >= d[2] && radial(x, z) > d[3])
					{
						vis[j * nb + (i >> 3)] |= (byte) (1 << (i & 7));
					}
					else
					{
						hidden = true;
					}
				}
			}
			tr.vis = hidden ? vis : null;
			tracks.add(tr);
			return tr;
		}

		/** The moment: the still dots, and the tracks over {@code frames} frames (0: a one-tick moment, version 1). */
		Moment moment(Map<String, Object> meta, int frames)
		{
			Moment m = new Moment();
			m.meta = meta;
			m.count = count;
			m.pos = pos;
			m.col = col;
			if (frames > 0)
			{
				m.frames = frames;
				m.tracks = new ArrayList<>(tracks);
			}
			return m;
		}

		/** the dots' spacing by class, for the renderer's dot size (moment.js's meta.spacing) */
		List<Object> spacing()
		{
			List<Object> s = new ArrayList<>();
			for (double w : WEIGHT)
			{
				s.add(Math.round(10 / Math.sqrt(density * w)) / 10.0);
			}
			return s;
		}
	}

	// ---- texture places (the client's planar mapping: a face's texture laid along its texture triangle P, M, N, as
	// RuneLite's GPU plugin and replay/3d/shade.js work it out; written here in our own words as a projection)

	/**
	 * Each face's (u, v) at its three corners, 6 floats a face, from its texture triangle (P, M, N: texIndices at the
	 * face's textureFaces entry, else the face's own corners): u along P to M and v along P to N, each measured across
	 * the plane the triangle stands in. Null where no face is textured.
	 */
	static float[] uvs(float[] x, float[] y, float[] z, int[] fa, int[] fb, int[] fc, short[] tex, byte[] texFaces,
		int[] tp, int[] tm, int[] tn)
	{
		if (tex == null)
		{
			return null;
		}
		float[] out = new float[fa.length * 6];
		for (int f = 0; f < fa.length; f++)
		{
			if (tex[f] < 0)
			{
				continue;
			}
			int p = fa[f], m = fb[f], n = fc[f];
			int t = texFaces == null ? -1 : texFaces[f] & 0xff;
			if (texFaces != null && texFaces[f] != -1 && tp != null && t < tp.length)
			{
				p = tp[t];
				m = tm[t];
				n = tn[t];
			}
			if (p >= x.length || m >= x.length || n >= x.length)
			{
				continue;
			}
			double ax = x[m] - x[p], ay = y[m] - y[p], az = z[m] - z[p];
			double bx = x[n] - x[p], by = y[n] - y[p], bz = z[n] - z[p];
			// (the plane's normal, then the two directions that measure u and v: each across the other edge)
			double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			double ux = by * nz - bz * ny, uy = bz * nx - bx * nz, uz = bx * ny - by * nx;
			double vx = ny * az - nz * ay, vy = nz * ax - nx * az, vz = nx * ay - ny * ax;
			double du = ux * ax + uy * ay + uz * az, dv = vx * bx + vy * by + vz * bz;
			if (du == 0 || dv == 0)
			{
				continue;
			}
			int[] corner = {fa[f], fb[f], fc[f]};
			for (int k = 0; k < 3; k++)
			{
				int c = corner[k];
				double px = x[c] - x[p], py = y[c] - y[p], pz = z[c] - z[p];
				out[f * 6 + k * 2] = (float) ((ux * px + uy * py + uz * pz) / du);
				out[f * 6 + k * 2 + 1] = (float) ((vx * px + vy * py + vz * pz) / dv);
			}
		}
		return out;
	}

	// ---- small growable lists

	static final class ShortList
	{
		short[] a = new short[1024];
		int size;

		void add(short v)
		{
			if (size == a.length)
			{
				a = Arrays.copyOf(a, a.length * 2);
			}
			a[size++] = v;
		}
	}

	static final class ByteList
	{
		byte[] a = new byte[1024];
		int size;

		void add(byte v)
		{
			if (size == a.length)
			{
				a = Arrays.copyOf(a, a.length * 2);
			}
			a[size++] = v;
		}
	}

	static final class IntList
	{
		int[] a = new int[16];
		int size;

		void add(int v)
		{
			if (size == a.length)
			{
				a = Arrays.copyOf(a, a.length * 2);
			}
			a[size++] = v;
		}
	}
}
