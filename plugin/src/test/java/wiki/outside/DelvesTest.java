package wiki.outside;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DelvesTest
{
	@Test
	public void aLevelDoneIsReadFromTheChat()
	{
		assertEquals(0, Delves.level("Delve level: 1 duration: 0:42.60"));
		assertEquals(7, Delves.level("Delve level: 8 duration: 2:31.20 (new personal best)"));
		assertEquals(8, Delves.level("Delve level: 8+ duration: 1:05.40"));
		assertEquals(-1, Delves.level("Delve level: 9 duration: 1:00.00"));
		assertEquals(-1, Delves.level("Your Vorkath kill count is: 418"));
		assertEquals(-1, Delves.level("Delve level: 3"));
		assertEquals(-1, Delves.level(null));
	}

	@Test
	public void aDeathLosesItsRunsLevelsAndNothingElse()
	{
		Delves d = new Delves();
		for (int l = 1; l <= 8; l++)
		{
			assertTrue(d.done("Delve level: " + l + " duration: 1:00.00"));
		}
		d.done("Delve level: 8+ duration: 1:00.00");
		d.done("Delve level: 8+ duration: 1:00.00");
		assertTrue(d.died());
		assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1, 1, 1, 2}, d.lost);
		assertFalse(d.running());
		// (a second death with nothing done since: nothing more lost)
		assertFalse(d.died());
		assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1, 1, 1, 2}, d.lost);
	}

	@Test
	public void aRunClaimedOrLeftLosesNothing()
	{
		Delves d = new Delves();
		d.done("Delve level: 1 duration: 1:00.00");
		d.done("Delve level: 2 duration: 1:00.00");
		d.left();
		assertFalse(d.died());
		assertArrayEquals(new int[9], d.lost);
	}

	@Test
	public void levelOneBeginsANewRun()
	{
		Delves d = new Delves();
		d.done("Delve level: 1 duration: 1:00.00");
		d.done("Delve level: 2 duration: 1:00.00");
		d.done("Delve level: 3 duration: 1:00.00");
		// (claimed at the burrow, then a new run: what came before isn't this run's)
		d.done("Delve level: 1 duration: 1:00.00");
		d.died();
		assertArrayEquals(new int[]{1, 0, 0, 0, 0, 0, 0, 0, 0}, d.lost);
	}

	@Test
	public void theLostAreKeptAndReadBack()
	{
		Delves d = new Delves();
		d.done("Delve level: 1 duration: 1:00.00");
		d.done("Delve level: 2 duration: 1:00.00");
		d.died();
		Delves e = new Delves();
		e.load(d.keep());
		assertArrayEquals(d.lost, e.lost);
		e.load("1,2,3");
		assertArrayEquals(new int[9], e.lost);
		e.load("1,2,x,4,5,6,7,8,9");
		assertArrayEquals(new int[9], e.lost);
		e.load(null);
		assertArrayEquals(new int[9], e.lost);
	}
}
