package wiki.outside;

/**
 * The page chat's emotes from the side tab (11/16/2026; the plugin first: until now only the outstation's settings
 * uploaded them): a picture and a name, sent to the relay as the outstation sends them (docs/protocol.md, "Emotes":
 * POST /emotes/<channel>?key=…&name=…, the picture as the body; DELETE /emotes/<channel>/<name>?key=…). What the relay
 * would refuse is told here first. Kept apart from RuneLite so it's tested.
 */
final class Emotes
{
	/** the relay's limits: each picture, and how many a page keeps */
	static final int MOST_BYTES = 256 * 1024, MOST = 30;

	private Emotes()
	{
	}

	/** A name the relay takes: 2 to 24 letters, numbers or _ (typed as :name: in the chat). */
	static boolean nameOk(String name)
	{
		return name != null && name.matches("[A-Za-z0-9_]{2,24}");
	}

	/** The picture's kind by its own first bytes, as the relay reads it: "png", "jpg", "gif", or null for anything else. */
	static String kind(byte[] b)
	{
		if (b == null || b.length < 4)
		{
			return null;
		}
		if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
		{
			return "png";
		}
		if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff)
		{
			return "jpg";
		}
		if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8')
		{
			return "gif";
		}
		return null;
	}

	/** The picture on the clipboard as a PNG (made in memory; a GIF's motion isn't kept), or null when there's none. */
	static byte[] fromClipboard()
	{
		try
		{
			java.awt.datatransfer.Transferable t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
			if (t == null || !t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor))
			{
				return null;
			}
			java.awt.Image img = (java.awt.Image) t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
			return png(img);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** An image as PNG bytes, scaled down to 128 px on its longer side if it's bigger (an emote shows small). */
	static byte[] png(java.awt.Image img) throws java.io.IOException
	{
		int w = img.getWidth(null), h = img.getHeight(null);
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		double k = Math.min(1.0, 128.0 / Math.max(w, h));
		int sw = Math.max(1, (int) Math.round(w * k)), sh = Math.max(1, (int) Math.round(h * k));
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(img, 0, 0, sw, sh, null);
		g.dispose();
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(out, "png", bytes);
		return bytes.toByteArray();
	}

	/** Why a picture can't go, in the side tab's words, or null when it can. */
	static String refuse(String name, byte[] picture)
	{
		if (!nameOk(name))
		{
			return "a name first: 2 to 24 letters, numbers or _";
		}
		if (picture == null || kind(picture) == null)
		{
			return "a PNG, JPEG or GIF picture";
		}
		if (picture.length > MOST_BYTES)
		{
			return "that picture's over 256 KB";
		}
		return null;
	}
}
