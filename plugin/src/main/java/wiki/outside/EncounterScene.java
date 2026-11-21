package wiki.outside;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ItemLayer;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.VarClientID;

/**
 * An encounter's scene moment taken from the player's own game (11/21/2026; ghillie's claude/outstation.md, "Encounters,
 * round two"): what the client drew round the kill, as EncounterMoment's dots, so every encounter that goes on the page
 * goes with its looping moment (ghillie's replay/3d/MOMENT-FORMAT.md, version 2) and a visitor needs no game files to see
 * it.
 *
 * <p>The stretch is moment.js's stretchFor in the recorder's terms: from 6 ticks before the kill (the kill or completion
 * count line the recorder ends on) to 5 after it, or 5 after the drop landed (the first item to land within 12 tiles of
 * the player from a tick before the kill to 20 after), held on the drop (the anchor: the frame the still scene, the
 * camera and the tick are taken at), 34 ticks at most. A recording that ends without a kill keeps the last seconds round
 * the player's death, else round the last hit, else its end ("round this tick").
 *
 * <p>Since the stretch begins before the kill is known, a rolling buffer keeps the moving things of the last
 * {@link #KEEP_TICKS} ticks, a frame every 2 client cycles (25 a second, moment.js's default): each NPC's, player's,
 * projectile's, graphic's, ground item's and animated object's model as the client poses it for drawing that cycle
 * (Actor.getModel and the rest: what the game draws, rotated and placed as Model's own drawing does it), its corners
 * copied as whole units in the moment's axes (6 bytes a corner) and its faces' see-through-ness when it has any; its
 * faces, colours and textures copied once while it stays the same model. At the kill (else as the recording ends) the
 * still scene is taken once: the tile paints and tile models within {@link EncounterMoment#REACH} tiles of the player on
 * their plane and below (no roofs, as most play), and every still object there (walls, wall decorations, floor
 * decorations, game objects) by its own model, as Scene's drawing places each; their arrays are the scene's own (they
 * don't change), read off the client's thread. Everything heavy (sampling the dots, the tracks, the file) runs on the
 * encounters' moment thread after the recording ends. Measured in the local world (claude/outstation.md): the client
 * thread's share per frame and at the still scene, and the buffer's size, are logged with each moment.
 *
 * <p>The ground items' names and prices are RuneLite's Ground Items plugin's (its defaults, as moment.js's itemText):
 * the item's name (a noted one's, the item it notes), "(qty)" past 1, "(GE: … gp)" from RuneLite's own prices
 * (ItemManager) and "(HA: … gp)", for all but coins, written by RuneLite's QuantityFormatter; coloured by the higher of
 * the two times the quantity; a loot beam over a tile whose best is worth more than 1,000,000.
 */
final class EncounterScene
{
	/** ticks of moving things kept before a kill is known (the stretch reaches 6 before it) */
	static final int KEEP_TICKS = 9;
	/** ticks before the kill and after the kill or the drop (moment.js's SET.before, SET.after); a stretch at most */
	static final int BEFORE = 6, AFTER = 5, STRETCH_MOST = 34, DROP_WAIT = 20;
	/** ticks kept after the loot came (round three: the window read) */
	static final int CHEST_AFTER = 8;
	/** (round four, 11/21/2026) ticks kept after a pet came (its message, or it appearing beside the player when it did),
	 *  and the most a stretch reaches back for the kill before a corpse destroyed */
	static final int PET_AFTER = 5;
	/** the loot window's picture, at most (a reward window is about 500 by 330 pixels: 100-200 KB as a PNG) */
	static final int PICTURE_MOST = 600 * 1024;
	/** client cycles between frames (2: 25 a second) */
	static final int CYCLES = 2;
	/** the buffer's size at most: past it the oldest frames go, or after the kill, no more are kept */
	static final long BUFFER_MOST = 96L * 1024 * 1024;
	/** tiles round the player a moving thing is kept within (the moment's reach, and a tile for its size) */
	static final int NEAR = EncounterMoment.REACH + 2;

	/** What the scene needs from the plugin: a ground item's name and prices as the Ground Items plugin shows them. */
	interface Items
	{
		/** {text, colour "#rrggbb", value} for an item and quantity, or null (on the client's thread) */
		Object[] label(int id, int qty);
	}

	/** A model as it stays (its faces, colours and textures), shared by its frames. */
	static final class Shape
	{
		final String key;
		final int kind;
		final EncounterMoment.Mesh mesh;
		byte[] texFaces;
		int[] tp, tm, tn;

		Shape(String key, int kind, EncounterMoment.Mesh mesh)
		{
			this.key = key;
			this.kind = kind;
			this.mesh = mesh;
		}
	}

	/** A model in one frame: its shape, its corners (whole units, the moment's axes), its faces' see-through-ness. */
	static final class Sample
	{
		final Shape shape;
		final short[] xyz;
		final byte[] alpha;

		Sample(Shape shape, short[] xyz, byte[] alpha)
		{
			this.shape = shape;
			this.xyz = xyz;
			this.alpha = alpha;
		}
	}

	/** A ground item seen: its label, its tile's middle and ground (the moment's axes, whole units, not yet from the
	 *  origin), its pile's height. */
	static final class Item
	{
		String text, color;
		long value;
		int id, qty;
		int x, y, z, lift;
	}

	/** One frame: the cycle, the recording's tick, the camera, the models and the items. */
	static final class Frame
	{
		int cycle, tick;
		float fx, fy;
		int yaw, pitch, zoom, h, ground;
		final List<Sample> samples = new ArrayList<>();
		final List<Item> items = new ArrayList<>();
		long bytes;
	}

	/** An item of loot (round three): its id, quantity, GE price and high alchemy value each, its name. */
	static final class Loot
	{
		final int id, qty;
		final long ge, ha;
		final String name;

		Loot(int id, int qty, long ge, long ha, String name)
		{
			this.id = id;
			this.qty = qty;
			this.ge = ge;
			this.ha = ha;
			this.name = name;
		}
	}

	/**
	 * The loot as the game showed it (11/21/2026, round three; Jace: no reward chest puts its loot on the ground, so the
	 * moment shows the window, not names over the chest): "reward" (a chest's or a boss's reward window, its interface
	 * and title) or "inventory" (a corpse looted: what came into the inventory); the items; and the window as the
	 * player's client drew it (a PNG of its box on the canvas, taken a few cycles after it opened), when it could be.
	 */
	static final class Window
	{
		final String kind, source;
		final int group;
		volatile String title;
		final List<Loot> items;
		volatile byte[] picture;
		volatile int pw, ph;

		Window(String kind, String source, int group, String title, List<Loot> items)
		{
			this.kind = kind;
			this.source = source;
			this.group = group;
			this.title = title;
			this.items = items;
		}
	}

	/** Everything a moment is built from, handed to the moment thread. */
	static final class Taken
	{
		List<Frame> frames;
		List<EncounterMoment.Mesh> still;
		Map<Integer, int[]> textures;
		/** (tick, cycle) of each tick of the recording seen */
		List<int[]> clock;
		int from, to, anchor, killTick = -1, dropTick = -1, fallTick = -1, lootTick = -1;
		Window window;
		/** (round four) the pet: its message's tick, the tick it was seen (beside them or in the backpack; -1 not seen), how
		 *  it came ("follower", "backpack", "duplicate"), its name and its NPC or item id when known, its chat line */
		int petTick = -1, petSeenTick = -1, petNpc = -1, petItem = -1;
		String petKind, petName;
		Window petWindow;
		String why, npc;
		int plane;
		long clientNanos, stillNanos, bufferBytes;
		int framesTaken;
	}

