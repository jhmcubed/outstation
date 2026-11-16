package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Tending the page's chat from the page (11/16/2026; docs/protocol.md, "Tending the page's chat from the page"): with the
 * plugin alone (the Plugin Hub's has no desk), "Tend my page's chat" makes a token, hands it to the relay with the page's
 * key (POST /tend/<channel>?key= {"token"}), and opens the page with it in the address's fragment, which no server sees.
 * There each visitor's line gets "take back" and "ban"; the token is good for 12 hours and can do nothing else. Kept apart
 * from RuneLite so it's tested.
 */
final class Tend
{
	private Tend()
	{
	}

	/** 32 random bytes as 43 base64url characters, no padding: what the relays take. */
	static String token(SecureRandom random)
	{
		byte[] b = new byte[32];
		random.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	/** The request's body: {"token": …}. */
	static String body(String token, Gson gson)
	{
		JsonObject o = new JsonObject();
		o.addProperty("token", token);
		return gson.toJson(o);
	}
}
