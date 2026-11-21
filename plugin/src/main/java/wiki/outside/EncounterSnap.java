/*
 * Encounter Recorder's Snap.java (github.com/mrgnomnom/encounter-recorder, the Plugin Hub's commit 3570026), ported into
 * Outside on 11/21/2026 with its click and layout helpers left out, under its licence:
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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.HeadIcon;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.NpcOverrides;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.util.Text;

/**
 * Game state as Encounter Recorder's lines write it (its docs/FORMAT.md, format 6), for Outside's own recorder
 * (Encounters; 11/21/2026, ghillie's claude/outstation.md, "Encounters on Outside: the night's design"). Coordinates are
 * written twice: "x"/"y"/"p" are real world coordinates, translated out of the instance when the player is in one, so
 * they match the map in the game cache; "sx"/"sy" are scene coordinates, which match the collision and height arrays in
 * the scene line. Every call is on the client's thread. What's Outside's own here: a projectile's line in one place
 * (written from ProjectileMoved and from the client's list of projectiles, so one over before it ever moves is caught)
 * and an object's animation read from its renderable (for the obj_anim lines).
 */
final class EncounterSnap
{
	private EncounterSnap()
	{
	}

	static JsonObject line(String event, int tick, int cycle)
	{
		JsonObject o = new JsonObject();
		o.addProperty("t", tick);
		o.addProperty("c", cycle);
		o.addProperty("e", event);
		return o;
	}

	static void tile(JsonObject o, WorldView wv, LocalPoint lp)
	{
		if (wv != null)
		{
			tile(o, wv, lp, wv.getPlane());
		}
	}

	static void tile(JsonObject o, WorldView wv, LocalPoint lp, int plane)
	{
		if (wv == null || lp == null)
		{
			return;
		}
		Scene scene = wv.getScene();
		WorldPoint wp = WorldPoint.fromLocalInstance(scene, lp, plane);
		o.addProperty("x", wp.getX());
		o.addProperty("y", wp.getY());
		o.addProperty("p", wp.getPlane());
		// In an instance "p" is the floor of the template it was copied from; this is the scene's own floor
		o.addProperty("pl", plane);
		o.addProperty("sx", lp.getSceneX());
		o.addProperty("sy", lp.getSceneY());
		// Sub-tile position: 128 units per tile. Shows where a projectile lands or a big NPC's centre is
		o.addProperty("lx", lp.getX());
		o.addProperty("ly", lp.getY());
	}

	/** An actor's position on its own floor (not necessarily the floor you're on) */
	static void actorTile(JsonObject o, Actor actor)
	{
		WorldPoint wp = actor.getWorldLocation();
		WorldView wv = actor.getWorldView();
		tile(o, wv, actor.getLocalLocation(), wp != null ? wp.getPlane() : wv == null ? 0 : wv.getPlane());
	}

