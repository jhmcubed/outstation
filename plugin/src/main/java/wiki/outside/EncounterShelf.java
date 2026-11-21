package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The encounters on this computer as the side tab's Encounters tab lists them (11/21/2026; ghillie's
 * claude/outstation.md, "Encounters on Outside: the night's design"): the recordings in .runelite/plugin-data/outside/
 * encounters, newest first, each with who it was against, when, how long, and whether it was a kill or a death (the
 * summary EncounterStrip makes of it, the same strip.js's summary() makes), and whether it's on the page.
 *
 * Every file job runs here, one at a time, on a thread of its own (never the client's or Swing's): listing, reading a
 * recording whole for its summary or to strip it, deleting one, the size cap, and the index (index.json beside them:
 * each file's summary, so a list needn't unpack each again, and what's on the page from it: its fight's id there, the
 * page's channel, and shown or kept). An index lost or broken is only work again: the summaries are made anew, and what
 * was on the page is read back from the relay's list (reconcile) the next time the tab's opened.
 */
final class EncounterShelf
{
	private static final Logger log = LoggerFactory.getLogger(EncounterShelf.class);
	/** at most this many listed in the side tab (the folder keeps more, within its size cap) */
	static final int LISTED = 30;

	/** A recording as the side tab shows it. */
	static final class Row
	{
		final String file;
		final String who;
		final LocalDateTime when;
		final int ticks;
		final boolean died, killed;
		final String player;
		/** its fight's id on the page (null: not on it), the page's channel, shown to anyone */
		final String fight, channel;
		final Boolean shown;

		Row(String file, String who, LocalDateTime when, int ticks, boolean died, boolean killed, String player, String fight,
			String channel, Boolean shown)
		{
			this.file = file;
			this.who = who;
			this.when = when;
			this.ticks = ticks;
			this.died = died;
			this.killed = killed;
			this.player = player;
			this.fight = fight;
			this.channel = channel;
			this.shown = shown;
		}
	}

	/** What goes on the page from a recording: the stripped copy gzipped, its summary, and what was taken out. */
	static final class Prepared
	{
		final String file;
		final byte[] gz;
		final EncounterStrip.Summary summary;
		final Map<String, Integer> removed;

		Prepared(String file, byte[] gz, EncounterStrip.Summary summary, Map<String, Integer> removed)
		{
			this.file = file;
			this.gz = gz;
			this.summary = summary;
			this.removed = removed;
		}
	}

	interface Folder
	{
		Filepath get() throws IOException;
	}

	private final Gson gson;
	private final Folder folder;
	private final ExecutorService files = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "outside-encounters");
		t.setDaemon(true);
		return t;
	});
	/** the index, read once and kept here (only on the files thread), and whether it's changed since it was saved */
	private JsonObject index;
	private boolean dirty;

	EncounterShelf(Gson gson, Folder folder)
	{
		this.gson = gson;
		this.folder = folder;
	}

	void shutDown()
	{
		files.shutdown();
	}

	/** A file job, its failure logged and said (none once the plugin's off). */
	void run(String what, Job job, Consumer<String> problem)
	{
		if (files.isShutdown())
		{
			return;
		}
		files.execute(() ->
		{
			try
			{
				job.run();
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("outside: encounters: {}", what, e);
				if (problem != null)
				{
					problem.accept(what + ": " + e.getMessage());
				}
			}
		});
	}

	interface Job
	{
		void run() throws IOException;
	}

	private Filepath dir() throws IOException
	{
		Filepath d = folder.get().joinSegment("encounters");
		d.createDirectories();
		return d;
	}

	// ---- the index (the files thread only)

	private JsonObject index() throws IOException
	{
		if (index != null)
		{
			return index;
		}
		Filepath f = dir().joinSegment(EncounterFiles.INDEX);
		index = new JsonObject();
		if (f.isFile())
		{
			try (InputStream in = f.openInputStream())
			{
				JsonObject o = gson.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
				if (o != null)
				{
					index = o;
				}
			}
			catch (JsonParseException | IOException e)
			{
				log.warn("outside: the encounters' index couldn't be read; it's made again", e);
			}
		}
		return index;
	}

	private void saveIndex() throws IOException
	{
		EncounterFiles.writeAtomically(dir().joinSegment(EncounterFiles.INDEX), gson.toJson(index()));
		dirty = false;
	}

	private JsonObject entry(String file) throws IOException
	{
		JsonElement e = index().get(file);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	/** A recording's summary in the index (made now if it's missing or the file's changed since). */
	private JsonObject summarized(Filepath f) throws IOException
	{
		JsonObject e = entry(f.getFileName());
		long size = f.size();
		if (e != null && e.has("size") && e.get("size").getAsLong() == size && e.has("who"))
		{
			return e;
		}
		EncounterStrip.Summarizer z = new EncounterStrip.Summarizer();
		EncounterFiles.eachLine(f, z::line);
		EncounterStrip.Summary s = z.finish();
		JsonObject n = e != null ? e : new JsonObject();
		n.addProperty("size", size);
		n.addProperty("who", s.who());
		n.addProperty("ticks", (int) s.ticks);
		n.addProperty("died", s.diedIn());
		n.addProperty("killed", s.killed);
		if (s.player != null)
		{
			n.addProperty("player", s.player);
		}
		index().add(f.getFileName(), n);
		dirty = true;
		return n;
	}

	// ---- jobs

	/** At the start: what a crash left behind packed, the size cap kept, the index's entries for files gone dropped. */
	void tidy(long most, Collection<String> busy, Runnable then)
	{
		run("tidying the encounters' folder", () ->
		{
			Filepath d = dir();
			EncounterFiles.finishLeftovers(d, System.currentTimeMillis());
			EncounterFiles.cap(d, most, busy);
			Set<String> there = new HashSet<>(EncounterFiles.names(d));
			boolean changed = index().entrySet().removeIf(en -> !there.contains(en.getKey()));
			if (changed)
			{
				saveIndex();
			}
			then.run();
		}, null);
	}

	/** A recording just packed: summarized now, the size cap kept. */
	void packed(String file, long most, Collection<String> busy, Runnable then)
	{
		run("summarizing " + file, () ->
		{
			Filepath d = dir();
			Filepath f = d.joinSegment(file);
			if (f.isFile())
			{
				summarized(f);
			}
			for (String gone : EncounterFiles.cap(d, most, busy))
			{
				index().remove(gone);
			}
			saveIndex();
			then.run();
		}, null);
	}

	/** The rows, newest first (LISTED at most), summarizing any not yet summarized; and how many more the folder has. */
	void list(Collection<String> busy, java.util.function.BiConsumer<List<Row>, Integer> rows, Consumer<String> problem)
	{
		run("reading the encounters", () ->
		{
			List<Filepath> all = EncounterFiles.list(dir());
			List<Row> out = new ArrayList<>();
			for (Filepath f : all)
			{
				if (out.size() >= LISTED)
				{
					break;
				}
				if (busy.contains(f.getFileName()) || !EncounterFiles.isPacked(f.getFileName()))
				{
					continue;
				}
				JsonObject e;
				try
				{
					e = summarized(f);
				}
				catch (IOException | RuntimeException ex)
				{
					log.debug("outside: couldn't read encounter {}", f.getFileName(), ex);
					continue;
				}
				out.add(row(f, e));
			}
			if (dirty)
			{
				saveIndex();
			}
			rows.accept(out, Math.max(0, all.size() - out.size()));
		}, problem);
	}

	private static Row row(Filepath f, JsonObject e) throws IOException
	{
		LocalDateTime when = EncounterFiles.stamp(f.getFileName());
		if (when == null)
		{
			when = LocalDateTime.ofInstant(f.getLastModifiedTime().toInstant(), java.time.ZoneId.systemDefault());
		}
		return new Row(f.getFileName(), str(e, "who"), when, e.has("ticks") ? e.get("ticks").getAsInt() : 0,
			bool(e, "died"), bool(e, "killed"), str(e, "player"), str(e, "fight"), str(e, "channel"),
			e.has("shown") ? e.get("shown").getAsBoolean() : null);
	}

	private static String str(JsonObject e, String k)
	{
		return e.has(k) && e.get(k).isJsonPrimitive() ? e.get(k).getAsString() : null;
	}

	private static boolean bool(JsonObject e, String k)
	{
		return e.has(k) && e.get(k).isJsonPrimitive() && e.get(k).getAsBoolean();
	}

	/**
	 * A recording stripped for the page (EncounterStrip, as the page's strip.js), gzipped, with its summary: read twice,
	 * a line at a time (first every other player's name in it, then each line stripped and packed as it comes), so a long
	 * raid is never whole in memory.
	 */
	void prepare(String file, Consumer<Prepared> ready, Consumer<String> problem)
	{
		run("reading " + file, () ->
		{
			Filepath f = dir().joinSegment(file);
			if (!f.isFile())
			{
				throw new IOException("it isn't there any more");
			}
			if (!EncounterFiles.isHeader(EncounterFiles.firstLine(f)))
			{
				throw new IOException("it isn't an encounter recording");
			}
			EncounterStrip.Names names = new EncounterStrip.Names();
			EncounterFiles.eachLine(f, names::line);
			EncounterStrip.Stripper st = new EncounterStrip.Stripper(names.pattern());
			EncounterStrip.Summarizer z = new EncounterStrip.Summarizer();
			EncounterFiles.Packer packer = new EncounterFiles.Packer();
			IOException[] failed = new IOException[1];
			EncounterFiles.eachLine(f, line ->
			{
				String kept = st.line(line);
				if (kept != null && failed[0] == null)
				{
					z.line(kept);
					try
					{
						packer.line(kept);
					}
					catch (IOException e)
					{
						failed[0] = e;
					}
				}
			});
			if (failed[0] != null)
			{
				throw failed[0];
			}
			EncounterStrip.Summary s = z.finish();
			ready.accept(new Prepared(file, packer.finish((int) s.ticks), s, st.removed));
		}, problem);
	}

	/** What's on the page from a recording (fight null: nothing), or the choice of shown or kept before it goes. */
	void onPage(String file, String channel, String fight, Boolean shown, Runnable then)
	{
		run("noting " + file, () ->
		{
			JsonObject e = entry(file);
			if (e == null)
			{
				e = new JsonObject();
				index().add(file, e);
			}
			if (fight == null)
			{
				e.remove("fight");
				e.remove("channel");
			}
			else
			{
				e.addProperty("fight", fight);
				e.addProperty("channel", channel);
			}
			if (shown != null)
			{
				e.addProperty("shown", shown);
			}
			saveIndex();
			then.run();
		}, null);
	}

	/**
	 * The page's own list (GET /fights/<channel>, as its owner: every fight on it) against what the index says went up
	 * from here for that channel: one taken down on the page, or gone with its days, is no longer on it; one shown or
	 * kept on the page is shown or kept here.
	 */
	void reconcile(String channel, Map<String, Boolean> fights, Runnable then)
	{
		run("reading what's on the page", () ->
		{
			boolean changed = false;
			for (Map.Entry<String, JsonElement> en : index().entrySet())
			{
				if (!en.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject e = en.getValue().getAsJsonObject();
				String id = str(e, "fight");
				if (id == null || !channel.equals(str(e, "channel")))
				{
					continue;
				}
				Boolean shown = fights.get(id);
				if (shown == null)
				{
					e.remove("fight");
					e.remove("channel");
					changed = true;
				}
				else if (!shown.equals(e.has("shown") ? e.get("shown").getAsBoolean() : null))
				{
					e.addProperty("shown", shown);
					changed = true;
				}
			}
			if (changed)
			{
				saveIndex();
			}
			then.run();
		}, null);
	}

	/** What the index says of one recording (null: nothing), on the files thread. */
	void entryOf(String file, Consumer<JsonObject> found)
	{
		run("reading the index", () -> found.accept(entry(file)), null);
	}

	/** ✕: the recording's file deleted from this computer (one being written or packed left alone). */
	void delete(String file, Collection<String> busy, Runnable then, Consumer<String> problem)
	{
		run("deleting " + file, () ->
		{
			if (busy.contains(file) || !EncounterFiles.isRecordingName(file))
			{
				return;
			}
			dir().joinSegment(file).deleteIfExists();
			index().remove(file);
			saveIndex();
			then.run();
		}, problem);
	}

	/** The folder's place, for pasting into a file manager (a plugin may not open folders itself). */
	void where(Consumer<String> found, Consumer<String> problem)
	{
		run("finding the encounters' folder", () -> found.accept(dir().toString()), problem);
	}
}
