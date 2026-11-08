package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.GameObject;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outside (11/07/2026): what you wear and carry, your looting bag, rune pouch and quiver, your skills, hitpoints,
 * prayer, run and special, sent to your outstation's channel on the relay (docs/protocol.md, "The game"), where your
 * Outside page draws them live beside your transmission and its chat, and keeps the last of them while you're away.
 * Nothing else is read or sent: no screen, no chat, no tile, no bank, nothing of other players. Each game tick, only what changed goes
 * (vitals alone at most every few seconds), with each new item's name and price once.
 * For outside.wiki's globe, each off to begin with (11/08/2026): following (Follow), and the world with it; what
 * they're doing (Activity, through Behind); the shooting stars they find (Stars). Each only ever the player's own, or
 * a world object; never another player's.
 */
@PluginDescriptor(
	name = "Outside",
	description = "Your gear, inventory, looting bag and stats, live on your Outside page",
	tags = {"outside", "outstation", "loadout", "inventory", "looting bag", "stream", "profile"}
)
public class OutsidePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(OutsidePlugin.class);
	static final String VERSION = "0.1.0";
	/** vitals alone (run energy draining as you run) go at most this often, in ticks */
	private static final int VITALS_EVERY = 5;
	/** the longest wait between tries to reach the relay, in seconds */
	private static final int RETRY_MOST = 60;

	@Inject
	private Client client;

	@Inject
	private OutsideConfig config;

	@Inject
	private OkHttpClient http;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chat;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	/** only on the client's thread (the ticks); a new connection asks for everything again through `resend` */
	private final Kit kit = new Kit();
	/** following, likewise only on the client's thread */
	private final Follow follow = new Follow();
	/** "and my world" as it was last tick (switched off: what was sent with the world goes at once) */
	private boolean followWorld;
	/** what they're doing (11/08/2026), and it two minutes behind; likewise only on the client's thread */
	private final Activity activity = new Activity();
	private final Behind doing = new Behind("activity");
	/** shooting stars (11/08/2026): what's to report, the stars in the scene (tile -> tier), and those that went this
	 *  tick while logged in (a hop or a new scene clears both first, so neither counts as gone); the client's thread */
	private final Stars stars = new Stars();
	private final Map<WorldPoint, Integer> starsHere = new HashMap<>();
	private final List<WorldPoint> starsWent = new ArrayList<>();
	/** worlds where nothing goes to the globe: PvP of any kind, Deadman, high risk, Last Man Standing */
	private static final EnumSet<WorldType> RISKY = EnumSet.of(WorldType.PVP, WorldType.DEADMAN, WorldType.HIGH_RISK,
		WorldType.BOUNTY, WorldType.PVP_ARENA, WorldType.LAST_MAN_STANDING);
	private volatile boolean resend;
	/** sending held while somewhere dangerous (the Wilderness, a PvP world), and said so once */
	private boolean paused;
	private volatile WebSocket socket;
	private volatile boolean open;
	/** stopped for good until the key changes (a wrong key, or another RuneLite took over) */
	private volatile boolean stopped;
	private Attach attach;
	private ScheduledFuture<?> retry;
	private int retryIn = 5;
	private int ticksSinceSent;
	private boolean toldConnected;

	@Provides
	OutsideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OutsideConfig.class);
	}

	@Override
	protected void startUp()
	{
		connect();
	}

	@Override
	protected void shutDown()
	{
		close("plugin off");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!OutsideConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		// (a new key, or sending switched: start again; a part switched off goes as an empty field next tick)
		if (e.getKey().equals("stationKey") || e.getKey().equals("send"))
		{
			close("settings changed");
			stopped = false;
			toldConnected = false;
			connect();
		}
		resend = true;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			// (logged out: the relay drops the area and the activity with the rest; next time, the two minutes start
			// again; the stars not reported yet are forgotten)
			follow.forget();
			follow.clear();
			doing.forget();
			doing.clear();
			activity.forget();
			stars.forget();
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING
			|| e.getGameState() == GameState.LOADING)
		{
			// (a new scene: its stars are seen again as it loads; one that went with the old one isn't gone)
			starsHere.clear();
			starsWent.clear();
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN && open)
		{
			JsonObject off = new JsonObject();
			off.addProperty("off", true);
			send(off);
			resend = true;
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			resend = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (open && resend)
		{
			resend = false;
			kit.forget();
			follow.again();
			doing.again();
		}
		// (the globe's: looked at every tick, the relay in reach or not, so what goes is always two minutes behind;
		// somewhere dangerous clears it at once, before anything else)
		boolean safe = globeSafe();
		long ms = System.currentTimeMillis();
		JsonObject where = follow(ms, safe);
		JsonObject what = activity(ms, safe);
		look(ms, safe);
		if (!open)
		{
			return;
		}
		if (where != null)
		{
			send(where);
		}
		if (what != null)
		{
			send(what);
		}
		JsonObject star = config.stars() ? stars.due(ms) : null;
		if (star != null)
		{
			send(star);
		}
		// (somewhere dangerous: nothing more goes till they're out, unless they've said otherwise, so nobody
		// watching their page sees what they risk)
		if (!config.wilderness() && dangerous())
		{
			if (!paused)
			{
				paused = true;
				send(Kit.held("wilderness"));
			}
			return;
		}
		if (paused)
		{
			paused = false;
			kit.forget();
		}
		Map<String, JsonElement> now = read();
		JsonObject changes = kit.changes(now);
		ticksSinceSent++;
		if (changes.size() == 0)
		{
			return;
		}
		// (vitals alone, as run energy drains: not every tick)
		boolean onlyVitals = changes.size() == 1 && changes.has("vitals");
		if (onlyVitals && ticksSinceSent < VITALS_EVERY)
		{
			return;
		}
		Set<Integer> fresh = kit.unnamed(now);
		if (!fresh.isEmpty())
		{
			JsonObject items = new JsonObject();
			for (int id : fresh)
			{
				String name = itemManager.getItemComposition(id).getName();
				long price = itemManager.getItemPrice(id);
				JsonArray np = new JsonArray();
				np.add(name);
				np.add(price);
				items.add(String.valueOf(id), np);
			}
			changes.add("items", items);
		}
		changes.addProperty("off", false);
		changes.addProperty("paused", false);
		if (send(changes))
		{
			kit.took(changes);
			ticksSinceSent = 0;
		}
	}

	/** The game as the relay's fields, from what's switched on. */
	private Map<String, JsonElement> read()
	{
		Map<String, JsonElement> m = new LinkedHashMap<>();
		Player me = client.getLocalPlayer();
		if (me != null && me.getName() != null)
		{
			m.put("name", new JsonPrimitive(me.getName()));
		}
		m.put("world", new JsonPrimitive(client.getWorld()));
		m.put("type", new JsonPrimitive(accountType(client.getVarbitValue(VarbitID.IRONMAN))));
		if (config.items())
		{
			m.put("worn", slots(client.getItemContainer(InventoryID.WORN), 14));
			m.put("inv", slots(client.getItemContainer(InventoryID.INV), 28));
			// (the looting bag is known once looked in, and kept as it was till then)
			ItemContainer bag = client.getItemContainer(InventoryID.LOOTING_BAG);
			if (bag != null)
			{
				m.put("bag", items(bag));
			}
			m.put("pouch", runePouch());
			int ammo = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
			int amount = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
			m.put("quiver", ammo > 0 && amount > 0 ? Kit.pair(ammo, amount) : com.google.gson.JsonNull.INSTANCE);
		}
		if (config.skills())
		{
			JsonObject skills = new JsonObject();
			for (Skill s : Skill.values())
			{
				// (the total isn't a skill: Skill.OVERALL, deprecated, is skipped by its name)
				if ("Overall".equals(s.getName()))
				{
					continue;
				}
				JsonArray v = new JsonArray();
				v.add(client.getRealSkillLevel(s));
				v.add(client.getBoostedSkillLevel(s));
				v.add(client.getSkillExperience(s));
				skills.add(s.getName(), v);
			}
			m.put("skills", skills);
		}
		if (config.vitals())
		{
			JsonObject vitals = new JsonObject();
			vitals.add("hp", Kit.pair(client.getBoostedSkillLevel(Skill.HITPOINTS), client.getRealSkillLevel(Skill.HITPOINTS)));
			vitals.add("prayer", Kit.pair(client.getBoostedSkillLevel(Skill.PRAYER), client.getRealSkillLevel(Skill.PRAYER)));
			vitals.addProperty("run", client.getEnergy() / 100);
			vitals.addProperty("spec", client.getVarpValue(VarPlayerID.SA_ENERGY) / 10);
			m.put("vitals", vitals);
			JsonArray on = new JsonArray();
			for (Prayer p : Prayer.values())
			{
				if (client.getVarbitValue(p.getVarbit()) == 1)
				{
					on.add(p.name());
				}
			}
			m.put("prayers", on);
		}
		return m;
	}

	private static JsonElement slots(ItemContainer c, int size)
	{
		if (c == null)
		{
			return Kit.slots(new int[0], new int[0], size);
		}
		Item[] items = c.getItems();
		int[] ids = new int[items.length];
		int[] qs = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qs[i] = items[i].getQuantity();
		}
		return Kit.slots(ids, qs, size);
	}

	private static JsonElement items(ItemContainer c)
	{
		Item[] items = c.getItems();
		int[] ids = new int[items.length];
		int[] qs = new int[items.length];
		for (int i = 0; i < items.length; i++)
		{
			ids[i] = items[i].getId();
			qs[i] = items[i].getQuantity();
		}
		return Kit.items(ids, qs);
	}

	/** The rune pouch's runes (its six slots, as the game keeps them; the page shows it with a pouch carried). */
	private JsonElement runePouch()
	{
		int[] types = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
			VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
		int[] amounts = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
			VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6};
		EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		int[] ids = new int[6];
		int[] qs = new int[6];
		for (int i = 0; i < 6; i++)
		{
			int type = client.getVarbitValue(types[i]);
			ids[i] = type > 0 && runes != null ? runes.getIntValue(type) : -1;
			qs[i] = client.getVarbitValue(amounts[i]);
		}
		return Kit.items(ids, qs);
	}

	/** Following's tick (Follow): what to send, or null. Off, what was sent goes once and nothing's kept. */
	private JsonObject follow(long now, boolean safe)
	{
		// ("and my world" switched off: what went with the world goes at once, and the two minutes start again)
		boolean world = config.followWorld();
		boolean wasWorld = followWorld;
		followWorld = world;
		if (!config.follow() || (wasWorld && !world))
		{
			JsonObject gone = follow.clear();
			follow.forget();
			return gone;
		}
		Player me = client.getLocalPlayer();
		WorldPoint at = me == null ? null : me.getWorldLocation();
		if (at == null)
		{
			return null;
		}
		return follow.tick(now, at.getX(), at.getY(), world ? client.getWorld() : 0, safe);
	}

	/** The activity's tick (Activity, two minutes behind through Behind): what to send, or null. Off, what was sent
	 *  goes once and nothing's kept. */
	private JsonObject activity(long now, boolean safe)
	{
		if (!config.activity())
		{
			JsonObject gone = doing.clear();
			doing.forget();
			activity.forget();
			return gone;
		}
		return doing.tick(now, activity.now(now), safe);
	}

	/** The stars in the scene this tick, and those that went while they watched, into Stars. Off, nothing's kept. */
	private void look(long now, boolean safe)
	{
		if (!config.stars())
		{
			stars.forget();
			starsWent.clear();
			return;
		}
		int world = client.getWorld();
		for (WorldPoint p : starsWent)
		{
			if (!starsHere.containsKey(p))
			{
				stars.gone(now, world, p.getX(), p.getY());
			}
		}
		starsWent.clear();
		for (Map.Entry<WorldPoint, Integer> e : starsHere.entrySet())
		{
			WorldPoint p = e.getKey();
			stars.see(now, world, p.getX(), p.getY(), e.getValue(), safe && !inWilderness(p.getX(), p.getY()));
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		GameObject o = e.getGameObject();
		int tier = Stars.tier(o.getId());
		if (tier > 0)
		{
			starsHere.put(o.getWorldLocation(), tier);
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned e)
	{
		GameObject o = e.getGameObject();
		if (Stars.tier(o.getId()) == 0)
		{
			return;
		}
		WorldPoint p = o.getWorldLocation();
		starsHere.remove(p);
		// (gone while logged in, near enough to have seen it go: mined out, or a tier mined down, which spawns the
		// next tier on the same tile this tick and so isn't gone)
		Player me = client.getLocalPlayer();
		if (client.getGameState() == GameState.LOGGED_IN && me != null && me.getWorldLocation().distanceTo(p) <= 32)
		{
			starsWent.add(p);
		}
	}

	/** Their own experience (the activity's skilling). */
	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (config.activity())
		{
			activity.xp(System.currentTimeMillis(), e.getSkill().getName(), e.getXp());
		}
	}

	/** A hit in their own fight: theirs on an NPC, or one on them (the activity's fighting, and which boss). */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (!config.activity())
		{
			return;
		}
		Player me = client.getLocalPlayer();
		Actor who = e.getActor();
		if (me == null || who == null)
		{
			return;
		}
		if (who == me)
		{
			activity.hit(System.currentTimeMillis(), foe(me));
		}
		else if (who instanceof NPC && e.getHitsplat().isMine())
		{
			activity.hit(System.currentTimeMillis(), who.getName());
		}
	}

	/** The NPC in a fight with them: the one they're attacking, or one attacking them (a boss first); null if none. */
	private String foe(Player me)
	{
		Actor target = me.getInteracting();
		if (target instanceof NPC)
		{
			return target.getName();
		}
		String any = null;
		for (NPC n : client.getTopLevelWorldView().npcs())
		{
			if (n.getInteracting() == me && n.getName() != null)
			{
				if (Activity.BOSSES.containsKey(n.getName()))
				{
					return n.getName();
				}
				any = n.getName();
			}
		}
		return any;
	}

	/** Safe for the globe's features: not somewhere dangerous, not in an instance, not on a risky world. */
	private boolean globeSafe()
	{
		if (dangerous() || client.getTopLevelWorldView().isInstance())
		{
			return false;
		}
		for (WorldType t : client.getWorldType())
		{
			if (RISKY.contains(t))
			{
				return false;
			}
		}
		return true;
	}

	/** In the Wilderness, a PvP area, or on a PvP or Deadman world (read here, never sent). */
	private boolean dangerous()
	{
		if (WorldType.isPvpWorld(client.getWorldType()) || client.getWorldType().contains(WorldType.DEADMAN))
		{
			return true;
		}
		if (client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1)
		{
			return true;
		}
		Player me = client.getLocalPlayer();
		WorldPoint at = me == null ? null : me.getWorldLocation();
		return at != null && inWilderness(at.getX(), at.getY());
	}

	/** The Wilderness's bounds, above ground and below (its levels count from y 3520 up, and 9920 under). */
	static boolean inWilderness(int x, int y)
	{
		return x >= 2944 && x < 3392 && ((y >= 3520 && y < 3968) || (y >= 9920 && y < 10368));
	}

	static String accountType(int v)
	{
		switch (v)
		{
			case 1: return "IRONMAN";
			case 2: return "ULTIMATE_IRONMAN";
			case 3: return "HARDCORE_IRONMAN";
			case 4: return "GROUP_IRONMAN";
			case 5: return "HARDCORE_GROUP_IRONMAN";
			case 6: return "UNRANKED_GROUP_IRONMAN";
			default: return "NORMAL";
		}
	}

	// ---- the relay ----

	private boolean send(JsonObject fields)
	{
		WebSocket ws = socket;
		if (ws == null || !open)
		{
			return false;
		}
		JsonObject msg = new JsonObject();
		msg.add("game", fields);
		return ws.send(gson.toJson(msg));
	}

	private synchronized void connect()
	{
		if (!config.send() || stopped || socket != null)
		{
			return;
		}
		attach = Attach.parse(config.stationKey(), gson);
		if (attach == null)
		{
			return;
		}
		Request req = new Request.Builder().url(attach.gameUrl(VERSION)).header("User-Agent", "outside-plugin/" + VERSION).build();
		socket = http.newWebSocket(req, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket ws, Response response)
			{
				open = true;
				retryIn = 5;
				resend = true;
				if (!toldConnected)
				{
					toldConnected = true;
					say("your outstation (" + attach.getChannel() + ") is receiving what you wear and carry: " + attach.page());
				}
			}

			@Override
			public void onClosing(WebSocket ws, int code, String reason)
			{
				ws.close(1000, null);
			}

			@Override
			public void onClosed(WebSocket ws, int code, String reason)
			{
				ended(ws, code);
			}

			@Override
			public void onFailure(WebSocket ws, Throwable t, Response response)
			{
				log.debug("outside: the relay's out of reach", t);
				ended(ws, 1006);
			}
		});
	}

	private synchronized void ended(WebSocket ws, int code)
	{
		if (ws != socket)
		{
			return;
		}
		socket = null;
		open = false;
		if (code == 4001 || code == 4003)
		{
			stopped = true;
			say("the relay didn't take your station key: paste it again in the plugin's settings");
			return;
		}
		if (code == 4000)
		{
			stopped = true;
			say("another RuneLite is sending to this outstation now, so this one stopped");
			return;
		}
		if (code == 1000 && !config.send())
		{
			return;
		}
		// (out of reach: try again, waiting longer each time)
		if (retry == null || retry.isDone())
		{
			retry = executor.schedule(this::connect, retryIn, TimeUnit.SECONDS);
			retryIn = Math.min(RETRY_MOST, retryIn * 2);
		}
	}

	private synchronized void close(String why)
	{
		if (retry != null)
		{
			retry.cancel(false);
			retry = null;
		}
		WebSocket ws = socket;
		socket = null;
		open = false;
		if (ws != null)
		{
			ws.close(1000, why);
		}
	}

	private void say(String words)
	{
		clientThread.invokeLater(() -> chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("Outside: " + words).build()));
	}
}
