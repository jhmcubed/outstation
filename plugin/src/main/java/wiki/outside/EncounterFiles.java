/*
 * Encounter Recorder's Recording.java, Compressed.java and Recordings.java (github.com/mrgnomnom/encounter-recorder, the
 * Plugin Hub's commit 3570026), ported into Outside on 11/21/2026, under their licence:
 *
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, Mr Gnome
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The encounters' files (11/21/2026; ghillie's claude/outstation.md, "Encounters on Outside: the night's design"), as
 * Encounter Recorder keeps its own (its docs/FORMAT.md, "Packing"), in RuneLite's folder for this plugin
 * (.runelite/plugin-data/outside/encounters: the Plugin Hub's rules let a plugin's files go only through RuneLite's
 * Filepath, under its own folder):
 * <ul>
 * <li>a recording is written as plain JSON lines while it runs, on a thread of its own (the client's thread only queues
 * them, once a tick), so a crash never leaves a broken file;</li>
 * <li>when it stops it's gzipped beside itself (about 18 times smaller), the gzip header's comment holding its length,
 * {"ticks":N}, and the plain file deleted;</li>
 * <li>one the client closed, crashed or was killed in the middle of is given its end line and packed the next time the
 * plugin starts (a file nothing's written for 2 minutes, that no other RuneLite holds the lock of);</li>
 * <li>named yyyyMMdd-HHmmss-label.jsonl(.gz), a label already there numbered, as Encounter Recorder names them, so his
 * replay lists them the same.</li>
 * </ul>
 * Outside's own: the newest kept within a size cap (the oldest go first: cap()), and an index beside them (index.json:
 * each file's summary, so the side tab lists them without unpacking each, and what's on the page from it).
 */
final class EncounterFiles
{
	private static final Logger log = LoggerFactory.getLogger(EncounterFiles.class);

	private EncounterFiles()
	{
	}

	static final String EXT = ".gz";
	/** A gzip being written: renamed to .gz only once it's whole */
	private static final String PART = ".part";
	/** A recording being written holds a lock on this byte, far past its end, so another RuneLite open on the same
	 *  folder leaves it alone. Past the end, so it never stops anything reading the file */
	static final long LOCK_AT = Long.MAX_VALUE - 1;
	/** A plain recording left alone this long is no longer being written */
	static final long IDLE_MS = 2 * 60 * 1000;
	/** How much of a file is read to find its first line */
	static final int FIRST_LINE_BYTES = 1 << 16;
	/** How far a gzip is unpacked (to read it whole, or find its length): past any real recording */
	static final long MAX_UNPACKED = 500L * 1024 * 1024;
	/** How much of a file's end is read for its last line */
	static final int TAIL_BYTES = 8192;
	static final String INDEX = "index.json";
	private static final Pattern COMMENT_TICKS = Pattern.compile("\"ticks\":(\\d+)");
	private static final int FLAG_EXTRA = 4;
	private static final int FLAG_NAME = 8;
	private static final int FLAG_COMMENT = 16;
	private static final Pattern FILE = Pattern.compile("^(\\d{8}-\\d{6})-(.*)\\.jsonl(?:\\.gz)?$");
	private static final Pattern EXTENSION = Pattern.compile("(?i)\\.jsonl(?:\\.gz)?$|\\.gz$");
	private static final Pattern COUNTED = Pattern.compile("^(.*)_(\\d+)$");
	private static final Pattern HEADER = Pattern.compile("\"e\"\\s*:\\s*\"header\"");
	private static final Pattern TICK = Pattern.compile("^\\{\"t\":(\\d+),");
	private static final Pattern END_TICKS = Pattern.compile("\"e\":\"end\".*\"ticks\":(\\d+)");
	static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	// ---- one recording being written (Encounter Recorder's Recording)

	interface Directory
	{
		Filepath get() throws IOException;
	}

