package wiki.outside;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A station key ("outstation1:" and base64url JSON {relay, channel, key}, as the outstation and a friend's invite
 * give it; "portal1:" from before the name was outstation): where the plugin sends the game, and with what key.
 */
public final class Attach
{
	private final String relay;
	private final String channel;
	private final String key;

	public Attach(String relay, String channel, String key)
	{
		this.relay = relay;
		this.channel = channel;
		this.key = key;
	}

	public String getRelay()
	{
		return relay;
	}

	public String getChannel()
	{
		return channel;
	}

	public String getKey()
	{
		return key;
	}

	/** The key read, or null when it isn't one. */
	public static Attach parse(String code)
	{
		if (code == null)
		{
			return null;
		}
		String c = code.trim();
		int colon = c.indexOf(':');
		if (colon < 0)
		{
			return null;
		}
		String prefix = c.substring(0, colon);
		if (!prefix.equals("outstation1") && !prefix.equals("portal1"))
		{
			return null;
		}
		try
		{
			String json = new String(Base64.getUrlDecoder().decode(c.substring(colon + 1)), StandardCharsets.UTF_8);
			JsonObject o = new JsonParser().parse(json).getAsJsonObject();
			String relay = o.get("relay").getAsString();
			String channel = o.get("channel").getAsString();
			String key = o.get("key").getAsString();
			if (relay.isEmpty() || channel.isEmpty() || key.length() < 8)
			{
				return null;
			}
			return new Attach(relay, channel, key);
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** The relay's game socket: ws(s)://…/game/<channel>?key=…&v=… (an https:// relay as wss://). */
	public String gameUrl(String version)
	{
		String base = relay.trim().replaceAll("/+$", "");
		if (base.startsWith("https://"))
		{
			base = "wss://" + base.substring(8);
		}
		else if (base.startsWith("http://"))
		{
			base = "ws://" + base.substring(7);
		}
		else if (!base.contains("://"))
		{
			base = "wss://" + base;
		}
		return base + "/game/" + enc(channel) + "?key=" + enc(key) + "&v=" + enc(version);
	}

	/** The outstation's page on Outside. */
	public String page()
	{
		return "https://ghillie.wiki/outside?player=" + enc(channel);
	}

	private static String enc(String s)
	{
		try
		{
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		}
		catch (java.io.UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
		}
	}
}