	private final Client client;
	private final Items items;
	private final boolean others;
	private final ArrayDeque<Frame> frames = new ArrayDeque<>();
	private final Map<String, Shape> shapes = new HashMap<>();
	private final Map<TileItem, Item> itemSeen = new IdentityHashMap<>();
	private final Map<Integer, int[]> textures = new HashMap<>();
	private final List<int[]> clock = new ArrayList<>();
	private List<EncounterMoment.Mesh> still;
	private int stillPlane;
	private long bytes, clientNanos, stillNanos;
	private int framesTaken;
	private boolean anyFrame;
	private int lastCycle;
	private int tick;
	private int killTick = -1, dropTick = -1, deathTick = -1, hitTick = -1;
	private String killNpc;
	/** the loot (round three): its tick (the whole recording's), the window, where it came from */
	private int lootTick = -1;
	private Window window;
	/** the pet (round four): its message's tick, when it was seen, how it came, its name, ids, its chat line */
	private int petTick = -1, petSeenTick = -1, petNpc = -1, petItem = -1;
	private String petKind, petName;
	private Window petWindow;
	private final Map<String, Integer> dealt = new HashMap<>();
	private boolean full, warned;

	EncounterScene(Client client, Items items, boolean others)
	{
		this.client = client;
		this.items = items;
		this.others = others;
	}

	// ---- told by the recorder (the client's thread)

	/** A tick of the recording: its number, and the cycle it came on. */
	void tick(int t)
	{
		tick = t;
		clock.add(new int[]{t, client.getGameCycle()});
		trim();
	}

	/** The kill (its count line, on the recording's tick {@code t}): the still scene is taken now. */
	void kill(int t, String npc)
	{
		if (killTick < 0)
		{
			killTick = t;
			killNpc = npc;
			takeStill();
		}
	}

	boolean killed()
	{
		return killTick >= 0;
	}

	boolean dropped()
	{
		return dropTick >= 0;
	}

	boolean looted()
	{
		return lootTick >= 0;
	}

	String killNpc()
	{
		return killNpc;
	}

	/** The loot (a chest's window, a corpse's): the moment is its opening; the still scene taken again here (a raid's
	 *  treasure room is another map from its last boss's). The first loot counts. ({@code at} and {@code plane}: where
	 *  it came from, kept by the loot line; the moment is centred as always on the camera's followed point.) */
	void loot(int t, Window w, LocalPoint at, int plane)
	{
		if (lootTick >= 0)
		{
			return;
		}
		lootTick = t;
		window = w;
		still = null;
		takeStill();
	}

	int killTick()
	{
		return killTick;
	}

	/** A pet's message (round four, 11/21/2026; Jace: "Pet rolls from bosses/raid loot chests are counted as moments,
	 *  correct?"): the moment then has the pet in it, with the chat line {@code w} as the game showed it. The first
	 *  counts. */
	void pet(int t, String kind, Window w)
	{
		if (petTick >= 0)
		{
			return;
		}
		petTick = t;
		petKind = kind;
		petWindow = w;
		if (still == null)
		{
			takeStill();
		}
	}

	/** The pet seen: beside the player as a follower ({@code npc} its id) or come into the backpack ({@code item}). */
	void petSeen(int t, int npc, int item, String name)
	{
		if (petTick < 0 || petSeenTick >= 0)
		{
			return;
		}
		petSeenTick = t;
		petNpc = npc;
		petItem = item;
		if (name != null)
		{
			petName = name;
		}
	}

	/** The pet's name when the game said it some other way (the collection log's line, an untradeable drop's). */
	void petName(String name)
	{
		if (petTick >= 0 && petName == null && name != null)
		{
			petName = name;
		}
	}

	boolean petCame()
	{
		return petTick >= 0;
	}

	int petTick()
	{
		return petTick;
	}

	boolean petSeen()
	{
		return petSeenTick >= 0;
	}

	int petSeenTick()
	{
		return petSeenTick;
	}

	/** An item landed (not one there at the start): the first near the player from a tick before the kill on is the
	 *  drop. Returns whether it was. */
	boolean itemLanded(int t, LocalPoint at, int plane)
	{
		Player me = client.getLocalPlayer();
		if (killTick < 0 || dropTick >= 0 || t < killTick - 1 || t > killTick + DROP_WAIT || me == null || at == null)
		{
			return false;
		}
		LocalPoint p = me.getLocalLocation();
		if (p == null || plane != me.getWorldLocation().getPlane() || Math.abs(at.getX() - p.getX()) > 12 * 128
			|| Math.abs(at.getY() - p.getY()) > 12 * 128)
		{
			return false;
		}
		dropTick = t;
		return true;
	}

	/** The subject died (the last death counts: 11/21/2026, the fall). */
	void died(int t)
	{
		deathTick = t;
	}

	/** A hit in the fight; one of theirs on an NPC counts toward which is the boss (the most dealt), for the fall's name. */
	void hit(int t, String npc, int amount)
	{
		hitTick = t;
		if (npc != null && amount > 0)
		{
			dealt.merge(npc, amount, Integer::sum);
		}
	}

	/** Whether the player dealt damage to an NPC of this name this recording (round four: a corpse's name). */
	boolean fought(String npc)
	{
		return npc != null && dealt.containsKey(npc);
	}

	private String boss()
	{
		String best = null;
		for (Map.Entry<String, Integer> e : dealt.entrySet())
		{
			if (best == null || e.getValue() > dealt.get(best))
			{
				best = e.getKey();
			}
		}
		return best;
	}

	/** Each client cycle: a frame every CYCLES. */
	void cycle()
	{
		int c = client.getGameCycle();
		if (anyFrame && c - lastCycle < CYCLES)
		{
			return;
		}
		anyFrame = true;
		// (after the loot, frames only as far as its stretch reaches: nothing after it is the moment; round three. After
		// a kill they go on, the last few ticks rolling, since a chest or a corpse looted later is the moment then)
		if (lootTick >= 0 && tick > lootTick + STRETCH_MOST)
		{
			return;
		}
		lastCycle = c;
		long t0 = System.nanoTime();
		try
		{
			Frame f = frame(c);
			if (f != null)
			{
				frames.addLast(f);
				bytes += f.bytes;
				trim();
			}
		}
		catch (RuntimeException e)
		{
			// (a model the client hands over half made: this frame goes without it; the first said in the log)
			if (!warned)
			{
				warned = true;
				org.slf4j.LoggerFactory.getLogger(EncounterScene.class).warn("outside: a frame of the scene couldn't be taken", e);
			}
		}
		clientNanos += System.nanoTime() - t0;
		framesTaken++;
	}

