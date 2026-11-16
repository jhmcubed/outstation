package wiki.outside;

// hub:cut model,bank
import com.google.gson.JsonArray;
// hub:end model,bank
// hub:cut model,bank
import com.google.gson.JsonObject;
// hub:end model,bank
import java.util.Locale;
// hub:cut bank
import java.util.Map;
// hub:end bank
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the plugin reads for the player's page beyond what's worn and carried, kept apart from RuneLite so it's tested
 * (docs/protocol.md, "Kept for the page"):
 * <ul>
 * <li>an item's charges, from the game's own chat line;</li>
// hub:cut bank
 * <li>the bank, as the relay keeps it;</li>
// hub:end bank
// hub:cut model
 * <li>the character's model, as the page draws it;</li>
// hub:end model
 * </ul>
 */
public final class Kept
{
	private Kept()
	{
	}

	// ---- charges (Jace: "show charges on the items that have charges like my tonalztics of ralos and archivist's
	// folio, if you can collect that info from the client"): the game says them only in its chat, when an item's
	// checked ("Your tonalztics of ralos has 1,234 charges left.") or as it's used, so they're read from there, as the
	// Hub's charge plugins read them

	/** "1,234 charges", "1 charge", "has 50 charges left", "(57 charges)", "uses remaining: 12" … */
	private static final Pattern CHARGES = Pattern.compile(
		"(?i)(?:\\b([0-9][0-9,]*)\\s+(?:charges?|uses?|scrolls?|doses?)\\b)|(?:(?:charges?|uses?)(?:\\s+(?:left|remaining))?\\s*[:]?\\s+([0-9][0-9,]*)\\b)");
	/** "none", "no charges left", "has run out" */
	private static final Pattern NONE = Pattern.compile("(?i)\\b(no charges|no more charges|has run out of charges|has no charges|is out of charges)\\b");

