/*
 * Copyright (c) 2020, Hydrox6 <ikada@protonmail.ch>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.hydrox.cratelimit;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ItemDefinition;
import net.runelite.api.ItemID;
import net.runelite.api.MenuOpcode;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import javax.inject.Inject;
import java.util.Set;
import net.runelite.client.plugins.PluginType;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Crate Limiter",
	description = "Slows down the opening of crates and jars",
	tags = {"crate", "jar", "eclectic", "medium", "mediums", "rangers", "ranger", "clue", "clues", "open", "loot"},
	type = PluginType.MISCELLANEOUS,
	enabledByDefault = false
)
public class CrateLimiterPlugin extends Plugin
{
	private static final Set<MenuOpcode> VALID_MENU_ACTIONS = ImmutableSet.of(
		MenuOpcode.ITEM_FIRST_OPTION,
		MenuOpcode.ITEM_SECOND_OPTION,
		MenuOpcode.ITEM_THIRD_OPTION,
		MenuOpcode.ITEM_FOURTH_OPTION,
		MenuOpcode.ITEM_FIFTH_OPTION
	);

	// These items have an Open option, but shouldn't have a speed limit
	private static final Set<Integer> OPEN_EXCEPTIONS = ImmutableSet.of(
		ItemID.LOOTING_BAG,
		ItemID.HERB_SACK,
		ItemID.SEED_BOX,
		ItemID.BOLT_POUCH,
		ItemID.COAL_BAG,
		ItemID.GEM_BAG,
		ItemID.HUNTER_KIT,
		ItemID.RUNE_POUCH,
		ItemID.RUNE_POUCH_L,
		ItemID.MASTER_SCROLL_BOOK,
		ItemID.MASTER_SCROLL_BOOK_EMPTY
	);

	@Inject
	private Client client;

	@Inject
	private CrateLimiterConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	private int tick = -1;

	@Provides
	CrateLimiterConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CrateLimiterConfig.class);
	}

	@Subscribe
	void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!VALID_MENU_ACTIONS.contains(event.getOpcode()))
		{
			return;
		}
		// Seed Pack
		if (event.getOption().equals("Take"))
		{
			if (event.getIdentifier() != ItemID.SEED_PACK)
			{
				return;
			}
		}
		else if (event.getOption().equals("Open"))
		{
			if (OPEN_EXCEPTIONS.contains(event.getIdentifier()))
			{
				return;
			}
			ItemDefinition comp = client.getItemDefinition(event.getIdentifier());
			// Bundle packs
			if (comp.getName().endsWith(" pack"))
			{
				return;
			}
			// So many coin pouches!
			else if (comp.getName().equals("Coin pouch"))
			{
				return;
			}
		}
		else if (!event.getOption().equals("Loot"))
		{
			return;
		}

		if (client.getTickCount() - tick >= config.ticksPerItem())
		{
			tick = client.getTickCount();
		}
		else
		{
			event.consume();
			if (config.showMessage())
			{
				chatMessageManager.queue(QueuedMessage
					.builder()
					.value("Woah there buddy, slow down!")
					.type(ChatMessageType.ENGINE)
					.build()
				);
			}
		}
	}
}
