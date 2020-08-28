package net.runelite.client.plugins.discordlootlogger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(DiscordLootLoggerConfig.GROUP)
public interface DiscordLootLoggerConfig extends Config
{
	String GROUP = "discordlootlogger";

	@ConfigItem(
		keyName = "webhook",
		name = "Webhook URL",
		description = "The Discord Webhook URL to send messages to"
	)
	String webhook();

	@ConfigItem(
		keyName = "lootnpcs",
		name = "Loot NPCs",
		description = "Only logs loot from these NPCs, comma separated"
	)
	String lootNpcs();

	@ConfigItem(
		keyName = "lootvalue",
		name = "Loot Value",
		description = "Only logs loot worth more then the given value. 0 to disable."
	)
	default int lootValue()
	{
		return 0;
	}
}