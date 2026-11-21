/*
 * Encounter Recorder's EncounterRecorderPlugin.java and Names.java (github.com/mrgnomnom/encounter-recorder, the Plugin
 * Hub's commit 3570026), ported into Outside on 11/21/2026 and changed as the comments below say, under their licence:
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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Actor;
import net.runelite.api.Animation;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Prayer;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outside's encounter recorder (11/21/2026; ghillie's claude/outstation.md, "Encounters on Outside: the night's design"):
 * Encounter Recorder by Mr Gnome (the Plugin Hub's encounter-recorder, BSD 2-Clause, its notice above; plugin/README.md,
 * "Credits") folded into Outside so a player needs only this plugin, writing his file format (his docs/FORMAT.md,
 * format 6), so his replay and Outside's read each other's files. One JSON line per event: "t" the recording's tick,
 * "c" the client cycle (20 ms) it arrived on; events the server sends for a tick share its "t" with the tick line
 * written at the end of it. Registered on RuneLite's event bus by OutsidePlugin; every listener runs on the client's
 * thread, and nothing here touches the disk (EncounterFiles' threads do) or the network (nothing here sends: putting an
 * encounter on the page is OutsidePlugin's, on the player's press).
 *
 * <p>What's Outside's own, beside his:
 * <ul>
 * <li>The header says whose recorder it was ("recorder": "outside", "recorderVersion"), whose game ("player": the
 * player's own name, for the side tab to offer it only to that character's page), what started it ("area") and, for a
 * recording split at the length limit, which part it is ("part", "follows": the part before's file).</li>
 * <li><b>The starting state</b> (the test fight: the boss's HP bar set before the recording began stayed unknown until it
 * changed): every varp that isn't 0 as a var line (varbit -1, the varp's whole value, as his own lines give a varp
 * change), the ground items already lying in the scene as item lines, the graphics already on tiles as gfx lines and
 * the projectiles already in flight as proj lines, each marked "init": true as his own start snapshot marks its
 * npc_spawn and obj lines.</li>
 * <li><b>An animation restarted</b> (the same animation sent again: the client puts its frame back to 0 and RuneLite
 * posts no AnimationChanged, so his files show it only in the tick lines): watched each client cycle, an anim line with
 * "restart": true when an actor's frame goes back while the animation is the same and hadn't reached its end (a loop
 * wraps only from its last frame).</li>
 * <li><b>Objects' animation changes</b> (the server animating an object already there: the client gives it a new
 * DynamicObject, and RuneLite posts nothing): the scene round the player read each tick, an obj_anim line (a new kind:
 * id, kind, anim and the tile) when an object's renderable becomes a new animated one.</li>
 * <li><b>Projectiles over in a cycle</b> (one gone before it ever moves never posts ProjectileMoved): the client's list
 * of projectiles read each client cycle too, any not seen yet written as his proj line.</li>
 * <li><b>The raids' completion lines</b> (friends-chat notifications, which his list of chat types drops): kept when they
 * are a completion or a time ("your raid is complete", "Team size", "Duration", "Personal best", "completion time",
 * "Challenge complete"), every other player's name the client knows (the scene's, the friends chat's) taken out first.</li>
 * <li><b>The length limit splits</b> rather than stops: at 5,000 ticks a part ends ("split: the length limit") and the
 * next starts at once with its own starting state.</li>
 * <li><b>Starting and ending by themselves</b>, with "Record my encounters" on and a page for the character: at a boss's
 * or a raid's area as RuneLite's Discord plugin names them (Presence.AREAS), and for the NPC ids listed in the settings
 * as his auto-record list (a listed NPC appearing or another NPC turning into one, as well as spawning); ended by the
 * kill (the game's kill or completion count line), the player's death (not in a raid, which goes on), or leaving (the
 * area, or no listed NPC, or one that turned from one, for 50 ticks). By hand from the side tab: started and stopped
 * there only.</li>
 * <li><b>Never in the Wilderness, a PvP area or on a PvP or Deadman world</b> (the snapshot's rule, OutsidePlugin.held):
 * none starts there, and one running stops the tick it's found there, that tick's lines never written.</li>
 * <li><b>Round four</b> (11/21/2026; ghillie's claude/outstation.md, "Encounters, round four"): a delve of Doom of
 * Mokhaiotl one recording from its first level (Doom's forms start it: no area of RuneLite's names its arena) through
 * every level's kill, window and descent, ended only by the player's death, or the claim (the game's claim script) and
 * the burrow's Leave (or the claim and out of the delve's instance); a corpse's forfeit (Araxxor's Destroy, the Royal
 * Titans' Sacrifice) as a loot line with nothing, "choice" saying which; and a pet: the game's line (a `pet` line), the
 * pet seen beside them or in the backpack (`pet_seen`), its chat line's picture, and the moment with it in.</li>
 * <li>Not his: mouse presses, clicks, their screen positions, the interface layout and markers are never recorded (a
 * replay doesn't need them and the page's strip would take them out anyway), and there's no overlay or hotkey.</li>
 * </ul>
 */
final class Encounters
{
	private static final Logger log = LoggerFactory.getLogger(Encounters.class);

	/** the end line's reason for a part split at the length limit (the plugin sends only the last part, the parts joined) */
	static final String SPLIT = "split: the length limit";