	/**
	 * One recording: a JSON-lines file written on its own thread. Lines are queued on the client thread and handed over
	 * once per tick, so the client thread never touches the disk. When it stops, the same thread packs it.
	 */
	static final class Writer
	{
		private final Gson gson;
		private final ExecutorService writer;
		private List<JsonObject> pending = new ArrayList<>();
		private java.io.Writer out;
		private volatile boolean failed;
		private volatile String fileName;
		private int ticks;
		private long lines;
		private final AtomicLong bytes = new AtomicLong();
		private volatile Filepath file;
		private volatile Future<?> finished;

		Writer(Gson gson, Directory directory, String label, LocalDateTime now)
		{
			this.gson = gson;
			this.fileName = STAMP.format(now) + "-" + safeLabel(label) + ".jsonl";
			this.writer = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "outside-encounter");
				t.setDaemon(true);
				return t;
			});
			writer.execute(() ->
			{
				try
				{
					Filepath dir = directory.get();
					dir.createDirectories();
					// A name already in the folder gets the next number: TFA, TFA-1, TFA-2...
					fileName = numbered(names(dir), fileName, null);
					file = dir.joinSegment(fileName);
					FileChannel channel = file.openFileChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
					// Marks the file as being written, for another RuneLite tidying up the same folder
					try
					{
						channel.tryLock(LOCK_AT, 1, false);
					}
					catch (IOException | OverlappingFileLockException e)
					{
						log.debug("outside: couldn't lock encounter {}", fileName, e);
					}
					out = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8.newEncoder(), -1));
				}
				catch (IOException | IllegalArgumentException e)
				{
					log.warn("outside: couldn't open encounter {}", fileName, e);
					failed = true;
				}
			});
		}

		String fileName()
		{
			return fileName;
		}

		int ticks()
		{
			return ticks;
		}

		long lines()
		{
			return lines;
		}

		long bytesWritten()
		{
			return bytes.get();
		}

		boolean hasFailed()
		{
			return failed;
		}

		Future<?> finished()
		{
			return finished;
		}

		void add(JsonObject line)
		{
			pending.add(line);
			lines++;
		}

		/** The lines queued this tick dropped, never written (somewhere dangerous: nothing of it goes in the file) */
		void dropPending()
		{
			lines -= pending.size();
			pending = new ArrayList<>();
		}

		void endTick()
		{
			ticks++;
			flush();
		}

		private void flush()
		{
			if (pending.isEmpty())
			{
				return;
			}
			List<JsonObject> batch = pending;
			pending = new ArrayList<>();
			writer.execute(() -> write(batch));
		}

		private void write(List<JsonObject> batch)
		{
			if (failed || out == null)
			{
				return;
			}
			try
			{
				for (JsonObject line : batch)
				{
					String text = gson.toJson(line);
					out.write(text);
					out.write('\n');
					bytes.addAndGet(text.length() + 1);
				}
				out.flush();
			}
			catch (IOException e)
			{
				log.warn("outside: encounter {} stopped writing", fileName, e);
				failed = true;
			}
		}

		/** Writes what's queued, closes the file and packs it, then runs {@code whenDone} with the packed file's name,
		 *  all on the writer thread, which then finishes on its own. A file that can't be packed stays as it is */
		Future<?> close(java.util.function.Consumer<String> whenDone)
		{
			flush();
			int length = ticks;
			finished = writer.submit(() ->
			{
				String done = null;
				try
				{
					if (out == null)
					{
						return;
					}
					try
					{
						out.close();
					}
					catch (IOException e)
					{
						log.warn("outside: couldn't close encounter {}", fileName, e);
						return;
					}
					done = fileName;
					try
					{
						fileName = compress(file.getParent(), fileName, length, null);
						done = fileName;
					}
					catch (IOException | RuntimeException e)
					{
						log.warn("outside: couldn't pack encounter {}; it's kept unpacked", fileName, e);
					}
				}
				finally
				{
					whenDone.accept(done);
				}
			});
			writer.shutdown();
			return finished;
		}
	}

	/** Keeps letters, digits and dashes so the label is safe in a file name on every OS */
	static String safeLabel(String label)
	{
		// Apostrophes go without a gap: "K'ril" is "Kril", not "K ril"
		String cleaned = label == null ? "" : label.trim().replace("'", "").replaceAll("[^A-Za-z0-9-]+", "-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
		if (cleaned.isEmpty())
		{
			return "session";
		}
		return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
	}

	// ---- packing (Encounter Recorder's Compressed)

	static boolean isGzip(byte[] head, int length)
	{
		return length >= 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b;
	}

	/**
	 * Gzips a recording next to itself, then deletes the plain file, and returns the new file's name. Only whole lines
	 * are kept, so a recording cut off by a crash loses just its half-written last line; {@code endLine}, if given, goes
	 * after them. {@code ticks} is the recording's length, or -1 if unknown
	 */
	static String compress(Filepath dir, String fileName, int ticks, String endLine) throws IOException
	{
		Filepath plain = dir.joinSegment(fileName);
		String packedName = fileName + EXT;
		Filepath packed = dir.joinSegment(packedName);
		Filepath part = dir.joinSegment(packedName + PART);
		try (FileChannel in = plain.openFileChannel(StandardOpenOption.READ))
		{
			long keep = wholeLines(in);
			// CREATE_NEW: if another RuneLite is packing the same file, leave it to that one
			try (OutputStream out = new BufferedOutputStream(part.openOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), 1 << 16))
			{
				gzip(in, keep, endLine, ticks, out);
			}
			catch (IOException | RuntimeException e)
			{
				part.deleteIfExists();
				throw e;
			}
		}
		part.moveTo(packed);
		try
		{
			plain.delete();
		}
		catch (IOException e)
		{
			// Open elsewhere, say in a replay, on Windows: the list shows the packed one, and the plain copy goes the
			// next time the plugin starts
			log.warn("outside: packed {} but couldn't delete the unpacked copy yet", fileName, e);
		}
		return packedName;
	}

	/** Writes the first {@code length} bytes of {@code in}, then {@code endLine}, as a gzip with the length in its header */
	static void gzip(FileChannel in, long length, String endLine, int ticks, OutputStream out) throws IOException
	{
		writeHeader(out, ticks);
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		CRC32 crc = new CRC32();
		long size = 0;
		try
		{
			DeflaterOutputStream deflate = new DeflaterOutputStream(out, deflater, 1 << 16);
			ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
			while (size < length)
			{
				buffer.clear();
				buffer.limit((int) Math.min(buffer.capacity(), length - size));
				int read = in.read(buffer, size);
				if (read <= 0)
				{
					break;
				}
				deflate.write(buffer.array(), 0, read);
				crc.update(buffer.array(), 0, read);
				size += read;
			}
			if (endLine != null)
			{
				byte[] end = (endLine + "\n").getBytes(StandardCharsets.UTF_8);
				deflate.write(end);
				crc.update(end);
				size += end.length;
			}
			deflate.finish();
		}
		finally
		{
			deflater.end();
		}
		writeIntLE(out, crc.getValue());
		writeIntLE(out, size);
	}

	private static void writeHeader(OutputStream out, int ticks) throws IOException
	{
		byte[] comment = ticks >= 0 ? ("{\"ticks\":" + ticks + "}").getBytes(StandardCharsets.ISO_8859_1) : null;
		// Magic, deflate, flags, no time, no extra flags, unknown OS
		out.write(new byte[]{0x1f, (byte) 0x8b, 8, (byte) (comment != null ? FLAG_COMMENT : 0), 0, 0, 0, 0, 0, (byte) 255});
		if (comment != null)
		{
			out.write(comment);
			out.write(0);
		}
	}

	private static void writeIntLE(OutputStream out, long value) throws IOException
	{
		for (int i = 0; i < 4; i++)
		{
			out.write((int) (value >>> (8 * i)) & 0xff);
		}
	}

	/** How many bytes of the file are whole lines: up to and including its last newline */
	static long wholeLines(FileChannel in) throws IOException
	{
		long end = in.size();
		ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
		while (end > 0)
		{
			long from = Math.max(0, end - buffer.capacity());
			buffer.clear();
			buffer.limit((int) (end - from));
			while (buffer.hasRemaining() && in.read(buffer, from + buffer.position()) > 0)
			{
				// Keep reading until the chunk is in
			}
			for (int i = buffer.position() - 1; i >= 0; i--)
			{
				if (buffer.get(i) == '\n')
				{
					return from + i + 1;
				}
			}
			end = from;
		}
		return 0;
	}

	/** The length a gzip's header gives, from its first bytes; -1 if it gives none */
	static int ticksFromHeader(byte[] head, int length)
	{
		if (!isGzip(head, length) || length < 10)
		{
			return -1;
		}
		int flags = head[3] & 0xff;
		int at = 10;
		if ((flags & FLAG_EXTRA) != 0)
		{
			if (at + 2 > length)
			{
				return -1;
			}
			at += 2 + ((head[at] & 0xff) | (head[at + 1] & 0xff) << 8);
		}
		if ((flags & FLAG_NAME) != 0)
		{
			while (at < length && head[at] != 0)
			{
				at++;
			}
			at++;
		}
		if ((flags & FLAG_COMMENT) == 0 || at >= length)
		{
			return -1;
		}
		int start = at;
		while (at < length && head[at] != 0)
		{
			at++;
		}
		Matcher m = COMMENT_TICKS.matcher(new String(head, start, at - start, StandardCharsets.ISO_8859_1));
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	private static int readUpTo(InputStream in, byte[] into) throws IOException
	{
		int length = 0;
		for (int read; length < into.length && (read = in.read(into, length, into.length - length)) > 0; )
		{
			length += read;
		}
		return length;
	}

	/**
	 * Each line of a recording, unpacked if it's gzipped, as JavaScript's text.split("\n") gives them (at each \n only,
	 * the piece after the last one too), read a piece at a time so a long raid is never whole in memory (at most
	 * MAX_UNPACKED unpacked: past it, an IOException).
	 */
	static void eachLine(Filepath f, java.util.function.Consumer<String> line) throws IOException
	{
		byte[] head = new byte[2];
		int length;
		try (InputStream in = f.openInputStream())
		{
			length = readUpTo(in, head);
		}
		try (InputStream raw = f.openInputStream();
			InputStream in = isGzip(head, length) ? new GZIPInputStream(raw, 1 << 16) : raw;
			java.io.Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8))
		{
			char[] chunk = new char[1 << 16];
			StringBuilder cur = new StringBuilder(4096);
			long total = 0;
			for (int read; (read = r.read(chunk)) > 0; )
			{
				total += read;
				if (total > MAX_UNPACKED)
				{
					throw new IOException("past " + (MAX_UNPACKED >> 20) + " MB unpacked");
				}
				int from = 0;
				for (int i = 0; i < read; i++)
				{
					if (chunk[i] == '\n')
					{
						cur.append(chunk, from, i - from);
						line.accept(cur.toString());
						cur.setLength(0);
						from = i + 1;
					}
				}
				cur.append(chunk, from, read - from);
			}
			line.accept(cur.toString());
		}
	}

	/** Lines gzipped as they come, as a packed recording is (the length in the header's comment), held packed in memory
	 *  only: what goes on the page, at most a few MB. */
	static final class Packer
	{
		private final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream(1 << 20);
		private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
		private final DeflaterOutputStream deflate = new DeflaterOutputStream(body, deflater, 1 << 16);
		private final CRC32 crc = new CRC32();
		private long size;

		void line(String text) throws IOException
		{
			byte[] b = (text + "\n").getBytes(StandardCharsets.UTF_8);
			deflate.write(b);
			crc.update(b);
			size += b.length;
		}

		/** The gzip, its header saying {@code ticks} */
		byte[] finish(int ticks) throws IOException
		{
			try
			{
				deflate.finish();
			}
			finally
			{
				deflater.end();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 64);
			writeHeader(out, ticks);
			body.writeTo(out);
			writeIntLE(out, crc.getValue());
			writeIntLE(out, size);
			return out.toByteArray();
		}
	}

	/** A recording's first line, unpacked if it's gzipped */
	static String firstLine(Filepath f) throws IOException
	{
		byte[] head = new byte[2];
		int length;
		try (InputStream in = f.openInputStream())
		{
			length = readUpTo(in, head);
		}
		try (InputStream in = f.openInputStream();
			InputStream unpacked = isGzip(head, length) ? new GZIPInputStream(in) : in)
		{
			return firstLine(unpacked);
		}
	}

	/** The start of a stream, up to its first newline or {@link #FIRST_LINE_BYTES} */
	static String firstLine(InputStream in) throws IOException
	{
		byte[] start = new byte[FIRST_LINE_BYTES];
		int length = 0;
		for (int read; length < start.length && (read = in.read(start, length, start.length - length)) > 0; )
		{
			for (int i = length; i < length + read; i++)
			{
				if (start[i] == '\n')
				{
					return new String(start, 0, i, StandardCharsets.UTF_8);
				}
			}
			length += read;
		}
		return new String(start, 0, length, StandardCharsets.UTF_8);
	}

	static boolean isHeader(String firstLine)
	{
		return firstLine != null && firstLine.trim().startsWith("{") && HEADER.matcher(firstLine).find();
	}

	/** Whether another RuneLite holds this file's lock, so it's still recording into it */
	static boolean inUse(Filepath f)
	{
		try (FileChannel channel = f.openFileChannel(StandardOpenOption.READ, StandardOpenOption.WRITE))
		{
			FileLock lock = channel.tryLock(LOCK_AT, 1, false);
			if (lock == null)
			{
				return true;
			}
			lock.release();
			return false;
		}
		catch (IOException | OverlappingFileLockException e)
		{
			return true;
		}
	}

	/**
	 * Packs what a recording that never stopped left behind: the client closed before it finished packing, or crashed,
	 * or was killed. Its file gets the end line it never wrote. Only plain recordings nothing has touched for
	 * {@link #IDLE_MS}, and that no other RuneLite has locked, are packed. Returns the names packed.
	 */
	static List<String> finishLeftovers(Filepath dir, long now)
	{
		List<String> packed = new ArrayList<>();
		if (!dir.isDirectory())
		{
			return packed;
		}
		List<Filepath> files;
		try (Stream<Filepath> walk = dir.walk(1))
		{
			files = walk.filter(Filepath::isFile).collect(Collectors.toList());
		}
		catch (IOException e)
		{
			return packed;
		}
		for (Filepath f : files)
		{
			String name = f.getFileName();
			try
			{
				if (now - f.getLastModifiedTime().toMillis() < IDLE_MS)
				{
					continue;
				}
				if (name.endsWith(".jsonl" + EXT + PART))
				{
					// Packing stopped halfway; the plain file it came from is still there
					f.deleteIfExists();
				}
				else if (name.endsWith(".jsonl"))
				{
					String done = finishLeftover(dir, f);
					if (done != null)
					{
						packed.add(done);
					}
				}
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("outside: couldn't finish leftover encounter {}", name, e);
			}
		}
		return packed;
	}

	private static String finishLeftover(Filepath dir, Filepath f) throws IOException
	{
		String name = f.getFileName();
		Filepath packed = dir.joinSegment(name + EXT);
		if (packed.isFile())
		{
			// Packed, but closed before the plain copy was deleted: the packed one holds at least as much
			if (unpackedSize(packed) >= f.size())
			{
				f.delete();
			}
			return null;
		}
		if (inUse(f))
		{
			return null;
		}
		String tail = tail(f, f.size());
		if (isFinished(tail))
		{
			return null;
		}
		int ticks = ticksFromTail(tail);
		String end = ticks < 0 ? null
			: "{\"t\":" + ticks + ",\"e\":\"end\",\"reason\":\"not stopped: the client closed or crashed\",\"ticks\":" + ticks + "}";
		return compress(dir, name, ticks, end);
	}

	/** A gzip's unpacked size, from its last four bytes (modulo 4 GB, more than any recording) */
	private static long unpackedSize(Filepath f) throws IOException
	{
		try (FileChannel in = f.openFileChannel(StandardOpenOption.READ))
		{
			ByteBuffer last = ByteBuffer.allocate(4);
			long size = in.size();
			if (size < 4)
			{
				return -1;
			}
			in.read(last, size - 4);
			return (last.get(0) & 0xffL) | (last.get(1) & 0xffL) << 8 | (last.get(2) & 0xffL) << 16 | (last.get(3) & 0xffL) << 24;
		}
	}

	// ---- the folder's recordings (Encounter Recorder's Recordings)

	/** The recordings in the folder, newest first (by the time in their names, else the file's) */
	static List<Filepath> list(Filepath dir) throws IOException
	{
		List<Filepath> found = files(dir);
		found.sort(Comparator.comparing((Filepath f) -> when(f)).reversed().thenComparing(Filepath::getFileName));
		return found;
	}

	private static LocalDateTime when(Filepath f)
	{
		LocalDateTime t = stamp(f.getFileName());
		if (t != null)
		{
			return t;
		}
		try
		{
			return LocalDateTime.ofInstant(f.getLastModifiedTime().toInstant(), java.time.ZoneId.systemDefault());
		}
		catch (IOException e)
		{
			return LocalDateTime.MIN;
		}
	}

	/** The time in a recording's name, or null */
	static LocalDateTime stamp(String fileName)
	{
		Matcher m = FILE.matcher(fileName);
		if (m.matches())
		{
			try
			{
				return LocalDateTime.parse(m.group(1), STAMP);
			}
			catch (DateTimeParseException e)
			{
				return null;
			}
		}
		return null;
	}

	/** The names of the recordings in the folder */
	static List<String> names(Filepath dir) throws IOException
	{
		return files(dir).stream().map(Filepath::getFileName).collect(Collectors.toList());
	}

	private static List<Filepath> files(Filepath dir) throws IOException
	{
		if (!dir.isDirectory())
		{
			return new ArrayList<>();
		}
		try (Stream<Filepath> walk = dir.walk(1))
		{
			List<Filepath> found = walk.filter(f -> isRecordingName(f.getFileName()) && f.isFile()).collect(Collectors.toList());
			// A plain recording whose packed copy is there too is on its way out: list it once
			Set<String> names = found.stream().map(Filepath::getFileName).collect(Collectors.toSet());
			found.removeIf(f -> names.contains(f.getFileName() + EXT));
			return found;
		}
	}

	static boolean isRecordingName(String fileName)
	{
		return fileName.endsWith(".jsonl") || fileName.endsWith(".jsonl" + EXT);
	}

	static boolean isPacked(String fileName)
	{
		return fileName.endsWith(EXT);
	}

	/** How a recording's file name ends: .jsonl.gz once packed, .jsonl before */
	private static String extension(String fileName)
	{
		return isPacked(fileName.toLowerCase()) ? ".jsonl" + EXT : ".jsonl";
	}

	/** The last few KB of a file, enough for its last line */
	static String tail(Filepath f, long bytes)
	{
		long from = Math.max(0, bytes - TAIL_BYTES);
		ByteBuffer buffer = ByteBuffer.allocate((int) (bytes - from));
		try (FileChannel channel = f.openFileChannel(StandardOpenOption.READ))
		{
			while (buffer.hasRemaining() && channel.read(buffer, from + buffer.position()) > 0)
			{
				// Keep reading until the tail is in
			}
		}
		catch (IOException e)
		{
			return "";
		}
		return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
	}

	/** How many ticks a recording spans, from the end of its file: a stopped one ends with its count; one still being
	 *  written, or cut off, by its last whole line's tick, counted from 0. -1 if there's none */
	static int ticksFromTail(String tail)
	{
		String line = lastWholeLine(tail);
		if (line == null)
		{
			return -1;
		}
		Matcher end = END_TICKS.matcher(line);
		if (end.find())
		{
			return Integer.parseInt(end.group(1));
		}
		Matcher tick = TICK.matcher(line);
		return tick.find() ? Integer.parseInt(tick.group(1)) + 1 : -1;
	}

	/** Whether a recording ended properly, with its end line, rather than being cut off */
	static boolean isFinished(String tail)
	{
		String line = lastWholeLine(tail);
		return line != null && END_TICKS.matcher(line).find();
	}

	private static String lastWholeLine(String tail)
	{
		String[] lines = tail.split("\n");
		for (int i = lines.length - 1; i >= 0; i--)
		{
			String line = lines[i].trim();
			// The first line of the tail may be cut off at the front, and the last at the back
			if (line.startsWith("{") && line.endsWith("}"))
			{
				return line;
			}
		}
		return null;
	}

	/** The name a recording is shown by: its label, without the time stamp in front or the ending (a repeated one's
	 *  number as "-1"), hyphens back to spaces */
	static String shownName(String fileName)
	{
		Matcher m = FILE.matcher(fileName);
		String label = m.matches() ? m.group(2) : EXTENSION.matcher(fileName).replaceFirst("");
		if (label.isEmpty())
		{
			return "session";
		}
		Matcher counted = COUNTED.matcher(label);
		return counted.matches() ? counted.group(1).replace('-', ' ') + "-" + counted.group(2) : label.replace('-', ' ');
	}

	/**
	 * A file name whose shown name no other recording in the folder has: a repeated name gets the next number, so TFA,
	 * then TFA-1, TFA-2... {@code existing} is the folder's file names; the file called {@code except} is left out.
	 */
	static String numbered(Collection<String> existing, String fileName, String except)
	{
		List<String> files = new ArrayList<>();
		List<String> taken = new ArrayList<>();
		for (String name : existing)
		{
			if (!name.equals(except))
			{
				files.add(name.toLowerCase());
				taken.add(shownName(name).toLowerCase());
			}
		}
		Matcher m = FILE.matcher(fileName);
		String stamp = m.matches() ? m.group(1) : STAMP.format(LocalDateTime.now());
		String label = m.matches() ? m.group(2) : safeLabel(EXTENSION.matcher(fileName).replaceFirst(""));
		String extension = extension(fileName);
		if (isFree(files, stamp + "-" + label + extension, label, taken))
		{
			return stamp + "-" + label + extension;
		}
		Matcher counted = COUNTED.matcher(label);
		String base = counted.matches() ? counted.group(1) : label;
		for (int n = 1; ; n++)
		{
			if (isFree(files, stamp + "-" + base + "_" + n + extension, base + "_" + n, taken))
			{
				return stamp + "-" + base + "_" + n + extension;
			}
		}
	}

	private static boolean isFree(List<String> files, String file, String label, List<String> taken)
	{
		return !taken.contains(shownName("00000000-000000-" + label + ".jsonl").toLowerCase()) && !files.contains(file.toLowerCase());
	}

	// ---- Outside's own: the size cap

	/**
	 * The oldest recordings deleted until the folder's recordings come to {@code most} bytes or less, never one named in
	 * {@code keep} (being written or packed). Returns the names deleted.
	 */
	static List<String> cap(Filepath dir, long most, Collection<String> keep) throws IOException
	{
		List<Filepath> all = list(dir);
		long total = 0;
		for (Filepath f : all)
		{
			total += f.size();
		}
		List<String> gone = new ArrayList<>();
		for (int i = all.size() - 1; i >= 0 && total > most; i--)
		{
			Filepath f = all.get(i);
			if (keep.contains(f.getFileName()) || !isPacked(f.getFileName()))
			{
				continue;
			}
			long size = f.size();
			f.deleteIfExists();
			total -= size;
			gone.add(f.getFileName());
		}
		return gone;
	}

	/** A small file written whole or not at all: beside itself first, then moved over the old one */
	static void writeAtomically(Filepath f, String text) throws IOException
	{
		Filepath part = f.getParent().joinSegment(f.getFileName() + PART);
		part.write(text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try
		{
			part.moveTo(f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException e)
		{
			part.moveTo(f, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
