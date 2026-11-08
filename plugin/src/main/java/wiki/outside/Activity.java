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
 * attacking them);</li>
 * <li>{"kind": "fighting"}: in a fight with anything else;</li>
 * <li>{"kind": "idle"}: none of those.</li>
 * </ul>
 * Minigames aren't one: the few that could be told cheaply by where they are (Pest Control, Barbarian Assault,
 * Tempoross, the Fight Caves) are instances, where nothing goes anyway, and the rest read as their skill (Wintertodt as
 * Firemaking, the Blast Furnace as Smithing).
 * Off by default (OutsideConfig.activity). It's only ever the player's own: their own experience, and the NPC in their
 * own fight, by its name, and only when it's a boss. The label goes through Behind, as following's square does: two
 * minutes behind, at most once a minute, never from anywhere dangerous or an instance, cleared there at once.
 * Kept apart from RuneLite so it's tested; the plugin feeds it from StatChanged and HitsplatApplied.
 */
class Activity
{
	/** skilling: the latest experience drop within this long; fighting: a hit, theirs or on them, within this long */
	static final long SKILL_MS = 60_000, FIGHT_MS = 15_000;

	/** the skills (RuneLite's Skill names, all but the total) */
	static final Set<String> SKILLS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
		"Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting",
		"Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving",
		"Slayer", "Farming", "Runecraft", "Hunter", "Construction", "Sailing")));
	/** experience in these during a fight is the fight's, not skilling */
	static final Set<String> COMBAT = new HashSet<>(Arrays.asList(
		"Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Magic", "Slayer"));

	/**
	 * The bosses (11/08/2026): each NPC's name as the game has it, and the label that goes (a boss of several NPCs as
	 * one). The relays take only these labels (game.ts's ACTIVITY_BOSSES, relay.py's; relay-py/test_relay.py checks
	 * the three lists match). The Wilderness's bosses aren't here: nothing goes from the Wilderness. Many on it are
	 * only ever fought in an instance (Vorkath, Zulrah, the Inferno), where nothing goes either: they're here in case
	 * that changes. A name not here, or spelled otherwise in the game, is "fighting".
	 */
	static final Map<String, String> BOSSES;

	static
	{
		Map<String, String> b = new LinkedHashMap<>();
		for (String n : new String[]{"Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Bryophyta", "Cerberus",
			"Commander Zilyana", "Corporeal Beast", "Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme",
			"Duke Sucellus", "General Graardor", "Giant Mole", "Hespori", "Kalphite Queen", "King Black Dragon", "Kraken",
			"Kree'arra", "K'ril Tsutsaroth", "Nex", "Obor", "Phantom Muspah", "Phosani's Nightmare", "Sarachnis",
			"Scurrius", "Skotizo", "Sol Heredit", "The Hueycoatl", "The Leviathan", "The Nightmare", "The Whisperer",
			"Thermonuclear smoke devil", "TzKal-Zuk", "TzTok-Jad", "Vardorvis", "Vorkath", "Yama", "Zalcano", "Zulrah"})
		{
			b.put(n, n);
		}
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
		BOSSES = Collections.unmodifiableMap(b);
	}

	/** each skill's experience as last seen (the first look at a skill, as they log in, is only its starting point) */
	private final Map<String, Integer> xp = new HashMap<>();
	private String skill = null;
	private long skillAt = -1;
	private long fightAt = -1;
	private String boss = null;

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
		else if (!fighting(now))
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
		if (fighting(now))
		{
			if (boss != null)
			{
				a.addProperty("kind", "boss");
				a.addProperty("boss", boss);
			}
			else
			{
				a.addProperty("kind", "fighting");
			}
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
	}

	/** The labels a boss goes as, sorted (for the relays' list). */
	static Set<String> bossLabels()
	{
		return new TreeSet<>(BOSSES.values());
	}
}
