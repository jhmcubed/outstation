package wiki.outside;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(OutsideConfig.GROUP)
public interface OutsideConfig extends Config
{
	String GROUP = "outside";

	// (11/17/2026, Jace: "organize our plugin settings menu so it's less confusing. use runeprofile as your example":
	// the switches under a few headings, each with a short name; what each does in its description, under the hand)
	@ConfigSection(name = "Your page", description = "Whether this plugin sends to your Outside page at all", position = 0)
	String pageSection = "page";

	@ConfigSection(name = "On your page", description = "What goes on your page, each its own switch", position = 1)
	String shownSection = "shown";

	@ConfigSection(name = "What you're doing", description = "What your page and Outside.wiki's globe say you're doing", position = 2)
	String doingSection = "doing";

	// hub:cut reports
	@ConfigSection(name = "Field reports", description = "Your levels, kills and drops as they happen", position = 3)
	String reportsSection = "reports";
	// hub:end reports

	// hub:cut year
	@ConfigSection(name = "Your year", description = "Your year's record, kept on this PC", position = 4)
	String yearSection = "year";
	// hub:end year

	// hub:cut talk
	@ConfigSection(name = "Your page's chat", description = "Your page's chat in your game", position = 5)
	String chatSection = "chat";
	// hub:end talk

