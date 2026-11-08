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
			+ " sent till you're out, so nobody watching your page can see what you risk.",
		warning = "On, anyone who opens your Outside page can see what you carry while you're in the Wilderness or on a"
			+ " PvP world, and what's in your looting bag.",
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
			+ " your outstation's transmitting. Never in the Wilderness, a PvP area, on a PvP or Deadman world or in an"
			+ " instance: there, what was sent is cleared at once.",
		warning = "On, the rough area you were in two minutes ago (a square of the map about 128 tiles a side) goes to"
			+ " your outstation's relay, a server not controlled or verified by the RuneLite developers, at most once a"
			+ " minute, and anyone who opens outside.wiki's globe can see it while your outstation's transmitting.",
		position = 6
	)
	default boolean follow()
	{
		return false;
	}
}
