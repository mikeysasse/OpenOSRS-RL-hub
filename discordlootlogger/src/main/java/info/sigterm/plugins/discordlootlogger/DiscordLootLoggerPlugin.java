package info.sigterm.plugins.discordlootlogger;

import com.google.common.base.Strings;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemDefinition;
import net.runelite.api.NPC;
import net.runelite.api.util.Text;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageCapture;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.WildcardMatcher;
import static net.runelite.http.api.RuneLiteAPI.GSON;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Discord Loot Logger",
	description = "Logs loot received to a Discord Webhook",
	enabledByDefault = false,
	type = PluginType.MISCELLANEOUS
)
@Slf4j
public class DiscordLootLoggerPlugin extends Plugin
{
	@Inject
	private DiscordLootLoggerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ImageCapture imageCapture;

	@Inject
	private DrawManager drawManager;

	private List<String> lootNpcs;

	private static String itemImageUrl(int itemId)
	{
		return "https://static.runelite.net/cache/item/icon/" + itemId + ".png";
	}

	@Override
	protected void startUp()
	{
		lootNpcs = Collections.emptyList();
	}

	@Override
	protected void shutDown()
	{
	}

	@Provides
	DiscordLootLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DiscordLootLoggerConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equalsIgnoreCase(DiscordLootLoggerConfig.GROUP))
		{
			String s = config.lootNpcs();
			lootNpcs = s != null ? Text.fromCSV(s) : Collections.emptyList();
		}
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived npcLootReceived)
	{
		NPC npc = npcLootReceived.getNpc();
		Collection<ItemStack> items = npcLootReceived.getItems();

		if (!lootNpcs.isEmpty())
		{
			for (String npcName : lootNpcs)
			{
				if (WildcardMatcher.matches(npcName, npc.getName()))
				{
					processLoot(npc.getName(), items);
					return;
				}
			}
		}
		else
		{
			processLoot(npc.getName(), items);
		}
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived playerLootReceived)
	{
		Collection<ItemStack> items = playerLootReceived.getItems();
		processLoot(playerLootReceived.getPlayer().getName(), items);
	}

	@Subscribe
	public void onLootReceived(LootReceived lootReceived)
	{
		if (lootReceived.getType() != LootRecordType.EVENT && lootReceived.getType() != LootRecordType.PICKPOCKET)
		{
			return;
		}

		processLoot(lootReceived.getName(), lootReceived.getItems());
	}

	private void processLoot(String name, Collection<ItemStack> items)
	{
		WebhookBody webhookBody = new WebhookBody();

		long totalValue = 0;
		StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("**").append(name).append("**").append(":\n");
		final int targetValue = config.lootValue();
		for (ItemStack item : stack(items))
		{
			int itemId = item.getId();
			int qty = item.getQuantity();

			int price = itemManager.getItemPrice(itemId);
			long total = (long) price * qty;

			totalValue += total;

			if (config.includeLowValueItems() || total >= targetValue)
			{
				ItemDefinition itemComposition = itemManager.getItemDefinition(itemId);
				stringBuilder.append(qty).append(" x ").append(itemComposition.getName());
				if (config.stackValue())
				{
					stringBuilder.append(" (").append(QuantityFormatter.quantityToStackSize(total)).append(")").append("\n");
				}
				stringBuilder.append("\n");
				webhookBody.getEmbeds().add(new WebhookBody.Embed(new WebhookBody.UrlEmbed(itemImageUrl(itemId))));
			}
		}

		if (targetValue == 0 || totalValue >= targetValue)
		{
			webhookBody.setContent(stringBuilder.toString());
			sendWebhook(webhookBody);
		}
	}

	private void sendWebhook(WebhookBody webhookBody)
	{
		String configUrl = config.webhook();
		if (Strings.isNullOrEmpty(configUrl))
		{
			return;
		}

		HttpUrl url = HttpUrl.parse(configUrl);
		MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("payload_json", GSON.toJson(webhookBody));

		if (config.sendScreenshot())
		{
			sendWebhookWithScreenshot(url, requestBodyBuilder);
		}
		else
		{
			buildRequestAndSend(url, requestBodyBuilder);
		}
	}

	private void sendWebhookWithScreenshot(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		drawManager.requestNextFrameListener(image ->
		{
			BufferedImage bufferedImage = (BufferedImage) image;
			byte[] imageBytes;
			try
			{
				imageBytes = convertImageToByteArray(bufferedImage);
			}
			catch (IOException e)
			{
				log.warn("Error converting image to byte array", e);
				return;
			}

			requestBodyBuilder.addFormDataPart("file", "image.png",
				RequestBody.create(MediaType.parse("image/png"), imageBytes));
			buildRequestAndSend(url, requestBodyBuilder);
		});
	}

	private void buildRequestAndSend(HttpUrl url, MultipartBody.Builder requestBodyBuilder)
	{
		RequestBody requestBody = requestBodyBuilder.build();
		Request request = new Request.Builder()
			.url(url)
			.post(requestBody)
			.build();
		sendRequest(request);
	}

	private void sendRequest(Request request)
	{
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Error submitting webhook", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				response.close();
			}
		});
	}

	private static byte[] convertImageToByteArray(BufferedImage bufferedImage) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
		return byteArrayOutputStream.toByteArray();
	}

	private static Collection<ItemStack> stack(Collection<ItemStack> items)
	{
		final List<ItemStack> list = new ArrayList<>();

		for (final ItemStack item : items)
		{
			int quantity = 0;
			for (final ItemStack i : list)
			{
				if (i.getId() == item.getId())
				{
					quantity = i.getQuantity();
					list.remove(i);
					break;
				}
			}
			if (quantity > 0)
			{
				list.add(new ItemStack(item.getId(), item.getQuantity() + quantity, item.getLocation()));
			}
			else
			{
				list.add(item);
			}
		}

		return list;
	}
}
