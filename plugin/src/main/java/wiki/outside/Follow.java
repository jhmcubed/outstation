package wiki.outside;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Following (11/08/2026, Jace: "add plugin following with those safeguards"): while it's on, the rough area the player
 * was in goes to the relay, for their outstation's light on outside.wiki's globe to travel to. The safeguards:
 * <ul>
 * <li>only a square of the map 128 tiles a side (about a town and its surroundings), never the tile;</li>
 * <li>two minutes behind: what's sent is where they were two minutes ago, at most once a minute;</li>
 * <li>never somewhere dangerous: in the Wilderness (whatever "In the Wilderness too" says), a PvP area, on a PvP or
 * Deadman world, nothing's recorded, and the moment they're there the area already sent is cleared (the PvP minigames
 * and Ferox Enclave aren't dangerous, 11/10/2026: OutsidePlugin.globeSafe);</li>
 * <li>never anywhere off the main map (Sailing's seas round it are the map's, X0 and Y0, and a boat's where it is at sea:
 * OutsidePlugin.onMainWorld; an underground area is counted where it lies under; Zanaris as one place, the
 * globe's moon; an instance where its template lies, or off the map its entrance, ENTRANCES; anything else,
 * nothing).</li>
 * </ul>
 * Off by default (OutsideConfig.follow), and the relay shows it only while their outstation's transmitting and open.
 * The client's thread calls tick() each game tick with where the player is; what it gives back, if anything, is sent.
 * With "and my world" on too (OutsideConfig.followWorld, 11/08/2026), the world they were on goes with the square.
 * The timing and the clearing are Behind's (11/08/2026), shared with the activity.
 */
class Follow
{
	static final int SQUARE = 128;
	static final long BEHIND_MS = Behind.BEHIND_MS, EVERY_MS = Behind.EVERY_MS, AGAIN_MS = Behind.AGAIN_MS;
	/**
	 * The main map's bounds (the surface; an underground area is 6400 tiles north of what's above it). Sailing (11/19/2025)
	 * put a band of sea round the old map, which ran x 1024-3967, y 2496-4159: x 960-1023 to the west, 3968-4031 to the
	 * east, y 2048-2495 to the south (the new islands: Sunbleak Island, Last Light, Charred Island, the Great Conch,
	 * Wyrmscraig and its Mad Angel) and 4160-4223 to the north (11/10/2026, Jace: "do we support location tracking when
	 * people are out at sea?"). Measured from the game's own map squares (the OSRS cache, archive.openrs2.org: 2341 of
	 * 10/29/2025 against 2363 of launch day and 2757 of 11/03/2026, the newest, which adds nothing past them), and the same
	 * as Shortest Path's collision map (regions x 15-62, y 32-65). X1 and Y1 stay on a square's edge.
	 */
	static final int X0 = 960, X1 = 4096, Y0 = 2048, Y1 = 4224, UNDER = 6400;
	/** Underground goes as the square above it under the map as it was before Sailing (y 2496 up): the older squares
	 *  just south of that band (x 3072-3775, y 8576-8831) lie under no sea, so they stay off the map... */
	static final int UNDER_Y0 = 2496;
	/** ...but the caves of Sailing's southern islands lie in it, 6400 tiles north of their islands, so those regions go as
	 *  the square above them, as any other underground: Sunbleak Cave (8840; the wiki's map, 2227, 8738, under the island
	 *  at 2209, 2330), Wyrmscraig Cavern (10374, 10375; 2590, 8615, under Wyrmscraig at 2532, 2215), the Charred Dungeon
	 *  (10634, 10890; its music's area, 2624-2751, 8832-8895, under Charred Island at 2648, 2406) and the Great Conch's
	 *  caves (12426, 12682, 12938, 13194; its gryphons at 3084-3219, 8841-8878 and its coral at 3285, 8859, under the
	 *  Conch at 3200, 2435). Each is a region the cache first holds with Sailing. Its other new regions there, whose places
	 *  aren't known, and everything else south of UNDER_Y0, go as nothing. */
	static final Set<Integer> SEA_CAVES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(8840, 10374, 10375,
		10634, 10890, 12426, 12682, 12938, 13194)));
	/** Zanaris (Jace, 11/08/2026: shown on the globe's moon), as one place whatever its tile: the fairy rings and the
	 *  shed both land in this box, and all of it goes as the one square ZANARIS, never more exactly */
	static final int ZX0 = 2368, ZX1 = 2496, ZY0 = 4352, ZY1 = 4480;
	static final int[] ZANARIS = {2368, 4416};
	/** Prifddinas (11/10/2026, Jace: "place prifddinas where the world map shows it"): the city's tiles lie off the main
	 *  map (x 3136-3391, y 5952-6207), but the world map draws it inside its gates in Tirannwn. The gates (Shortest Path's
	 *  transports: north 3264,6135 to 2240,3386; south 3264,6024 to 2240,3269; west 3208,6080 to 2181,3328; east
	 *  3319,6080 to 2298,3328) put the city 1024 tiles west and 2752 south of its tiles, give or take three, so a tile in
	 *  the city goes as the square it lies over there */
	static final int PX0 = 3136, PX1 = 3392, PY0 = 5952, PY1 = 6208, PDX = 1024, PDY = 2752;
	/** the city's middle there, for what lies in Prifddinas but outside the city's own tiles */
	static final int[] PRIFDDINAS = {2240, 3328};

	/**
	 * Instances (11/09/2026, Jace: "allow instances, everyone knows where those instances get opened"): an instance goes
	 * as where its template lies on the map (the plugin reads it with WorldPoint.fromLocalInstance, as RuneLite's Discord
	 * plugin does). Most lie on the main map and need nothing more. These lie off it, so they go as the square of their
	 * entrance, by the template's region (RuneLite's DiscordGameEventType's region ids). Any other place off the map
	 * (the Leviathan, the Whisperer, Duke Sucellus, the Phantom Muspah, the
	 * Abyssal Sire, the Corporeal Beast, the Fractured Archive...) goes as nothing, as before.
	 */
	static final Map<Integer, int[]> ENTRANCES;

	static
	{
		Map<Integer, int[]> e = new HashMap<>();
		// (the Inferno and the Fight Caves: TzHaar's way in, inside the Karamja volcano)
		put(e, new int[]{2880, 3136}, 9043, 9551);
		// (the Chambers of Xeric: Mount Quidamortem, its bank)
		put(e, new int[]{1216, 3520}, 12889, 13136, 13137, 13138, 13139, 13140, 13141, 13145, 13393, 13394, 13395, 13396,
			13397, 13401);
		// (the Theatre of Blood: Ver Sinhaza)
		put(e, new int[]{3648, 3264}, 12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379);
		// (the Tombs of Amascut: the Jaltevas pyramid in the Necropolis, the square its lobby lies under)
		put(e, new int[]{3392, 2752}, 14160, 14162, 14164, 14672, 14674, 14676, 15184, 15186, 15188, 15696, 15698, 15700);
		// (the Grotesque Guardians: the Slayer Tower's roof)
		put(e, new int[]{3392, 3520}, 6727);
		// (Prifddinas's own, 11/10/2026: the Gauntlet's lobby and the Gauntlet, Corrupted too, and Zalcano's mine, in the city's
		// middle where the world map draws it)
		put(e, square(PRIFDDINAS[0], PRIFDDINAS[1]), 12127, 7512, 7768, 12126);
		// (the Giant Mole's lair, 11/10/2026, Jace: "it's under falador park": its tiles lie off the map, around 1752,5237,
		// where Falador Park's mole hills lead (Shortest Path's transports); Falador Park's square)
		put(e, new int[]{3008, 3392}, 6992, 6993);
		// (the God Wars Dungeon, each general's room: its way in, in Trollheim)
		put(e, new int[]{2880, 3776}, 11346, 11347, 11601, 11602, 11603);
		ENTRANCES = Collections.unmodifiableMap(e);
	}

	private static void put(Map<Integer, int[]> e, int[] at, int... regions)
	{
		for (int r : regions)
		{
			e.put(r, at);
		}
	}

	/** A point's region, as RuneLite counts them (64 tiles a side). */
	static int region(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	/** Where a point goes: its square (square()), or off the map, its entrance's (ENTRANCES); null for neither. */
	static int[] place(int x, int y)
	{
		// (Prifddinas: where the world map draws it, in Tirannwn)
		if (x >= PX0 && x < PX1 && y >= PY0 && y < PY1)
		{
			return square(x - PDX, y - PDY);
		}
		int[] sq = square(x, y);
		if (sq != null)
		{
			return sq;
		}
		int[] at = ENTRANCES.get(region(x, y));
		return at == null ? null : at.clone();
	}

	/** the two minutes behind, the minute between, the clearing (Behind: the activity keeps the same ones) */
	private final Behind behind = new Behind("where");

	/** A tick, without the world (OutsideConfig.followWorld off). */
	JsonObject tick(long now, int x, int y, boolean safe)
	{
		return tick(now, x, y, 0, safe);
	}

	/**
	 * A tick: where the player is (x, y on the map, an instance's as its template lies; safe false somewhere
	 * dangerous), and the world
	 * they're on (0 to leave it out: "and my world", 11/08/2026, is off). Gives what to send ({"where": {"x", "y"}},
	 * with "world" when it's given, or {"where": null}), or null for nothing now. The world is part of the place, so
	 * the square and the world that go are always from the same moment, two minutes ago.
	 */
	JsonObject tick(long now, int x, int y, int world, boolean safe)
	{
		int[] sq = safe ? place(x, y) : null;
		// (safe but nowhere on the map, as in a player-owned house: the clock pauses, 11/10/2026)
		if (safe && sq == null)
		{
			return behind.paused(now);
		}
		JsonObject w = null;
		if (sq != null)
		{
			w = new JsonObject();
			w.addProperty("x", sq[0]);
			w.addProperty("y", sq[1]);
			if (world > 0)
			{
				w.addProperty("world", world);
			}
		}
		return behind.tick(now, w, safe);
	}

	/** Following turned off, or the connection's new: what was sent goes (once). */
	JsonObject clear()
	{
		return behind.clear();
	}

	/** A new connection: the relay may not have it, so the next one due goes even if it's the same square. */
	void again()
	{
		behind.again();
	}

	/** Forget everything (following turned off): nothing old is sent once it's on again. */
	void forget()
	{
		behind.forget();
	}

	/** The middle of the square a point's in, on the main map (the sea round it too, 11/10/2026), or Zanaris as one
	 *  place; null anywhere else. */
	static int[] square(int x, int y)
	{
		if (x >= ZX0 && x < ZX1 && y >= ZY0 && y < ZY1)
		{
			return ZANARIS.clone();
		}
		int[] t = onMap(x, y);
		return t == null ? null : new int[]{(t[0] / SQUARE) * SQUARE + SQUARE / 2, (t[1] / SQUARE) * SQUARE + SQUARE / 2};
	}

	/** A point as the tile of the main map it lies on: itself, or underground the tile above it (as square() counts
	 *  it); null off the map. */
	static int[] onMap(int x, int y)
	{
		if ((y >= UNDER_Y0 + UNDER && y < Y1 + UNDER) || (y >= Y0 + UNDER && y < Y1 + UNDER && SEA_CAVES.contains(region(x, y))))
		{
			y -= UNDER;
		}
		if (x < X0 || x >= X1 || y < Y0 || y >= Y1)
		{
			return null;
		}
		return new int[]{x, y};
	}

	/**
	 * The exact tile, for the player's own record (11/10/2026, Track: "Record my exact path for my year"): place()'s
	 * rules, but the tile rather than its square. Prifddinas where the world map draws it; Zanaris as its one place;
	 * a tile of the main map or the sea, underground as the tile above it; off the map, an instance's entrance (its
	 * square's middle, ENTRANCES); null anywhere else.
	 */
	static int[] tile(int x, int y)
	{
		if (x >= PX0 && x < PX1 && y >= PY0 && y < PY1)
		{
			return onMap(x - PDX, y - PDY);
		}
		if (x >= ZX0 && x < ZX1 && y >= ZY0 && y < ZY1)
		{
			return ZANARIS.clone();
		}
		int[] t = onMap(x, y);
		if (t != null)
		{
			return t;
		}
		int[] at = ENTRANCES.get(region(x, y));
		return at == null ? null : at.clone();
	}
}
