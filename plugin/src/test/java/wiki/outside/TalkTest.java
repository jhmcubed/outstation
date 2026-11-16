package wiki.outside;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The page's chat in the game (11/16/2026): what the desk hears, as the game's chat box gets it, and what it says. */
public class TalkTest
{
	private final Gson gson = new Gson();

	@Test
	public void visitorsLinesOnly()
	{
		Talk.Line l = Talk.visitor("{\"chat\": {\"id\": 4, \"name\": \"  moss  grove \", \"text\": \"gz on the\\npet\", \"host\": false, \"who\": 3, \"at\": 2000}}", 1000, gson);
		assertEquals("moss grove", l.name);
		assertEquals("gz on the pet", l.text);
		assertEquals("visitor", Talk.visitor("{\"chat\": {\"text\": \"hi\"}}", 0, gson).name);
		// (the host's own, a line with no words, anything else, and the chat so far as the desk opens)
		assertNull(Talk.visitor("{\"chat\": {\"name\": \"sburb\", \"text\": \"hi\", \"host\": true, \"at\": 9000}}", 1000, gson));
		assertNull(Talk.visitor("{\"chat\": {\"name\": \"a\", \"text\": \"   \"}}", 0, gson));
		assertNull(Talk.visitor("{\"relay\": {\"live\": false}}", 0, gson));
		assertNull(Talk.visitor("not json", 0, gson));
		assertNull(Talk.visitor("{\"chat\": {\"name\": \"a\", \"text\": \"old\", \"at\": 1000}}", 60000, gson));
	}

	@Test
	public void sayingAndTheSwitch()
	{
		assertEquals("{\"say\":\"thanks all\"}", Talk.say("  thanks \n all ", gson));
		assertNull(Talk.say("   ", gson));
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 400; i++)
		{
			b.append('x');
		}
		assertEquals(Talk.SAY_MAX + "{\"say\":\"\"}".length(), Talk.say(b.toString(), gson).length());
		assertEquals("{\"chat_on\":false}", Talk.chatOn(false, gson));
	}

	@Test
	public void theDeskSocket()
	{
		Attach a = Attach.parse("outstation1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
			"{\"relay\":\"https://relay.ghillie.workers.dev\",\"channel\":\"sburb\",\"key\":\"k-123456\"}".getBytes()), gson);
		assertEquals("wss://relay.ghillie.workers.dev/desk/sburb?key=k-123456&name=Sburb%20Two&talk=1", a.deskUrl("Sburb Two"));
	}
}
