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
 * <li>Not his: mouse presses, clicks, their screen positions, the interface layout and markers are never recorded (a
 * replay doesn't need them and the page's strip would take them out anyway), and there's no overlay or hotkey.</li>
 * </ul>
 */
final class Encounters
{
	private static final Logger log = LoggerFactory.getLogger(Encounters.class);

	/** Encounter Recorder's format these files keep to */
	static final int FORMAT_VERSION = 6;
	/** ticks a part runs before the next is split off (his length limit's default: 50 minutes) */
	static final int PART_TICKS = 5000;
	/** after the kill (its count line) or the player's death, how many ticks more are kept: the death and the loot */
	static final int AFTER_KILL = 5, AFTER_DEATH = 6;
	/** an automatic recording started for listed NPCs stops this many ticks after the last of them is seen (his) */
	static final int WATCHED_GONE = 50;
	/** ticks outside the area that started it before it counts as left: in a region no area names (a raid's room the
	 *  list misses, a map loading), a raid gets longer */
	static final int LEFT_BOSS = 2, LEFT_RAID = 50;
	/** Discord's bosses that are skilling: no recording starts by itself there */
	static final Set<String> SKILLING = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList("Wintertodt", "Tempoross")));
	/** how far round the player objects' animations are watched, in tiles */
	static final int OBJ_RADIUS = 32;

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
	}

	private final Client client;
	private final net.runelite.client.callback.ClientThread clientThread;
	private final Gson gson;
	private final Host host;

	private volatile EncounterFiles.Writer recording;
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
		start(unnamedLabel(), false, null, Collections.emptySet(), 1, null);
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

	private void start(String name, boolean auto, Presence.Area at, Set<Integer> watched, int partNo, String follows)
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
		recording = new EncounterFiles.Writer(gson, () -> host.folder().joinSegment("encounters"), name, LocalDateTime.now());
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
		EncounterFiles.Writer stopped = recording;
		if (dropTick)
		{
			stopped.dropPending();
		}
		JsonObject end = emit("end");
		end.addProperty("reason", reason);
		end.addProperty("ticks", stopped.ticks());
		end.addProperty("endedMs", System.currentTimeMillis());
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
		stop("split: the length limit", false);
		start(name, auto, at, watched, next, follows);
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
		return a != null && (Presence.BOSS.equals(a.kind) || Presence.RAID.equals(a.kind)) && !SKILLING.contains(a.name) ? a : null;
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

		// (how it ends: the kill or a death a few ticks on, leaving, the listed NPCs gone)
		if (endAt >= 0 && recording.ticks() >= endAt)
		{
			stopAuto(endWhy);
			return;
		}
		if (automatic && area != null)
		{
			Presence.Area now = here();
			boolean in = now == area || now != null && now.name.equals(area.name);
			outside = in ? 0 : outside + 1;
			// (in an area of another name: left at once; where no area's named, after a few ticks, a raid's longer)
			if (!in && (now != null || outside > (Presence.RAID.equals(area.kind) ? LEFT_RAID : LEFT_BOSS)))
			{
				stopAuto("left");
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
		if (a != null && ("killed".equals(why) || "died".equals(why) || "completed".equals(why)))
		{
			afterKill = a;
		}
	}

	/** Not recording: at a boss's or a raid's area, one starts (not again where one was stopped by hand, or just ended by
	 *  its kill, till the next fight there). */
	private void autoStartByArea()
	{
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
		start(a.name, true, a, Collections.emptySet(), 1, null);
	}

	/** After a kill in the area: the next fight (a hitsplat on them, or on an NPC from them) starts the next one. */
	private void fightAgain()
	{
		if (afterKill != null && recording == null && mayStart() && bossArea() == afterKill)
		{
			Presence.Area a = afterKill;
			afterKill = null;
			start(a.name, true, a, Collections.emptySet(), 1, null);
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (recording == null)
		{
			return;
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
	}

	/** A listed NPC appeared, or another turned into one: one starts (his auto-record list; a form change is Outside's). */
	private void autoStartFor(NPC npc)
	{
		Set<Integer> ids = parseIds(host.npcIds());
		if (ids.contains(npc.getId()) && mayStart())
		{
			// The start snapshot includes this NPC, so it isn't written twice
			start(Text.removeTags(npc.getName()), true, null, ids, 1, null);
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
		if (recording != null && automatic && endAt < 0 && event.getType() == ChatMessageType.GAMEMESSAGE
			&& KILL_LINE.matcher(text).matches())
		{
			endAt = recording.ticks() + AFTER_KILL;
			endWhy = area != null && Presence.RAID.equals(area.kind) ? "completed" : "killed";
		}
		if (recording == null)
		{
			return;
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
		}
		else if (event.getContainerId() == InventoryID.WORN)
		{
			containers(event.getItemContainer(), "worn");
		}
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
		// (a death ends an automatic recording a few ticks on: not in a raid, which goes on)
		if (actor == client.getLocalPlayer() && automatic && endAt < 0 && (area == null || !Presence.RAID.equals(area.kind)))
		{
			endAt = recording.ticks() + AFTER_DEATH;
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
