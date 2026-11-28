package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Trips (update 8, 11/23/2026; docs/hub-submission.md, "Trips"): a boss's instance's kills, each its own recording, the
 * next begun as the last ends, carrying the trip's id and number; the one it ends with says how. Here, what goes into the
 * files and up with them; the recorder's own timing is tried in the local world.
 */
public class TripsTest
{
	private static String file(String header, String end)
	{
		return "{\"t\":0,\"c\":0,\"e\":\"header\",\"format\":6,\"label\":\"Vorkath\",\"automatic\":true,\"startedMs\":1795400000000"
			+ header + "}\n"
			+ "{\"t\":3,\"c\":90,\"e\":\"tick\",\"abs\":3}\n"
			+ "{\"t\":5,\"c\":150,\"e\":\"end\",\"reason\":\"killed\",\"ticks\":5" + end + "}\n";
	}

	@Test
	public void aTripsKillCarriesItsIdAndNumber()
	{
		EncounterStrip.Summary s = EncounterStrip.summary(file(",\"trip\":\"abcDEF123_-x\",\"tripNo\":3", ""));
		assertEquals("abcDEF123_-x", s.trip);
		assertEquals(3, s.tripNo);
		assertNull(s.tripEnd);
		String meta = EncounterStrip.meta(s, null);
		assertTrue(meta, meta.contains("\"trip\":{\"id\":\"abcDEF123_-x\",\"no\":3}"));
	}

	@Test
	public void theOneATripEndsWithSaysHow()
	{
		EncounterStrip.Summary s = EncounterStrip.summary(file(",\"trip\":\"abcDEF123_-x\",\"tripNo\":4", ",\"tripEnd\":\"left\""));
		assertEquals("left", s.tripEnd);
		assertTrue(EncounterStrip.meta(s, true).contains("\"trip\":{\"id\":\"abcDEF123_-x\",\"no\":4,\"end\":\"left\"}"));
	}

	@Test
	public void aRecordingWithNoTripSaysNothingOfOne()
	{
		EncounterStrip.Summary s = EncounterStrip.summary(file("", ""));
		assertNull(s.trip);
		assertFalse(EncounterStrip.meta(s, null).contains("trip"));
	}

	@Test
	public void theWalkOutGoesUpOnlyAfterATripsKills()
	{
		// (the first of a trip left before any kill: as any recording that left, sent only if they'd fought)
		assertFalse(Encounters.tripTail("left", 1));
		assertFalse(Encounters.tripTail("left", 0));
		// (after one kill: stays on the computer, the kill shown alone)
		assertFalse(Encounters.tripTail("left", 2));
		// (after two kills or more: the trip's last part, the walk out, goes up so the trip plays whole)
		assertTrue(Encounters.tripTail("left", 3));
		// (a log out or the client closed isn't the trip's end on the page: it stays on the computer)
		assertFalse(Encounters.tripTail("logged out", 5));
	}

	@Test
	public void tripIdsAreRandomAndUrlSafe()
	{
		String a = Encounters.newTripId(), b = Encounters.newTripId();
		assertTrue(a, a.matches("[A-Za-z0-9_-]{12}"));
		assertNotEquals(a, b);
	}

	@Test
	public void aVisitsRunsFirstCarriesItsTripInItsEndLine()
	{
		// (11/28/2026, the Corporeal Beast's teleport in and out: the first visit's header was written before anyone knew)
		EncounterStrip.Summary s = EncounterStrip.summary(file("", ",\"trip\":\"visitAbC12_-xY\",\"tripNo\":1"));
		assertEquals("visitAbC12_-xY", s.trip);
		assertEquals(1, s.tripNo);
		assertTrue(EncounterStrip.meta(s, null).contains("\"trip\":{\"id\":\"visitAbC12_-xY\",\"no\":1}"));
		// (a header's trip wins over an end line's)
		assertEquals("abcDEF123_-x", EncounterStrip.summary(file(",\"trip\":\"abcDEF123_-x\",\"tripNo\":2", ",\"trip\":\"visitAbC12_-xY\",\"tripNo\":1")).trip);
	}

	@Test
	public void aVisitJoinsTheRunAtTheSameBossWithinTenMinutes()
	{
		Presence.Area corp = Presence.area(11842, 0), zul = Presence.area(9007, 0);
		long left = 1_795_673_522_363L;
		assertTrue(Encounters.visitJoins("visitAbC12_-xY", corp, left, corp, left + 60_000));
		assertTrue(Encounters.visitJoins("visitAbC12_-xY", corp, left, corp, left + Encounters.VISIT_GAP_MS));
		assertFalse(Encounters.visitJoins("visitAbC12_-xY", corp, left, corp, left + Encounters.VISIT_GAP_MS + 1));
		assertFalse(Encounters.visitJoins("visitAbC12_-xY", corp, left, zul, left + 60_000));
		assertFalse(Encounters.visitJoins(null, corp, left, corp, left + 60_000));
		assertFalse(Encounters.visitJoins("visitAbC12_-xY", corp, left, null, left + 60_000));
	}

	@Test
	public void aVisitsIdSaysVisitsAndTheRelayTakesIt()
	{
		String id = Encounters.newVisitId();
		assertTrue(id, id.matches("visit[A-Za-z0-9_-]{9}"));
		assertNotEquals(id, Encounters.newVisitId());
	}
}
