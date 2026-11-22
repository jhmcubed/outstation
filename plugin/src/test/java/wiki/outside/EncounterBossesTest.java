package wiki.outside;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Every boss, raid and boss-type minigame's expected behaviour (11/22/2026; plugin/docs/encounter-bosses.md): one case a
 * row, written here apart from the table (EncounterBosses) from what the game does, and walked through the recorder's
 * own decisions (Encounters.recordedArea, startsOnEntry, startsByFight, placeOf, killEnds, deathEnds, idleEnds): where it
 * starts (on entering, or with the fight, an instance or not), which NPC names are its boss, which count lines end it
 * (and why) and which pass by, whose death with no count line ends it, and how long a fight may go quiet. Then the
 * table against its sources: Discord's areas (Presence), the hiscores' bosses (RuneLite's HiscoreSkill), and the cache's
 * NPC names (OpenRS2's cache 2772, revision 242).
 */
public class EncounterBossesTest
{
	/** a row's expected behaviour */
	private static final class Case
	{
		final String row;
		/** a region of Discord's area for it (its plane 0), or -1: no area, started by its boss's fight */
		int region = -1;
		/** "entry" (always on entering), "place" (an instance on entering, the open copy with the fight), "fight", "own"
		 *  (its own rule: a run, a delve), "never" */
		String start;
		final List<String> bosses = new ArrayList<>(), notBosses = new ArrayList<>();
		/** a count line and why it ends it */
		final Map<String, String> ends = new HashMap<>();
		/** count lines that pass by */
		final List<String> passes = new ArrayList<>();
		/** several bosses: a line with another fought still up doesn't end it */
		boolean last;
		/** deaths with no count line that end it, and those that don't ({id, name}) */
		final List<Object[]> dies = new ArrayList<>(), lives = new ArrayList<>();
		/** ticks a fight may go quiet where it started by the fight (0: never) */
		int idle = -1;

		Case(String row)
		{
			this.row = row;
		}

		Case at(int r)
		{
			region = r;
			return this;
		}

		Case start(String s)
		{
			start = s;
			return this;
		}

		Case boss(String... n)
		{
			bosses.addAll(Arrays.asList(n));
			return this;
		}

		Case notBoss(String... n)
		{
			notBosses.addAll(Arrays.asList(n));
			return this;
		}

		Case ends(String line, String why)
		{
			ends.put(line, why);
			return this;
		}

		Case passes(String... lines)
		{
			passes.addAll(Arrays.asList(lines));
			return this;
		}

		Case last()
		{
			last = true;
			return this;
		}

		Case dies(int id, String name)
		{
			dies.add(new Object[]{id, name});
			return this;
		}

		Case lives(int id, String name)
		{
			lives.add(new Object[]{id, name});
			return this;
		}

		Case idle(int t)
		{
			idle = t;
			return this;
		}
	}

	private static final List<Case> CASES = new ArrayList<>();

	private static Case c(String row)
	{
		Case k = new Case(row);
		CASES.add(k);
		return k;
	}

	/** a boss at Discord's area: an instance on entering, the open copy with the fight; one kill */
	private static Case boss(String row, int region, String line)
	{
		return c(row).at(region).start("place").boss(row).ends(line, "killed").idle(Encounters.OPEN_IDLE);
	}

	/** a boss Discord has no area for: its fight, anywhere; one kill */
	private static Case own(String row, String npc, String line)
	{
		return c(row).start("fight").boss(npc).ends(line, "killed").idle(Encounters.OPEN_IDLE);
	}

	static
	{
		// ---- Discord's boss areas
		boss("Abyssal Sire", 11851, "Your Abyssal Sire kill count is: 12.").dies(5886, "Abyssal Sire").notBoss("Abyssal Nexus");
		boss("Araxxor", 14489, "Your Araxxor kill count is: 3.").dies(13668, "Araxxor").notBoss("Araxyte");
		boss("Cerberus", 4883, "Your Cerberus kill count is: 3273.").dies(5862, "Cerberus").notBoss("Summoned Soul");
		boss("Commander Zilyana", 11602, "Your Commander Zilyana kill count is: 9.").dies(2205, "Commander Zilyana")
			.notBoss("Starlight", "Growler", "Bree");
		boss("Corporeal Beast", 11842, "Your Corporeal Beast kill count is: 4.").dies(319, "Corporeal Beast")
			.notBoss("Dark energy core");
		c("Dagannoth Kings").at(11588).start("place").boss("Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme")
			.notBoss("Dagannoth Kings", "Dagannoth").last().idle(Encounters.OPEN_IDLE)
			.ends("Your Dagannoth Rex kill count is: 50.", "killed").ends("Your Dagannoth Supreme kill count is: 7.", "killed")
			.dies(2267, "Dagannoth Rex").dies(2265, "Dagannoth Supreme");
		boss("Duke Sucellus", 12132, "Your Duke Sucellus kill count is: 2.").dies(12166, "Duke Sucellus");
		boss("General Graardor", 11347, "Your General Graardor kill count is: 30.").dies(2215, "General Graardor")
			.notBoss("Sergeant Strongstack", "Sergeant Steelwill", "Sergeant Grimspike");
		boss("Giant Mole", 6993, "Your Giant Mole kill count is: 5.").dies(5779, "Giant Mole").notBoss("Baby Mole");
		// (one count line for both, once both are down: the line is the end)
		c("Grotesque Guardians").at(6727).start("place").boss("Dusk", "Dawn").notBoss("Grotesque Guardians")
			.idle(Encounters.OPEN_IDLE).ends("Your Grotesque Guardians kill count is: 179.", "killed")
			.dies(7888, "Dusk").dies(7850, "Dawn");
		boss("Hespori", 5021, "Your Hespori kill count is: 4.").dies(8583, "Hespori").notBoss("Flower");
		boss("Alchemical Hydra", 5536, "Your Alchemical Hydra kill count is: 100.").dies(8622, "Alchemical Hydra");
		// (the first form's fall is a phase: NpcID.KALPHITE_QUEEN; the second, KALPHITE_FLYINGQUEEN, is the death)
		boss("Kalphite Queen", 13972, "Your Kalphite Queen kill count is: 8.").dies(965, "Kalphite Queen")
			.lives(963, "Kalphite Queen").notBoss("Kalphite Guardian", "Kalphite Worker")
			.ends("Your Kalphite Queen (Echo) kill count is:1", "killed");
		// (a tentacle's fight starts it; only the Kraken's death ends it; "Whirlpool" is neither till disturbed)
		c("Kraken").at(9116).start("place").boss("Kraken", "Enormous Tentacle").notBoss("Whirlpool", "Cave kraken")
			.idle(Encounters.OPEN_IDLE).ends("Your Kraken kill count is: 1,000.", "killed").dies(494, "Kraken")
			.lives(5535, "Enormous Tentacle");
		boss("Kree'arra", 11346, "Your Kree'arra kill count is: 4.").dies(3162, "Kree'arra").notBoss("Wingman Skree");
		boss("K'ril Tsutsaroth", 11603, "Your K'ril Tsutsaroth kill count is: 481").dies(3129, "K'ril Tsutsaroth")
			.notBoss("Balfrug Kreeyath");
		boss("Nex", 11601, "Your Nex kill count is: 17.").dies(11278, "Nex").notBoss("Fumus", "Umbra", "Cruor", "Glacies");
		c("Nightmare of Ashihama").at(15515).start("place").boss("The Nightmare", "Phosani's Nightmare")
			.notBoss("Nightmare of Ashihama", "Totem", "Husk").idle(Encounters.OPEN_IDLE)
			.ends("Your Nightmare kill count is: 1,130", "killed").ends("Your Phosani's Nightmare kill count is: 3.", "killed")
			.dies(9425, "The Nightmare");
		boss("Phantom Muspah", 11330, "Your Phantom Muspah kill count is: 6.").dies(12077, "Phantom Muspah");
		boss("Sarachnis", 7322, "Your Sarachnis kill count is: 12.").dies(8713, "Sarachnis").notBoss("Spawn of Sarachnis");
		boss("Skotizo", 9048, "Your Skotizo kill count is: 2.").dies(7286, "Skotizo").notBoss("Reanimated demon spawn");
		boss("Thermonuclear smoke devil", 9363, "Your Thermonuclear smoke devil kill count is: 40.")
			.dies(499, "Thermonuclear smoke devil").notBoss("Smoke devil");
		c("Tempoross").at(12076).start("never").notBoss("Tempoross");
		boss("The Leviathan", 8291, "Your Leviathan kill count is: 5.").dies(12214, "The Leviathan");
		c("The Royal Titans").at(11669).start("place").boss("Branda the Fire Queen", "Eldric the Ice King")
			.notBoss("Fire elemental", "Ice elemental", "The Royal Titans").idle(Encounters.OPEN_IDLE)
			.ends("Your Royal Titans kill count is: 3.", "killed")
			.dies(12596, "Branda the Fire Queen").dies(14147, "Eldric the Ice King");
		boss("The Whisperer", 10595, "Your Whisperer kill count is: 9.").dies(12204, "The Whisperer");
		boss("Vardorvis", 4405, "Your Vardorvis kill count is: 9.").dies(12223, "Vardorvis");
		// (its zombified spawn is nothing)
		boss("Vorkath", 9023, "Your Vorkath kill count is: 418.").dies(8061, "Vorkath").notBoss("Zombified Spawn")
			.lives(8062, "Zombified Spawn");
		c("Wintertodt").at(6462).start("never").notBoss("Wintertodt");
		boss("Yama", 6045, "Your Yama success count is: 227").dies(14176, "Yama").notBoss("Judge of Yama", "Void flare");
		boss("Zalcano", 12126, "Your Zalcano kill count is: 20.").dies(9049, "Zalcano").notBoss("Golem");
		boss("Zulrah", 9007, "Your Zulrah kill count is: 4.").dies(2042, "Zulrah").dies(2043, "Zulrah").dies(2044, "Zulrah")
			.notBoss("Snakeling");

		// ---- Discord's raid areas: on entering; a raid's count lines are its own rules' (the raid's tests)
		c("Chambers of Xeric").at(13136).start("entry").idle(0).passes("Your completed Chambers of Xeric count is: 51.");
		c("Theatre of Blood").at(12611).start("entry").idle(0).passes("Your completed Theatre of Blood count is: 73.");
		c("Tombs of Amascut").at(14160).start("entry").idle(0).passes("Your completed Tombs of Amascut: Expert Mode count is: 1.");
		c("The Fractured Archive").at(16214).start("entry").idle(0).passes("Your completed Fractured Archive count is: 58.");
		c("Jaltevas Pyramid").at(13454).start("never");
		c("Osmumten's Burial Chamber").at(14672).start("never");

		// ---- Discord's minigames that are a boss fight
		c("Barrows").at(14231).start("fight").boss("Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested",
			"Karil the Tainted", "Torag the Corrupted", "Verac the Defiled").notBoss("Crypt rat", "Bloodworm")
			.ends("Your Barrows chest count is: 277.", "looted").lives(1673, "Dharok the Wretched").idle(Encounters.RUN_IDLE);
		c("The Inferno").at(9043).start("entry").boss("TzKal-Zuk").notBoss("JalTok-Jad", "Jal-Nib", "JalZek")
			.ends("Your TzKal-Zuk kill count is: 2.", "completed").passes("Your TzTok-Jad kill count is: 3.")
			.lives(7706, "TzKal-Zuk").lives(7700, "JalTok-Jad").idle(0);
		c("Tzhaar Fight Caves").at(9551).start("entry").boss("TzTok-Jad").notBoss("Tz-Kih", "Yt-HurKot")
			.ends("Your TzTok-Jad kill count is: 2.", "completed").passes("Your TzKal-Zuk kill count is: 2.")
			.lives(3127, "TzTok-Jad").idle(0);
		c("The Gauntlet").at(7512).start("own");
		c("Corrupted Gauntlet").at(7768).start("own");
		c("Fortis Colosseum").at(7216).start("own");
		c("Fortis Colosseum Lobby").at(7316).start("never");

		// ---- bosses Discord has no area for: their fight, wherever it is
		own("Scurrius", "Scurrius", "Your Scurrius kill count is: 50.").dies(7221, "Scurrius").notBoss("Giant rat");
		own("The Hueycoatl", "The Hueycoatl", "Your Hueycoatl kill count is: 4.").boss("Hueycoatl tail", "Hueycoatl body")
			.dies(14009, "The Hueycoatl").lives(14014, "Hueycoatl tail");
		own("Amoxliatl", "Amoxliatl", "Your Amoxliatl kill count is: 8.").dies(13685, "Amoxliatl");
		// (three moons, then the chest: a moon's line passes, the Lunar Chest's ends it)
		c("Moons of Peril").start("fight").boss("Blue Moon", "Eclipse Moon", "Blood Moon").notBoss("Moon Shield")
			.ends("Your Lunar Chest count is: 30.", "looted").passes("Your Blue Moon kill count is: 30.", "Your Blood Moon kill count is: 2.")
			.lives(13011, "Blood Moon").idle(Encounters.RUN_IDLE * 2);
		own("Brutus", "Brutus", "Your Brutus kill count is: 1.").boss("Demonic Brutus").dies(15626, "Brutus").notBoss("Cow");
		own("Mad Angel", "Mad Angel", "Your Mad Angel kill count is: 1.").dies(16305, "Mad Angel");
		own("Maggot King", "Maggot King", "Your Maggot King kill count is: 1.").dies(15742, "Maggot King")
			.notBoss("Maggot King Corpse", "Ur-maggot larvae");
		own("Shellbane gryphon", "Shellbane gryphon", "Your Shellbane Gryphon kill count is: 2.").dies(14860, "Shellbane gryphon")
			.notBoss("Gryphon");
		// (the count is at the chest after the fight: no end at the death)
		own("Obor", "Obor", "Your Obor chests opened count is: 5.").lives(7416, "Obor");
		own("Bryophyta", "Bryophyta", "Your Bryophyta chests opened count is: 5.").lives(8195, "Bryophyta").notBoss("Growthling");
		own("The Mimic", "The Mimic", "Your Mimic kill count is: 3.").dies(8633, "The Mimic");
		own("Deranged archaeologist", "Deranged archaeologist", "Your Deranged Archaeologist kill count is: 3.")
			.dies(7806, "Deranged archaeologist").notBoss("Crazy archaeologist");
		own("King Black Dragon", "King Black Dragon", "Your King Black Dragon kill count is: 420.").dies(239, "King Black Dragon");
		c("TzHaar-Ket-Rak's Challenges").start("fight").boss("JalTok-Jad").idle(Encounters.OPEN_IDLE)
			.ends("Your completion count for TzHaar-Ket-Rak's First Challenge is: 1.", "completed")
			.ends("Your completion count for TzHaar-Ket-Rak's Sixth Challenge is: 12.", "completed")
			.passes("Your TzTok-Jad kill count is: 3.").lives(10623, "JalTok-Jad");
		c("Doom of Mokhaiotl").start("own");

		// ---- the Wilderness: never
		for (String w : new String[]{"Artio", "Callisto", "Calvar'ion", "Vet'ion", "Spindel", "Venenatis", "Chaos Elemental",
			"Chaos Fanatic", "Crazy archaeologist", "Scorpia"})
		{
			c(w).start("never").notBoss(w);
		}
	}

	/** Each row, through the recorder's decisions. */
	@Test
	public void everyRowStartsAndEndsAsItShould()
	{
		Set<String> seen = new HashSet<>();
		for (Case k : CASES)
		{
			String r = k.row;
			EncounterBosses.Boss b = EncounterBosses.named(r);
			assertNotNull(r + ": a row in the table", b);
			assertTrue(r + ": one case a row", seen.add(r));
			Presence.Area at = null;
			if (k.region >= 0)
			{
				// (Discord's area by its region, and the table's row for it)
				at = Presence.area(k.region, 0);
				assertNotNull(r + ": Discord's area at " + k.region, at);
				assertSame(r + ": its row", b, EncounterBosses.forArea(at));
				assertEquals(r + ": Discord names it", r, at.name);
			}
			else if ("fight".equals(k.start))
			{
				// (no area of Discord's: the place named for it, by its boss's fight)
				for (String n : k.bosses)
				{
					at = Encounters.placeOf(n);
					assertNotNull(r + ": " + n + "'s fight starts it with no area", at);
					assertSame(r + ": " + n, b, EncounterBosses.forArea(at));
					assertTrue(r, Encounters.placeless(at));
				}
				assertEquals(r + ": its place's name", r, at.name);
			}
			switch (k.start)
			{
				case "entry":
					assertNotNull(r + ": recorded", Encounters.recordedArea(at));
					assertTrue(r + ": on entering an instance", Encounters.startsOnEntry(at, true));
					assertTrue(r + ": on entering, open", Encounters.startsOnEntry(at, false));
					assertFalse(r + ": not by a fight", Encounters.openArea(at, true) || Encounters.openArea(at, false));
					break;
				case "place":
					assertNotNull(r + ": recorded", Encounters.recordedArea(at));
					assertTrue(r + ": an instance on entering", Encounters.startsOnEntry(at, true));
					assertFalse(r + ": an instance's fight starts nothing more", Encounters.openArea(at, true));
					assertFalse(r + ": the open copy not on entering", Encounters.startsOnEntry(at, false));
					assertTrue(r + ": the open copy with the fight", Encounters.openArea(at, false));
					break;
				case "fight":
					assertFalse(r + ": never on entering", Encounters.startsOnEntry(at, true) || Encounters.startsOnEntry(at, false));
					assertTrue(r + ": with the fight, open", Encounters.openArea(at, false));
					assertTrue(r + ": with the fight, an instance", Encounters.openArea(at, true));
					if (k.region >= 0)
					{
						assertNotNull(r + ": recorded", Encounters.recordedArea(at));
					}
					break;
				case "own":
				case "never":
					if (at != null)
					{
						assertNull(r + ": no recording by its area", Encounters.recordedArea(at));
						assertFalse(r, Encounters.startsOnEntry(at, true) || Encounters.startsOnEntry(at, false));
						assertFalse(r, Encounters.openArea(at, true) || Encounters.openArea(at, false));
					}
					assertTrue(r + ": by its own rule or never: " + b.start, b.start == EncounterBosses.Start.OWN
						? "own".equals(k.start) : b.start == EncounterBosses.Start.NEVER && "never".equals(k.start));
					break;
				default:
					throw new AssertionError(r + ": " + k.start);
			}
			// (its boss, any case, and a non-breaking space as the game sends it)
			for (String n : k.bosses)
			{
				assertTrue(r + ": " + n + " is its boss", Encounters.bossOf(at, n));
				assertEquals(r + ": " + n + "'s fight starts it, open", "place".equals(k.start) || "fight".equals(k.start),
					Encounters.startsByFight(at, false, n));
				assertTrue(r + ": " + n + " lower case", Encounters.bossOf(at, n.toLowerCase(Locale.ROOT)));
				assertTrue(r + ": " + n + " with a non-breaking space", Encounters.bossOf(at, n.replace(' ', ' ')));
			}
			for (String n : k.notBosses)
			{
				// (a row that's never recorded: its boss's fight starts nothing; any other: the name isn't its boss)
				assertFalse(r + ": " + n + " isn't its boss", at != null && (!"never".equals(k.start) && Encounters.bossOf(at, n)
					|| Encounters.startsByFight(at, false, n) || Encounters.startsByFight(at, true, n)));
				Presence.Area p = Encounters.placeOf(n);
				assertTrue(r + ": " + n + " starts nothing of its own", p == null || "never".equals(k.start) || !p.name.equals(r));
			}
			// (the count lines: which end it, and why; which pass by)
			for (Map.Entry<String, String> e : k.ends.entrySet())
			{
				assertEquals(r + ": " + e.getKey(), e.getValue(), Encounters.killEnds(at, e.getKey(), false));
				if (k.last)
				{
					assertNull(r + ": another fought still up: " + e.getKey(), Encounters.killEnds(at, e.getKey(), true));
				}
				else
				{
					assertEquals(r + ": one boss: " + e.getKey(), e.getValue(), Encounters.killEnds(at, e.getKey(), true));
				}
			}
			for (String l : k.passes)
			{
				assertNotNull(r + ": a count line: " + l, Encounters.killName(l));
				assertNull(r + ": passes by: " + l, Encounters.killEnds(at, l, false));
			}
			// (the deaths with no count line)
			for (Object[] d : k.dies)
			{
				assertTrue(r + ": " + d[1] + " (" + d[0] + ") dead ends it", Encounters.deathEnds(at, (Integer) d[0], (String) d[1]));
			}
			for (Object[] d : k.lives)
			{
				assertFalse(r + ": " + d[1] + " (" + d[0] + ") dead doesn't", Encounters.deathEnds(at, (Integer) d[0], (String) d[1]));
			}
			// (a fight gone quiet, where it started by the fight; never in an instance entered)
			if (k.idle > 0)
			{
				assertFalse(r + ": quiet a tick short", Encounters.idleEnds(at, false, 1000 + k.idle - 1, 1000));
				assertTrue(r + ": quiet " + k.idle + " ticks", Encounters.idleEnds(at, false, 1000 + k.idle, 1000));
				// (an instance entered never goes quiet; where the fight always starts it, an instance's does)
				assertEquals(r + ": quiet in an instance", "fight".equals(k.start), Encounters.idleEnds(at, true, 1000 + k.idle, 1000));
			}
			else if (k.idle == 0 && at != null)
			{
				assertFalse(r + ": never quiet", Encounters.idleEnds(at, false, 100000, 0) || Encounters.idleEnds(at, true, 100000, 0));
			}
		}
		assertEquals("every row has a case", EncounterBosses.ALL.size(), CASES.size());
	}

	/** The count lines read, the game's own as RuneLite's and Dink's tests quote them. */
	@Test
	public void theCountLinesAreRead()
	{
		assertEquals("Vorkath", Encounters.killName("Your Vorkath kill count is: 418."));
		assertEquals("Bryophyta", Encounters.killName("Your Bryophyta chests opened count is: 5."));
		assertEquals("Barrows", Encounters.killName("Your Barrows chest count is: 277."));
		assertEquals("Lunar Chest", Encounters.killName("Your Lunar Chest count is: 30."));
		assertEquals("Yama", Encounters.killName("Your Yama success count is: 227"));
		assertEquals("Gauntlet", Encounters.killName("Your Gauntlet completion count is: 123."));
		assertEquals("Chambers of Xeric", Encounters.killName("Your completed Chambers of Xeric count is: 51."));
		assertEquals("Tombs of Amascut: Expert Mode", Encounters.killName("Your completed Tombs of Amascut: Expert Mode count is: 1."));
		assertEquals("TzHaar-Ket-Rak's First Challenge", Encounters.killName("Your completion count for TzHaar-Ket-Rak's First Challenge is: 1."));
		assertEquals("Kalphite Queen (Echo)", Encounters.killName("Your Kalphite Queen (Echo) kill count is:1"));
		assertEquals("Nightmare", Encounters.killName("Your Nightmare kill count is: @mes_hl_red@1,130"));
		// (no fight's: an agility course's laps, the Agility Arena's tickets, the herbiboar's harvests)
		assertNull(Encounters.killName("Your Prifddinas Agility Course lap count is: @mes_hl_red@2."));
		assertNull(Encounters.killName("Your Agility Arena Total Ticket count is: 206."));
		assertNull(Encounters.killName("Your herbiboar harvest count is: 4091."));
		assertNull(Encounters.killName("You have completed 152 hard Treasure Trails."));
		assertNull(Encounters.killName("Wave: 63"));
		assertNull(Encounters.killName(null));
		// (a recording by hand or for a listed NPC: any count line is the kill, as before)
		assertEquals("killed", Encounters.killEnds(null, "Your Testfight kill count is: 418.", false));
		assertNull(Encounters.killEnds(null, "Your Seers' Village Rooftop lap count is: 9.", false));
	}

	/** The Inferno walked through: waves, a death of a Jad, Zuk; one recording, ended only by Zuk's line. */
	@Test
	public void theInfernoIsOneRecording()
	{
		Presence.Area inferno = Presence.area(9043, 0);
		assertEquals("The Inferno", inferno.name);
		assertTrue(Encounters.startsOnEntry(inferno, true));
		for (String l : new String[]{"Wave: 1", "Wave: 66", "Wave: 69"})
		{
			assertNull(l, Encounters.killEnds(inferno, l, false));
		}
		assertFalse(Encounters.deathEnds(inferno, 7700, "JalTok-Jad"));
		assertFalse(Encounters.deathEnds(inferno, 7706, "TzKal-Zuk"));
		assertFalse(Encounters.idleEnds(inferno, true, 1_000_000, 0));
		assertEquals("completed", Encounters.killEnds(inferno, "Your TzKal-Zuk kill count is: 2.", false));
		// (and the Fight Cave's the same, to Jad)
		Presence.Area caves = Presence.area(9551, 0);
		assertEquals("Tzhaar Fight Caves", caves.name);
		assertTrue(Encounters.startsOnEntry(caves, false) && Encounters.startsOnEntry(caves, true));
		assertEquals("completed", Encounters.killEnds(caves, "Your TzTok-Jad kill count is: 46", false));
		// (a long way out of named ground, as a raid's: a map load between waves isn't leaving)
		assertEquals(Encounters.LEFT_RAID, EncounterBosses.forArea(inferno).grace);
		assertEquals(Encounters.LEFT_RAID, EncounterBosses.forArea(caves).grace);
	}

	/** The Dagannoth Kings walked through: Rex down with Prime fought and up goes on; Prime's line, none up, ends it. */
	@Test
	public void severalBossesEndWithTheLast()
	{
		Presence.Area dks = Presence.area(11589, 0);
		assertEquals("Dagannoth Kings", dks.name);
		assertNull(Encounters.killEnds(dks, "Your Dagannoth Rex kill count is: 50.", true));
		assertEquals("killed", Encounters.killEnds(dks, "Your Dagannoth Prime kill count is: 51.", false));
		// (one king fought alone: its own line ends it, the next king's fight the next recording)
		assertEquals("killed", Encounters.killEnds(dks, "Your Dagannoth Supreme kill count is: 7.", false));
	}

	/** Barrows and the Moons of Peril walked through: a brother's or a moon's fall is nothing; their chest ends it. */
	@Test
	public void aChestEndsItsRun()
	{
		Presence.Area barrows = Presence.area(14131, 0);
		assertEquals("Barrows", barrows.name);
		assertTrue(Encounters.startsByFight(barrows, false, "Ahrim the Blighted"));
		for (String brother : new String[]{"Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested", "Karil the Tainted",
			"Torag the Corrupted", "Verac the Defiled"})
		{
			assertFalse(brother, Encounters.deathEnds(barrows, 1, brother));
		}
		assertFalse("the tunnels' quiet stretches", Encounters.idleEnds(barrows, false, Encounters.OPEN_IDLE * 3, 0));
		assertEquals("looted", Encounters.killEnds(barrows, "Your Barrows chest count is: 268", false));
		Presence.Area moons = Encounters.placeOf("Eclipse Moon");
		assertEquals("Moons of Peril", moons.name);
		assertSame(moons, Encounters.placeOf("Blood Moon"));
		assertNull(Encounters.killEnds(moons, "Your Eclipse Moon kill count is: 4.", false));
		assertFalse(Encounters.deathEnds(moons, 13013, "Blue Moon"));
		assertFalse("restocking between moons", Encounters.idleEnds(moons, false, Encounters.RUN_IDLE, 0));
		assertEquals("looted", Encounters.killEnds(moons, "Your Lunar Chest count is: 30.", false));
		assertEquals("never left by the moons out of sight", 0, EncounterBosses.forArea(moons).gone);
	}

	/** A boss of its own place is left once none of its NPCs has been in sight a while; never one of Discord's areas. */
	@Test
	public void aPlaceOfItsOwnIsLeftWhenItsBossIsGone()
	{
		EncounterBosses.Boss scurrius = EncounterBosses.forArea(Encounters.placeOf("Scurrius"));
		assertFalse(scurrius.goneEnds(Encounters.WATCHED_GONE - 1, 0));
		assertTrue(scurrius.goneEnds(Encounters.WATCHED_GONE, 0));
		assertFalse(EncounterBosses.forArea(Presence.area(6993, 0)).goneEnds(1_000_000, 0));
		// (the Wilderness's bosses have no place, and nothing of Discord's areas is one)
		for (String w : new String[]{"Callisto", "Vet'ion", "Scorpia", "Chaos Elemental", "Crazy archaeologist"})
		{
			assertNull(w, Encounters.placeOf(w));
		}
		assertNull(Encounters.placeOf("Vorkath"));
		assertNull(Encounters.placeOf("General Graardor"));
		assertNull(Encounters.placeOf("Crystalline Hunllef"));
		assertNull(Encounters.placeOf("Doom of Mokhaiotl"));
	}

	/** Every boss and raid area of Discord's has its row, with Discord's own regions; every row's area is Discord's; the
	 *  rows' names, and their own places' names, are each one row's. */
	@Test
	public void theTableCoversDiscordsAreas()
	{
		for (Presence.Area a : Presence.AREAS)
		{
			if (Presence.BOSS.equals(a.kind) || Presence.RAID.equals(a.kind))
			{
				EncounterBosses.Boss b = EncounterBosses.named(a.name);
				assertNotNull(a.name + ": a row", b);
				assertEquals(a.name, a.name, b.area);
			}
		}
		Set<String> names = new HashSet<>();
		for (EncounterBosses.Boss b : EncounterBosses.ALL)
		{
			assertTrue(b.name + ": one row", names.add(b.name));
			if (b.area != null)
			{
				Presence.Area d = EncounterBosses.discord(b.area);
				assertNotNull(b.name + ": Discord's " + b.area, d);
				assertArrayEquals(b.name, d.regions, b.regions);
			}
			else
			{
				assertNull(b.name + ": its own place isn't one of Discord's names", EncounterBosses.discord(b.name));
			}
			assertNotNull(b.name + ": its count line, as the game words it, or why it isn't known", b.line);
		}
		// (a new boss area discord_areas.py brings in: one kill, as the plain row)
		Presence.Area fresh = new Presence.Area("A New Boss", Presence.BOSS, new int[]{1});
		assertTrue(Encounters.startsOnEntry(fresh, true));
		assertTrue(Encounters.startsByFight(fresh, false, "A New Boss"));
		assertEquals("killed", Encounters.killEnds(fresh, "Your A New Boss kill count is: 1.", false));
	}

	private static void assertArrayEquals(String what, int[] a, int[] b)
	{
		assertEquals(what, Arrays.toString(a), Arrays.toString(b));
	}

	/** The write-up (plugin/docs/encounter-bosses.md): a row in its table for each of the table's, and its counts. */
	@Test
	public void theWriteUpIsTheTable() throws Exception
	{
		java.nio.file.Path doc = java.nio.file.Paths.get("docs", "encounter-bosses.md");
		String text = new String(java.nio.file.Files.readAllBytes(doc), java.nio.charset.StandardCharsets.UTF_8);
		for (EncounterBosses.Boss b : EncounterBosses.ALL)
		{
			assertTrue(b.name + ": its row in the write-up", text.contains("\n| " + b.name + " | "));
		}
		Map<EncounterBosses.Start, Integer> starts = new java.util.EnumMap<>(EncounterBosses.Start.class);
		Map<EncounterBosses.End, Integer> ends = new java.util.EnumMap<>(EncounterBosses.End.class);
		for (EncounterBosses.Boss b : EncounterBosses.ALL)
		{
			starts.merge(b.start, 1, Integer::sum);
			ends.merge(b.end, 1, Integer::sum);
		}
		assertEquals(70, EncounterBosses.ALL.size());
		assertEquals("{ENTRY=6, PLACE=30, FIGHT=15, OWN=4, NEVER=15}", starts.toString());
		assertEquals("{KILL=41, LAST=1, WAVES=3, CHEST=2, RUN=3, RAID=4, DELVE=1, NONE=15}", ends.toString());
	}

	/** RuneLite's hiscores' bosses (HiscoreSkill, master 11/18/2026), each a row, by its name or its boss's. */
	@Test
	public void everyHiscoreBossHasARow()
	{
		Map<String, String> as = new HashMap<>();
		as.put("Barrows Chests", "Barrows");
		as.put("Lunar Chests", "Moons of Peril");
		as.put("Nightmare", "Nightmare of Ashihama");
		as.put("Phosani's Nightmare", "Nightmare of Ashihama");
		as.put("Mimic", "The Mimic");
		as.put("Chambers of Xeric: Challenge Mode", "Chambers of Xeric");
		as.put("Theatre of Blood: Hard Mode", "Theatre of Blood");
		as.put("Tombs of Amascut: Expert Mode", "Tombs of Amascut");
		as.put("The Corrupted Gauntlet", "Corrupted Gauntlet");
		String[] hiscores = {"Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Artio", "Barrows Chests", "Brutus",
			"Bryophyta", "Callisto", "Calvar'ion", "Cerberus", "Chambers of Xeric", "Chambers of Xeric: Challenge Mode",
			"Chaos Elemental", "Chaos Fanatic", "Commander Zilyana", "Corporeal Beast", "Crazy Archaeologist", "Dagannoth Prime",
			"Dagannoth Rex", "Dagannoth Supreme", "Deranged Archaeologist", "Doom of Mokhaiotl", "Duke Sucellus",
			"General Graardor", "Giant Mole", "Grotesque Guardians", "Hespori", "Kalphite Queen", "King Black Dragon", "Kraken",
			"Kree'Arra", "K'ril Tsutsaroth", "Lunar Chests", "Mad Angel", "Maggot King", "Mimic", "Nex", "Nightmare",
			"Phosani's Nightmare", "Obor", "Phantom Muspah", "Sarachnis", "Scorpia", "Scurrius", "Shellbane Gryphon", "Skotizo",
			"Sol Heredit", "Spindel", "Tempoross", "The Gauntlet", "The Corrupted Gauntlet", "The Hueycoatl", "The Leviathan",
			"The Royal Titans", "The Whisperer", "Theatre of Blood", "Theatre of Blood: Hard Mode", "The Fractured Archive",
			"Thermonuclear Smoke Devil", "Tombs of Amascut", "Tombs of Amascut: Expert Mode", "TzKal-Zuk", "TzTok-Jad",
			"Vardorvis", "Venenatis", "Vet'ion", "Vorkath", "Wintertodt", "Yama", "Zalcano", "Zulrah"};
		for (String h : hiscores)
		{
			EncounterBosses.Boss found = as.containsKey(h) ? EncounterBosses.named(as.get(h)) : null;
			for (EncounterBosses.Boss b : EncounterBosses.ALL)
			{
				if (found == null && (b.name.equalsIgnoreCase(h) || b.npcs.contains(h.toLowerCase(Locale.ROOT))))
				{
					found = b;
				}
			}
			assertNotNull(h + ": a row", found);
		}
	}

	/** The cache's NPC names (OpenRS2's cache 2772, revision 242, 11/18/2026: its NPC configs, name to ids), and every
	 *  boss name in the table one of them, as the cache spells it. */
	private static final Map<String, int[]> CACHE = new HashMap<>();

	private static void cache(String name, int... ids)
	{
		CACHE.put(name, ids);
	}

	static
	{
		cache("Abyssal Sire", 5886, 5887, 5888, 5889, 5890, 5891, 5908, 11961);
		cache("Araxxor", 13668, 13669);
		cache("Cerberus", 5862, 5863, 5866, 13657, 15612);
		cache("Commander Zilyana", 2205, 6493, 12445);
		cache("Corporeal Beast", 319);
		cache("Dagannoth Rex", 2267, 6498, 12439, 15620);
		cache("Dagannoth Prime", 2266, 6497, 12442, 15619);
		cache("Dagannoth Supreme", 2265, 6496, 12441, 15618);
		cache("Duke Sucellus", 12166, 12167, 12191, 12192, 12193, 12194, 12195, 12196);
		cache("General Graardor", 2215, 6494, 12444);
		cache("Giant Mole", 5779, 6499);
		cache("Dusk", 7849, 7851, 7854, 7855, 7882, 7883, 7886, 7887, 7888, 7889, 15621, 15622);
		cache("Dawn", 7850, 7852, 7853, 7884, 7885, 15623, 15635);
		cache("Hespori", 8583, 11192, 15615);
		cache("Alchemical Hydra", 8615, 8616, 8617, 8618, 8619, 8620, 8621, 8622, 8634, 11962);
		cache("Kalphite Queen", 128, 963, 965, 4303, 4304, 6500, 6501, 15616, 15617);
		cache("Kraken", 494, 6640, 6656);
		cache("Enormous Tentacle", 5535, 10708, 10709);
		cache("Kree'arra", 3162, 6492, 12443, 15697);
		cache("K'ril Tsutsaroth", 3129, 6495, 12446);
		cache("Nex", 11278, 11279, 11280, 11281, 11282);
		cache("The Nightmare", 378, 9425, 9426, 9427, 9428, 9429, 9430, 9431, 9432, 9433, 9460, 9461, 9462, 9463, 9464);
		cache("Phosani's Nightmare", 377, 9416, 9417, 9418, 9419, 9420, 9421, 9422, 9423, 9424, 11153, 11154, 11155);
		cache("Phantom Muspah", 12077, 12078, 12079, 12080, 12082, 15549);
		cache("Sarachnis", 8713);
		cache("Skotizo", 7286);
		cache("Thermonuclear smoke devil", 499, 13659, 15611);
		cache("Tempoross", 6257, 10572, 10574, 10575);
		cache("The Leviathan", 12214, 12215, 12219, 12591);
		cache("Branda the Fire Queen", 12596, 14148);
		cache("Eldric the Ice King", 14147, 14149);
		cache("The Whisperer", 12204, 12205, 12206, 12207);
		cache("Vardorvis", 12223, 12224, 12228, 12425, 12426, 13656);
		cache("Vorkath", 8026, 8058, 8059, 8060, 8061, 11959);
		cache("Yama", 124, 14176, 15555, 15645, 15700);
		cache("Zalcano", 9049, 9050, 12124);
		cache("Zulrah", 2042, 2043, 2044);
		cache("Ahrim the Blighted", 1672, 12316, 12322);
		cache("Dharok the Wretched", 1673, 12317, 12323, 12447);
		cache("Guthan the Infested", 1674, 12318, 12324);
		cache("Karil the Tainted", 1675, 12319, 12325);
		cache("Torag the Corrupted", 1676, 12320, 12326);
		cache("Verac the Defiled", 1677, 12321, 12327);
		cache("TzKal-Zuk", 7706);
		cache("TzTok-Jad", 3127, 6506, 13661, 15574, 15699);
		cache("Crystalline Hunllef", 9021, 9022, 9023, 9024, 12123, 15613, 15614);
		cache("Corrupted Hunllef", 9035, 9036, 9037, 9038);
		cache("Sol Heredit", 12821, 12827, 15554, 15696);
		cache("Scurrius", 7221, 7222, 15548, 15695);
		cache("The Hueycoatl", 14009, 14010, 14011, 14012, 14013);
		cache("Hueycoatl tail", 14014);
		cache("Hueycoatl body", 14017);
		cache("Amoxliatl", 13685, 13686, 13687, 13689);
		cache("Blue Moon", 13013);
		cache("Eclipse Moon", 13012);
		cache("Blood Moon", 13011);
		cache("Brutus", 15626, 15627);
		cache("Demonic Brutus", 15628, 15629);
		cache("Mad Angel", 16305, 16306, 16307, 16308, 16309, 16310, 16311, 16312, 16314, 16315);
		cache("Maggot King", 15742);
		cache("Maggot King Corpse", 15741);
		cache("Shellbane gryphon", 14860, 15010);
		cache("Obor", 7416);
		cache("Bryophyta", 8195);
		cache("The Mimic", 7979, 8633);
		cache("Deranged archaeologist", 7806);
		cache("King Black Dragon", 239, 2642, 6502, 12440);
		cache("JalTok-Jad", 7700, 7704, 10623);
		cache("Doom of Mokhaiotl", 14707);
		cache("Doom of Mokhaiotl (Shielded)", 14708);
		cache("Doom of Mokhaiotl (Burrowed)", 14709);
		cache("Artio", 11992);
		cache("Callisto", 6503, 6609);
		cache("Calvar'ion", 11993, 11994, 11995);
		cache("Vet'ion", 6611, 6612, 12002);
		cache("Spindel", 11998);
		cache("Venenatis", 6504, 6610);
		cache("Chaos Elemental", 2054, 6505);
		cache("Chaos Fanatic", 6619);
		cache("Crazy archaeologist", 6618);
		cache("Scorpia", 6615);
	}

	@Test
	public void everyBossNameIsTheCaches()
	{
		Set<String> cached = new HashSet<>();
		for (String n : CACHE.keySet())
		{
			cached.add(n.toLowerCase(Locale.ROOT));
		}
		for (EncounterBosses.Boss b : EncounterBosses.ALL)
		{
			if ("raid".equals(b.kind) || "Wintertodt".equals(b.name) || b.end == EncounterBosses.End.NONE && !"wilderness".equals(b.kind))
			{
				continue;
			}
			for (String n : b.npcs)
			{
				assertTrue(b.name + ": " + n + " is a name in the cache", cached.contains(n));
			}
		}
		// (the Kalphite Queen's phase is her first form, one of hers; Doom's forms are DOOM_IDS)
		assertTrue(Arrays.stream(CACHE.get("Kalphite Queen")).anyMatch(id -> id == 963));
		Set<Integer> doom = new HashSet<>();
		for (String n : new String[]{"Doom of Mokhaiotl", "Doom of Mokhaiotl (Shielded)", "Doom of Mokhaiotl (Burrowed)"})
		{
			for (int id : CACHE.get(n))
			{
				doom.add(id);
			}
		}
		assertEquals(Encounters.DOOM_IDS, doom);
	}
}
