package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A station key ("outstation1:" and base64url JSON {relay, channel, key}, as the outstation and a friend's invite
 * give it; "portal1:" from before the name was outstation): where the plugin sends the game, and with what key.
 */
public final class Attach
{
	/** The Outside relay's host: the only one the Plugin Hub's version talks to (11/16/2026, the reviewer on
	 *  runelite/plugin-hub#20811: "Hub plugins aren't allowed to take a URL from a server response then connect to it…
	 *  make sure the hostname portion of relay is relay.ghillie.workers.dev"). */
	public static final String HOME_HOST = "relay.ghillie.workers.dev";
	/** Its address, the base of every request the Hub's version makes. */
	public static final String HOME = "https://" + HOME_HOST;

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

	/** The key read (with RuneLite's own Gson), or null when it isn't one. */
	public static Attach parse(String code, Gson gson)
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
			JsonObject o = gson.fromJson(json, JsonObject.class);
			String relay = o.get("relay").getAsString();
			String channel = o.get("channel").getAsString();
			String key = o.get("key").getAsString();
			if (relay.isEmpty() || channel.isEmpty() || key.length() < 8)
			{
				return null;
			}
			// (the Hub's version: a key for any other relay is no key, whether pasted or from /claim's answer, and
			// every request is built on HOME, never on what the key says; the whole plugin, in developer mode, also
			// takes an outstation's own relay or a local one)
			// hub:only if (!isHome(relay))
			// hub:only {
			// hub:only 	return null;
			// hub:only }
			// hub:only return new Attach(HOME, channel, key);
			// hub:cut live
			return new Attach(relay, channel, key);
			// hub:end live
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/** Whether a key's relay is the Outside relay itself: relay.ghillie.workers.dev, as https://, wss:// or bare, with no
	 *  port, path or anything else. */
	public static boolean isHome(String relay)
	{
		if (relay == null)
		{
			return false;
		}
		String r = relay.trim().replaceAll("/+$", "");
		if (r.startsWith("https://"))
		{
			r = r.substring(8);
		}
		else if (r.startsWith("wss://"))
		{
			r = r.substring(6);
		}
		return r.equalsIgnoreCase(HOME_HOST);
	}

	// hub:cut live
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
	// hub:end live

	/** The snapshot's address (11/16/2026; docs/protocol.md, "The snapshot"): POST http(s)://…/game/<channel>?key=…&v=…. */
	public String postUrl(String version)
	{
		// (&proof=1, 11/17/2026: the answer says what the page's proof did, while it's still to be made)
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/game/" + enc(channel) + "?") + "&v=" + enc(version) + "&proof=1";
	}

	// hub:cut talk
	/** The relay's desk socket for the page's chat in the game (11/16/2026): ws(s)://…/desk/<channel>?key=…&name=…&talk=1. */
	public String deskUrl(String name)
	{
		String game = gameUrl("0");
		String base = game.substring(0, game.indexOf("/game/"));
		return base + "/desk/" + enc(channel) + "?key=" + enc(key) + "&name=" + enc(name) + "&talk=1";
	}
	// hub:end talk

	// hub:cut talk
	/** The page chat's emotes (11/16/2026): the list (no name: ?code=, as anyone reads it), an upload (a name: ?key=…&name=…). */
	public String emotesUrl(String name)
	{
		String base = keepUrl("x");
		base = base.substring(0, base.indexOf("/keep/"));
		return base + "/emotes/" + enc(channel) + (name == null ? "?code=" : "?key=" + enc(key) + "&name=" + enc(name));
	}
	// hub:end talk

	// hub:cut talk
	/** One emote, to take it away: DELETE http(s)://…/emotes/<channel>/<name>?key=…. */
	public String emoteUrl(String name)
	{
		String base = keepUrl("x");
		base = base.substring(0, base.indexOf("/keep/"));
		return base + "/emotes/" + enc(channel) + "/" + enc(name) + "?key=" + enc(key);
	}
	// hub:end talk

	/** The relay's keep for the page (11/14/2026): http(s)://…/keep/<channel>/<what>?key=… (a bare relay as https://). */
	public String keepUrl(String what)
	{
		String base = relay.trim().replaceAll("/+$", "");
		if (base.startsWith("wss://"))
		{
			base = "https://" + base.substring(6);
		}
		else if (base.startsWith("ws://"))
		{
			base = "http://" + base.substring(5);
		}
		else if (!base.contains("://"))
		{
			base = "https://" + base;
		}
		return base + "/keep/" + enc(channel) + "/" + enc(what) + "?key=" + enc(key);
	}

