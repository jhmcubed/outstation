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
		/** (11/21/2026, round two) kept for good on the page, the page's featured one; the sender's state for it
		 *  (EncounterQueue: due, sending, sent, refused, held; null: never queued) and why; whether it has a moment */
		boolean kept, featured, moment;
		String send, why;

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

	/** What goes on the page from a recording: the stripped copy gzipped, its summary, and what was taken out; and its
	 *  scene moment (null: none) and the moment's tick. */
	static final class Prepared
	{
		final String file;
		final byte[] gz;
		final EncounterStrip.Summary summary;
		final Map<String, Integer> removed;
		byte[] moment;
		int momentTick = -1;

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
	/** the scene moments' building (11/21/2026): seconds each, so a thread of their own, the lowest priority, one at a time */
	private final ExecutorService moments = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "outside-moments");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	/** the recordings whose moment is still being built (the sender waits for it) */
	private final Set<String> building = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
		moments.shutdownNow();
	}

	/** A recording's moment file, beside it (11/21/2026): its name and ".moment" (not a recording's name, so the
	 *  folder's lists pass it by). */
	static String momentName(String file)
	{
		return file + ".moment";
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
		if (s.label instanceof String)
		{
			n.addProperty("label", (String) s.label);
		}
		if (s.started instanceof Double)
		{
			n.addProperty("at", (Double) s.started);
		}
		if (s.player != null)
		{
			n.addProperty("player", s.player);
		}
		if (s.follows != null)
		{
			n.addProperty("follows", s.follows);
		}
		index().add(f.getFileName(), n);
		dirty = true;
		return n;
	}

	// ---- jobs

	/** At the start: what a crash left behind packed (and, {@code send}, queued to go up by itself: Jace, 11/21/2026,
	 *  round three), the size cap kept, the index's entries for files gone dropped. */
	void tidy(long most, Collection<String> busy, boolean send, Runnable then)
	{
		run("tidying the encounters' folder", () ->
		{
			Filepath d = dir();
			for (String left : EncounterFiles.finishLeftovers(d, System.currentTimeMillis()))
			{
				if (send)
				{
					queueNow(left);
				}
			}
			EncounterFiles.cap(d, most, busy);
			Set<String> there = new HashSet<>(EncounterFiles.names(d));
			boolean changed = index().entrySet().removeIf(en -> !there.contains(en.getKey()));
			// (a scene moment whose recording's gone goes too: 11/21/2026)
			try (java.util.stream.Stream<Filepath> all = d.walk(1))
			{
				for (Filepath f : (Iterable<Filepath>) all::iterator)
				{
					String n = f.getFileName();
					if (n.endsWith(".moment") && !there.contains(n.substring(0, n.length() - ".moment".length())))
					{
						f.deleteIfExists();
					}
				}
			}
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
			// (round three: a recording's earlier parts aren't rows of their own; the last part's row is all of it)
			Set<String> followed = new HashSet<>();
			for (Map.Entry<String, JsonElement> en : index().entrySet())
			{
				if (en.getValue().isJsonObject() && str(en.getValue().getAsJsonObject(), "follows") != null)
				{
					followed.add(str(en.getValue().getAsJsonObject(), "follows"));
				}
			}
			for (Filepath f : all)
			{
				if (out.size() >= LISTED)
				{
					break;
				}
				if (busy.contains(f.getFileName()) || !EncounterFiles.isPacked(f.getFileName()) || followed.contains(f.getFileName()))
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
				if (str(e, "follows") != null)
				{
					// (a part read for the first time just now names the one before: that one's row goes)
					followed.add(str(e, "follows"));
					out.removeIf(r -> followed.contains(r.file));
				}
				Row r = row(f, e);
				if (str(e, "follows") != null)
				{
					r = withTicks(r, (int) chainSummary(dir(), f.getFileName())[1]);
				}
				out.add(r);
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
		Row r = new Row(f.getFileName(), str(e, "who"), when, e.has("ticks") ? e.get("ticks").getAsInt() : 0,
			bool(e, "died"), bool(e, "killed"), str(e, "player"), str(e, "fight"), str(e, "channel"),
			e.has("shown") ? e.get("shown").getAsBoolean() : null);
		r.kept = bool(e, "kept");
		r.featured = bool(e, "featured");
		r.send = str(e, "send");
		r.why = str(e, "why");
		r.moment = f.getParent().joinSegment(momentName(f.getFileName())).isFile();
		return r;
	}

	/** A row with the whole recording's length (its parts'). */
	private static Row withTicks(Row r, int ticks)
	{
		Row n = new Row(r.file, r.who, r.when, ticks, r.died, r.killed, r.player, r.fight, r.channel, r.shown);
		n.kept = r.kept;
		n.featured = r.featured;
		n.send = r.send;
		n.why = r.why;
		n.moment = r.moment;
		return n;
	}

	/** A recording's start and length as the page has them, its parts joined: {the first part's start (ms), every part's
	 *  ticks added up} (EncounterFiles.eachJoined counts them so). The files thread. */
	private double[] chainSummary(Filepath d, String file) throws IOException
	{
		double at = 0, ticks = 0;
		List<Filepath> parts = EncounterFiles.chain(d, file);
		for (int i = 0; i < parts.size(); i++)
		{
			Filepath p = parts.get(i);
			JsonObject e = EncounterFiles.isPacked(p.getFileName()) ? summarized(p) : null;
			if (e == null)
			{
				continue;
			}
			if (i == 0)
			{
				at = e.has("at") ? e.get("at").getAsDouble() : 0;
			}
			ticks += e.has("ticks") ? e.get("ticks").getAsDouble() : 0;
		}
		if (parts.isEmpty())
		{
			JsonObject e = entry(file);
			at = e != null && e.has("at") ? e.get("at").getAsDouble() : 0;
			ticks = e != null && e.has("ticks") ? e.get("ticks").getAsDouble() : 0;
		}
		return new double[]{at, ticks};
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
			// (a recording past the length limit: its parts joined, the ticks running on; round three)
			List<Filepath> parts = EncounterFiles.chain(dir(), file);
			if (parts.isEmpty())
			{
				parts = java.util.Collections.singletonList(f);
			}
			EncounterStrip.Names names = new EncounterStrip.Names();
			EncounterFiles.eachJoined(parts, names::line);
			EncounterStrip.Stripper st = new EncounterStrip.Stripper(names.pattern());
			EncounterStrip.Summarizer z = new EncounterStrip.Summarizer();
			EncounterFiles.Packer packer = new EncounterFiles.Packer();
			IOException[] failed = new IOException[1];
			EncounterFiles.eachJoined(parts, line ->
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
			Prepared p = new Prepared(file, packer.finish((int) s.ticks), s, st.removed);
			// (its scene moment, if one was made: its tick read from its own meta, as the relay reads it)
			Filepath mf = dir().joinSegment(momentName(file));
			if (mf.isFile() && mf.size() <= 3 * 1024 * 1024)
			{
				try (InputStream in = mf.openInputStream())
				{
					byte[] b = in.readAllBytes();
					EncounterMoment.Moment m = EncounterMoment.decode(b);
					int tick = (int) EncounterMoment.num(m.meta.get("tick"));
					if (tick >= 0 && tick <= s.ticks)
					{
						p.moment = b;
						p.momentTick = tick;
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("outside: the moment beside {} couldn't be read; it goes without", file, e);
				}
			}
			ready.accept(p);
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
				e.remove("kept");
				e.remove("featured");
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
	void reconcile(String channel, Map<String, boolean[]> fights, Runnable then)
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
				// (each: shown, kept for good, featured)
				boolean[] on = fights.get(id);
				if (on == null)
				{
					e.remove("fight");
					e.remove("channel");
					e.remove("kept");
					e.remove("featured");
					// (taken down there: never sent again by itself)
					if (EncounterQueue.SENT.equals(str(e, "send")))
					{
						e.addProperty("why", "taken down from your page");
					}
					changed = true;
					continue;
				}
				changed |= set(e, "shown", on[0]) | set(e, "kept", on[1]) | set(e, "featured", on[2]);
			}
			if (changed)
			{
				saveIndex();
			}
			then.run();
		}, null);
	}

	private static boolean set(JsonObject e, String k, boolean v)
	{
		if (e.has(k) && e.get(k).isJsonPrimitive() && e.get(k).getAsBoolean() == v)
		{
			return false;
		}
		e.addProperty(k, v);
		return true;
	}

	/** Kept for good, or the page's featured one, as the relay answered (the featured one is always kept; a page has
	 *  one featured, so the others' marks go). */
	void marks(String file, Boolean kept, Boolean featured, Runnable then)
	{
		run("noting " + file, () ->
		{
			JsonObject e = entry(file);
			if (e == null)
			{
				return;
			}
			if (Boolean.TRUE.equals(featured))
			{
				for (Map.Entry<String, JsonElement> en : index().entrySet())
				{
					if (en.getValue().isJsonObject())
					{
						en.getValue().getAsJsonObject().remove("featured");
					}
				}
			}
			if (kept != null)
			{
				e.addProperty("kept", kept);
			}
			if (featured != null)
			{
				e.addProperty("featured", featured);
			}
			saveIndex();
			then.run();
		}, null);
	}

	// ---- each encounter up by itself (11/21/2026; EncounterQueue)

	/** A recording finished with "Put each encounter on my page" on: due to go up (once its moment's made). */
	void queue(String file, Runnable then)
	{
		run("queueing " + file, () ->
		{
			queueNow(file);
			then.run();
		}, null);
	}

	/** Queued on the files thread (one on the page already, or queued once before: never twice). The file may not be
	 *  packed yet (round three: queued as the recording ends, so a client closed while it packs still sends it). */
	private void queueNow(String file) throws IOException
	{
		JsonObject e = entry(file);
		if (e == null)
		{
			e = new JsonObject();
			index().add(file, e);
		}
		if (e.has("fight") || e.has("send"))
		{
			return;
		}
		e.addProperty("send", EncounterQueue.DUE);
		e.addProperty("next", 0L);
		e.addProperty("tries", 0);
		saveIndex();
	}

	/** A recording whose moment is still to be made (its scene was taken as it ended): the sender waits for it. Called
	 *  on the client's thread as it ends, before it's packed. */
	void expect(String file)
	{
		building.add(file);
	}

	/** A send to make now: its file, its index entry as it was, and whether its last send went unanswered. */
	static final class Due
	{
		final String file;
		final boolean unsure;
		final double at, ticks;

		Due(String file, boolean unsure, double at, double ticks)
		{
			this.file = file;
			this.unsure = unsure;
			this.at = at;
			this.ticks = ticks;
		}
	}

	/**
	 * The oldest recording due to go up now, for the character logged in ({@code me}; one recorded on another waits),
	 * its moment made; marked "sending" (and its tries counted) before anything leaves, so a crash mid-send is known to
	 * be unsure. Null when none is due. The files thread.
	 */
	void nextDue(long now, String me, Collection<String> busy, Consumer<Due> found)
	{
		run("finding what's due to go up", () ->
		{
			Filepath d = dir();
			String best = null;
			LocalDateTime bestWhen = null;
			for (Map.Entry<String, JsonElement> en : index().entrySet())
			{
				if (!en.getValue().isJsonObject())
				{
					continue;
				}
				JsonObject e = en.getValue().getAsJsonObject();
				String st = str(e, "send");
				if (!EncounterQueue.DUE.equals(st) && !EncounterQueue.SENDING.equals(st) || e.has("fight"))
				{
					continue;
				}
				if (e.has("next") && e.get("next").getAsLong() > now || busy.contains(en.getKey()) || building.contains(en.getKey()))
				{
					continue;
				}
				if (!d.joinSegment(en.getKey()).isFile())
				{
					continue;
				}
				// (only on the character it was recorded on: its player read from its header first, if it hasn't been;
				// one that names no player (not this recorder's) never goes by itself; round three)
				if (!e.has("who"))
				{
					try
					{
						e = summarized(d.joinSegment(en.getKey()));
					}
					catch (IOException | RuntimeException ex)
					{
						continue;
					}
				}
				String player = str(e, "player");
				if (player == null || me == null || !OutsidePanel.sameName(player, me))
				{
					continue;
				}
				LocalDateTime when = EncounterFiles.stamp(en.getKey());
				if (best == null || when != null && bestWhen != null && when.isBefore(bestWhen))
				{
					best = en.getKey();
					bestWhen = when;
				}
			}
			if (best == null)
			{
				found.accept(null);
				return;
			}
			JsonObject e = entry(best);
			boolean unsure = EncounterQueue.SENDING.equals(str(e, "send"));
			e.addProperty("send", EncounterQueue.SENDING);
			e.addProperty("tries", (e.has("tries") ? e.get("tries").getAsInt() : 0) + 1);
			saveIndex();
			// (the page's list has the parts joined: the first part's start, every part's ticks; round three)
			double[] whole = chainSummary(d, best);
			found.accept(new Due(best, unsure, whole[0], whole[1]));
		}, null);
	}

	/** A send's end: its state and when it's due again; on the page, its id and the page's channel too. */
	void sent(String file, EncounterQueue.After after, String fight, String channel, Boolean shown, Runnable then)
	{
		run("noting " + file, () ->
		{
			JsonObject e = entry(file);
			if (e == null)
			{
				return;
			}
			e.addProperty("send", after.state);
			e.addProperty("next", after.next);
			if (after.why == null)
			{
				e.remove("why");
			}
			else
			{
				e.addProperty("why", after.why);
			}
			if (fight != null)
			{
				e.addProperty("fight", fight);
				e.addProperty("channel", channel);
				e.addProperty("tries", 0);
			}
			if (shown != null)
			{
				e.addProperty("shown", shown);
			}
			saveIndex();
			then.run();
		}, null);
	}

	/** How many tries a recording's send has had. */
	int triesOf(JsonObject e)
	{
		return e != null && e.has("tries") ? e.get("tries").getAsInt() : 0;
	}

	// ---- the scene moments (11/21/2026; EncounterScene)

	/**
	 * A recording's moment built from what its scene took (on the moments thread), once the recording's packed and
	 * summarized: its foes and label from the summary (never a player's name), then written beside it. {@code then}
	 * runs either way (made or not: the sender waits on it).
	 */
	void moment(String file, EncounterScene.Taken taken, Runnable then)
	{
		if (taken == null)
		{
			building.remove(file);
			then.run();
			return;
		}
		building.add(file);
		run("reading " + file + "'s summary", () ->
		{
			JsonObject e = entry(file);
			Map<String, Object> base = new java.util.LinkedHashMap<>();
			base.put("fight", null);
			base.put("foes", e != null && e.has("who") ? e.get("who").getAsString() : null);
			base.put("label", e != null && e.has("label") ? e.get("label").getAsString() : null);
			base.put("at", e != null && e.has("at") ? (Object) e.get("at").getAsDouble() : null);
			Filepath target = dir().joinSegment(momentName(file));
			try
			{
				moments.execute(() ->
				{
					try
					{
						long t0 = System.nanoTime();
						byte[] bytes = EncounterScene.build(taken, base, file.hashCode());
						long ms = (System.nanoTime() - t0) / 1_000_000;
						run("writing " + file + "'s moment", () ->
						{
							try
							{
								target.write(bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
									java.nio.file.StandardOpenOption.WRITE);
								log.info("outside: {}'s moment: {} KB, built in {} ms; the client thread's share {} ms over {} frames taken ({} us a frame), the still scene {} ms, the buffer {} KB, {} frames kept",
									file, bytes.length / 1024, ms, taken.clientNanos / 1_000_000, taken.framesTaken,
									taken.framesTaken == 0 ? 0 : taken.clientNanos / 1000 / taken.framesTaken, taken.stillNanos / 1_000_000,
									taken.bufferBytes / 1024, taken.frames.size());
							}
							finally
							{
								building.remove(file);
								then.run();
							}
						}, null);
					}
					catch (IOException | RuntimeException | OutOfMemoryError ex)
					{
						log.warn("outside: {}'s moment couldn't be made; it goes without", file, ex);
						building.remove(file);
						then.run();
					}
				});
			}
			catch (java.util.concurrent.RejectedExecutionException ex)
			{
				building.remove(file);
			}
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
			// (its earlier parts with it: they're one recording; round three)
			for (Filepath p : EncounterFiles.chain(dir(), file))
			{
				String n = p.getFileName();
				if (busy.contains(n) || !EncounterFiles.isRecordingName(n))
				{
					continue;
				}
				p.deleteIfExists();
				dir().joinSegment(momentName(n)).deleteIfExists();
				index().remove(n);
			}
			dir().joinSegment(file).deleteIfExists();
			dir().joinSegment(momentName(file)).deleteIfExists();
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