	/** The charges a game message tells of, or null: none told. 0 for "no charges". */
	public static Integer charges(String message)
	{
		if (message == null)
		{
			return null;
		}
		if (NONE.matcher(message).find())
		{
			return 0;
		}
		Matcher m = CHARGES.matcher(message);
		if (!m.find())
		{
			return null;
		}
		String n = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
		try
		{
			long v = Long.parseLong(n);
			return v > Integer.MAX_VALUE ? null : (int) v;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** An item's name as the game says it in chat: lower case, without its "(charged)", "(e)", "(4)" or the like. */
	public static String spoken(String name)
	{
		return name == null ? "" : name.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim().toLowerCase(Locale.ROOT);
	}

	/** Whether a message is about this item (its spoken name in it), its name at least 5 letters so a short word ("ring") never does. */
	public static boolean about(String message, String name)
	{
		String n = spoken(name);
		return n.length() >= 5 && message != null && message.toLowerCase(Locale.ROOT).contains(n);
	}

	// hub:cut bank
	// ---- the bank (Jace: "an option for people to capture their entire bank, just as the liveloadout twitch
	// extension/plugin offers"): every slot in the bank's own order, a placeholder as [its item, 0], the tabs' counts
	// (the game's BANK_TAB_1 to 9; the main tab is what's left), and each item's name and price

	/** {"items", "tabs", "names"} as the relay keeps it. */
	public static JsonObject bank(int[] ids, int[] quantities, int[] tabs, Map<Integer, Object[]> names)
	{
		JsonObject o = new JsonObject();
		JsonArray items = new JsonArray();
		for (int i = 0; i < ids.length && i < quantities.length; i++)
		{
			if (ids[i] > 0 && quantities[i] >= 0)
			{
				items.add(Kit.pair(ids[i], quantities[i]));
			}
		}
		o.add("items", items);
		JsonArray t = new JsonArray();
		for (int n : tabs)
		{
			t.add(Math.max(0, n));
		}
		o.add("tabs", t);
		JsonObject nm = new JsonObject();
		for (Map.Entry<Integer, Object[]> e : names.entrySet())
		{
			JsonArray np = new JsonArray();
			np.add((String) e.getValue()[0]);
			np.add(Math.max(0, ((Number) e.getValue()[1]).longValue()));
			nm.add(String.valueOf(e.getKey()), np);
		}
		o.add("names", nm);
		return o;
	}
	// hub:end bank

	// hub:cut model
	// ---- the model (Jace: "upload a captured model of their character onto their profile page just as RuneProfile
	// does; but we can make ours look cooler and rendered to our liking/in our own style"): only its shape and its
	// colours, as RuneProfile's exporter reads them (its ModelMeshBuilder, BSD 2-Clause: plugin/README.md, "Credits"),
	// the faces the game never draws left out

	/** A face the game never draws (its third colour), and a face shaded flat (one colour, not one a corner). */
	static final int HIDDEN = -2, FLAT = -1;

	/**
	 * The model as the page draws it: every face drawn but the hidden and the wholly see-through ones.
	 * <ul>
	 * <li>"v": vertices (x, y, z, the game's units, rounded); "f": faces (three vertices' places each)</li>
	 * <li>"c": each face's colour unlit (the game's packed HSL, or -1 for a textured face), as since 11/14/2026</li>
	 * <li>(11/15/2026, Jace: "draw ours as accurately to the game as possible, shading and geometry") "g": each face's
	 * three corners as the game has lit them (its packed HSL each; a flat face's one colour three times; a textured
	 * face's three 7-bit lightnesses, which multiply its texture)</li>
	 * <li>"a": each face's opacity, 0 to 255 (only when any face is see-through); "p": each face's draw priority (only when
	 * the model has them)</li>
	 * <li>"t": each face's texture id, -1 for none, and "uv": its three corners' texture coordinates, u then v, times
	 * 4096 (0s for an untextured face), found as the client finds them from the face's texture triangle (RuneProfile's
	 * ModelMeshBuilder.computeFaceUvs, BSD 2-Clause): only when any face is textured</li>
	 * </ul>
	 * Null arrays are taken as none.
	 */
	public static JsonObject model(float[] x, float[] y, float[] z, int vertices, int[] a, int[] b, int[] c, int faces,
		int[] colors1, int[] colors3, short[] unlit, byte[] transparencies, short[] textures)
	{
		return model(x, y, z, vertices, a, b, c, faces, colors1, null, colors3, unlit, transparencies, textures, null, null, null, null, null);
	}

	public static JsonObject model(float[] x, float[] y, float[] z, int vertices, int[] a, int[] b, int[] c, int faces,
		int[] colors1, int[] colors2, int[] colors3, short[] unlit, byte[] transparencies, short[] textures,
		byte[] priorities, byte[] textureFaces, int[] tex1, int[] tex2, int[] tex3)
	{
		JsonObject o = new JsonObject();
		JsonArray v = new JsonArray();
		for (int i = 0; i < vertices; i++)
		{
			v.add(clamp(Math.round(x[i])));
			v.add(clamp(Math.round(y[i])));
			v.add(clamp(Math.round(z[i])));
		}
		JsonArray f = new JsonArray(), col = new JsonArray(), lit = new JsonArray(), alpha = new JsonArray();
		JsonArray pri = new JsonArray(), tex = new JsonArray(), uv = new JsonArray();
		boolean seeThrough = false, anyTexture = false;
		for (int i = 0; i < faces; i++)
		{
			if (colors3 != null && colors3[i] == HIDDEN)
			{
				continue;
			}
			int al = transparencies == null ? 255 : 255 - (transparencies[i] & 0xff);
			if (al == 0)
			{
				continue;
			}
			if (a[i] < 0 || b[i] < 0 || c[i] < 0 || a[i] >= vertices || b[i] >= vertices || c[i] >= vertices)
			{
				continue;
			}
			f.add(a[i]);
			f.add(b[i]);
			f.add(c[i]);
			int tid = textures != null ? textures[i] : -1;
			boolean textured = tid != -1;
			int hsl = unlit != null ? unlit[i] & 0xffff : (colors1 != null ? colors1[i] & 0xffff : 0);
			col.add(textured ? -1 : hsl);
			if (colors1 != null)
			{
				boolean flat = colors3 == null || colors3[i] == FLAT;
				int c1 = colors1[i];
				int c2 = flat || colors2 == null ? c1 : colors2[i];
				int c3 = flat ? c1 : colors3[i];
				lit.add(textured ? c1 : c1 & 0xffff);
				lit.add(textured ? c2 : c2 & 0xffff);
				lit.add(textured ? c3 : c3 & 0xffff);
			}
			alpha.add(al);
			seeThrough |= al != 255;
			pri.add(priorities == null ? 0 : priorities[i] & 0xff);
			tex.add(tid);
			if (textured && textureFaces != null && tex1 != null && (textureFaces[i] & 0xff) < tex1.length)
			{
				anyTexture = true;
				float[] u = new float[3], w = new float[3];
				uvs(x, y, z, a[i], b[i], c[i], tex1[textureFaces[i] & 0xff], tex2[textureFaces[i] & 0xff], tex3[textureFaces[i] & 0xff], u, w);
				for (int k = 0; k < 3; k++)
				{
					uv.add(fin(u[k]));
					uv.add(fin(w[k]));
				}
			}
			else
			{
				for (int k = 0; k < 6; k++)
				{
					uv.add(0);
				}
			}
		}
		o.add("v", v);
		o.add("f", f);
		o.add("c", col);
		if (colors1 != null)
		{
			o.add("g", lit);
		}
		if (seeThrough)
		{
			o.add("a", alpha);
		}
		if (priorities != null)
		{
			o.add("p", pri);
		}
		if (anyTexture)
		{
			o.add("t", tex);
			o.add("uv", uv);
		}
		return o;
	}

	/** One frame of a motion: every vertex's x, y, z rounded (as "v"'s), as little-endian 16-bit numbers, in base64. */
	public static String frame(float[] x, float[] y, float[] z, int vertices)
	{
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(vertices * 6).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < vertices; i++)
		{
			b.putShort((short) clamp(Math.round(x[i])));
			b.putShort((short) clamp(Math.round(y[i])));
			b.putShort((short) clamp(Math.round(z[i])));
		}
		return java.util.Base64.getEncoder().encodeToString(b.array());
	}

	/** A motion's frames: {"fps", "v": [each frame (frame)]}. */
	public static JsonObject frames(java.util.List<String> frames, double fps)
	{
		JsonObject o = new JsonObject();
		o.addProperty("fps", Math.round(fps * 100) / 100.0);
		JsonArray v = new JsonArray();
		for (String f : frames)
		{
			v.add(f);
		}
		o.add("v", v);
		return o;
	}

	// ---- effects and the ground (11/15/2026, Jace: "can you build the effects (spot animations) and the ground into the
	// model viewer/capturer?")

	/**
	 * How many of a player model's vertices are the player's own: the client may hand out the player's model with their
	 * effects (spot animations) merged in after them. When the last {@code fx} vertices lie exactly where the effects'
	 * own models put them across (x and z the same, in order), the player's own are the ones before; otherwise all.
	 * {@code fxX}/{@code fxZ}: each effect's vertices' x and z, in order.
	 */
	public static int ownVertices(float[] x, float[] z, int vertices, java.util.List<float[]> fxX, java.util.List<float[]> fxZ, int[] fxCounts)
	{
		int s = 0;
		for (int n : fxCounts)
		{
			s += n;
		}
		if (s <= 0 || s >= vertices)
		{
			return vertices;
		}
		int at = vertices - s;
		for (int k = 0; k < fxCounts.length; k++)
		{
			float[] ex = fxX.get(k), ez = fxZ.get(k);
			for (int i = 0; i < fxCounts[k]; i++, at++)
			{
				if (Math.abs(x[at] - ex[i]) > 0.5f || Math.abs(z[at] - ez[i]) > 0.5f)
				{
					return vertices;
				}
			}
		}
		return vertices - s;
	}

	/** A model (as {@link #model}) cut to its first {@code keep} vertices: every face reaching past them dropped, with
	 *  its colours, opacity, priority and texture. */
	public static JsonObject trim(JsonObject m, int keep)
	{
		JsonArray v = m.getAsJsonArray("v");
		if (v == null || v.size() <= keep * 3)
		{
			return m;
		}
		JsonArray f = m.getAsJsonArray("f");
		int nf = f.size() / 3;
		JsonObject o = new JsonObject();
		JsonArray nv = new JsonArray();
		for (int i = 0; i < keep * 3; i++)
		{
			nv.add(v.get(i));
		}
		o.add("v", nv);
		String[] per = {"c", "a", "p", "t"};
		JsonArray of = new JsonArray(), og = new JsonArray(), ouv = new JsonArray();
		JsonArray[] op = new JsonArray[per.length];
		for (int k = 0; k < per.length; k++)
		{
			op[k] = m.has(per[k]) ? new JsonArray() : null;
		}
		for (int t = 0; t < nf; t++)
		{
			int a = f.get(t * 3).getAsInt(), b = f.get(t * 3 + 1).getAsInt(), c = f.get(t * 3 + 2).getAsInt();
			if (a >= keep || b >= keep || c >= keep)
			{
				continue;
			}
			of.add(a);
			of.add(b);
			of.add(c);
			for (int k = 0; k < per.length; k++)
			{
				if (op[k] != null)
				{
					op[k].add(m.getAsJsonArray(per[k]).get(t));
				}
			}
			if (m.has("g"))
			{
				for (int q = 0; q < 3; q++)
				{
					og.add(m.getAsJsonArray("g").get(t * 3 + q));
				}
			}
			if (m.has("uv"))
			{
				for (int q = 0; q < 6; q++)
				{
					ouv.add(m.getAsJsonArray("uv").get(t * 6 + q));
				}
			}
		}
		o.add("f", of);
		for (int k = 0; k < per.length; k++)
		{
			if (op[k] != null)
			{
				o.add(per[k], op[k]);
			}
		}
		if (m.has("g"))
		{
			o.add("g", og);
		}
		if (m.has("uv"))
		{
			o.add("uv", ouv);
		}
		return o;
	}

	/** A colour the game marks "don't draw" on a tile. */
	static final int TILE_HIDDEN = 12345678;

	/**
	 * The ground round the player as a model (the same shape as {@link #model}, three vertices a face), from its triangles:
	 * each {@code tri} is x, y, z for its three corners (already in the player's model space), then its three colours
	 * (the game's packed HSL, lit), then its texture (-1 for none), then u, v for each corner times 4096. A face with a
	 * colour the game never draws is left out.
	 */
	public static JsonObject ground(java.util.List<int[]> tris)
	{
		JsonObject o = new JsonObject();
		JsonArray v = new JsonArray(), f = new JsonArray(), c = new JsonArray(), g = new JsonArray(), t = new JsonArray(), uv = new JsonArray();
		boolean anyTexture = false;
		int n = 0;
		for (int[] q : tris)
		{
			if (q[9] == TILE_HIDDEN || q[10] == TILE_HIDDEN || q[11] == TILE_HIDDEN)
			{
				continue;
			}
			for (int k = 0; k < 9; k++)
			{
				v.add(clamp(q[k]));
			}
			f.add(n);
			f.add(n + 1);
			f.add(n + 2);
			n += 3;
			boolean textured = q[12] >= 0;
			anyTexture |= textured;
			c.add(textured ? -1 : q[9] & 0xffff);
			for (int k = 0; k < 3; k++)
			{
				// (a textured face's corners as a model's: 7-bit lightnesses that multiply its texture)
				g.add(textured ? q[9 + k] & 127 : q[9 + k] & 0xffff);
			}
			t.add(q[12]);
			for (int k = 0; k < 6; k++)
			{
				uv.add(textured ? q[13 + k] : 0);
			}
		}
		o.add("v", v);
		o.add("f", f);
		o.add("c", c);
		o.add("g", g);
		if (anyTexture)
		{
			o.add("t", t);
			o.add("uv", uv);
		}
		return o;
	}

	/** A texture coordinate times 4096, kept to what the relay takes. */
	private static int fin(float t)
	{
		if (Float.isNaN(t) || Float.isInfinite(t))
		{
			return 0;
		}
		return (int) Math.max(-1_000_000, Math.min(1_000_000, Math.round(t * 4096f)));
	}

	/** A face's corners' texture coordinates from its texture triangle (origin, then its two axes), as the client finds them. */
	static void uvs(float[] vx, float[] vy, float[] vz, int fa, int fb, int fc, int ta, int tb, int tc, float[] u, float[] v)
	{
		float ox = vx[ta], oy = vy[ta], oz = vz[ta];
		float ux = vx[tb] - ox, uy = vy[tb] - oy, uz = vz[tb] - oz;
		float wx = vx[tc] - ox, wy = vy[tc] - oy, wz = vz[tc] - oz;
		float[][] p = {{vx[fa] - ox, vy[fa] - oy, vz[fa] - oz}, {vx[fb] - ox, vy[fb] - oy, vz[fb] - oz}, {vx[fc] - ox, vy[fc] - oy, vz[fc] - oz}};
		float nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
		float px = wy * nz - wz * ny, py = wz * nx - wx * nz, pz = wx * ny - wy * nx;
		float s = 1f / (px * ux + py * uy + pz * uz);
		for (int k = 0; k < 3; k++)
		{
			u[k] = (px * p[k][0] + py * p[k][1] + pz * p[k][2]) * s;
		}
		px = uy * nz - uz * ny; py = uz * nx - ux * nz; pz = ux * ny - uy * nx;
		s = 1f / (px * wx + py * wy + pz * wz);
		for (int k = 0; k < 3; k++)
		{
			v[k] = (px * p[k][0] + py * p[k][1] + pz * p[k][2]) * s;
		}
	}

	private static int clamp(int n)
	{
		return Math.max(-8192, Math.min(8192, n));
	}
	// hub:end model

}
