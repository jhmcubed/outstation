package wiki.outside;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The page chat's emotes from the side tab (11/16/2026): what the relay would take, said first. */
public class EmotesTest
{
	@Test
	public void namesAndPictures()
	{
		assertTrue(Emotes.nameOk("gz_mole"));
		assertEquals(false, Emotes.nameOk("x"));
		assertEquals(false, Emotes.nameOk("no spaces"));
		assertEquals("png", Emotes.kind(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13}));
		assertEquals("jpg", Emotes.kind(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}));
		assertEquals("gif", Emotes.kind("GIF89a".getBytes()));
		assertNull(Emotes.kind("<svg".getBytes()));
		assertNull(Emotes.refuse("gz", new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
		assertEquals("a PNG, JPEG or GIF picture", Emotes.refuse("gz", "BM..".getBytes()));
		assertTrue(Emotes.refuse("g", new byte[]{(byte) 0x89, 'P', 'N', 'G'}).startsWith("a name"));
		byte[] big = new byte[Emotes.MOST_BYTES + 1];
		big[0] = (byte) 0x89; big[1] = 'P'; big[2] = 'N'; big[3] = 'G';
		assertEquals("that picture's over 256 KB", Emotes.refuse("gz", big));
	}

	@Test
	public void urls()
	{
		Attach a = Attach.parse("outstation1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
			"{\"relay\":\"https://relay.ghillie.workers.dev\",\"channel\":\"sburb\",\"key\":\"k-123456\"}".getBytes()), new com.google.gson.Gson());
		assertEquals("https://relay.ghillie.workers.dev/emotes/sburb?code=", a.emotesUrl(null));
		assertEquals("https://relay.ghillie.workers.dev/emotes/sburb?key=k-123456&name=gz", a.emotesUrl("gz"));
		assertEquals("https://relay.ghillie.workers.dev/emotes/sburb/gz?key=k-123456", a.emoteUrl("gz"));
	}

	@Test
	public void aPictureAsPng() throws Exception
	{
		java.awt.image.BufferedImage big = new java.awt.image.BufferedImage(512, 256, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		byte[] b = Emotes.png(big);
		assertEquals("png", Emotes.kind(b));
		java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
		assertEquals(128, back.getWidth());
		assertEquals(64, back.getHeight());
	}
}
