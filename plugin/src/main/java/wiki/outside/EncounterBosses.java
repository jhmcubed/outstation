package wiki.outside;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every boss, raid and boss-type minigame the game has, and how the encounter recorder starts and ends a recording of
 * each (11/22/2026; plugin/docs/encounter-bosses.md is this table written up, with what was wrong and its sources).
 * Read by Encounters for its decisions (where a recording starts on entering, which NPCs are a place's boss, which count
 * lines end it and which pass by, whose death ends it with no count line, how long a fight may go quiet), and by
 * EncounterBossesTest, which walks every row through those decisions against its own list of what each should do.
 *
 * <p>Where each fact comes from (nothing from memory):
 * <ul>
 * <li>the areas and their regions: RuneLite's DiscordGameEventType (runelite/runelite master, 11/18/2026), as
 * Presence.AREAS holds them; a boss Discord has no area for is placed by its boss's fight instead (its regions here
 * are the OSRS Wiki's map pins, LocLine's x and y, for the write-up only: nothing reads them);</li>
 * <li>the NPC names: the game's cache (OpenRS2's cache 2772, revision 242, 11/18/2026; its NPC configs read with
 * ghillie's replay/3d defs.js), each name exactly as the cache spells it (case is ignored when matching);</li>
 * <li>the count lines: RuneLite's ChatCommandsPlugin (its KILLCOUNT_PATTERN, its boss-name table, and its tests'
 * lines, ChatCommandsPluginTest) and Dink's KillCountNotifier and its tests (pajlads/DinkPlugin, 11/22/2026), quoted in
 * each row; where neither quotes the game's line, the row says so and ends on any count line;</li>
 * <li>how a fight goes (an instance or not, several bosses, a phase that "dies"): the OSRS Wiki's pages, cited in the
 * rows.</li>
 * </ul>
 */
final class EncounterBosses
{
	/** How a recording of it starts. */
	enum Start
	{
		/** on entering its area (a raid, the Inferno, the Fight Cave) */
		ENTRY,
		/** its area: an instance on entering; its open (public) copy with the fight with its boss (11/22/2026, the Mole) */
		PLACE,
		/** with the fight with its boss: a hitsplat either way, or the player attacking it (an open area like Barrows,
		 *  or a boss Discord has no area for, anywhere) */
		FIGHT,
		/** by its own rule elsewhere: a run's way in (Encounters.Run), Doom of Mokhaiotl's forms (DOOM_IDS) */
		OWN,
		/** never by itself: skilling (Wintertodt, Tempoross), a lobby, a raid's part, the Wilderness */
		NEVER
	}

	/** How a recording of it ends (besides the player's death, leaving and logging out). */
	enum End
	{
		/** one kill: its count line ("killed"), or its boss's death with no line */
		KILL,
		/** several bosses: a count line ends it once none of the others fought is still up ("killed") */
		LAST,
		/** waves: only the last boss's count line ends it ("completed"), never a wave's */
		WAVES,
		/** a chest: only the chest's count line or its loot ends it ("looted"), never a boss's kill */
		CHEST,
		/** a run (the Gauntlets, the Colosseum): Encounters.runEnding */
		RUN,
		/** a raid: from entering to leaving its treasure room (Encounters' raid rules) */
		RAID,
		/** a delve of Doom of Mokhaiotl: to the death, or the claim and leaving */
		DELVE,
		/** never recorded by itself */
		NONE
	}

	/** One row. */
	static final class Boss
	{
		/** its own name (the hiscores' or the wiki's), and the area's when Discord names one */
		final String name;
		/** "boss", "raid", "minigame", "wilderness", "skilling" */
		final String kind;
		/** Discord's area for it, or null: a place of its own, named for it */
		final String area;
		/** the regions: Discord's (Presence's) for an area, the wiki's map pins for one Discord misses (the write-up's) */
		final int[] regions;
		final Start start;
		final End end;
		/** its boss's names (lower case): a fight with one starts it where it starts by the fight */
		final Set<String> npcs;
		/** the names whose death ends it with no count line (lower case; empty: never, the line always comes) */
		final Set<String> deaths;
		/** NPC ids whose death is only a phase (the Kalphite Queen's first form) */
		final Set<Integer> phases;
		/** the count lines' names (as counted() gives them) that end it; null: any count line */
		final Set<String> ends;
		/** ticks with no hitsplat on or from the player before it ends ("the fight stopped"), where it started by the
		 *  fight; 0: never */
		final int idle;
		/** a place of its own: ticks with none of its NPCs in sight before it's left; 0: never */
		final int gone;
		/** ticks out of its area (in no named area) before it's left */
		final int grace;
		/** the count line as the game words it, with where that wording comes from */
		final String line;
		/** a place of its own (area null): the recording's area, named for it; else null */
		final Presence.Area place;

