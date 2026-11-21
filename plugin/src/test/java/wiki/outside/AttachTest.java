package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The relay's addresses made from a station key (11/15/2026: your year's routes and the plugin's own reports). */
public class AttachTest
{
	@Test
	public void theRecordsAndTheReportsRoutes()
	{
		Attach a = new Attach("wss://relay.ghillie.workers.dev", "jhmcubed", "k3y-k3y-k3y");
		// hub:cut year
		assertEquals("https://relay.ghillie.workers.dev/record/jhmcubed/token?key=k3y-k3y-k3y", a.recordUrl("token"));
		assertEquals("https://relay.ghillie.workers.dev/record/jhmcubed/Gzkegxw9dQilhK1vBkcdUg?key=k3y-k3y-k3y", a.recordUrl("Gzkegxw9dQilhK1vBkcdUg"));
		// hub:end year
		// hub:cut reports
		assertEquals("https://relay.ghillie.workers.dev/dink/jhmcubed?key=k3y-k3y-k3y&from=plugin", a.dinkUrl());
		// hub:end reports
		// (the page's address for the Dink plugin's webhooks, 11/16/2026: copied for the player, never fetched)
		assertEquals("https://relay.ghillie.workers.dev/dink/jhmcubed?key=k3y-k3y-k3y", a.dinkAddress());
		assertEquals("https://relay.ghillie.workers.dev/game/jhmcubed?key=k3y-k3y-k3y&v=0.3.0&proof=1", a.postUrl("0.3.0"));
		assertEquals("https://relay.ghillie.workers.dev/proof/jhmcubed", a.proofUrl());
	}

	@Test
	public void friendsOnlyAndTakingThePageDown()
	{
		Attach a = new Attach("wss://relay.ghillie.workers.dev", "jhmcubed", "k3y-k3y-k3y");
		assertEquals("https://relay.ghillie.workers.dev/code/jhmcubed?key=k3y-k3y-k3y", a.codeUrl());
		assertEquals("https://relay.ghillie.workers.dev/channels/jhmcubed?key=k3y-k3y-k3y", a.takeDownUrl());
		assertEquals("https://relay.ghillie.workers.dev/game/jhmcubed", a.gameReadUrl());
		assertEquals("https://outside.wiki/jhmcubed?code=a-b_c", a.page("a-b_c"));
		assertEquals("https://outside.wiki/jhmcubed", a.page(""));
	}

	// hub:cut encounters
	@Test
	public void theEncountersRoutes()
	{
		Attach a = new Attach("relay.ghillie.workers.dev", "jhmcubed", "k3y-k3y-k3y");
		assertEquals("https://relay.ghillie.workers.dev/fights/jhmcubed?key=k3y-k3y-k3y", a.fightsUrl());
		assertEquals("https://relay.ghillie.workers.dev/fights/jhmcubed/upl1MVobEcCgDxIc-Z-MgQ?key=k3y-k3y-k3y&public=1",
			a.fightUrl("upl1MVobEcCgDxIc-Z-MgQ", true));
		assertEquals("https://outside.wiki/jhmcubed/fight/upl1MVobEcCgDxIc-Z-MgQ", a.fightPage("upl1MVobEcCgDxIc-Z-MgQ", null));
		assertEquals("https://outside.wiki/jhmcubed/fight/upl1MVobEcCgDxIc-Z-MgQ?code=a-b_c", a.fightPage("upl1MVobEcCgDxIc-Z-MgQ", "a-b_c"));
	}
	// hub:end encounters

	@Test
	public void tendingThePagesChat()
	{
		// (11/16/2026: the token handed in by the page's key, and the page opened with it in the fragment)
		Attach a = new Attach("wss://relay.ghillie.workers.dev", "jhmcubed", "k3y-k3y-k3y");
		assertEquals("https://relay.ghillie.workers.dev/tend/jhmcubed?key=k3y-k3y-k3y", a.tendUrl());
		assertEquals("https://outside.wiki/jhmcubed#tend=abc", a.tendPage("abc"));
	}

	@Test
	public void onlyTheOutsideRelay()
	{
		assertTrue(Attach.isHome("https://relay.ghillie.workers.dev"));
		assertTrue(Attach.isHome("wss://relay.ghillie.workers.dev/"));
		assertTrue(Attach.isHome("relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("https://relay.ghillie.workers.dev.evil.example"));
		assertFalse(Attach.isHome("https://evil.example/relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("https://relay.ghillie.workers.dev:8443"));
		assertFalse(Attach.isHome("https://user@relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("http://relay.ghillie.workers.dev"));
		assertFalse(Attach.isHome("http://127.0.0.1:8787"));
		assertFalse(Attach.isHome(null));
	}

	// hub:only @Test
	// hub:only public void theHubsVersionTakesNoOtherRelay()
	// hub:only {
	// hub:only 	com.google.gson.Gson gson = new com.google.gson.Gson();
	// hub:only 	assertNull(Attach.parse(key("https://evil.example"), gson));
	// hub:only 	Attach a = Attach.parse(key("wss://relay.ghillie.workers.dev"), gson);
	// hub:only 	assertEquals(Attach.HOME, a.getRelay());
	// hub:only 	assertEquals("https://relay.ghillie.workers.dev/proof/jhmcubed", a.proofUrl());
	// hub:only }
	// hub:only
	// hub:only private static String key(String relay)
	// hub:only {
	// hub:only 	String json = "{\"relay\":\"" + relay + "\",\"channel\":\"jhmcubed\",\"key\":\"k3y-k3y-k3y\"}";
	// hub:only 	return "outstation1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	// hub:only }
}
