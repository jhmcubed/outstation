package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

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
		assertEquals("https://relay.ghillie.workers.dev/dink/jhmcubed?key=k3y-k3y-k3y&from=plugin", a.dinkUrl());
		assertEquals("https://relay.ghillie.workers.dev/proof/jhmcubed", a.proofUrl());
	}
}
