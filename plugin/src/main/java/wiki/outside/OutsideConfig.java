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
		description = "Off: nothing is sent anywhere.",
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
		keyName = "vitals",
		name = "Hitpoints, prayer, run and special",
		description = "And which prayers are on",
		position = 4
	)
	default boolean vitals()
	{
		return true;
	}
}
