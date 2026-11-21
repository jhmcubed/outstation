package wiki.outside;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The encounters' files (11/21/2026): packing and its length in the gzip header, a recording cut off mid-line, the
 *  names, as Encounter Recorder's own CompressedTest checks his (ported with his code); and Outside's: the size cap, the
 *  stripped copy gzipped for the page, a crash's leftover packed at the next start. */
public class EncounterFilesTest
{
	private static final String LINES = "{\"t\":0,\"c\":1,\"e\":\"header\"}\n{\"t\":0,\"c\":2,\"e\":\"tick\"}\n{\"t\":1,\"c\":32,\"e\":\"tick\"}\n";

	private static byte[] pack(String text, String endLine, int ticks) throws IOException
	{
		Path file = Files.createTempFile("recording", ".jsonl");
		try
		{
			Files.write(file, text.getBytes(StandardCharsets.UTF_8));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (FileChannel in = FileChannel.open(file, StandardOpenOption.READ))
			{
				EncounterFiles.gzip(in, EncounterFiles.wholeLines(in), endLine, ticks, out);
			}
			return out.toByteArray();
		}
		finally
		{
			Files.delete(file);
		}
	}

	private static String unpack(byte[] gz) throws IOException
	{
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz)))
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void packedRecordingsUnpackToTheSameLinesAndCarryTheirLength() throws IOException
	{
		byte[] gz = pack(LINES, null, 2);
		assertEquals(LINES, unpack(gz));
		assertEquals(2, EncounterFiles.ticksFromHeader(gz, gz.length));
		assertTrue(EncounterFiles.isGzip(gz, gz.length));
		byte[] page = EncounterFiles.gzipText(LINES, 2);
		assertEquals(LINES, unpack(page));
		assertEquals(2, EncounterFiles.ticksFromHeader(page, page.length));
	}

	@Test
	public void aRecordingCutOffMidLineLosesOnlyThatLineAndGetsItsEnd() throws IOException
	{
		String end = "{\"t\":2,\"e\":\"end\",\"reason\":\"not stopped\",\"ticks\":2}";
		byte[] gz = pack(LINES + "{\"t\":2,\"c\":62,\"e\":\"cl", end, 2);
		assertEquals(LINES + end + "\n", unpack(gz));
		assertTrue(EncounterFiles.isFinished(unpack(gz)));
		assertEquals(2, EncounterFiles.ticksFromTail(unpack(gz)));
	}

	@Test
	public void finishedAndUnfinishedRecordingsAreToldApart()
	{
		assertTrue(EncounterFiles.isFinished("{\"t\":5,\"c\":1,\"e\":\"tick\"}\n{\"t\":6,\"c\":2,\"e\":\"end\",\"reason\":\"stopped by hand\",\"ticks\":6}\n"));
		assertFalse(EncounterFiles.isFinished("{\"t\":5,\"c\":1,\"e\":\"tick\"}\n{\"t\":6,\"c\":2,\"e\":\"cl"));
		assertFalse(EncounterFiles.isFinished(""));
		assertEquals(6, EncounterFiles.ticksFromTail("{\"t\":5,\"c\":1,\"e\":\"tick\"}\n{\"t\":5,\"c\":2,\"e\":\"cl"));
	}

	@Test
	public void aFirstLineStopsAtItsNewline() throws IOException
	{
		assertEquals("{\"t\":0,\"c\":1,\"e\":\"header\"}", EncounterFiles.firstLine(new ByteArrayInputStream(LINES.getBytes(StandardCharsets.UTF_8))));
		assertTrue(EncounterFiles.isHeader("{\"t\":0,\"c\":1,\"e\":\"header\",\"format\":6}"));
		assertFalse(EncounterFiles.isHeader("{\"t\":0,\"c\":1,\"e\":\"tick\"}"));
	}

	@Test
	public void namesAreHisAndRepeatedOnesNumbered()
	{
		assertEquals("Vorkath", EncounterFiles.safeLabel("Vorkath"));
		assertEquals("Kril-Tsutsaroth", EncounterFiles.safeLabel("K'ril Tsutsaroth"));
		assertEquals("session", EncounterFiles.safeLabel("  "));
		assertEquals("TFA 5man", EncounterFiles.shownName("20261027-025914-TFA-5man.jsonl.gz"));
		assertEquals("TFA-1", EncounterFiles.shownName("20261027-025914-TFA_1.jsonl.gz"));
		List<String> folder = new ArrayList<>(Arrays.asList("20261027-100000-TFA.jsonl.gz"));
		assertEquals("20261027-110000-TFA_1.jsonl", EncounterFiles.numbered(folder, "20261027-110000-TFA.jsonl", null));
		folder.add("20261027-110000-TFA_1.jsonl");
		assertEquals("20261027-120000-TFA_2.jsonl.gz", EncounterFiles.numbered(folder, "20261027-120000-TFA.jsonl.gz", null));
		// Packing a recording keeps its number: the plain file it replaces is left out
		assertEquals("20261027-110000-TFA_1.jsonl.gz", EncounterFiles.numbered(folder, "20261027-110000-TFA_1.jsonl.gz", "20261027-110000-TFA_1.jsonl"));
		assertEquals(2026, EncounterFiles.stamp("20261027-025914-TFA.jsonl.gz").getYear());
		assertTrue(EncounterFiles.isRecordingName("20261027-025914-TFA.jsonl.gz"));
		assertFalse(EncounterFiles.isRecordingName("20261027-025914-TFA.jsonl.gz.part"));
		assertFalse(EncounterFiles.isRecordingName(EncounterFiles.INDEX));
	}

	private static Filepath folder() throws IOException
	{
		return Filepath.Unchecked.getRooted(Files.createTempDirectory("encounters"));
	}

	@Test
	public void theCapDeletesTheOldestFirst() throws IOException
	{
		Filepath dir = folder();
		Path d = Filepath.Unchecked.getPath(dir);
		for (String n : new String[]{"20261101-100000-A.jsonl.gz", "20261102-100000-B.jsonl.gz", "20261103-100000-C.jsonl.gz",
			"20261104-100000-D.jsonl"})
		{
			Files.write(d.resolve(n), new byte[1000]);
		}
		Files.write(d.resolve(EncounterFiles.INDEX), new byte[5000]);
		// (C being packed: never deleted; D still being written, plain: never deleted)
		List<String> gone = EncounterFiles.cap(dir, 2500, Collections.singleton("20261103-100000-C.jsonl.gz"));
		assertEquals(Arrays.asList("20261101-100000-A.jsonl.gz", "20261102-100000-B.jsonl.gz"), gone);
		assertTrue(Files.exists(d.resolve("20261103-100000-C.jsonl.gz")));
		assertTrue(Files.exists(d.resolve(EncounterFiles.INDEX)));
		assertEquals(Collections.emptyList(), EncounterFiles.cap(dir, 2500, Collections.emptySet()));
	}

	@Test
	public void aCrashsLeftoverIsPackedWithItsEnd() throws IOException
	{
		Filepath dir = folder();
		Path d = Filepath.Unchecked.getPath(dir);
		Path plain = d.resolve("20261121-030000-Vorkath.jsonl");
		Files.write(plain, (LINES + "{\"t\":2,\"c\":62,\"e\":\"cl").getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(plain, FileTime.fromMillis(System.currentTimeMillis() - EncounterFiles.IDLE_MS - 1000));
		assertEquals(Collections.singletonList("20261121-030000-Vorkath.jsonl.gz"), EncounterFiles.finishLeftovers(dir, System.currentTimeMillis()));
		assertFalse(Files.exists(plain));
		String text = EncounterFiles.readText(dir.joinSegment("20261121-030000-Vorkath.jsonl.gz"));
		assertEquals(LINES + "{\"t\":2,\"e\":\"end\",\"reason\":\"not stopped: the client closed or crashed\",\"ticks\":2}\n", text);
	}
}
