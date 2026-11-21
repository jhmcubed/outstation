package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.client.util.Filepath;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Each encounter up by itself (11/21/2026): the sender's answers (EncounterQueue) and its queue in the index
 * (EncounterShelf): a 429 waits its Retry-After, the relay out of reach waits longer each time, a send with no answer is
 * looked for before it's sent again, one on the page is never sent again, one recorded on another character waits.
 */
public class EncounterQueueTest
{
	@Test
	public void answers()
	{
		long now = 1_000_000L;
		EncounterQueue.After a = EncounterQueue.answered(201, null, null, now, 1);
		assertEquals(EncounterQueue.SENT, a.state);
		a = EncounterQueue.answered(429, "120", "60 fights an hour at most: try again later", now, 1);
		assertEquals(EncounterQueue.DUE, a.state);
		assertEquals(now + 120_000L, a.next);
		assertTrue(a.why, a.why.contains("2 min"));
		// (no Retry-After, or nonsense: a minute)
		assertEquals(now + 60_000L, EncounterQueue.answered(429, null, null, now, 1).next);
		assertEquals(now + 60_000L, EncounterQueue.answered(429, "soon", null, now, 1).next);
		a = EncounterQueue.answered(404, null, "fights aren't on here", now, 1);
		assertEquals(EncounterQueue.DUE, a.state);
		assertEquals(now + EncounterQueue.FIGHTS_OFF_WAIT, a.next);
		assertEquals(EncounterQueue.HELD, EncounterQueue.answered(403, null, "wrong key", now, 1).state);
		for (int code : new int[]{400, 413, 415})
		{
			assertEquals(EncounterQueue.REFUSED, EncounterQueue.answered(code, null, "no", now, 1).state);
		}
		// (a failing relay, and one out of reach, wait longer each time, hourly at most)
		assertEquals(now + 60_000L, EncounterQueue.answered(502, null, null, now, 1).next);
		assertEquals(now + 300_000L, EncounterQueue.answered(502, null, null, now, 3).next);
		assertEquals(now + 3_600_000L, EncounterQueue.answered(502, null, null, now, 40).next);
		a = EncounterQueue.lost(now, 2);
		assertEquals(EncounterQueue.SENDING, a.state);
		assertEquals(now + 120_000L, a.next);
	}

	@Test
	public void sameFight()
	{
		Map<String, Object> f = new HashMap<>();
		f.put("at", 1763700000000.0);
		f.put("ticks", 412.0);
		assertTrue(EncounterQueue.sameFight(f, 1763700000000.0, 412));
		assertFalse(EncounterQueue.sameFight(f, 1763700000000.0, 413));
		assertFalse(EncounterQueue.sameFight(f, 0, 412));
		assertFalse(EncounterQueue.sameFight(null, 1763700000000.0, 412));
	}

	private static <T> T await(java.util.function.Consumer<java.util.function.Consumer<T>> job) throws Exception
	{
		CompletableFuture<T> f = new CompletableFuture<>();
		job.accept(f::complete);
		return f.get(10, TimeUnit.SECONDS);
	}

	private static void await(EncounterShelf shelf, java.util.function.Consumer<Runnable> job) throws Exception
	{
		CompletableFuture<Void> f = new CompletableFuture<>();
		job.accept(() -> f.complete(null));
		f.get(10, TimeUnit.SECONDS);
	}