	/**
	 * The frames past what's needed go (round three: a kill, a death and the loot each keep their own stretch, from
	 * BEFORE ticks before to STRETCH_MOST after, and the last KEEP_TICKS ticks roll on, for a loot or a death still to
	 * come); past the size, the oldest unanchored first, then the newest stop coming.
	 */
	private void trim()
	{
		if (!frames.isEmpty())
		{
			java.util.Iterator<Frame> it = frames.iterator();
			while (it.hasNext())
			{
				Frame f = it.next();
				if (!kept(f.tick))
				{
					bytes -= f.bytes;
					it.remove();
				}
			}
		}
		if (bytes > BUFFER_MOST)
		{
			java.util.Iterator<Frame> it = frames.iterator();
			while (it.hasNext() && bytes > BUFFER_MOST && frames.size() > 1)
			{
				Frame f = it.next();
				if (!anchored(f.tick))
				{
					bytes -= f.bytes;
					it.remove();
				}
			}
			if (bytes > BUFFER_MOST)
			{
				// (the anchors' own stretches fill it: the newest stop coming instead)
				full = true;
			}
		}
		// (the clock keeps what the frames reach, and a little)
		while (clock.size() > 2 && !frames.isEmpty() && clock.get(1)[0] < frames.peekFirst().tick - 1)
		{
			clock.remove(0);
		}
	}

	/** Whether a frame of tick {@code t} is within a kill's, a death's or the loot's stretch. */
	private boolean anchored(int t)
	{
		return near(t, killTick) || near(t, deathTick) || near(t, lootTick) || near(t, petTick);
	}

	private static boolean near(int t, int anchor)
	{
		return anchor >= 0 && t >= anchor - BEFORE && t <= anchor + STRETCH_MOST;
	}

	/** Whether a frame of tick {@code t} is kept: anchored, or among the last KEEP_TICKS ticks (until the loot's come). */
	private boolean kept(int t)
	{
		return anchored(t) || lootTick < 0 && t >= tick - KEEP_TICKS;
	}

	// ---- a frame (the client's thread)

