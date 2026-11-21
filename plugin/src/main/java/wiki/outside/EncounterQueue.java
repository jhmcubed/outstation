package wiki.outside;

import java.util.Map;

/**
 * Each encounter up by itself (11/21/2026; ghillie's claude/outstation.md, "Encounters, round two": Jace, "having it
 * automatically upload every encounter takes the tediousness out"): what the sender does with each recording that
 * finished while "Put each encounter on my page" was on, decided here without the network or the files, so the tests can
 * walk it. The state lives in the encounters' index.json beside each file (EncounterShelf), so a send that couldn't go
 * (the relay out of reach, the client closed, an hour's limit) goes the next time it can, and never twice.
 *
 * <ul>
 * <li><b>due</b>: to send, at or after {@code next}.</li>
 * <li><b>sending</b>: sent and no answer seen (the connection lost mid-way, the client closed). Whether it arrived isn't
 * known, so before it's sent again the page's own list is read and a fight with the same start and length taken as it
 * ({@link #sameFight}): never twice.</li>
 * <li><b>sent</b>: on the page (its id kept beside it).</li>
 * <li><b>refused</b>: the relay said no for good (the file isn't one, it's too big): never tried again by itself; Put on
 * my page still offers it.</li>
 * <li><b>held</b>: waiting on the player (the page's key not taken, another character's recording): tried again once
 * they've logged in on its character, or by hand.</li>
 * </ul>
 * A 429 waits exactly as long as its Retry-After says (the relay's 60 an hour); a relay out of reach or failing waits
 * longer each time (1, 2, 5, 10 and 30 minutes, then hourly); the relay's fights off waits six hours.
 */
final class EncounterQueue
{
	static final String DUE = "due", SENDING = "sending", SENT = "sent", REFUSED = "refused", HELD = "held";
	/** the waits after each failure in a row, in ms (then the last, hourly) */
	static final long[] BACKOFF = {60_000L, 120_000L, 300_000L, 600_000L, 1_800_000L, 3_600_000L};
	/** after "fights aren't on here": six hours */
	static final long FIGHTS_OFF_WAIT = 6 * 3_600_000L;

	private EncounterQueue()
	{
	}

	/** What a send's answer leaves it as: its state, when it's due again (ms), why (words for the side tab, or null). */
	static final class After
	{
		final String state;
		final long next;
		final String why;

		After(String state, long next, String why)
		{
			this.state = state;
			this.next = next;
			this.why = why;
		}
	}

	static long backoff(int tries)
	{
		return BACKOFF[Math.max(0, Math.min(BACKOFF.length - 1, tries - 1))];
	}

	/**
	 * The relay's answer to a send (its status; Retry-After's seconds as it came, or null; its error's words, or null),
	 * {@code tries} counting this one.
	 */
	static After answered(int code, String retryAfter, String error, long now, int tries)
	{
		if (code == 201)
		{
			return new After(SENT, 0, null);
		}
		if (code == 429)
		{
			long s = 60;
			try
			{
				s = Math.max(1, Math.min(6 * 3600, Long.parseLong(retryAfter == null ? "" : retryAfter.trim())));
			}
			catch (NumberFormatException e)
			{
				// (none, or one that isn't seconds: a minute)
			}
			return new After(DUE, now + s * 1000, "the hour's limit: going up in " + inWords(s));
		}
		if (code == 404 && error != null && error.contains("fights aren't on"))
		{
			return new After(DUE, now + FIGHTS_OFF_WAIT, "Outside.wiki isn't taking encounters yet");
		}
		if (code == 403)
		{
			return new After(HELD, 0, "the relay didn't take your page's key");
		}
		if (code == 400 || code == 413 || code == 415)
		{
			return new After(REFUSED, 0, error != null ? error : "the relay said " + code);
		}
		return new After(DUE, now + backoff(tries), "the relay said " + code + ": trying again in a bit");
	}

	/** The relay out of reach, or the connection lost before its answer: whether it arrived is checked before next time. */
	static After lost(long now, int tries)
	{
		return new After(SENDING, now + backoff(tries), "the relay's out of reach: trying again in a bit");
	}

	/** Whether a fight on the page (its entry in the relay's list) is this recording: the same start (ms) and length. */
	static boolean sameFight(Map<String, Object> fight, double at, double ticks)
	{
		return fight != null && at > 0 && EncounterMoment.num(fight.get("at")) == at && EncounterMoment.num(fight.get("ticks")) == ticks;
	}

	/** Seconds as the side tab says them. */
	static String inWords(long s)
	{
		return s < 90 ? s + " s" : (s + 30) / 60 + " min";
	}
}