	@ConfigSection(name = "Advanced", description = "Your page's key, if you were given one", position = 9, closedByDefault = true)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "stationKey",
		name = "Station key",
		// hub:cut live
		description = "Your station key (it starts outstation1:): made for you by \"Make my Outside page\" in the Outside side tab,"
			+ " or from the outstation or a friend's invite. With it, what you wear and carry and your stats show on your Outside page.",
		// hub:end live
		// hub:only description = "Your page's key (it starts outstation1:): made for you by \"Make my Outside page\" in the Outside"
		// hub:only 	+ " side tab and kept with your account, so this stays blank. Paste one here only if you were given one.",
		secret = true,
		position = 0,
		section = advancedSection
	)
	default String stationKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "send",
		name = "Send to my Outside page",
		// hub:cut live
		description = "What's switched on below goes to the Outside relay (on Cloudflare, unless your key names another),"
			+ " and your Outside page shows it to anyone. While you play, you also show on Outside.wiki's globe and feed,"
			+ " unless your page is friends only (the Outside side tab). Off: nothing more is sent, and your gear, stats and progress come off your page.",
		// hub:end live
		// hub:only description = "What's switched on below goes to the Outside relay (on Cloudflare) when you press Update my page in the"
		// hub:only 	+ " Outside side tab, and hourly while you're logged in; what you're doing goes as it changes. Your Outside page"
		// hub:only 	+ " shows it to anyone, and while you play you show on outside.wiki's globe, unless your page is friends only"
		// hub:only 	+ " (the side tab). Off: nothing more is sent, and your gear, stats and progress come off your page.",
		// hub:cut live
		warning = "This submits your gear, stats, quests, diaries, slayer task, kills and drops, and your IP address,"
			+ " to the Outside relay, a 3rd-party server not controlled or verified by the RuneLite developers."
			+ " Your Outside page shows them publicly.",
		// hub:end live
		// hub:only warning = "This submits your gear, stats, quests, diaries, slayer task, what you're doing (as Discord shows it)"
		// hub:only 	+ " and your IP address to the Outside relay, a 3rd-party server not controlled or verified by the RuneLite"
		// hub:only 	+ " developers, when you press Update my page, and hourly. Your Outside page shows them publicly.",
		position = 0,
		section = pageSection
	)
	default boolean send()
	{
		return true;
	}

	@ConfigItem(
		keyName = "items",
		name = "Gear and inventory",
		description = "Your equipment, inventory, looting bag (once you've looked in it), seed box, chugging barrel, rune pouch and quiver",
		position = 0,
		section = shownSection
	)
	default boolean items()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skills",
		name = "Skills",
		description = "Each skill's level, boosted level and experience",
		position = 1,
		section = shownSection
	)
	default boolean skills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "progress",
		name = "Quests, diaries and progress",
		description = "Your quests (done and started) and quest points, each Achievement Diary's tiers and tasks, your combat"
			+ " achievements' points and tasks by tier, and your collection log's count (and the slots the game's chat says"
			+ " you've added since the last update), and your kill counts and clue counts as the game's chat last said them",
		position = 2,
		section = shownSection
	)
	default boolean progress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "slayer",
		name = "Slayer task",
		description = "Your slayer task (the monster, how many are left of how many, and where if your master named it),"
			+ " your slayer points and your task streak",
		position = 3,
		section = shownSection
	)
	default boolean slayer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "presence",
		name = "What I'm doing",
		description = "The skill you're training and the area you're in by name (a city, a dungeon, a boss, a raid, a minigame"
			+ " or a region), as RuneLite's own Discord plugin shows them: sent as they change (once a minute at most) and with"
			+ " each update. Your page shows it, and Outside.wiki's globe lights you in that area. Never your tile or your"
			+ " world, and nothing from the Wilderness or a PvP world.",
		position = 0,
		section = doingSection
	)
	default boolean presence()
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
		position = 0,
		section = chatSection
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
			+ " Outside.wiki's globe can see what you were doing in the Wilderness two minutes before.",
		position = 6,
		section = doingSection
	)
	default boolean wilderness()
	{
		return false;
	}
	// hub:end globe

	// hub:cut live
	@ConfigItem(
		keyName = "vitals",
		name = "Hitpoints, prayer, run and special",
		description = "And which prayers are on",
		position = 10,
		section = shownSection
	)
	default boolean vitals()
	{
		return true;
	}
	// hub:end live

	// hub:cut globe
	@ConfigItem(
		keyName = "follow",
		name = "Follow me on the globe",
		description = "Off (to begin with). On, your light on Outside.wiki's globe travels to the rough area"
			+ " you were in two minutes ago (a square of the map about 128 tiles a side), at most once a minute, while"
			+ " you're logged in with this plugin (or your outstation's transmitting). In an instance (a raid, a boss room), the area it's opened from. Never in"
			+ " the Wilderness (whatever \"In the Wilderness too\" says), a PvP area, or on a PvP or Deadman world: there,"
			+ " what was sent is cleared at once. Ferox Enclave and the PvP minigames are safe.",
		warning = "On, the rough area you were in two minutes ago (a square of the map about 128 tiles a side) goes to"
			+ " the Outside relay, a server not controlled or verified by the RuneLite developers, at most once a"
			+ " minute, and anyone who opens Outside.wiki's globe can see it while you're logged in with this plugin or your"
			+ " outstation's transmitting.",
		position = 1,
		section = doingSection
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
			+ " tiles a side), so anyone who opens Outside.wiki's globe while you're logged in with this plugin can see which"
			+ " world and roughly where you were two minutes before.",
		position = 2,
		section = doingSection
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
		description = "Off (to begin with). On, your light on Outside.wiki's globe shows what you were doing"
			+ " two minutes ago, only as one word: skilling (and which skill), a boss, a raid or a minigame (and which),"
			+ " fighting, or idle. At most once a minute, while you're logged in with this plugin. Never in a PvP area or on"
			+ " a PvP or Deadman world (Ferox Enclave and the PvP minigames are safe), nor in the Wilderness unless"
			+ " \"In the Wilderness too\" is on: there, what was sent is cleared at once. It doesn't need following on.",
		warning = "On, what you were doing two minutes ago (skilling and which skill, a boss, raid or minigame and"
			+ " which, fighting, or idle) goes to the Outside relay, a server not controlled or verified by the RuneLite developers,"
			+ " at most once a minute, and anyone who opens Outside.wiki's globe can see it while you're logged in with"
			+ " this plugin.",
		position = 3,
		section = doingSection
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
		description = "Off (to begin with). On, a crashed shooting star you see goes on Outside.wiki's list of stars:"
			+ " its size, its world, its landing site and the rough area it's in (a square about 128 tiles a side), two"
			+ " minutes late (with your name as its finder only if you say so, below). Never one in the Wilderness, or"
			+ " seen from it, a PvP area or world, or Deadman (Ferox Enclave and the PvP minigames are safe).",
		warning = "On, each shooting star you see (its size, world, landing site and rough area, two minutes late) goes"
			+ " to the Outside relay, a server not controlled or verified by the RuneLite developers, which shows it"
			+ " to anyone on Outside.wiki's list of stars (your name with it only with \"Name me as a star's"
			+ " finder\" on).",
		position = 4,
		section = doingSection
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
		description = "Off (to begin with). Only with \"Shooting stars I find\" on: your name goes on"
			+ " Outside.wiki's list of stars beside each star you told of first. Off, your stars still go on the list,"
			+ " with no finder named.",
		warning = "On, Outside.wiki's list of stars shows your name beside each shooting star you told of"
			+ " first (its world, landing site and rough area, two minutes after you saw it), to anyone who opens it.",
		position = 5,
		section = doingSection
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
		position = 0,
		section = yearSection
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
			+ " any you give it to) holds, so \"Open my year\" in the Outside tab can show it on Outside.wiki. The relay"
			+ " can't read it, nor can anyone else without the key.",
		warning = "On, your year's record (where you've been, what you did, and with the exact path on, the tiles you"
			+ " walked) goes to the Outside relay, a server not controlled or verified by the RuneLite developers,"
			+ " encrypted with a key only your own computers hold. Lose the key and that copy can't be read by anyone.",
		position = 1,
		section = yearSection
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
			+ " encrypted copy, if you keep one). If you also run the outstation app, the tile goes to it through your"
			+ " relay too, only while the app is open: the relay passes it on, never keeps it, and never shows it to anyone."
			+ " On a boat, where it is at sea. Never in the Wilderness, a PvP area, or on a PvP or Deadman world (Ferox"
			+ " Enclave and the PvP minigames are safe).",
		warning = "On, while your outstation app is open, the exact tile you're on (and your world) goes to the Outside"
			+ " relay, a 3rd-party server not controlled or verified by the RuneLite developers, every few seconds while you"
			+ " move, for the app's record. The relay doesn't keep it. Without the app open, it stays on this PC.",
		position = 2,
		section = yearSection
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
		position = 4,
		section = shownSection
	)
	default boolean charges()
	{
		return true;
	}

	// hub:cut reports
	@ConfigItem(
		keyName = "reports",
		name = "Levels, kills, drops and log slots",
		description = "Your levels as you gain them, kill counts, collection log slots, pets, clue counts, quests, diary tiers,"
			+ " combat tasks, slayer tasks and drops worth at least the amount below, as the game tells you them, for your page's event log and Outside.wiki's feed (as"
			+ " Dink would send them; while your outstation transmits, Dink's go through it instead)",
		position = 0,
		section = reportsSection
	)
	default boolean reports()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reportLoot",
		name = "Drops worth at least",
		description = "The least a drop is worth (in coins, at Grand Exchange prices) for it to go on your page",
		position = 1,
		section = reportsSection
	)
	default int reportLoot()
	{
		return 100_000;
	}
	// hub:end reports

	// hub:cut delve
	@ConfigItem(
		keyName = "delve",
		name = "Doom of Mokhaiotl delves",
		description = "When you read the board beside the Doom of Mokhaiotl, how many delves you've finished at each level"
			+ " (as the Delve Calculator plugin reads it), and how many a death lost from then on, for your Outside page",
		position = 5,
		section = shownSection
	)
	default boolean delve()
	{
		return true;
	}
	// hub:end delve

	// hub:cut bank
	@ConfigItem(
		keyName = "bank",
		name = "Bank",
		description = "On (to begin with): your whole bank (every item, how many, and its tabs) goes to your Outside"
			// hub:cut live
			+ " page each time you use it, where anyone can see it",
			// hub:end live
			// hub:only + " page with your next update after you use it (Update my page, or the hourly one), where anyone can see it",
		warning = "On, your whole bank (every item and how many) goes to the Outside relay, a server not controlled"
			// hub:cut live
			+ " or verified by the RuneLite developers, each time you use your bank, and your Outside page shows it to"
			// hub:end live
			// hub:only + " or verified by the RuneLite developers, with your next update after you use your bank, and your Outside page shows it to"
			+ " anyone who opens it. Switching it off takes it off your page.",
		position = 6,
		section = shownSection
	)
	default boolean bank()
	{
		return true;
	}
	// hub:end bank

	// hub:cut deathbank
	@ConfigItem(
		keyName = "deathbank",
		name = "Deathbank",
		description = "On (to begin with): when a death makes a deathbank (the Item Retrieval Service's, as at"
			+ " Hespori, Zulrah or Vorkath), what it took and which one it is go to your Outside page, where anyone"
			// hub:cut live
			+ " can see them, and again as its window shows it when you look in it. Emptied, it comes off your page",
			// hub:end live
			// hub:only + " can see them, with your next update (and again as its window shows it after you look in it). Emptied, it comes off your page",
		warning = "On, each time a death makes a deathbank or you look in one, what's in it (every item and how many) and which boss or place"
			// hub:cut live
			+ " it's for go to the Outside relay, a server not controlled or verified by the RuneLite developers, and"
			// hub:end live
			// hub:only + " it's for go to the Outside relay, a server not controlled or verified by the RuneLite developers, with your next update, and"
			+ " your Outside page shows them to anyone who opens it. Switching it off takes it off your page.",
		position = 7,
		section = shownSection
	)
	default boolean deathbank()
	{
		return true;
	}
	// hub:end deathbank

	// hub:cut model
	@ConfigItem(
		keyName = "model",
		name = "Character model",
		description = "On (to begin with): your character's 3D model as you look now goes to your Outside page"
			+ " (as RuneProfile's does); type ::outsidemodel to send it again after a change. Off takes it down",
		warning = "On, your character's model as you look now (its shape and colours, nothing else) goes to the"
			+ " Outside relay, a server not controlled or verified by the RuneLite developers, and your Outside page"
			+ " shows it to anyone who opens it.",
		position = 8,
		section = shownSection
	)
	default boolean model()
	{
		return true;
	}
	// hub:end model

	// hub:cut model
	@ConfigItem(
		keyName = "ground",
		name = "Ground under my model",
		description = "On (to begin with): when you capture your character (::outsidemodel, ::outsidemotion or the side"
			+ " tab), the ground round you then (about five tiles each way: its paint and its shape, no objects or"
			+ " people) goes with it, and your page draws you standing on it. Never with the live view's own captures.",
		warning = "On, each time you capture your character, the ground round you then (about five tiles each way) goes"
			+ " to the Outside relay, a server not controlled or verified by the RuneLite developers, and your"
			+ " Outside page shows it to anyone who opens it.",
		position = 9,
		section = shownSection,
		// (11/17/2026, Jace: "this setting should be in the model section": a box in the side tab's Model tab, by the
		// capture it goes with, not here)
		hidden = true
	)
	default boolean ground()
	{
		return true;
	}
	// hub:end model

	// hub:cut goal
	@ConfigItem(
		keyName = "goal",
		name = "Goal item",
		description = "On (to begin with): the item you're after (\"Set my goal item\" in the side tab, or ::outsidegoal) goes"
			+ " to your Outside page with its 3D model, and the page shows your kill count and how dry you are for it."
			+ " When the game's collection log says you got it, the page says so. ::outsidegoal clear takes it off",
		warning = "On, the item you set as your goal (its name and its 3D model) and, when you get it, your kill and clue"
			+ " counts then go to the Outside relay, a server not controlled or verified by the RuneLite developers, and"
			+ " your Outside page shows them to anyone who opens it.",
		position = 10,
		section = shownSection
	)
	default boolean goal()
	{
		return true;
	}
	// hub:end goal
}
