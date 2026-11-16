package wiki.outside;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What they're doing, as RuneLite's own Discord plugin shows it (11/16/2026, the Plugin Hub's answer: "matching Discord
 * activity should be okay"; ghillie's claude/outstation.md, "What RuneLite's Discord plugin shares";
 * docs/protocol.md, "The snapshot"), never more:
 * <ul>
 * <li>the skill being trained: the player's own experience going up (the first reading of each skill after a login only
 * remembered), shown till 5 minutes pass with no more of it, Slayer and Fishing above the rest; any skill but
 * Hitpoints, which Discord's never names;</li>
 * <li>the area by Discord's own name and group (AREAS: its DiscordGameEventType's areas and regions, written here by
 * plugin/discord_areas.py), read at each scene load from the region the player stands in, an instance's as its template
 * lies; Nightmare Zone's region on the ground floor (the King Black Dragon's lair) as no area, as Discord has it;</li>
 * <li>a new scene clears the skill, as Discord's area event does.</li>
 * </ul>
 * Less than Discord: no time or version; on a PvP or Deadman world, in the Wilderness or a PvP area, neither (both null,
 * "in game", as an area off the list), cleared at once. Sent only as it changes, at most once a minute (the latest when
 * the minute's up). With an area, one fixed point of it for the globe (at): the region of it nearest its regions'
 * middle (underground as the place above it, as following counts it), or off the map its entrance (ENTRANCES,
 * following's own), or none. The point is a function of the name, so it never says more than the name does.
 * Kept apart from RuneLite so it can be tested.
 */
final class Presence
{
	static final String BOSS = "boss", CITY = "city", DUNGEON = "dungeon", MINIGAME = "minigame", RAID = "raid",
		REGION = "region";
	/** a skill shows this long after its last experience drop (Discord's "Activity timeout": 5 minutes by default) */
	static final long SKILL_MS = 5 * 60_000L;
	/** a change goes at most this often (the latest when it's up); clearing somewhere dangerous goes at once */
	static final long EVERY_MS = 60_000L;
	/** Nightmare Zone's region, which the King Black Dragon's lair shares on the ground floor */
	static final int NMZ = 9033;
	/** the skills Discord names (all but Hitpoints and the total) */
	static final Set<String> SKILLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("Attack", "Defence",
		"Strength", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting",
		"Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecraft", "Hunter", "Construction",
		"Sailing")));

	/** An area as Discord names it: its name, its group, its regions, and the globe's point for it (or null). */
	static final class Area
	{
		final String name, kind;
		final int[] regions;
		int[] at;

		Area(String name, String kind, int[] regions)
		{
			this.name = name;
			this.kind = kind;
			this.regions = regions;
		}
	}

	private static Area a(String name, String kind, int... regions)
	{
		return new Area(name, kind, regions);
	}

	/** RuneLite's DiscordGameEventType's areas (runelite/runelite master, read 11/16/2026; BSD 2-Clause, Copyright (c)
	 *  2018, Tomas Slusny: README.md, "Credits"), written by plugin/discord_areas.py: never by hand. */
	static final Area[] AREAS = {
	// discord:areas
		a("Abyssal Sire", BOSS, 11851, 11850, 12363, 12362),
		a("Araxxor", BOSS, 14489),
		a("Cerberus", BOSS, 4883, 5140, 5395),
		a("Commander Zilyana", BOSS, 11602),
		a("Corporeal Beast", BOSS, 11842, 11844),
		a("Dagannoth Kings", BOSS, 11588, 11589),
		a("Duke Sucellus", BOSS, 12132),
		a("General Graardor", BOSS, 11347),
		a("Giant Mole", BOSS, 6993, 6992),
		a("Grotesque Guardians", BOSS, 6727),
		a("Hespori", BOSS, 5021),
		a("Alchemical Hydra", BOSS, 5536),
		a("Kalphite Queen", BOSS, 13972),
		a("Kraken", BOSS, 9116),
		a("Kree'arra", BOSS, 11346),
		a("K'ril Tsutsaroth", BOSS, 11603),
		a("Nex", BOSS, 11601),
		a("Nightmare of Ashihama", BOSS, 15515),
		a("Phantom Muspah", BOSS, 11330),
		a("Sarachnis", BOSS, 7322),
		a("Skotizo", BOSS, 9048),
		a("Thermonuclear smoke devil", BOSS, 9363, 9619),
		a("Tempoross", BOSS, 12076),
		a("The Leviathan", BOSS, 8291),
		a("The Royal Titans", BOSS, 11669),
		a("The Whisperer", BOSS, 10595),
		a("Vardorvis", BOSS, 4405),
		a("Vorkath", BOSS, 9023),
		a("Wintertodt", BOSS, 6462),
		a("Yama", BOSS, 6045),
		a("Zalcano", BOSS, 12126),
		a("Zulrah", BOSS, 9007),
		a("Al Kharid", CITY, 13105, 13106),
		a("Arceuus", CITY, 6458, 6459, 6460, 6714, 6715),
		a("Ardougne", CITY, 9779, 9780, 10035, 10036, 10291, 10292, 10547, 10548),
		a("Bandit Camp", CITY, 12590),
		a("Barbarian Outpost", CITY, 10039),
		a("Barbarian Village", CITY, 12341),
		a("Bedabin Camp", CITY, 12591),
		a("Brimhaven", CITY, 11057, 11058),
		a("Burgh de Rott", CITY, 13874, 13873, 14130, 14129),
		a("Burthorpe", CITY, 11319, 11575),
		a("Cam Torum", CITY, 5525, 5780, 5781, 6037),
		a("Canifis", CITY, 13878),
		a("Catherby", CITY, 11317, 11318, 11061),
		a("Civitas Illa Fortis", CITY, 6448, 6449, 6704, 6705, 6960, 6961),
		a("Corsair Cove", CITY, 10028, 10284),
		a("Darkmeyer", CITY, 14388, 14644),
		a("Dorgesh-Kaan", CITY, 10835, 10834),
		a("Draynor", CITY, 12338, 12339),
		a("Edgeville", CITY, 12342),
		a("Entrana", CITY, 11060, 11316),
		a("Etceteria", CITY, 10300),
		a("Falador", CITY, 11828, 11572, 11827, 12084),
		a("Gu'Tanoth", CITY, 10031),
		a("Gwenith", CITY, 8757),
		a("Hosidius", CITY, 6710, 6711, 6712, 6455, 6456, 6966, 6967, 6968, 7221, 7223, 7224, 7478, 7479),
		a("Jatizso", CITY, 9531),
		a("Keldagrim", CITY, 11423, 11422, 11679, 11678),
		a("Land's End", CITY, 5941),
		a("Lassar Undercity", CITY, 9314, 9315, 9316, 9571, 9572, 9828, 10338, 10339, 10340, 10596, 10852),
		a("Lletya", CITY, 9265, 11103),
		a("Lovakengj", CITY, 5692, 5691, 5947, 6203, 6202, 5690, 5946),
		a("Lumbridge", CITY, 12850),
		a("Lunar Isle", CITY, 8253, 8252, 8509, 8508),
		a("Marim", REGION, 11051),
		a("Meiyerditch", CITY, 14132, 14387, 14386, 14385),
		a("Menaphos", CITY, 12843),
		a("Miscellania", CITY, 10044),
		a("Mor Ul Rek", CITY, 9808, 9807, 10064, 10063),
		a("Mort'ton", CITY, 13875),
		a("Mos Le'Harmless", CITY, 14637, 14638, 14639, 14894, 14895, 15151, 15406, 15407),
		a("Mount Karuulm", CITY, 5179, 4923, 5180),
		a("Mountain Camp", CITY, 11065),
		a("Mynydd", CITY, 8501),
		a("Nardah", CITY, 13613),
		a("Neitiznot", CITY, 9275),
		a("Port Piscarilius", CITY, 6969, 6971, 7227, 6970, 7225, 7226),
		a("Piscatoris", CITY, 9273),
		a("Pollnivneach", CITY, 13358),
		a("Port Khazard", CITY, 10545),
		a("Port Phasmatys", CITY, 14646),
		a("Port Sarim", CITY, 12081, 12082),
		a("Prifddinas", CITY, 8499, 8500, 8755, 8756, 9011, 9012, 9013, 12894, 12895, 13150, 13151),
		a("Rellekka", CITY, 10297, 10553),
		a("Rimmington", CITY, 11826, 11570),
		a("Seers' Village", CITY, 10806),
		a("Shayzien", CITY, 5944, 5943, 6200, 6199, 5686, 5687, 5688, 5689, 5945),
		a("Shilo Village", CITY, 11310),
		a("Slepe", CITY, 14643, 14899, 14900, 14901),
		a("Sophanem", CITY, 13099),
		a("Tai Bwo Wannai", CITY, 11056, 11055),
		a("Taverley", CITY, 11574, 11573),
		a("Tree Gnome Stronghold", CITY, 9525, 9526, 9782, 9781),
		a("Tree Gnome Village", CITY, 10033),
		a("Troll Stronghold", CITY, 11321, 11421),
		a("Uzer", CITY, 13872),
		a("Uzer Oasis", CITY, 13871),
		a("Varrock", CITY, 12596, 12597, 12852, 12853, 12854, 13108, 13109, 13110),
		a("Ver Sinhaza", CITY, 14642),
		a("Void Knights' Outpost", CITY, 10537),
		a("Weiss", CITY, 11325, 11581),
		a("Witchaven", CITY, 10803),
		a("Yanille", CITY, 10288, 10032),
		a("Zanaris", CITY, 9285, 9541, 9540, 9797),
		a("Zul-Andra", CITY, 8495, 8751),
		a("Abandoned Mine", DUNGEON, 13618, 13718, 11079, 11078, 11077, 10823, 10822, 10821),
		a("Ah Za Rhoon", DUNGEON, 11666),
		a("Ancient Cavern", DUNGEON, 6483, 6995),
		a("Ape Atoll Dungeon", DUNGEON, 11150, 10894),
		a("Ape Atoll Banana Plantation", DUNGEON, 10895),
		a("West Ardougne Basement", DUNGEON, 10135),
		a("Ardougne Sewers", DUNGEON, 10134, 10136, 10391, 10647),
		a("Asgarnian Ice Caves", DUNGEON, 11925, 12181),
		a("Tomb of Bervirius", DUNGEON, 11154),
		a("Brimhaven Dungeon", DUNGEON, 10901, 10900, 10899, 10645, 10644, 10643),
		a("Brine Rat Cavern", DUNGEON, 10910),
		a("Catacombs of Kourend", DUNGEON, 6557, 6556, 6813, 6812),
		a("Champions' Challenge", DUNGEON, 12696),
		a("Chaos Druid Tower", DUNGEON, 10392),
		a("Chasm of Fire", DUNGEON, 5789),
		a("Chasm of Tears", DUNGEON, 12948),
		a("Chinchompa Hunting Ground", DUNGEON, 10129),
		a("Civitas illa Fortis Underground", DUNGEON, 6549, 6804, 6805),
		a("Clock Tower Basement", DUNGEON, 10390),
		a("Corsair Cove Dungeon", DUNGEON, 8076, 8332),
		a("Crabclaw Caves", DUNGEON, 6553, 6809),
		a("Crandor Dungeon", DUNGEON, 11414),
		a("Crash Site Cavern", DUNGEON, 8280, 8536),
		a("Crumbling Tower", DUNGEON, 7827),
		a("Daeyalt Essence Mine", DUNGEON, 14744),
		a("Digsite Dungeon", DUNGEON, 13464, 13465),
		a("Dorgesh-Kaan South Dungeon", DUNGEON, 10833),
		a("Dorgeshuun Mines", DUNGEON, 12950, 13206),
		a("Draynor Sewers", DUNGEON, 12439, 12438),
		a("Dwarven Mines", DUNGEON, 12185, 12184, 12183),
		a("Eagles' Peak Dungeon", DUNGEON, 8013),
		a("Ectofuntus", DUNGEON, 14746),
		a("Edgeville Dungeon", DUNGEON, 12441, 12442, 12443, 12698),
		a("Elemental Workshop", DUNGEON, 10906, 7760),
		a("Elven rabbit cave", DUNGEON, 13252),
		a("Enakhra's Temple", DUNGEON, 12423),
		a("Evil Chicken's Lair", DUNGEON, 9796),
		a("Experiment Cave", DUNGEON, 14235, 13979),
		a("Ferox Enclave Dungeon", DUNGEON, 12700),
		a("Forthos Dungeon", DUNGEON, 7323),
		a("Fremennik Slayer Dungeon", DUNGEON, 10907, 10908, 11164),
		a("Glarial's Tomb", DUNGEON, 10137),
		a("Goblin Cave", DUNGEON, 10393),
		a("Grand Tree Tunnels", DUNGEON, 9882),
		a("H.A.M. Hideout", DUNGEON, 12694),
		a("H.A.M. Store room", DUNGEON, 10321),
		a("Heroes' Guild Mine", DUNGEON, 11674),
		a("Iorwerth Dungeon", DUNGEON, 12737, 12738, 12993, 12994),
		a("Isle of Souls Dungeon", DUNGEON, 8593),
		a("Jatizso Mines", DUNGEON, 9631),
		a("Jiggig Burial Tomb", DUNGEON, 9875, 9874),
		a("Jogre Dungeon", DUNGEON, 11412),
		a("Karamja Dungeon", DUNGEON, 11413),
		a("Karuulm Slayer Dungeon", DUNGEON, 5280, 5279, 5023, 5535, 5022, 4766, 4510, 4511, 4767, 4768, 4512),
		a("KGP Headquarters", DUNGEON, 10658),
		a("Kruk's Dungeon", DUNGEON, 9358, 9359, 9360, 9615, 9616, 9871, 10125, 10126, 10127, 10128, 10381, 10382, 10383, 10384, 10637, 10638, 10639, 10640),
		a("Legends' Guild Dungeon", DUNGEON, 10904),
		a("Lighthouse", DUNGEON, 10140),
		a("Lizardman Caves", DUNGEON, 5275),
		a("Lizardman Temple", DUNGEON, 5277),
		a("Lumbridge Swamp Caves", DUNGEON, 12693, 12949),
		a("Lunar Isle Mine", DUNGEON, 9377),
		a("Maniacal Monkey Hunter Area", DUNGEON, 11662),
		a("Meiyerditch Mine", DUNGEON, 9544),
		a("Miscellania Dungeon", DUNGEON, 10144, 10400),
		a("Mogre Camp", DUNGEON, 11924),
		a("Mos Le'Harmless Caves", DUNGEON, 14994, 14995, 15251),
		a("Motherlode Mine", DUNGEON, 14679, 14680, 14681, 14935, 14936, 14937, 15191, 15192, 15193),
		a("Mourner Tunnels", DUNGEON, 7752, 8008),
		a("Mouse Hole", DUNGEON, 9046),
		a("Meiyerditch Laboratories", DUNGEON, 14232, 14233, 14487, 14488),
		a("Myreque Hideout", DUNGEON, 13721, 13974, 13977, 13978),
		a("Myths' Guild Dungeon", DUNGEON, 7564, 7820, 7821),
		a("Observatory Dungeon", DUNGEON, 9362),
		a("Ogre Enclave", DUNGEON, 10387),
		a("Ourania Cave", DUNGEON, 12119),
		a("Quidamortem Cave", DUNGEON, 4763),
		a("Rashiliyta's Tomb", DUNGEON, 11668),
		a("Ruins of Camdozaal", DUNGEON, 11609, 11610, 11611, 11865, 11866, 11867, 12121, 12122, 12123),
		a("Salt Mine", DUNGEON, 11425),
		a("Saradomin Shrine (Paterdomus)", DUNGEON, 13722),
		a("Shade Catacombs", DUNGEON, 13975),
		a("Shadow Dungeon", DUNGEON, 10575, 10831),
		a("Shayzien Crypts", DUNGEON, 6043),
		a("Sisterhood Sanctuary", DUNGEON, 14999, 15000, 15001, 15255, 15256, 15257, 15511, 15512, 15513),
		a("Smoke Dungeon", DUNGEON, 12946, 13202),
		a("Sophanem Dungeon", DUNGEON, 13200),
		a("Sourhog Cave", DUNGEON, 12695),
		a("Stronghold of Security", DUNGEON, 7505, 8017, 8530, 9297),
		a("Stronghold Slayer Cave", DUNGEON, 9624, 9625, 9880, 9881),
		a("Tarn's Lair", DUNGEON, 12616, 12615),
		a("Taverley Dungeon", DUNGEON, 11416, 11417, 11671, 11672, 11673, 11928, 11929),
		a("Temple of Ikov", DUNGEON, 10649, 10905, 10650),
		a("Temple of Light", DUNGEON, 7496),
		a("Temple of Marimbo", DUNGEON, 11151),
		a("The Burrow", DUNGEON, 6291),
		a("The Warrens", DUNGEON, 7070, 7326),
		a("Dungeon of Tolna", DUNGEON, 13209),
		a("Tower of Life Basement", DUNGEON, 12100),
		a("Trahaearn Mine", DUNGEON, 13250),
		a("Tunnel of Chaos", DUNGEON, 12625),
		a("Underground Pass", DUNGEON, 9369, 9370),
		a("Varrock Sewers", DUNGEON, 12954, 13210),
		a("Viyeldi Caves", DUNGEON, 9545, 11153),
		a("Warriors' Guild Basement", DUNGEON, 11675),
		a("Water Ravine", DUNGEON, 13461),
		a("Waterbirth Dungeon", DUNGEON, 9886, 10142, 7492, 7748),
		a("Waterfall Dungeon", DUNGEON, 10394),
		a("Werewolf Agility Course", DUNGEON, 14234),
		a("White Wolf Mountain Caves", DUNGEON, 11418, 11419),
		a("Witchhaven Shrine Dungeon", DUNGEON, 10903),
		a("Wizards' Tower Basement", DUNGEON, 12437),
		a("Woodcutting Guild Dungeon", DUNGEON, 6298),
		a("Wyvern Cave", DUNGEON, 14495, 14496),
		a("Yanille Agility Dungeon", DUNGEON, 10388),
		a("Ardougne Rat Pits", MINIGAME, 10646),
		a("Barbarian Assault", MINIGAME, 7508, 7509, 10322),
		a("Barrows", MINIGAME, 14131, 14231),
		a("Blast Furnace", MINIGAME, 7757),
		a("Brimhaven Agility Arena", MINIGAME, 11157),
		a("Burthorpe Games Room", MINIGAME, 8781),
		a("Castle Wars", MINIGAME, 9520, 9620),
		a("Clan Wars", MINIGAME, 12621, 12622, 12623, 13130, 13131, 13133, 13134, 13135, 13386, 13387, 13390, 13641, 13642, 13643, 13644, 13645, 13646, 13647, 13899, 13900, 14155, 14156),
		a("Emir's Arena", MINIGAME, 13362, 13363),
		a("Fishing Trawler", MINIGAME, 7499),
		a("Fortis Colosseum", MINIGAME, 7216),
		a("Fortis Colosseum Lobby", MINIGAME, 7316),
		a("The Gauntlet", MINIGAME, 12127, 7512),
		a("Corrupted Gauntlet", MINIGAME, 7768),
		a("Giants' Foundry", MINIGAME, 13491),
		a("Guardians of the Rift", MINIGAME, 14484),
		a("Hallowed Sepulchre", MINIGAME, 8797, 9051, 9052, 9053, 9054, 9309, 9563, 9565, 9821, 10074, 10075, 10077),
		a("The Inferno", MINIGAME, 9043),
		a("Keldagrim Rat Pits", MINIGAME, 7753),
		a("LMS - Deserted Island", MINIGAME, 13658, 13659, 13660, 13914, 13915, 13916),
		a("LMS - Wild Varrock", MINIGAME, 13918, 13919, 13920, 14174, 14175, 14176, 14430, 14431, 14432),
		a("Mage Training Arena", MINIGAME, 13462, 13463),
		a("Nightmare Zone", MINIGAME, 9033),
		a("Pest Control", MINIGAME, 10536),
		a("Port Sarim Rat Pits", MINIGAME, 11926),
		a("Pyramid Plunder", MINIGAME, 7749),
		a("Rogues' Den", MINIGAME, 11854, 11855, 12109, 12110, 12111),
		a("Sorceress's Garden", MINIGAME, 11605),
		a("Soul Wars", MINIGAME, 8493, 8748, 8749, 9005),
		a("Temple Trekking", MINIGAME, 8014, 8270, 8256, 8782, 9038, 9294, 9550, 9806),
		a("Tithe Farm", MINIGAME, 7222),
		a("Trouble Brewing", MINIGAME, 15150),
		a("Tzhaar Fight Caves", MINIGAME, 9551),
		a("Tzhaar Fight Pits", MINIGAME, 9552),
		a("Varrock Rat Pits", MINIGAME, 11599),
		a("Volcanic Mine", MINIGAME, 15263, 15262),
		a("Chambers of Xeric", RAID, 12889, 13136, 13137, 13138, 13139, 13140, 13141, 13145, 13393, 13394, 13395, 13396, 13397, 13401),
		a("Theatre of Blood", RAID, 12611, 12612, 12613, 12867, 12869, 13122, 13123, 13125, 13379),
		a("Tombs of Amascut", RAID, 14160, 14162, 14164, 14674, 14676, 15184, 15186, 15188, 15696, 15698, 15700),
		a("The Fractured Archive", RAID, 16214, 16726, 16215, 16727, 16216, 16728, 16472, 16474),
		a("Jaltevas Pyramid", RAID, 13454),
		a("Osmumten's Burial Chamber", RAID, 14672),
		a("Abyssal Area", REGION, 12108),
		a("Abyssal Nexus", REGION, 12106),
		a("Agility Pyramid", REGION, 12105, 13356),
		a("Air Altar", REGION, 11339),
		a("Al Kharid Mine", REGION, 13107),
		a("Ancient Vault", REGION, 12644, 13156),
		a("Ape Atoll", REGION, 10794, 10795, 10974, 11050),
		a("Arandar", REGION, 9266, 9267, 9523),
		a("Asgarnia", REGION, 11825, 11829, 11830, 12085, 12086),
		a("Avium Savannah", REGION, 5935, 5936, 5937, 6189, 6445, 6446, 6447, 6701, 6702, 6703, 6957, 6958, 6959, 7215),
		a("Battlefield", REGION, 10034),
		a("Battlefront", REGION, 5433, 5434),
		a("Blast Mine", REGION, 5948),
		a("Body Altar", REGION, 10059),
		a("Chaos Altar", REGION, 9035),
		a("Cosmic Altar", REGION, 8523),
		a("Cosmic Entity's Plane", REGION, 8267),
		a("Crabclaw Isle", REGION, 6965),
		a("Crafting Guild", REGION, 11571),
		a("Crandor", REGION, 11314, 11315),
		a("Crash Island", REGION, 11562),
		a("Dark Altar", REGION, 6716),
		a("Death Altar", REGION, 8779),
		a("Death Plateau", REGION, 11320),
		a("Dense Essence Mine", REGION, 6972),
		a("Desert Plateau", REGION, 13361, 13617),
		a("Digsite", REGION, 13365),
		a("Dragontooth Island", REGION, 15159),
		a("Draynor Manor", REGION, 12340),
		a("Drill Sergeant's Training Camp", REGION, 12619),
		a("Eagles' Peak", REGION, 9270),
		a("Earth Altar", REGION, 10571),
		a("Enchanted Valley", REGION, 12102),
		a("Evil Twin Crane Room", REGION, 7504),
		a("Exam Centre", REGION, 13364),
		a("Falador Farm", REGION, 12083),
		a("Farming Guild", REGION, 4922),
		a("Feldip Hills", REGION, 9773, 9774, 10029, 10030, 10285, 10286, 10287, 10542, 10543),
		a("Fenkenstrain's Castle", REGION, 14135),
		a("Fight Arena", REGION, 10289),
		a("Fire Altar", REGION, 10315),
		a("Fisher Realm", REGION, 10569),
		a("Fishing Guild", REGION, 10293),
		a("Fishing Platform", REGION, 11059),
		a("The Forsaken Tower", REGION, 5435),
		a("Fossil Island", REGION, 14650, 14651, 14652, 14906, 14907, 14908, 15162, 15163, 15164),
		a("Freaky Forester's Clearing", REGION, 10314),
		a("Fremennik Province", REGION, 10296, 10552, 10808, 10809, 10810, 10811, 11064),
		a("Fremennik Isles", REGION, 9276, 9532),
		a("Frogland", REGION, 9802),
		a("Galvek Shipwrecks", REGION, 6486, 6487, 6488, 6489, 6742, 6743, 6744, 6745),
		a("Ghorrock Dungeon", REGION, 11681),
		a("Gorak's Plane", REGION, 12115),
		a("Grand Exchange", REGION, 12598),
		a("God Wars Dungeon", REGION, 11578),
		a("Harmony Island", REGION, 15148),
		a("Hazelmere's Island", REGION, 10544),
		a("Hunter Guild", REGION, 6191),
		a("Ice Path", REGION, 11322, 11323),
		a("Iceberg", REGION, 10558, 10559),
		a("Icyene Graveyard", REGION, 14641, 14897, 14898),
		a("Isafdar", REGION, 8497, 8753, 8754, 9009, 9010),
		a("Island of Stone", REGION, 9790),
		a("Isle of Souls", REGION, 8236, 8237, 8238, 8491, 8492, 8494, 8747, 8750, 9003, 9004, 9006, 9260, 9261, 9262),
		a("Jiggig", REGION, 9775),
		a("Kandarin", REGION, 9268, 9269, 9014, 9263, 9264, 9519, 9524, 9527, 9776, 9783, 10037, 10290, 10294, 10546, 10551, 10805, 11062),
		a("Karamja", REGION, 10801, 10802, 11054, 11311, 11312, 11313, 11566, 11567, 11568, 11569, 11822),
		a("Kebos Lowlands", REGION, 4665, 4666, 4667, 4921, 5178),
		a("Kebos Swamp", REGION, 4664, 4920, 5174, 5175, 5176, 5430, 5431),
		a("Kharazi Jungle", REGION, 11053, 11309, 11565, 11821),
		a("Kharidian Desert", REGION, 12587, 12844, 12845, 12846, 12847, 12848, 13100, 13101, 13102, 13103, 13104, 13357, 13359, 13360, 13614, 13615, 13616, 13869, 13870),
		a("Killerwatt Plane", REGION, 10577),
		a("Great Kourend", REGION, 6201, 6457, 6713),
		a("Kourend Woodland", REGION, 5942, 6197, 6453),
		a("Law Altar", REGION, 9803),
		a("Legends' Guild", REGION, 10804),
		a("Lighthouse", REGION, 10040),
		a("Lithkren", REGION, 14142, 14398),
		a("Lumbridge Swamp", REGION, 12593, 12849),
		a("Max Island", REGION, 11063),
		a("McGrubor's Wood", REGION, 10550),
		a("Mime's Stage", REGION, 8010),
		a("Mind Altar", REGION, 11083),
		a("Misthalin", REGION, 12594, 12595, 12851),
		a("Molch", REGION, 5177),
		a("Molch Island", REGION, 5432),
		a("Morytania", REGION, 13619, 13620, 13621, 13622, 13876, 13877, 13879, 14133, 14134, 14389, 14390, 14391, 14645, 14647),
		a("Mount Quidamortem", REGION, 4662, 4663, 4918, 4919),
		a("Mr. Mordaut's Classroom", REGION, 7502),
		a("Mudskipper Point", REGION, 11824),
		a("Mysterious Old Man's Maze", REGION, 11591),
		a("Myths' Guild", REGION, 9772),
		a("Nature Altar", REGION, 9547),
		a("Necropolis", REGION, 13098, 13353, 13354, 13609, 13610),
		a("Northern Tundras", REGION, 6204, 6205, 6717),
		a("Observatory", REGION, 9777),
		a("Odd One Out", REGION, 7754),
		a("Ortus Farm", REGION, 6192, 6193),
		a("Otto's Grotto", REGION, 10038),
		a("Ourania Hunter Area", REGION, 9778),
		a("Pirates' Cove", REGION, 8763),
		a("Piscatoris Hunter Area", REGION, 9015, 9016, 9271, 9272, 9528),
		a("Player Owned House", REGION, 7534, 7535, 7790, 7791, 8046, 8047, 8302, 8303),
		a("Poison Waste", REGION, 8752, 9008),
		a("Port Tyras", REGION, 8496),
		a("Puro-Puro", REGION, 10307),
		a("Quarry", REGION, 12589),
		a("Ralos' Rise", REGION, 5424, 5425, 5679, 5680, 5681, 5682),
		a("Ranging Guild", REGION, 10549),
		a("Ratcatchers Mansion", REGION, 11343),
		a("Ruins of Unkah", REGION, 12588),
		a("Ruins of Ullek", REGION, 13355, 13611, 13612),
		a("Rune Essence Mine", REGION, 11595),
		a("ScapeRune", REGION, 10058, 7758, 8261),
		a("Sea Spirit Dock", REGION, 12332),
		a("Ship Yard", REGION, 11823),
		a("Silvarea", REGION, 13366),
		a("Sinclair Mansion", REGION, 10807),
		a("Slayer Tower", REGION, 13623, 13723),
		a("Soul Altar", REGION, 7228),
		a("Stranglewood Temple", REGION, 4761),
		a("Sunset Coast", REGION, 5934, 6190),
		a("The Scar", REGION, 8036, 8292),
		a("The Stranglewood", REGION, 4403, 4404, 4659, 4660, 4661, 4916, 4917),
		a("Troll Arena", REGION, 11576),
		a("Trollheim", REGION, 11577),
		a("Trollweiss Mountain", REGION, 11066, 11067, 11068),
		a("Tutorial Island", REGION, 12079, 12080, 12335, 12336, 12436, 12592),
		a("Underwater", REGION, 15008, 15264),
		a("Water Altar", REGION, 10827),
		a("Waterbirth Island", REGION, 10042),
		a("Wintertodt Camp", REGION, 6461),
		a("Wizards' Tower", REGION, 12337),
		a("Woodcutting Guild", REGION, 6198, 6454),
		a("Wrath Altar", REGION, 9291)
	// discord:end
	};

	/** Off the map, where its entrance is (following's own list, Follow.ENTRANCES, by the area's name). */
	static final Map<String, int[]> ENTRANCES;
	/** the main map's bounds, underground's offset, and Sailing's southern islands' caves (Follow's, the same) */
	static final int X0 = 960, X1 = 4096, Y0 = 2048, Y1 = 4224, UNDER = 6400, UNDER_Y0 = 2496;
	static final Set<Integer> SEA_CAVES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(8840, 10374, 10375,
		10634, 10890, 12426, 12682, 12938, 13194)));
	/** Prifddinas's tiles and where the world map draws them (Follow's PX0... PDY) */
	static final int PX0 = 3136, PX1 = 3392, PY0 = 5952, PY1 = 6208, PDX = 1024, PDY = 2752;
	static final int[] ZANARIS = {2368, 4416};
	static final Map<Integer, Area> BY_REGION;

	static
	{
		Map<String, int[]> e = new HashMap<>();
		for (String n : new String[]{"The Inferno", "Tzhaar Fight Caves", "Tzhaar Fight Pits", "Mor Ul Rek"})
		{
			e.put(n, new int[]{2880, 3136});   // (TzHaar's way in, inside the Karamja volcano)
		}
		e.put("Chambers of Xeric", new int[]{1216, 3520});   // (Mount Quidamortem)
		e.put("Theatre of Blood", new int[]{3648, 3264});   // (Ver Sinhaza)
		e.put("Tombs of Amascut", new int[]{3392, 2752});   // (the Jaltevas pyramid, in the Necropolis)
		e.put("Osmumten's Burial Chamber", new int[]{3392, 2752});
		e.put("Grotesque Guardians", new int[]{3392, 3520});   // (the Slayer Tower's roof)
		for (String n : new String[]{"The Gauntlet", "Corrupted Gauntlet", "Zalcano"})
		{
			e.put(n, new int[]{2240, 3328});   // (Prifddinas's middle, where the world map draws it)
		}
		e.put("Giant Mole", new int[]{3008, 3392});   // (Falador Park)
		// (11/16/2026, Jace: a friend at the Guardians of the Rift stood in the Murkshore Sea, as a boat: the place above
		// its region is no way in. These three were the ones whose place above was far from their way in)
		e.put("Guardians of the Rift", new int[]{3104, 3168});   // (the Wizards' Tower, whose basement holds the portal)
		e.put("Kalphite Queen", new int[]{3232, 3104});   // (the Kalphite Lair's way in, in the desert)
		e.put("Temple Trekking", new int[]{3424, 3488});   // (Paterdomus, where the treks start)
		for (String n : new String[]{"General Graardor", "Kree'arra", "Commander Zilyana", "K'ril Tsutsaroth", "Nex"})
		{
			e.put(n, new int[]{2880, 3776});   // (the God Wars Dungeon's way in, in Trollheim)
		}
		ENTRANCES = Collections.unmodifiableMap(e);
		Map<Integer, Area> by = new HashMap<>();
		for (Area area : AREAS)
		{
			area.at = place(area);
			for (int r : area.regions)
			{
				by.put(r, area);
			}
		}
		BY_REGION = Collections.unmodifiableMap(by);
	}

	/** The area's point for the globe: the region of it on the map nearest the middle of those on the map (each
	 *  region's middle, underground as the place above it, Prifddinas where the world map draws it), Zanaris as its one
	 *  place, or off the map its entrance; null for none. */
	static int[] place(Area area)
	{
		if (area.name.equals("Zanaris"))
		{
			return ZANARIS.clone();
		}
		int n = 0;
		long sx = 0, sy = 0;
		int[][] on = new int[area.regions.length][];
		for (int i = 0; i < area.regions.length; i++)
		{
			int r = area.regions[i];
			on[i] = onMap((r >> 8) * 64 + 32, (r & 255) * 64 + 32, r);
			if (on[i] != null)
			{
				n++;
				sx += on[i][0];
				sy += on[i][1];
			}
		}
		// (a known way in first, since 11/16/2026: the place above an underground region can be nowhere near it)
		int[] at = ENTRANCES.get(area.name);
		if (at != null)
		{
			return at.clone();
		}
		if (n == 0)
		{
			return null;
		}
		double mx = (double) sx / n, my = (double) sy / n, best = Double.MAX_VALUE;
		int[] pick = null;
		for (int[] p : on)
		{
			if (p != null)
			{
				double d = (p[0] - mx) * (p[0] - mx) + (p[1] - my) * (p[1] - my);
				if (d < best)
				{
					best = d;
					pick = p;
				}
			}
		}
		return pick;
	}

	/** A region's middle on the main map: itself, underground the place above it, Prifddinas's tiles where the world
	 *  map draws them; null off the map. */
	static int[] onMap(int x, int y, int region)
	{
		if (x >= PX0 && x < PX1 && y >= PY0 && y < PY1)
		{
			x -= PDX;
			y -= PDY;
		}
		if ((y >= UNDER_Y0 + UNDER && y < Y1 + UNDER) || (y >= Y0 + UNDER && y < Y1 + UNDER && SEA_CAVES.contains(region)))
		{
			y -= UNDER;
		}
		return x < X0 || x >= X1 || y < Y0 || y >= Y1 ? null : new int[]{x, y};
	}

	/** The area for a region, or null (Discord's "In Game"): Nightmare Zone's on the ground floor is no area. */
	static Area area(int region, int plane)
	{
		Area a = BY_REGION.get(region);
		return a != null && region == NMZ && plane == 0 ? null : a;
	}

	// ---- the player's own, on the client's thread

	private final Map<String, Integer> xp = new HashMap<>();
	private final Map<String, Long> trained = new HashMap<>();
	private Area here;
	private JsonObject sent;
	private long sentAt = Long.MIN_VALUE / 2;

	/** Logged out (or another character): everything forgotten, and nothing taken as sent. */
	void loggedOut()
	{
		xp.clear();
		trained.clear();
		here = null;
		sent = null;
		sentAt = Long.MIN_VALUE / 2;
	}

	/** A skill's experience as the game tells it (StatChanged): above what was last seen, it's being trained. */
	void experience(String skill, int exp, long now)
	{
		Integer was = xp.put(skill, exp);
		if (was != null && was < exp && SKILLS.contains(skill))
		{
			trained.put(skill, now);
		}
	}

	/** A scene loaded where the player stands (the region, an instance's as its template lies, and the floor): its
	 *  area, and the skill cleared, as Discord's area event clears it. */
	void scene(int region, int plane)
	{
		here = area(region, plane);
		trained.clear();
	}

	/** What it says now: {"skill", "area", "kind"}, with "x" and "y" when the area has a point; somewhere dangerous,
	 *  all null. */
	JsonObject now(long now, boolean danger)
	{
		String skill = null;
		long best = Long.MIN_VALUE;
		int rank = -1;
		for (Map.Entry<String, Long> e : trained.entrySet())
		{
			if (now - e.getValue() > SKILL_MS)
			{
				continue;
			}
			int r = e.getKey().equals("Slayer") || e.getKey().equals("Fishing") ? 1 : 0;
			if (r > rank || (r == rank && e.getValue() > best))
			{
				rank = r;
				best = e.getValue();
				skill = e.getKey();
			}
		}
		Area a = danger ? null : here;
		JsonObject o = new JsonObject();
		o.addProperty("skill", danger ? null : skill);
		o.addProperty("area", a == null ? null : a.name);
		o.addProperty("kind", a == null ? null : a.kind);
		if (a != null && a.at != null)
		{
			o.addProperty("x", a.at[0]);
			o.addProperty("y", a.at[1]);
		}
		return o;
	}

	/** What to send now, or null: what it says when that's changed, at most once every EVERY_MS (the latest once it's
	 *  up), or at once when somewhere dangerous clears what was sent. */
	JsonObject due(long now, boolean danger)
	{
		JsonObject cur = now(now, danger);
		if (cur.equals(sent))
		{
			return null;
		}
		if (danger && sent != null)
		{
			return cur;
		}
		return now - sentAt >= EVERY_MS ? cur : null;
	}

	/** What due() (or a snapshot) gave went. */
	void took(JsonObject o, long now)
	{
		sent = o;
		sentAt = now;
	}

	/** Switched off, or the page cleared: nothing counts as sent. */
	void unsent()
	{
		sent = null;
	}
}