	/**
	 * Who an actor is, without their name: "me", an NPC by index and ID, or another player by index
	 */
	static JsonElement ref(Client client, Actor actor)
	{
		if (actor == null)
		{
			return JsonNull.INSTANCE;
		}
		JsonObject o = new JsonObject();
		if (actor == client.getLocalPlayer())
		{
			o.addProperty("k", "me");
		}
		else if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			o.addProperty("k", "npc");
			o.addProperty("i", npc.getIndex());
			o.addProperty("id", npc.getId());
		}
		else if (actor instanceof Player)
		{
			o.addProperty("k", "player");
			o.addProperty("i", ((Player) actor).getId());
		}
		else
		{
			o.addProperty("k", "other");
		}
		return o;
	}

	/**
	 * The tile the server has the actor on, which the drawn position ("x"/"y", "lx"/"ly") catches up to
	 * over the tick as the client animates the move. For NPCs bigger than one tile it is the south-west tile
	 */
	private static JsonObject serverTile(Actor actor)
	{
		WorldView wv = actor.getWorldView();
		WorldPoint wp = actor.getWorldLocation();
		if (wv == null || wp == null)
		{
			return null;
		}
		LocalPoint lp = LocalPoint.fromScene(wp.getX() - wv.getBaseX(), wp.getY() - wv.getBaseY(), wv);
		JsonObject t = new JsonObject();
		tile(t, wv, lp, wp.getPlane());
		t.remove("lx");
		t.remove("ly");
		return t;
	}

	/**
	 * Stand, stand-turn left and right, walk, walk back, walk left, walk right and run animations
	 */
	static JsonArray moveSet(Actor actor)
	{
		return ints(new int[]{
			actor.getIdlePoseAnimation(), actor.getIdleRotateLeft(), actor.getIdleRotateRight(),
			actor.getWalkAnimation(), actor.getWalkRotate180(), actor.getWalkRotateLeft(), actor.getWalkRotateRight(),
			actor.getRunAnimation()
		});
	}

	/**
	 * Per-NPC model, colour and texture changes on top of the cache definition, which some bosses use
	 */
	static void overrides(JsonObject o, NPC npc)
	{
		NpcOverrides overrides = npc.getModelOverrides();
		if (overrides == null)
		{
			return;
		}
		JsonObject v = new JsonObject();
		v.add("models", ints(overrides.getModelIds()));
		v.add("colors", shorts(overrides.getColorToReplaceWith()));
		v.add("textures", shorts(overrides.getTextureToReplaceWith()));
		v.addProperty("useLocalPlayer", overrides.useLocalPlayer());
		o.add("overrides", v);
	}

	static JsonArray spotAnims(Actor actor)
	{
		JsonArray list = new JsonArray();
		for (ActorSpotAnim spot : actor.getSpotAnims())
		{
			JsonObject s = new JsonObject();
			s.addProperty("id", spot.getId());
			s.addProperty("h", spot.getHeight());
			s.addProperty("start", spot.getStartCycle());
			s.addProperty("f", spot.getFrame());
			list.add(s);
		}
		return list;
	}

	/**
	 * What any actor is doing this tick
	 */
	static JsonObject actor(Client client, Actor actor)
	{
		JsonObject o = new JsonObject();
		o.add("who", ref(client, actor));
		actorTile(o, actor);
		JsonObject server = serverTile(actor);
		if (server != null)
		{
			o.add("tile", server);
		}
		o.addProperty("o", actor.getCurrentOrientation());
		if (actor.getOrientation() != actor.getCurrentOrientation())
		{
			// Still turning: where it will end up facing
			o.addProperty("of", actor.getOrientation());
		}
		o.addProperty("a", actor.getAnimation());
		o.addProperty("af", actor.getAnimationFrame());
		o.addProperty("pose", actor.getPoseAnimation());
		JsonArray spots = spotAnims(actor);
		if (spots.size() > 0)
		{
			o.add("spot", spots);
		}
		if (actor.getHealthRatio() >= 0)
		{
			JsonArray hp = new JsonArray();
			hp.add(actor.getHealthRatio());
			hp.add(actor.getHealthScale());
			o.add("hp", hp);
		}
		if (actor.getInteracting() != null)
		{
			o.add("int", ref(client, actor.getInteracting()));
		}
		if (actor instanceof NPC)
		{
			NPC npc = (NPC) actor;
			int[] archives = npc.getOverheadArchiveIds();
			short[] sprites = npc.getOverheadSpriteIds();
			if (archives != null && sprites != null)
			{
				JsonArray icons = new JsonArray();
				for (int i = 0; i < Math.min(archives.length, sprites.length); i++)
				{
					if (archives[i] >= 0)
					{
						JsonArray icon = new JsonArray();
						icon.add(archives[i]);
						icon.add(sprites[i]);
						icons.add(icon);
					}
				}
				if (icons.size() > 0)
				{
					o.add("head", icons);
				}
			}
		}
		else if (actor instanceof Player)
		{
			Player player = (Player) actor;
			HeadIcon icon = player.getOverheadIcon();
			if (icon != null)
			{
				o.addProperty("head", icon.name());
			}
			if (player.getSkullIcon() != -1)
			{
				o.addProperty("skull", player.getSkullIcon());
			}
		}
		return o;
	}

	/**
	 * What a player looks like. "equip" is the game's own 12-slot body array in KitType order: values of
	 * ITEM_OFFSET and up are worn items (subtract it for the item ID), KIT_OFFSET and up are body kits
	 * (subtract it for the kit ID), 0 is an empty slot. "colors" are the body colour indices
	 */
	static void look(JsonObject o, Client client, Player player, PlayerComposition comp)
	{
		o.add("who", ref(client, player));
		o.addProperty("gender", comp.getGender());
		o.addProperty("cb", player.getCombatLevel());
		o.addProperty("team", player.getTeam());
		o.add("equip", ints(comp.getEquipmentIds()));
		o.add("colors", ints(comp.getColors()));
		o.addProperty("itemOffset", PlayerComposition.ITEM_OFFSET);
		o.addProperty("kitOffset", PlayerComposition.KIT_OFFSET);
		if (comp.getTransformedNpcId() != -1)
		{
			o.addProperty("npc", comp.getTransformedNpcId());
		}
	}

	/**
	 * Everything the cache says about an NPC that a replica needs: written once per NPC ID per session
	 */
	static JsonObject npcDefinition(NPC npc)
	{
		NPCComposition def = npc.getTransformedComposition();
		if (def == null)
		{
			def = npc.getComposition();
		}
		JsonObject o = new JsonObject();
		o.addProperty("id", def.getId());
		o.addProperty("name", Text.removeTags(def.getName()));
		o.addProperty("size", def.getSize());
		o.addProperty("level", def.getCombatLevel());
		o.add("models", ints(def.getModels()));
		o.add("stats", ints(def.getStats()));
		o.add("actions", strings(def.getActions()));
		o.addProperty("widthScale", def.getWidthScale());
		o.addProperty("heightScale", def.getHeightScale());
		if (def.getId() != npc.getId())
		{
			o.addProperty("baseId", npc.getId());
		}
		return o;
	}

	static JsonObject objectDefinition(ObjectComposition def)
	{
		JsonObject o = new JsonObject();
		o.addProperty("id", def.getId());
		o.addProperty("name", Text.removeTags(def.getName()));
		o.addProperty("sizeX", def.getSizeX());
		o.addProperty("sizeY", def.getSizeY());
		o.add("actions", strings(def.getActions()));
		o.add("impostors", ints(def.getImpostorIds()));
		return o;
	}

	static JsonObject object(TileObject obj)
	{
		JsonObject o = new JsonObject();
		o.addProperty("id", obj.getId());
		// The object's own floor, not the one you're standing on: scenes hold every floor at once
		tile(o, obj.getWorldView(), obj.getLocalLocation(), obj.getPlane());
		o.addProperty("pl", obj.getPlane());
		o.addProperty("kind", kind(obj));
		if (obj instanceof GameObject)
		{
			GameObject g = (GameObject) obj;
			o.addProperty("orient", g.getOrientation());
			o.addProperty("config", g.getConfig());
			o.addProperty("w", g.getSceneMaxLocation().getX() - g.getSceneMinLocation().getX() + 1);
			o.addProperty("l", g.getSceneMaxLocation().getY() - g.getSceneMinLocation().getY() + 1);
		}
		else if (obj instanceof WallObject)
		{
			WallObject w = (WallObject) obj;
			o.addProperty("orientA", w.getOrientationA());
			o.addProperty("orientB", w.getOrientationB());
			o.addProperty("config", w.getConfig());
		}
		else if (obj instanceof DecorativeObject)
		{
			o.addProperty("config", ((DecorativeObject) obj).getConfig());
		}
		else if (obj instanceof GroundObject)
		{
			o.addProperty("config", ((GroundObject) obj).getConfig());
		}
		int anim = animation(renderable(obj));
		if (anim != -1)
		{
			o.addProperty("anim", anim);
		}
		return o;
	}

	/** game, wall, decor or ground, as the obj lines' "kind" */
	static String kind(TileObject obj)
	{
		return obj instanceof GameObject ? "game" : obj instanceof WallObject ? "wall" : obj instanceof DecorativeObject ? "decor"
			: obj instanceof GroundObject ? "ground" : "other";
	}

	/** What draws an object (its first renderable): a DynamicObject while it's animated */
	static Renderable renderable(TileObject obj)
	{
		if (obj instanceof GameObject)
		{
			return ((GameObject) obj).getRenderable();
		}
		if (obj instanceof WallObject)
		{
			return ((WallObject) obj).getRenderable1();
		}
		if (obj instanceof DecorativeObject)
		{
			return ((DecorativeObject) obj).getRenderable();
		}
		if (obj instanceof GroundObject)
		{
			return ((GroundObject) obj).getRenderable();
		}
		return null;
	}

	/** The animation a renderable plays, or -1 */
	static int animation(Renderable renderable)
	{
		if (renderable instanceof DynamicObject)
		{
			Animation anim = ((DynamicObject) renderable).getAnimation();
			if (anim != null)
			{
				return anim.getId();
			}
		}
		return -1;
	}

	/** A projectile's launch, as Encounter Recorder's proj line (from ProjectileMoved, or the client's list) */
	static void projectile(JsonObject line, Client client, WorldView wv, Projectile proj)
	{
		line.addProperty("id", proj.getId());
		line.add("from", ref(client, proj.getSourceActor()));
		line.add("to", ref(client, proj.getTargetActor()));
		JsonObject start = new JsonObject();
		if (proj.getSourcePoint() != null)
		{
			tile(start, wv, LocalPoint.fromWorld(wv, proj.getSourcePoint()), proj.getSourceLevel());
		}
		line.add("start", start);
		JsonObject target = new JsonObject();
		if (proj.getTargetPoint() != null)
		{
			tile(target, wv, LocalPoint.fromWorld(wv, proj.getTargetPoint()), proj.getTargetLevel());
		}
		line.add("target", target);
		line.addProperty("startCycle", proj.getStartCycle());
		line.addProperty("endCycle", proj.getEndCycle());
		line.addProperty("startHeight", proj.getStartHeight());
		line.addProperty("endHeight", proj.getEndHeight());
		line.addProperty("slope", proj.getSlope());
		line.addProperty("startPos", proj.getStartPos());
	}

	static JsonArray ints(int[] values)
	{
		JsonArray list = new JsonArray();
		if (values != null)
		{
			for (int v : values)
			{
				list.add(v);
			}
		}
		return list;
	}

	static JsonArray shorts(short[] values)
	{
		JsonArray list = new JsonArray();
		if (values != null)
		{
			for (short v : values)
			{
				list.add(v);
			}
		}
		return list;
	}

	static JsonArray strings(String[] values)
	{
		JsonArray list = new JsonArray();
		if (values != null)
		{
			for (String v : values)
			{
				list.add(v == null ? JsonNull.INSTANCE : new JsonPrimitive(v));
			}
		}
		return list;
	}

	static JsonArray grid(int[][] values)
	{
		JsonArray rows = new JsonArray();
		for (int[] row : values)
		{
			rows.add(ints(row));
		}
		return rows;
	}
}
