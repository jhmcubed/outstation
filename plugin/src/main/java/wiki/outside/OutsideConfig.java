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
		description = "Your station key (it starts outstation1:): made for you by \"Make my Outside page\" in the Outside side tab,"
			+ " or from the outstation or a friend's invite. With it, what you wear and carry and your stats show live on your Outside page.",
		secret = true,
		position = 0
	)
	default String stationKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "send",
		name = "Send to my Outside page",
		description = "What's switched on below goes to the Outside relay (on Cloudflare, unless your key names another),"
			+ " and your Outside page shows it to anyone. Off: nothing is sent anywhere.",
		warning = "This sends what you wear and carry, your looting bag, rune pouch, skills and vitals, and your IP"
			+ " address, to the Outside relay, a server not controlled or verified by the RuneLite developers."
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
		description = "Your equipment, inventory, looting bag (once you've looked in it), seed box, chugging barrel, rune pouch and quiver",
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
		keyName = "progress",
		name = "Quests, diaries and combat achievements",
		description = "Your quests (done and started) and quest points, each Achievement Diary's tiers and tasks, and your combat"
			+ " achievements' points and tasks by tier",
		position = 3
	)
	default boolean progress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "slayer",
		name = "My slayer task",
		description = "Your slayer task (the monster, how many are left of how many, and where if your master named it),"
			+ " your slayer points and your task streak",
		position = 3
	)
	default boolean slayer()
	{
		return true;
	}

	// hub:cut talk
	@ConfigItem(
		keyName = "chat",
		name = "The page's chat in my game",
		description = "What visitors say in your Outside page's chat shows in your game's chat box, and ::o <words> or /o <words> answers"
			+ " them (::ochat off stops their lines). Your page shows its chat while this is on, even with nothing transmitting.",
		warning = "Lines anyone types in your Outside page's chat will show in your game's chat box, under their own chosen"
			+ " names, and your page will offer a chat while you play. Nothing is sent but what you type after ::o or /o. Switch it on?",
		position = 12
	)
	default boolean chat()
	{
		return false;
	}
	// hub:end talk

	// hub:cut globe
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
	// hub:end globe

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

	// hub:cut globe
	@ConfigItem(
		keyName = "follow",
		name = "Follow me on the globe",
		description = "Off (to begin with). On, your outstation's light on outside.wiki's globe travels to the rough area"
			+ " you were in two minutes ago (a square of the map about 128 tiles a side), at most once a minute, while"
			+ " you're logged in with this plugin (or your outstation's transmitting). In an instance (a raid, a boss room), the area it's opened from. Never in"
			+ " the Wilderness (whatever \"In the Wilderness too\" says), a PvP area, or on a PvP or Deadman world: there,"
			+ " what was sent is cleared at once. Ferox Enclave and the PvP minigames are safe.",
		warning = "On, the rough area you were in two minutes ago (a square of the map about 128 tiles a side) goes to"
			+ " the Outside relay, a server not controlled or verified by the RuneLite developers, at most once a"
			+ " minute, and anyone who opens outside.wiki's globe can see it while you're logged in with this plugin or your"
			+ " outstation's transmitting.",
		position = 6
	)
	default boolean follow()
	{
		return false;
	}
	// hub:end globe

	// hub:cut globe
	@ConfigItem(
		keyName = "followWorld",
		name = "And my world",
		description = "Off (to begin with). On, with following on too, the world you were on two minutes ago goes with"
			+ " the square, so the globe can show which world your light's on. Never on a PvP, Deadman or other"
			+ " dangerous world, and never without following.",
		warning = "On, the world you were on two minutes ago goes with the rough area (a square of the map about 128"
			+ " tiles a side), so anyone who opens outside.wiki's globe while you're logged in with this plugin can see which"
			+ " world and roughly where you were two minutes before.",
		position = 7
	)
	default boolean followWorld()
	{
		return false;
	}
	// hub:end globe

	// hub:cut globe
	@ConfigItem(
		keyName = "activity",
		name = "What I'm doing, on the globe",
		description = "Off (to begin with). On, your outstation's light on outside.wiki's globe shows what you were doing"
			+ " two minutes ago, only as one word: skilling (and which skill), a boss, a raid or a minigame (and which),"
			+ " fighting, or idle. At most once a minute, while you're logged in with this plugin. Never in a PvP area or on"
			+ " a PvP or Deadman world (Ferox Enclave and the PvP minigames are safe), nor in the Wilderness unless"
			+ " \"In the Wilderness too\" is on: there, what was sent is cleared at once. It doesn't need following on.",
		warning = "On, what you were doing two minutes ago (skilling and which skill, a boss, raid or minigame and"
			+ " which, fighting, or idle) goes to the Outside relay, a server not controlled or verified by the RuneLite developers,"
			+ " at most once a minute, and anyone who opens outside.wiki's globe can see it while you're logged in with"
			+ " this plugin.",
		position = 8
	)
	default boolean activity()
	{
		return false;
	}
	// hub:end globe

	// hub:cut globe
	@ConfigItem(
		keyName = "stars",
		name = "Shooting stars I find",
		description = "Off (to begin with). On, a crashed shooting star you see goes on outside.wiki's list of stars:"
			+ " its size, its world, its landing site and the rough area it's in (a square about 128 tiles a side), two"
			+ " minutes late (with your outstation's name as its finder only if you say so, below). Never one in the Wilderness, or"
			+ " seen from it, a PvP area or world, or Deadman (Ferox Enclave and the PvP minigames are safe).",
		warning = "On, each shooting star you see (its size, world, landing site and rough area, two minutes late) goes"
			+ " to the Outside relay, a server not controlled or verified by the RuneLite developers, which shows it"
			+ " to anyone on outside.wiki's list of stars (your outstation's name with it only with \"Name me as a star's"
			+ " finder\" on).",
		position = 9
	)
	default boolean stars()
	{
		return false;
	}
	// hub:end globe

	// hub:cut globe
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
	// hub:end globe

	// hub:cut year
	@ConfigItem(
		keyName = "year",
		name = "Record my year",
		description = "On (to begin with). This plugin keeps your year on this PC: the rough area you're in (a square of"
			+ " the map about 128 tiles a side, two minutes behind), what you're doing, your levels, kills, pets, log"
			+ " slots, clues and big drops, and with \"Record my exact path\" on, the tiles you walk. Nothing of it leaves"
			+ " this PC unless the switch below is on. Never in the Wilderness, a PvP area, or on a PvP or Deadman world."
			+ " Kept in RuneLite's folder, outside/record.",
		position = 10
	)
	default boolean year()
	{
		return true;
	}
	// hub:end year

	// hub:cut year
	@ConfigItem(
		keyName = "yearCopy",
		name = "Keep an encrypted copy on the relay",
		description = "Off (to begin with). On, your year's record goes to your relay sealed with a key only this PC (and"
			+ " any you give it to) holds, so \"Open my year\" in the Outside tab can show it on outside.wiki. The relay"
			+ " can't read it, nor can anyone else without the key.",
		warning = "On, your year's record (where you've been, what you did, and with the exact path on, the tiles you"
			+ " walked) goes to the Outside relay, a server not controlled or verified by the RuneLite developers,"
			+ " encrypted with a key only your own computers hold. Lose the key and that copy can't be read by anyone.",
		position = 10
	)
	default boolean yearCopy()
	{
		return false;
	}
	// hub:end year

	// hub:cut year
	@ConfigItem(
		keyName = "recordPath",
		name = "Record my exact path for my year",
		description = "Off (to begin with). On, the exact tile you're on goes into your year's record on this PC (and its"
			+ " encrypted copy, if you keep one), and to your own outstation's app if you run one: when you move, at most"
			+ " once every few seconds, and when you stop. On a boat, where it is at sea. Your relay passes it only to your own"
			+ " outstation's app, never keeps it, and never shows it to anyone. Never in the Wilderness, a PvP area, or on"
			+ " a PvP or Deadman world (Ferox Enclave and the PvP minigames are safe).",
		warning = "On, the exact tile you're on (and your world) goes to the Outside relay, a server not controlled"
			+ " or verified by the RuneLite developers, every few seconds while you move. The relay passes it on only to"
			+ " your own outstation's app, for your year's record, and doesn't keep it; but it passes through that server.",
		position = 11
	)
	default boolean recordPath()
	{
		return false;
	}
	// hub:end year

	@ConfigItem(
		keyName = "charges",
		name = "Item charges",
		description = "Each worn or carried item's charges, as the game tells you them in its chat (when you check one, or"
			+ " as it's used): only the number, by the item",
		position = 12
	)
	default boolean charges()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reports",
		name = "Levels, kills, drops and log slots",
		description = "Your levels as you gain them, kill counts, collection log slots, pets, clue counts, quests, diary tiers,"
			+ " combat tasks, slayer tasks and drops worth at least the amount below, as the game tells you them, for your page's event log and outside.wiki's feed (as"
			+ " Dink would send them; while your outstation transmits, Dink's go through it instead)",
		position = 12
	)
	default boolean reports()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reportLoot",
		name = "Drops worth at least",
		description = "The least a drop is worth (in coins, at Grand Exchange prices) for it to go on your page",
		position = 12
	)
	default int reportLoot()
	{
		return 100_000;
	}

	// hub:cut delve
	@ConfigItem(
		keyName = "delve",
		name = "Doom of Mokhaiotl delves",
		description = "When you read the board beside the Doom of Mokhaiotl, how many delves you've finished at each level"
			+ " (as the Delve Calculator plugin reads it), for your Outside page",
		position = 13
	)
	default boolean delve()
	{
		return true;
	}
	// hub:end delve

	// hub:cut bank
	@ConfigItem(
		keyName = "bank",
		name = "My bank on my page",
		description = "Off (to begin with). On, your whole bank (every item, how many, and its tabs) goes to your Outside"
			+ " page each time you use it, where anyone can see it",
		warning = "On, your whole bank (every item and how many) goes to the Outside relay, a server not controlled"
			+ " or verified by the RuneLite developers, each time you use your bank, and your Outside page shows it to"
			+ " anyone who opens it. Switching it off takes it off your page.",
		position = 14
	)
	default boolean bank()
	{
		return false;
	}
	// hub:end bank

	// hub:cut model
	@ConfigItem(
		keyName = "model",
		name = "My character's model on my page",
		description = "Off (to begin with). On, your character's 3D model as you look now goes to your Outside page"
			+ " (as RuneProfile's does); type ::outsidemodel to send it again after a change. Off takes it down",
		warning = "On, your character's model as you look now (its shape and colours, nothing else) goes to the"
			+ " Outside relay, a server not controlled or verified by the RuneLite developers, and your Outside page"
			+ " shows it to anyone who opens it.",
		position = 15
	)
	default boolean model()
	{
		return false;
	}
	// hub:end model

	// hub:cut model
	@ConfigItem(
		keyName = "ground",
		name = "The ground under my model",
		description = "Off (to begin with). On, when you capture your character (::outsidemodel, ::outsidemotion or the side"
			+ " tab), the ground round you then (about five tiles each way: its paint and its shape, no objects or"
			+ " people) goes with it, and your page draws you standing on it. Never with the live view's own captures.",
		warning = "On, each time you capture your character, the ground round you then (about five tiles each way) goes"
			+ " to the Outside relay, a server not controlled or verified by the RuneLite developers, and your"
			+ " Outside page shows it to anyone who opens it.",
		position = 16
	)
	default boolean ground()
	{
		return false;
	}
	// hub:end model
}
