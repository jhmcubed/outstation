package wiki.outside;

import java.util.Arrays;
import java.util.HashSet;
import net.runelite.api.ChatMessageType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The recorder's own rules, apart from the game (11/21/2026): what's a restart, which friends-chat lines are kept and
 *  with whose names, which chat ends a fight, the NPC ids listed. In the game: the local world's test fight
 *  (plugin/README.md, "Encounters"). */
public class EncountersTest
{
	@Test
	public void aRestartIsAFrameBackShortOfTheEnd()
	{
		// (the punch three ticks running: frame 5 of 9 back to 0)
		assertTrue(Encounters.isRestart(5, 0, 8));
		// (a loop wraps from its last frame: not a restart)
		assertFalse(Encounters.isRestart(8, 0, 8));
		// (going on, or standing still)
		assertFalse(Encounters.isRestart(3, 4, 8));
		assertFalse(Encounters.isRestart(0, 0, 8));
		// (a loop back to a frame past its start: not one)
		assertFalse(Encounters.isRestart(6, 3, 8));
	}

	@Test
	public void theRaidsCompletionLinesAreKeptAndNothingElseOfTheFriendsChat()
	{
		assertEquals(Encounters.ChatKind.COMPLETION, Encounters.chatKind(ChatMessageType.FRIENDSCHATNOTIFICATION));
		assertTrue(Encounters.completionLine("Congratulations - your raid is complete!"));
		assertTrue(Encounters.completionLine("Team size: 3 players Duration: 24:12 (new personal best)"));
		assertFalse(Encounters.completionLine("Bob Smith has joined the channel."));
		assertFalse(Encounters.completionLine("Special loot: Bob Smith - Twisted bow"));
		assertEquals(Encounters.ChatKind.NEVER, Encounters.chatKind(ChatMessageType.PRIVATECHAT));
		assertEquals(Encounters.ChatKind.NEVER, Encounters.chatKind(ChatMessageType.CLAN_CHAT));
		assertEquals(Encounters.ChatKind.GAME, Encounters.chatKind(ChatMessageType.GAMEMESSAGE));
		assertEquals("Congratulations, <player> and <player>!", Encounters.scrub("Congratulations, Bob Smith and bob_ross!",
			new HashSet<>(Arrays.asList("Bob Smith", "Bob Ross"))));
	}

	@Test
	public void theKillIsTheGamesCountLine()
	{
		assertTrue(Encounters.KILL_LINE.matcher("Your Vorkath kill count is: 418.").matches());
		assertTrue(Encounters.KILL_LINE.matcher("Your completed Chambers of Xeric count is: 12.").matches());
		assertTrue(Encounters.KILL_LINE.matcher("Your Testfight kill count is: 418.").matches());
		assertFalse(Encounters.KILL_LINE.matcher("You have completed 152 hard Treasure Trails.").matches());
	}

	@Test
	public void theListedNpcs()
	{
		assertEquals(new HashSet<>(Arrays.asList(3106, 12077)), Encounters.parseIds("3106, 12077, nope"));
		assertTrue(Encounters.parseIds("").isEmpty());
	}
}
