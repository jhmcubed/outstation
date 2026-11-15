package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Your year, kept by the plugin (11/15/2026, Jace: "start on the year recorded by the plugin"; the plugin first): the
 * record docs/record.md describes, written and sealed here exactly as the outstation app's client/src/record.rs does,
 * so the year page reads either the same way.
 *
 *   - The local record: one newline-JSON file a month (UTC) under RuneLite's folder, outside/record/<channel>/, plain
 *     (the player's own, on their own PC), with this PC's device id beside it.
 *   - The record key: 32 random bytes ("record1:" and base64url as text), kept with the character's account in
 *     RuneLite's settings; the seal key, the read and write tokens and each chunk's id are made from it by HMAC-SHA256.
 *   - A chunk: one day's lines (UTC), a header first, compressed with raw DEFLATE and sealed with AES-256-GCM (the
 *     chunk's id as additional data): [1][12-byte nonce][ciphertext and tag].
 *   - Sent: what's been uploaded, day by day (how many lines), so only a day that's grown goes again.
 *
 * Checked against tests/fixtures/record/vectors.json (a copy in the plugin's test resources). Kept apart from RuneLite.
 */
final class Record
{
	/** a chunk is at most this many lines of a day (record.md: only a long day with the exact path reaches it) */
	static final int PART_LINES = 4000;
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();

	private final Path dir;
	private final Gson gson;
	final String device;

	Record(Path dir, Gson gson) throws IOException
	{
		this.dir = dir;
		this.gson = gson;
		Files.createDirectories(dir);
		Path d = dir.resolve("device");
		String id = Files.exists(d) ? new String(Files.readAllBytes(d), StandardCharsets.UTF_8).trim() : "";
		if (!id.matches("[0-9a-f]{16}"))
		{
			byte[] b = new byte[8];
			RANDOM.nextBytes(b);
			StringBuilder s = new StringBuilder();
			for (byte x : b)
			{
				s.append(String.format("%02x", x));
			}
			id = s.toString();
			Files.write(d, id.getBytes(StandardCharsets.UTF_8));
		}
		device = id;
	}

	// ---- the local record

	/** A line ({"t", ...}) appended to its month's file. */
	synchronized void write(JsonObject line) throws IOException
	{
		long t = line.get("t").getAsLong();
		Path f = dir.resolve(month(t) + ".jsonl");
		Files.write(f, (gson.toJson(line) + "\n").getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** Every day's lines (UTC), in the order written, from every month's file. */
	synchronized Map<String, List<String>> days() throws IOException
	{
		Map<String, List<String>> out = new TreeMap<>();
		List<Path> files = new ArrayList<>();
		try (java.util.stream.Stream<Path> all = Files.list(dir))
		{
			all.filter(f -> f.getFileName().toString().matches("\\d{4}-\\d{2}\\.jsonl")).sorted().forEach(files::add);
		}
		for (Path f : files)
		{
			for (String l : Files.readAllLines(f, StandardCharsets.UTF_8))
			{
				if (l.isEmpty())
				{
					continue;
				}
				try
				{
					long t = gson.fromJson(l, JsonObject.class).get("t").getAsLong();
					out.computeIfAbsent(day(t), k -> new ArrayList<>()).add(l);
				}
				catch (RuntimeException ignored)
				{
					// (a line cut short, as by a crash: left out)
				}
			}
		}
		return out;
	}

	/** What's been uploaded: day -> how many lines, for the key and relay it was sent to (`to`). */
	synchronized Map<String, Integer> sent(String to) throws IOException
	{
		Map<String, Map<String, Integer>> all = sentAll();
		return all.getOrDefault(to, new LinkedHashMap<>());
	}

	synchronized void markSent(String to, String day, int lines) throws IOException
	{
		Map<String, Map<String, Integer>> all = sentAll();
		all.computeIfAbsent(to, k -> new LinkedHashMap<>()).put(day, lines);
		Files.write(dir.resolve("sent.json"), gson.toJson(all).getBytes(StandardCharsets.UTF_8));
	}

	private Map<String, Map<String, Integer>> sentAll() throws IOException
	{
		Path f = dir.resolve("sent.json");
		if (!Files.exists(f))
		{
			return new LinkedHashMap<>();
		}
		try
		{
			Map<String, Map<String, Integer>> m = gson.fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8),
				new TypeToken<Map<String, Map<String, Integer>>>()
				{
				}.getType());
			return m == null ? new LinkedHashMap<>() : m;
		}
		catch (RuntimeException e)
		{
			return new LinkedHashMap<>();
		}
	}

	static String day(long t)
	{
		return Instant.ofEpochMilli(t).atZone(ZoneOffset.UTC).toLocalDate().toString();
	}

	static String month(long t)
	{
		return day(t).substring(0, 7);
	}

	/** A chunk's plaintext: its header, then its lines, each ending in a newline. */
	static byte[] plaintext(String device, String day, int part, List<String> lines)
	{
		StringBuilder s = new StringBuilder("{\"record\": 1, \"device\": \"" + device + "\", \"day\": \"" + day + "\", \"part\": " + part + "}\n");
		for (String l : lines)
		{
			s.append(l).append('\n');
		}
		return s.toString().getBytes(StandardCharsets.UTF_8);
	}

	// ---- the record key and what's made from it

	/** A new record key. */
	static byte[] newKey()
	{
		byte[] k = new byte[32];
		RANDOM.nextBytes(k);
		return k;
	}

	static String keyText(byte[] k)
	{
		return "record1:" + B64U.encodeToString(k);
	}

	static String keyB64u(byte[] k)
	{
		return B64U.encodeToString(k);
	}

	/** The key from its text ("record1:" and 43 base64url characters), or null. */
	static byte[] parseKey(String text)
	{
		if (text == null || !text.trim().startsWith("record1:"))
		{
			return null;
		}
		try
		{
			byte[] k = Base64.getUrlDecoder().decode(text.trim().substring(8));
			return k.length == 32 ? k : null;
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	static byte[] hmac(byte[] k, String s)
	{
		try
		{
			Mac m = Mac.getInstance("HmacSHA256");
			m.init(new SecretKeySpec(k, "HmacSHA256"));
			return m.doFinal(s.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException(e);
		}
	}

	static byte[] sealKey(byte[] k)
	{
		return hmac(k, "outstation record seal v1");
	}

	static String readToken(byte[] k)
	{
		return B64U.encodeToString(hmac(k, "outstation record read v1"));
	}

	static String writeToken(byte[] k)
	{
		return B64U.encodeToString(hmac(k, "outstation record write v1"));
	}

	static String chunkId(byte[] k, String device, String day, int part)
	{
		return B64U.encodeToString(Arrays.copyOf(hmac(k, "outstation record chunk v1\n" + device + "\n" + day + "\n" + part), 16));
	}

	// ---- sealing

	/** A chunk sealed: raw DEFLATE, then AES-256-GCM with the seal key, the nonce and the id as additional data. */
	static byte[] seal(byte[] sealKey, String id, byte[] plaintext, byte[] nonce)
	{
		Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		d.setInput(plaintext);
		d.finish();
		ByteArrayOutputStream z = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		while (!d.finished())
		{
			z.write(buf, 0, d.deflate(buf));
		}
		d.end();
		try
		{
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sealKey, "AES"), new GCMParameterSpec(128, nonce));
			c.updateAAD(id.getBytes(StandardCharsets.UTF_8));
			byte[] ct = c.doFinal(z.toByteArray());
			byte[] out = new byte[1 + 12 + ct.length];
			out[0] = 1;
			System.arraycopy(nonce, 0, out, 1, 12);
			System.arraycopy(ct, 0, out, 13, ct.length);
			return out;
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException(e);
		}
	}

	static byte[] seal(byte[] sealKey, String id, byte[] plaintext)
	{
		byte[] nonce = new byte[12];
		RANDOM.nextBytes(nonce);
		return seal(sealKey, id, plaintext, nonce);
	}

	/** A sealed chunk opened (the page's way, for the tests): its plaintext. */
	static byte[] open(byte[] sealKey, String id, byte[] sealed) throws GeneralSecurityException
	{
		if (sealed.length < 30 || sealed[0] != 1)
		{
			throw new GeneralSecurityException("not a sealed chunk");
		}
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sealKey, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(sealed, 1, 13)));
		c.updateAAD(id.getBytes(StandardCharsets.UTF_8));
		byte[] z = c.doFinal(Arrays.copyOfRange(sealed, 13, sealed.length));
		Inflater in = new Inflater(true);
		in.setInput(z);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		try
		{
			while (!in.finished())
			{
				int n = in.inflate(buf);
				if (n == 0 && (in.needsInput() || in.needsDictionary()))
				{
					break;
				}
				out.write(buf, 0, n);
			}
		}
		catch (java.util.zip.DataFormatException e)
		{
			throw new GeneralSecurityException(e);
		}
		finally
		{
			in.end();
		}
		return out.toByteArray();
	}
}
