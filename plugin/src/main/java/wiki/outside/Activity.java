package wiki.outside;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What they're doing (11/08/2026, Jace's choice for the globe: the light's mood), never more exactly than one of these:
 * <ul>
 * <li>{"kind": "skilling", "skill": "Mining"}: the skill of their own latest experience drop, within about a minute;</li>
 * <li>{"kind": "boss", "boss": "Vorkath"}: in a fight with a boss on BOSSES (the NPC they're attacking, or one
 * attacking them), or inside a boss's own place (BOSS_REGIONS: the Gauntlets);</li>
 * <li>{"kind": "raid", "raid": "Theatre of Blood"}: in a raid (RAIDS, by where they are), unless in a boss fight;</li>
 * <li>{"kind": "minigame", "minigame": "the Inferno"}: in a minigame (MINIGAMES, by where they are), the same;</li>
 * <li>{"kind": "fighting"}: in a fight with anything else;</li>
 * <li>{"kind": "idle"}: none of those.</li>
 * </ul>
 * Raids and minigames (11/09/2026, Jace: "include all activities that utilize instances") are told by the region the
 * player's in, an instance's as its template lies, as RuneLite's own Discord plugin tells them (its DiscordGameEventType's
 * region ids): cheap, and the same every time. The PvP minigames (Last Man Standing, Castle Wars, Clan Wars, Soul Wars,
 * the Fight Pits, Emir's Arena) are minigames like the rest (11/10/2026, Jace: "they are just minigames").
 * Off by default (OutsideConfig.activity). It's only ever the player's own: their own experience, the NPC in their own
 * fight, by its name, and only when it's a boss, and the region they're in. The label goes through Behind, as following's
 * square does: two minutes behind, at most once a minute, never from anywhere dangerous, cleared there at once; in the
 * Wilderness only with "In the Wilderness too" on (11/10/2026: OutsidePlugin.activitySafe), and never where they are.
 * Kept apart from RuneLite so it's tested; the plugin feeds it from StatChanged and HitsplatApplied.
 */
class Activity
{
	/** skilling: the latest experience drop within this long; fighting: a hit, theirs or on them, within this long */
	static final long SKILL_MS = 60_000, FIGHT_MS = 15_000;
	/** A boss fight holds this long after its last hit (11/10/2026: the Giant Mole digs away and is chased, and its
	 *  fight read as idle every other minute); any other fight, FIGHT_MS */
	static final long BOSS_MS = 60_000;

	/** the skills (RuneLite's Skill names, all but the total) */
	static final Set<String> SKILLS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
		"Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting",
		"Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving",
		"Slayer", "Farming", "Runecraft", "Hunter", "Construction", "Sailing")));
	/** experience in these during a fight is the fight's, not skilling */
	static final Set<String> COMBAT = new HashSet<>(Arrays.asList(
		"Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Magic", "Slayer"));

	/**
	 * The bosses (11/08/2026; made whole 11/10/2026, Jace: "make sure you have the most up-to-date list"): each NPC's
	 * name as the game has it (RuneLite's NpcID names), and the label that goes. Every boss on the official hiscores
	 * (RuneLite's HiscoreSkill, read 11/10/2026) is here, but for the raids (RAIDS) and Tempoross, Wintertodt and Zalcano
	 * (MINIGAMES, as the minigames they are); a boss of several NPCs goes as one label. The relays take only these labels
	 * (game.ts's ACTIVITY_BOSSES, relay.py's; relay-py/test_relay.py checks the three lists match). Those fought in an
	 * instance (Vorkath, Zulrah, Zuk...) keep their labels there (11/09/2026). A name not here, or spelled otherwise in
	 * the game, is "fighting" (or the raid or minigame it's in).
	 */
	static final Map<String, String> BOSSES;

	/**
	 * The Wilderness's bosses (11/10/2026, Jace: "Wilderness bosses should be included since we have the option for
	 * 'In the Wilderness too'"): on BOSSES like the rest, but the plugin sends anything from the Wilderness only with
	 * that setting on (OutsidePlugin.activitySafe), and never where they are.
	 */
	static final Set<String> WILDERNESS_BOSSES = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
		"Artio", "Callisto", "Calvar'ion", "Chaos Elemental", "Chaos Fanatic", "Crazy archaeologist", "Scorpia",
		"Spindel", "Venenatis", "Vet'ion")));

	static
	{
		Map<String, String> b = new LinkedHashMap<>();
		for (String n : new String[]{"Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Bryophyta", "Cerberus",
			"Commander Zilyana", "Corporeal Beast", "Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme",
			"Deranged archaeologist", "Duke Sucellus", "General Graardor", "Giant Mole", "Hespori", "Kalphite Queen",
			"King Black Dragon", "Kraken", "Kree'arra", "K'ril Tsutsaroth", "Mad Angel", "Maggot King", "Nex", "Obor",
			"Phantom Muspah", "Phosani's Nightmare", "Sarachnis", "Scurrius", "Shellbane gryphon", "Skotizo", "Sol Heredit",
			"The Leviathan", "The Mimic", "The Nightmare", "The Whisperer", "Thermonuclear smoke devil", "TzKal-Zuk",
			"TzTok-Jad", "Vardorvis", "Vorkath", "Yama", "Zulrah"})
		{
			b.put(n, n);
		}
		// (the Wilderness's, WILDERNESS_BOSSES)
		for (String n : new String[]{"Artio", "Callisto", "Calvar'ion", "Chaos Elemental", "Chaos Fanatic",
			"Crazy archaeologist", "Scorpia", "Spindel", "Venenatis", "Vet'ion"})
		{
			b.put(n, n);
		}
		// (a boss of several NPCs, or of one NPC by several names, as one)
		for (String n : new String[]{"Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested",
			"Karil the Tainted", "Torag the Corrupted", "Verac the Defiled"})
		{
			b.put(n, "Barrows");
		}
		b.put("Dusk", "Grotesque Guardians");
		b.put("Dawn", "Grotesque Guardians");
		b.put("Branda the Fire Queen", "Royal Titans");
		b.put("Eldric the Ice King", "Royal Titans");
		b.put("Blue Moon", "Moons of Peril");
		b.put("Eclipse Moon", "Moons of Peril");
		b.put("Blood Moon", "Moons of Peril");
		b.put("Brutus", "Brutus");
		b.put("Demonic Brutus", "Brutus");
		b.put("Doom of Mokhaiotl", "Doom of Mokhaiotl");
		b.put("Doom of Mokhaiotl (Shielded)", "Doom of Mokhaiotl");
		b.put("Doom of Mokhaiotl (Burrowed)", "Doom of Mokhaiotl");
		b.put("The Hueycoatl", "The Hueycoatl");
		b.put("Hueycoatl tail", "The Hueycoatl");
		b.put("Hueycoatl tail (broken)", "The Hueycoatl");
		b.put("Hueycoatl body", "The Hueycoatl");
		// (the Gauntlet's Hunllef; inside the Gauntlet, the whole run is its boss's anyway: BOSS_REGIONS)
		b.put("Crystalline Hunllef", "the Gauntlet");
		b.put("Corrupted Hunllef", "the Corrupted Gauntlet");
		BOSSES = Collections.unmodifiableMap(b);
	}

	/** Bosses by where they are (11/10/2026, Jace: "The Gauntlet and the Corrupted Gauntlet are bosses"): inside, the
	 *  whole run is the boss's, the preparation too, as the hiscores count it (RuneLite's DiscordGameEventType's region
	 *  ids for each one's instance; not the lobby in Prifddinas, which the two share) */
	static final Map<Integer, String> BOSS_REGIONS = regions(
		"the Gauntlet", new int[]{7512},
		"the Corrupted Gauntlet", new int[]{7768});

	/** the raids, by their regions (RuneLite's DiscordGameEventType: its Jaltevas Pyramid and Osmumten's Burial Chamber,
	 *  the Tombs' way in and way out, as the Tombs) */
	static final Map<Integer, String> RAIDS = regions(
		"Chambers of Xeric", new int[]{12889, 13136, 13137, 13138, 13139, 13140, 13141, 13145, 13393, 13394, 13395, 13396,
			13397, 13401},
		"Theatre of Blood", new int[]{12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379},
		"Tombs of Amascut", new int[]{14160, 14162, 14164, 14674, 14676, 15184, 15186, 15188, 15696, 15698, 15700, 13454,
			14672},
		"The Fractured Archive", new int[]{16214, 16726, 16215, 16727, 16216, 16728, 16472, 16474});

	/** the minigames, by their regions (RuneLite's DiscordGameEventType's, less the rat pits and the games room;
	 *  Tempoross, Wintertodt and Zalcano, which it counts as bosses, here as the minigames they are, 11/10/2026; the
	 *  Gauntlets are bosses, BOSS_REGIONS). The PvP minigames (11/10/2026, Jace: "they are just minigames") are here
	 *  too: PVP_MINIGAMES */
	static final Map<Integer, String> MINIGAMES = regions(
		"Barbarian Assault", new int[]{7508, 7509, 10322},
		"Blast Furnace", new int[]{7757},
		"Brimhaven Agility Arena", new int[]{11157},
		"Castle Wars", new int[]{9520, 9620},
		"Clan Wars", new int[]{12621, 12622, 12623, 13130, 13131, 13133, 13134, 13135, 13386, 13387, 13390, 13641, 13642,
			13643, 13644, 13645, 13646, 13647, 13899, 13900, 14155, 14156},
		"Emir's Arena", new int[]{13362, 13363},
		"Fishing Trawler", new int[]{7499},
		"Giants' Foundry", new int[]{13491},
		"Guardians of the Rift", new int[]{14484},
		"Hallowed Sepulchre", new int[]{8797, 9051, 9052, 9053, 9054, 9309, 9563, 9565, 9821, 10074, 10075, 10077},
		"Last Man Standing", new int[]{13658, 13659, 13660, 13914, 13915, 13916, 13918, 13919, 13920, 14174, 14175, 14176,
			14430, 14431, 14432},
		"Mage Training Arena", new int[]{13462, 13463},
		"Nightmare Zone", new int[]{9033},
		"Pest Control", new int[]{10536},
		"Pyramid Plunder", new int[]{7749},
		"Rogues' Den", new int[]{11854, 11855, 12109, 12110, 12111},
		"Sorceress's Garden", new int[]{11605},
		"Soul Wars", new int[]{8493, 8748, 8749, 9005},
		"Tempoross", new int[]{12076},
		"Temple Trekking", new int[]{8014, 8270, 8256, 8782, 9038, 9294, 9550, 9806},
		"the Colosseum", new int[]{7216, 7316},
		"the Fight Caves", new int[]{9551},
		"the Fight Pits", new int[]{9552},
		"the Inferno", new int[]{9043},
		"Tithe Farm", new int[]{7222},
		"Trouble Brewing", new int[]{15150},
		"Volcanic Mine", new int[]{15263, 15262},
		"Wintertodt", new int[]{6462},
		"Zalcano", new int[]{12126});

	/** The PvP minigames (11/10/2026, Jace: "there is no danger to someone tracking them there"): ordinary minigames on
	 *  MINIGAMES, and in their regions the game's PvP-area flag doesn't hold the globe's features back (the Wilderness,
	 *  PvP and Deadman worlds still do: OutsidePlugin.globeSafe). None of their regions lies in the Wilderness. */
	static final Set<String> PVP_MINIGAMES = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
		"Castle Wars", "Clan Wars", "Emir's Arena", "Last Man Standing", "Soul Wars", "the Fight Pits")));

	/** PVP_MINIGAMES's regions */
	static final Set<Integer> PVP_MINIGAME_REGIONS;

	static
	{
		Set<Integer> r = new HashSet<>();
		for (Map.Entry<Integer, String> e : MINIGAMES.entrySet())
		{
			if (PVP_MINIGAMES.contains(e.getValue()))
			{
				r.add(e.getKey());
			}
		}
		PVP_MINIGAME_REGIONS = Collections.unmodifiableSet(r);
	}

	private static Map<Integer, String> regions(Object... labelsAndRegions)
	{
		Map<Integer, String> m = new HashMap<>();
		for (int i = 0; i < labelsAndRegions.length; i += 2)
		{
			for (int r : (int[]) labelsAndRegions[i + 1])
			{
				// (a region is one place's only)
				if (m.put(r, (String) labelsAndRegions[i]) != null)
				{
					throw new IllegalArgumentException("region " + r + " twice");
				}
			}
		}
		return Collections.unmodifiableMap(m);
	}

	/** each skill's experience as last seen (the first look at a skill, as they log in, is only its starting point) */
	private final Map<String, Integer> xp = new HashMap<>();
	private String skill = null;
	private long skillAt = -1;
	private long fightAt = -1;
	private String boss = null;
	private int region = -1;

	/** Nightmare Zone shares its region with the King Black Dragon's lair: KBD's on plane 0, the Zone above it (as
	 *  RuneLite's Discord plugin tells them apart, 11/09/2026) */
	static final int NMZ_OR_KBD = 9033;

	/** The region they're in this tick (an instance's as its template lies) and its plane, for the raids and
	 *  minigames. */
	void region(int r, int plane)
	{
		region = r == NMZ_OR_KBD && plane == 0 ? -1 : r;
	}

	/** A skill's experience (StatChanged): a drop in it is skilling, unless it's a fighting skill's during a fight. */
	void xp(long now, String name, int value)
	{
		if (!SKILLS.contains(name))
		{
			return;
		}
		Integer had = xp.put(name, value);
		if (had == null || value <= had)
		{
			return;
		}
		if (COMBAT.contains(name) && fighting(now))
		{
			return;
		}
		skill = name;
		skillAt = now;
	}

	/** A hit in their own fight (theirs on an NPC they're attacking, or one on them), and the NPC's name if known. */
	void hit(long now, String npc)
	{
		// (a hit whose NPC isn't known, in a boss fight going on, is still the boss's)
		if (npc != null)
		{
			boss = BOSSES.get(npc);
		}
		else if (!(boss != null && now - fightAt < BOSS_MS))
		{
			boss = null;
		}
		fightAt = now;
	}

	private boolean fighting(long now)
	{
		return fightAt >= 0 && now - fightAt < FIGHT_MS;
	}

	/** What they're doing now, as it goes (Behind sends it two minutes later). */
	JsonObject now(long now)
	{
		JsonObject a = new JsonObject();
		// (a boss fight first, wherever it is: Jad in the Fight Caves is Jad; then a boss's own place (the Gauntlets, the
		// preparation too), a raid or a minigame they're in)
		String place = BOSS_REGIONS.get(region), raid = RAIDS.get(region), minigame = MINIGAMES.get(region);
		if (boss != null && fightAt >= 0 && now - fightAt < BOSS_MS)
		{
			a.addProperty("kind", "boss");
			a.addProperty("boss", boss);
		}
		else if (place != null)
		{
			a.addProperty("kind", "boss");
			a.addProperty("boss", place);
		}
		else if (raid != null)
		{
			a.addProperty("kind", "raid");
			a.addProperty("raid", raid);
		}
		else if (minigame != null)
		{
			a.addProperty("kind", "minigame");
			a.addProperty("minigame", minigame);
		}
		else if (fighting(now))
		{
			a.addProperty("kind", "fighting");
		}
		else if (skill != null && now - skillAt < SKILL_MS)
		{
			a.addProperty("kind", "skilling");
			a.addProperty("skill", skill);
		}
		else
		{
			a.addProperty("kind", "idle");
		}
		return a;
	}

	/** Forget everything (switched off, or logged out: the next login's experience is a new starting point). */
	void forget()
	{
		xp.clear();
		skill = null;
		skillAt = -1;
		fightAt = -1;
		boss = null;
		region = -1;
	}

	/** The labels a raid and a minigame go as, sorted (for the relays' lists). */
	static Set<String> raidLabels()
	{
		return new TreeSet<>(RAIDS.values());
	}

	static Set<String> minigameLabels()
	{
		return new TreeSet<>(MINIGAMES.values());
	}

	/** The labels a boss goes as, sorted (for the relays' list): by its NPC or by where it is. */
	static Set<String> bossLabels()
	{
		Set<String> all = new TreeSet<>(BOSSES.values());
		all.addAll(BOSS_REGIONS.values());
		return all;
	}
}