		private Boss(Row r)
		{
			name = r.name;
			kind = r.kind;
			area = r.area;
			regions = r.regions;
			start = r.start;
			end = r.end;
			npcs = lower(r.npcs);
			deaths = r.deaths == null ? npcs : lower(r.deaths);
			phases = Collections.unmodifiableSet(new HashSet<>(r.phases));
			ends = r.ends == null ? null : lower(r.ends);
			idle = r.idle;
			gone = r.gone;
			grace = r.grace;
			line = r.line;
			place = r.area == null && (r.start == Start.FIGHT) ? new Presence.Area(r.name, Presence.BOSS, r.regions) : null;
		}

		/** Whether it starts the tick they're in its area (an instance, as RuneLite's top-level world view says). */
		boolean startsOnEntry(boolean instance)
		{
			return start == Start.ENTRY || start == Start.PLACE && instance;
		}

		/** Whether it starts with the fight with its boss, not on entering. */
		boolean startsByFight(boolean instance)
		{
			return start == Start.FIGHT || start == Start.PLACE && !instance;
		}

		/** Whether an NPC of this name is its boss (any case; a non-breaking space as the game sends it). */
		boolean isBoss(String npc)
		{
			return npc != null && npcs.contains(norm(npc));
		}

		/** Whether an NPC of this name is one whose death ends it with no count line. */
		boolean isDeath(String npc)
		{
			return npc != null && deaths.contains(norm(npc));
		}

		/** Whether this NPC's death (its id and name) ends it with no count line: one of its deaths, not a phase. */
		boolean diesTo(int id, String npc)
		{
			return isDeath(npc) && !phases.contains(id);
		}

		/** Whether a count line naming {@code counted} (as counted() gives it) is one that ends it. */
		boolean endsOn(String counted)
		{
			return ends == null || counted != null && ends.contains(norm(counted));
		}

		/** A count line naming {@code counted}: why it ends the recording, or null for one that passes by (a wave's, a
		 *  sub-boss's, a moon's before the chest; one of several bosses with another fought still up: {@code otherUp}).
		 *  A raid's, a run's and a delve's count lines are their own rules' (null here). */
		String killEnds(String counted, boolean otherUp)
		{
			switch (end)
			{
				case KILL:
				case NONE:
					return endsOn(counted) ? "killed" : null;
				case LAST:
					return endsOn(counted) && !otherUp ? "killed" : null;
				case WAVES:
					return endsOn(counted) ? "completed" : null;
				case CHEST:
					return endsOn(counted) ? "looted" : null;
				default:
					return null;
			}
		}

		/** Whether a fight that's gone quiet ends it: {@code idle} ticks since the last hitsplat on or from the player,
		 *  where it started by the fight (an open area, or a place of its own; never an instance entered). */
		boolean idleEnds(boolean instance, int now, int lastFight)
		{
			return idle > 0 && (area == null || startsByFight(instance)) && now >= lastFight + idle;
		}

