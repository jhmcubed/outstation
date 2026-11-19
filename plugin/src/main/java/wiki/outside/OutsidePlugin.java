package wiki.outside;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonNull;
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
// hub:cut globe,year,model
import net.runelite.api.Actor;
// hub:end globe,year,model
// hub:cut model
import net.runelite.api.ActorSpotAnim;
// hub:end model
import net.runelite.api.Perspective;
// hub:cut model
import net.runelite.api.Scene;
// hub:end model
// hub:cut model
import net.runelite.api.SceneTileModel;
// hub:end model
// hub:cut model
import net.runelite.api.SceneTilePaint;
// hub:end model
// hub:cut model
import net.runelite.api.Tile;
// hub:end model
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Item;
// hub:cut globe
import net.runelite.api.GameObject;
// hub:end globe
import net.runelite.api.ItemContainer;
// hub:cut globe,year,model
import net.runelite.api.NPC;
// hub:end globe,year,model
import net.runelite.api.Player;
// hub:cut live
import net.runelite.api.Prayer;
// hub:end live
import net.runelite.api.Skill;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
// hub:cut globe
import net.runelite.api.events.GameObjectDespawned;
// hub:end globe
// hub:cut globe
import net.runelite.api.events.GameObjectSpawned;
// hub:end globe
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
// hub:cut globe,year
import net.runelite.api.events.HitsplatApplied;
// hub:end globe,year
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
// hub:cut reports
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemStack;
// hub:end reports
// hub:cut deathbank
import net.runelite.api.events.ActorDeath;
// hub:end deathbank
import net.runelite.api.events.ChatMessage;
// hub:cut model,talk
import net.runelite.api.events.CommandExecuted;
// hub:end model,talk
// hub:cut talk
import net.runelite.api.events.ScriptCallbackEvent;
// hub:end talk
import net.runelite.api.events.MenuOptionClicked;
// hub:cut delve
import net.runelite.api.events.WidgetLoaded;
// hub:end delve
// hub:cut model
import net.runelite.api.Model;
// hub:end model
// hub:cut delve
import net.runelite.api.widgets.Widget;
// hub:end delve
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import java.io.IOException;
import java.util.HashSet;
// hub:cut deathbank
import net.runelite.api.gameval.InterfaceID;
// hub:end deathbank
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
// hub:cut talk
import net.runelite.api.gameval.VarClientID;
// hub:end talk
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
// hub:cut live
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
// hub:end live
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outside: the player's own game on their Outside page, each part behind its own switch: what they wear and carry
 * (with the looting bag, rune pouch, quiver, seed box and chugging barrel), their skills, item charges, their quests,
 * diaries, combat achievements and slayer task (Progress).
 * The snapshot (11/16/2026, the Plugin Hub's answer; docs/protocol.md, "The snapshot"): all of it posted once when
 * they press "Update my page" in the side tab, and hourly while they're logged in (AUTO_SYNC_MINUTES, as RuneProfile's),
 * never from the Wilderness, a PvP area or world; and what they're doing as RuneLite's own Discord plugin shows it
 * (Presence: the skill being trained, the area by Discord's name), as it changes, at most once a minute. Never another
 * player's anything, nor anything they type.
// hub:cut live
 * The whole plugin (developer mode) also keeps a socket: each game tick what changed goes (vitals alone every few
 * seconds; hitpoints, prayer, run and special and the prayers on), each new item's name and price once, the world;
 * nothing more from the Wilderness, a PvP area or world (docs/protocol.md, "The game").
// hub:end live
// hub:cut reports
 * Field reports for their page's event log (Reports).
// hub:end reports
// hub:cut globe
 * For outside.wiki's globe, each off to begin with: following (Follow), and the world with it; what they're doing
 * (Activity, through Behind); the shooting stars they find (Stars).
// hub:end globe
// hub:cut year
 * Their own year's record, kept on this PC (Record), with its exact path if they switch it on (Track).
// hub:end year
// hub:cut model
 * Their character's model, its motions and the live view (Kept), off to begin with.
// hub:end model
// hub:cut bank
 * Their bank, off to begin with.
// hub:end bank
// hub:cut deathbank
 * Their deathbank, as they last looked in it, off to begin with.
// hub:end deathbank
// hub:cut talk
 * Their page's chat in the game's chat box, off to begin with, answered with ::o or /o; their emotes (Talk, Emotes).
// hub:end talk
 */
@PluginDescriptor(
	name = "Outside",
	description = "Your gear, stats, quests, diaries and slayer task on your own page at Outside.wiki",
	tags = {"outside", "loadout", "inventory", "profile", "quests", "diaries", "slayer", "discord"}
)
public class OutsidePlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(OutsidePlugin.class);
	static final String VERSION = "0.3.0";
	/** the relay a page is claimed on when there's no key yet (docs/protocol.md "Claiming a name") */
	static final String HOME_RELAY = Attach.HOME;
	// hub:cut live
	/** vitals alone (run energy draining as you run) go at most this often, in ticks */
	private static final int VITALS_EVERY = 5;
	/** the longest wait between tries to reach the relay, in seconds */
	private static final int RETRY_MOST = 60;
	// hub:end live
	/** quests and diaries read again at least this often, in ticks (5 minutes) */
	private static final int PROGRESS_EVERY = 500;
	/** the hourly sync (11/16/2026, the Hub's answer; as RuneProfile's AutoSyncScheduler, AUTO_SYNC_MINUTES = 60): a
	 *  snapshot after this many minutes logged in since the last, counted in game ticks (100 a minute), so it waits
	 *  while they're logged out */
	static final int AUTO_SYNC_MINUTES = 60;
	static final int SYNC_TICKS = AUTO_SYNC_MINUTES * 100;
	/** "Update my page" once a minute at most */
	static final long UPDATE_EVERY_MS = 60_000L;
	/** what a snapshot carries (the rest of read()'s fields are the socket's) */
	static final String[] SNAP_FIELDS = {"name", "type", "worn", "inv", "bag", "pouch", "seeds", "barrel", "quiver",
		"charges", "skills", "quests", "diaries", "combat", "slayer", "delve", "delve_lost", "clog", "counts"};   // (delve: update 2; clog: the collection log's counter; counts: kill and clue counts, 11/16/2026; delve_lost: the delve levels lost to deaths, 11/19/2026)

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

	// hub:cut goal
	// (the game's own item search in the chatbox, as the bank tags' icon picker: the goal item's, 11/17/2026)
	@Inject
	private net.runelite.client.game.chatbox.ChatboxItemSearch goalSearch;
	// hub:end goal

	@Inject
	private ChatMessageManager chat;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	// hub:cut live
	/** only on the client's thread (the ticks); a new connection asks for everything again through `resend` */
	private final Kit kit = new Kit();
	// hub:end live
	/** what they're doing as Discord shows it (11/16/2026; Presence), the client's thread */
	private final Presence presence = new Presence();
	/** the snapshot (11/16/2026): game ticks logged in since the last; one asked for (the button, the hour) and waiting
	 *  for a tick out of anywhere dangerous; when the last went (ms; 0 none this session); whether anything's been posted
	 *  this session (so logging out says so); a scene just loaded, its area read on the next tick */
	private int syncTicks;
	private volatile boolean syncDue;
	private volatile long snapAt, updateAt;
	/** a press of "Update my page" too soon, waiting for the minute (updateNow) */
	private volatile boolean updateWaiting;
	private volatile boolean posted;
	private boolean sceneDue;
	// hub:cut globe
	/** following, likewise only on the client's thread */
	private final Follow follow = new Follow();
	// hub:end globe
	// hub:cut year
	/** the exact path, for the player's own year (11/10/2026; Track) */
	private final Track track = new Track();
	// hub:end year
	// hub:cut globe
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
	// hub:end globe
	// hub:cut globe,year,model
	/** worlds where nothing goes to the globe: PvP, Deadman, high risk, Bounty Hunter (11/10/2026: not the PvP Arena's
	 *  or Last Man Standing's, there only for those minigames, which are ordinary minigames now) */
	private static final EnumSet<WorldType> RISKY = EnumSet.of(WorldType.PVP, WorldType.DEADMAN, WorldType.HIGH_RISK,
		WorldType.BOUNTY);
	// hub:end globe,year,model
	// hub:cut live
	private volatile boolean resend;
	/** sending held while somewhere dangerous (the Wilderness, a PvP area or world: held()), and said so once */
	private boolean paused;
	// hub:end live
	// (quests and diaries, read again when what they depend on changes, or every 5 minutes; the slayer task's names
	// looked up again when its varps change; 11/15/2026)
	private JsonObject questsSeen, diariesSeen, slayerSeen;
	private long progressSig = Long.MIN_VALUE;
	private int progressAt = -1;
	private String slayerKey;
	// hub:cut talk
	/** the desk socket for the page's chat in the game (11/16/2026) */
	private WebSocket talkSocket;
	// hub:end talk
	/** the player's name as the game shows it, from the last tick */
	private volatile String shownName;
	// hub:cut reports
	/** the slayer task the game's just said is done, till its next line gives the count and the points */
	private String slayerDoneTask;
	// hub:end reports
	// hub:cut live
	private volatile WebSocket socket;
	private volatile boolean open;
	private Attach attach;
	private ScheduledFuture<?> retry;
	private int retryIn = 5;
	// hub:end live
	/** stopped for good until the key changes (a wrong key, or another RuneLite took over) */
	private volatile boolean stopped;
	/** the character logged in, as a channel has its name (null: not known yet, or logged out). 11/15/2026, Jace: logged
	 *  into his alt, the side tab still said sburb, and the alt's kit would have gone to sburb's page: a key is now this
	 *  character's own (kept per account), and nothing goes on a key whose page isn't the character logged in. */
	private volatile String character = null;
	// hub:cut year
	/** your year (11/15/2026; Record): the character's record, its own place and activity (two minutes behind, the
	 *  public switches' logic run apart, so it records with them off), what's held, and when a line last stated it */
	private volatile Record yearRecord = null;
	private volatile String yearFor = null;
	private final Follow yearFollow = new Follow();
	private final Behind yearDoing = new Behind("activity");
	private final java.util.Map<String, JsonElement> yearHeld = new java.util.concurrent.ConcurrentHashMap<>();
	private long yearStated = 0;
	private volatile long yearSynced = 0;
	// hub:end year
	// hub:cut reports
	/** each skill's real level as last seen this session, for the levels' reports */
	private final java.util.Map<String, Integer> levelsSeen = new java.util.concurrent.ConcurrentHashMap<>();
	// hub:end reports
	/** this key is a challenger's (taking a name back, 11/15/2026): what it sends only proves the name, till it's proven */
	private volatile boolean challenging = false;
	// proving, as the side tab follows it (11/17/2026): what the relay last said, the total experience in the update it
	// said "ready" to, and whether more's been gained since (noticed here, nothing sent)
	private volatile String proofState = null;
	// the register's newest note as last read (its id), and whether this login's been told of new ones
	private volatile long notesLast = 0;
	private volatile boolean notesSaid = false;
	private volatile long postXp = -1, readyXp = -1;
	private volatile boolean readyStale = false;
	/** movePage tried this session (a page under the name's old form) */
	private boolean movedThisSession = false;
	// hub:cut live
	private int ticksSinceSent;
	// hub:end live
	/** the seed box's and the chugging barrel's slots as last sent by the game (onItemContainerChanged), or null */
	private JsonElement seedsSeen, barrelSeen;
	// hub:cut live
	private boolean toldConnected;
	// hub:end live
	/** (11/14/2026) each item's charges as the game last told of them, by item id (kept on this RuneLite's profile for
	 *  the account, so they're known after a restart); the item last checked, and on which tick; the client's thread */
	private final Map<Integer, Integer> charges = new HashMap<>();
	private int checkedItem = -1, checkedTick = -100, ticks;
	// hub:cut delve
	/** (11/14/2026) the Doom of Mokhaiotl board's counts as last read (also kept on the profile), or null */
	private JsonElement delveSeen;
	/** (11/19/2026) the delve levels lost to deaths (Delves: this run's, and all lost, kept on the profile), and those
	 *  lost as they stood when the board was last read (sent beside it), or null */
	private final Delves delves = new Delves();
	private JsonElement delveLostSeen;
	// hub:end delve
	// hub:cut bank
	/** (11/14/2026) the bank changed on this tick (sent a few ticks after the last change), or -1 */
	private int bankChanged = -1;
	// hub:end bank
	// hub:cut deathbank
	/** (11/16/2026) the deathbank as it was last seen, {where, locked, ids, quantities}, sent a couple of ticks after
	 *  it last changed (a withdrawal of several goes once); the tick it changed, or -1 */
	private Object[] deathbankSeen;
	private int deathbankChanged = -1;
	/** (11/16/2026, Jace: "make it fill in the moment they die too") what they carried and wore as they fell, {ids,
	 *  how many}, the region they fell in and the tick; the game's line that a deathbank was made, once it comes */
	private int[][] deathCarried;
	private int deathRegion = -1;
	private int deathTick = -1;
	private String deathLine;
	// hub:end deathbank
	private static final MediaType JSON = MediaType.parse("application/json");
	// hub:cut delve
	/** the board beside the Doom of Mokhaiotl (the Delve Calculator plugin's: its scoreboard interface, each level's
	 *  count in child 46 + 3 a level, 1 to 8, then the waves past 8) */
	private static final int DELVE_BOARD = 920, DELVE_FIRST = 46, DELVE_STEP = 3;
	/** the Doom's lair, its delves' regions (the Delve Calculator plugin's: its DELVE_REGION_IDS) */
	private static final int[] DELVE_REGIONS = {5269, 13668, 14180};
	// hub:end delve

	@Inject
	private ConfigManager configManager;

	@Inject
	private net.runelite.client.ui.ClientToolbar clientToolbar;

	/** the side tab (11/15/2026; OutsidePanel) and its button */
	private OutsidePanel panel;
	private net.runelite.client.ui.NavigationButton navButton;

	@Provides
	OutsideConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OutsideConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new OutsidePanel(panelHooks());
		navButton = net.runelite.client.ui.NavigationButton.builder().tooltip("Outside").icon(tabIcon()).priority(8).panel(panel).build();
		clientToolbar.addNavigation(navButton);
		Attach a = key();
		panel.who(a == null ? null : a.getChannel());
		if (a != null)
		{
			// hub:cut model
			readMotions();
			// hub:end model
			// hub:cut talk
			readEmotes();
			// hub:end talk
			readProof();
		}
		// hub:cut live
		connect();
		// hub:end live
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			panel.stop();
		}
		// (RuneLite closing, or the plugin switched off, while they're logged in: as a logout)
		if (posted && client.getGameState() == GameState.LOGGED_IN)
		{
			post(off(), null);
		}
		posted = false;
		// hub:cut live
		close("plugin off");
		// hub:end live
	}

	private static JsonObject off()
	{
		JsonObject o = new JsonObject();
		o.addProperty("off", true);
		return o;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!OutsideConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if (panel != null && e.getKey().equals("stationKey"))
		{
			Attach a = key();
			panel.who(a == null ? null : a.getChannel());
			// hub:cut model
			readMotions();
			// hub:end model
			// hub:cut talk
			readEmotes();
			// hub:end talk
			readProof();
			readAccess();
		}
		if (e.getKey().equals("stationKey"))
		{
			stopped = false;
			snapAt = 0;
			presence.unsent();
			showSnap();
		}
		// (sending switched off: what's on the page comes off it; the snapshot's parts switched off go with the next)
		if (e.getKey().equals("send") && !config.send())
		{
			post(Kit.cleared(), null);
			presence.unsent();
			posted = false;
		}
		if (e.getKey().equals("presence") && !config.presence())
		{
			JsonObject none = new JsonObject();
			none.add("presence", JsonNull.INSTANCE);
			post(none, null);
			presence.unsent();
		}
		// hub:cut live
		// (a new key, or sending switched: start again; a part switched off goes as an empty field next tick)
		if (e.getKey().equals("stationKey") || e.getKey().equals("send"))
		{
			if (e.getKey().equals("send") && !config.send() && socket != null && open)
			{
				// (switched off: what's on the page comes off it first)
				send(Kit.cleared());
			}
			close("settings changed");
			stopped = false;
			toldConnected = false;
			connect();
		}
		// hub:end live
		// hub:cut model
		// (the model: on, it goes now; off, it's taken down. The bank: off, it's taken down; on, it goes next time it's used)
		if (e.getKey().equals("model"))
		{
			if (config.model())
			{
				clientThread.invokeLater(this::sendModel);
			}
			else
			{
				configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET);
				keep("model", null);
			}
		}
		// hub:end model
		// hub:cut goal
		// (the goal: off, it's taken down; on, the one set goes again)
		if (e.getKey().equals("goal"))
		{
			if (config.goal())
			{
				clientThread.invokeLater(() -> sendGoal(false));
			}
			else
			{
				keep("goal", null);
			}
		}
		// hub:end goal
		// hub:cut talk
		if (e.getKey().equals("chat"))
		{
			if (config.chat())
			{
				talkOpen();
			}
			else
			{
				talkClose();
			}
		}
		// hub:end talk
		// hub:cut bank
		if (e.getKey().equals("bank") && !config.bank())
		{
			keep("bank", null);
		}
		// hub:end bank
		// hub:cut deathbank
		if (e.getKey().equals("deathbank") && !config.deathbank())
		{
			deathbankChanged = -1;
			keep("deathbank", null);
		}
		// hub:end deathbank
		// hub:cut live
		resend = true;
		// hub:end live
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			// hub:cut goal
			goalPanel();
			// hub:end goal
			readProof();   // (proven since the last logout? the side tab says so)
			sceneDue = true;   // (a scene loaded: its area, as Discord reads it, on the next tick)
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			// (logged out, or hopping: the area's read again where they land)
			sceneDue = false;
		}
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			notesSaid = false;   // (the register's new notes, said again at the next login)
			// (logged out: the snapshot's and what they're doing's, said once if anything went this session)
			if (posted)
			{
				post(off(), null);
				posted = false;
			}
			presence.loggedOut();
			syncDue = false;   // (the hour's count waits while they're logged out)
			updateWaiting = false;   // (a press waiting for its minute: logged out, it doesn't go)
			if (panel != null)
			{
				panel.updateWait(0);
			}
			// hub:cut globe
			// (logged out: the relay drops the area and the activity with the rest; next time, the two minutes start
			// again; the stars not reported yet are forgotten)
			// hub:end globe
			// hub:cut globe
			follow.forget();
			follow.clear();
			// hub:end globe
			// hub:cut year
			track.forget();
			// hub:end year
			// (the seed box and the barrel as the game last sent them: another account may log in next)
			seedsSeen = barrelSeen = null;
			charges.clear();
			// hub:cut delve
			delveSeen = delveLostSeen = null;
			delves.left();
			delves.load(null);
			// hub:end delve
			// hub:cut live
			liveGear = gearSeen = null;
			liveRecorded.clear();
			autoStart = -1;
			// hub:end live
			// hub:cut globe
			doing.forget();
			doing.clear();
			activity.forget();
			stars.forget();
			// hub:end globe
		}
		// hub:cut globe
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING
			|| e.getGameState() == GameState.LOADING)
		{
			// (a new scene: its stars are seen again as it loads; one that went with the old one isn't gone)
			starsHere.clear();
			starsWent.clear();
		}
		// hub:end globe
		// hub:cut live
		if (e.getGameState() == GameState.LOGIN_SCREEN && open)
		{
			JsonObject off = new JsonObject();
			off.addProperty("off", true);
			send(off);
			resend = true;
		}
		// hub:end live
		if (e.getGameState() == GameState.LOGIN_SCREEN)
		{
			// hub:cut year
			yearLogout();
			// hub:end year
			character = null;
			// hub:cut reports
			levelsSeen.clear();
			// hub:end reports
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			// hub:cut live
			resend = true;
			// hub:end live
			loadKept();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		// (whose game this is: known from the first tick; another than before, the key and the side tab follow)
		Player me = client.getLocalPlayer();
		String whose = me == null ? null : channelName(me.getName());
		if (me != null && me.getName() != null)
		{
			shownName = me.getName().replace('\u00a0', ' ');
		}
		if (whose != null && !whose.equals(character))
		{
			character = whose;
			characterChanged();
		}
		ticks++;
		// hub:cut delve
		// (a delve's run over once they're away from the boss: claimed and gone, or left in the chest, nothing lost)
		if (delves.running() && !atDelve())
		{
			delves.left();
		}
		// hub:end delve
		// hub:cut bank
		// (the bank: sent three ticks after it last changed, so a whole deposit goes once)
		if (bankChanged >= 0 && ticks - bankChanged >= 3)
		{
			bankChanged = -1;
			sendBank();
		}
		// hub:end bank
		// hub:cut deathbank
		deathbankFromDeath();
		if (deathbankChanged >= 0 && ticks - deathbankChanged >= 2)
		{
			deathbankChanged = -1;
			sendDeathbank();
		}
		// hub:end deathbank
		// hub:cut live
		if (open && resend)
		{
			resend = false;
			kit.forget();
			// hub:cut globe
			follow.again();
			// hub:end globe
			// hub:cut year
			track.again();
			// hub:end year
			// hub:cut globe
			doing.again();
			// hub:end globe
		}
		// hub:end live
		// (where they are, read every tick, the relay in reach or not: somewhere dangerous holds things back at once)
		WorldPoint at = here();
		int x = at == null ? -1 : at.getX(), y = at == null ? -1 : at.getY(), region = at == null ? -1 : at.getRegionID();
		// hub:cut globe,year,model
		boolean riskyWorld = riskyWorld();
		// hub:end globe,year,model
		boolean wild = wilderness(x, y, client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1);
		boolean pvpArea = pvpArea(x, y, region, client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1);
		// (the snapshot and what they're doing, 11/16/2026: never from anywhere dangerous, whatever else is switched on)
		snapTick(held(pvpWorld(), wild, pvpArea), at, me);
		// hub:cut globe,year,model
		boolean safe = globeSafe(riskyWorld, wild, pvpArea);
		long ms = System.currentTimeMillis();
		// hub:end globe,year,model
		// hub:cut globe
		JsonObject where = follow(ms, safe);
		JsonObject what = activity(ms, activitySafe(riskyWorld, wild, pvpArea, config.wilderness()));
		// hub:end globe
		// hub:cut year
		JsonObject path = track(ms, at, safe);
		yearTick(ms, at, safe, activitySafe(riskyWorld, wild, pvpArea, false), path);
		// hub:end year
		// hub:cut globe
		look(ms, safe);
		// hub:end globe
		// hub:cut model
		// hub:cut live
		// (the live view's: the boss fought and the gear as it changes, every tick over the socket)
		encounter(ms, safe);
		liveSafe = safe && !paused;
		liveGearTick(ms);
		// hub:end live
		// hub:end model
		// hub:cut live
		if (!open)
		{
			return;
		}
		// hub:cut globe
		if (where != null)
		{
			send(where);
		}
		if (what != null)
		{
			send(what);
		}
		// hub:end globe
		// (the exact path stays on this PC, in the year's record: it went to the relay only for the outstation's own
		// year, which went 11/17/2026, Jace: "plugin owns the year")
		// hub:cut globe
		JsonObject star = config.stars() ? stars.due(ms, config.starsCredit()) : null;
		if (star != null)
		{
			send(star);
		}
		// hub:end globe
		// (somewhere dangerous: nothing more goes till they're out, unless they've said otherwise, so nobody
		// watching their page sees what they risk)
		// hub:cut globe
		if (!config.wilderness() && held(pvpWorld(), wild, pvpArea))
		// hub:end globe
		// hub:only if (held(pvpWorld(), wild, pvpArea))
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
		// hub:end live
	}

	// ---- the snapshot, and what they're doing as Discord shows it (11/16/2026, the Plugin Hub's answer;
	// docs/protocol.md, "The snapshot")

	/** Each tick logged in: the area read where a scene just loaded (as Discord reads it), the hour counted, a snapshot
	 *  asked for sent once they're somewhere safe, and what they're doing sent as it changes (cleared at once somewhere
	 *  dangerous). */
	private void snapTick(boolean danger, WorldPoint at, Player me)
	{
		if (sceneDue && at != null && me != null)
		{
			sceneDue = false;
			presence.scene(at.getRegionID(), me.getWorldLocation().getPlane());
		}
		if (!config.send() || key() == null)
		{
			return;
		}
		if (++syncTicks >= SYNC_TICKS)
		{
			syncTicks = 0;
			syncDue = true;
		}
		long now = System.currentTimeMillis();
		// (a press too soon, its minute up: it goes now, as a press would)
		if (updateWaiting && System.currentTimeMillis() - updateAt >= UPDATE_EVERY_MS)
		{
			updateWaiting = false;
			updateNow();
		}
		if (syncDue && !danger)
		{
			syncDue = false;
			syncTicks = 0;
			snapshot(now);
			return;
		}
		if (!config.presence())
		{
			return;
		}
		JsonObject p = presence.due(now, danger);
		if (p != null)
		{
			JsonObject body = new JsonObject();
			body.add("presence", p);
			presence.took(p, now);
			post(body, null);
		}
	}

	/** The snapshot (on the client's thread): what's switched on of read()'s fields (SNAP_FIELDS), every item's name and
	 *  price, and what they're doing; posted once. */
	private void snapshot(long now)
	{
		JsonObject body = snapshotBody(now, true);
		List<String> named;
		synchronized (clogNew)
		{
			named = new ArrayList<>(clogNew);
		}
		panel.snapStatus("updating your page…");
		post(body, ok ->
		{
			if (ok)
			{
				snapAt = System.currentTimeMillis();
				// (the slots named with it: gone with it, so the next update names only newer ones)
				synchronized (clogNew)
				{
					clogNew.removeAll(named);
				}
				// hub:cut model,bank
				sendHeldKeeps();   // (the Hub's version: the bank and the deathbank, held since they looked)
				// hub:end model,bank
				showSnap();
			}
		});
	}

	/** What a snapshot carries (on the client's thread): what's switched on of read()'s fields (SNAP_FIELDS), every
	 *  item's name and price, and what they're doing. `taking`: what they're doing counted as sent (a real snapshot);
	 *  false for "Show what's sent", which only shows it. */
	JsonObject snapshotBody(long now, boolean taking)
	{
		Map<String, JsonElement> m = read();
		JsonObject body = new JsonObject();
		for (String f : SNAP_FIELDS)
		{
			if (m.containsKey(f))
			{
				body.add(f, m.get(f));
			}
		}
		JsonObject items = new JsonObject();
		for (int id : Kit.ids(m))
		{
			JsonArray np = new JsonArray();
			np.add(itemManager.getItemComposition(id).getName());
			np.add(itemManager.getItemPrice(id));
			items.add(String.valueOf(id), np);
		}
		body.add("items", items);
		body.addProperty("off", false);
		body.addProperty("paused", false);
		if (config.presence())
		{
			JsonObject p = presence.now(now, false);
			body.add("presence", p);
			if (taking)
			{
				presence.took(p, now);
			}
		}
		return body;
	}

	/** the game's chat line for a new collection log slot (its "Collection log - New addition notification" setting;
	 *  Dink's CollectionNotifier reads the same line) */
	private static final java.util.regex.Pattern CLOG_ADDED = java.util.regex.Pattern.compile("^New item added to your collection log: (.+?)\\s*$");
	/** at most this many slots' names with one update */
	static final int CLOG_NEW_MOST = 8;
	/** the slots the chat named since the last update went */
	private final List<String> clogNew = new ArrayList<>();
	/** kill counts and clue counts as the chat says them, kept per account (Counts; 11/16/2026) */
	private final Counts counts = new Counts();

	/** The item a chat line says was added to the collection log, or null. */
	static String clogAdded(String line)
	{
		java.util.regex.Matcher m = line == null ? null : CLOG_ADDED.matcher(line);
		return m != null && m.matches() ? m.group(1).trim() : null;
	}

	/** The collection log's counter (on the client's thread): {"count", "max", "new": [names]}, or null while the game
	 *  hasn't said its count. */
	private JsonObject clogRead()
	{
		int count = client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT);
		int max = client.getVarpValue(net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_MAX);
		if (count <= 0 || max <= 0 || count > max)
		{
			return null;
		}
		JsonObject o = new JsonObject();
		o.addProperty("count", count);
		o.addProperty("max", max);
		JsonArray names = new JsonArray();
		synchronized (clogNew)
		{
			for (String n : clogNew)
			{
				names.add(n);
			}
		}
		o.add("new", names);
		return o;
	}

	/** "Show what's sent" (11/16/2026, update 2: as OSRS Map Companion's "Show what's sent"): exactly what "Update my
	 *  page" would post now, read on the client's thread and shown in a window; nothing's sent. */
	private void showSent()
	{
		clientThread.invokeLater(() ->
		{
			Attach a = key();
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
			{
				panel.snapStatus("log in first: it shows what this character would send");
				return;
			}
			JsonObject body = snapshotBody(System.currentTimeMillis(), false);
			String text = gson.newBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(body);
			String to = a == null ? "your page, once it's made" : "Outside.wiki/" + a.getChannel() + " (through " + HOME_RELAY.replaceAll("^https?://", "") + ")";
			panel.showSent(to, text, config.send());
		});
	}

	/** "Update my page" (the side tab): a snapshot now, once a minute at most, never from anywhere dangerous. */
	private void updateNow()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
			{
				panel.snapStatus("log in first");
				return;
			}
			if (!config.send())
			{
				panel.snapStatus("switch on \"Send to my Outside page\" in the plugin's settings first");
				return;
			}
			if (key() == null)
			{
				panel.snapStatus("make your page first");
				return;
			}
			long now = System.currentTimeMillis();
			if (now - updateAt < UPDATE_EVERY_MS)
			{
				// (a press too soon waits, and goes by itself once the minute's up: onGameTick, updateWaiting)
				updateWaiting = true;
				panel.updateWait(updateAt + UPDATE_EVERY_MS);
				panel.snapStatus("updated a moment ago: this one goes when the minute's up");
				return;
			}
			WorldPoint at = here();
			int x = at == null ? -1 : at.getX(), y = at == null ? -1 : at.getY(), region = at == null ? -1 : at.getRegionID();
			if (held(pvpWorld(), wilderness(x, y, client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1),
				pvpArea(x, y, region, client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1)))
			{
				panel.snapStatus("not from here: the Wilderness, a PvP area or world. It goes once you're out.");
				syncDue = true;
				return;
			}
			updateAt = now;
			updateWaiting = false;
			panel.updateWait(0);
			syncTicks = 0;
			snapshot(now);
		});
	}

	/** The side tab's line: when the page was last updated from here. */
	private void showSnap()
	{
		if (panel != null)
		{
			panel.snapped(snapAt);
		}
	}

	/** Posts {"game": fields} to the relay (POST /game; docs/protocol.md, "The snapshot"), for the character logged in
	 *  only, with a key for its page; `done` hears whether it was taken. A refused key stops it till the key changes. */
	private void post(JsonObject fields, java.util.function.Consumer<Boolean> done)
	{
		Attach a = key();
		if (a == null || stopped || (character == null && !fields.has("off")))
		{
			if (done != null)
			{
				done.accept(false);
			}
			return;
		}
		posted = posted || !fields.has("off");
		long sentXp = fields.has("skills") && fields.get("skills").isJsonObject() && fields.getAsJsonObject("skills").size() > 0 ? postXp : -1;
		JsonObject msg = new JsonObject();
		msg.add("game", fields);
		Request req = new Request.Builder().url(a.postUrl(VERSION)).header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, Kit.wire(gson).toJson(msg))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("outside: the relay's out of reach", ex);
				if (done != null)
				{
					panel.snapStatus("the relay's out of reach: try again in a bit");
					done.accept(false);
				}
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					int code = res.code();
					if (code == 403)
					{
						stopped = true;
						say(challenging ? "someone else proved this name first, so your claim on it has ended"
							: "the relay didn't take your station key: paste it again in the plugin's settings");
					}
					else if (code == 429 && done != null)
					{
						panel.snapStatus("too often: try again in a minute");
					}
					else if (code == 200 && res.body() != null)
					{
						// (what the page's proof did with it, while it's still to be made: docs/protocol.md "Proving a name")
						try
						{
							JsonObject o = gson.fromJson(res.body().string(), JsonObject.class);
							if (o != null && o.has("proof"))
							{
								proofSaid(o.get("proof").getAsString(), o.has("why") ? o.get("why").getAsString() : null, sentXp);
							}
						}
						catch (IOException | RuntimeException ignored)
						{
							// (an answer it can't read: the update itself was taken)
						}
					}
					if (done != null)
					{
						done.accept(code == 204 || code == 200);
					}
				}
			}
		});
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
		// hub:cut live
		m.put("world", new JsonPrimitive(client.getWorld()));
		// hub:end live
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
			// (the seed box and the chugging barrel, 11/14/2026, Jace: "capture and show the contents inside of the seed
			// box and chugging barrel too": each its slots as the game keeps them, known once the game has told RuneLite,
			// as the looting bag is, and kept as they were till then)
			// (and as the game sent them, caught as they came, onItemContainerChanged: the client may not keep them after)
			ItemContainer seeds = client.getItemContainer(InventoryID.SEED_BOX);
			if (seeds != null)
			{
				seedsSeen = slots(seeds, 6);
			}
			if (seedsSeen != null)
			{
				m.put("seeds", seedsSeen);
			}
			ItemContainer barrel = client.getItemContainer(InventoryID.PREPOT_DEVICE_INV);
			if (barrel != null)
			{
				barrelSeen = slots(barrel, 5);
			}
			if (barrelSeen != null)
			{
				m.put("barrel", barrelSeen);
			}
			int ammo = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
			int amount = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
			m.put("quiver", ammo > 0 && amount > 0 ? Kit.pair(ammo, amount) : com.google.gson.JsonNull.INSTANCE);
		}
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			for (String f : new String[]{"worn", "inv", "bag", "pouch", "seeds", "barrel"})
			{
				m.put(f, new JsonArray());
			}
			m.put("quiver", JsonNull.INSTANCE);
		}
		if (config.items() && config.charges())
		{
			// (only the items worn or carried now)
			Set<Integer> here = new HashSet<>();
			for (int c : new int[]{InventoryID.WORN, InventoryID.INV})
			{
				ItemContainer ic = client.getItemContainer(c);
				if (ic != null)
				{
					for (Item it : ic.getItems())
					{
						here.add(it.getId());
					}
				}
			}
			JsonObject ch = new JsonObject();
			for (Map.Entry<Integer, Integer> e : new java.util.TreeMap<>(charges).entrySet())
			{
				if (here.contains(e.getKey()))
				{
					ch.addProperty(String.valueOf(e.getKey()), e.getValue());
				}
			}
			m.put("charges", ch);
		}
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("charges", new JsonObject());
		}
		// hub:cut live
		// (the live view, 11/15/2026: the animation they're doing now and the gear it's in)
		if (config.model() && me != null && liveGear != null)
		{
			JsonArray an = new JsonArray();
			an.add(me.getAnimation());
			an.add(liveGear);
			m.put("anim", an);
		}
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("anim", JsonNull.INSTANCE);
		}
		// hub:end live
		// hub:cut delve
		if (config.delve() && delveSeen != null)
		{
			m.put("delve", delveSeen);
			// (the levels lost to deaths as they stood when the board was read: Delves; none sent till it's read again)
			m.put("delve_lost", delveLostSeen == null ? JsonNull.INSTANCE : delveLostSeen);
		}
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("delve", JsonNull.INSTANCE);
			m.put("delve_lost", JsonNull.INSTANCE);
		}
		// hub:end delve
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
			postXp = client.getOverallExperience();
		}
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("skills", new JsonObject());
		}
		if (config.progress())
		{
			long sig = Progress.signature(client);
			if (sig != progressSig || progressAt < 0 || ticks - progressAt >= PROGRESS_EVERY)
			{
				progressSig = sig;
				progressAt = ticks;
				// hub:cut reports
				JsonObject before = questsSeen;
				// hub:end reports
				questsSeen = Progress.readQuests(client);
				// hub:cut reports
				// (a quest that's done now and wasn't at the last read, this session: a field report; 11/15/2026)
				for (String q : Progress.newlyDone(before, questsSeen))
				{
					report(Reports.quest(q, questsSeen.getAsJsonArray("done").size(), questsSeen.get("total").getAsInt(), myName()));
				}
				// hub:end reports
				diariesSeen = Progress.readDiaries(client);
			}
			m.put("quests", questsSeen);
			m.put("diaries", diariesSeen);
			m.put("combat", Progress.readCombat(client));
			// (11/16/2026, the collection log's counter: slots filled and in all, as the game keeps them, and the names of
			// those the game's chat said were added since the last update; nothing till the game has said its count)
			JsonObject clog = clogRead();
			if (clog != null)
			{
				m.put("clog", clog);
			}
			// (kill counts and clue counts, as the chat last said each; kept on this RuneLite's profile)
			JsonObject c = counts.json();
			if (c != null)
			{
				m.put("counts", c);
			}
		}
		else
		{
			// (switched off: taken off their page)
			m.put("quests", JsonNull.INSTANCE);
			m.put("diaries", JsonNull.INSTANCE);
			m.put("combat", JsonNull.INSTANCE);
			m.put("clog", JsonNull.INSTANCE);
			m.put("counts", JsonNull.INSTANCE);
		}
		if (config.slayer())
		{
			String k = Progress.slayerKey(client);
			if (!k.equals(slayerKey) || slayerSeen == null)
			{
				slayerKey = k;
				slayerSeen = Progress.readSlayer(client);
			}
			m.put("slayer", slayerSeen);
		}
		else
		{
			m.put("slayer", JsonNull.INSTANCE);
		}
		// hub:cut live
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
		else
		{
			// (switched off: taken off the page, as quests and slayer are; the relay keeps each field as last sent)
			m.put("vitals", new JsonObject());
			m.put("prayers", new JsonArray());
		}
		// hub:end live
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

	// hub:cut globe
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
		WorldPoint at = here();
		if (at == null)
		{
			return null;
		}
		return follow.tick(now, at.getX(), at.getY(), world ? client.getWorld() : 0, safe);
	}
	// hub:end globe

	// hub:cut year
	/** The exact path's tick (Track, 11/10/2026): what to send, or null. Off, what was sent is cleared once. No
	 *  delay: it goes only to the player's own outstation, for their record. */
	private JsonObject track(long now, WorldPoint at, boolean safe)
	{
		if (!config.recordPath())
		{
			JsonObject gone = track.clear();
			track.forget();
			return gone;
		}
		if (at == null)
		{
			return null;
		}
		return track.tick(now, at.getX(), at.getY(), at.getPlane(), client.getWorld(), safe);
	}
	// hub:end year

	// hub:cut globe
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
		WorldPoint at = here();
		activity.region(at == null ? -1 : at.getRegionID(), at == null ? 0 : at.getPlane());
		activity.trial(client.getVarbitValue(VarbitID.SAILING_BT_IN_TRIAL) != 0);
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
		// (on a boat too, 11/10/2026: from where it is at sea, here())
		WorldPoint me = client.getGameState() == GameState.LOGGED_IN ? here() : null;
		if (me != null && me.distanceTo(p) <= 32)
		{
			starsWent.add(p);
		}
	}
	// hub:end globe

	/** Their own experience (the activity's skilling). */
	/** The seed box's and the chugging barrel's contents as the game sends them (when one's checked or filled, or the
	 *  barrel's bank loadout opens), kept for read(): the Hub's Seed Box plugin reads the seed box the same way. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() == InventoryID.SEED_BOX)
		{
			seedsSeen = slots(e.getItemContainer(), 6);
		}
		else if (e.getContainerId() == InventoryID.PREPOT_DEVICE_INV)
		{
			barrelSeen = slots(e.getItemContainer(), 5);
		}
		// hub:cut bank
		else if (e.getContainerId() == InventoryID.BANK && config.bank())
		{
			bankChanged = ticks;
		}
		// hub:end bank
		// hub:cut deathbank
		// (a deathbank's window fills GRAVESTONE too, as a gravestone's does: the gravestone's own window open, it's a
		// gravestone's, not sent; read now, while the window's IF1 says which deathbank it is)
		else if (e.getContainerId() == InventoryID.GRAVESTONE && config.deathbank()
			&& client.getWidget(InterfaceID.GravestoneGeneric.UNIVERSE) == null)
		{
			Object[] where = Kept.deathbankWhere(client.getVarpValue(VarPlayerID.IF1));
			Item[] items = e.getItemContainer() == null ? new Item[0] : e.getItemContainer().getItems();
			int[] ids = new int[items.length];
			int[] qs = new int[items.length];
			for (int i = 0; i < items.length; i++)
			{
				ids[i] = items[i].getId();
				qs[i] = items[i].getQuantity();
			}
			// (no deathbank's window open and nothing in it: emptied as they took the last out)
			deathbankSeen = where == null ? new Object[]{"", false, new int[0], new int[0], false} : new Object[]{where[0], where[1], ids, qs, false};
			deathbankChanged = ticks;
		}
		// hub:end deathbank
	}

	// hub:cut delve,bank,model
	// ---- (11/14/2026) charges, the delve board, the bank and the model: Kept
	// hub:end delve,bank,model
	// hub:only // ---- (11/14/2026) charges: Kept

	/** An item checked ("Check", "Inspect" and the like): the next charges the game tells of are its. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		String opt = e.getMenuOption() == null ? "" : Text.removeTags(e.getMenuOption()).toLowerCase();
		if (e.getItemId() > 0 && (opt.startsWith("check") || opt.equals("inspect") || opt.equals("charges")))
		{
			checkedItem = e.getItemId();
			checkedTick = ticks;
		}
	}

	/** The game's own words of an item's charges: the item just checked, or the worn or carried one it names. */
	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		// (a collection log slot the game's chat names, for the next update's counter: clogRead)
		if ((e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM) && config.progress())
		{
			String added = clogAdded(Text.removeTags(e.getMessage()));
			synchronized (clogNew)
			{
				if (added != null && clogNew.size() < CLOG_NEW_MOST && !clogNew.contains(added))
				{
					clogNew.add(added);
				}
			}
			if (counts.take(Text.removeTags(e.getMessage())))
			{
				configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "counts", gson.toJson(counts.json()));
			}
		}
		// hub:cut delve
		// (a delve level done, counted in its run: Delves)
		if ((e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM) && config.delve())
		{
			delves.done(Text.removeTags(e.getMessage()));
		}
		// hub:end delve
		// hub:cut deathbank
		if (e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM)
		{
			deathbankLine(Text.removeTags(e.getMessage()));
		}
		// hub:end deathbank
		// hub:cut goal
		if ((e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM) && config.goal())
		{
			goalLine(clogAdded(Text.removeTags(e.getMessage())));
		}
		// hub:end goal
		// hub:cut reports
		if (e.getType() == ChatMessageType.GAMEMESSAGE || e.getType() == ChatMessageType.SPAM)
		{
			String said = Text.removeTags(e.getMessage());
			report(Reports.fromChat(said, myName()));
			// (a slayer task's end comes in two lines: the task, then the count and the points; 11/15/2026)
			String task = Reports.slayerTask(said);
			if (task != null)
			{
				slayerDoneTask = task;
			}
			else if (slayerDoneTask != null)
			{
				JsonObject done = Reports.slayerDone(said, slayerDoneTask, myName());
				if (done != null)
				{
					slayerDoneTask = null;
					report(done);
				}
			}
		}
		// hub:end reports
		if (!config.charges() || (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM))
		{
			return;
		}
		String msg = Text.removeTags(e.getMessage());
		Integer n = Kept.charges(msg);
		if (n == null)
		{
			return;
		}
		int item = -1;
		if (checkedItem > 0 && ticks - checkedTick <= 3)
		{
			item = checkedItem;
		}
		else
		{
			for (int c : new int[]{InventoryID.WORN, InventoryID.INV})
			{
				ItemContainer ic = client.getItemContainer(c);
				if (ic == null)
				{
					continue;
				}
				for (Item it : ic.getItems())
				{
					if (item < 0 && it.getId() > 0 && Kept.about(msg, itemManager.getItemComposition(it.getId()).getName()))
					{
						item = it.getId();
					}
				}
			}
		}
		if (item > 0)
		{
			charges.put(item, n);
			checkedItem = -1;
			saveKept();
		}
	}

	// hub:cut delve
	/** The board beside the Doom of Mokhaiotl opened: its counts read (as the Delve Calculator plugin reads them). */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == DELVE_BOARD && config.delve())
		{
			clientThread.invokeLater(this::readDelve);
		}
	}

	private void readDelve()
	{
		JsonArray counts = new JsonArray();
		boolean any = false;
		for (int i = 0; i < 9; i++)
		{
			Widget w = client.getWidget(DELVE_BOARD, DELVE_FIRST + i * DELVE_STEP);
			String t = w == null || w.getText() == null ? "" : w.getText().replaceAll("[^0-9]", "");
			int n = 0;
			if (!t.isEmpty() && t.length() < 10)
			{
				n = Integer.parseInt(t);
				any = true;
			}
			counts.add(n);
		}
		if (any)
		{
			delveSeen = counts;
			// (the levels lost to deaths, as they stand now: the board just read counts them too)
			JsonArray lost = new JsonArray();
			for (int n : delves.lost)
			{
				lost.add(n);
			}
			delveLostSeen = lost;
			saveKept();
		}
	}
	// hub:end delve

	// hub:cut talk,model
	/** ::outsidemodel: the model again, as they look now. */
	@Subscribe
	public void onCommandExecuted(CommandExecuted e)
	{
		// hub:cut goal
		// (::outsidegoal opens the item search for the goal; ::outsidegoal clear takes it off)
		if ("outsidegoal".equalsIgnoreCase(e.getCommand()))
		{
			if (e.getArguments().length > 0 && "clear".equalsIgnoreCase(e.getArguments()[0]))
			{
				goalClear();
			}
			else
			{
				goalPick();
			}
			return;
		}
		// hub:end goal
		// hub:cut talk
		// (the page's chat, 11/16/2026: ::o answers it, ::ochat off / on stops and starts visitors' lines)
		if ("o".equalsIgnoreCase(e.getCommand()) || "ochat".equalsIgnoreCase(e.getCommand()))
		{
			WebSocket ws = talkSocket;
			if (ws == null)
			{
				say(config.chat() ? "your page's chat isn't open yet" : "switch on \"The page's chat in my game\" in the plugin's settings first");
				return;
			}
			if ("ochat".equalsIgnoreCase(e.getCommand()))
			{
				String w = e.getArguments().length > 0 ? e.getArguments()[0].toLowerCase(java.util.Locale.ROOT) : "";
				if (!w.equals("on") && !w.equals("off"))
				{
					say("::ochat off stops visitors' lines on your page, ::ochat on lets them back");
					return;
				}
				ws.send(Talk.chatOn(w.equals("on"), gson));
				say(w.equals("on") ? "visitors can talk on your page again" : "visitors' lines on your page go nowhere now");
				return;
			}
			answerPage(String.join(" ", e.getArguments()));
			return;
		}
		// hub:end talk
		if ("outsideforget".equalsIgnoreCase(e.getCommand()))
		{
			String name = motionName(e.getArguments());
			if (name == null || e.getArguments().length == 0)
			{
				sayModel("which motion? ::outsideforget and its name");
				return;
			}
			keep("motion", name, null);
			sayModel("taking the motion \"" + name + "\" off your page");
			return;
		}
		if ("outsidemotion".equalsIgnoreCase(e.getCommand()))
		{
			if (config.model())
			{
				String name = motionName(e.getArguments());
				if (e.getArguments().length > 0 && name == null)
				{
					sayModel("a motion's name: up to 24 letters, numbers, spaces, - or _");
					return;
				}
				armMotion(name);
			}
			else
			{
				sayModel("switch on \"Character model\" in the plugin's settings first");
			}
			return;
		}
		if ("outsidemodel".equalsIgnoreCase(e.getCommand()))
		{
			if (config.model())
			{
				sendStill();
			}
			else
			{
				sayModel("switch on \"Character model\" in the plugin's settings first");
			}
		}
	}
	// hub:end talk,model

	// hub:cut model
	// hub:cut live
	// ---- the live view (11/15/2026, Jace: "allow them to have a static set of gear/animation of their choosing while also
	// having the ability for the viewer to show the 'live view' that shows what they are currently wearing and what they
	// are doing in game"; "yes, build the live view that way"): the model in the gear they wear now, sent a few seconds
	// after it changes (at most every LIVE_EVERY), its gear marked; each animation they do recorded the first time it's
	// seen in that gear (one at a time, at most one starting every LIVE_GAP), and them standing (idle); the animation
	// they're doing now goes with the game's fields ("anim"), so the page plays the one that matches. Only with the model
	// switch on, never anywhere dangerous, never while a capture of theirs is recording.
	private static final long LIVE_EVERY = 20_000L, LIVE_GAP = 6_000L;
	private String liveGear, gearSeen;
	private long gearAt, liveSentAt, liveAutoAt;
	private boolean liveSafe, liveDue;
	private final Set<String> liveRecorded = new HashSet<>();
	private int autoStart = -1, autoLast, autoCount, autoPetCount;
	private String autoAnim;
	private JsonObject autoBase, autoPet;
	private final List<String> autoFrames = new ArrayList<>(), autoPetFrames = new ArrayList<>();

	/** The worn gear's mark: its items, hashed. */
	private String gearMark()
	{
		ItemContainer w = client.getItemContainer(InventoryID.WORN);
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		if (w != null)
		{
			for (Item it : w.getItems())
			{
				int id = it.getId();
				crc.update(new byte[]{(byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id});
			}
		}
		return Long.toHexString(crc.getValue());
	}

	private void liveGearTick(long now)
	{
		if (!config.model() || !open || !liveSafe)
		{
			return;
		}
		String g = gearMark();
		if (!g.equals(gearSeen))
		{
			gearSeen = g;
			gearAt = now;
			liveDue = true;
		}
		// (a few seconds after the gear settles, and not more often than LIVE_EVERY)
		if (liveDue && now - gearAt >= 3_000 && now - liveSentAt >= LIVE_EVERY && motionArmed < 0 && autoStart < 0)
		{
			Player me = client.getLocalPlayer();
			Model m = me == null ? null : me.getModel();
			if (m == null)
			{
				return;
			}
			JsonObject player = modelJson(m);
			Model pet = petModel();
			JsonObject o = wholeModel(player, pet == null ? null : modelJson(pet), null, null);
			liveDue = false;
			liveSentAt = now;
			if (!g.equals(liveGear))
			{
				liveRecorded.clear();
			}
			liveGear = g;
			keepLive("live", null, o);
		}
	}

	/** Each client cycle: an animation not yet recorded in this gear (or them standing), recorded in the background. */
	private void liveRecordTick(Player me, int cycle)
	{
		if (!config.model() || !open || !liveSafe || liveGear == null || motionArmed >= 0)
		{
			autoStart = -1;
			return;
		}
		if (autoStart < 0)
		{
			int anim = me.getAnimation();
			boolean standing = anim == -1 && me.getPoseAnimation() == me.getIdlePoseAnimation();
			String key = anim != -1 ? String.valueOf(anim) : standing ? "idle" : null;
			long now = System.currentTimeMillis();
			if (key == null || liveRecorded.contains(key) || now - liveAutoAt < LIVE_GAP)
			{
				return;
			}
			Model base = me.getModel();
			if (base == null)
			{
				return;
			}
			Snap bs = snap(base, true);
			List<Fx> bfx = effects(me, null);
			autoCount = ownOf(bs, bfx);
			autoBase = Kept.trim(bs.json, autoCount);
			autoFx = new FxTrack();
			Model pet = petModel();
			autoPetCount = pet == null ? 0 : pet.getVerticesCount();
			autoPet = pet == null ? null : modelJson(pet);
			autoAnim = key;
			autoStart = cycle;
			autoLast = cycle - MOTION_EVERY;
			autoFrames.clear();
			autoPetFrames.clear();
			liveAutoAt = now;
		}
		if (cycle - autoLast < MOTION_EVERY)
		{
			return;
		}
		autoLast = cycle;
		boolean idle = "idle".equals(autoAnim);
		// (standing: let go the moment they move; an animation: until it ends)
		if (idle && (me.getAnimation() != -1 || me.getPoseAnimation() != me.getIdlePoseAnimation()))
		{
			autoStart = -1;
			return;
		}
		Model m = me.getModel();
		Snap ms = m == null ? null : snap(m, false);
		List<Fx> mfx = ms == null ? null : effects(me, autoFx.models.keySet());
		if (ms != null && ownOf(ms, mfx) == autoCount)
		{
			autoFrames.add(Kept.frame(ms.x, ms.y, ms.z, autoCount));
			autoFx.add(mfx);
			Model p = autoPet == null ? null : petModel();
			autoPetFrames.add(p != null && p.getVerticesCount() == autoPetCount ? Kept.frame(p.getVerticesX(), p.getVerticesY(), p.getVerticesZ(), p.getVerticesCount()) : null);
		}
		boolean done = autoFrames.size() >= MOTION_MOST
			|| (idle ? cycle - autoStart >= MOTION_IDLE : !autoAnim.equals(String.valueOf(me.getAnimation())) && autoFrames.size() > 2);
		if (!done)
		{
			return;
		}
		autoStart = -1;
		if (autoFrames.size() < 3)
		{
			return;
		}
		liveRecorded.add(autoAnim);
		JsonObject frames = Kept.frames(autoFrames, 1000.0 / (MOTION_EVERY * 20));
		autoFx.into(frames);
		JsonObject petFrames = autoPet != null && !autoPetFrames.contains(null) ? Kept.frames(autoPetFrames, 1000.0 / (MOTION_EVERY * 20)) : null;
		keepLive("livemotion", autoAnim, wholeModel(autoBase, autoPet, frames, petFrames));
	}

	/** The live view's uploads (the relay's /keep/<channel>/live and /livemotion), quietly: nothing said in the chat. */
	private void keepLive(String what, String anim, JsonObject body)
	{
		Attach a = attach != null ? attach : key();
		if (a == null || !config.send() || liveGear == null)
		{
			return;
		}
		String url = a.keepUrl(what) + "&gear=" + liveGear + (anim == null ? "" : "&anim=" + anim);
		http.newCall(new Request.Builder().url(url).header("User-Agent", "outside-plugin/" + VERSION).post(RequestBody.create(JSON, gson.toJson(body))).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("outside: live {} failed", what, ex);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				r.close();
			}
		});
	}

	// ---- the encounter (11/15/2026, Jace: "build what you think is best" under the subject's form): the last boss they
	// fought, its model, on their page as the fight ends; never from anywhere dangerous; each boss at most once every
	// ENCOUNTER_EVERY; only with the model switch on
	private static final long ENCOUNTER_EVERY = 30 * 60_000L, ENCOUNTER_OVER = 20_000L;
	private final Map<String, Long> encounterSent = new HashMap<>();
	private JsonObject encounterModel;
	private String encounterName;
	private long encounterAt;

	private void encounter(long now, boolean safe)
	{
		if (!config.model() || !open)
		{
			return;
		}
		if (!safe)
		{
			encounterModel = null;   // (nothing from anywhere dangerous: what was caught there is let go)
			encounterName = null;
			return;
		}
		Player me = client.getLocalPlayer();
		Actor t = me == null ? null : me.getInteracting();
		String boss = t instanceof NPC && t.getName() != null ? Activity.BOSSES.get(t.getName()) : null;
		if (boss != null && !Activity.WILDERNESS_BOSSES.contains(boss))
		{
			if (!boss.equals(encounterName))
			{
				Long last = encounterSent.get(boss);
				Model m = ((NPC) t).getModel();
				if ((last == null || now - last >= ENCOUNTER_EVERY) && m != null)
				{
					// (written out at once: one shared model object for anything animated)
					encounterModel = wholeModel(modelJson(m), null, null, null);
					encounterName = boss;
				}
			}
			encounterAt = now;
			return;
		}
		if (encounterModel != null && now - encounterAt >= ENCOUNTER_OVER)
		{
			JsonObject o = new JsonObject();
			o.addProperty("name", encounterName);
			o.add("model", encounterModel);
			encounterSent.put(encounterName, now);
			keep("encounter", o);
			encounterModel = null;
			encounterName = null;
		}
	}
	// hub:end live
	// hub:end model

	/** The side tab's icon: a lime ✼, drawn here (no picture to ship). */
	private static java.awt.image.BufferedImage tabIcon()
	{
		java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = im.createGraphics();
		g.setColor(new java.awt.Color(0xb9, 0xff, 0x00));
		g.fillRect(7, 1, 2, 14);
		g.fillRect(1, 7, 14, 2);
		for (int i = 0; i < 4; i++)
		{
			g.fillRect(3 + i, 3 + i, 2, 2);
			g.fillRect(11 - i, 3 + i, 2, 2);
			g.fillRect(3 + i, 11 - i, 2, 2);
			g.fillRect(11 - i, 11 - i, 2, 2);
		}
		g.dispose();
		return im;
	}

	// hub:cut goal
	// ---- the goal item (Goal.java; 11/17/2026, update 3) ----

	/** The game's item search in the chatbox, for the goal: as you type, only the items that match, a click sets it. */
	private void goalPick()
	{
		clientThread.invokeLater(() ->
		{
			if (!config.goal())
			{
				say("switch on \"Goal item\" in the plugin's settings first");
				return;
			}
			if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
			{
				say("log in first: the goal is your account's own");
				return;
			}
			goalSearch.tooltipText("Set as my Outside goal")
				.onItemSelected(id -> clientThread.invokeLater(() -> goalChosen(id)))
				.build();
		});
	}

	/** An item chosen: kept for this account, sent with its model (on the client's thread). */
	private void goalChosen(int id)
	{
		int item = itemManager.canonicalize(id);
		configManager.setRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM, item);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT_COUNTS);
		sendGoal();
		goalPanel();
		say("your goal is " + itemManager.getItemComposition(item).getName() + ": your Outside page shows it, your kill count and how dry you are");
	}

	/** The side tab's goal line, as this account's goal stands (on the client's thread). */
	private void goalPanel()
	{
		if (panel == null)
		{
			return;
		}
		Integer item = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM, Integer.class);
		if (item == null || item <= 0)
		{
			panel.goal(null);
			return;
		}
		boolean got = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT, Long.class) != null;
		panel.goal(itemManager.getItemComposition(item).getName() + (got ? " (got)" : ""));
	}

	private void goalClear()
	{
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT);
		configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT_COUNTS);
		keep("goal", null);
		goalPanel();
		say("your goal's off your Outside page");
	}

	/** The goal set for this account to the keep: the item, its model as the game draws it (its own recolours and
	 *  retextures), and when it was got with the counts then (on the client's thread). */
	private void sendGoal()
	{
		sendGoal(false);
	}

	/** byItself: the game's collection log line sent it, not the player's press, so in the Hub's version it waits for the
	 *  next update (Update my page, or the hourly one), as the bank does: the reviewer's terms, a press or the hourly sync. */
	private void sendGoal(boolean byItself)
	{
		Integer item = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM, Integer.class);
		if (!config.goal() || item == null || item <= 0)
		{
			return;
		}
		net.runelite.api.ItemComposition c = itemManager.getItemComposition(item);
		// (11/18/2026, the first goal on the Hub came without its model: its textures were copied whether it had any or
		// not, and an item with none, most of them, threw there; now each step only as the item needs it, the model sent
		// unlit if lighting it fails, and why it couldn't be read said in the chat)
		JsonObject model = null;
		String why = null;
		net.runelite.api.ModelData md = null;
		try
		{
			md = client.loadModelData(c.getInventoryModel());
			if (md == null)
			{
				why = "the game gave no model for it";
			}
			else
			{
				short[] from = c.getColorToReplace(), to = c.getColorToReplaceWith();
				if (from != null && to != null && from.length > 0)
				{
					md = md.cloneColors();
					for (int i = 0; i < Math.min(from.length, to.length); i++)
					{
						md.recolor(from[i], to[i]);
					}
				}
				short[] tf = c.getTextureToReplace(), tt = c.getTextureToReplaceWith();
				if (tf != null && tt != null && tf.length > 0 && md.getFaceTextures() != null)
				{
					md = md.cloneTextures();
					for (int i = 0; i < Math.min(tf.length, tt.length); i++)
					{
						md.retexture(tf[i], tt[i]);
					}
				}
			}
		}
		catch (RuntimeException ex)
		{
			why = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
			md = null;
		}
		if (md != null)
		{
			try
			{
				Model m = md.light();
				if (m != null)
				{
					model = wholeModel(modelJson(m), null, null, null);
				}
			}
			catch (RuntimeException ex)
			{
				why = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
			}
			if (model == null)
			{
				// (unlit, its own colours: the page lights it)
				try
				{
					model = Kept.model(md.getVerticesX(), md.getVerticesY(), md.getVerticesZ(), md.getVerticesCount(),
						md.getFaceIndices1(), md.getFaceIndices2(), md.getFaceIndices3(), md.getFaceCount(),
						null, null, md.getFaceColors(), md.getFaceTransparencies(), md.getFaceTextures());
				}
				catch (RuntimeException ex)
				{
					why = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
				}
			}
		}
		if (model == null)
		{
			log.debug("outside: the goal's model: {}", why);
			say("your goal goes to your page as its picture: its 3D model couldn't be read (" + (why == null ? "no reason given" : why) + ")");
		}
		Long got = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT, Long.class);
		String then = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT_COUNTS);
		JsonObject countsThen = null;
		try
		{
			countsThen = then == null ? null : gson.fromJson(then, JsonObject.class);
		}
		catch (RuntimeException ex)
		{
			countsThen = null;
		}
		JsonObject body = Goal.body(item, c.getName(), model, got == null ? 0 : got, countsThen);
		if (byItself && HOLD_KEEPS)
		{
			synchronized (keepHeld)
			{
				keepHeld.put("goal", body);
			}
			return;
		}
		keep("goal", body);
	}

	/** The game's collection log line: the goal's, it's got. */
	private void goalLine(String added)
	{
		Integer item = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM, Integer.class);
		if (added != null && item != null && item > 0 && Goal.isIt(added, itemManager.getItemComposition(item).getName()))
		{
			goalGot();
		}
	}

	/** Loot as RuneLite reads it (a kill's, or the Loot Tracker's: a raid's chest, a casket, a minigame's reward): the
	 *  goal among it, it's got (11/18/2026, update 4: the collection log's line shows only the first time, and only
	 *  with its notification on in the game's settings). */
	private void goalLoot(java.util.Collection<net.runelite.client.game.ItemStack> items)
	{
		Integer item = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.ITEM, Integer.class);
		if (items == null || item == null || item <= 0 || !config.goal())
		{
			return;
		}
		for (net.runelite.client.game.ItemStack it : items)
		{
			if (it != null && itemManager.canonicalize(it.getId()) == item)
			{
				goalGot();
				return;
			}
		}
	}

	// (RuneLite's event bus takes a listener only when it's named "on" and its event's name: anything else and the plugin
	// won't start. 11/19/2026: update 4 named these goalOnNpcLoot and goalOnLoot, to keep clear of the field reports'
	// own listener, and the Hub's version wouldn't switch on. One listener now, which hands the reports theirs.)
	@Subscribe
	public void onNpcLootReceived(net.runelite.client.events.NpcLootReceived e)
	{
		goalLoot(e.getItems());
		// hub:cut reports
		reportLoot(e);
		// hub:end reports
	}

	@Subscribe
	public void onLootReceived(net.runelite.client.plugins.loottracker.LootReceived e)
	{
		goalLoot(e.getItems());
	}

	/** The goal's got (once): when, and the counts then, kept with it and sent (in the Hub's version, with the next
	 *  update). */
	private void goalGot()
	{
		if (configManager.getRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT, Long.class) != null)
		{
			return;
		}
		configManager.setRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT, System.currentTimeMillis());
		configManager.setRSProfileConfiguration(OutsideConfig.GROUP, Goal.GOT_COUNTS, gson.toJson(counts.json()));
		sendGoal(true);
		goalPanel();
		say(HOLD_KEEPS ? "your goal's got: your Outside page says so with your next update" : "your goal's got: your Outside page says so");
	}
	// hub:end goal

	private OutsidePanel.Hooks panelHooks()
	{
		return new OutsidePanel.Hooks()
		{
			// hub:cut goal
			@Override
			public void setGoal()
			{
				goalPick();
			}

			@Override
			public void clearGoal()
			{
				clientThread.invokeLater(() -> goalClear());
			}
			// hub:end goal

			// hub:cut model
			@Override
			public void capture(String name)
			{
				clientThread.invokeLater(() ->
				{
					if (!config.model())
					{
						panel.status("switch on \"Character model\" in the plugin's settings first");
						return;
					}
					if (client.getLocalPlayer() == null)
					{
						panel.status("log in first");
						return;
					}
					String n = name.isEmpty() ? null : motionName(name.split("\\s+"));
					if (!name.isEmpty() && n == null)
					{
						panel.status("a name: up to 24 letters, numbers, spaces, - or _");
						return;
					}
					armMotion(n);
				});
			}
			// hub:end model

			@Override
			public void refresh()
			{
				// hub:cut model
				readMotions();
				// hub:end model
				// hub:cut talk
				readEmotes();
				// hub:end talk
				readProof();
			}

			// hub:cut model
			@Override
			public void first(String name)
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				String url = a.keepUrl("motions") + "&first=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
				http.newCall(new Request.Builder().url(url).header("User-Agent", "outside-plugin/" + VERSION).post(RequestBody.create(JSON, "")).build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.status("the relay's out of reach");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						r.close();
						panel.status(name.isEmpty() ? "your page shows your model standing still first" : "your page shows \"" + name + "\" first");
					}
				});
			}
			// hub:end model

			// hub:cut model
			@Override
			public void forget(String name)
			{
				keep("motion", name, null);
			}
			// hub:end model

			// hub:cut model
			@Override
			public void captureStill()
			{
				clientThread.invokeLater(() ->
				{
					if (!config.model())
					{
						sayModel("switch on \"Character model\" in the plugin's settings first");
						return;
					}
					if (client.getLocalPlayer() == null)
					{
						panel.status("log in first");
						return;
					}
					sendStill();
				});
			}

			@Override
			public void removeModel()
			{
				configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET);
				keep("model", null);
				panel.status("taking your model off your page");
			}

			@Override
			public boolean ground()
			{
				return config.ground();
			}

			@Override
			public void setGround(boolean on)
			{
				configManager.setConfiguration(OutsideConfig.GROUP, "ground", on);
			}
			// hub:end model

			// hub:cut year
			@Override
			public void openYear()
			{
				openMyYear();
			}
			// hub:end year

			@Override
			public void claim()
			{
				clientThread.invokeLater(() ->
				{
					if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
					{
						panel.claiming(false, "log in first: your page is named after the character you're logged in as");
						return;
					}
					String shown = client.getLocalPlayer().getName();
					String name = channelName(shown);
					if (name == null)
					{
						panel.claiming(false, "\"" + shown + "\" can't be a page's name");
						return;
					}
					long hash = client.getAccountHash();
					claimPage(name, hash == -1 ? null : Long.toString(hash));
				});
			}

			// hub:cut talk
			@Override
			public void addEmote(String name)
			{
				Attach a = key();
				byte[] b = Emotes.fromClipboard();
				String no = a == null ? "no page for this character yet" : b == null ? "copy a picture first, then paste it here" : Emotes.refuse(name, b);
				if (no != null)
				{
					panel.emoteStatus(no, false);
					return;
				}
				panel.emoteStatus("sending :" + name + ":…", false);
				http.newCall(new Request.Builder().url(a.emotesUrl(name)).header("User-Agent", "outside-plugin/" + VERSION)
					.post(RequestBody.create(MediaType.parse("image/" + ("jpg".equals(Emotes.kind(b)) ? "jpeg" : Emotes.kind(b))), b)).build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.emoteStatus("the relay's out of reach", false);
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						int code = r.code();
						r.close();
						panel.emoteStatus(code / 100 == 2 ? ":" + name + ": is on your page" : code == 429 ? "too many uploads this hour: try later"
							: code == 413 ? "too big, or your page holds 30 already" : "the relay didn't take it (" + code + ")", code / 100 == 2);
						readEmotes();
					}
				});
			}
			// hub:end talk

			// hub:cut talk
			@Override
			public void removeEmote(String name)
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				http.newCall(new Request.Builder().url(a.emoteUrl(name)).header("User-Agent", "outside-plugin/" + VERSION).delete().build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.emoteStatus("the relay's out of reach", false);
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						r.close();
						panel.emoteStatus(":" + name + ": is off your page", false);
						readEmotes();
					}
				});
			}
			// hub:end talk

			// hub:cut live
			@Override
			public void copyKey()
			{
				Attach a = key();
				if (a == null)
				{
					panel.keyStatus("no key for this character yet");
					return;
				}
				try
				{
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(stationKey().trim()), null);
					panel.keyStatus("copied: paste it into the outstation's first screen");
				}
				catch (IllegalStateException | java.awt.HeadlessException ex)
				{
					panel.keyStatus("the clipboard's busy: try again");
				}
			}
			// hub:end live

			@Override
			public void update()
			{
				updateNow();
			}

			@Override
			public void showSent()
			{
				OutsidePlugin.this.showSent();
			}

			@Override
			public void copyDink()
			{
				Attach a = key();
				if (a == null)
				{
					panel.dinkStatus("make your page first");
					return;
				}
				try
				{
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(a.dinkAddress()), null);
					panel.dinkStatus("copied: paste it into Dink's Primary Webhook URLs, on its own line");
				}
				catch (IllegalStateException | java.awt.HeadlessException ex)
				{
					panel.dinkStatus("the clipboard's busy: try again");
				}
			}

			@Override
			public void aetherAsMe()
			{
				Attach a = key();
				if (a == null)
				{
					say("log in on a character with a page first");
					return;
				}
				net.runelite.client.util.LinkBrowser.browse(a.aetherPage(friendsOnly ? watchCode(false) : null));
			}

			@Override
			public void openPage()
			{
				Attach a = key();
				if (a != null)
				{
					net.runelite.client.util.LinkBrowser.browse(a.page(friendsOnly ? watchCode(false) : null));
				}
			}

			@Override
			public void friendsOnly(boolean on)
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				String code = on ? watchCode(true) : "";
				JsonObject body = new JsonObject();
				body.addProperty("code", code);
				panel.accessStatus(on ? "making it friends only…" : "opening it to anyone…");
				http.newCall(new Request.Builder().url(a.codeUrl())
					.header("User-Agent", "outside-plugin/" + VERSION).put(RequestBody.create(JSON, gson.toJson(body))).build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.accessStatus("the relay's out of reach: try again");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						try (Response res = r)
						{
							if (res.code() != 200)
							{
								panel.accessStatus("the relay said no (" + res.code() + ")");
								return;
							}
							friendsOnly = on;
							panel.access(on);
							panel.accessStatus(on ? "friends only now: copy the friends' link for them" : "open to anyone now");
						}
					}
				});
			}

			@Override
			public void copyFriendsLink()
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				try
				{
					java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(a.page(watchCode(true))), null);
					panel.accessStatus("copied: give it only to friends");
				}
				catch (IllegalStateException | java.awt.HeadlessException ex)
				{
					panel.accessStatus("the clipboard's busy: try again");
				}
			}

			@Override
			public void takeDown()
			{
				Attach a = key();
				if (a == null)
				{
					return;
				}
				panel.accessStatus("taking it down…");
				http.newCall(new Request.Builder().url(a.takeDownUrl())
					.header("User-Agent", "outside-plugin/" + VERSION).delete().build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.accessStatus("the relay's out of reach: nothing was taken down");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						try (Response res = r)
						{
							if (res.code() != 204)
							{
								panel.accessStatus("the relay said no (" + res.code() + "): nothing was taken down");
								return;
							}
						}
						// (the key's no good now: forgotten here too, and in the settings if it was this page's)
						String gone = stationKey();
						configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, "stationKey");
						configManager.unsetRSProfileConfiguration(OutsideConfig.GROUP, "watchCode");
						if (gone != null && gone.trim().equals(config.stationKey().trim()))
						{
							configManager.unsetConfiguration(OutsideConfig.GROUP, "stationKey");
						}
						friendsOnly = false;
						posted = false;   // (nothing more to say to a page that's gone)
						// hub:cut live
						close("page taken down");
						// hub:end live
						panel.accessStatus(null);
						panel.who(null);
						say("your Outside page is taken down");
					}
				});
			}

			/** "Tend my page's chat" (11/16/2026; docs/protocol.md "Tending the page's chat from the page"): a token made
			 *  here, handed to the relay with the page's key, and the page opened with it in the fragment. */
			@Override
			public void tendChat()
			{
				Attach a = key();
				if (a == null)
				{
					panel.tendStatus("log in on a character with a page first");
					return;
				}
				String token = Tend.token(new java.security.SecureRandom());
				panel.tendStatus("opening your page's register…");
				http.newCall(new Request.Builder().url(a.tendUrl())
					.header("User-Agent", "outside-plugin/" + VERSION)
					.post(RequestBody.create(JSON, Tend.body(token, gson))).build()).enqueue(new Callback()
				{
					@Override
					public void onFailure(Call call, IOException ex)
					{
						panel.tendStatus("the relay's out of reach: try again in a moment");
					}

					@Override
					public void onResponse(Call call, Response r)
					{
						try (Response res = r)
						{
							if (res.code() != 200)
							{
								panel.tendStatus("the relay said no (" + res.code() + ")");
								return;
							}
						}
						panel.tendStatus("opened in your browser: each visitor's note has take back and ban");
						// (read: what's there now isn't new any more)
						if (notesLast > 0)
						{
							configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "registerSeen", Long.toString(notesLast));
						}
						panel.notes(0);
						net.runelite.client.util.LinkBrowser.browse(a.tendPage(token));
					}
				});
			}
		};
	}

	/** Whether this character's page is friends only, as last read (readAccess) or set here. */
	private volatile boolean friendsOnly;

	/** This character's watch code, kept with their account; made the first time it's asked for (make), else "". */
	private String watchCode(boolean make)
	{
		String c = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "watchCode");
		if ((c == null || c.isEmpty()) && make)
		{
			byte[] b = new byte[9];
			new java.security.SecureRandom().nextBytes(b);
			c = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "watchCode", c);
		}
		return c == null ? "" : c;
	}

	/** Who sees the page, read as anyone would: the kept game refused without a code is friends only (an outstation
	 *  transmitting sets its own watch code, so this reads what's true now, not what was set here). */
	private void readAccess()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			return;
		}
		http.newCall(new Request.Builder().url(a.gameReadUrl()).header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					friendsOnly = res.code() == 403;
					panel.access(friendsOnly);
				}
			}
		});
	}

	/** The station key for the character logged in: their own (kept with their account by "Make my Outside page"), else
	 *  the one in the plugin's settings. */
	private String stationKey()
	{
		String own = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "stationKey");
		// (11/17/2026, the review before the outstation's v0.2.0: a key pasted in the settings, for the character
		// logged in, wins over the one Make my Outside page kept with the account, so a player who made a page here
		// first and was issued an outstation later can put its key in Advanced, as the outstation's setup says)
		String pasted = config.stationKey();
		if (pasted != null && !pasted.trim().isEmpty())
		{
			Attach p = Attach.parse(pasted, gson);
			String c = character;
			if (p != null && (c == null || isOwn(p.getChannel(), c)))
			{
				return pasted;
			}
		}
		return own != null && !own.trim().isEmpty() ? own : pasted;
	}

	/** The key read, or null: none, or one whose page isn't the character logged in (another account's). */
	private Attach key()
	{
		Attach a = Attach.parse(stationKey(), gson);
		String c = character;
		return a != null && c != null && !isOwn(a.getChannel(), c) ? null : a;
	}

	/** Another character logged in (or the first, once its name is known): the side tab, and the relay on their key. */
	private void characterChanged()
	{
		progressAt = -1;
		questsSeen = null;
		slayerKey = null;
		slayerSeen = null;
		Attach any = Attach.parse(stationKey(), gson), a = key();
		// (a page under the name's old form: moved to the name as it is, once a session)
		if (a != null && character != null && !a.getChannel().equalsIgnoreCase(character) && !movedThisSession)
		{
			movedThisSession = true;
			long hash = client.getAccountHash();
			movePage(a, character, hash == -1 ? null : Long.toString(hash));
		}
		if (panel != null)
		{
			panel.who(a == null ? null : a.getChannel());
			panel.otherKey(any != null && a == null ? any.getChannel() : null, character);
			// hub:cut model
			readMotions();
			// hub:end model
			// hub:cut talk
			readEmotes();
			// hub:end talk
			readProof();
			readAccess();
		}
		stopped = false;
		snapAt = 0;
		showSnap();
		// hub:cut live
		if (attach != null && (a == null || !a.getChannel().equals(attach.getChannel())))
		{
			close("another character");
		}
		toldConnected = false;
		connect();
		// hub:end live
	}

	/** A player's name as a channel has it (since 11/16/2026, Jace: "a space and underscored name are the same name in
	 *  practice, in the game. oranberries and oran berries/oran_berries are not the same"): lower case, each run of the
	 *  spaces, hyphens and underscores the game treats alike one "_" (its names use a no-break space), none at either
	 *  end, 1 to 12 long; null when it can't be one. "Oran Berries" is oran_berries; "OranBerries", oranberries. */
	static String channelName(String shown)
	{
		if (shown == null)
		{
			return null;
		}
		String n = shown.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\u00a0_-]+", "_").replaceAll("^_+|_+$", "");
		return n.length() <= 12 && n.matches("[a-z0-9]+(_[a-z0-9]+)*") ? n : null;
	}

	/** Whether a key's channel is this character's: its own form, or a page made before 11/16/2026 (no "_" in it, the
	 *  name's spaces taken out), which stays theirs till it's moved (movePage). */
	static boolean isOwn(String keyChannel, String character)
	{
		if (keyChannel == null || character == null)
		{
			return false;
		}
		String k = keyChannel.toLowerCase(java.util.Locale.ROOT);
		return k.equals(character) || (k.indexOf('_') < 0 && k.equals(character.replace("_", "")));
	}

	/** A page made before 11/16/2026 under the name with its spaces taken out (outside.wiki/oranberries for Oran Berries)
	 *  moved to the name as it is (oran_berries): a page claimed under it (a new key, unproven till the next logout's
	 *  look, as any claim), the old one taken down with the old key once the new is theirs. Once a session; a name
	 *  someone else has proven or was vouched (409), or the relay out of reach, leaves the old page as it is. */
	private void movePage(Attach old, String name, String account)
	{
		JsonObject body = new JsonObject();
		body.addProperty("player", name);
		if (account != null)
		{
			body.addProperty("account", account);
		}
		http.newCall(new Request.Builder().url(HOME_RELAY + "/claim").header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, gson.toJson(body))).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					JsonObject o = null;
					try
					{
						o = gson.fromJson(res.body() != null ? res.body().string() : "", JsonObject.class);
					}
					catch (RuntimeException ignored)
					{
					}
					if (res.code() != 200 || o == null || !o.has("code") || Attach.parse(o.get("code").getAsString(), gson) == null
						|| (o.has("proof") && "challenging".equals(o.get("proof").getAsString())))
					{
						return;
					}
					String gone = stationKey();
					configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "stationKey", o.get("code").getAsString());
					if (gone != null && gone.trim().equals(config.stationKey().trim()))
					{
						configManager.unsetConfiguration(OutsideConfig.GROUP, "stationKey");
					}
					syncDue = true;
					say("your Outside page moved to Outside.wiki/" + name + " (your name as the game has it)");
					http.newCall(new Request.Builder().url(old.takeDownUrl()).header("User-Agent", "outside-plugin/" + VERSION)
						.delete().build()).enqueue(new Callback()
					{
						@Override
						public void onFailure(Call call, IOException ex)
						{
						}

						@Override
						public void onResponse(Call call, Response r2)
						{
							r2.close();
						}
					});
				}
				catch (IOException ignored)
				{
				}
			}
		});
	}

	/** Claims a page for the name (docs/protocol.md "Claiming a name"): the key that comes back goes in the settings,
	 *  which connects as any key does (onConfigChanged). */
	private void claimPage(String name, String account)
	{
		panel.claiming(true, null);
		JsonObject body = new JsonObject();
		body.addProperty("player", name);
		if (account != null)
		{
			body.addProperty("account", account);
		}
		Request req = new Request.Builder().url(HOME_RELAY + "/claim").header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, gson.toJson(body))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.claiming(false, "the relay's out of reach: try again in a bit");
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					String text = res.body() != null ? res.body().string() : "";
					JsonObject o = null;
					try
					{
						o = gson.fromJson(text, JsonObject.class);
					}
					catch (RuntimeException ignored)
					{
					}
					if (res.code() == 200 && o != null && o.has("code") && Attach.parse(o.get("code").getAsString(), gson) != null)
					{
						boolean back = o.has("proof") && "challenging".equals(o.get("proof").getAsString());
						challenging = back;
						configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "stationKey", o.get("code").getAsString());
						// (its first snapshot, on the next tick somewhere safe: they asked for their page)
						syncDue = true;
						if (back)
						{
							// (taking a name back: someone else claimed it first and hasn't proven it)
							panel.claiming(false, "Outside.wiki/" + name + " was claimed by someone who hasn't proven it. Gain any experience,"
								+ " press Update my page, wait a minute, then log out: when the official hiscores show it, the page is yours.");
							say("you're taking back Outside.wiki/" + name + ": gain any experience, press Update my page, wait a minute, then log out");
						}
						else
						{
							panel.claiming(false, "your page: Outside.wiki/" + name + " (marked unproven till it's proven yours)");
							say("your Outside page is made: Outside.wiki/" + name);
						}
						return;
					}
					if (res.code() == 409)
					{
						panel.claiming(false, "Outside.wiki/" + name + " is proven or vouched for already. If it's yours, paste its station key in the plugin's settings.");
						return;
					}
					String why = o != null && o.has("error") ? o.get("error").getAsString() : "the relay said " + res.code();
					panel.claiming(false, why);
				}
				catch (IOException ex)
				{
					panel.claiming(false, "the relay's out of reach: try again in a bit");
				}
			}
		});
	}

	/** What the relay said of the page's proof, into the side tab: from a read of it, or an update's own answer (`sentXp`:
	 *  the total experience that update carried, -1 for a read). A "ready" is remembered with it, so experience gained
	 *  after it can be noticed (onStatChanged). Skills switched off, nothing can prove it: said so. */
	private void proofSaid(String proof, String why, long sentXp)
	{
		proofState = proof;
		challenging = "challenging".equals(proof);
		boolean open = "unproven".equals(proof) || challenging;
		if (!open || why == null || !why.startsWith("ready"))
		{
			readyXp = -1;
		}
		else if (sentXp > 0)
		{
			readyXp = sentXp;
			readyStale = false;
		}
		if (open && !config.skills())
		{
			why = "Skills is switched off in the plugin's settings (On your page): proving needs it on, as it's your"
				+ " experience the official hiscores confirm.";
		}
		else if (open && readyStale && readyXp > 0)
		{
			why = STALE;
		}
		if (panel != null)
		{
			panel.proof(proof, why);
		}
	}

	private static final String STALE = "You've gained experience since your page said ready: once you've stopped, press"
		+ " Update my page again, then log out when this says ready.";

	/** Whether their page is proven theirs, into the side tab (docs/protocol.md "Proving a name"). */
	private void readProof()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			if (panel != null)
			{
				panel.proof(null, null);
			}
			return;
		}
		String seen = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "registerSeen");
		http.newCall(new Request.Builder().url(a.proofUrl() + "?key=" + java.net.URLEncoder.encode(a.getKey(), java.nio.charset.StandardCharsets.UTF_8)
			+ "&seen=" + (seen != null && seen.matches("\\d{1,12}") ? seen : "0"))
			.header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try (Response res = r)
				{
					JsonObject o = res.code() == 200 && res.body() != null ? gson.fromJson(res.body().string(), JsonObject.class) : null;
					String proof = o != null && o.has("proof") ? o.get("proof").getAsString() : null;
					proofSaid(proof, o != null && o.has("why") ? o.get("why").getAsString() : null, -1);
					// (the register's notes not read here yet, 11/17/2026: the side tab's line, and once a login a line in
					// the chat box)
					if (o != null && o.has("notes") && o.get("notes").isJsonObject())
					{
						JsonObject n = o.getAsJsonObject("notes");
						int count = n.has("n") ? n.get("n").getAsInt() : 0;
						notesLast = n.has("last") ? n.get("last").getAsLong() : 0;
						panel.notes(count);
						if (count > 0 && !notesSaid)
						{
							notesSaid = true;
							say(count == 1 ? "1 new note in your Outside page's register (the side tab's Page tab)"
								: count + " new notes in your Outside page's register (the side tab's Page tab)");
						}
					}
				}
				catch (IOException | RuntimeException ignored)
				{
				}
			}
		});
	}

	// hub:cut talk
	/** The motions on their page and the one it shows first, into the side tab. */
	/** The page's emotes, for the side tab (GET /emotes/<channel>, as anyone reads them). */
	private void readEmotes()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			return;
		}
		http.newCall(new Request.Builder().url(a.emotesUrl(null)).header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.showEmotes(null);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try
				{
					JsonObject o = gson.fromJson(r.body() == null ? "" : r.body().string(), JsonObject.class);
					List<String> names = new ArrayList<>();
					if (o != null && o.has("emotes"))
					{
						for (JsonElement e : o.getAsJsonArray("emotes"))
						{
							names.add(e.getAsJsonObject().get("name").getAsString());
						}
					}
					panel.showEmotes(names);
				}
				catch (IOException | RuntimeException ex)
				{
					panel.showEmotes(null);
				}
				finally
				{
					r.close();
				}
			}
		});
	}
	// hub:end talk

	// hub:cut model
	private void readMotions()
	{
		Attach a = key();
		if (a == null || panel == null)
		{
			return;
		}
		String url = a.keepUrl("motions").replaceFirst("\\?key=[^&]*", "?code=");
		http.newCall(new Request.Builder().url(url).header("User-Agent", "outside-plugin/" + VERSION).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				panel.showMotions(null, "");
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				try
				{
					String t = r.body() == null ? "" : r.body().string();
					JsonObject o = gson.fromJson(t, JsonObject.class);
					List<OutsidePanel.Motion> list = new ArrayList<>();
					if (o != null && o.has("motions"))
					{
						for (JsonElement e : o.getAsJsonArray("motions"))
						{
							JsonObject m = e.getAsJsonObject();
							list.add(new OutsidePanel.Motion(m.get("name").getAsString(), m.has("frames") ? m.get("frames").getAsInt() : 0));
						}
					}
					panel.showMotions(list, o != null && o.has("first") && !o.get("first").isJsonNull() ? o.get("first").getAsString() : (list.stream().anyMatch(m -> "idle".equals(m.name)) ? "idle" : ""));
				}
				catch (IOException | RuntimeException ex)
				{
					panel.showMotions(null, "");
				}
				finally
				{
					r.close();
				}
			}
		});
	}

	/** The account's own still, set (Capture a still, ::outsidemodel): a capture no longer replaces it. */
	static final String STILL_SET = "stillSet";

	/** Capture a still (11/18/2026, update 4, Cranky): the model as it is the moment it's pressed, mid-emote too, as the
	 *  page's still, kept until another still or the model's taken off. */
	private void sendStill()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getModel() == null)
		{
			sayModel("your model isn't there to send yet: try Capture a still in a moment");
			return;
		}
		configManager.setRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET, true);
		sendModel();
	}

	private void sendModel()
	{
		Player me = client.getLocalPlayer();
		Model m = me == null ? null : me.getModel();
		if (m == null)
		{
			sayModel("your model isn't there to send yet: try Capture a still in a moment");
			return;
		}
		// (each written out the moment it's read: the client hands out one shared model for anything animated, so reading
		// the pet's after the player's overwrote the player's; 11/15/2026, Jace saw only his pet)
		Snap snap = snap(m, true);
		// (11/15/2026: their effects as they are now, each a model of its own, and the ground round them if they let it go)
		List<Fx> fx = effects(me, null);
		JsonObject player = Kept.trim(snap.json, ownOf(snap, fx));
		addEffects(player, fx);
		addGround(player, me);
		Model pet = petModel();
		keep("model", wholeModel(player, pet == null ? null : modelJson(pet), null, null));
	}

	/** The pet following them (11/15/2026, Jace: "one thing i would like to have it do is capture the pet"), or null. */
	private Model petModel()
	{
		NPC pet = client.getFollower();
		return pet == null ? null : pet.getModel();
	}

	// ---- effects and the ground (11/15/2026, Jace: "can you build the effects (spot animations) and the ground into the
	// model viewer/capturer?"). Effects: each spot animation on them (a special attack's, a teleport's, an emote's), its
	// own model raised by its height, kept apart from theirs (Kept.ownVertices: the client may merge them into the
	// player's model), so the page can draw them, and a motion's frames carry them frame by frame. The ground: the tiles
	// round them (GROUND_TILES each way) as the scene has them, painted and shaped, turned into their model's own space;
	// only with "Ground under my model" on, and only on a capture they ask for (never the live view's own).

	private static final int GROUND_TILES = 5, FX_MOST = 4, FX_MODELS = 8;

	/** A model read at once (the client's model object is shared): its vertices copied, and as JSON if asked. */
	private static final class Snap
	{
		JsonObject json;
		float[] x, z, y;
		int n;
	}

	private Snap snap(Model m, boolean json)
	{
		Snap s = new Snap();
		s.n = m.getVerticesCount();
		s.x = java.util.Arrays.copyOf(m.getVerticesX(), s.n);
		s.y = java.util.Arrays.copyOf(m.getVerticesY(), s.n);
		s.z = java.util.Arrays.copyOf(m.getVerticesZ(), s.n);
		s.json = json ? modelJson(m) : null;
		return s;
	}

	/** One effect on them now: its key (id, vertices, faces), its vertices (at its height, as RuneLite has them), and
	 *  its model as JSON unless it's one already known. */
	private static final class Fx
	{
		String key;
		JsonObject json;
		float[] x, y, z;
		int n;
	}

	private List<Fx> effects(Player me, Set<String> known)
	{
		List<Fx> out = new ArrayList<>();
		if (me.getSpotAnims() == null)
		{
			return out;
		}
		for (ActorSpotAnim sa : me.getSpotAnims())
		{
			if (out.size() >= FX_MOST)
			{
				break;
			}
			Model m = sa.getModel();
			if (m == null || m.getVerticesCount() <= 0 || m.getFaceCount() <= 0)
			{
				continue;
			}
			Fx f = new Fx();
			f.n = m.getVerticesCount();
			f.key = sa.getId() + "." + f.n + "." + m.getFaceCount();
			f.x = java.util.Arrays.copyOf(m.getVerticesX(), f.n);
			f.y = java.util.Arrays.copyOf(m.getVerticesY(), f.n);
			f.z = java.util.Arrays.copyOf(m.getVerticesZ(), f.n);
			// (11/19/2026: taken as RuneLite hands it over, already at its height. Raised again by sa.getHeight(), every
			// effect sat one height too high: a friend's arrow drawn from his bow came out 96 over it, at the top of his
			// head (Jace: "my friends SalutCFlagg's arrow its flying above his bow, make it look right"), and Ja's
			// casting at 308, over his head, not at his chest)
			if (known == null || !known.contains(f.key))
			{
				f.json = Kept.model(f.x, f.y, f.z, f.n, m.getFaceIndices1(), m.getFaceIndices2(), m.getFaceIndices3(), m.getFaceCount(),
					m.getFaceColors1(), m.getFaceColors2(), m.getFaceColors3(), m.getUnlitFaceColors(), m.getFaceTransparencies(),
					m.getFaceTextures(), m.getFaceRenderPriorities(), m.getTextureFaces(), m.getTexIndices1(), m.getTexIndices2(),
					m.getTexIndices3());
			}
			out.add(f);
		}
		return out;
	}

	/** How many of the snapped model's vertices are the player's own (the effects merged after them, in either order). */
	private static int ownOf(Snap s, List<Fx> fx)
	{
		if (fx == null || fx.isEmpty())
		{
			return s.n;
		}
		for (int pass = 0; pass < 2; pass++)
		{
			List<Fx> l = new ArrayList<>(fx);
			if (pass == 1)
			{
				java.util.Collections.reverse(l);
			}
			List<float[]> xs = new ArrayList<>(), zs = new ArrayList<>();
			int[] counts = new int[l.size()];
			for (int k = 0; k < l.size(); k++)
			{
				xs.add(l.get(k).x);
				zs.add(l.get(k).z);
				counts[k] = l.get(k).n;
			}
			int own = Kept.ownVertices(s.x, s.z, s.n, xs, zs, counts);
			if (own != s.n)
			{
				return own;
			}
		}
		return s.n;
	}

	/** The still capture's effects: "fx", each a model. */
	private static void addEffects(JsonObject o, List<Fx> fx)
	{
		JsonArray a = new JsonArray();
		for (Fx f : fx)
		{
			if (f.json != null)
			{
				a.add(f.json);
			}
		}
		if (a.size() > 0)
		{
			o.add("fx", a);
		}
	}

	/** A motion's effects, frame by frame: each effect's model once ("m", by its key), then each frame's effects there,
	 *  their key and their vertices then ("f", one list a frame). */
	private static final class FxTrack
	{
		final Map<String, JsonObject> models = new LinkedHashMap<>();
		final List<JsonArray> rows = new ArrayList<>();

		void add(List<Fx> fx)
		{
			JsonArray row = new JsonArray();
			for (Fx f : fx)
			{
				if (!models.containsKey(f.key))
				{
					if (f.json == null || models.size() >= FX_MODELS)
					{
						continue;
					}
					models.put(f.key, f.json);
				}
				JsonObject e = new JsonObject();
				e.addProperty("k", f.key);
				e.addProperty("v", Kept.frame(f.x, f.y, f.z, f.n));
				row.add(e);
			}
			rows.add(row);
		}

		void into(JsonObject frames)
		{
			if (models.isEmpty())
			{
				return;
			}
			JsonObject fx = new JsonObject(), m = new JsonObject();
			models.forEach(m::add);
			JsonArray f = new JsonArray();
			rows.forEach(f::add);
			fx.add("m", m);
			fx.add("f", f);
			frames.add("fx", fx);
		}
	}

	/** The ground round them, with "Ground under my model" on: "ground", a model in theirs's own space. */
	private void addGround(JsonObject o, Player me)
	{
		if (!config.ground())
		{
			return;
		}
		try
		{
			JsonObject g = groundJson(me);
			if (g != null && g.getAsJsonArray("f").size() > 0)
			{
				o.add("ground", g);
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("outside: the ground", ex);
		}
	}

	private JsonObject groundJson(Player me)
	{
		WorldView wv = me.getWorldView();
		LocalPoint lp = me.getLocalLocation();
		if (wv == null || lp == null)
		{
			return null;
		}
		Scene scene = wv.getScene();
		int plane = wv.getPlane();
		Tile[][][] tiles = scene.getTiles();
		int[][][] hs = wv.getTileHeights();
		int px = lp.getX(), py = lp.getY(), ph = Perspective.getTileHeight(client, lp, plane);
		// (the client turns a model by its orientation as it draws it (Perspective.modelToCanvas): x' = x cos + z sin,
		// z' = z cos - x sin; the ground's turned back the other way into the model's own space)
		double ang = me.getOrientation() * Math.PI / 1024.0, cs = Math.cos(ang), sn = Math.sin(ang);
		int sx0 = lp.getSceneX(), sy0 = lp.getSceneY();
		List<int[]> tris = new ArrayList<>();
		for (int dx = -GROUND_TILES; dx <= GROUND_TILES; dx++)
		{
			for (int dy = -GROUND_TILES; dy <= GROUND_TILES; dy++)
			{
				int x = sx0 + dx, y = sy0 + dy;
				if (x < 0 || y < 0 || x >= tiles[plane].length - 1 || y >= tiles[plane][x].length - 1)
				{
					continue;
				}
				Tile t = tiles[plane][x][y];
				if (t == null)
				{
					continue;
				}
				int bx = x * 128, by = y * 128;
				SceneTilePaint p = t.getSceneTilePaint();
				if (p != null)
				{
					int tex = p.getTexture();
					int[] sw = {bx, hs[plane][x][y], by}, se = {bx + 128, hs[plane][x + 1][y], by};
					int[] ne = {bx + 128, hs[plane][x + 1][y + 1], by + 128}, nw = {bx, hs[plane][x][y + 1], by + 128};
					tris.add(tri(ne, nw, se, p.getNeColor(), p.getNwColor(), p.getSeColor(), tex, bx, by, px, py, ph, cs, sn));
					tris.add(tri(sw, se, nw, p.getSwColor(), p.getSeColor(), p.getNwColor(), tex, bx, by, px, py, ph, cs, sn));
				}
				SceneTileModel tm = t.getSceneTileModel();
				if (tm != null && tm.getFaceX() != null)
				{
					int[] fa = tm.getFaceX(), fb = tm.getFaceY(), fc = tm.getFaceZ(), vx = tm.getVertexX(), vy = tm.getVertexY(), vz = tm.getVertexZ();
					int[] ca = tm.getTriangleColorA(), cb = tm.getTriangleColorB(), cc = tm.getTriangleColorC(), tt = tm.getTriangleTextureId();
					for (int i = 0; i < fa.length; i++)
					{
						int[] a = {vx[fa[i]], vy[fa[i]], vz[fa[i]]}, b = {vx[fb[i]], vy[fb[i]], vz[fb[i]]}, c = {vx[fc[i]], vy[fc[i]], vz[fc[i]]};
						tris.add(tri(a, b, c, ca[i], cb[i], cc[i], tt == null ? -1 : tt[i], bx, by, px, py, ph, cs, sn));
					}
				}
			}
		}
		return Kept.ground(tris);
	}

	/** One of the ground's triangles as Kept.ground takes it: each corner (x, height, the scene's y) moved into the
	 *  player's model space, its colours, its texture and each corner's place on its tile as u, v. */
	private static int[] tri(int[] a, int[] b, int[] c, int ca, int cb, int cc, int tex, int bx, int by, int px, int py, int ph, double cs, double sn)
	{
		int[] q = new int[19];
		int[][] k = {a, b, c};
		for (int i = 0; i < 3; i++)
		{
			double dx = k[i][0] - px, dz = k[i][2] - py;
			q[i * 3] = (int) Math.round(dx * cs - dz * sn);
			q[i * 3 + 1] = k[i][1] - ph;
			q[i * 3 + 2] = (int) Math.round(dz * cs + dx * sn);
			q[13 + i * 2] = Math.round((k[i][0] - bx) / 128f * 4096);
			q[14 + i * 2] = Math.round((k[i][2] - by) / 128f * 4096);
		}
		q[9] = ca;
		q[10] = cb;
		q[11] = cc;
		q[12] = tex;
		return q;
	}

	private JsonObject modelJson(Model m)
	{
		// (11/15/2026: the game's own lighting and textures too, for the page to draw it as the game does)
		return Kept.model(m.getVerticesX(), m.getVerticesY(), m.getVerticesZ(), m.getVerticesCount(),
			m.getFaceIndices1(), m.getFaceIndices2(), m.getFaceIndices3(), m.getFaceCount(),
			m.getFaceColors1(), m.getFaceColors2(), m.getFaceColors3(), m.getUnlitFaceColors(), m.getFaceTransparencies(),
			m.getFaceTextures(), m.getFaceRenderPriorities(), m.getTextureFaces(), m.getTexIndices1(), m.getTexIndices2(),
			m.getTexIndices3());
	}

	/** The player's model, their pet's beside it (if one's following), each with the frames of a motion if one was
	 *  recorded, every texture either uses and the brightness the player has the game at (the palette's). */
	private JsonObject wholeModel(JsonObject o, JsonObject petJson, JsonObject frames, JsonObject petFrames)
	{
		JsonObject pet = petJson;
		if (frames != null)
		{
			o.add("frames", frames);
		}
		if (pet != null)
		{
			JsonObject p = pet;
			if (petFrames != null)
			{
				p.add("frames", petFrames);
			}
			o.add("pet", p);
		}
		// each texture it uses, as the client has it (128 x 128, 0 a see-through texel), a PNG each
		JsonObject pics = new JsonObject();
		List<JsonObject> parts = new ArrayList<>();
		parts.add(o);
		if (pet != null)
		{
			parts.add(o.getAsJsonObject("pet"));
		}
		// (11/15/2026: the effects' and the ground's textures too)
		if (o.has("ground"))
		{
			parts.add(o.getAsJsonObject("ground"));
		}
		if (o.has("fx"))
		{
			o.getAsJsonArray("fx").forEach(e -> parts.add(e.getAsJsonObject()));
		}
		if (o.has("frames") && o.getAsJsonObject("frames").has("fx"))
		{
			o.getAsJsonObject("frames").getAsJsonObject("fx").getAsJsonObject("m").entrySet().forEach(e -> parts.add(e.getValue().getAsJsonObject()));
		}
		for (JsonObject part : parts)
		{
			if (!part.has("t") || client.getTextureProvider() == null)
			{
				continue;
			}
			for (JsonElement e : part.getAsJsonArray("t"))
			{
				int id = e.getAsInt();
				if (id < 0 || pics.has(String.valueOf(id)) || pics.size() >= 16)
				{
					continue;
				}
				String png = texturePng(id);
				if (png != null)
				{
					pics.addProperty(String.valueOf(id), png);
				}
			}
		}
		if (pics.size() > 0)
		{
			o.add("tex", pics);
		}
		if (client.getTextureProvider() != null)
		{
			o.addProperty("b", Math.round(client.getTextureProvider().getBrightness() * 1000) / 1000.0);
		}
		return o;
	}

	// ---- the motion loop (11/15/2026, Jace: "build the pet and the motion loop"): ::outsidemotion gets ready, then
	// records the next animation they do (an emote, a special attack), from its start to its end, at most MOTION_MOST
	// frames; nothing begun within MOTION_WAIT, it records their stance standing (its breathing) for MOTION_IDLE. A frame
	// every MOTION_EVERY client cycles (20 ms each: 12.5 frames a second), the pet's with the player's.
	private static final int MOTION_EVERY = 4, MOTION_MOST = 50, MOTION_WAIT = 400, MOTION_IDLE = 150;
	private int motionArmed = -1, motionStart = -1, motionLast;
	private boolean motionIdle;
	private JsonObject motionBase, motionPet;
	private FxTrack motionFx = new FxTrack(), autoFx = new FxTrack();
	private int motionCount, motionPetCount;
	private final List<String> motionFrames = new ArrayList<>(), motionPetFrames = new ArrayList<>();

	/** A motion's name from the command's words (lower case), or null: none given, or not a name the relay takes. */
	static String motionName(String[] args)
	{
		if (args == null || args.length == 0)
		{
			return null;
		}
		String n = String.join(" ", args).trim().toLowerCase();
		return n.matches("[a-z0-9_ -]{1,24}") ? n : null;
	}

	/** the name the motion being recorded goes under (null: "idle" if they stood, "motion" if they did something) */
	private String motionAs;

	private void armMotion(String name)
	{
		motionAs = name;
		motionArmed = client.getGameCycle();
		if (panel != null)
		{
			panel.capturing(true);
		}
		motionStart = -1;
		sayModel("recording the next thing you do (an emote, a special attack): do it now. Nothing in 8 seconds, and it records you standing.");
	}

	@Subscribe
	public void onClientTick(net.runelite.api.events.ClientTick e)
	{
		if (motionArmed < 0)
		{
			// hub:cut live
			Player lp = client.getLocalPlayer();
			if (lp != null && client.getGameState() == GameState.LOGGED_IN)
			{
				liveRecordTick(lp, client.getGameCycle());
			}
			// hub:end live
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			motionArmed = -1;
			if (panel != null)
			{
				panel.capturing(false);
			}
			return;
		}
		int now = client.getGameCycle();
		if (motionStart < 0)
		{
			boolean moving = me.getAnimation() != -1;
			if (!moving && now - motionArmed < MOTION_WAIT)
			{
				return;
			}
			motionIdle = !moving;
			motionStart = now;
			motionLast = now - MOTION_EVERY;
			motionFrames.clear();
			motionPetFrames.clear();
			// (each written out as it's read: one shared model object for anything animated)
			Model base = me.getModel();
			if (base == null)
			{
				motionArmed = -1;
				if (panel != null)
				{
					panel.capturing(false);
				}
				return;
			}
			Snap bs = snap(base, true);
			List<Fx> bfx = effects(me, null);
			motionCount = ownOf(bs, bfx);
			motionBase = Kept.trim(bs.json, motionCount);
			motionFx = new FxTrack();
			addGround(motionBase, me);
			Model pet = petModel();
			motionPetCount = pet == null ? 0 : pet.getVerticesCount();
			motionPet = pet == null ? null : modelJson(pet);
		}
		if (now - motionLast < MOTION_EVERY)
		{
			return;
		}
		motionLast = now;
		Model m = me.getModel();
		Snap ms = m == null ? null : snap(m, false);
		List<Fx> mfx = ms == null ? null : effects(me, motionFx.models.keySet());
		if (ms != null && ownOf(ms, mfx) == motionCount)
		{
			motionFrames.add(Kept.frame(ms.x, ms.y, ms.z, motionCount));
			motionFx.add(mfx);
			Model p = motionPet == null ? null : petModel();
			motionPetFrames.add(p != null && p.getVerticesCount() == motionPetCount
				? Kept.frame(p.getVerticesX(), p.getVerticesY(), p.getVerticesZ(), p.getVerticesCount()) : null);
		}
		boolean done = motionFrames.size() >= MOTION_MOST
			|| (motionIdle ? now - motionStart >= MOTION_IDLE : me.getAnimation() == -1 && motionFrames.size() > 2);
		if (!done)
		{
			return;
		}
		motionArmed = -1;
		JsonObject frames = Kept.frames(motionFrames, 1000.0 / (MOTION_EVERY * 20));
		motionFx.into(frames);
		JsonObject petFrames = motionPet != null && !motionPetFrames.contains(null) ? Kept.frames(motionPetFrames, 1000.0 / (MOTION_EVERY * 20)) : null;
		sayModel("recorded " + motionFrames.size() + " frames" + (motionIdle ? " of you standing" : "") + ": sending it to your page");
		// (11/15/2026, Jace: "build the motion library": kept under its name beside the others)
		String name = motionAs != null ? motionAs : motionIdle ? "idle" : "motion";
		sayModel("it goes on your page as \"" + name + "\"");
		// (the side tab's one button, 11/15/2026: the still model goes too, from the motion's first moment; since
		// 11/18/2026 only while they haven't set a still of their own with Capture a still)
		if (configManager.getRSProfileConfiguration(OutsideConfig.GROUP, STILL_SET) == null)
		{
			keep("model", wholeModel(motionBase.deepCopy(), motionPet == null ? null : motionPet.deepCopy(), null, null));
		}
		keep("motion", name, wholeModel(motionBase, motionPet, frames, petFrames));
		if (panel != null)
		{
			panel.capturing(false);
		}
	}

	/** A texture as the client has it, a PNG in base64 (0 a see-through texel), or null. */
	private String texturePng(int id)
	{
		try
		{
			int[] px = client.getTextureProvider().load(id);
			int size = px == null ? 0 : (int) Math.round(Math.sqrt(px.length));
			if (size == 0 || size * size != px.length)
			{
				return null;
			}
			java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			for (int i = 0; i < px.length; i++)
			{
				im.setRGB(i % size, i / size, px[i] == 0 ? 0 : 0xff000000 | px[i]);
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(im, "png", out);
			return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (RuntimeException | IOException e)
		{
			return null;
		}
	}
	// hub:end model

	// hub:cut bank
	/** The whole bank, its tabs and each item's name and price (the client's thread). */
	private void sendBank()
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null || !config.bank())
		{
			return;
		}
		Item[] items = bank.getItems();
		int[] ids = new int[items.length];
		int[] qs = new int[items.length];
		Map<Integer, Object[]> names = new LinkedHashMap<>();
		for (int i = 0; i < items.length; i++)
		{
			// (a placeholder as its item, none of it)
			int id = items[i].getId();
			int real = id > 0 ? itemManager.canonicalize(id) : id;
			ids[i] = real;
			qs[i] = real != id ? 0 : items[i].getQuantity();
			if (real > 0 && !names.containsKey(real))
			{
				names.put(real, new Object[]{itemManager.getItemComposition(real).getName(), (long) itemManager.getItemPrice(real)});
			}
		}
		int[] tabVarbits = {VarbitID.BANK_TAB_1, VarbitID.BANK_TAB_2, VarbitID.BANK_TAB_3, VarbitID.BANK_TAB_4,
			VarbitID.BANK_TAB_5, VarbitID.BANK_TAB_6, VarbitID.BANK_TAB_7, VarbitID.BANK_TAB_8, VarbitID.BANK_TAB_9};
		int[] tabs = new int[9];
		for (int i = 0; i < 9; i++)
		{
			tabs[i] = client.getVarbitValue(tabVarbits[i]);
		}
		keep("bank", Kept.bank(ids, qs, tabs, names));
	}
	// hub:end bank

	/** Their own death: a delve's levels lost with it (11/19/2026), and what they carried and wore as they fell. */
	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		Player me = client.getLocalPlayer();
		if (me == null || e.getActor() != me)
		{
			return;
		}
		// hub:cut delve
		if (config.delve() && atDelve() && delves.died())
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "delveLost", delves.keep());
		}
		// hub:end delve
		// hub:cut deathbank
		deathbankDied();
		// hub:end deathbank
	}

	// hub:cut delve
	/** Whether they're in the Doom of Mokhaiotl's lair (DELVE_REGIONS, by the instance's own map). */
	private boolean atDelve()
	{
		WorldPoint at = here();
		if (at == null)
		{
			return false;
		}
		for (int r : DELVE_REGIONS)
		{
			if (at.getRegionID() == r)
			{
				return true;
			}
		}
		return false;
	}
	// hub:end delve

	// hub:cut deathbank
	/** Their own death (11/16/2026): what they carried and wore as they fell, and where. */
	private void deathbankDied()
	{
		if (!config.deathbank())
		{
			return;
		}
		List<Item> all = new ArrayList<>();
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer c = client.getItemContainer(id);
			if (c != null)
			{
				java.util.Collections.addAll(all, c.getItems());
			}
		}
		deathCarried = new int[2][all.size()];
		for (int i = 0; i < all.size(); i++)
		{
			deathCarried[0][i] = all.get(i).getId();
			deathCarried[1][i] = all.get(i).getQuantity();
		}
		WorldPoint at = here();
		deathRegion = at == null ? -1 : at.getRegionID();
		deathTick = ticks;
		deathLine = null;
	}

	/** The game's line that a death made a deathbank: once they're back on their feet, deathbankFromDeath sends it. */
	private void deathbankLine(String said)
	{
		if (deathTick >= 0 && said != null && said.startsWith(Kept.DEATHBANK_LINE))
		{
			deathLine = said;
		}
	}

	/** Each tick: a death the game said made a deathbank, once they're back (their hitpoints restored, a few ticks on),
	 *  sent as what it took (Kept.lost), "from" the death; a death with no such line within half an hour, forgotten.
	 *  Locked (its fee unpaid), but an ultimate ironman's Zulrah's, which has none. */
	private void deathbankFromDeath()
	{
		if (deathTick < 0)
		{
			return;
		}
		if (!config.deathbank() || ticks - deathTick > 3000)
		{
			deathTick = -1;
			deathCarried = null;
			deathLine = null;
			return;
		}
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		if (deathLine == null || ticks - deathTick < 3 || hp < Math.min(10, client.getRealSkillLevel(Skill.HITPOINTS)))
		{
			return;
		}
		List<Item> now = new ArrayList<>();
		for (int id : new int[]{InventoryID.INV, InventoryID.WORN})
		{
			ItemContainer c = client.getItemContainer(id);
			if (c != null)
			{
				java.util.Collections.addAll(now, c.getItems());
			}
		}
		int[] ids = new int[now.size()];
		int[] qs = new int[now.size()];
		for (int i = 0; i < now.size(); i++)
		{
			ids[i] = now.get(i).getId();
			qs[i] = now.get(i).getQuantity();
		}
		int[][] lost = Kept.lost(deathCarried[0], deathCarried[1], ids, qs);
		String where = Kept.deathbankAt(deathLine, deathRegion);
		boolean locked = !("Zulrah".equals(where) && client.getVarbitValue(VarbitID.IRONMAN) == 2);
		deathTick = -1;
		deathCarried = null;
		deathLine = null;
		if (lost[0].length > 0)
		{
			deathbankSeen = new Object[]{where, locked, lost[0], lost[1], true};
			deathbankChanged = ticks;
		}
	}

	/** The deathbank as last seen (deathbankSeen), each item's name and price; emptied, taken off the page (the client's thread). */
	private void sendDeathbank()
	{
		Object[] d = deathbankSeen;
		if (d == null || !config.deathbank())
		{
			return;
		}
		int[] ids = (int[]) d[2];
		int[] qs = (int[]) d[3];
		Map<Integer, Object[]> names = new LinkedHashMap<>();
		for (int i = 0; i < ids.length; i++)
		{
			int id = ids[i];
			if (id > 0 && qs[i] > 0 && !names.containsKey(id))
			{
				names.put(id, new Object[]{itemManager.getItemComposition(id).getName(), (long) itemManager.getItemPrice(id)});
			}
		}
		JsonObject body = Kept.deathbank((String) d[0], (boolean) d[1], ids, qs, names, (boolean) d[4]);
		keep("deathbank", body);
	}
	// hub:end deathbank

	// hub:cut model,bank
	/* (11/17/2026, the Hub's update 2: the bank and the deathbank on the page, under the reviewer's terms, "an 'Update my
	   page' action and an hourly sync": in the Hub's version each is read as now, when they look in it, and held here,
	   the latest of each, till the next update takes it (Update my page, or the hourly one); the model, captured by their
	   own press, goes at once, and a switch turned off takes its part down at once. The whole plugin sends each at once.) */
	private final Map<String, JsonObject> keepHeld = new LinkedHashMap<>();
	// hub:cut live
	static final boolean HOLD_KEEPS = false;
	// hub:end live
	// hub:only static final boolean HOLD_KEEPS = true;

	/** Whether this keep waits for the next update (the Hub's version): the bank's or the deathbank's, not a take-down. */
	private boolean holdKeep(String what, JsonObject body)
	{
		if (body == null || !("bank".equals(what) || "deathbank".equals(what)))
		{
			synchronized (keepHeld)
			{
				keepHeld.remove(what);   // (a take-down: nothing held of it goes later)
			}
			return false;
		}
		boolean first;
		synchronized (keepHeld)
		{
			first = keepHeld.put(what, body) == null;
		}
		if (first)
		{
			sayAbout(what, "your " + what + " goes up with your next update (Update my page, or the hourly one)");
		}
		return true;
	}

	/** What's held goes, after an update's gone. */
	private void sendHeldKeeps()
	{
		Map<String, JsonObject> due;
		synchronized (keepHeld)
		{
			due = new LinkedHashMap<>(keepHeld);
			keepHeld.clear();
		}
		due.forEach(this::sendKeep);
	}

	/** Sent to the relay's keep (POST; null: DELETE), off the client's thread; a failure said in the chat. */
	private void keep(String what, JsonObject body)
	{
		keep(what, null, body);
	}

	private void keep(String what, String name, JsonObject body)
	{
		if (name == null && HOLD_KEEPS && holdKeep(what, body))
		{
			return;
		}
		sendKeep(what, name, body);
	}

	private void sendKeep(String what, JsonObject body)
	{
		sendKeep(what, null, body);
	}

	private void sendKeep(String what, String name, JsonObject body)
	{
		// hub:cut live
		Attach a = attach != null ? attach : key();
		// hub:end live
		// hub:only Attach a = key();
		if (a == null || !config.send())
		{
			return;
		}
		String url = a.keepUrl(what) + (name == null ? "" : "&name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
		Request.Builder b = new Request.Builder().url(url).header("User-Agent", "outside-plugin/" + VERSION);
		b = body == null ? b.delete() : b.post(RequestBody.create(JSON, gson.toJson(body)));
		http.newCall(b.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("outside: keep {} failed", what, ex);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				int code = r.code();
				String why = "";
				try
				{
					// (the relay's own words for what it refused: {"error": "..."})
					String t = r.body() == null ? "" : r.body().string();
					JsonObject e = t.startsWith("{") ? gson.fromJson(t, JsonObject.class) : null;
					why = e != null && e.has("error") ? ": " + e.get("error").getAsString() : "";
				}
				catch (IOException | RuntimeException ex)
				{
					why = "";
				}
				r.close();
				if ("motion".equals(what) || "model".equals(what))
				{
					readMotions();
				}
				if (body != null && code == 200)
				{
					if (!"encounter".equals(what))
					{
						sayAbout(what, "model".equals(what) ? "your still is on your page" : "motion".equals(what) ? "the motion \"" + name + "\" is on your page" : "your " + what + " is on your page");
					}
				}
				else if (body != null && code != 200)
				{
					sayAbout(what, "your " + what + " didn't go up (the relay said " + code + why + ")");
				}
			}
		});
	}
	// hub:end model,bank

	// hub:cut delve
	/** Charges and the delve board, as kept on this RuneLite's profile for the account. */
	// hub:end delve
	// hub:only /** Charges, as kept on this RuneLite's profile for the account. */
	private void loadKept()
	{
		charges.clear();
		counts.load(null);
		try
		{
			String c = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "charges");
			if (c != null)
			{
				JsonObject o = gson.fromJson(c, JsonObject.class);
				for (Map.Entry<String, JsonElement> e : o.entrySet())
				{
					charges.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt());
				}
			}
			String n = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "counts");
			counts.load(n == null ? null : gson.fromJson(n, JsonObject.class));
			// hub:cut delve
			String d = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "delve");
			delveSeen = d == null ? null : gson.fromJson(d, JsonArray.class);
			delves.load(configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "delveLost"));
			String dl = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "delveLostSeen");
			delveLostSeen = dl == null ? null : gson.fromJson(dl, JsonArray.class);
			// hub:end delve
		}
		catch (RuntimeException e)
		{
			log.debug("outside: charges or delve unreadable", e);
		}
	}

	private void saveKept()
	{
		JsonObject o = new JsonObject();
		for (Map.Entry<Integer, Integer> e : charges.entrySet())
		{
			o.addProperty(String.valueOf(e.getKey()), e.getValue());
		}
		configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "charges", gson.toJson(o));
		// hub:cut delve
		if (delveSeen != null)
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "delve", gson.toJson(delveSeen));
		}
		if (delveLostSeen != null)
		{
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "delveLostSeen", gson.toJson(delveLostSeen));
		}
		// hub:end delve
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		// hub:cut globe,year
		if (config.activity() || config.year())
		{
			activity.xp(System.currentTimeMillis(), e.getSkill().getName(), e.getXp());
		}
		// hub:end globe,year
		// (what they're doing as Discord shows it: their own experience going up, 11/16/2026)
		presence.experience(e.getSkill().getName(), e.getXp(), System.currentTimeMillis());
		// (proving, 11/17/2026: experience gained after the relay said "ready" makes the update it said it to out of date by
		// logout, so the side tab says to update again; noticed here, nothing sent)
		if (readyXp > 0 && !readyStale && client.getOverallExperience() > readyXp && panel != null)
		{
			readyStale = true;
			panel.proof(proofState, STALE);
		}
		// hub:cut reports
		// (a level gained: its real level above the one last seen this session; the first seen is only remembered)
		String skill = e.getSkill().getName();
		Integer was = levelsSeen.put(skill, e.getLevel());
		if (was != null && e.getLevel() > was && client.getGameState() == GameState.LOGGED_IN)
		{
			java.util.Map<String, Integer> one = new java.util.LinkedHashMap<>();
			one.put(skill, e.getLevel());
			report(Reports.levels(one, myName()));
		}
		// hub:end reports
	}

	// hub:cut reports
	/** A monster's loot (RuneLite's own reading of it, as Dink's): worth "Drops worth at least" or more, a report.
	 *  (Handed it by the goal's onNpcLootReceived: the event bus takes one listener of that name.) */
	private void reportLoot(NpcLootReceived e)
	{
		if (!config.reports() || e.getNpc() == null)
		{
			return;
		}
		java.util.List<Reports.Item> items = new java.util.ArrayList<>();
		for (ItemStack s : e.getItems())
		{
			int id = itemManager.canonicalize(s.getId());
			items.add(new Reports.Item(id, itemManager.getItemComposition(id).getName(), s.getQuantity(), itemManager.getItemPrice(id)));
		}
		report(Reports.loot(e.getNpc().getName(), items, Math.max(0, config.reportLoot()), myName()));
	}
	// hub:end reports

	// hub:cut year
	// ---- your year (11/15/2026; Record, docs/record.md)

	/** The record for the character logged in (made the first time), or null: not logged in, or "Record my year" off. */
	private Record yearRecord()
	{
		String c = character;
		if (c == null || !config.year())
		{
			return null;
		}
		if (yearRecord == null || !c.equals(yearFor))
		{
			try
			{
				yearRecord = new Record(net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("outside").resolve("record").resolve(c), gson);
				yearFor = c;
				yearHeld.clear();
			}
			catch (IOException ex)
			{
				log.debug("outside: the year's folder couldn't be made", ex);
				return null;
			}
		}
		return yearRecord;
	}

	/** A line written to the year: its time, and what it states. */
	private void yearWrite(long t, java.util.Map<String, JsonElement> fields)
	{
		Record r = yearRecord();
		if (r == null || fields.isEmpty())
		{
			return;
		}
		JsonObject line = new JsonObject();
		line.addProperty("t", t);
		fields.forEach(line::add);
		try
		{
			r.write(line);
		}
		catch (IOException ex)
		{
			log.debug("outside: a line of the year couldn't be written", ex);
		}
	}

	/** Each tick: the year's place (two minutes behind, as following's), its activity (the same), the exact path (with
	 *  its switch on), each when it changes or is cleared; a line restating what's held a minute after the last; and
	 *  the hourly upload. */
	private void yearTick(long now, WorldPoint at, boolean safe, boolean actSafe, JsonObject path)
	{
		if (!config.year() || character == null)
		{
			return;
		}
		java.util.Map<String, JsonElement> said = new java.util.LinkedHashMap<>();
		JsonObject w = at == null ? null : yearFollow.tick(now, at.getX(), at.getY(), client.getWorld(), safe);
		if (!config.activity())
		{
			activity.region(at == null ? -1 : at.getRegionID(), at == null ? 0 : at.getPlane());
			activity.trial(client.getVarbitValue(VarbitID.SAILING_BT_IN_TRIAL) != 0);
		}
		JsonObject a = yearDoing.tick(now, activity.now(now), actSafe);
		for (JsonObject o : new JsonObject[]{w, a, path})
		{
			if (o == null)
			{
				continue;
			}
			for (java.util.Map.Entry<String, JsonElement> e : o.entrySet())
			{
				said.put(e.getKey(), e.getValue());
				if (e.getValue() == null || e.getValue().isJsonNull())
				{
					yearHeld.remove(e.getKey());
				}
				else
				{
					yearHeld.put(e.getKey(), e.getValue());
				}
			}
		}
		if (said.containsKey("where") || said.containsKey("activity"))
		{
			yearStated = now;
		}
		else if (!yearHeld.isEmpty() && now - yearStated >= 60_000)
		{
			// (the heartbeat: a long stay in one place counts as long)
			said.putAll(yearHeld);
			yearStated = now;
		}
		yearWrite(now, said);
		if (now - yearSynced > 3_600_000)
		{
			yearSynced = now;
			executor.submit(() -> yearSync(null));
		}
	}

	/** A report's events (array.ts's arrayEvents' shapes) into the year, never from somewhere the plugin holds back. */
	private void yearEvents(JsonObject payload)
	{
		if (payload == null || paused || !config.year())
		{
			return;
		}
		JsonObject x = payload.getAsJsonObject("extra");
		java.util.List<JsonObject> events = new java.util.ArrayList<>();
		switch (payload.get("type").getAsString())
		{
			case "KILL_COUNT":
				events.add(event("kill", "boss", x.get("boss"), "kc", x.get("count")));
				break;
			case "LEVEL":
				for (java.util.Map.Entry<String, JsonElement> e : x.getAsJsonObject("levelledSkills").entrySet())
				{
					events.add(event("level", "skill", new com.google.gson.JsonPrimitive(e.getKey()), "level", e.getValue()));
				}
				break;
			case "PET":
				events.add(event("pet", null, null, null, null));
				break;
			case "COLLECTION":
				events.add(event("clog", "name", x.get("itemName"), null, null));
				break;
			case "CLUE":
				events.add(event("clue", "tier", x.get("clueType"), "count", x.get("numberCompleted")));
				break;
			case "LOOT":
			{
				long value = 0;
				String name = null;
				long most = -1;
				for (JsonElement i : x.getAsJsonArray("items"))
				{
					JsonObject o = i.getAsJsonObject();
					long worth = o.get("quantity").getAsLong() * o.get("priceEach").getAsLong();
					value += worth;
					if (worth > most)
					{
						most = worth;
						name = o.get("name").getAsString();
					}
				}
				if (value >= 1_000_000)
				{
					events.add(event("loot", "name", new com.google.gson.JsonPrimitive(name), "value", new com.google.gson.JsonPrimitive(value)));
				}
				break;
			}
			default:
				break;
		}
		long now = System.currentTimeMillis();
		for (JsonObject ev : events)
		{
			java.util.Map<String, JsonElement> one = new java.util.LinkedHashMap<>();
			one.put("event", ev);
			yearWrite(now, one);
		}
	}

	private static JsonObject event(String kind, String k1, JsonElement v1, String k2, JsonElement v2)
	{
		JsonObject o = new JsonObject();
		o.addProperty("kind", kind);
		if (k1 != null && v1 != null)
		{
			o.add(k1, v1);
		}
		if (k2 != null && v2 != null)
		{
			o.add(k2, v2);
		}
		return o;
	}

	/** Logged out: what's held is cleared in the record (a gap, not a stay), and what's new goes up. */
	private void yearLogout()
	{
		if (yearRecord != null && character != null && !yearHeld.isEmpty())
		{
			java.util.Map<String, JsonElement> gone = new java.util.LinkedHashMap<>();
			for (String k : yearHeld.keySet())
			{
				gone.put(k, com.google.gson.JsonNull.INSTANCE);
			}
			yearWrite(System.currentTimeMillis(), gone);
		}
		yearHeld.clear();
		yearFollow.forget();
		yearDoing.forget();
		if (executor != null)
		{
			executor.submit(() -> yearSync(null));
		}
	}

	/** This character's record key ("record1:", kept with their account), made the first time it's asked for. */
	private byte[] recordKey(boolean make)
	{
		String text = configManager.getRSProfileConfiguration(OutsideConfig.GROUP, "recordKey");
		byte[] k = Record.parseKey(text);
		if (k == null && make)
		{
			k = Record.newKey();
			configManager.setRSProfileConfiguration(OutsideConfig.GROUP, "recordKey", Record.keyText(k));
		}
		return k;
	}

	/** Uploads each day whose lines changed (docs/record.md, "Sync"): the tokens registered first. On the executor;
	 *  `then` after, whatever happened. */
	private void yearSync(Runnable then)
	{
		try
		{
			Attach a = key();
			Record r = yearRecord;
			byte[] k = recordKey(false);
			if (a == null || r == null || k == null || !config.yearCopy())
			{
				return;
			}
			JsonObject tokens = new JsonObject();
			tokens.addProperty("read", Record.readToken(k));
			tokens.addProperty("write", Record.writeToken(k));
			try (Response res = http.newCall(new Request.Builder().url(a.recordUrl("token")).header("User-Agent", "outside-plugin/" + VERSION)
				.post(RequestBody.create(JSON, gson.toJson(tokens))).build()).execute())
			{
				if (res.code() != 204 && res.code() != 200)
				{
					panel.yearStatus("the relay didn't take your year's key (" + res.code() + ")");
					return;
				}
			}
			String to = a.getRelay() + "|" + a.getChannel() + "|" + Record.readToken(k).substring(0, 8);
			java.util.Map<String, Integer> sent = r.sent(to);
			byte[] seal = Record.sealKey(k);
			for (java.util.Map.Entry<String, java.util.List<String>> d : r.days().entrySet())
			{
				java.util.List<String> lines = d.getValue();
				if (sent.getOrDefault(d.getKey(), -1) == lines.size())
				{
					continue;
				}
				boolean ok = true;
				for (int part = 0; part * Record.PART_LINES < lines.size() && ok; part++)
				{
					java.util.List<String> some = lines.subList(part * Record.PART_LINES, Math.min(lines.size(), (part + 1) * Record.PART_LINES));
					String id = Record.chunkId(k, r.device, d.getKey(), part);
					byte[] sealed = Record.seal(seal, id, Record.plaintext(r.device, d.getKey(), part, some));
					try (Response res = http.newCall(new Request.Builder().url(a.recordUrl(id)).header("User-Agent", "outside-plugin/" + VERSION)
						.put(RequestBody.create(MediaType.parse("application/octet-stream"), sealed)).build()).execute())
					{
						ok = res.isSuccessful();
					}
				}
				if (ok)
				{
					r.markSent(to, d.getKey(), lines.size());
				}
			}
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("outside: the year didn't go up", ex);
		}
		finally
		{
			if (then != null)
			{
				then.run();
			}
		}
	}

	/** "Open my year": the encrypted copy switched on if it isn't (asked first), what's new sent, and the year's page
	 *  opened with the key in its fragment (never sent to any server; docs/record.md, "Viewing"). */
	private void openMyYear()
	{
		Attach a = key();
		if (a == null || character == null)
		{
			panel.yearStatus("log in on a character with a page first");
			return;
		}
		if (!config.year())
		{
			panel.yearStatus("switch on \"Record my year\" in the plugin's settings first");
			return;
		}
		if (!config.yearCopy())
		{
			javax.swing.SwingUtilities.invokeLater(() ->
			{
				int yes = javax.swing.JOptionPane.showConfirmDialog(panel,
					"<html><div style='width:280px'>Your year is kept on this PC. To see it on Outside.wiki, an encrypted copy"
						+ " goes to the relay, sealed with a key only your own computers hold: the relay can't read it, and"
						+ " lose the key and nobody can.<br><br>Keep an encrypted copy on the relay?</div></html>",
					"Your year", javax.swing.JOptionPane.YES_NO_OPTION);
				if (yes == javax.swing.JOptionPane.YES_OPTION)
				{
					configManager.setConfiguration(OutsideConfig.GROUP, "yearCopy", true);
					openMyYear();
				}
			});
			return;
		}
		byte[] k = recordKey(true);
		panel.yearStatus("sending what's new, then opening your year");
		String page = "https://outside.wiki/" + java.net.URLEncoder.encode(a.getChannel(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
			+ "/year/mine#k=" + Record.keyB64u(k);
		executor.submit(() -> yearSync(() ->
		{
			panel.yearStatus(" ");
			net.runelite.client.util.LinkBrowser.browse(page);
		}));
	}
	// hub:end year

	// hub:cut reports
	/** The name they're logged in as, for a report's line. */
	private String myName()
	{
		Player me = client.getLocalPlayer();
		return me == null || me.getName() == null ? "the subject" : me.getName().replace('\u00a0', ' ');
	}

	/** A report sent to the relay's Dink route (docs/protocol.md "Dink", from=plugin): with "Levels, kills, drops and log
	 *  slots" on, a key for the character logged in, and never while held somewhere dangerous (the Wilderness, a PvP
	 *  area or world), as nothing else is sent there. */
	private void report(JsonObject payload)
	{
		// hub:cut year
		yearEvents(payload);
		// hub:end year
		Attach a = attach;
		String c = character;
		if (payload == null || !config.reports() || !config.send() || paused || a == null || c == null
			|| !isOwn(a.getChannel(), c))
		{
			return;
		}
		Request req = new Request.Builder().url(a.dinkUrl()).header("User-Agent", "outside-plugin/" + VERSION)
			.post(RequestBody.create(JSON, gson.toJson(payload))).build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				log.debug("outside: a report didn't reach the relay", ex);
			}

			@Override
			public void onResponse(Call call, Response r)
			{
				r.close();
			}
		});
	}
	// hub:end reports

	// hub:cut globe,year
	/** A hit in their own fight: theirs on an NPC, or one on them (the activity's fighting, and which boss). */
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (!config.activity() && !config.year())
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
	// hub:end globe,year

	/**
	 * Where the player is, an instance's as its template lies on the map (11/09/2026: instances are allowed, placed
	 * where they're opened), as RuneLite's own Discord plugin reads it; outside one, where they are; on a boat, where the
	 * boat is at sea (onMainWorld, 11/10/2026). Null before there's a player, or for a moment while a boat's being set up.
	 */
	private WorldPoint here()
	{
		return here(client, client.getLocalPlayer());
	}

	static WorldPoint here(Client client, Player me)
	{
		LocalPoint at = me == null ? null : onMainWorld(client.getTopLevelWorldView(), me.getLocalLocation());
		return at == null ? null : WorldPoint.fromLocalInstance(client, at);
	}

	/**
	 * A point on the main world (11/10/2026, Jace: "I notice there are no players on their boats at sea"). With Sailing,
	 * a player on a boat stands in the boat's own world view (a WorldEntity's), and their local point is the deck's, so
	 * read as it was it gave the boat's own template, off the map, and nothing went. Here the deck's point is carried
	 * out to where the boat is on the main world, the way RuneLite's own code does it (runelite/runelite at e7098b7,
	 * Perspective.localToMinimap: the top-level view's worldEntities().byIndex(the point's world view), then
	 * transformToMainWorld) and the Sailing plugin's SailingUtil.getTopLevelLocalPoint (LlemonDuck/sailing at af987f3);
	 * Group Ironmen Tracker and Shortest Path take the boat's own place (worldEntity.getLocalLocation()), here the fallback.
	 * Null for a point in a world view with no boat on the main world (as while a world hop sets it up): never the deck's.
	 */
	static LocalPoint onMainWorld(WorldView top, LocalPoint p)
	{
		if (p == null || p.getWorldView() == WorldView.TOPLEVEL)
		{
			return p;
		}
		IndexedObjectSet<? extends WorldEntity> boats = top == null ? null : top.worldEntities();
		WorldEntity boat = boats == null ? null : boats.byIndex(p.getWorldView());
		if (boat == null)
		{
			return null;
		}
		LocalPoint at = boat.transformToMainWorld(p);
		if (at == null)
		{
			at = boat.getLocalLocation();
		}
		return at == null ? null : new LocalPoint(at.getX(), at.getY(), WorldView.TOPLEVEL);
	}

	/**
	 * Ferox Enclave (11/10/2026, Jace: "it is a safe area/haven in the wilderness"): the town inside its barriers, where
	 * no player can attack another, as Shortest Path's WildernessChecker draws it (github.com/Skretzo/shortest-path,
	 * src/main/java/shortestpath/pathfinder/WildernessChecker.java at 78593f7, FEROX_ENCLAVE_1 to 5: each {x, y, width,
	 * height}, on every floor). Not the semi-safe ground outside its barriers, nor the dungeon under it.
	 */
	static final int[][] FEROX = {{3123, 3622, 2, 10}, {3125, 3617, 16, 23}, {3138, 3636, 18, 10}, {3141, 3625, 14, 11},
		{3141, 3619, 7, 6}};

	static boolean inFerox(int x, int y)
	{
		for (int[] a : FEROX)
		{
			if (x >= a[0] && x < a[0] + a[2] && y >= a[1] && y < a[1] + a[3])
			{
				return true;
			}
		}
		return false;
	}

	/** In the Wilderness: within its bounds (an instance's template there too), or where the game's own
	 *  in-the-Wilderness flag says so (any part of it outside them); but never in Ferox Enclave, a haven (whatever the
	 *  flag says there, which couldn't be checked in the game). Kept apart from the client so it's tested. */
	static boolean wilderness(int x, int y, boolean insideFlag)
	{
		return !inFerox(x, y) && (inWilderness(x, y) || insideFlag);
	}

	/** In a PvP area: the game's PvP-area flag up, but not in Ferox Enclave and not in a PvP minigame's region
	 *  (11/10/2026, Jace: "there is no danger to someone tracking them there": Activity.PVP_MINIGAME_REGIONS). */
	static boolean pvpArea(int x, int y, int region, boolean pvpFlag)
	{
		// hub:cut globe
		return pvpFlag && !inFerox(x, y) && !Activity.PVP_MINIGAME_REGIONS.contains(region);
		// hub:end globe
		// hub:only return pvpFlag && !inFerox(x, y);
	}

	// hub:cut globe,year,model
	/**
	 * Safe for the globe's features (following and its world, the activity, the stars): not on a risky world (PvP,
	 * Deadman, high risk, Bounty Hunter), not in the Wilderness, not in a PvP area (each as above: Ferox Enclave and the
	 * PvP minigames are safe). Instances are (11/09/2026), unless their template lies in the Wilderness.
	 */
	static boolean globeSafe(boolean riskyWorld, boolean wilderness, boolean pvpArea)
	{
		return !riskyWorld && !wilderness && !pvpArea;
	}
	// hub:end globe,year,model

	// hub:cut globe,year,model
	/**
	 * Safe for the activity's label (11/10/2026, Jace: "Wilderness bosses should be included since we have the option
	 * for 'In the Wilderness too'"): wherever the globe's features are, and in the Wilderness too (its PvP-area flag
	 * with it) when they've said so (OutsideConfig.wilderness), since they've already chosen to transmit there. Never
	 * on a risky world. Following and the stars stay off in the Wilderness whatever the setting (globeSafe): only the
	 * label goes, two minutes behind as ever.
	 */
	static boolean activitySafe(boolean riskyWorld, boolean wilderness, boolean pvpArea, boolean wildernessToo)
	{
		return globeSafe(riskyWorld, wilderness, pvpArea) || (wildernessToo && wilderness && !riskyWorld);
	}
	// hub:end globe,year,model

	/**
	 * Where the stream holds (what they wear and carry, their vitals: Kit.held), unless "In the Wilderness too" is on:
	 * a PvP or Deadman world, the Wilderness, a PvP area. Not in Ferox Enclave or a PvP minigame (11/10/2026, Jace:
	 * "stream should keep going"), whatever the game's flags say there.
	 */
	static boolean held(boolean pvpWorld, boolean wilderness, boolean pvpArea)
	{
		return pvpWorld || wilderness || pvpArea;
	}

	// hub:cut globe,year,model
	/** On a world where nothing goes to the globe (RISKY, or any the client counts as PvP). */
	private boolean riskyWorld()
	{
		EnumSet<WorldType> types = client.getWorldType();
		if (pvpWorld())
		{
			return true;
		}
		for (WorldType t : types)
		{
			if (RISKY.contains(t))
			{
				return true;
			}
		}
		return false;
	}
	// hub:end globe,year,model

	/** On a PvP or Deadman world (read here, never sent). */
	private boolean pvpWorld()
	{
		return WorldType.isPvpWorld(client.getWorldType()) || client.getWorldType().contains(WorldType.DEADMAN);
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

	// hub:cut live
	// ---- the relay ----

	/** RuneLite's Gson, keeping nulls as it writes (Kit.wire): the game socket's messages say "clear this" with them. */
	private Gson wire;

	private boolean send(JsonObject fields)
	{
		WebSocket ws = socket;
		Attach a = attach;
		String c = character;
		if (ws == null || !open || a == null || c == null || !isOwn(a.getChannel(), c))
		{
			return false;
		}
		JsonObject msg = new JsonObject();
		msg.add("game", fields);
		if (wire == null)
		{
			wire = Kit.wire(gson);
		}
		return ws.send(wire.toJson(msg));
	}

	private synchronized void connect()
	{
		if (!config.send() || stopped || socket != null)
		{
			return;
		}
		attach = key();
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
				// hub:cut talk
				talkOpen();
				// hub:end talk
				if (!toldConnected)
				{
					toldConnected = true;
					say(challenging ? "your claim on Outside.wiki/" + attach.getChannel() + " is being proven: gain any experience, then log out"
						: "your Outside page is live: " + attach.page().replaceFirst("^https://", ""));
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
		// hub:cut talk
		talkClose();
		// hub:end talk
		if (code == 4012)
		{
			// (taking a name back, 11/15/2026: proven, the page is theirs now; its key opens it as the holder's)
			challenging = false;
			say("your Outside page is proven yours: Outside.wiki/" + attach.getChannel());
			readProof();
			retry = executor.schedule(this::connect, 1, TimeUnit.SECONDS);
			return;
		}
		if (code == 4001 || code == 4003)
		{
			stopped = true;
			say(challenging ? "someone else proved this name first, so your claim on it has ended"
				: "the relay didn't take your station key: paste it again in the plugin's settings");
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
		// hub:cut talk
		talkClose();
		// hub:end talk
		if (ws != null)
		{
			ws.close(1000, why);
		}
	}

	// hub:cut talk
	// ---- the page's chat in the game (11/16/2026; Talk) ----

	/** The desk socket for the page's chat, while "The page's chat in my game" is on and the game's socket is open. */
	private synchronized void talkOpen()
	{
		Attach a = attach;
		if (!config.chat() || talkSocket != null || a == null || !open)
		{
			return;
		}
		// (the name as the game shows it, read on the client thread each tick: this runs on the socket's)
		String name = shownName != null ? shownName : a.getChannel();
		long since = System.currentTimeMillis();
		Request req = new Request.Builder().url(a.deskUrl(name)).header("User-Agent", "outside-plugin/" + VERSION).build();
		talkSocket = http.newWebSocket(req, new WebSocketListener()
		{
			@Override
			public void onMessage(WebSocket ws, String text)
			{
				Talk.Line l = Talk.visitor(text, since, gson);
				if (l != null)
				{
					clientThread.invokeLater(() -> chat.queue(QueuedMessage.builder().type(ChatMessageType.FRIENDSCHAT)
						.sender("Outside").name(Text.escapeJagex(l.name)).value(Text.escapeJagex(l.text)).build()));
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
				talkEnded(ws);
			}

			@Override
			public void onFailure(WebSocket ws, Throwable t, Response response)
			{
				talkEnded(ws);
			}
		});
	}

	/** The player's answer to their page's chat (::o or /o): to the desk, and into the chat box beside the visitors'. */
	private void answerPage(String words)
	{
		WebSocket ws = talkSocket;
		if (ws == null)
		{
			say(config.chat() ? "your page's chat isn't open yet" : "switch on \"The page's chat in my game\" in the plugin's settings first");
			return;
		}
		String line = Talk.say(words, gson);
		if (line == null)
		{
			say("::o or /o and your words, to answer your page's chat");
			return;
		}
		ws.send(line);
		// (the relay's echo is the host's, which isn't shown)
		String me = myName(), one = words.replaceAll("\\s+", " ").trim();
		clientThread.invokeLater(() -> chat.queue(QueuedMessage.builder().type(ChatMessageType.FRIENDSCHAT)
			.sender("Outside").name(Text.escapeJagex(me == null ? "you" : me)).value(Text.escapeJagex(one)).build()));
	}

	/**
	 * /o in the chat box (11/16/2026, Jace: "build ::o, but also include /o"): caught as the chat box's enter is pressed,
	 * as RuneLite's Twitch plugin catches /t (its onScriptCallbackEvent, "chatDefaultReturn"), and kept from the game, so
	 * it never reaches the friends chat a / would otherwise send it to; then answered as ::o is.
	 */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent e)
	{
		if (!"chatDefaultReturn".equals(e.getEventName()))
		{
			return;
		}
		String words = Talk.prefixed(client.getVarcStrValue(VarClientID.CHATINPUT));
		if (words == null)
		{
			return;
		}
		int[] stack = client.getIntStack();
		stack[client.getIntStackSize() - 3] = 1; // (keep it from the game)
		answerPage(words);
	}

	private synchronized void talkEnded(WebSocket ws)
	{
		if (ws != talkSocket)
		{
			return;
		}
		talkSocket = null;
		// (still wanted, and the game's socket still open: again, in a while)
		if (config.chat() && open)
		{
			executor.schedule(this::talkOpen, 15, TimeUnit.SECONDS);
		}
	}

	private synchronized void talkClose()
	{
		WebSocket ws = talkSocket;
		talkSocket = null;
		if (ws != null)
		{
			ws.close(1000, "chat off");
		}
	}
	// hub:end talk
	// hub:end live

	private void say(String words)
	{
		clientThread.invokeLater(() -> chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("Outside: " + words).build()));
	}

	/** A line about the character model or its motions: in the chat, and under Capture my character in the side tab's
	 *  Model tab (11/18/2026: every line went there, so the goal's turned up under the capture button). */
	private void sayModel(String words)
	{
		if (panel != null)
		{
			panel.status(words);
		}
		say(words);
	}

	/** A keep's line: under Capture my character only when it's the model's or a motion's. */
	private void sayAbout(String what, String words)
	{
		if ("model".equals(what) || "motion".equals(what))
		{
			sayModel(words);
		}
		else
		{
			say(words);
		}
	}
}