	/** Encounter Recorder's format these files keep to */
	static final int FORMAT_VERSION = 6;
	/** ticks a part runs before the next is split off (his length limit's default: 50 minutes) */
	static final int PART_TICKS = 5000;
	/** after the kill (its count line) or the player's death, how many ticks more are kept: the death and the loot */
	static final int AFTER_KILL = 5, AFTER_DEATH = 6;
	/** after a kill with no drop yet, how long the recording waits for one (11/21/2026: the scene moment holds on the
	 *  drop landing, and a long death animation drops it past AFTER_KILL); a drop seen keeps it to AFTER_KILL past it */
	static final int DROP_WAIT = 12;
	/** an automatic recording started for listed NPCs stops this many ticks after the last of them is seen (his) */
	static final int WATCHED_GONE = 50;
	/** ticks outside the area that started it before it counts as left: in a region no area names (a raid's room the
	 *  list misses, a map loading), a raid gets longer */
	static final int LEFT_BOSS = 2, LEFT_RAID = 50;
	/** Discord's bosses that are skilling: no recording starts by itself there */
	static final Set<String> SKILLING = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList("Wintertodt", "Tempoross")));
	/** how far round the player objects' animations are watched, in tiles */
	static final int OBJ_RADIUS = 32;

	// ---- raids whole, and loot from a chest, a reward window or a corpse (11/21/2026, round three; ghillie's
	// claude/outstation.md, "Encounters, round three"). Jace: "it should be the whole raid when it's a raid", and "A raid
	// should record from entering through clicking the door to leave the treasure/chest room at the end of every raid".

	/** after the loot of a boss outside a raid (a chest, a reward window, a corpse looted), ticks more kept */
	static final int LOOT_AFTER = 6;
	/** a raid completed: the most it waits for the player to leave the treasure room, in ticks (30 minutes) */
	static final int RAID_AFTER = 3000;
	/** a raid's whole recording, every part, at most (three hours): a safety end */
	static final int RAID_MOST = 18000;
	/** a kill with no drop on the ground and a corpse to loot: the most it waits for the corpse to be looted or go */
	static final int CORPSE_WAIT = 300;
	/** after the click on a corpse, the ticks within which the inventory's gain is its loot */
	static final int CORPSE_TAKE = 6;
	/** Discord's raid areas that are a part of another raid (the area that started it counts them as in it): Tombs of
	 *  Amascut's treasure room, Osmumten's Burial Chamber (region 14672, beside the raid's own 14674 and 14676) */
	static final Map<String, String> RAID_PARTS = Collections.singletonMap("Osmumten's Burial Chamber", "Tombs of Amascut");
	/** Discord's raid areas that are a lobby, not a raid: no recording starts by itself there (Tombs of Amascut's, in the
	 *  Jaltevas pyramid), and walking out to one is leaving */
	static final Set<String> LOBBIES = Collections.singleton("Jaltevas Pyramid");

	/**
	 * The rewards that come in a window, as RuneLite's Loot Tracker reads them (LootTrackerPlugin.onWidgetLoaded, 1.13.7):
	 * the window's interface, the inventory the game fills with the reward, and whether it's a raid's. Read straight
	 * from the game here (WidgetLoaded and ItemContainerChanged), so the Loot Tracker needn't be on. Doom of Mokhaiotl's
	 * pile is claimed by a script, not a window (the Loot Tracker's onScriptPreFired, DOM_LOOT_CLAIM); its window is the
	 * level's end (DOM_END_LEVEL_UI), whose title names the level.
	 */
	static final class Chest
	{
		final String source;
		final int window, inv;
		final boolean raid;

		Chest(String source, int window, int inv, boolean raid)
		{
			this.source = source;
			this.window = window;
			this.inv = inv;
			this.raid = raid;
		}
	}

	static final Chest[] CHESTS = {
		new Chest("Chambers of Xeric", InterfaceID.RAIDS_REWARDS, InventoryID.RAIDS_REWARDS, true),
		new Chest("Theatre of Blood", InterfaceID.TOB_CHESTS, InventoryID.TOB_CHESTS, true),
		new Chest("Tombs of Amascut", InterfaceID.TOA_CHESTS, InventoryID.TOA_CHESTS, true),
		new Chest("The Fractured Archive", InterfaceID.TFA_CHESTS, InventoryID.TFA_CHESTS, true),
		new Chest("Barrows", InterfaceID.BARROWS_REWARD, InventoryID.TRAIL_REWARDINV, false),
		new Chest("Lunar Chest", InterfaceID.PMOON_REWARD, InventoryID.PMOON_REWARDINV, false),
		new Chest("Fortis Colosseum", InterfaceID.COLOSSEUM_REWARD_CHEST_2, InventoryID.COLOSSEUM_REWARDS, false),
		new Chest("Doom of Mokhaiotl", InterfaceID.DOM_END_LEVEL_UI, InventoryID.DOM_LOOTPILE, false),
	};
	/** the client script the game runs as Doom of Mokhaiotl's pile is claimed (RuneLite's ScriptID.DOM_LOOT_CLAIM) */
	static final int DOM_LOOT_CLAIM = 7928;
	/** Theatre of Blood's treasure room and lobby (the Loot Tracker's THEATRE_OF_BLOOD_REGION and _LOBBY: its chests'
	 *  window is taken only there) */
	static final int TOB_CHEST_REGION = 12867, TOB_LOBBY = 14642;
	/** a menu option that opens or loots something (a chest, a corpse): where the loot came from is what was clicked */
	static final Set<String> LOOT_OPTIONS = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
		"Open", "Search", "Loot", "Harvest", "Claim", "Take", "Take-loot", "Collect", "Grab", "Plunder",
		// (round four: the Royal Titans' third choice, their desiccated pages into the inventory, the wiki's "Take pages")
		"Take pages")));
	/** a corpse's option that gives up its loot for a better chance at the pet (round four, 11/21/2026; the wiki):
	 *  Araxxor's "Destroy" (a prompt after), the Royal Titans' "Sacrifice" */
	static final Set<String> FORFEIT_OPTIONS = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList("Destroy", "Sacrifice")));

	// ---- round four (11/21/2026; ghillie's claude/outstation.md, "Encounters, round four"): Doom of Mokhaiotl's delve
	// whole, Araxxor's corpse harvested or destroyed, and a pet as the moment.

	/** Doom of Mokhaiotl as each of its forms (RuneLite's NpcID.DOM_BOSS, _SHIELDED, _BURROWED): no area of RuneLite's
	 *  names its arena, so one of them appearing starts a delve's recording */
	static final Set<Integer> DOOM_IDS = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
		net.runelite.api.gameval.NpcID.DOM_BOSS, net.runelite.api.gameval.NpcID.DOM_BOSS_SHIELDED,
		net.runelite.api.gameval.NpcID.DOM_BOSS_BURROWED)));
	static final String DOOM = "Doom of Mokhaiotl";
	/** a delve: ticks out of the delve's instance before it counts as left (unclaimed: a teleport out; claimed: 2) */
	static final int DELVE_OUT = 50, DELVE_LEFT = 2;
	/** the game's pet lines, as Dink's PetNotifier reads them: "You have a funny feeling like you're being followed.",
	 *  "You have a funny feeling like you would have been followed..." (one already owned), "You feel something weird
	 *  sneaking into your backpack." (a follower already out) */
	static final Pattern PET_LINE = Pattern.compile("^You (?:have a funny feeling like you|feel something weird sneaking).*");
	/** the game's lines that can name the pet a few ticks after (Dink's: the collection log's, an untradeable drop's) */
	static final Pattern PET_NAMED = Pattern.compile("^(?:New item added to your collection log|Untradeable drop): (.+?)\\s*$");
	/** ticks after a pet's line within which it appears (beside them, or in the backpack) */
	static final int PET_WAIT = 8;
	/** after the click on a corpse's Destroy, the ticks within which its going is the destroying (a prompt answered) */
	static final int DESTROY_WAIT = 100;

	/** the game's kill and completion count lines ("Your Vorkath kill count is: 418.", "Your completed Chambers of Xeric
	 *  count is: 12."), as Counts reads them */
	static final Pattern KILL_LINE = Pattern.compile("^Your (?:completed )?(.+?) (?:(?:kill|chest|completion|harvest|success) )?count is: ?([\\d,]+)\\b.*$");
	/** the raids' friends-chat lines kept: a completion or a time (everything else there can be about other players) */
	static final Pattern COMPLETION = Pattern.compile("(?i)raid is complete|team size|duration|personal best|completion time|challenge complete|congratulations");

	/** what the recorder needs from the plugin */
	interface Host
	{
		/** the plugin's own folder for its files (Plugin.getPluginDirectory) */
		Filepath folder() throws IOException;

		/** whether the character logged in has an Outside page (recording needs one) */
		boolean hasPage();

		/** "Record my encounters" */
		boolean autoOn();

		/** "Players around you" */
		boolean otherPlayers();

		/** the NPC ids listed to start one */
		String npcIds();

		/** the folder's size cap, in bytes */
		long most();

		/** the plugin's version, for the header */
		String version();

		/** a recording's started, is running (its words) or stopped: the side tab's line (null: none running) */
		void recording(String words, boolean running);

		/** a recording's packed (its file name), or files changed: the side tab's list read again */
		void filesChanged(String packed);

		/** a ground item's name, colour and value as RuneLite's Ground Items plugin shows them ({text, "#rrggbb", value as a
		 *  Long}), or null; on the client's thread (11/21/2026, the scene moment's labels) */
		Object[] itemLabel(int id, int qty);

		/** a recording's ended: its file once packed ({@code packed}), why, and what its scene moment is made from (null:
		 *  none, as for one stopped in the Wilderness); on the client's thread (11/21/2026, "Encounters, round two") */
		void ended(String packed, EncounterScene.Taken taken, String reason);

		/** an item's GE price (RuneLite's own) and high alchemy value, each, as the Ground Items plugin reads them (a
		 *  noted item's, the item it notes): {ge, ha}; on the client's thread (11/21/2026, round three) */
		long[] prices(int id);

		/** the next frame the client draws, cropped to {@code bounds} on the canvas, as PNG bytes to {@code done} (null:
		 *  none); asked on the client's thread, answered on whichever (11/21/2026, round three: the reward window as the
		 *  player saw it) */
		void picture(java.awt.Rectangle bounds, java.util.function.Consumer<byte[]> done);
	}

	private final Client client;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final Gson gson;
	private final Host host;

	private volatile EncounterFiles.Writer recording;
	/** the recording's scene, for its moment (11/21/2026; EncounterScene): its frames kept on this thread */
	private EncounterScene scene;
	/** recordings stopped but still being packed: closing the client waits for them */
	private final Set<EncounterFiles.Writer> finishing = ConcurrentHashMap.newKeySet();
	private final Set<Integer> definedNpcs = new HashSet<>();
	private final Set<Integer> definedObjects = new HashSet<>();
	private final Set<Integer> definedItems = new HashSet<>();
	private final Map<Actor, Integer> moveSets = new WeakHashMap<>();
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new WeakHashMap<>());
	private boolean players;
	private boolean bankPinOpen;
	private boolean sceneDirty;
	private LocalPoint lastDestination;
	/** each actor's animation and frame at the last client cycle (anim << 32 | frame), for restarts */
	private final Map<Actor, Long> lastFrames = new WeakHashMap<>();
	/** each animated object's renderable at the last look, by its hash and floor; objects spawned this tick */
	private final Map<Long, Renderable> animated = new HashMap<>();
	private final Set<Long> spawnedThisTick = new HashSet<>();

	// (what started the recording running, and how it's to end)
	private boolean automatic;
	private Presence.Area area;
	private Set<Integer> watchedIds = Collections.emptySet();
	/** NPC indices that were a listed NPC, so one turning into another form still counts */
	private final Set<Integer> watchedIndices = new HashSet<>();
	private int sinceWatched, outside;
	/** the tick the recording ends on (the kill, a death), and why; -1 none due */
	private int endAt = -1;
	private String endWhy;
	private String label;
	private int part;
	// (between recordings: the area last stood in; one stopped by hand there, or ended by its kill, waits)
	private Presence.Area lastArea;
	private Presence.Area handStopped;
	private Presence.Area afterKill;
	private int tickNow;
	// (round three, 11/21/2026: a raid whole and its loot)
	/** the ticks of the parts before this one (the scene counts the whole recording's ticks) */
	private int partBase;
	/** the tick (the whole recording's) a raid's completion line came, or -1 */
	private int raidDone = -1;
	/** the loot written this recording, by source (one each: the window's opened again, an item taken, makes none) */
	private final Set<String> looted = new HashSet<>();
	/** a reward window open with its inventory still empty: written when the inventory fills, a few ticks at most */
	private Chest waitingFor;
	private int waitingUntil;
	/** what the player last chose to open or loot (a menu option on an object or an NPC: never written, only its place
	 *  kept for the loot line): its tile, floor, id, kind ("obj" or "npc"), name, NPC index, and the tick */
	private LocalPoint clickedAt;
	private int clickedPlane, clickedId, clickedIndex = -1, clickedTick = -1000;
	private String clickedKind, clickedName, clickedOption;
	/** the inventory as it was, item by item (id to count), for a corpse's loot (what it gained after the click) */
	private Map<Integer, Long> invBefore = new HashMap<>();
	/** the loot's window whose picture is to be taken, its interface (-1: the inventory), client cycles to go */
	private EncounterScene.Window pictureOf;
	private int pictureGroup, pictureIn;
	/** the pet's chat line whose picture is to be taken, the cycles to go and the tries left (round four) */
	private EncounterScene.Window chatShot;
	private int chatShotIn, chatShotTries;
	/** a boss's corpse waiting to be looted (its name; the kill's tick), or null */
	private String corpseOf;
	private int corpseSince;
	// (round four, 11/21/2026)
	/** a delve of Doom of Mokhaiotl: one recording from its first level to the death, or the claim and the leaving */
	private boolean delve, delveClaimed;
	/** ticks out of an instance in a delve; the tick the burrow's Leave was pressed (-1 not) */
	private int delveOut, delveLeave = -1;
	/** a delve stopped by hand: none starts again till they're out of its instance */
	private boolean delveStopped;
	/** a pet's line came (its tick, -1 none) and it's still to be seen: the follower that was out before it (its index,
	 *  -1 none), the inventory as it was */
	private int petAt = -1;
	private boolean petWaiting;
	private int followerBefore = -1;
	private Map<Integer, Long> invAtPet = new HashMap<>();
	/** the pet's chat line, its picture to be taken (round four: as the reward window's) */
	private EncounterScene.Window petChat;
	/** Destroy chosen on a corpse: its NPC index and the tick (-1 none), the inventory then */
	private int destroyIndex = -1, destroyTick = -1;
	private String destroyName;
	private int destroyId;
	private Map<Integer, Long> invAtDestroy = new HashMap<>();

	Encounters(Client client, net.runelite.client.callback.ClientThread clientThread, Gson gson, Host host)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.gson = gson;
		this.host = host;
	}

	boolean running()
	{
		return recording != null;
	}

	/** the file being written now, or null */
	String writing()
	{
		EncounterFiles.Writer r = recording;
		return r == null ? null : r.fileName();
	}

	/** the files being written or packed (the size cap and the side tab's ✕ leave them alone) */
	Set<String> busy()
	{
		Set<String> s = new HashSet<>();
		EncounterFiles.Writer r = recording;
		if (r != null)
		{
			s.add(r.fileName());
		}
		for (EncounterFiles.Writer f : finishing)
		{
			s.add(f.fileName());
		}
		return s;
	}

	/** the packing still under way (closing the client waits for it) */
	List<Future<?>> packing()
	{
		List<Future<?>> l = new ArrayList<>();
		for (EncounterFiles.Writer f : finishing)
		{
			if (f.finished() != null)
			{
				l.add(f.finished());
			}
		}
		return l;
	}

	// ---- starting and stopping (the client's thread)

	/** "Record now" (the side tab, on the client's thread): a recording by hand, named after where they are. Returns why
	 *  not, or null. */
	String startByHand()
	{
		if (recording != null)
		{
			return null;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return "log in first";
		}
		if (danger())
		{
			return "never in the Wilderness, a PvP area or on a PvP world";
		}
		handStopped = null;
		start(unnamedLabel(), false, null, Collections.emptySet(), 1, null, null, 0);
		return null;
	}

	/** "Stop" (the side tab): the recording running stopped; one started by an area doesn't start again till they've
	 *  left it. */
	void stopByHand()
	{
		if (recording != null)
		{
			if (automatic && area != null)
			{
				handStopped = area;
			}
			stop("stopped by hand", false);
		}
	}

	/** The plugin's off, or the client's closing: the recording stops. */
	void shutDown(String why)
	{
		if (recording != null)
		{
			stop(why, false);
		}
	}

	/** A new recording; for the next part of one split at the length limit, {@code keep} is its scene (its frames and
	 *  anchors go on: the moment is the whole recording's) and {@code base} the ticks of the parts before. */
	private void start(String name, boolean auto, Presence.Area at, Set<Integer> watched, int partNo, String follows,
		EncounterScene keep, int base)
	{
		if (name == null || name.trim().isEmpty())
		{
			name = unnamedLabel();
		}
		label = name;
		automatic = auto;
		area = at;
		watchedIds = watched;
		watchedIndices.clear();
		sinceWatched = 0;
		outside = 0;
		endAt = -1;
		endWhy = null;
		part = partNo;
		partBase = base;
		if (keep == null)
		{
			// (a new encounter: nothing of the last one's raid or loot)
			raidDone = -1;
			looted.clear();
			corpseOf = null;
			waitingFor = null;
			delve = false;
			delveClaimed = false;
			delveOut = 0;
			delveLeave = -1;
			petAt = -1;
			petWaiting = false;
			petChat = null;
			destroyIndex = -1;
			destroyTick = -1;
		}
		recording = new EncounterFiles.Writer(gson, () -> host.folder().joinSegment("encounters"), name, LocalDateTime.now());
		scene = keep != null ? keep : new EncounterScene(client, host::itemLabel, host.otherPlayers());
		definedNpcs.clear();
		definedObjects.clear();
		definedItems.clear();
		moveSets.clear();
		lastFrames.clear();
		// Fixed for the whole recording, so a file never switches halfway between with and without players
		players = host.otherPlayers();
		seenProjectiles.clear();
		lastDestination = null;

		JsonObject header = emit("header");
		header.addProperty("format", FORMAT_VERSION);
		header.addProperty("label", name);
		header.addProperty("automatic", auto);
		header.addProperty("revision", client.getRevision());
		header.addProperty("world", client.getWorld());
		JsonArray worldTypes = new JsonArray();
		client.getWorldType().forEach(type -> worldTypes.add(type.name()));
		header.add("worldTypes", worldTypes);
		header.addProperty("startedMs", System.currentTimeMillis());
		header.addProperty("startTick", client.getTickCount());
		header.addProperty("otherPlayers", players);
		header.addProperty("playerNames", true);
		header.addProperty("localChat", true);
		header.addProperty("sceneLayout", true);
		header.addProperty("vars", true);
		header.addProperty("canvasW", client.getCanvasWidth());
		header.addProperty("canvasH", client.getCanvasHeight());
		header.addProperty("resized", client.isResized());
		// (Outside's own fields)
		header.addProperty("recorder", "outside");
		header.addProperty("recorderVersion", host.version());
		Player me = client.getLocalPlayer();
		if (me != null && me.getName() != null)
		{
			header.addProperty("player", Text.removeTags(me.getName()).replace(' ', ' '));
		}
		if (at != null)
		{
			header.addProperty("area", at.name);
		}
		if (partNo > 1)
		{
			header.addProperty("part", partNo);
			header.addProperty("follows", follows);
		}

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			startingState();
		}
		log.debug("outside: encounter {} started", recording.fileName());
		host.recording(words(), true);
	}

	/** What the game is as the recording begins: his start snapshot, then Outside's starting state. */
	private void startingState()
	{
		dumpScene();
		WorldView wv = client.getTopLevelWorldView();
		for (NPC npc : wv.npcs())
		{
			npcSpawned(npc, true);
		}
		if (client.getLocalPlayer() != null)
		{
			look(client.getLocalPlayer());
		}
		if (players)
		{
			for (Player player : wv.players())
			{
				if (player != client.getLocalPlayer())
				{
					JsonObject spawn = emit("player_spawn");
					spawn.add("who", EncounterSnap.ref(client, player));
					nameOf(spawn, player);
					look(player);
				}
			}
		}
		emit("ui_tab").addProperty("tab", client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL));
		containers(client.getItemContainer(InventoryID.INV), "inv");
		containers(client.getItemContainer(InventoryID.WORN), "worn");
		invBefore = counts(client.getItemContainer(InventoryID.INV));
		for (Skill skill : Skill.values())
		{
			JsonObject s = emit("stat");
			s.addProperty("skill", skill.name());
			s.addProperty("xp", client.getSkillExperience(skill));
			s.addProperty("level", client.getRealSkillLevel(skill));
			s.addProperty("boosted", client.getBoostedSkillLevel(skill));
		}
		// (Outside's: the varps already set, as var lines of a whole varp)
		if (!bankPinShowing())
		{
			int[] varps = client.getVarps();
			for (int i = 0; varps != null && i < varps.length; i++)
			{
				if (varps[i] != 0)
				{
					JsonObject v = emit("var");
					v.addProperty("varp", i);
					v.addProperty("varbit", -1);
					v.addProperty("value", varps[i]);
					v.addProperty("init", true);
				}
			}
		}
		// (the ground items already lying in the scene, as his item lines)
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getTiles();
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					List<TileItem> items = tile == null ? null : tile.getGroundItems();
					if (items == null)
					{
						continue;
					}
					for (TileItem item : items)
					{
						defineItem(item.getId());
						JsonObject line = emit("item");
						line.addProperty("id", item.getId());
						line.addProperty("qty", item.getQuantity());
						EncounterSnap.tile(line, wv, tile.getLocalLocation(), tile.getPlane());
						line.addProperty("init", true);
					}
				}
			}
		}
		// (the graphics already on tiles, and the projectiles already in flight)
		for (GraphicsObject gfx : wv.getGraphicsObjects())
		{
			if (!gfx.finished())
			{
				gfxLine(gfx).addProperty("init", true);
			}
		}
		for (Projectile proj : client.getProjectiles())
		{
			if (seenProjectiles.add(proj))
			{
				JsonObject line = emit("proj");
				EncounterSnap.projectile(line, client, wv, proj);
				line.addProperty("init", true);
			}
		}
		// (each animated object as it is now, so only a change from here is written)
		animated.clear();
		scanObjects(false);
	}

	/**
	 * Stops the recording: its end line, then it's closed and packed on its own thread. {@code dropTick}: the lines of
	 * the tick under way never written (somewhere dangerous).
	 */
	private void stop(String reason, boolean dropTick)
	{
		stop(reason, dropTick, false);
	}

	/** {@code split}: the part ends at the length limit and the next goes on (its scene kept, no moment yet, and nothing
	 *  sent: the parts go up joined, as one, once the last has ended; EncounterShelf.prepare). */
	private void stop(String reason, boolean dropTick, boolean split)
	{
		EncounterFiles.Writer stopped = recording;
		if (dropTick)
		{
			stopped.dropPending();
		}
		JsonObject end = emit("end");
		end.addProperty("reason", reason);
		end.addProperty("ticks", stopped.ticks());
		end.addProperty("endedMs", System.currentTimeMillis());
		// (its scene moment's makings: nothing from anywhere dangerous; the rest built on the plugin's moment thread)
		EncounterScene.Taken taken = null;
		if (scene != null && !dropTick && !split)
		{
			try
			{
				taken = scene.finish(partBase + stopped.ticks() - 1);
			}
			catch (RuntimeException e)
			{
				log.warn("outside: the encounter's scene couldn't be taken", e);
			}
		}
		if (taken == null && !dropTick)
		{
			log.info("outside: encounter {} has no scene moment (nothing of the scene was taken)", stopped.fileName());
		}
		if (!split)
		{
			scene = null;
			// (a delve ended, however: Doom's forms still about while the player falls or leaves start none again till
			// they're out of its instance; round four)
			if (delve)
			{
				delveStopped = true;
			}
		}
		host.ended(stopped.fileName() + EncounterFiles.EXT, taken, reason);
		finishing.add(stopped);
		recording = null;
		stopped.close(packed ->
		{
			finishing.remove(stopped);
			host.filesChanged(packed);
		});
		log.debug("outside: encounter {} stopped: {}", stopped.fileName(), reason);
		host.recording(null, false);
	}

	/** A part at the length limit ended, and the next begun at once (Outside's: his stops there). */
	private void split()
	{
		// (the part before as it'll be once packed, a moment from now)
		String follows = recording.fileName() + EncounterFiles.EXT;
		boolean auto = automatic;
		Presence.Area at = area;
		Set<Integer> watched = watchedIds;
		Set<Integer> indices = new HashSet<>(watchedIndices);
		String name = label;
		int next = part + 1;
		int base = partBase + recording.ticks();
		EncounterScene kept = scene;
		stop(SPLIT, false, true);
		start(name, auto, at, watched, next, follows, kept, base);
		watchedIndices.addAll(indices);
	}

	private String words()
	{
		EncounterFiles.Writer r = recording;
		if (r == null)
		{
			return null;
		}
		int s = r.ticks() * 6 / 10;
		return String.format("%s%s · %d:%02d · %,d KB%s", label, part > 1 ? " (part " + part + ")" : "", s / 60, s % 60,
			r.bytesWritten() / 1024, r.hasFailed() ? " · writing failed: see the client's log" : "");
	}

	// ---- where they are

	/** In the Wilderness, a PvP area, or on a PvP or Deadman world (OutsidePlugin.held, the snapshot's rule). */
	private boolean danger()
	{
		Player me = client.getLocalPlayer();
		WorldPoint at = OutsidePlugin.here(client, me);
		int x = at == null ? -1 : at.getX(), y = at == null ? -1 : at.getY(), region = at == null ? -1 : at.getRegionID();
		EnumSet<WorldType> types = client.getWorldType();
		boolean pvpWorld = WorldType.isPvpWorld(types) || types.contains(WorldType.DEADMAN);
		boolean wild = OutsidePlugin.wilderness(x, y, client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1);
		boolean pvpArea = OutsidePlugin.pvpArea(x, y, region, client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) == 1);
		return OutsidePlugin.held(pvpWorld, wild, pvpArea);
	}

	/** The boss's or raid's area they stand in, as Discord names it (an instance's by its template), or null; not the
	 *  skilling bosses Discord lists with the rest, where a recording would run as long as they skill. */
	private Presence.Area bossArea()
	{
		Presence.Area a = here();
		return a != null && (Presence.BOSS.equals(a.kind) || Presence.RAID.equals(a.kind)) && !SKILLING.contains(a.name)
			&& !LOBBIES.contains(a.name) && !RAID_PARTS.containsKey(a.name) ? a : null;
	}

	/** Whether where they are ({@code now}) is the area a recording started at: the same, one of the same name, or a part
	 *  of it (Tombs of Amascut's treasure room). */
	static boolean sameArea(Presence.Area now, Presence.Area area)
	{
		return now != null && area != null && (now == area || now.name.equals(area.name) || area.name.equals(RAID_PARTS.get(now.name)));
	}

	/** The raid they're in, for the recording's endings: the one that started it, else the raid they stand in (a part of
	 *  one as the raid itself; a lobby isn't one), or null. */
	private Presence.Area raidArea()
	{
		if (area != null)
		{
			return Presence.RAID.equals(area.kind) ? area : null;
		}
		Presence.Area h = here();
		return h != null && Presence.RAID.equals(h.kind) && !LOBBIES.contains(h.name) ? h : null;
	}

	/** Whether a count line in a raid is the raid's own completion ("Your completed Chambers of Xeric count is: 12."),
	 *  not a boss's inside it. */
	static boolean raidCompletion(String text, String counted, String raid)
	{
		if (text != null && text.startsWith("Your completed "))
		{
			return true;
		}
		String c = counted == null ? "" : counted.toLowerCase().replaceFirst("^the ", "");
		String r = raid == null ? "" : raid.toLowerCase().replaceFirst("^the ", "");
		return !c.isEmpty() && !r.isEmpty() && (c.contains(r) || r.contains(c));
	}

	private Presence.Area here()
	{
		Player me = client.getLocalPlayer();
		WorldPoint at = OutsidePlugin.here(client, me);
		return at == null ? null : Presence.area(at.getRegionID(), at.getPlane());
	}

	/**
	 * A name for a recording: the area they're in, as Discord names it, or else the highest-level NPC around (usually the
	 * boss), or else the nearest NPC; empty if there's none of those ("session"). (His names a place from his own copy of
	 * the Discord plugin's list; this one is Presence's.)
	 */
	private String unnamedLabel()
	{
		Player me = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (me == null || wv == null)
		{
			return "";
		}
		Presence.Area a = here();
		if (a != null)
		{
			return a.name;
		}
		NPC strongest = null;
		NPC nearest = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (NPC npc : wv.npcs())
		{
			String name = npc.getName();
			if (name == null || name.equals("null"))
			{
				continue;
			}
			if (npc.getCombatLevel() > 0 && (strongest == null || npc.getCombatLevel() > strongest.getCombatLevel()))
			{
				strongest = npc;
			}
			int distance = npc.getWorldLocation().distanceTo(me.getWorldLocation());
			if (distance < nearestDistance)
			{
				nearest = npc;
				nearestDistance = distance;
			}
		}
		NPC named = strongest != null ? strongest : nearest;
		return named == null ? "" : Text.removeTags(named.getName());
	}

	static Set<Integer> parseIds(String text)
	{
		Set<Integer> ids = new HashSet<>();
		for (String part : Text.fromCSV(text == null ? "" : text))
		{
			try
			{
				ids.add(Integer.parseInt(part.trim()));
			}
			catch (NumberFormatException e)
			{
				// (not a number: left out)
			}
		}
		return Collections.unmodifiableSet(ids);
	}

	/** May one start by itself now: switched on, a page, logged in, nowhere dangerous. */
	private boolean mayStart()
	{
		return recording == null && host.autoOn() && host.hasPage() && client.getGameState() == GameState.LOGGED_IN && !danger();
	}

	/** the whole recording's tick (every part's): the scene's and the endings' clock */
	private int whole()
	{
		return partBase + (recording == null ? 0 : recording.ticks());
	}

	private JsonObject emit(String event)
	{
		JsonObject line = EncounterSnap.line(event, recording.ticks(), client.getGameCycle());
		recording.add(line);
		return line;
	}

	/**
	 * Closing the window doesn't log out first, so a recording still running is stopped here. The client waits (up to
	 * 10 seconds, shared with other plugins) for it and any still packing to finish; if it can't, the plain file is left
	 * whole and packed the next time the plugin starts (his)
	 */
	@Subscribe
	public void onClientShutdown(net.runelite.client.events.ClientShutdown event)
	{
		java.util.concurrent.CompletableFuture<Void> stopped = new java.util.concurrent.CompletableFuture<>();
		clientThread.invoke(() ->
		{
			if (recording != null)
			{
				stop("client closed", false);
			}
			// Queued before stopped completes, so the client waits for these too
			for (Future<?> packed : packing())
			{
				event.waitFor(packed);
			}
			stopped.complete(null);
		});
		event.waitFor(stopped);
	}

	// ---- each tick and each client cycle

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickNow++;
		if (recording == null)
		{
			autoStartByArea();
			return;
		}
		// (somewhere dangerous: stopped, this tick's lines never written)
		if (danger())
		{
			stop("the Wilderness or PvP", true);
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (scene != null)
		{
			scene.tick(whole());
		}
		JsonObject tick = emit("tick");
		tick.addProperty("abs", client.getTickCount());
		tick.addProperty("ms", System.currentTimeMillis());

		Player me = client.getLocalPlayer();
		if (me != null)
		{
			tick.add("me", self(me));
			moves(me);
		}
		tick.add("ui", ui());

		boolean watchedSeen = false;
		JsonArray npcs = new JsonArray();
		for (NPC npc : wv.npcs())
		{
			npcs.add(EncounterSnap.actor(client, npc));
			moves(npc);
			if (watchedIds.contains(npc.getId()))
			{
				watchedIndices.add(npc.getIndex());
			}
			// (Doom of Mokhaiotl about: a delve, however this recording started; round four)
			delve |= DOOM_IDS.contains(npc.getId());
			watchedSeen |= watchedIds.contains(npc.getId()) || watchedIndices.contains(npc.getIndex());
		}
		tick.add("npcs", npcs);

		if (players)
		{
			JsonArray others = new JsonArray();
			for (Player player : wv.players())
			{
				if (player != me)
				{
					others.add(EncounterSnap.actor(client, player));
					moves(player);
				}
			}
			tick.add("players", others);
		}
		scanObjects(true);
		spawnedThisTick.clear();
		recording.endTick();

		// (a reward window open whose inventory hadn't filled yet: read again, then let go; round three)
		if (waitingFor != null)
		{
			Chest w = waitingFor;
			List<int[]> got = itemsOf(client.getItemContainer(w.inv));
			if (!got.isEmpty())
			{
				loot(w.source, got, w.window, false);
			}
			else if (whole() > waitingUntil)
			{
				waitingFor = null;
			}
		}

		// (a pet's line: the pet looked for beside them, as the follower that wasn't out before; round four)
		if (petWaiting)
		{
			petLook();
		}

		// (how it ends: the kill or a death a few ticks on, leaving, the listed NPCs gone)
		if (endAt >= 0 && whole() >= endAt && petHolds())
		{
			// (a pet's come: held till it's seen and a few ticks more, so the moment has it; round four)
		}
		else if (endAt >= 0 && whole() >= endAt)
		{
			// (a kill with no drop yet: held a little for it, the moment's anchor (11/21/2026); and a corpse to loot, till
			// it's looted or goes (round three: Araxxor's, as the game's own loot needs a click on what's left))
			boolean unlooted = "killed".equals(endWhy) && scene != null && scene.killed() && !scene.dropped() && !scene.looted();
			boolean waiting = unlooted && (whole() < scene.killTick() + DROP_WAIT || corpseWaiting());
			if (!waiting)
			{
				stopAuto(endWhy);
				return;
			}
		}
		if (automatic && delve)
		{
			// (a delve (Jace, 11/21/2026): every level one recording, through each level's end, its window and the descent;
			// it ends with the player's death (above), or the claim and the burrow's Leave a few ticks on (its click),
			// or the claim and out of the delve's instance; unclaimed and out of it a while, a teleport: left)
			delveOut = client.getTopLevelWorldView().isInstance() ? 0 : delveOut + 1;
			if (endAt < 0 && delveClaimed && delveOut > DELVE_LEFT)
			{
				stopAuto("claimed");
				return;
			}
			if (endAt < 0 && !delveClaimed && delveOut > DELVE_OUT)
			{
				stopAuto("left");
				return;
			}
			if (whole() >= RAID_MOST)
			{
				stopAuto("the time limit");
				return;
			}
		}
		else if (automatic && area != null)
		{
			Presence.Area now = here();
			boolean in = sameArea(now, area);
			outside = in ? 0 : outside + 1;
			boolean raid = Presence.RAID.equals(area.kind);
			// (a raid is whole from entering to leaving its treasure room (Jace, 11/21/2026): its completion line, a
			// room's "Challenge complete", a sub-boss's count, the loot and a death all go on; once it's completed or
			// looted, the first ticks out of it are the end, the click on the exit and the map leaving the raid)
			boolean done = raid && (raidDone >= 0 || !looted.isEmpty());
			// (in an area of another name: left at once; where no area's named, after a few ticks, a raid's longer
			// before it's done: a room the list misses, a map loading)
			if (!in && (now != null || outside > (raid && !done ? LEFT_RAID : LEFT_BOSS)))
			{
				stopAuto(done ? "completed" : "left");
				return;
			}
			if (raid && raidDone >= 0 && whole() >= raidDone + RAID_AFTER)
			{
				stopAuto("completed");
				return;
			}
			if (raid && whole() >= RAID_MOST)
			{
				stopAuto("the time limit");
				return;
			}
		}
		else if (automatic)
		{
			sinceWatched = watchedSeen ? 0 : sinceWatched + 1;
			if (sinceWatched >= WATCHED_GONE)
			{
				stopAuto("watched NPCs gone");
				return;
			}
		}
		if (recording.ticks() >= PART_TICKS)
		{
			split();
			return;
		}
		if (recording.ticks() % 5 == 0)
		{
			host.recording(words(), true);
		}
	}

	/** An automatic recording's end: after the kill or a death in the area, the next starts only with the next fight. */
	private void stopAuto(String why)
	{
		Presence.Area a = area;
		stop(why, false);
		if (a != null && ("killed".equals(why) || "died".equals(why) || "completed".equals(why) || "looted".equals(why)
			|| "claimed".equals(why)))
		{
			afterKill = a;
		}
	}

	/** Not recording: at a boss's or a raid's area, one starts (not again where one was stopped by hand, or just ended by
	 *  its kill, till the next fight there). */
	private void autoStartByArea()
	{
		if (delveStopped && client.getGameState() == GameState.LOGGED_IN && !client.getTopLevelWorldView().isInstance())
		{
			delveStopped = false;
		}
		Presence.Area a = client.getGameState() == GameState.LOGGED_IN ? bossArea() : null;
		if (a == null || lastArea == null || !a.name.equals(lastArea.name))
		{
			// (left, or somewhere new: what waited there is forgotten)
			handStopped = null;
			afterKill = null;
		}
		lastArea = a;
		if (a == null || !mayStart() || a == handStopped || a == afterKill)
		{
			return;
		}
		start(a.name, true, a, Collections.emptySet(), 1, null, null, 0);
	}

	/** After a kill in the area: the next fight (a hitsplat on them, or on an NPC from them) starts the next one. */
	private void fightAgain()
	{
		if (afterKill != null && recording == null && mayStart() && bossArea() == afterKill)
		{
			Presence.Area a = afterKill;
			afterKill = null;
			start(a.name, true, a, Collections.emptySet(), 1, null, null, 0);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (recording == null)
		{
			return;
		}
		// (the scene moment's frames: the moving things as the client poses them, every 2 cycles; 11/21/2026)
		if (scene != null)
		{
			scene.cycle();
		}
		// (the loot's window as the player sees it: its title read again now its scripts have run, its box, the next
		// frame cropped to it; round three)
		if (pictureOf != null && --pictureIn <= 0)
		{
			EncounterScene.Window w = pictureOf;
			pictureOf = null;
			if (w.title == null && pictureGroup >= 0)
			{
				w.title = windowTitle(pictureGroup);
			}
			java.awt.Rectangle bounds = pictureGroup >= 0 ? windowBounds(pictureGroup) : inventoryBounds();
			if (bounds != null && bounds.width > 0 && bounds.height > 0)
			{
				host.picture(bounds, png ->
				{
					w.pw = bounds.width;
					w.ph = bounds.height;
					w.picture = png;
				});
			}
		}
		// (the pet's chat line as the chatbox drew it: once the line's there, the next frame cropped to its row; round four)
		if (chatShot != null && --chatShotIn <= 0)
		{
			EncounterScene.Window w = chatShot;
			java.awt.Rectangle bounds = chatLineBounds(w.title);
			if (bounds != null)
			{
				chatShot = null;
				host.picture(bounds, png ->
				{
					w.pw = bounds.width;
					w.ph = bounds.height;
					w.picture = png;
				});
			}
			else if (--chatShotTries <= 0)
			{
				chatShot = null;
			}
			else
			{
				chatShotIn = 2;
			}
		}
		// Where the client is walking you, each time it changes: the tile it puts the red flag on (his dest line,
		// without the click it came from: clicks aren't recorded here)
		LocalPoint destination = client.getLocalDestinationLocation();
		boolean changed = destination != null && !destination.equals(lastDestination);
		lastDestination = destination;
		if (changed && !bankPinShowing())
		{
			JsonObject line = emit("dest");
			EncounterSnap.tile(line, client.getTopLevelWorldView(), destination);
			line.remove("lx");
			line.remove("ly");
		}
		// (Outside's: a projectile over before it moves posts no ProjectileMoved; the client's list has it a cycle)
		WorldView wv = client.getTopLevelWorldView();
		for (Projectile proj : client.getProjectiles())
		{
			if (seenProjectiles.add(proj))
			{
				EncounterSnap.projectile(emit("proj"), client, wv, proj);
			}
		}
		// (Outside's: an animation sent again restarts with no event: its frame back while it hadn't reached its end)
		for (NPC npc : wv.npcs())
		{
			restart(npc);
		}
		Player me = client.getLocalPlayer();
		if (me != null)
		{
			restart(me);
		}
		if (players)
		{
			for (Player p : wv.players())
			{
				if (p != me)
				{
					restart(p);
				}
			}
		}
	}

	private void restart(Actor actor)
	{
		int a = actor.getAnimation(), f = actor.getAnimationFrame();
		Long was = lastFrames.put(actor, ((long) a << 32) | (f & 0xffffffffL));
		if (was == null || a == -1 || (int) (was >> 32) != a)
		{
			return;
		}
		int before = (int) (long) was;
		if (f >= before || f > 1)
		{
			return;
		}
		Animation anim = client.loadAnimation(a);
		int last = anim == null ? Integer.MAX_VALUE : anim.isMayaAnim() ? anim.getDuration() - 1 : anim.getNumFrames() - 1;
		if (isRestart(before, f, last))
		{
			JsonObject line = emit("anim");
			line.add("who", EncounterSnap.ref(client, actor));
			line.addProperty("a", a);
			line.addProperty("o", actor.getCurrentOrientation());
			EncounterSnap.actorTile(line, actor);
			line.addProperty("restart", true);
		}
	}

	/** Whether an animation's frame going from {@code before} to {@code now} (the same animation, the next cycle) is a
	 *  restart: back near its start from short of its last frame ({@code last}), where a loop wraps only from its last. */
	static boolean isRestart(int before, int now, int last)
	{
		return now < before && now <= 1 && before < last;
	}

	/**
	 * Objects whose animation changed since the last look (Outside's obj_anim lines): the scene within OBJ_RADIUS
	 * tiles, each object drawn by a DynamicObject other than the one last seen (the client makes a new one for each
	 * animation the server sends it). {@code write}: lines written, else only noted (the starting state).
	 */
	private void scanObjects(boolean write)
	{
		Player me = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (me == null || wv == null)
		{
			return;
		}
		LocalPoint at = me.getLocalLocation();
		if (at == null || at.getWorldView() != wv.getId())
		{
			at = OutsidePlugin.onMainWorld(wv, at);
		}
		if (at == null)
		{
			return;
		}
		Tile[][][] tiles = wv.getScene().getTiles();
		int cx = at.getSceneX(), cy = at.getSceneY();
		Set<Long> seen = new HashSet<>();
		for (int z = 0; z < tiles.length; z++)
		{
			for (int x = Math.max(0, cx - OBJ_RADIUS); x <= Math.min(tiles[z].length - 1, cx + OBJ_RADIUS); x++)
			{
				for (int y = Math.max(0, cy - OBJ_RADIUS); y <= Math.min(tiles[z][x].length - 1, cy + OBJ_RADIUS); y++)
				{
					Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}
					for (GameObject obj : tile.getGameObjects())
					{
						if (obj != null && obj.getSceneMinLocation().equals(tile.getSceneLocation()))
						{
							objectAnim(obj, write, seen);
						}
					}
					objectAnim(tile.getWallObject(), write, seen);
					objectAnim(tile.getDecorativeObject(), write, seen);
					objectAnim(tile.getGroundObject(), write, seen);
				}
			}
		}
		animated.keySet().retainAll(seen);
	}

	/** An object's key among those watched: its hash (where it is and what) and its floor */
	private static long objectKey(TileObject obj)
	{
		return (obj.getHash() << 2) ^ obj.getPlane();
	}

	private void objectAnim(TileObject obj, boolean write, Set<Long> seen)
	{
		if (obj == null)
		{
			return;
		}
		Renderable r = EncounterSnap.renderable(obj);
		if (!(r instanceof DynamicObject))
		{
			return;
		}
		long key = objectKey(obj);
		seen.add(key);
		Renderable was = animated.put(key, r);
		if (!write || was == r || spawnedThisTick.contains(key))
		{
			return;
		}
		int anim = EncounterSnap.animation(r);
		if (anim == -1)
		{
			return;
		}
		JsonObject line = emit("obj_anim");
		line.addProperty("id", obj.getId());
		line.addProperty("kind", EncounterSnap.kind(obj));
		line.addProperty("anim", anim);
		EncounterSnap.tile(line, obj.getWorldView(), obj.getLocalLocation(), obj.getPlane());
		line.addProperty("pl", obj.getPlane());
	}

	// ---- his tick lines

	/**
	 * Writes an actor's stand, walk, run and turn animations when they first appear and whenever they change
	 */
	private void moves(Actor actor)
	{
		JsonArray set = EncounterSnap.moveSet(actor);
		Integer hash = set.hashCode();
		if (!hash.equals(moveSets.put(actor, hash)))
		{
			JsonObject line = emit("moves");
			line.add("who", EncounterSnap.ref(client, actor));
			line.add("anims", set);
		}
	}

	private JsonObject self(Player me)
	{
		JsonObject m = EncounterSnap.actor(client, me);
		m.addProperty("hpNow", client.getBoostedSkillLevel(Skill.HITPOINTS));
		m.addProperty("hpMax", client.getRealSkillLevel(Skill.HITPOINTS));
		m.addProperty("prayNow", client.getBoostedSkillLevel(Skill.PRAYER));
		m.addProperty("prayMax", client.getRealSkillLevel(Skill.PRAYER));
		// Both are stored by the game in tenths or hundredths: 10000 run energy is 100%, 1000 spec is 100%
		m.addProperty("run", client.getEnergy());
		m.addProperty("spec", client.getVarpValue(VarPlayerID.SA_ENERGY));
		JsonArray prayers = new JsonArray();
		for (Prayer prayer : Prayer.values())
		{
			if (client.getVarbitValue(prayer.getVarbit()) == 1)
			{
				prayers.add(prayer.name());
			}
		}
		m.add("prayers", prayers);
		LocalPoint destination = client.getLocalDestinationLocation();
		if (destination != null)
		{
			JsonObject dest = new JsonObject();
			EncounterSnap.tile(dest, client.getTopLevelWorldView(), destination);
			m.add("dest", dest);
		}
		return m;
	}

	/** The side panel and settings a replay needs to draw the interface as it was */
	private JsonObject ui()
	{
		JsonObject u = new JsonObject();
		u.addProperty("tab", client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL));
		u.addProperty("spellbook", client.getVarbitValue(VarbitID.SPELLBOOK));
		u.addProperty("quickPrayers", client.getVarbitValue(VarbitID.QUICKPRAYER_ACTIVE));
		u.addProperty("style", client.getVarpValue(VarPlayerID.COM_MODE));
		u.addProperty("weaponType", client.getVarbitValue(VarbitID.COMBAT_WEAPON_CATEGORY));
		u.addProperty("autoRetaliate", client.getVarpValue(VarPlayerID.OPTION_NODEF) == 0);
		u.addProperty("runOn", client.getVarpValue(VarPlayerID.OPTION_RUN) == 1);
		return u;
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		// Tab switches by hotkey never pass through a menu click, so this is the only record of them
		if (recording != null && event.getIndex() == VarClientID.TOPLEVEL_PANEL && !bankPinShowing())
		{
			emit("ui_tab").addProperty("tab", client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL));
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
				sceneDirty = true;
				animated.clear();
				break;
			case LOGGED_IN:
				if (sceneDirty && recording != null)
				{
					dumpScene();
					scanObjects(false);
				}
				sceneDirty = false;
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				lastArea = null;
				handStopped = null;
				afterKill = null;
				if (recording != null)
				{
					stop("logged out or hopped", false);
				}
				break;
			default:
				break;
		}
	}

	/**
	 * Where the player is and what the arena is made of. Written at the start and after every map load. Arrays are
	 * indexed [plane][sceneX][sceneY].
	 */
	private void dumpScene()
	{
		WorldView wv = client.getTopLevelWorldView();
		Scene scene = wv.getScene();
		JsonObject s = emit("scene");
		s.addProperty("baseX", wv.getBaseX());
		s.addProperty("baseY", wv.getBaseY());
		s.addProperty("plane", wv.getPlane());
		s.addProperty("instance", wv.isInstance());
		s.add("regions", EncounterSnap.ints(wv.getMapRegions()));
		if (wv.isInstance())
		{
			// Each entry packs the real-world chunk an instance chunk was copied from: see WorldPoint.fromLocalInstance
			JsonArray templates = new JsonArray();
			for (int[][] plane : wv.getInstanceTemplateChunks())
			{
				templates.add(EncounterSnap.grid(plane));
			}
			s.add("templates", templates);
		}

		JsonArray collision = new JsonArray();
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps != null)
		{
			for (CollisionData map : maps)
			{
				collision.add(map == null ? new JsonArray() : EncounterSnap.grid(map.getFlags()));
			}
		}
		s.add("collision", collision);
		if (!wv.isInstance())
		{
			// Outside instances the terrain and objects are the game cache's own map for these regions
			return;
		}

		JsonArray heights = new JsonArray();
		for (int[][] plane : wv.getTileHeights())
		{
			heights.add(EncounterSnap.grid(plane));
		}
		s.add("heights", heights);

		JsonArray settings = new JsonArray();
		for (byte[][] plane : wv.getTileSettings())
		{
			JsonArray rows = new JsonArray();
			for (byte[] row : plane)
			{
				JsonArray cells = new JsonArray();
				for (byte cell : row)
				{
					cells.add(cell);
				}
				rows.add(cells);
			}
			settings.add(rows);
		}
		s.add("settings", settings);

		Tile[][][] tiles = scene.getTiles();
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane)
			{
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					for (GameObject obj : tile.getGameObjects())
					{
						// Big objects sit in every tile they cover: only write them from their own tile
						if (obj != null && obj.getSceneMinLocation().equals(tile.getSceneLocation()))
						{
							objectSeen(obj, true);
						}
					}
					if (tile.getWallObject() != null)
					{
						objectSeen(tile.getWallObject(), true);
					}
					if (tile.getDecorativeObject() != null)
					{
						objectSeen(tile.getDecorativeObject(), true);
					}
					if (tile.getGroundObject() != null)
					{
						objectSeen(tile.getGroundObject(), true);
					}
				}
			}
		}
	}

	private void objectSeen(TileObject obj, boolean initial)
	{
		if (definedObjects.add(obj.getId()))
		{
			emit("objdef").add("def", EncounterSnap.objectDefinition(client.getObjectDefinition(obj.getId())));
		}
		JsonObject o = EncounterSnap.object(obj);
		o.addProperty("init", initial);
		JsonObject line = emit("obj");
		o.entrySet().forEach(entry -> line.add(entry.getKey(), entry.getValue()));
		if (!initial)
		{
			// (its animation is in this line: a change is only one after it)
			spawnedThisTick.add(objectKey(obj));
			Renderable r = EncounterSnap.renderable(obj);
			if (r instanceof DynamicObject)
			{
				animated.put(objectKey(obj), r);
			}
		}
	}

	private void objectGone(TileObject obj)
	{
		JsonObject line = emit("obj_gone");
		line.addProperty("id", obj.getId());
		EncounterSnap.tile(line, obj.getWorldView(), obj.getLocalLocation(), obj.getPlane());
		line.addProperty("pl", obj.getPlane());
	}

	/** Map loads respawn every object in the scene, which the scene dump already lists */
	private boolean recordingObjects()
	{
		return recording != null && client.getGameState() == GameState.LOGGED_IN;
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (recordingObjects())
		{
			objectSeen(event.getGameObject(), false);
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		if (recordingObjects())
		{
			objectGone(event.getGameObject());
		}
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		if (recordingObjects())
		{
			objectSeen(event.getWallObject(), false);
		}
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		if (recordingObjects())
		{
			objectGone(event.getWallObject());
		}
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		if (recordingObjects())
		{
			objectSeen(event.getDecorativeObject(), false);
		}
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		if (recordingObjects())
		{
			objectGone(event.getDecorativeObject());
		}
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		if (recordingObjects())
		{
			objectSeen(event.getGroundObject(), false);
		}
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		if (recordingObjects())
		{
			objectGone(event.getGroundObject());
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (recording == null)
		{
			autoStartFor(npc);
			return;
		}
		npcSpawned(npc, false);
		petSpawned(npc);
		delve |= DOOM_IDS.contains(npc.getId());
	}

	/** A listed NPC appeared, or another turned into one: one starts (his auto-record list; a form change is Outside's). */
	private void autoStartFor(NPC npc)
	{
		// (Doom of Mokhaiotl: no area of RuneLite's names its arena, so its forms start a delve's recording; round four)
		if (DOOM_IDS.contains(npc.getId()) && !delveStopped && mayStart())
		{
			start(DOOM, true, null, DOOM_IDS, 1, null, null, 0);
			delve = true;
			watchedIndices.add(npc.getIndex());
			return;
		}
		Set<Integer> ids = parseIds(host.npcIds());
		if (ids.contains(npc.getId()) && mayStart())
		{
			// The start snapshot includes this NPC, so it isn't written twice
			start(Text.removeTags(npc.getName()), true, null, ids, 1, null, null, 0);
			watchedIndices.add(npc.getIndex());
		}
	}

	private void npcSpawned(NPC npc, boolean initial)
	{
		defineNpc(npc);
		JsonObject line = emit("npc_spawn");
		EncounterSnap.actor(client, npc).entrySet().forEach(entry -> line.add(entry.getKey(), entry.getValue()));
		line.addProperty("init", initial);
		EncounterSnap.overrides(line, npc);
	}

	private void defineNpc(NPC npc)
	{
		if (definedNpcs.add(npc.getId()))
		{
			emit("npcdef").add("def", EncounterSnap.npcDefinition(npc));
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		if (recording == null)
		{
			return;
		}
		watchedIndices.remove(npc.getIndex());
		JsonObject line = emit("npc_gone");
		line.add("who", EncounterSnap.ref(client, npc));
		line.addProperty("dead", npc.isDead());
		EncounterSnap.actorTile(line, npc);
		corpseGone(npc);
	}

	/** The corpse Destroy was chosen on went (or turned into something else) within DESTROY_WAIT ticks, unlooted: it
	 *  was destroyed. A loot line with nothing (but what came into the inventory meanwhile: the elite clue it can still
	 *  give), "choice": "destroy"; the moment is the kill and the destroying (round four). */
	private void corpseGone(NPC npc)
	{
		if (destroyIndex < 0 || npc.getIndex() != destroyIndex || whole() - destroyTick > DESTROY_WAIT || looted.contains("corpse"))
		{
			return;
		}
		destroyIndex = -1;
		clickedTick = Math.max(clickedTick, whole() - 30);
		loot("corpse", gained(invAtDestroy, itemsOf(client.getItemContainer(InventoryID.INV))), -1, "destroyed");
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		if (recording == null)
		{
			autoStartFor(npc);
			return;
		}
		if (watchedIds.contains(npc.getId()))
		{
			watchedIndices.add(npc.getIndex());
		}
		defineNpc(npc);
		JsonObject line = emit("npc_changed");
		line.add("who", EncounterSnap.ref(client, npc));
		line.addProperty("from", event.getOld() == null ? -1 : event.getOld().getId());
		EncounterSnap.overrides(line, npc);
		delve |= DOOM_IDS.contains(npc.getId());
		if (npc.getId() != destroyId)
		{
			corpseGone(npc);
		}
	}

	@Subscribe
	public void onPlayerSpawned(PlayerSpawned event)
	{
		if (recording != null && players && event.getPlayer() != client.getLocalPlayer())
		{
			JsonObject spawn = emit("player_spawn");
			spawn.add("who", EncounterSnap.ref(client, event.getPlayer()));
			nameOf(spawn, event.getPlayer());
			look(event.getPlayer());
		}
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		Player player = event.getPlayer();
		if (recording != null && (player == client.getLocalPlayer() || players))
		{
			look(player);
		}
	}

	private void look(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return;
		}
		for (int raw : comp.getEquipmentIds())
		{
			if (raw >= PlayerComposition.ITEM_OFFSET)
			{
				defineItem(raw - PlayerComposition.ITEM_OFFSET);
			}
		}
		JsonObject line = emit("look");
		EncounterSnap.look(line, client, player, comp);
		nameOf(line, player);
	}

	/** A player's name, as his recorder writes it with "Hide player names" off: on this computer only (putting it on a
	 *  page strips every other player's) */
	private void nameOf(JsonObject line, Player player)
	{
		if (player.getName() != null)
		{
			line.addProperty("name", Text.removeTags(player.getName()).replace(' ', ' '));
		}
	}

	private void defineItem(int id)
	{
		if (id >= 0 && definedItems.add(id))
		{
			ItemComposition def = client.getItemDefinition(id);
			JsonObject line = emit("itemdef");
			line.addProperty("id", id);
			line.addProperty("name", Text.removeTags(def.getName()));
			line.addProperty("stackable", def.isStackable());
			line.addProperty("noted", def.getNote() != -1);
		}
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		if (recording != null && players && event.getPlayer() != client.getLocalPlayer())
		{
			emit("player_gone").add("who", EncounterSnap.ref(client, event.getPlayer()));
		}
	}

	private boolean wanted(Actor actor)
	{
		if (recording == null || actor == null)
		{
			return false;
		}
		return actor instanceof NPC || actor == client.getLocalPlayer() || (actor instanceof Player && players);
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Actor actor = event.getActor();
		if (!wanted(actor))
		{
			return;
		}
		JsonObject line = emit("anim");
		line.add("who", EncounterSnap.ref(client, actor));
		line.addProperty("a", actor.getAnimation());
		line.addProperty("o", actor.getCurrentOrientation());
		EncounterSnap.actorTile(line, actor);
		// (a new animation: its frames counted afresh for restarts)
		lastFrames.put(actor, ((long) actor.getAnimation() << 32) | (actor.getAnimationFrame() & 0xffffffffL));
	}

	@Subscribe
	public void onGraphicChanged(GraphicChanged event)
	{
		Actor actor = event.getActor();
		if (!wanted(actor))
		{
			return;
		}
		JsonObject line = emit("spot");
		line.add("who", EncounterSnap.ref(client, actor));
		line.add("spot", EncounterSnap.spotAnims(actor));
	}

	@Subscribe
	public void onGraphicsObjectCreated(GraphicsObjectCreated event)
	{
		if (recording != null)
		{
			gfxLine(event.getGraphicsObject());
		}
	}

	private JsonObject gfxLine(GraphicsObject gfx)
	{
		JsonObject line = emit("gfx");
		line.addProperty("id", gfx.getId());
		line.addProperty("start", gfx.getStartCycle());
		line.addProperty("z", gfx.getZ());
		EncounterSnap.tile(line, gfx.getWorldView(), gfx.getLocation(), gfx.getLevel());
		return line;
	}

	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		Projectile proj = event.getProjectile();
		// The event repeats while a projectile tracks its target: the first one is its launch
		if (recording == null || !seenProjectiles.add(proj))
		{
			return;
		}
		EncounterSnap.projectile(emit("proj"), client, client.getTopLevelWorldView(), proj);
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		Actor actor = event.getActor();
		if (recording == null && actor != null && (actor == client.getLocalPlayer() || actor instanceof NPC && event.getHitsplat().isMine()))
		{
			fightAgain();
		}
		if (!wanted(actor))
		{
			return;
		}
		if (scene != null && (actor == client.getLocalPlayer() || actor instanceof NPC && event.getHitsplat().isMine()))
		{
			scene.hit(whole(), actor instanceof NPC && event.getHitsplat().isMine() ? Text.removeTags(((NPC) actor).getName()) : null,
				event.getHitsplat().getAmount());
		}
		JsonObject line = emit("hit");
		line.add("who", EncounterSnap.ref(client, actor));
		line.addProperty("type", event.getHitsplat().getHitsplatType());
		line.addProperty("amount", event.getHitsplat().getAmount());
		line.addProperty("mine", event.getHitsplat().isMine());
		line.addProperty("others", event.getHitsplat().isOthers());
		line.addProperty("until", event.getHitsplat().getDisappearsOnGameCycle());
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		if (recording == null)
		{
			return;
		}
		JsonObject line = emit("sound");
		line.addProperty("id", event.getSoundId());
		line.addProperty("delay", event.getDelay());
		line.add("from", EncounterSnap.ref(client, event.getSource()));
	}

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (recording == null)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		JsonObject line = emit("area_sound");
		line.addProperty("id", event.getSoundId());
		line.addProperty("delay", event.getDelay());
		line.addProperty("range", event.getRange());
		line.add("from", EncounterSnap.ref(client, event.getSource()));
		EncounterSnap.tile(line, wv, LocalPoint.fromScene(event.getSceneX(), event.getSceneY(), wv));
	}

	/**
	 * True while the bank PIN keypad is up. Nothing that could give the PIN away is recorded then: no var changes, no
	 * tab switches, no walking flag. (No click is ever recorded here.)
	 */
	private boolean bankPinShowing()
	{
		if (bankPinOpen)
		{
			return true;
		}
		// Covers a keypad that was already open when the plugin started
		Widget keypad = client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0);
		return keypad != null && !keypad.isHidden();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (recording == null || bankPinShowing())
		{
			return;
		}
		JsonObject line = emit("var");
		line.addProperty("varp", event.getVarpId());
		line.addProperty("varbit", event.getVarbitId());
		line.addProperty("value", event.getValue());
	}

	enum ChatKind
	{
		/** Public chat said out loud, shown over the speaker's head */
		LOCAL,
		/** Messages from the game itself */
		GAME,
		/** The raids' friends-chat notifications: only a completion or a time, names taken out (Outside's) */
		COMPLETION,
		/** Private, friends, clan and group chat, and notifications that name other players */
		NEVER
	}

	static ChatKind chatKind(ChatMessageType type)
	{
		switch (type)
		{
			case PUBLICCHAT:
			case MODCHAT:
			case AUTOTYPER:
			case MODAUTOTYPER:
				return ChatKind.LOCAL;
			case GAMEMESSAGE:
			case ENGINE:
			case SPAM:
			case CONSOLE:
			case ITEM_EXAMINE:
			case NPC_EXAMINE:
			case OBJECT_EXAMINE:
			case DIALOG:
			case MESBOX:
			case NPC_SAY:
			case LEVELUPMESSAGE:
			case WELCOME:
			case DIDYOUKNOW:
			case TENSECTIMEOUT:
				return ChatKind.GAME;
			case FRIENDSCHATNOTIFICATION:
				return ChatKind.COMPLETION;
			default:
				return ChatKind.NEVER;
		}
	}

	/** Whether a friends-chat notification is one kept: a completion or a time. */
	static boolean completionLine(String text)
	{
		return text != null && COMPLETION.matcher(text).find();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String text = Text.removeTags(event.getMessage());
		// (the kill: the game's count line ends an automatic recording a few ticks on)
		Matcher kill = recording != null && event.getType() == ChatMessageType.GAMEMESSAGE ? KILL_LINE.matcher(text) : null;
		if (kill != null && kill.matches())
		{
			Presence.Area raid = raidArea();
			if (delve)
			{
				// (a delve: each level's kill passes by, and nothing's its moment but the claim, a pet or the fall; round
				// four)
			}
			else if (raid != null)
			{
				// (in a raid, nothing ends it here and nothing's its moment: the raid's own line is noted, a boss's inside
				// it passed by; the treasure room's leaving ends it, the chest is its moment; round three)
				if (raidCompletion(text, kill.group(1), raid.name) && raidDone < 0)
				{
					raidDone = whole();
				}
			}
			else
			{
				if (automatic && endAt < 0)
				{
					endAt = whole() + AFTER_KILL;
					endWhy = "killed";
				}
				// (the scene moment's kill, by hand or not: its still scene taken now)
				if (scene != null)
				{
					scene.kill(whole(), kill.group(1));
				}
			}
		}
		if (recording == null)
		{
			return;
		}
		// (a pet: the game's own line, as Dink reads it; round four)
		if (event.getType() == ChatMessageType.GAMEMESSAGE && PET_LINE.matcher(text).matches())
		{
			petLine(text);
		}
		else if (petAt >= 0 && event.getType() == ChatMessageType.GAMEMESSAGE && scene != null)
		{
			Matcher named = PET_NAMED.matcher(text);
			if (named.matches())
			{
				scene.petName(named.group(1));
			}
		}
		ChatKind kind = chatKind(event.getType());
		if (kind == ChatKind.NEVER || kind == ChatKind.COMPLETION && !completionLine(text))
		{
			return;
		}
		JsonObject line = emit("chat");
		line.addProperty("type", event.getType().name());
		if (kind == ChatKind.LOCAL)
		{
			// The speaker by index only: the name is used to find them and never written
			line.add("who", EncounterSnap.ref(client, speaker(event.getName())));
		}
		line.addProperty("text", kind == ChatKind.COMPLETION ? scrub(text, othersKnown()) : text);
	}

	// ---- a pet (11/21/2026, round four; Jace: "Pet rolls from bosses/raid loot chests are counted as moments,
	// correct?"): the game's line, then the pet beside the player (the follower that wasn't out before) or in the
	// backpack; a `pet` line at the message and a `pet_seen` line when it appears; the moment has both, the line's
	// picture as the chatbox showed it.

	/** The game's pet line: written, the moment told, and the pet looked for over the next PET_WAIT ticks. */
	private void petLine(String text)
	{
		if (petAt >= 0)
		{
			return;
		}
		String kind = text.contains("would have been") ? "duplicate" : text.contains("backpack") ? "backpack" : "follower";
		petAt = whole();
		petWaiting = !"duplicate".equals(kind);
		NPC f = client.getFollower();
		followerBefore = f == null ? -1 : f.getIndex();
		invAtPet = counts(client.getItemContainer(InventoryID.INV));
		JsonObject line = emit("pet");
		line.addProperty("text", text);
		line.addProperty("kind", kind);
		if (scene != null)
		{
			petChat = new EncounterScene.Window("chat", null, -1, text, new ArrayList<>());
			scene.pet(whole(), kind, petChat);
			chatShot = petChat;
			chatShotIn = 3;
			chatShotTries = 12;
		}
		// (an ending already due waits for it: petHolds)
	}

	/** Each tick while it's awaited: the follower out now that wasn't before is the pet; past PET_WAIT, given up. */
	private void petLook()
	{
		NPC f = client.getFollower();
		if (f != null && f.getIndex() != followerBefore)
		{
			petIs(f, -1);
			return;
		}
		if (whole() > petAt + PET_WAIT)
		{
			petWaiting = false;
		}
	}

	/** The pet seen: an NPC beside them ({@code npc}), or an item come into the backpack ({@code item}). */
	private void petIs(NPC npc, int item)
	{
		petWaiting = false;
		JsonObject line = emit("pet_seen");
		String name = null;
		if (npc != null)
		{
			defineNpc(npc);
			line.add("who", EncounterSnap.ref(client, npc));
			line.addProperty("npc", npc.getId());
			name = npc.getName() == null ? null : Text.removeTags(npc.getName());
		}
		else
		{
			defineItem(item);
			line.addProperty("item", item);
			ItemComposition def = client.getItemDefinition(item);
			name = def == null ? null : Text.removeTags(def.getName());
		}
		if (name != null)
		{
			line.addProperty("name", name);
		}
		if (scene != null)
		{
			scene.petSeen(whole(), npc == null ? -1 : npc.getId(), item, name);
		}
	}

	/** Whether an ending due waits for a pet: its line came, and it's still awaited, or seen (or its line came) less than
	 *  PET_AFTER ticks ago. */
	private boolean petHolds()
	{
		if (petAt < 0 || scene == null)
		{
			return false;
		}
		return petWaiting || whole() <= petAt + EncounterScene.PET_AFTER || petSeenAt() >= 0 && whole() <= petSeenAt() + EncounterScene.PET_AFTER;
	}

	private int petSeenAt()
	{
		return scene == null ? -1 : scene.petSeenTick();
	}

	/** An NPC come beside them while a pet's awaited, one that can be picked up: the pet (when the client's follower
	 *  isn't told). */
	private void petSpawned(NPC npc)
	{
		Player me = client.getLocalPlayer();
		if (!petWaiting || me == null || npc.getWorldLocation().distanceTo(me.getWorldLocation()) > 3)
		{
			return;
		}
		net.runelite.api.NPCComposition def = npc.getTransformedComposition();
		if (def == null)
		{
			return;
		}
		for (String act : def.getActions())
		{
			if (act != null && "Pick-up".equals(Text.removeTags(act)))
			{
				petIs(npc, -1);
				return;
			}
		}
	}

	/** The inventory changed while a pet's awaited: an untradeable single item come in is the pet in the backpack. */
	private void petInBackpack(ItemContainer inv)
	{
		if (!petWaiting)
		{
			return;
		}
		for (int[] it : gained(invAtPet, itemsOf(inv)))
		{
			ItemComposition def = client.getItemDefinition(it[0]);
			if (it[1] == 1 && def != null && !def.isTradeable() && !def.isStackable())
			{
				petIs(null, it[0]);
				return;
			}
		}
	}

	/** The pet's line in the chatbox, where it's drawn on the canvas: the line's row across the chatbox (a pixel over and
	 *  under), the newest that has its text; null while it isn't drawn (the chatbox closed, or not yet). */
	private java.awt.Rectangle chatLineBounds(String text)
	{
		Widget area = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		if (area == null || area.isHidden() || text == null)
		{
			return null;
		}
		java.awt.Rectangle box = area.getBounds();
		if (box == null || box.width <= 0 || box.height <= 0)
		{
			return null;
		}
		java.awt.Rectangle best = null;
		Widget[] kids = area.getDynamicChildren();
		for (int k = 0; kids != null && k < kids.length; k++)
		{
			Widget w = kids[k];
			String t = w == null || w.getText() == null ? "" : Text.removeTags(w.getText());
			if (w == null || w.isHidden() || !t.contains(text))
			{
				continue;
			}
			java.awt.Rectangle b = w.getBounds();
			if (b == null || b.height <= 0 || !box.intersects(b))
			{
				continue;
			}
			if (best == null || b.y > best.y)
			{
				best = b;
			}
		}
		if (best == null)
		{
			return null;
		}
		java.awt.Rectangle row = new java.awt.Rectangle(box.x, best.y - 1, box.width, best.height + 2).intersection(box);
		return row.isEmpty() ? null : row;
	}

	/** Every other player's name the client knows now: the scene's and the friends chat's (not the player's own). */
	private Set<String> othersKnown()
	{
		Set<String> names = new HashSet<>();
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player.getName() != null && player != client.getLocalPlayer())
			{
				names.add(Text.removeTags(player.getName()));
			}
		}
		FriendsChatManager fc = client.getFriendsChatManager();
		if (fc != null)
		{
			for (FriendsChatMember m : fc.getMembers())
			{
				if (m != null && m.getName() != null)
				{
					names.add(Text.removeTags(m.getName()));
				}
			}
		}
		Player me = client.getLocalPlayer();
		if (me != null && me.getName() != null)
		{
			names.remove(Text.removeTags(me.getName()));
		}
		return names;
	}

	/** Names taken out of a text, however the game writes them: any case, and with spaces, non-breaking spaces,
	 *  underscores or hyphens between their words (his Names.scrub) */
	static String scrub(String text, Set<String> names)
	{
		if (text == null || text.isEmpty() || names.isEmpty())
		{
			return text;
		}
		List<String> sorted = new ArrayList<>(names);
		sorted.sort((a, b) -> b.length() - a.length());
		String out = text;
		for (String name : sorted)
		{
			String[] words = name.replace(' ', ' ').trim().split("[ _-]+");
			StringBuilder re = new StringBuilder("(?<![A-Za-z0-9])");
			int kept = 0;
			for (String word : words)
			{
				if (word.isEmpty())
				{
					continue;
				}
				if (kept++ > 0)
				{
					re.append("[ _\\u00A0-]");
				}
				re.append(Pattern.quote(word));
			}
			if (kept > 0)
			{
				out = Pattern.compile(re.append("(?![A-Za-z0-9])").toString(), Pattern.CASE_INSENSITIVE).matcher(out)
					.replaceAll(Matcher.quoteReplacement("<player>"));
			}
		}
		return out;
	}

	private Player speaker(String name)
	{
		if (name == null)
		{
			return null;
		}
		String wanted = Text.toJagexName(Text.removeTags(name));
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player.getName() != null && Text.toJagexName(player.getName()).equalsIgnoreCase(wanted))
			{
				return player;
			}
		}
		return null;
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		Actor actor = event.getActor();
		if (recording == null || actor == null)
		{
			return;
		}
		JsonObject line = emit("say");
		line.add("who", EncounterSnap.ref(client, actor));
		line.addProperty("text", Text.removeTags(event.getOverheadText()));
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		// Unlike objects, ground items aren't in the map dump: the ones already lying there when a map loads arrive as
		// spawns during the load, so they're recorded then too
		if (recording != null)
		{
			defineItem(event.getItem().getId());
			JsonObject line = emit("item");
			line.addProperty("id", event.getItem().getId());
			line.addProperty("qty", event.getItem().getQuantity());
			EncounterSnap.tile(line, client.getTopLevelWorldView(), event.getTile().getLocalLocation(), event.getTile().getPlane());
			// (the drop, for the scene moment: the recording holds on till it's landed and a few ticks more; not what a
			// map's load brings)
			if (scene != null && client.getGameState() == GameState.LOGGED_IN
				&& scene.itemLanded(whole(), event.getTile().getLocalLocation(), event.getTile().getPlane())
				&& "killed".equals(endWhy) && endAt >= 0)
			{
				endAt = Math.max(endAt, whole() + AFTER_KILL + 1);
			}
		}
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (recordingObjects())
		{
			JsonObject line = emit("item_gone");
			line.addProperty("id", event.getItem().getId());
			EncounterSnap.tile(line, client.getTopLevelWorldView(), event.getTile().getLocalLocation(), event.getTile().getPlane());
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (recording == null)
		{
			return;
		}
		if (event.getContainerId() == InventoryID.INV)
		{
			containers(event.getItemContainer(), "inv");
			petInBackpack(event.getItemContainer());
			corpseLoot(event.getItemContainer());
		}
		else if (event.getContainerId() == InventoryID.WORN)
		{
			containers(event.getItemContainer(), "worn");
		}
		// (a reward window's inventory filled after its window opened)
		Chest w = waitingFor;
		if (w != null && event.getContainerId() == w.inv)
		{
			List<int[]> got = itemsOf(event.getItemContainer());
			if (!got.isEmpty())
			{
				loot(w.source, got, w.window, false);
			}
		}
	}

	// ---- the loot (11/21/2026, round three; ghillie's claude/outstation.md, "Encounters, round three"): from a reward
	// window (a raid's chest, Barrows', the Lunar Chest, the Colosseum's), Doom of Mokhaiotl's pile, or a corpse looted;
	// one loot line each, and the scene moment's anchor. Items that land on the ground are the item lines, as before.

	/** A reward window loaded: its reward read now, or once its inventory fills (a few ticks at most). */
	private void rewardWindow(int group)
	{
		for (Chest c : CHESTS)
		{
			if (c.window != group || c.inv == InventoryID.DOM_LOOTPILE)
			{
				// (Doom's level-end window shows the pile before it's claimed: the claim is its loot, below)
				continue;
			}
			if (c.inv == InventoryID.TOB_CHESTS && !inTobChestRegion())
			{
				continue;
			}
			List<int[]> got = itemsOf(client.getItemContainer(c.inv));
			if (!got.isEmpty())
			{
				loot(c.source, got, c.window, false);
			}
			else if (!looted.contains(c.source))
			{
				waitingFor = c;
				waitingUntil = whole() + 5;
			}
		}
	}

	/** Doom of Mokhaiotl's pile claimed (the game's script as it's taken, as the Loot Tracker reads it). */
	@Subscribe
	public void onScriptPreFired(net.runelite.api.events.ScriptPreFired event)
	{
		if (recording != null && event.getScriptId() == DOM_LOOT_CLAIM)
		{
			List<int[]> got = itemsOf(client.getItemContainer(InventoryID.DOM_LOOTPILE));
			if (!got.isEmpty())
			{
				loot("Doom of Mokhaiotl", got, InterfaceID.DOM_END_LEVEL_UI, false);
			}
		}
	}

	/** Theatre of Blood's chests' window counts only in its treasure room or lobby (the Loot Tracker's own check). */
	private boolean inTobChestRegion()
	{
		Player me = client.getLocalPlayer();
		WorldPoint at = OutsidePlugin.here(client, me);
		int r = at == null ? -1 : at.getRegionID();
		return r == TOB_CHEST_REGION || r == TOB_LOBBY;
	}

	/** What the player chose to open or loot (an object's or an NPC's menu option): kept, never written, so the loot line
	 *  can say where its loot came from; and the inventory as it was, for a corpse's loot. */
	@Subscribe
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
	{
		if (recording == null)
		{
			return;
		}
		String option = Text.removeTags(event.getMenuOption() == null ? "" : event.getMenuOption());
		// (a delve: the burrow's Leave, after the claim, ends it a few ticks on (Jace, 11/21/2026: "it should only end when
		// the player dies or after they claim their loot and press the leave button on the burrow"); the Mokha Loot
		// Tracker reads the same option; round four)
		if (delve && "Leave".equals(option)
			&& ((event.getParam1() >>> 16) == InterfaceID.DOM_END_LEVEL_UI || delveClaimed))
		{
			delveLeave = whole();
			if (automatic)
			{
				endAt = whole() + LOOT_AFTER;
				endWhy = delveClaimed ? "claimed" : "left";
			}
			return;
		}
		// (Araxxor's corpse: Destroy, for a better chance at the pet and no loot (the wiki: "Harvest, Destroy", a prompt
		// after); kept till the corpse goes; round four)
		NPC on = event.getMenuEntry() == null ? null : event.getMenuEntry().getNpc();
		if (FORFEIT_OPTIONS.contains(option) && on != null && scene != null && scene.killed() && raidArea() == null)
		{
			destroyIndex = on.getIndex();
			destroyTick = whole();
			destroyId = on.getId();
			destroyName = on.getName() == null ? null : Text.removeTags(on.getName());
			invAtDestroy = counts(client.getItemContainer(InventoryID.INV));
			clickedAt = on.getLocalLocation();
			clickedPlane = client.getTopLevelWorldView().getPlane();
			clickedId = on.getId();
			clickedIndex = on.getIndex();
			clickedKind = "npc";
			clickedName = destroyName;
			clickedOption = option;
			clickedTick = whole();
			return;
		}
		if (!LOOT_OPTIONS.contains(option))
		{
			return;
		}
		clickedOption = option;
		if (on != null && on.getIndex() == destroyIndex)
		{
			// (Destroy's prompt answered no, and harvested after all)
			destroyIndex = -1;
		}
		WorldView wv = client.getTopLevelWorldView();
		net.runelite.api.MenuAction a = event.getMenuAction();
		NPC npc = event.getMenuEntry() == null ? null : event.getMenuEntry().getNpc();
		if (npc != null)
		{
			clickedAt = npc.getLocalLocation();
			clickedPlane = wv.getPlane();
			clickedId = npc.getId();
			clickedIndex = npc.getIndex();
			clickedKind = "npc";
			clickedName = npc.getName() == null ? null : Text.removeTags(npc.getName());
		}
		else if (a != null && a.name().startsWith("GAME_OBJECT_"))
		{
			clickedAt = LocalPoint.fromScene(event.getParam0(), event.getParam1(), wv);
			clickedPlane = wv.getPlane();
			clickedId = event.getId();
			clickedIndex = -1;
			clickedKind = "obj";
			net.runelite.api.ObjectComposition def = client.getObjectDefinition(event.getId());
			if (def != null && def.getImpostorIds() != null)
			{
				net.runelite.api.ObjectComposition imp = def.getImpostor();
				def = imp != null ? imp : def;
			}
			clickedName = def == null ? null : Text.removeTags(def.getName());
		}
		else
		{
			return;
		}
		clickedTick = whole();
		invBefore = counts(client.getItemContainer(InventoryID.INV));
	}

	/** A container's items, [id, quantity] each, the empty slots left out. */
	static List<int[]> itemsOf(ItemContainer c)
	{
		List<int[]> out = new ArrayList<>();
		if (c == null)
		{
			return out;
		}
		for (Item item : c.getItems())
		{
			if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
			{
				out.add(new int[]{item.getId(), item.getQuantity()});
			}
		}
		return out;
	}

	/** A container's count of each item (stacks and slots added up). */
	static Map<Integer, Long> counts(ItemContainer c)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int[] it : itemsOf(c))
		{
			m.merge(it[0], (long) it[1], Long::sum);
		}
		return m;
	}

	/** What an inventory gained over what it had (each item's count up), in the order of {@code now}'s slots. */
	static List<int[]> gained(Map<Integer, Long> before, List<int[]> now)
	{
		Map<Integer, Long> after = new java.util.LinkedHashMap<>();
		for (int[] it : now)
		{
			after.merge(it[0], (long) it[1], Long::sum);
		}
		List<int[]> out = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : after.entrySet())
		{
			long up = e.getValue() - before.getOrDefault(e.getKey(), 0L);
			if (up > 0)
			{
				out.add(new int[]{e.getKey(), (int) Math.min(Integer.MAX_VALUE, up)});
			}
		}
		return out;
	}

	/** The inventory changed: after a click on a corpse (an NPC's loot option, a kill this recording, outside a raid),
	 *  what it gained is the corpse's loot (Jace, 11/21/2026: Araxxor's, the Maggot King's: "the loot goes into the
	 *  inventory"; the ground only when it's full, and that's the item lines). */
	private void corpseLoot(ItemContainer inv)
	{
		if (!"npc".equals(clickedKind) || FORFEIT_OPTIONS.contains(clickedOption) || whole() - clickedTick > CORPSE_TAKE
			|| looted.contains("corpse") || scene == null
			|| !scene.killed() || raidArea() != null)
		{
			return;
		}
		List<int[]> got = gained(invBefore, itemsOf(inv));
		if (!got.isEmpty())
		{
			loot("corpse", got, -1, true);
		}
	}

	/** Whether a kill's corpse is still there to loot (an NPC of the killed one's name near them, with a loot option and
	 *  no Attack), so the recording waits for it, CORPSE_WAIT ticks at most. */
	private boolean corpseWaiting()
	{
		if (scene == null || !scene.killed() || whole() >= scene.killTick() + CORPSE_WAIT)
		{
			return false;
		}
		Player me = client.getLocalPlayer();
		String killed = scene.killNpc();
		if (me == null || killed == null)
		{
			return false;
		}
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			net.runelite.api.NPCComposition def = npc.getTransformedComposition();
			String name = npc.getName() == null ? "" : Text.removeTags(npc.getName());
			// (the killed one's name, or one fought: the Royal Titans' count line names neither titan, and each falls
			// where it stood with its own name, Branda the Fire Queen's and Eldric the Ice King's; round four)
			if (def == null || !(name.equalsIgnoreCase(killed) || scene.fought(name))
				|| npc.getWorldLocation().distanceTo(me.getWorldLocation()) > 15)
			{
				continue;
			}
			boolean loot = false, attack = false;
			for (String act : def.getActions())
			{
				if (act != null)
				{
					loot |= LOOT_OPTIONS.contains(Text.removeTags(act));
					attack |= "Attack".equals(Text.removeTags(act));
				}
			}
			if (loot && !attack)
			{
				corpseOf = name;
				return true;
			}
		}
		return false;
	}

	/**
	 * A loot line (Outside's own kind, round three): where it came from (the chest or corpse the player last chose to
	 * open or loot, within 30 ticks; else the nearest thing named a chest within 10 tiles; else the player), the source
	 * ("Chambers of Xeric", "Lunar Chest", "corpse"), the window it came in, and each item with its GE price and high
	 * alchemy value, each. Once a source a recording; the scene moment's anchor; a boss outside a raid ends a few ticks
	 * on.
	 */
	private void loot(String source, List<int[]> items, int window, boolean corpse)
	{
		loot(source, items, window, corpse ? "inventory" : "reward");
	}

	/** {@code kind}: "reward", "inventory" (a corpse looted), "destroyed" (a corpse destroyed: no window, no picture). */
	private void loot(String source, List<int[]> items, int window, String kind)
	{
		boolean corpse = !"reward".equals(kind);
		if (!looted.add(source))
		{
			return;
		}
		if (waitingFor != null && source.equals(waitingFor.source))
		{
			waitingFor = null;
		}
		WorldView wv = client.getTopLevelWorldView();
		Player me = client.getLocalPlayer();
		for (int[] it : items)
		{
			defineItem(it[0]);
		}
		JsonObject line = emit("loot");
		line.addProperty("source", source);
		LocalPoint at = null;
		int plane = wv.getPlane();
		JsonObject from = null;
		if (clickedAt != null && whole() - clickedTick <= 30)
		{
			at = clickedAt;
			plane = clickedPlane;
			from = new JsonObject();
			from.addProperty("kind", clickedKind);
			from.addProperty("id", clickedId);
			if (clickedName != null)
			{
				from.addProperty("name", clickedName);
			}
		}
		else
		{
			Object[] near = nearestChest(wv, me);
			if (near != null)
			{
				at = (LocalPoint) near[0];
				from = (JsonObject) near[1];
			}
		}
		if (at == null && me != null)
		{
			at = me.getLocalLocation();
		}
		EncounterSnap.tile(line, wv, at, plane);
		line.addProperty("at", from != null ? (corpse ? "corpse" : "chest") : "player");
		// (a corpse's: what the player chose, "harvest" or "destroy" (Araxxor's), "loot"...; round four)
		if (corpse && clickedOption != null)
		{
			line.addProperty("choice", clickedOption.toLowerCase(java.util.Locale.ROOT));
		}
		if (from != null)
		{
			line.add("from", from);
		}
		if (window >= 0)
		{
			line.addProperty("window", window);
			String title = windowTitle(window);
			if (title != null)
			{
				line.addProperty("title", title);
			}
		}
		JsonArray list = new JsonArray();
		List<EncounterScene.Loot> scened = new ArrayList<>();
		for (int[] it : items)
		{
			long[] p = host.prices(it[0]);
			JsonObject o = new JsonObject();
			o.addProperty("id", it[0]);
			o.addProperty("qty", it[1]);
			o.addProperty("ge", p == null ? 0 : p[0]);
			o.addProperty("ha", p == null ? 0 : p[1]);
			list.add(o);
			ItemComposition def = client.getItemDefinition(it[0]);
			scened.add(new EncounterScene.Loot(it[0], it[1], p == null ? 0 : p[0], p == null ? 0 : p[1],
				def == null ? null : Text.removeTags(def.getName())));
		}
		line.add("items", list);
		// (the scene moment: the chest's opening, the window as the player saw it laid over)
		if (scene != null)
		{
			EncounterScene.Window w = new EncounterScene.Window(kind, source, window,
				window >= 0 ? windowTitle(window) : "destroyed".equals(kind) ? clickedOption : null, scened);
			scene.loot(whole(), w, at, plane);
			// (its picture a few client cycles on, once the window's laid out and drawn with its items; none for a corpse
			// destroyed)
			if (!"destroyed".equals(kind))
			{
				pictureOf = w;
				pictureGroup = corpse ? -1 : window;
				pictureIn = 3;
			}
		}
		// (a boss outside a raid: ends a few ticks after its loot; a raid goes on to its treasure room's door)
		boolean raid = raidArea() != null;
		for (Chest c : CHESTS)
		{
			raid |= c.raid && c.source.equals(source);
		}
		// (a delve's claim: it goes on to the burrow's Leave; round four)
		if (DOOM.equals(source))
		{
			delveClaimed = true;
		}
		if (automatic && !raid && !delve)
		{
			endAt = Math.max(endAt, whole() + LOOT_AFTER);
			if (endWhy == null)
			{
				endWhy = "looted";
			}
		}
	}

	/** The nearest object or NPC named a chest (or its corpse, or Doom's burrow) within 10 tiles: {its tile, {kind, id, name}}, or null. */
	private Object[] nearestChest(WorldView wv, Player me)
	{
		if (me == null || me.getLocalLocation() == null)
		{
			return null;
		}
		LocalPoint mine = me.getLocalLocation();
		Object[] best = null;
		int bestD = 10 * 128 + 1;
		for (NPC npc : wv.npcs())
		{
			String name = npc.getName() == null ? "" : Text.removeTags(npc.getName());
			LocalPoint lp = npc.getLocalLocation();
			int d = lp == null ? Integer.MAX_VALUE : Math.max(Math.abs(lp.getX() - mine.getX()), Math.abs(lp.getY() - mine.getY()));
			if (name.toLowerCase().contains("chest") && d < bestD)
			{
				JsonObject f = new JsonObject();
				f.addProperty("kind", "npc");
				f.addProperty("id", npc.getId());
				f.addProperty("name", name);
				best = new Object[]{lp, f};
				bestD = d;
			}
		}
		Tile[][][] tiles = wv.getScene().getTiles();
		int plane = wv.getPlane(), cx = mine.getSceneX(), cy = mine.getSceneY();
		for (int x = Math.max(0, cx - 10); x <= Math.min(tiles[plane].length - 1, cx + 10); x++)
		{
			for (int y = Math.max(0, cy - 10); y <= Math.min(tiles[plane][x].length - 1, cy + 10); y++)
			{
				Tile tile = tiles[plane][x][y];
				if (tile == null)
				{
					continue;
				}
				for (GameObject o : tile.getGameObjects())
				{
					if (o == null || !tile.getSceneLocation().equals(o.getSceneMinLocation()))
					{
						continue;
					}
					net.runelite.api.ObjectComposition def = client.getObjectDefinition(o.getId());
					if (def != null && def.getImpostorIds() != null && def.getImpostor() != null)
					{
						def = def.getImpostor();
					}
					String name = def == null || def.getName() == null ? "" : Text.removeTags(def.getName());
					LocalPoint lp = o.getLocalLocation();
					int d = Math.max(Math.abs(lp.getX() - mine.getX()), Math.abs(lp.getY() - mine.getY()));
					// (round four: Doom of Mokhaiotl's "Burrow hole", its pile's place)
					String lower = name.toLowerCase();
					if ((lower.contains("chest") || lower.contains("coffer") || lower.contains("burrow")) && d < bestD)
					{
						JsonObject f = new JsonObject();
						f.addProperty("kind", "obj");
						f.addProperty("id", o.getId());
						f.addProperty("name", name);
						best = new Object[]{lp, f};
						bestD = d;
					}
				}
			}
		}
		return best;
	}

	/** A window's title as the game draws it: the first text in it that has a letter (its static parts in order, then
	 *  their children), or null. */
	private String windowTitle(int group)
	{
		for (int i = 0; i < 128; i++)
		{
			Widget w = client.getWidget(group, i);
			if (w == null)
			{
				continue;
			}
			String t = w.getText() == null ? "" : Text.removeTags(w.getText()).trim();
			if (!t.isEmpty() && t.matches("(?s).*[A-Za-z].*"))
			{
				return t;
			}
			Widget[] kids = w.getDynamicChildren();
			for (int k = 0; kids != null && k < kids.length; k++)
			{
				String kt = kids[k] == null || kids[k].getText() == null ? "" : Text.removeTags(kids[k].getText()).trim();
				if (!kt.isEmpty() && kt.matches("(?s).*[A-Za-z].*"))
				{
					return kt;
				}
			}
		}
		return null;
	}

	/** Where a window is drawn on the canvas: its first part's box (the window's frame), else all its shown parts'. */
	private java.awt.Rectangle windowBounds(int group)
	{
		if (group < 0)
		{
			return null;
		}
		java.awt.Rectangle all = null;
		int cw = client.getCanvasWidth(), ch = client.getCanvasHeight();
		for (int i = 0; i < 128; i++)
		{
			Widget w = client.getWidget(group, i);
			if (w == null || w.isHidden())
			{
				continue;
			}
			java.awt.Rectangle b = w.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				continue;
			}
			if (i == 0 && b.width >= 120 && b.height >= 80 && b.width < cw && b.height < ch)
			{
				return b.intersection(new java.awt.Rectangle(0, 0, cw, ch));
			}
			all = all == null ? new java.awt.Rectangle(b) : all.union(b);
		}
		return all == null ? null : all.intersection(new java.awt.Rectangle(0, 0, cw, ch));
	}

	/** The inventory's box on the canvas, when it's showing (a corpse's loot arriving there). */
	private java.awt.Rectangle inventoryBounds()
	{
		Widget w = client.getWidget(InterfaceID.Inventory.ITEMS);
		if (w == null || w.isHidden())
		{
			return null;
		}
		java.awt.Rectangle b = w.getBounds();
		return b == null || b.width <= 0 ? null : b;
	}

	private void containers(ItemContainer container, String which)
	{
		if (container == null)
		{
			return;
		}
		JsonObject line = emit("items");
		line.addProperty("which", which);
		JsonArray items = new JsonArray();
		for (Item item : container.getItems())
		{
			defineItem(item.getId());
			JsonArray pair = new JsonArray();
			pair.add(item.getId());
			pair.add(item.getQuantity());
			items.add(pair);
		}
		line.add("items", items);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (recording == null)
		{
			return;
		}
		JsonObject line = emit("stat");
		line.addProperty("skill", event.getSkill().name());
		line.addProperty("xp", event.getXp());
		line.addProperty("level", event.getLevel());
		line.addProperty("boosted", event.getBoostedLevel());
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!wanted(event.getSource()))
		{
			return;
		}
		JsonObject line = emit("target");
		line.add("who", EncounterSnap.ref(client, event.getSource()));
		line.add("at", EncounterSnap.ref(client, event.getTarget()));
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!wanted(actor))
		{
			return;
		}
		emit("death").add("who", EncounterSnap.ref(client, actor));
		if (scene != null && actor == client.getLocalPlayer())
		{
			scene.died(whole());
		}
		// (a death ends an automatic recording a few ticks on: not in a raid, which goes on)
		if (actor == client.getLocalPlayer() && automatic && endAt < 0 && (area == null || !Presence.RAID.equals(area.kind)))
		{
			endAt = whole() + AFTER_DEATH;
			endWhy = "died";
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKPIN_KEYPAD)
		{
			bankPinOpen = true;
		}
		if (recording != null)
		{
			emit("widget_open").addProperty("group", event.getGroupId());
			rewardWindow(event.getGroupId());
			// (the burrow's window: a delve; round four)
			delve |= event.getGroupId() == InterfaceID.DOM_END_LEVEL_UI;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKPIN_KEYPAD)
		{
			bankPinOpen = false;
		}
		if (recording != null)
		{
			emit("widget_close").addProperty("group", event.getGroupId());
		}
	}
}