	/** a packed recording in the folder, from the samples EncounterFilesTest writes, with its index entry */
	private static void recording(Path enc, String name, String player, long at) throws IOException
	{
		Files.write(enc.resolve(name), new byte[]{0x1f, (byte) 0x8b});
		JsonObject e = new JsonObject();
		e.addProperty("size", 2);
		e.addProperty("who", "Giant Mole");
		e.addProperty("ticks", 40);
		e.addProperty("at", (double) at);
		if (player != null)
		{
			e.addProperty("player", player);
		}
		Path idx = enc.resolve(EncounterFiles.INDEX);
		JsonObject all = Files.exists(idx) ? new Gson().fromJson(new String(Files.readAllBytes(idx), StandardCharsets.UTF_8), JsonObject.class) : new JsonObject();
		all.add(name, e);
		Files.write(idx, new Gson().toJson(all).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void theQueue() throws Exception
	{
		Path root = Files.createTempDirectory("outside");
		Path enc = Files.createDirectories(root.resolve("encounters"));
		recording(enc, "20261121-101500-Giant_Mole.jsonl.gz", "camo field", 1763720100000L);
		recording(enc, "20261121-100000-Giant_Mole.jsonl.gz", "camo field", 1763719200000L);
		recording(enc, "20261121-102000-Vorkath.jsonl.gz", "someone else", 1763720400000L);
		EncounterShelf shelf = new EncounterShelf(new Gson(), () -> Filepath.Unchecked.getRooted(root));
		List<String> files = new ArrayList<>();
		java.io.File[] there = enc.toFile().listFiles();
		for (java.io.File f : there)
		{
			if (f.getName().endsWith(".gz"))
			{
				files.add(f.getName());
			}
		}
		Collections.sort(files);
		for (String f : files)
		{
			await(shelf, done -> shelf.queue(f, done));
		}
		long now = System.currentTimeMillis();
		// (the oldest first, for the character logged in; the other character's waits)
		EncounterShelf.Due d = await(c -> shelf.nextDue(now, "camo field", Collections.emptySet(), c));
		assertEquals("20261121-100000-Giant_Mole.jsonl.gz", d.file);
		assertFalse(d.unsure);
		assertEquals(1763719200000.0, d.at, 0);
		// (sent, no answer: unsure, and not due again till its wait's over)
		await(shelf, done -> shelf.sent(d.file, EncounterQueue.lost(now, 1), null, null, null, done));
		EncounterShelf.Due d2 = await(c -> shelf.nextDue(now, "camo field", Collections.emptySet(), c));
		assertEquals("20261121-101500-Giant_Mole.jsonl.gz", d2.file);
		// (a 429 for it: due again only after its Retry-After)
		await(shelf, done -> shelf.sent(d2.file, EncounterQueue.answered(429, "300", null, now, 1), null, null, null, done));
		assertNull(EncounterQueueTest.<EncounterShelf.Due>await(c -> shelf.nextDue(now, "camo field", Collections.emptySet(), c)));
		// (later: the unsure one comes back marked unsure, so it's looked for on the page before it goes again)
		EncounterShelf.Due d3 = await(c -> shelf.nextDue(now + 61_000L, "camo field", Collections.emptySet(), c));
		assertEquals(d.file, d3.file);
		assertTrue(d3.unsure);
		// (on the page: never sent again, nor queued again)
		await(shelf, done -> shelf.sent(d3.file, EncounterQueue.answered(201, null, null, now, 2), "abc123", "camo_field", true, done));
		await(shelf, done -> shelf.queue(d3.file, done));
		EncounterShelf.Due d4 = await(c -> shelf.nextDue(now + 3_600_000L, "camo field", Collections.emptySet(), c));
		assertEquals(d2.file, d4.file);
		await(shelf, done -> shelf.sent(d4.file, EncounterQueue.answered(201, null, null, now, 1), "def456", "camo_field", null, done));
		assertNull(EncounterQueueTest.<EncounterShelf.Due>await(c -> shelf.nextDue(now + 7_200_000L, "camo field", Collections.emptySet(), c)));
		// (the other character's, once they've logged in)
		EncounterShelf.Due d5 = await(c -> shelf.nextDue(now, "Someone Else", Collections.emptySet(), c));
		assertEquals("20261121-102000-Vorkath.jsonl.gz", d5.file);
		// (the rows say so: on the page with its id; the sender's state)
		List<EncounterShelf.Row> rows = await(c -> shelf.list(Collections.emptySet(), (r, more) -> c.accept(r), null));
		int onPage = 0;
		for (EncounterShelf.Row r : rows)
		{
			onPage += r.fight != null ? 1 : 0;
		}
		assertEquals(2, onPage);
		shelf.shutDown();
	}
}
