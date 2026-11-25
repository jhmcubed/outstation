package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * The update a minute after logging in (11/25/2026): the hourly sync's count, as a login leaves it (OutsidePlugin's
 * loginTicks; the count goes up a tick at a time while logged in and the update goes at SYNC_TICKS).
 */
public class LoginSyncTest
{
	@Test
	public void aLoginFromTheLoginScreenUpdatesAMinuteIn()
	{
		int t = OutsidePlugin.loginTicks(0, true);
		assertEquals(OutsidePlugin.SYNC_TICKS - 100, t);
		assertEquals(100, OutsidePlugin.SYNC_TICKS - t);   // (100 ticks: a minute)
	}

	@Test
	public void aCountAlreadyCloserStaysAsItIs()
	{
		// (an hour nearly up when they logged out: its update comes as soon as it would have)
		int nearly = OutsidePlugin.SYNC_TICKS - 20;
		assertEquals(nearly, OutsidePlugin.loginTicks(nearly, true));
	}

	@Test
	public void aWorldHopLeavesTheCountAlone()
	{
		assertEquals(1234, OutsidePlugin.loginTicks(1234, false));
	}
}
