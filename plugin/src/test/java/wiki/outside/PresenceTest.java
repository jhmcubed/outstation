package wiki.outside;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What they're doing, as RuneLite's Discord plugin shows it (11/16/2026; Presence). */
public class PresenceTest
{
	/** the relays' own checks of a presence (game.ts's AREA_NAME, PRESENCE_KINDS, PRESENCE_GRID; relay.py's the same) */
	private static final Pattern AREA_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 '.()&-]{0,47}");
	private static final Set<String> KINDS = new HashSet<>(java.util.Arrays.asList("boss", "city", "dungeon", "minigame", "raid", "region"));

	@Test
	public void discordsAreasEachWithAPointTheRelaysTake()
	{
		assertEquals(398, Presence.AREAS.length);
		int placed = 0;
		for (Presence.Area a : Presence.AREAS)
		{
			assertTrue(a.name, AREA_NAME.matcher(a.name).matches());
			assertTrue(a.name, KINDS.contains(a.kind));
			if (a.at == null)
			{
				continue;
			}
			placed++;
			boolean zanaris = a.at[0] == 2368 && a.at[1] == 4416;
			assertTrue(a.name, zanaris || (a.at[0] % 32 == 0 && a.at[1] % 32 == 0 && a.at[0] >= 960 && a.at[0] < 4096
				&& a.at[1] >= 2048 && a.at[1] < 4224));
		}
		assertTrue(placed > 300);
	}

	@Test
	public void areasByTheirRegionsAndWhereTheGlobeStandsThem()
	{
		Presence.Area wc = Presence.area(6198, 0);
		assertEquals("Woodcutting Guild", wc.name);
		assertEquals("region", wc.kind);
		assertNotNull(wc.at);
		assertEquals("Zulrah", Presence.area(9007, 0).name);
		assertArrayEquals(new int[]{2368, 4416}, Presence.area(9541, 0).at);              // (Zanaris, on the moon)
		assertArrayEquals(new int[]{1216, 3520}, Presence.area(12889, 0).at);             // (the Chambers: Mount Quidamortem)
		assertArrayEquals(new int[]{2880, 3776}, Presence.area(11347, 0).at);             // (General Graardor: Trollheim)
		assertEquals("Lumbridge", Presence.area(12850, 0).name);
		assertArrayEquals(new int[]{3232, 3232}, Presence.area(12850, 0).at);             // (its one region's middle)
		// (an underground area as the place above it: Edgeville Dungeon's regions under Edgeville)
		int[] edge = Presence.area(12441, 0).at;
		assertTrue(edge[1] < 4224 && edge[0] >= 3008 && edge[0] < 3200);
		// (Nightmare Zone's region, on its ground floor, is the King Black Dragon's lair: no area, as Discord has it)
		assertNull(Presence.area(9033, 0));
		assertEquals("Nightmare Zone", Presence.area(9033, 1).name);
		assertNull(Presence.area(14000, 0));                                             // (not on Discord's list: "In Game")
	}

	@Test
	public void theSkillComesFromTheirOwnExperienceAndANewSceneClearsIt()
	{
		Presence p = new Presence();
		p.scene(6198, 0);
		p.experience("Woodcutting", 1000, 0);                 // (the first reading only remembered)
		assertTrue(p.now(0, false).get("skill").isJsonNull());
		p.experience("Woodcutting", 1050, 1000);
		JsonObject o = p.now(2000, false);
		assertEquals("Woodcutting", o.get("skill").getAsString());
		assertEquals("Woodcutting Guild", o.get("area").getAsString());
		p.experience("Hitpoints", 100, 0);
		p.experience("Hitpoints", 200, 3000);                // (never Hitpoints: Discord's never names it)
		assertEquals("Woodcutting", p.now(4000, false).get("skill").getAsString());
		p.experience("Slayer", 10, 0);
		p.experience("Slayer", 20, 1500);
		p.experience("Woodcutting", 1100, 5000);
		assertEquals("Slayer", p.now(6000, false).get("skill").getAsString());   // (Slayer and Fishing first)
		assertEquals("Woodcutting", p.now(1500 + Presence.SKILL_MS + 1, false).get("skill").getAsString());
		assertTrue(p.now(5000 + Presence.SKILL_MS + 1, false).get("skill").isJsonNull());   // (5 minutes with none)
		p.scene(12850, 0);                                    // (a new scene: the skill goes till the next drop)
		assertTrue(p.now(7000, false).get("skill").isJsonNull());
	}

	@Test
	public void somewhereDangerousSaysNothing()
	{
		Presence p = new Presence();
		p.scene(6198, 0);
		p.experience("Woodcutting", 1000, 0);
		p.experience("Woodcutting", 1050, 1000);
		JsonObject o = p.now(2000, true);
		assertTrue(o.get("skill").isJsonNull() && o.get("area").isJsonNull() && o.get("kind").isJsonNull());
		assertFalse(o.has("x"));
	}

	@Test
	public void itGoesAsItChangesOnceAMinuteAtMostAndClearsAtOnce()
	{
		Presence p = new Presence();
		p.scene(6198, 0);
		JsonObject first = p.due(0, false);
		assertEquals("Woodcutting Guild", first.get("area").getAsString());
		p.took(first, 0);
		assertNull(p.due(1000, false));                       // (nothing changed)
		p.scene(12850, 0);
		assertNull(p.due(30_000, false));                     // (changed, but within the minute)
		JsonObject later = p.due(60_000, false);              // (the latest, once it's up)
		assertEquals(p.now(60_000, false), later);
		p.took(later, 60_000);
		JsonObject clear = p.due(61_000, true);               // (somewhere dangerous: at once)
		assertTrue(clear.get("area").isJsonNull());
		p.took(clear, 61_000);
		assertNull(p.due(62_000, true));
		p.loggedOut();
		assertTrue(p.now(63_000, false).get("area").isJsonNull());
	}
}
