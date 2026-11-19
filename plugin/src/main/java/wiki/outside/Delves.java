package wiki.outside;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Doom of Mokhaiotl's delve levels lost to deaths (11/19/2026; SaluteCFlagg, through Jace: the plugin should "count
 * the levels each death throws away"). A death in a delve loses every drop of its run (the wiki: "The delve level is
 * reset to 1, and all drops from that run are lost"), but the board beside the boss counts the run's levels all the
 * same, so a page's odds from the board alone run high for someone who dies mid-run.
 *
 * Counted from the game's chat as the Delve Calculator plugin counts a level done ("Delve level: 3 duration: ...", and
 * "Delve level: 8+ ..." for each wave past 8): the levels done this run, a run begun again at level 1, and over once
 * they're away from the boss (claimed and gone, or left in the chest by the scoreboard, which keeps what wasn't
 * claimed). A death there adds the run's levels to those lost. Lost from when this was first counted, not before: kept
 * on this RuneLite's profile for the account (OutsidePlugin's "delveLost"), and sent beside the board as they stood
 * when the board was last read, so the page takes them from the board they were counted against.
 */
final class Delves
{
	/** "Delve level: 3 duration: 1:23.40", "Delve level: 8+ duration: ..." */
	private static final Pattern DONE = Pattern.compile("Delve level: (8\\+|[1-8])(?![0-9+]).*duration:");

	/** the levels done this run (1 to 8, then the waves past 8) */
	final int[] run = new int[9];
	/** the levels lost to deaths, since this was first counted */
	final int[] lost = new int[9];

	/** The level a chat line says was done: 0 to 7 for levels 1 to 8, 8 for a wave past 8; -1 for any other line. */
	static int level(String line)
	{
		Matcher m = line == null ? null : DONE.matcher(line);
		if (m == null || !m.find())
		{
			return -1;
		}
		return "8+".equals(m.group(1)) ? 8 : Integer.parseInt(m.group(1)) - 1;
	}

	/** A chat line: a level done counted in this run (level 1 begins a new one). True when it was one. */
	boolean done(String line)
	{
		int l = level(line);
		if (l < 0)
		{
			return false;
		}
		if (l == 0)
		{
			java.util.Arrays.fill(run, 0);
		}
		run[l]++;
		return true;
	}

	/** Their death at the boss: this run's levels lost with its drops. True when it had any. */
	boolean died()
	{
		boolean any = running();
		for (int i = 0; i < 9; i++)
		{
			lost[i] += run[i];
		}
		java.util.Arrays.fill(run, 0);
		return any;
	}

	/** Away from the boss: the run's over and nothing in it lost. */
	void left()
	{
		java.util.Arrays.fill(run, 0);
	}

	/** Whether this run has a level done. */
	boolean running()
	{
		for (int n : run)
		{
			if (n > 0)
			{
				return true;
			}
		}
		return false;
	}

	/** The levels lost, as kept: nine whole numbers, commas between. */
	String keep()
	{
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 9; i++)
		{
			s.append(i > 0 ? "," : "").append(lost[i]);
		}
		return s.toString();
	}

	/** The levels lost, as keep() wrote them; anything else leaves them none. */
	void load(String kept)
	{
		java.util.Arrays.fill(lost, 0);
		String[] parts = kept == null ? new String[0] : kept.split(",");
		if (parts.length != 9)
		{
			return;
		}
		int[] got = new int[9];
		for (int i = 0; i < 9; i++)
		{
			try
			{
				got[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
			}
			catch (NumberFormatException e)
			{
				return;
			}
		}
		System.arraycopy(got, 0, lost, 0, 9);
	}
}