		/** A place of its own: whether it's left, none of its NPCs seen since {@code seenAt}. */
		boolean goneEnds(int now, int seenAt)
		{
			return area == null && gone > 0 && now >= seenAt + gone;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	/** A row as it's written below. */
	private static final class Row
	{
		final String name, kind;
		String area;
		int[] regions = new int[0];
		Start start = Start.PLACE;
		End end = End.KILL;
		List<String> npcs = new ArrayList<>(), deaths, ends;
		List<Integer> phases = new ArrayList<>();
		int idle = Encounters.OPEN_IDLE, gone, grace = Encounters.LEFT_BOSS;
		String line;

		Row(String name, String kind)
		{
			this.name = name;
			this.kind = kind;
		}

		/** Discord's area of this name (its regions read from Presence: a test checks each is there) */
		Row area(String a)
		{
			area = a;
			Presence.Area p = discord(a);
			regions = p == null ? new int[0] : p.regions;
			return this;
		}

		Row regions(int... r)
		{
			regions = r;
			return this;
		}

		Row start(Start s)
		{
			start = s;
			return this;
		}

		Row end(End e)
		{
			end = e;
			return this;
		}

		Row npcs(String... n)
		{
			npcs.addAll(Arrays.asList(n));
			return this;
		}

		Row deaths(String... n)
		{
			deaths = Arrays.asList(n);
			return this;
		}

		Row phases(Integer... ids)
		{
			phases.addAll(Arrays.asList(ids));
			return this;
		}

		Row ends(String... n)
		{
			ends = Arrays.asList(n);
			return this;
		}

		Row idle(int t)
		{
			idle = t;
			return this;
		}

		Row gone(int t)
		{
			gone = t;
			return this;
		}

		Row grace(int t)
		{
			grace = t;
			return this;
		}

		Row line(String l)
		{
			line = l;
			return this;
		}
	}

	private static final List<Row> ROWS = new ArrayList<>();

	private static Row row(String name, String kind)
	{
		Row r = new Row(name, kind);
		ROWS.add(r);
		return r;
	}

	/** a boss at Discord's boss area of its own name (the npc the area's name, unless given) */
	private static Row boss(String name)
	{
		return row(name, "boss").area(name);
	}

	/** a boss Discord has no area for: started by its fight anywhere (never in the Wilderness: Encounters' rule) */
	private static Row own(String name)
	{
		return row(name, "boss").start(Start.FIGHT).gone(Encounters.WATCHED_GONE);
	}

	private static Row wilderness(String name, String line)
	{
		return row(name, "wilderness").start(Start.NEVER).end(End.NONE).npcs(name).idle(0).line(line);
	}

	// RL: RuneLite's ChatCommandsPluginTest quotes the line; Dink: Dink's tests do; RL's name: RuneLite's chat commands
	// keep the kill count under this name (its boss-name table, longBossName, or Dink's cleanBossName): the line's own
	// name, in the game's one form "Your <name> kill count is: <n>." (KILLCOUNT_PATTERN); hiscores: only the hiscores'
	// name (RuneLite's HiscoreSkill) is known, the line's wording isn't quoted anywhere read, and any count line ends it.
	static
	{
		// ---- Discord's boss areas (DiscordGameEventType's BOSS_*, every one)
		boss("Abyssal Sire").line("Your Abyssal Sire kill count is: N. (RL's name: \"sire\")");
		// (the wiki: "an instanced lair inside the Morytania Spider Cave")
		boss("Araxxor").line("Your Araxxor kill count is: N. (hiscores; Dink's special-loot list)");
		boss("Cerberus").line("Your Cerberus kill count is: 3273. (Dink)");
		boss("Commander Zilyana").line("Your Commander Zilyana kill count is: N. (RL's name: \"zilyana\")");
		boss("Corporeal Beast").line("Your Corporeal Beast kill count is: 4. (RL)");
		// (the three kings each have their own count line and respawn on their own; fought together, the last one's line
		// ends it)
		boss("Dagannoth Kings").npcs("Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme").end(End.LAST)
			.line("Your Dagannoth Rex kill count is: N. (RL's names: \"rex\", \"prime\", \"supreme\"; one line a king)");
		boss("Duke Sucellus").line("Your Duke Sucellus kill count is: N. (RL's name: \"duke\")");
		boss("General Graardor").line("Your General Graardor kill count is: N. (RL's name: \"graardor\")");
		boss("Giant Mole").line("Your Giant Mole kill count is: N. (RL's name: \"mole\")");
		// (Dusk and Dawn: one count line for both, once both are down, so the line is the end; with no line, the death
		// ends it only once the other's down too; the wiki: in an instance)
		boss("Grotesque Guardians").npcs("Dusk", "Dawn")
			.line("Your Grotesque Guardians kill count is: 179. (RL)");
		boss("Hespori").line("Your Hespori kill count is: N. (hiscores)");
		boss("Alchemical Hydra").line("Your Alchemical Hydra kill count is: N. (RL's name: \"hydra\")");
		// (the wiki: "When the first form is killed, it takes 20 game ticks (12 seconds) for the transition into the
		// second airborne form": the first form's death (NpcID.KALPHITE_QUEEN, 963) is a phase; the second is
		// KALPHITE_FLYINGQUEEN, 965)
		boss("Kalphite Queen").phases(963)
			.line("Your Kalphite Queen kill count is: N. (RL's name: \"kq\"; RL quotes \"Your Kalphite Queen (Echo) kill count is:1\")");
		// (the boss and its tentacles are "Whirlpool" till disturbed; a tentacle's fight starts it, only the Kraken's
		// death ends it)
		boss("Kraken").npcs("Kraken", "Enormous Tentacle").deaths("Kraken")
			.line("Your Kraken kill count is: N. (hiscores)");
		boss("Kree'arra").line("Your Kree'arra kill count is: 4. (RL)");
		boss("K'ril Tsutsaroth").line("Your K'ril Tsutsaroth kill count is: 481 (Dink)");
		boss("Nex").line("Your Nex kill count is: N. (hiscores)");
		boss("Nightmare of Ashihama").npcs("The Nightmare", "Phosani's Nightmare")
			.line("Your Nightmare kill count is: 1130 (RL); Your Phosani's Nightmare kill count is: N. (RL's name: \"phosani\")");
		boss("Phantom Muspah").line("Your Phantom Muspah kill count is: N. (RL's name: \"muspah\")");
		boss("Sarachnis").line("Your Sarachnis kill count is: N. (hiscores)");
		boss("Skotizo").line("Your Skotizo kill count is: N. (hiscores)");
		boss("Thermonuclear smoke devil").line("Your Thermonuclear smoke devil kill count is: N. (RL's name: \"thermy\")");
		row("Tempoross", "skilling").area("Tempoross").start(Start.NEVER).end(End.NONE).idle(0)
			.line("Your Tempoross kill count is: 55. (RL)");
		boss("The Leviathan").line("Your Leviathan kill count is: N. (RL's name: \"the leviathan\" is \"Leviathan\"; Dink: the line's name isn't the NPC's)");
		// (Branda and Eldric: one count line for both, once both are down, so the line is the end (the first one down
		// lies as a corpse to loot, which isn't "up"); with no line, as Dusk and Dawn; the wiki: "an additional player can
		// join the instance")
		boss("The Royal Titans").npcs("Branda the Fire Queen", "Eldric the Ice King")
			.line("[not quoted by RuneLite or Dink; the hiscores' \"The Royal Titans\"; round four: the line names neither titan]");
		boss("The Whisperer").line("Your Whisperer kill count is: N. (RL's name: \"the whisperer\" is \"Whisperer\"; Dink)");
		boss("Vardorvis").line("Your Vardorvis kill count is: N. (RL's name: \"vard\")");
		// (Vorkath's zombified spawn is another NPC, "Zombified Spawn": its death is nothing)
		boss("Vorkath").line("Your Vorkath kill count is: 418. (EncountersTest; RL's name: \"vork\")");
		row("Wintertodt", "skilling").area("Wintertodt").start(Start.NEVER).end(End.NONE).idle(0)
			.line("Your subdued Wintertodt count is: 4. (RL)");
		boss("Yama").line("Your Yama success count is: 227 (RL)");
		boss("Zalcano").line("Your Zalcano kill count is: N. (hiscores)");
		// (its three forms are all "Zulrah" in the cache: 2042, 2043, 2044)
		boss("Zulrah").line("Your Zulrah kill count is: 4. (RL)");

		// ---- Discord's raid areas (RAIDS_*)
		row("Chambers of Xeric", "raid").area("Chambers of Xeric").start(Start.ENTRY).end(End.RAID).idle(0)
			.line("Your completed Chambers of Xeric count is: 51. / Your completed Chambers of Xeric Challenge Mode count is: 13. (RL)");
		row("Theatre of Blood", "raid").area("Theatre of Blood").start(Start.ENTRY).end(End.RAID).idle(0)
			.line("Your completed Theatre of Blood count is: 73. / ...: Entry Mode count is / ...: Hard Mode count is (RL)");
		row("Tombs of Amascut", "raid").area("Tombs of Amascut").start(Start.ENTRY).end(End.RAID).idle(0)
			.line("Your completed Tombs of Amascut count is: 40. (Dink) / ...: Expert Mode count is: 1. / ...: Entry Mode count is: 3. (RL)");
		row("The Fractured Archive", "raid").area("The Fractured Archive").start(Start.ENTRY).end(End.RAID).idle(0)
			.line("Your completed Fractured Archive count is: 58. (his recordings; RL's name: \"tfa\" is \"Fractured Archive\")");
		row("Jaltevas Pyramid", "raid").area("Jaltevas Pyramid").start(Start.NEVER).end(End.NONE).idle(0)
			.line("(Tombs of Amascut's lobby)");
		row("Osmumten's Burial Chamber", "raid").area("Osmumten's Burial Chamber").start(Start.NEVER).end(End.NONE).idle(0)
			.line("(Tombs of Amascut's treasure room: a part of its recording)");

		// ---- Discord's minigames that are a boss fight (MG_*)
		// (the six brothers, then the chest: Barrows' reward window and its line end it, never a brother's death; the
		// barrows and their tunnels are open, so the first brother's fight starts it; the run between crypts is long)
		row("Barrows", "minigame").area("Barrows").start(Start.FIGHT).end(End.CHEST)
			.npcs("Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested", "Karil the Tainted", "Torag the Corrupted",
				"Verac the Defiled").deaths().ends("Barrows", "Barrows chest").idle(Encounters.RUN_IDLE)
			.line("Your Barrows chest count is: 277. (RL)");
		// (the waves to TzKal-Zuk: started on entering, ended only by Zuk's line (TzHaar-Ket-Rak's challenge lines too,
		// in case its arena is a copy of the Inferno's), a death or leaving; RuneLite's Timers and Buffs reads the Inferno as
		// region 9043 and the Fight Cave as 9551, and their pause, "The Inferno has been paused. You may now log out.")
		row("The Inferno", "minigame").area("The Inferno").start(Start.ENTRY).end(End.WAVES).npcs("TzKal-Zuk").deaths()
			.ends(addAll(new ArrayList<>(Collections.singletonList("TzKal-Zuk")), challenges()).toArray(new String[0]))
			.idle(0).grace(Encounters.LEFT_RAID)
			.line("Your TzKal-Zuk kill count is: 2. (RL)");
		row("Tzhaar Fight Caves", "minigame").area("Tzhaar Fight Caves").start(Start.ENTRY).end(End.WAVES).npcs("TzTok-Jad")
			.deaths().ends("TzTok-Jad").idle(0).grace(Encounters.LEFT_RAID)
			.line("Your TzTok-Jad kill count is: 2. (RL)");
		row("The Gauntlet", "minigame").area("The Gauntlet").start(Start.OWN).end(End.RUN).npcs("Crystalline Hunllef").idle(0)
			.line("Your Gauntlet completion count is: 123. (RL)");
		row("Corrupted Gauntlet", "minigame").area("Corrupted Gauntlet").start(Start.OWN).end(End.RUN).npcs("Corrupted Hunllef").idle(0)
			.line("Your Corrupted Gauntlet completion count is: 4729. (RL)");
		row("Fortis Colosseum", "minigame").area("Fortis Colosseum").start(Start.OWN).end(End.RUN).npcs("Sol Heredit").idle(0)
			.line("Your Sol Heredit kill count is: N. (RL's name: \"colosseum\" is \"Sol Heredit\")");
		row("Fortis Colosseum Lobby", "minigame").area("Fortis Colosseum Lobby").start(Start.NEVER).end(End.NONE).idle(0)
			.line("(the Colosseum's lobby)");

		// ---- bosses Discord has no area for: started by their boss's fight wherever it is (the hiscores' bosses)
		// (the wiki: a public lair in Varrock Sewers, Discord's dungeon, or a private instance)
		own("Scurrius").npcs("Scurrius").regions(13210).line("Your Scurrius kill count is: N. (hiscores)");
		// (the wiki: a world boss in the Darkfrost, or an instance by the meeting flag; its tail and body are fought too)
		own("The Hueycoatl").npcs("The Hueycoatl", "Hueycoatl tail", "Hueycoatl body").deaths("The Hueycoatl").regions(5939)
			.line("Your Hueycoatl kill count is: N. (RL's name: \"the hueycoatl\" is \"Hueycoatl\"; Dink)");
		// (the wiki: "she can only be fought solo in an instanced area")
		own("Amoxliatl").npcs("Amoxliatl").regions(5446).line("Your Amoxliatl kill count is: N. (RL's name: \"amox\")");
		// (the wiki, "Moons of Peril": three moons fought one by one, restocking between, then the Lunar Chest, "like the
		// Barrows"; the moons aren't in sight between fights; RuneLite's Timers and Buffs: the Eclipse Moon's region 6038)
		own("Moons of Peril").npcs("Blue Moon", "Eclipse Moon", "Blood Moon").deaths().end(End.CHEST)
			.ends("Lunar Chest").idle(Encounters.RUN_IDLE * 2).gone(0).regions(5783, 6038, 5526)
			.line("Your Lunar Chest count is: 30. (Dink)");
		// (the wiki: "teleported into an empty, instanced version of the field"; Demonic Brutus after Desert Treasure II)
		own("Brutus").npcs("Brutus", "Demonic Brutus").regions(12851).line("Your Brutus kill count is: N. (hiscores)");
		own("Mad Angel").npcs("Mad Angel").regions(10018).line("Your Mad Angel kill count is: N. (hiscores)");
		// (the wiki: "the fight takes place inside an instance"; its loot is the corpse's, "Maggot King Corpse")
		own("Maggot King").npcs("Maggot King").line("Your Maggot King kill count is: N. (hiscores; RL's Loot Tracker)");
		own("Shellbane gryphon").npcs("Shellbane gryphon").line("Your Shellbane Gryphon kill count is: N. (hiscores)");
		// (the count comes as its chest is opened after the fight (Dink: "chests opened"): no end at its death)
		own("Obor").npcs("Obor").deaths()
			.line("[not quoted; by Bryophyta's: \"Your Obor chests opened count is: N.\"; the wiki: the kill count and the chest after the fight]");
		own("Bryophyta").npcs("Bryophyta").deaths().regions(12955)
			.line("Your Bryophyta chests opened count is: 5. (Dink)");
		own("The Mimic").npcs("The Mimic").line("Your Mimic kill count is: N. (hiscores)");
		own("Deranged archaeologist").npcs("Deranged archaeologist").regions(14649)
			.line("Your Deranged Archaeologist kill count is: N. (RL's name: \"deranged arch\")");
		// (RuneLite's NightmareZonePlugin: "NMZ and the KBD lair uses the same region ID but NMZ uses planes 1-3 and KBD
		// uses plane 0"; the wiki: "the lair itself is not in the Wilderness"; and its Private lever's instance)
		own("King Black Dragon").npcs("King Black Dragon").regions(9033)
			.line("Your King Black Dragon kill count is: 420. (Dink)");
		// (the wiki: one to six JalTok-Jads at once, a challenge each, in Mor Ul Rek; its line names the challenge)
		own("TzHaar-Ket-Rak's Challenges").npcs("JalTok-Jad").deaths().end(End.WAVES).ends(challenges().toArray(new String[0]))
			.line("Your completion count for TzHaar-Ket-Rak's First Challenge is: 1. (RL)");
		row("Doom of Mokhaiotl", "boss").start(Start.OWN).end(End.DELVE).npcs("Doom of Mokhaiotl", "Doom of Mokhaiotl (Shielded)",
			"Doom of Mokhaiotl (Burrowed)").idle(0).line("(a delve's count lines pass by: Encounters' round four)");

		// ---- the Wilderness: never (OutsidePlugin.held)
		wilderness("Artio", "hiscores");
		wilderness("Callisto", "hiscores");
		wilderness("Calvar'ion", "RL's name: \"calvarion\"");
		wilderness("Vet'ion", "RL's name: \"vetion\"");
		wilderness("Spindel", "hiscores");
		wilderness("Venenatis", "RL's name: \"vene\"");
		wilderness("Chaos Elemental", "RL's name: \"chaos ele\"");
		wilderness("Chaos Fanatic", "hiscores");
		wilderness("Crazy archaeologist", "RL's name: \"crazy arch\"");
		wilderness("Scorpia", "hiscores");

		for (Row r : ROWS)
		{
			if (r.npcs.isEmpty() && r.area != null)
			{
				r.npcs.add(r.area);
			}
		}
	}

	/** TzHaar-Ket-Rak's challenges' names as their lines give them */
	private static List<String> challenges()
	{
		List<String> l = new ArrayList<>();
		for (String n : new String[]{"First", "Second", "Third", "Fourth", "Fifth", "Sixth"})
		{
			l.add("TzHaar-Ket-Rak's " + n + " Challenge");
		}
		return l;
	}

	private static List<String> addAll(List<String> to, List<String> more)
	{
		to.addAll(more);
		return to;
	}

	/** every row, in the table's order */
	static final List<Boss> ALL;
	/** by Discord's area name, and a place of its own by its name */
	private static final Map<String, Boss> BY_AREA = new HashMap<>();
	/** a place of its own's rows by their bosses' names (lower case) */
	private static final Map<String, Boss> BY_NPC = new HashMap<>();
	/** rows made for a boss or raid area of Discord's the table doesn't know (a new one discord_areas.py brought in) */
	private static final Map<String, Boss> MADE = new java.util.concurrent.ConcurrentHashMap<>();

	static
	{
		List<Boss> all = new ArrayList<>();
		for (Row r : ROWS)
		{
			Boss b = new Boss(r);
			all.add(b);
			if (b.area != null)
			{
				BY_AREA.put(b.area, b);
			}
			else if (b.place != null)
			{
				BY_AREA.put(b.name, b);
				for (String n : b.npcs)
				{
					BY_NPC.put(n, b);
				}
			}
		}
		ALL = Collections.unmodifiableList(all);
	}

	/** The row for an area: Discord's (or a place of its own, by its name); a boss or raid area of Discord's the table
	 *  hasn't a row for gets the plain one (PLACE and one kill; a raid's whole), so a new area still records. */
	static Boss forArea(Presence.Area a)
	{
		if (a == null)
		{
			return null;
		}
		Boss b = BY_AREA.get(a.name);
		if (b != null || !(Presence.BOSS.equals(a.kind) || Presence.RAID.equals(a.kind)))
		{
			return b;
		}
		return MADE.computeIfAbsent(a.name, n ->
		{
			Row r = new Row(n, a.kind);
			r.area = n;
			r.regions = a.regions;
			r.npcs.add(n);
			if (Presence.RAID.equals(a.kind))
			{
				r.start = Start.ENTRY;
				r.end = End.RAID;
				r.idle = 0;
			}
			return new Boss(r);
		});
	}

	/** The row by its own name, or null. */
	static Boss named(String name)
	{
		for (Boss b : ALL)
		{
			if (b.name.equals(name))
			{
				return b;
			}
		}
		return null;
	}

	/** A boss Discord has no area for whose fight starts a recording wherever it is: its row, by the NPC's name, or null. */
	static Boss placeless(String npc)
	{
		return npc == null ? null : BY_NPC.get(norm(npc));
	}

	/** A count line's name as KILL_LINE's first group gives it, as the rows' ends are written: the chests a boss's count
	 *  is of taken off ("Bryophyta chests opened" is Bryophyta's). */
	static String counted(String group)
	{
		if (group == null)
		{
			return null;
		}
		String s = group.replace(' ', ' ').trim();
		for (String tail : new String[]{" chests opened", " opened"})
		{
			if (s.toLowerCase(Locale.ROOT).endsWith(tail))
			{
				s = s.substring(0, s.length() - tail.length());
			}
		}
		return s;
	}

	static String norm(String s)
	{
		return s.replace(' ', ' ').trim().toLowerCase(Locale.ROOT);
	}

	private static Set<String> lower(List<String> names)
	{
		Set<String> out = new java.util.LinkedHashSet<>();
		for (String n : names)
		{
			out.add(norm(n));
		}
		return Collections.unmodifiableSet(out);
	}

	/** Discord's area of this name, or null */
	static Presence.Area discord(String name)
	{
		for (Presence.Area a : Presence.AREAS)
		{
			if (a.name.equals(name))
			{
				return a;
			}
		}
		return null;
	}

	private EncounterBosses()
	{
	}
}
