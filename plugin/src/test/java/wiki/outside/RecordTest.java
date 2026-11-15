package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/** Your year kept by the plugin (11/15/2026): docs/record.md's format, against its test vectors (made by WebCrypto). */
public class RecordTest
{
	private static final Gson GSON = new Gson();

	private static JsonObject vectors()
	{
		return GSON.fromJson(new InputStreamReader(RecordTest.class.getResourceAsStream("/record-vectors.json"), StandardCharsets.UTF_8), JsonObject.class);
	}

	private static byte[] hex(String s)
	{
		byte[] b = new byte[s.length() / 2];
		for (int i = 0; i < b.length; i++)
		{
			b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
		}
		return b;
	}

	@Test
	public void theKeysTokensAndIdAreTheVectors()
	{
		JsonObject v = vectors();
		byte[] k = hex(v.get("key_hex").getAsString());
		assertEquals(v.get("key_text").getAsString(), Record.keyText(k));
		assertArrayEquals(k, Record.parseKey(v.get("key_text").getAsString()));
		assertArrayEquals(hex(v.get("seal_key_hex").getAsString()), Record.sealKey(k));
		assertEquals(v.get("read_token").getAsString(), Record.readToken(k));
		assertEquals(v.get("write_token").getAsString(), Record.writeToken(k));
		assertEquals(v.get("chunk_id").getAsString(), Record.chunkId(k, v.get("device").getAsString(), v.get("day").getAsString(), v.get("part").getAsInt()));
		assertNull(Record.parseKey("record1:short"));
		assertNull(Record.parseKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"));
	}

	@Test
	public void theVectorsSealedChunkOpensAndOursOpenTheSameWay() throws Exception
	{
		JsonObject v = vectors();
		byte[] k = hex(v.get("key_hex").getAsString()), seal = Record.sealKey(k);
		String id = v.get("chunk_id").getAsString(), plain = v.get("plaintext").getAsString();
		// (WebCrypto's sealed chunk, opened here as the page opens it)
		assertEquals(plain, new String(Record.open(seal, id, hex(v.get("sealed_hex").getAsString())), StandardCharsets.UTF_8));
		// (ours, with the same nonce: its layout, and it opens to the same plaintext)
		byte[] ours = Record.seal(seal, id, plain.getBytes(StandardCharsets.UTF_8), hex(v.get("nonce_hex").getAsString()));
		assertEquals(1, ours[0]);
		assertArrayEquals(hex(v.get("nonce_hex").getAsString()), Arrays.copyOfRange(ours, 1, 13));
		assertEquals(plain, new String(Record.open(seal, id, ours), StandardCharsets.UTF_8));
		// (under another id it won't open: the id is the additional data)
		try
		{
			Record.open(seal, "AAAAAAAAAAAAAAAAAAAAAA", ours);
			fail("opened under another id");
		}
		catch (java.security.GeneralSecurityException expected)
		{
		}
	}

	@Test
	public void theHeaderIsTheVectorsAndTheLocalRecordKeepsItsDays() throws Exception
	{
		JsonObject v = vectors();
		String plain = v.get("plaintext").getAsString();
		List<String> lines = Arrays.asList(plain.split("\n")).subList(1, plain.split("\n").length);
		assertEquals(plain, new String(Record.plaintext(v.get("device").getAsString(), v.get("day").getAsString(), 0, lines), StandardCharsets.UTF_8));
		java.nio.file.Path dir = Files.createTempDirectory("record");
		Record r = new Record(dir, GSON);
		assertEquals(16, r.device.length());
		assertEquals(r.device, new Record(dir, GSON).device);                 // (made once)
		JsonObject a = new JsonObject();
		a.addProperty("t", 1794268812345L);
		a.add("where", GSON.fromJson("{\"x\": 3264, \"y\": 3264}", JsonObject.class));
		JsonObject b = new JsonObject();
		b.addProperty("t", 1794268812345L + 86_400_000L);
		b.add("where", null);
		r.write(a);
		r.write(b);
		Map<String, List<String>> days = r.days();
		assertEquals(2, days.size());
		assertEquals(1, days.get(Record.day(1794268812345L)).size());
		r.markSent("relay|key", Record.day(1794268812345L), 1);
		assertEquals(Integer.valueOf(1), r.sent("relay|key").get(Record.day(1794268812345L)));
		assertEquals(0, r.sent("another").size());
	}
}