	/** The page's address for the Dink plugin's webhooks (11/16/2026, "Copy my address for Dink"): http(s)://…/dink/<channel>?key=…,
	 *  on the relay every request goes to. Only copied for the player to paste into Dink; never fetched here. */
	public String dinkAddress()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/dink/" + enc(channel) + "?");
	}

	// hub:cut reports
	/** The relay's Dink route, for the plugin's own reports (11/15/2026): http(s)://…/dink/<channel>?key=…&from=plugin. */
	public String dinkUrl()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/dink/" + enc(channel) + "?") + "&from=plugin";
	}
	// hub:end reports

	// hub:cut year
	/** Your year's routes (11/15/2026; docs/record.md, "Sync"): http(s)://…/record/<channel>/<tail>?key=…. */
	public String recordUrl(String tail)
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/record/" + enc(channel) + "/" + enc(tail) + "?");
	}
	// hub:end year

	/** Who stands behind the page (11/15/2026): http(s)://…/proof/<channel>, anyone's. */
	public String proofUrl()
	{
		return keepUrl("x").replaceFirst("/keep/.*$", "/proof/" + enc(channel));
	}

	/** Friends only, set from the plugin (11/16/2026, the permissions review): http(s)://…/code/<channel>?key=…. */
	public String codeUrl()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/code/" + enc(channel) + "?");
	}

	/** The page taken down by its own key (11/16/2026): http(s)://…/channels/<channel>?key=…. */
	public String takeDownUrl()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/channels/" + enc(channel) + "?");
	}

	/** A token to tend the page's chat from the page, handed in by its own key (11/16/2026): http(s)://…/tend/<channel>?key=…. */
	public String tendUrl()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/tend/" + enc(channel) + "?");
	}

	/** The page, opened to tend its chat: the token in the fragment, which no server sees (11/16/2026). */
	public String tendPage(String token)
	{
		return page() + "#tend=" + token;
	}

	/** The page's kept game, as anyone reads it (403 when it's friends only and no code is given). */
	public String gameReadUrl()
	{
		return keepUrl("x").replaceFirst("/keep/.*$", "/game/" + enc(channel));
	}

	/** The page with its key in the fragment, for the aether under the page's own name (11/18/2026): no server sees a
	 *  fragment, and the page takes it out of the address at once. */
	public String aetherPage(String code)
	{
		return page(code) + "#aether=" + enc(key);
	}

	/** The page with a watch code, for friends (11/16/2026). */
	public String page(String code)
	{
		return code == null || code.isEmpty() ? page() : page() + "?code=" + enc(code);
	}

	// hub:cut encounters
	/** The page's recorded encounters (11/21/2026; docs/protocol.md, "Recorded fights"): http(s)://…/fights/<channel>?key=…,
	 *  the list as its owner reads it (GET) and one put up (POST, multipart). */
	public String fightsUrl()
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/fights/" + enc(channel) + "?");
	}

	/** One of them shown to anyone or kept to themselves (POST): http(s)://…/fights/<channel>/<id>?key=…&public=1|0. */
	public String fightUrl(String id, boolean shown)
	{
		return keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/fights/" + enc(channel) + "/" + enc(id) + "?") + "&public=" + (shown ? "1" : "0");
	}

	/** One of them changed (POST, 11/21/2026): http(s)://…/fights/<channel>/<id>?key=…&<what> (kept=1|0, featured=1|0);
	 *  with no what, the fight itself (DELETE: taken down). */
	public String fightUrl(String id, String what)
	{
		String u = keepUrl("x").replaceFirst("/keep/[^?]*\\?", "/fights/" + enc(channel) + "/" + enc(id) + "?");
		return what == null || what.isEmpty() ? u : u + "&" + what;
	}

	/** Its page on Outside: outside.wiki/<name>/fight/<id>, with a friends-only page's watch code. */
	public String fightPage(String id, String code)
	{
		String p = page() + "/fight/" + enc(id);
		return code == null || code.isEmpty() ? p : p + "?code=" + enc(code);
	}
	// hub:end encounters

	/** The outstation's page on Outside. */
	public String page()
	{
		// (11/16/2026: outside.wiki/<name>, as Open my page opens it; it was ghillie.wiki/outside?player=)
		return "https://outside.wiki/" + enc(channel);
	}

	private static String enc(String s)
	{
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
