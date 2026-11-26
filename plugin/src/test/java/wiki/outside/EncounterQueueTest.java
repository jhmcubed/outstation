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

	/** (11/21/2026, round three) a crash's leftover packed at the next start goes up by itself (only with the switch on, and
	 *  only for the character it was recorded on); one that names no player never goes by itself */
	@Test
	public void aCrashsLeftoverIsQueuedAtTheStart() throws Exception
	{
		Path root = Files.createTempDirectory("outside");
		Path enc = Files.createDirectories(root.resolve("encounters"));
		Path plain = enc.resolve("20261121-110000-Vorkath.jsonl");
		Files.write(plain, ("{\"t\":0,\"c\":1,\"e\":\"header\",\"label\":\"Vorkath\",\"startedMs\":1763722800000,\"player\":\"camo field\"}\n"
			+ "{\"t\":0,\"c\":2,\"e\":\"tick\"}\n{\"t\":1,\"c\":32,\"e\":\"tick\"}\n").getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(plain, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - EncounterFiles.IDLE_MS - 1000));
		recording(enc, "20261121-100000-Giant_Mole.jsonl.gz", null, 1763719200000L);
		EncounterShelf shelf = new EncounterShelf(new Gson(), () -> Filepath.Unchecked.getRooted(root));
		await(shelf, done -> shelf.tidy(200L << 20, Collections.emptySet(), true, done));
		await(shelf, done -> shelf.queue("20261121-100000-Giant_Mole.jsonl.gz", done));
		long now = System.currentTimeMillis();
		assertNull(EncounterQueueTest.<EncounterShelf.Due>await(c -> shelf.nextDue(now, "Someone Else", Collections.emptySet(), c)));
		EncounterShelf.Due d = await(c -> shelf.nextDue(now, "camo field", Collections.emptySet(), c));
		assertEquals("20261121-110000-Vorkath.jsonl.gz", d.file);
		assertEquals(1763722800000.0, d.at, 0);
		assertEquals(2.0, d.ticks, 0);
		await(shelf, done -> shelf.sent(d.file, EncounterQueue.answered(201, null, null, now, 1), "abc", "camo_field", true, done));
		// (the one naming no player: never by itself)
		assertNull(EncounterQueueTest.<EncounterShelf.Due>await(c -> shelf.nextDue(now, "camo field", Collections.emptySet(), c)));
		shelf.shutDown();
	}

	/** a recording in the folder as the recorder leaves one: packed (as a crash's leftover is, at the start), its name */
	private static String packedRecording(Path root, Path enc, String name) throws Exception
	{
		Path plain = enc.resolve(name);
		Files.write(plain, ("{\"t\":0,\"c\":1,\"e\":\"header\",\"format\":6,\"label\":\"Phosani's Nightmare\",\"startedMs\":1795646372019,\"player\":\"camo field\"}\n"
			+ "{\"t\":0,\"c\":2,\"e\":\"tick\"}\n{\"t\":1,\"c\":32,\"e\":\"tick\"}\n").getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(plain, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - EncounterFiles.IDLE_MS - 1000));
		EncounterShelf shelf = new EncounterShelf(new Gson(), () -> Filepath.Unchecked.getRooted(root));
		await(shelf, done -> shelf.tidy(200L << 20, Collections.emptySet(), false, done));
		shelf.shutDown();
		assertTrue(Files.exists(enc.resolve(name + EncounterFiles.EXT)));
		return name + EncounterFiles.EXT;
	}

	/**
	 * (11/26/2026, Don Danz's Phosani's Nightmare went up eleven hours after it ended saying only "no moment was made":
	 * its moment's making never finished, and nothing said why) A recording whose moment was coming as RuneLite closed goes
	 * up next time saying so; one whose moment couldn't be made says why; one made says nothing.
	 */
	@Test
	public void aMomentNeverFinishedSaysWhyNextTime() throws Exception
	{
		Path root = Files.createTempDirectory("outside");
		Path enc = Files.createDirectories(root.resolve("encounters"));
		String file = packedRecording(root, enc, "20261125-223932-Sisterhood-Sanctuary.jsonl");
		// (it ends, its moment's coming; the client closes before it's made)
		EncounterShelf shelf = new EncounterShelf(new Gson(), () -> Filepath.Unchecked.getRooted(root));
		shelf.expect(file);
		EncounterQueueTest.<JsonObject>await(c -> shelf.entryOf(file, c));
		shelf.shutDown();
		// (the next start: it goes up saying so, not "no moment was made")
		EncounterShelf next = new EncounterShelf(new Gson(), () -> Filepath.Unchecked.getRooted(root));
		EncounterShelf.Prepared p = await(c -> next.prepare(file, c, null));
		assertNull(p.moment);
		assertEquals("RuneLite closed or the plugin stopped before its moment was made", p.momentWhy);
		// (its making ended with a reason: that reason, and it's no longer due)
		await(next, done -> next.moment(file, EncounterScene.none("the scene's buffer was full"), done));
		p = await(c -> next.prepare(file, c, null));
		assertEquals("the scene's buffer was full", p.momentWhy);
		assertFalse(EncounterQueueTest.<JsonObject>await(c -> next.entryOf(file, c)).has("momentDue"));
		next.shutDown();
		// (nothing expected, nothing known: as before)
		assertEquals("no moment was made", EncounterShelf.noMomentWhy(null, false, false));
		assertEquals("its moment was too big or couldn't be read", EncounterShelf.noMomentWhy(new JsonObject(), true, false));
		JsonObject due = new JsonObject();
		due.addProperty("momentDue", true);
		assertEquals("its moment was still being made", EncounterShelf.noMomentWhy(due, false, true));
	}

	/** (11/26/2026) A moment whose making fails anywhere still lets the recording's send go, saying why: a summary that
	 *  can't be read once it's packed (it used to leave the recording marked as building till the client closed, and its
	 *  moment never made), and a moment with nothing in it to build. */
	@Test
	public void aMomentsFailureNeverHoldsTheSend() throws Exception
	{
		Path root = Files.createTempDirectory("outside");
		Path enc = Files.createDirectories(root.resolve("encounters"));
		// (packed, but not a recording a summary can be read from)
		Files.write(enc.resolve("20261126-041049-Nex.jsonl.gz"), new byte[]{1, 2, 3, 4});
		EncounterShelf shelf = new EncounterShelf(new Gson(), () -> Filepath.Unchecked.getRooted(root));
		shelf.expect("20261126-041049-Nex.jsonl.gz");
		await(shelf, done -> shelf.packed("20261126-041049-Nex.jsonl.gz", 200L << 20, Collections.emptySet(), done));
		// (a Taken with nothing in it: its build fails on the moments thread, and says so)
		EncounterScene.Taken empty = new EncounterScene.Taken();
		empty.frames = new ArrayList<>();
		await(shelf, done -> shelf.moment("20261126-041049-Nex.jsonl.gz", empty, done));
		JsonObject e = await(c -> shelf.entryOf("20261126-041049-Nex.jsonl.gz", c));
		assertTrue(e.toString(), e.get("momentWhy").getAsString().startsWith("it couldn't be made"));
		assertFalse(e.has("momentDue"));
		shelf.shutDown();
	}
}
