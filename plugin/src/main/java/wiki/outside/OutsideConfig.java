package wiki.outside;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OutsideConfig.GROUP)
public interface OutsideConfig extends Config
{
	String GROUP = "outside";

	@ConfigItem(
		keyName = "stationKey",
		name = "Station key",
		description = "Your outstation's station key (it starts outstation1:), from the outstation or a friend's invite."
			+ " With it, what you wear and carry and your stats show live on your Outside page.",
		secret = true,
		position = 0
	)
	default String stationKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "send",
		name = "Send to my outstation",
		description = "What's switched on below goes to your outstation's relay (Jace's, on Cloudflare, unless your key"
			+ " names another), and your Outside page shows it to anyone. Off: nothing is sent anywhere.",
		warning = "This sends what you wear and carry, your looting bag, rune pouch, skills and vitals, and your IP"
			+ " address, to your outstation's relay, a server not controlled or verified by the RuneLite developers."
			+ " Your Outside page shows them to anyone who opens it.",
		position = 1
	)
	default boolean send()
	{
		return true;
	}

	@ConfigItem(
		keyName = "items",
		name = "Worn and carried",
		description = "Your equipment, inventory, looting bag (once you've looked in it), rune pouch and quiver",
		position = 2
	)
	default boolean items()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skills",
		name = "Skills",
		description = "Each skill's level, boosted level and experience",
		position = 3
	)
	default boolean skills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wilderness",
		name = "In the Wilderness too",
		description = "Off (to begin with): in the Wilderness, a PvP area or on a PvP or Deadman world, nothing more is"
			+ " sent till you're out, so nobody watching your page can see what you risk (Ferox Enclave and the PvP"
			+ " minigames, Last Man Standing, Castle Wars, Clan Wars, Soul Wars, the Fight Pits and Emir's Arena, are"
			+ " safe: it keeps going there). On, with \"What I'm doing, on"
			+ " the globe\" on too, what you were doing in the Wilderness (a Wilderness boss, say) shows on the globe two"
			+ " minutes late; never where you are, your world or a star there.",
		warning = "On, anyone who opens your Outside page can see what you carry while you're in the Wilderness or on a"
			+ " PvP world, and what's in your looting bag; and, with \"What I'm doing, on the globe\" on, anyone on"
			+ " outside.wiki's globe can see what you were doing in the Wilderness two minutes before.",
		position = 5
	)
	default boolean wilderness()
	{
		return false;
	}

	@ConfigItem(
		keyName = "vitals",
		name = "Hitpoints, prayer, run and special",
		description = "And which prayers are on",
		position = 4
	)
	default boolean vitals()
	{
		return true;
	}

	@ConfigItem(
		keyName = "follow",
		name = "Follow me on the globe",
		description = "Off (to begin with). On, your outstation's light on outside.wiki's globe travels to the rough area"
			+ " you were in two minutes ago (a square of the map about 128 tiles a side), at most once a minute, while"
			+ " your outstation's transmitting. In an instance (a raid, a boss room), the area it's opened from. Never in"
			+ " the Wilderness (whatever \"In the Wilderness too\" says), a PvP area, or on a PvP or Deadman world: there,"
			+ " what was sent is cleared at once. Ferox Enclave and the PvP minigames are safe.",
		warning = "On, the rough area you were in two minutes ago (a square of the map about 128 tiles a side) goes to"
			+ " your outstation's relay, a server not controlled or verified by the RuneLite developers, at most once a"
			+ " minute, and anyone who opens outside.wiki's globe can see it while your outstation's transmitting.",
		position = 6
	)
	default boolean follow()
	{
		return false;
	}

	@ConfigItem(
		keyName = "followWorld",
		name = "And my world",
		description = "Off (to begin with). On, with following on too, the world you were on two minutes ago goes with"
			+ " the square, so the globe can show which world your light's on. Never on a PvP, Deadman or other"
			+ " dangerous world, and never without following.",
		warning = "On, the world you were on two minutes ago goes with the rough area (a square of the map about 128"
			+ " tiles a side), so anyone who opens outside.wiki's globe while your outstation's transmitting can see which"
			+ " world and roughly where you were two minutes before.",
		position = 7
	)
	default boolean followWorld()
	{
		return false;
	}

	@ConfigItem(
		keyName = "activity",
		name = "What I'm doing, on the globe",
		description = "Off (to begin with). On, your outstation's light on outside.wiki's globe shows what you were doing"
			+ " two minutes ago, only as one word: skilling (and which skill), a boss, a raid or a minigame (and which),"
			+ " fighting, or idle. At most once a minute, while your outstation's transmitting. Never in a PvP area or on"
			+ " a PvP or Deadman world (Ferox Enclave and the PvP minigames are safe), nor in the Wilderness unless"
			+ " \"In the Wilderness too\" is on: there, what was sent is cleared at once. It doesn't need following on.",
		warning = "On, what you were doing two minutes ago (skilling and which skill, a boss, raid or minigame and"
			+ " which, fighting, or idle) goes to your outstation's relay, a server not controlled or verified by the RuneLite developers,"
			+ " at most once a minute, and anyone who opens outside.wiki's globe can see it while your outstation's"
			+ " transmitting.",
		position = 8
	)
	default boolean activity()
	{
		return false;
	}

	@ConfigItem(
		keyName = "stars",
		name = "Shooting stars I find",
		description = "Off (to begin with). On, a crashed shooting star you see goes on outside.wiki's list of stars:"
			+ " its size, its world, its landing site and the rough area it's in (a square about 128 tiles a side), two"
			+ " minutes late (with your outstation's name as its finder only if you say so, below). Never one in the Wilderness, or"
			+ " seen from it, a PvP area or world, or Deadman (Ferox Enclave and the PvP minigames are safe).",
		warning = "On, each shooting star you see (its size, world, landing site and rough area, two minutes late) goes"
			+ " to your outstation's relay, a server not controlled or verified by the RuneLite developers, which shows it"
			+ " to anyone on outside.wiki's list of stars (your outstation's name with it only with \"Name me as a star's"
			+ " finder\" on).",
		position = 9
	)
	default boolean stars()
	{
		return false;
	}

	@ConfigItem(
		keyName = "starsCredit",
		name = "Name me as a star's finder",
		description = "Off (to begin with). Only with \"Shooting stars I find\" on: your outstation's name goes on"
			+ " outside.wiki's list of stars beside each star you told of first. Off, your stars still go on the list,"
			+ " with no finder named.",
		warning = "On, outside.wiki's list of stars shows your outstation's name beside each shooting star you told of"
			+ " first (its world, landing site and rough area, two minutes after you saw it), to anyone who opens it.",
		position = 10
	)
	default boolean starsCredit()
	{
		return false;
	}

	@ConfigItem(
		keyName = "recordPath",
		name = "Record my exact path for my year",
		description = "Off (to begin with). On, the exact tile you're on goes to your own outstation, only for your"
			+ " year's record on your PC (and its encrypted copy, if you keep one): when you move, at most once every"
			+ " few seconds, and when you stop. On a boat, where it is at sea. Your relay passes it only to your own"
			+ " outstation's app, never keeps it, and never shows it to anyone. Never in the Wilderness, a PvP area, or on"
			+ " a PvP or Deadman world (Ferox Enclave and the PvP minigames are safe).",
		warning = "On, the exact tile you're on (and your world) goes to your outstation's relay, a server not controlled"
			+ " or verified by the RuneLite developers, every few seconds while you move. The relay passes it on only to"
			+ " your own outstation's app, for your year's record, and doesn't keep it; but it passes through that server.",
		position = 11
	)
	default boolean recordPath()
	{
		return false;
	}
}