	private Frame frame(int cycle)
	{
		WorldView wv = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (wv == null || me == null || me.getLocalLocation() == null || full)
		{
			return null;
		}
		Frame f = new Frame();
		f.cycle = cycle;
		f.tick = tick;
		f.fx = client.getCameraFocalPointX();
		f.fy = client.getCameraFocalPointZ();
		f.yaw = client.getCameraYaw();
		f.pitch = client.getCameraPitch();
		f.zoom = client.getVarcIntValue(client.isResized() ? VarClientID.CAMERA_ZOOM_BIG : VarClientID.CAMERA_ZOOM_SMALL);
		f.h = client.getViewportHeight();
		int plane = wv.getPlane();
		f.ground = Perspective.getTileHeight(client, new LocalPoint((int) f.fx, (int) f.fy, wv), plane);
		LocalPoint mine = me.getLocalLocation();
		int near = NEAR * 128;
		for (NPC npc : wv.npcs())
		{
			LocalPoint lp = npc.getLocalLocation();
			if (lp != null && close(lp, mine, near))
			{
				actor(f, "n" + npc.getIndex(), npc.getModel(), lp, plane, npc.getCurrentOrientation(), wv);
			}
		}
		for (Player p : wv.players())
		{
			if (p != me && !others)
			{
				continue;
			}
			LocalPoint lp = p.getLocalLocation();
			if (lp != null && close(lp, mine, near))
			{
				actor(f, "p" + p.getId(), p.getModel(), lp, plane, p.getCurrentOrientation(), wv);
			}
		}
		for (Projectile proj : client.getProjectiles())
		{
			if (proj.getSourceLevel() != plane || Math.abs(proj.getX() - mine.getX()) > near || Math.abs(proj.getY() - mine.getY()) > near)
			{
				continue;
			}
			place(f, "j" + System.identityHashCode(proj), EncounterMoment.KIND_PROJ, proj.getModel(), (float) proj.getX(),
				(float) proj.getY(), (float) proj.getZ(), proj.getOrientation());
		}
		for (GraphicsObject g : wv.getGraphicsObjects())
		{
			LocalPoint lp = g.getLocation();
			if (g.finished() || lp == null || g.getLevel() != plane || !close(lp, mine, near))
			{
				continue;
			}
			place(f, "g" + System.identityHashCode(g), EncounterMoment.KIND_GFX, g.getModel(), lp.getX(), lp.getY(), g.getZ(), 0);
		}
		// (the scene round the player: items on the ground, and objects that animate)
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		int cx = mine.getSceneX(), cy = mine.getSceneY();
		for (int x = Math.max(0, cx - NEAR); x <= Math.min(tiles[plane].length - 1, cx + NEAR); x++)
		{
			for (int y = Math.max(0, cy - NEAR); y <= Math.min(tiles[plane][x].length - 1, cy + NEAR); y++)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null)
				{
					continue;
				}
				ItemLayer layer = tile.getItemLayer();
				List<TileItem> got = tile.getGroundItems();
				if (layer != null && got != null && !got.isEmpty())
				{
					// (the pile: the top three drawn as the client draws them, all at the layer's place raised by its height)
					Renderable[] drawn = {layer.getBottom(), layer.getMiddle(), layer.getTop()};
					for (Renderable r : drawn)
					{
						if (r instanceof TileItem)
						{
							place(f, "i" + System.identityHashCode(r), EncounterMoment.KIND_ITEM, r.getModel(), layer.getX(),
								layer.getY(), layer.getZ() - layer.getHeight(), 0);
						}
					}
					for (TileItem it : got)
					{
						Item seen = itemSeen.get(it);
						if (seen == null)
						{
							Object[] l = items.label(it.getId(), it.getQuantity());
							if (l == null)
							{
								continue;
							}
							seen = new Item();
							seen.text = (String) l[0];
							seen.color = (String) l[1];
							seen.value = (Long) l[2];
							seen.id = it.getId();
							seen.qty = it.getQuantity();
							seen.x = layer.getX();
							seen.y = -layer.getZ();
							seen.z = -layer.getY();
							seen.lift = layer.getHeight();
							itemSeen.put(it, seen);
						}
						f.items.add(seen);
					}
				}
				for (GameObject o : tile.getGameObjects())
				{
					if (o != null && o.getRenderable() instanceof DynamicObject && tile.getSceneLocation().equals(o.getSceneMinLocation()))
					{
						place(f, "o" + o.getHash(), EncounterMoment.KIND_OBJECT, o.getRenderable().getModel(), o.getX(), o.getY(), o.getZ(),
							o.getModelOrientation());
					}
				}
				WallObject w = tile.getWallObject();
				if (w != null && w.getRenderable1() instanceof DynamicObject)
				{
					place(f, "w" + w.getHash(), EncounterMoment.KIND_OBJECT, w.getRenderable1().getModel(), w.getX(), w.getY(), w.getZ(), 0);
				}
			}
		}
		return f;
	}

	private static boolean close(LocalPoint a, LocalPoint b, int near)
	{
		return Math.abs(a.getX() - b.getX()) <= near && Math.abs(a.getY() - b.getY()) <= near;
	}

	private void actor(Frame f, String key, Model m, LocalPoint lp, int plane, int orientation, WorldView wv)
	{
		if (m == null)
		{
			return;
		}
		int h = Perspective.getTileHeight(client, lp, plane);
		place(f, key, EncounterMoment.KIND_ACTOR, m, lp.getX(), lp.getY(), h, orientation);
	}

	/**
	 * A model as Model's own drawing places it: each corner turned by the orientation (x' = z sin + x cos, z' = z cos -
	 * x sin, the client's 2048 a turn), then at (x, height, y) in the scene; written in the moment's axes (x east, y up,
	 * z south) as whole units.
	 */
	private void place(Frame f, String key, int kind, Model m, float x, float y, float h, int orientation)
	{
		if (m == null || m.getFaceCount() == 0 || m.getVerticesCount() == 0)
		{
			return;
		}
		int fc = m.getFaceCount(), vc = m.getVerticesCount();
		int[] c1 = m.getFaceColors1();
		int hash = fc * 31 + vc;
		for (int i = 0; i < fc; i += Math.max(1, fc / 16))
		{
			hash = hash * 31 + c1[i];
		}
		String sk = key + "#" + fc + ":" + vc + ":" + hash;
		Shape s = shapes.get(sk);
		if (s == null)
		{
			s = shape(sk, kind, m, fc);
			shapes.put(sk, s);
		}
		float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
		short[] xyz = new short[vc * 3];
		double sin = 0, cos = 1;
		if (orientation != 0)
		{
			sin = Perspective.SINE[orientation & 2047] / 65536.0;
			cos = Perspective.COSINE[orientation & 2047] / 65536.0;
		}
		for (int v = 0; v < vc; v++)
		{
			double px = vx[v], pz = vz[v];
			if (orientation != 0)
			{
				double t = pz * sin + px * cos;
				pz = pz * cos - px * sin;
				px = t;
			}
			xyz[v * 3] = EncounterMoment.Builder.unit(x + px);
			xyz[v * 3 + 1] = EncounterMoment.Builder.unit(-(h + vy[v]));
			xyz[v * 3 + 2] = EncounterMoment.Builder.unit(-(y + pz));
		}
		byte[] tr = m.getFaceTransparencies();
		byte[] alpha = tr == null ? null : java.util.Arrays.copyOf(tr, fc);
		f.samples.add(new Sample(s, xyz, alpha));
		f.bytes += 48 + xyz.length * 2L + (alpha == null ? 0 : fc);
	}

	private Shape shape(String key, int kind, Model m, int fc)
	{
		EncounterMoment.Mesh mesh = new EncounterMoment.Mesh();
		mesh.fa = java.util.Arrays.copyOf(m.getFaceIndices1(), fc);
		mesh.fb = java.util.Arrays.copyOf(m.getFaceIndices2(), fc);
		mesh.fc = java.util.Arrays.copyOf(m.getFaceIndices3(), fc);
		mesh.c1 = java.util.Arrays.copyOf(m.getFaceColors1(), fc);
		mesh.c2 = java.util.Arrays.copyOf(m.getFaceColors2(), fc);
		mesh.c3 = java.util.Arrays.copyOf(m.getFaceColors3(), fc);
		short[] t = m.getFaceTextures();
		mesh.tex = t == null ? null : java.util.Arrays.copyOf(t, fc);
		mesh.cls = EncounterMoment.KIND_CLASS[kind];
		Shape s = new Shape(key, kind, mesh);
		if (mesh.tex != null)
		{
			byte[] tf = m.getTextureFaces();
			s.texFaces = tf == null ? null : java.util.Arrays.copyOf(tf, fc);
			s.tp = m.getTexIndices1() == null ? null : m.getTexIndices1().clone();
			s.tm = m.getTexIndices2() == null ? null : m.getTexIndices2().clone();
			s.tn = m.getTexIndices3() == null ? null : m.getTexIndices3().clone();
			for (short id : mesh.tex)
			{
				texture(id);
			}
		}
		bytes += fc * 26L;
		return s;
	}

	/** A texture's pixels, kept once (the client's thread: the provider loads it if it hasn't). */
	private void texture(int id)
	{
		if (id < 0 || textures.containsKey(id))
		{
			return;
		}
		TextureProvider tp = client.getTextureProvider();
		int[] px = null;
		if (tp != null)
		{
			Texture[] all = tp.getTextures();
			if (all != null && id < all.length && all[id] != null && all[id].isLoaded())
			{
				px = all[id].getPixels();
			}
			if (px == null)
			{
				px = tp.load(id);
			}
		}
		textures.put(id, px == null ? new int[0] : px.clone());
	}

	// ---- the still scene (the client's thread, once)

	/** The ground and the still objects within reach of the player, on their plane and those under it. */
	void takeStill()
	{
		if (still != null)
		{
			return;
		}
		long t0 = System.nanoTime();
		WorldView wv = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		if (wv == null || me == null || me.getLocalLocation() == null)
		{
			return;
		}
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		int[][][] heights = scene.getTileHeights();
		int plane = wv.getPlane();
		stillPlane = plane;
		LocalPoint mine = me.getLocalLocation();
		int cx = mine.getSceneX(), cy = mine.getSceneY(), r = EncounterMoment.REACH + 2;
		List<EncounterMoment.Mesh> out = new ArrayList<>();
		for (int z = 0; z <= plane; z++)
		{
			for (int x = Math.max(0, cx - r); x <= Math.min(tiles[z].length - 1, cx + r); x++)
			{
				for (int y = Math.max(0, cy - r); y <= Math.min(tiles[z][x].length - 1, cy + r); y++)
				{
					Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}
					SceneTilePaint paint = tile.getSceneTilePaint();
					if (paint != null)
					{
						EncounterMoment.Mesh m = paint(paint, x, y, heights[z]);
						if (m != null)
						{
							out.add(m);
						}
					}
					SceneTileModel tm = tile.getSceneTileModel();
					if (tm != null)
					{
						EncounterMoment.Mesh m = tileModel(tm, x, y);
						if (m != null)
						{
							out.add(m);
						}
					}
					WallObject w = tile.getWallObject();
					if (w != null)
					{
						scenery(out, w.getRenderable1(), w.getX(), w.getY(), w.getZ(), 0);
						scenery(out, w.getRenderable2(), w.getX(), w.getY(), w.getZ(), 0);
					}
					DecorativeObject d = tile.getDecorativeObject();
					if (d != null)
					{
						scenery(out, d.getRenderable(), d.getX() + d.getXOffset(), d.getY() + d.getYOffset(), d.getZ(), 0);
						scenery(out, d.getRenderable2(), d.getX() + d.getXOffset2(), d.getY() + d.getYOffset2(), d.getZ(), 0);
					}
					GroundObject g = tile.getGroundObject();
					if (g != null)
					{
						scenery(out, g.getRenderable(), g.getX(), g.getY(), g.getZ(), 0);
					}
					for (GameObject o : tile.getGameObjects())
					{
						if (o != null && tile.getSceneLocation().equals(o.getSceneMinLocation()))
						{
							scenery(out, o.getRenderable(), o.getX(), o.getY(), o.getZ(), o.getModelOrientation());
						}
					}
				}
			}
		}
		still = out;
		stillNanos = System.nanoTime() - t0;
	}

	/** A still object's model (an animated one is a frame's, not the still scene's), placed as the scene draws it. */
	private void scenery(List<EncounterMoment.Mesh> out, Renderable r, int x, int y, int h, int orientation)
	{
		if (!(r instanceof Model))
		{
			return;
		}
		Model m = (Model) r;
		int fc = m.getFaceCount(), vc = m.getVerticesCount();
		if (fc == 0 || vc == 0)
		{
			return;
		}
		EncounterMoment.Mesh mesh = new EncounterMoment.Mesh();
		float[] vx = m.getVerticesX(), vy = m.getVerticesY(), vz = m.getVerticesZ();
		mesh.x = new float[vc];
		mesh.y = new float[vc];
		mesh.z = new float[vc];
		double sin = 0, cos = 1;
		if (orientation != 0)
		{
			sin = Perspective.SINE[orientation & 2047] / 65536.0;
			cos = Perspective.COSINE[orientation & 2047] / 65536.0;
		}
		for (int v = 0; v < vc; v++)
		{
			double px = vx[v], pz = vz[v];
			if (orientation != 0)
			{
				double t = pz * sin + px * cos;
				pz = pz * cos - px * sin;
				px = t;
			}
			mesh.x[v] = (float) (x + px);
			mesh.y[v] = -(h + vy[v]);
			mesh.z[v] = (float) -(y + pz);
		}
		// (the scene's own still models don't change: their faces and colours are read as they are, off this thread)
		mesh.fa = m.getFaceIndices1();
		mesh.fb = m.getFaceIndices2();
		mesh.fc = m.getFaceIndices3();
		if (mesh.fa.length != fc)
		{
			mesh.fa = java.util.Arrays.copyOf(mesh.fa, fc);
			mesh.fb = java.util.Arrays.copyOf(mesh.fb, fc);
			mesh.fc = java.util.Arrays.copyOf(mesh.fc, fc);
		}
		mesh.c1 = m.getFaceColors1();
		mesh.c2 = m.getFaceColors2();
		mesh.c3 = m.getFaceColors3();
		mesh.alpha = m.getFaceTransparencies();
		short[] t = m.getFaceTextures();
		mesh.tex = t;
		if (t != null)
		{
			for (int f = 0; f < fc; f++)
			{
				texture(t[f]);
			}
			mesh.uv = EncounterMoment.uvs(mesh.x, mesh.y, mesh.z, mesh.fa, mesh.fb, mesh.fc, t, m.getTextureFaces(),
				m.getTexIndices1(), m.getTexIndices2(), m.getTexIndices3());
		}
		mesh.cls = 1;
		mesh.world = true;
		out.add(mesh);
	}

	/** A tile's paint: two faces over its four corners (north-east, north-west, south-east; south-west, south-east,
	 *  north-west), each corner its own lit colour; none where the client draws none (12345678). */
	private EncounterMoment.Mesh paint(SceneTilePaint p, int x, int y, int[][] heights)
	{
		if (p.getNeColor() == 12345678 || x + 1 >= heights.length || y + 1 >= heights[x].length)
		{
			return null;
		}
		EncounterMoment.Mesh m = new EncounterMoment.Mesh();
		int lx = x * 128, ly = y * 128;
		// (corners: south-west, south-east, north-east, north-west)
		m.x = new float[]{lx, lx + 128, lx + 128, lx};
		m.z = new float[]{-ly, -ly, -(ly + 128), -(ly + 128)};
		m.y = new float[]{-heights[x][y], -heights[x + 1][y], -heights[x + 1][y + 1], -heights[x][y + 1]};
		m.fa = new int[]{2, 0};
		m.fb = new int[]{3, 1};
		m.fc = new int[]{1, 3};
		m.c1 = new int[]{p.getNeColor(), p.getSwColor()};
		m.c2 = new int[]{p.getNwColor(), p.getSeColor()};
		m.c3 = new int[]{p.getSeColor(), p.getNwColor()};
		if (p.getTexture() >= 0)
		{
			m.tex = new short[]{(short) p.getTexture(), (short) p.getTexture()};
			m.uv = new float[]{1, 1, 0, 1, 1, 0, 0, 0, 1, 0, 0, 1};
			texture(p.getTexture());
		}
		m.cls = 0;
		m.terrain = true;
		m.world = true;
		return m;
	}

	/** A tile's model (a shaped tile: its overlay's corner cut, a slope's faces), its faces as the client keeps them. */
	private EncounterMoment.Mesh tileModel(SceneTileModel t, int x, int y)
	{
		int[] vx = t.getVertexX(), vy = t.getVertexY(), vz = t.getVertexZ();
		int[] fx = t.getFaceX(), fy = t.getFaceY(), fz = t.getFaceZ();
		int[] ca = t.getTriangleColorA(), cb = t.getTriangleColorB(), cc = t.getTriangleColorC();
		int[] tex = t.getTriangleTextureId();
		if (vx == null || fx == null || ca == null)
		{
			return null;
		}
		int faces = 0;
		for (int f = 0; f < fx.length; f++)
		{
			faces += ca[f] == 12345678 ? 0 : 1;
		}
		if (faces == 0)
		{
			return null;
		}
		EncounterMoment.Mesh m = new EncounterMoment.Mesh();
		m.x = new float[vx.length];
		m.y = new float[vx.length];
		m.z = new float[vx.length];
		for (int v = 0; v < vx.length; v++)
		{
			m.x[v] = vx[v];
			m.y[v] = -vy[v];
			m.z[v] = -vz[v];
		}
		m.fa = new int[faces];
		m.fb = new int[faces];
		m.fc = new int[faces];
		m.c1 = new int[faces];
		m.c2 = new int[faces];
		m.c3 = new int[faces];
		boolean textured = false;
		if (tex != null)
		{
			for (int f = 0; f < fx.length; f++)
			{
				textured |= ca[f] != 12345678 && tex[f] >= 0;
			}
		}
		m.tex = textured ? new short[faces] : null;
		m.uv = textured ? new float[faces * 6] : null;
		for (int f = 0, k = 0; f < fx.length; f++)
		{
			if (ca[f] == 12345678)
			{
				continue;
			}
			m.fa[k] = fx[f];
			m.fb[k] = fy[f];
			m.fc[k] = fz[f];
			m.c1[k] = ca[f];
			m.c2[k] = cb[f];
			m.c3[k] = cc[f];
			if (textured)
			{
				m.tex[k] = (short) tex[f];
				if (tex[f] >= 0)
				{
					texture(tex[f]);
					int[] cs = {fx[f], fy[f], fz[f]};
					for (int q = 0; q < 3; q++)
					{
						m.uv[k * 6 + q * 2] = (vx[cs[q]] - x * 128) / 128f;
						m.uv[k * 6 + q * 2 + 1] = (vz[cs[q]] - y * 128) / 128f;
					}
				}
			}
			k++;
		}
		m.cls = 0;
		m.terrain = true;
		m.world = true;
		return m;
	}

	// ---- the end: what's been taken, chosen for the stretch (the client's thread), then built (the moment thread)

	/** The recording's ended at tick {@code last}: the stretch chosen, the still scene taken if it wasn't, everything
	 *  handed over. Null when there's nothing to make a moment of. */
	Taken finish(int last)
	{
		if (frames.isEmpty())
		{
			return null;
		}
		takeStill();
		if (still == null || still.isEmpty())
		{
			return null;
		}
		Taken t = new Taken();
		if (lootTick >= 0 && "destroyed".equals(window.kind))
		{
			// a corpse destroyed for a better chance at the pet (round four, 11/21/2026: Araxxor's "Destroy"): no loot, so
			// the kill and the destroying, 6 ticks before the kill to 5 after the corpse went, when that's within a
			// stretch; else the destroying alone, held on the corpse going
			t.to = lootTick + AFTER;
			t.from = killTick >= 0 && t.to - (killTick - BEFORE) <= STRETCH_MOST ? killTick - BEFORE : lootTick - BEFORE;
			t.anchor = lootTick;
			t.killTick = killTick >= 0 && killTick >= t.from ? killTick : -1;
			String done = "Sacrifice".equals(window.title) ? "the corpse sacrificed" : "the corpse destroyed";
			t.why = t.killTick >= 0 ? "the kill and " + done : done;
			t.lootTick = lootTick;
			t.window = window;
			t.npc = killNpc != null ? killNpc : boss();
		}
		else if (lootTick >= 0)
		{
			// the chest's opening (round three): the player at the chest, its animation and the loot's window, 6 ticks
			// before the loot came to 8 after, held a tick in, the window laid over from its frame
			t.from = lootTick - BEFORE;
			t.to = lootTick + CHEST_AFTER;
			t.anchor = lootTick + 1;
			t.why = "inventory".equals(window.kind) ? "the loot" : "the chest";
			t.lootTick = lootTick;
			t.window = window;
			t.killTick = killTick >= 0 && killTick >= t.from ? killTick : -1;
			t.npc = killNpc != null ? killNpc : boss();
		}
		else if (deathTick >= 0 && (killTick < 0 || killTick < deathTick))
		{
			// the subject's fall (11/21/2026, Jace: "What is the moment on an encounter when the subject dies rather than
			// boss?"; moment.js's stretchFor): their last death, unless the boss died after it (a raid's team finishing it):
			// 6 ticks before it to 5 after, held a tick in
			t.from = deathTick - BEFORE;
			t.to = deathTick + AFTER;
			t.anchor = deathTick + 1;
			t.why = "the fall";
			t.fallTick = deathTick;
			t.npc = killNpc != null ? killNpc : boss();
		}
		else if (killTick >= 0)
		{
			t.from = killTick - BEFORE;
			t.to = dropTick >= 0 ? Math.max(killTick + AFTER, dropTick + AFTER) : killTick + AFTER;
			t.anchor = dropTick >= 0 ? dropTick + 1 : killTick;
			t.why = dropTick >= 0 ? "the kill and its drop" : "the kill";
			t.killTick = killTick;
			t.dropTick = dropTick;
			t.npc = killNpc != null ? killNpc : boss();
		}
		else
		{
			int at = hitTick >= 0 && hitTick >= last - KEEP_TICKS ? hitTick : last;
			t.from = at - 4;
			t.to = at + 4;
			t.anchor = at;
			t.why = "round this tick";
		}
		if (petTick >= 0)
		{
			withPet(t);
		}
		int first = frames.peekFirst().tick, end = Math.min(last, frames.peekLast().tick);
		t.from = Math.max(first, t.from);
		t.to = Math.min(end, t.to);
		if (t.to - t.from > STRETCH_MOST)
		{
			t.from = t.to - STRETCH_MOST;
		}
		t.anchor = Math.max(t.from, Math.min(t.to, t.anchor));
		if (t.to < t.from)
		{
			return null;
		}
		List<Frame> kept = new ArrayList<>();
		for (Frame f : frames)
		{
			if (f.tick >= t.from && f.tick <= t.to)
			{
				kept.add(f);
			}
		}
		if (kept.isEmpty())
		{
			return null;
		}
		t.frames = kept;
		t.still = still;
		t.textures = new HashMap<>(textures);
		t.clock = new ArrayList<>(clock);
		t.plane = stillPlane;
		t.clientNanos = clientNanos;
		t.stillNanos = stillNanos;
		t.bufferBytes = bytes;
		t.framesTaken = framesTaken;
		frames.clear();
		return t;
	}

	/**
	 * The pet in the stretch (round four, 11/21/2026; Jace: a raid's pet is rolled as its chest opens, "you get a pet and
	 * loot, or just loot"; a boss's appears beside you as its loot lands, or goes into the backpack): the pet joins the
	 * stretch it came with (the chest, the kill and its drop, the loot, the fall), which runs on to 5 ticks after the pet
	 * was seen, held a tick after it was; a pet far from any of those (or with none) is the moment by itself, its message
	 * 6 ticks in. Above everything else: a stretch too long for both keeps the pet.
	 */
	private void withPet(Taken t)
	{
		int seen = petSeenTick >= 0 ? petSeenTick : petTick;
		int pf = petTick - BEFORE, pt = seen + PET_AFTER;
		int from = Math.min(t.from, pf), to = Math.max(t.to, pt);
		boolean based = !"round this tick".equals(t.why);
		if (based && pt >= t.from - BEFORE && pf <= t.to + BEFORE && to - from <= STRETCH_MOST)
		{
			t.from = from;
			t.to = to;
			t.why = andAPet(t.why);
		}
		else
		{
			t.from = pf;
			t.to = pt;
			t.why = "the pet";
			// (what else falls inside it stays named)
			t.killTick = t.killTick >= pf && t.killTick <= pt ? t.killTick : -1;
			t.dropTick = t.dropTick >= pf && t.dropTick <= pt ? t.dropTick : -1;
			t.fallTick = t.fallTick >= pf && t.fallTick <= pt ? t.fallTick : -1;
			if (t.lootTick < pf || t.lootTick > pt)
			{
				t.lootTick = -1;
				t.window = null;
			}
			if (t.npc == null)
			{
				t.npc = killNpc != null ? killNpc : boss();
			}
		}
		t.anchor = seen + 1;
		t.petTick = petTick;
		t.petSeenTick = petSeenTick;
		t.petKind = petKind;
		t.petName = petName;
		t.petNpc = petNpc;
		t.petItem = petItem;
		t.petWindow = petWindow;
	}

	/** "the kill and its drop" with a pet: "the kill, its drop and a pet"; "the chest": "the chest and a pet". */
	static String andAPet(String why)
	{
		if (why == null)
		{
			return "the pet";
		}
		int and = why.lastIndexOf(" and ");
		return (and < 0 ? why : why.substring(0, and) + "," + why.substring(and + 4)) + " and a pet";
	}

	/** The moment's file, made from what was taken (the moment thread). meta's fight, foes, label and at are the
	 *  recorder's; the rest is worked out here. Returns the gzipped file, at most EncounterMoment.MOST. */
	static byte[] build(Taken t, Map<String, Object> base, int seed) throws java.io.IOException
	{
		List<Frame> fr = t.frames;
		// the frames on the moment's clock: frame k at cycle c0 + 2k, each the newest taken at or before it
		int c0 = cycleAt(t.clock, t.from, fr), c1 = cycleAt(t.clock, t.to + 1, fr) - 1;
		c0 = Math.max(c0, fr.get(0).cycle);
		c1 = Math.min(Math.max(c1, c0), fr.get(fr.size() - 1).cycle);
		int count = Math.max(1, Math.min(600, (c1 - c0) / CYCLES + 1));
		Frame[] at = new Frame[count];
		for (int k = 0, q = 0; k < count; k++)
		{
			int want = c0 + k * CYCLES;
			while (q + 1 < fr.size() && fr.get(q + 1).cycle <= want)
			{
				q++;
			}
			at[k] = fr.get(q);
		}
		int ka = frameOf(cycleAt(t.clock, t.anchor, fr), c0, count);
		Frame anchor = at[ka];
		// the origin: the camera's followed point at the anchor, on the ground under it
		double ox = anchor.fx, oz = -anchor.fy, oy = -anchor.ground;
		EncounterMoment.Builder b = new EncounterMoment.Builder(ox, oy, oz, id -> t.textures.get(id), seed);
		b.still(t.still, EncounterMoment.POINTS);
		// the tracks: each shape's run of frames it's in, unbroken (one gone and back is a new run)
		Map<Shape, int[]> open = new IdentityHashMap<>();
		Map<Shape, List<EncounterMoment.Pose>> poses = new IdentityHashMap<>();
		List<Object[]> runs = new ArrayList<>();
		for (int k = 0; k < count; k++)
		{
			Map<Shape, Sample> here = new IdentityHashMap<>();
			for (Sample s : at[k].samples)
			{
				here.putIfAbsent(s.shape, s);
			}
			for (Map.Entry<Shape, Sample> en : here.entrySet())
			{
				Shape s = en.getKey();
				if (!open.containsKey(s))
				{
					open.put(s, new int[]{k});
					poses.put(s, new ArrayList<>());
				}
				poses.get(s).add(pose(en.getValue()));
			}
			for (Shape s : new ArrayList<>(open.keySet()))
			{
				if (!here.containsKey(s))
				{
					runs.add(new Object[]{s, open.remove(s)[0], poses.remove(s)});
				}
			}
		}
		for (Shape s : open.keySet())
		{
			runs.add(new Object[]{s, open.get(s)[0], poses.get(s)});
		}
		runs.sort((p, q) -> Integer.compare((Integer) p[1], (Integer) q[1]));
		for (Object[] r : runs)
		{
			Shape s = (Shape) r[0];
			@SuppressWarnings("unchecked")
			List<EncounterMoment.Pose> ps = (List<EncounterMoment.Pose>) r[2];
			EncounterMoment.Mesh m = s.mesh;
			if (m.tex != null && m.uv == null && !ps.isEmpty())
			{
				EncounterMoment.Pose p0 = ps.get(0);
				m.uv = EncounterMoment.uvs(p0.x, p0.y, p0.z, m.fa, m.fb, m.fc, m.tex, s.texFaces, s.tp, s.tm, s.tn);
			}
			b.track(m, s.kind, (Integer) r[1], ps);
		}
		// the items on the ground: a name each from when it came to when it went, and the beams over valuable piles
		List<Object> labels = new ArrayList<>(), beams = new ArrayList<>();
		Map<Item, Map<String, Object>> labelOf = new IdentityHashMap<>();
		Map<String, Map<String, Object>> beamOf = new HashMap<>();
		double reach = EncounterMoment.REACH * 128.0;
		for (int k = 0; k < count; k++)
		{
			Map<String, Item> piles = new LinkedHashMap<>();
			for (Item it : at[k].items)
			{
				if (Math.hypot(it.x - ox, it.z - oz) > reach)
				{
					continue;
				}
				Map<String, Object> l = labelOf.get(it);
				if (l == null)
				{
					l = new LinkedHashMap<>();
					l.put("text", it.text);
					l.put("color", it.color);
					l.put("at", list(EncounterMoment.Builder.unit(it.x - ox), EncounterMoment.Builder.unit(it.y + it.lift + 20 - oy),
						EncounterMoment.Builder.unit(it.z - oz)));
					l.put("from", (double) k);
					l.put("to", (double) k + 1);
					l.put("id", (double) it.id);
					l.put("qty", (double) it.qty);
					l.put("value", (double) it.value);
					labelOf.put(it, l);
					labels.add(l);
				}
				l.put("to", (double) k + 1);
				String pk = it.x + "," + it.z;
				Item best = piles.get(pk);
				if (best == null || best.value < it.value)
				{
					piles.put(pk, it);
				}
			}
			for (Map.Entry<String, Item> en : piles.entrySet())
			{
				Item it = en.getValue();
				if (it.value <= 1_000_000)
				{
					continue;
				}
				String color = tier(it.value);
				String bk = en.getKey() + "," + color;
				Map<String, Object> beam = beamOf.get(bk);
				if (beam == null || EncounterMoment.num(beam.get("to")) != k)
				{
					beam = new LinkedHashMap<>();
					beam.put("at", list(EncounterMoment.Builder.unit(it.x - ox), EncounterMoment.Builder.unit(it.y - oy),
						EncounterMoment.Builder.unit(it.z - oz)));
					beam.put("color", color);
					beam.put("from", (double) k);
					beam.put("to", (double) k + 1);
					beamOf.put(bk, beam);
					beams.add(beam);
				}
				else
				{
					beam.put("to", (double) k + 1);
				}
			}
		}
		// the meta (MOMENT-FORMAT.md): the recorder's, then the scene's
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("kind", "outside-moment");
		meta.put("v", 2.0);
		meta.put("fight", base.get("fight"));
		meta.put("tick", (double) t.anchor);
		meta.put("cycle", (double) anchor.cycle);
		meta.put("foes", base.get("foes"));
		meta.put("label", base.get("label"));
		meta.put("at", base.get("at"));
		meta.put("made", (double) System.currentTimeMillis());
		meta.put("plane", (double) t.plane);
		meta.put("tile", list((double) Math.round(anchor.fx / 128.0), (double) Math.round(anchor.fy / 128.0)));
		Map<String, Object> cam = new LinkedHashMap<>();
		// (the client's camera angles as whole numbers are 16384 a turn since it moved to floats: camera.js; the replay's
		// are radians)
		cam.put("yaw", anchor.yaw * Math.PI * 2 / 16384);
		cam.put("pitch", anchor.pitch * Math.PI * 2 / 16384);
		cam.put("zoom", (double) (anchor.zoom > 0 ? anchor.zoom : 512));
		cam.put("H", (double) Math.max(1, anchor.h));
		meta.put("camera", cam);
		meta.put("grid", list((double) Math.floorMod((long) Math.round(ox) - 64, 128), (double) Math.floorMod((long) Math.round(oz) - 64, 128)));
		meta.put("spacing", b.spacing());
		meta.put("core", (double) EncounterMoment.CORE);
		meta.put("reach", (double) EncounterMoment.REACH);
		meta.put("count", (double) b.count);
		meta.put("frame", (double) ka);
		Map<String, Object> frames = new LinkedHashMap<>();
		frames.put("count", (double) count);
		frames.put("cycles", (double) CYCLES);
		frames.put("tick0", Math.round(tickAt(t.clock, c0) * 1000) / 1000.0);
		frames.put("cycle0", (double) c0);
		meta.put("frames", frames);
		Map<String, Object> stretch = new LinkedHashMap<>();
		stretch.put("from", (double) t.from);
		stretch.put("to", (double) t.to);
		meta.put("stretch", stretch);
		meta.put("why", t.why);
		if (t.killTick >= 0)
		{
			Map<String, Object> kill = new LinkedHashMap<>();
			kill.put("tick", (double) t.killTick);
			kill.put("frame", (double) frameOf(cycleAt(t.clock, t.killTick, fr), c0, count));
			kill.put("npc", t.npc);
			meta.put("kill", kill);
		}
		else
		{
			meta.put("kill", null);
		}
		if (t.fallTick >= 0)
		{
			Map<String, Object> fall = new LinkedHashMap<>();
			fall.put("tick", (double) t.fallTick);
			fall.put("frame", (double) frameOf(cycleAt(t.clock, t.fallTick, fr), c0, count));
			fall.put("npc", t.npc);
			meta.put("fall", fall);
		}
		else
		{
			meta.put("fall", null);
		}
		if (t.dropTick >= 0)
		{
			Map<String, Object> drop = new LinkedHashMap<>();
			drop.put("tick", (double) t.dropTick);
			drop.put("frame", (double) frameOf(cycleAt(t.clock, t.dropTick, fr), c0, count));
			meta.put("drop", drop);
		}
		else
		{
			meta.put("drop", null);
		}
		// (round three: the chest's opening, and its window as the game showed it, laid over from the loot's frame)
		Window w = t.window;
		byte[] picture = null;
		if (t.lootTick >= 0 && w != null)
		{
			int lf = frameOf(cycleAt(t.clock, t.lootTick, fr), c0, count);
			Map<String, Object> chest = new LinkedHashMap<>();
			chest.put("tick", (double) t.lootTick);
			chest.put("frame", (double) lf);
			chest.put("source", w.source);
			chest.put("kind", w.kind);
			meta.put("chest", chest);
		}
		else
		{
			meta.put("chest", null);
		}
		// (a corpse destroyed has no window: nothing came; round four)
		if (t.lootTick >= 0 && w != null && !"destroyed".equals(w.kind))
		{
			int lf = frameOf(cycleAt(t.clock, t.lootTick, fr), c0, count);
			Map<String, Object> win = new LinkedHashMap<>();
			win.put("kind", w.kind);
			win.put("source", w.source);
			win.put("group", w.group >= 0 ? (Object) (double) w.group : null);
			win.put("title", w.title);
			win.put("from", (double) lf);
			List<Object> its = new ArrayList<>();
			for (Loot l : w.items)
			{
				Map<String, Object> o = new LinkedHashMap<>();
				o.put("id", (double) l.id);
				o.put("qty", (double) l.qty);
				o.put("ge", (double) l.ge);
				o.put("ha", (double) l.ha);
				o.put("name", l.name);
				its.add(o);
			}
			win.put("items", its);
			byte[] png = w.picture;
			if (png != null && png.length > 0 && png.length <= PICTURE_MOST)
			{
				Map<String, Object> pic = new LinkedHashMap<>();
				pic.put("w", (double) w.pw);
				pic.put("h", (double) w.ph);
				pic.put("type", "image/png");
				win.put("picture", pic);
				picture = png;
			}
			meta.put("window", win);
		}
		else
		{
			meta.put("window", null);
		}
		// (round four, 11/21/2026: the pet, its message's frame, when it was seen, and its chat line as the game showed
		// it, the second picture block)
		byte[] chat = null;
		if (t.petTick >= 0)
		{
			Map<String, Object> pet = new LinkedHashMap<>();
			pet.put("tick", (double) t.petTick);
			pet.put("frame", (double) frameOf(cycleAt(t.clock, t.petTick, fr), c0, count));
			pet.put("seen", t.petSeenTick >= 0 ? (Object) (double) t.petSeenTick : null);
			pet.put("seenFrame", t.petSeenTick >= 0 ? (Object) (double) frameOf(cycleAt(t.clock, t.petSeenTick, fr), c0, count) : null);
			pet.put("kind", t.petKind);
			pet.put("name", t.petName);
			pet.put("npc", t.petNpc >= 0 ? (Object) (double) t.petNpc : null);
			pet.put("item", t.petItem >= 0 ? (Object) (double) t.petItem : null);
			Window pw = t.petWindow;
			pet.put("message", pw == null ? null : pw.title);
			if (pw != null)
			{
				Map<String, Object> win = new LinkedHashMap<>();
				win.put("kind", "chat");
				win.put("source", t.petName);
				win.put("group", null);
				win.put("title", pw.title);
				win.put("from", pet.get("frame"));
				win.put("items", new ArrayList<>());
				byte[] png = pw.picture;
				if (png != null && png.length > 0 && png.length <= PICTURE_MOST)
				{
					Map<String, Object> pic = new LinkedHashMap<>();
					pic.put("w", (double) pw.pw);
					pic.put("h", (double) pw.ph);
					pic.put("type", "image/png");
					win.put("picture", pic);
					chat = png;
				}
				pet.put("window", win);
			}
			meta.put("pet", pet);
		}
		else
		{
			meta.put("pet", null);
		}
		meta.put("labels", labels);
		meta.put("beams", beams);
		// (what it cost, for whoever reads the file: the client thread's share, the buffer)
		Map<String, Object> cost = new LinkedHashMap<>();
		cost.put("clientMs", Math.round(t.clientNanos / 1e5) / 10.0);
		cost.put("stillMs", Math.round(t.stillNanos / 1e5) / 10.0);
		cost.put("bufferKB", (double) (t.bufferBytes / 1024));
		cost.put("frames", (double) fr.size());
		cost.put("framesTaken", (double) t.framesTaken);
		cost.put("frameMs", t.framesTaken == 0 ? 0.0 : Math.round(t.clientNanos / 1e4 / t.framesTaken) / 100.0);
		meta.put("taken", cost);
		EncounterMoment.Moment m = b.moment(meta, count);
		m.picture = picture;
		m.chat = chat;
		return EncounterMoment.fit(m, EncounterMoment.MOST, null);
	}

	private static EncounterMoment.Pose pose(Sample s)
	{
		int n = s.xyz.length / 3;
		float[] x = new float[n], y = new float[n], z = new float[n];
		for (int v = 0; v < n; v++)
		{
			x[v] = s.xyz[v * 3];
			y[v] = s.xyz[v * 3 + 1];
			z[v] = s.xyz[v * 3 + 2];
		}
		return new EncounterMoment.Pose(x, y, z, s.alpha);
	}

	/** The cycle a tick of the recording began on (from the ticks seen; else the first frame of that tick). */
	static int cycleAt(List<int[]> clock, int tick, List<Frame> frames)
	{
		for (int[] c : clock)
		{
			if (c[0] == tick)
			{
				return c[1];
			}
		}
		for (Frame f : frames)
		{
			if (f.tick >= tick)
			{
				return f.cycle;
			}
		}
		return frames.get(frames.size() - 1).cycle;
	}

	/** The recording's tick at a cycle, fractional (straight between the ticks seen, 30 cycles a tick past them). */
	static double tickAt(List<int[]> clock, int cycle)
	{
		if (clock.isEmpty())
		{
			return 0;
		}
		int[] lo = null, hi = null;
		for (int[] c : clock)
		{
			if (c[1] <= cycle)
			{
				lo = c;
			}
			else if (hi == null)
			{
				hi = c;
			}
		}
		if (lo == null)
		{
			return clock.get(0)[0] + (cycle - clock.get(0)[1]) / 30.0;
		}
		if (hi == null)
		{
			return lo[0] + (cycle - lo[1]) / 30.0;
		}
		return lo[0] + (double) (cycle - lo[1]) / Math.max(1, hi[1] - lo[1]) * (hi[0] - lo[0]);
	}

	static int frameOf(int cycle, int c0, int count)
	{
		return Math.max(0, Math.min(count - 1, Math.round((cycle - c0) / (float) CYCLES)));
	}

	/** The Ground Items plugin's colour for a value (its defaults' tiers). */
	static String tier(long value)
	{
		return value > 10_000_000 ? "#ff66b2" : value > 1_000_000 ? "#ff9600" : value > 100_000 ? "#99ff99" : value > 20_000 ? "#66b2ff" : "#ffffff";
	}

	private static List<Object> list(Object... o)
	{
		List<Object> l = new ArrayList<>();
		for (Object v : o)
		{
			l.add(v instanceof Number ? (Object) ((Number) v).doubleValue() : v);
		}
		return l;
	}
}
