package net.machpi.runelite.influxdb;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.inject.Inject;
import net.machpi.runelite.influxdb.activity.ActivityState;
import net.machpi.runelite.influxdb.activity.GameEvent;
import net.machpi.runelite.influxdb.write.Measurement;
import net.machpi.runelite.influxdb.write.Series;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Experience;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemDefinition;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

import javax.inject.Singleton;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Singleton
public class MeasurementCreator {
    public static final String SERIES_INVENTORY = "rs_inventory";
    public static final String SERIES_SKILL = "rs_skill";
    public static final String SERIES_SELF = "rs_self";
    public static final String SERIES_KILL_COUNT = "rs_killcount";
    public static final String SERIES_SELF_LOC = "rs_self_loc";
    public static final String SERIES_ACTIVITY = "rs_activity";
    public static final String SERIES_LOOT = "rs_loot";
    public static final String SELF_KEY_X = "locX";
    public static final String SELF_KEY_Y = "locY";
    public static final Set<String> SELF_POS_KEYS = ImmutableSet.of(SELF_KEY_X, SELF_KEY_Y);

    private final Client client;
    private final ItemManager itemManager;
    private final ConfigManager configManager;

    @Inject
    public MeasurementCreator(Client client, ItemManager itemManager, ConfigManager configManager) {
        this.client = client;
        this.itemManager = itemManager;
        this.configManager = configManager;
    }

    public boolean isInLastManStanding() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return false;
        }
        final int regionId = WorldPoint.fromLocalInstance(client, localPlayer.getLocalLocation()).getRegionID();
        return GameEvent.MG_LAST_MAN_STANDING.equals(GameEvent.fromRegion(regionId));
    }

    private Series.SeriesBuilder createSeries() {
        Series.SeriesBuilder builder = Series.builder();
        builder.tag("user", client.getUsername());
        builder.tags(WorldTags.tagsForWorld(client.getWorldType()));
        return builder;
    }

    public Series createXpSeries(Skill skill) {
        return createSeries().measurement(SERIES_SKILL).tag("skill", skill.name()).build();
    }

    public Optional<Measurement> createXpMeasurement(Skill skill) {
        long xp = skill == Skill.OVERALL ? client.getOverallExperience() : client.getSkillExperience(skill);
        if (xp == 0) {
            return Optional.empty();
        }
        int virtualLevel;
        int realLevel;
        if (skill == Skill.OVERALL) {
            virtualLevel = Arrays.stream(Skill.values())
                    .filter(x -> x != Skill.OVERALL)
                    .mapToInt(x -> Experience.getLevelForXp(client.getSkillExperience(x)))
                    .sum();
            realLevel = client.getTotalLevel();
        } else {
            virtualLevel = Experience.getLevelForXp((int) xp);
            realLevel = client.getRealSkillLevel(skill);
        }
        return Optional.of(Measurement.builder()
                .series(createXpSeries(skill))
                .numericValue("xp", xp)
                .numericValue("realLevel", realLevel)
                .numericValue("virtualLevel", virtualLevel)
                .build());
    }

    public Series createItemSeries(InventoryID inventory, InvValueType type) {
        return createSeries().measurement(SERIES_INVENTORY)
                .tag("inventory", inventory.name())
                .tag("type", type.name())
                .build();
    }

    private static String itemToKey(ItemDefinition composition) {
        return composition.getName() + "@" + composition.getId();
    }

    private static void addToMap(Map<String, Long> map, String key, long value) {
        map.compute(key, (_key, oldValue) -> (oldValue != null ? oldValue : 0) + value);
    }

    public Stream<Measurement> createItemMeasurements(InventoryID inventoryID, Item[] items) {
        Map<String, Long> gePrice = new HashMap<>(items.length / 2);
        Map<String, Long> haPrice = new HashMap<>(items.length / 2);
        Map<String, Long> count = new HashMap<>(items.length / 2);

        long totalGe = 0, totalAlch = 0;
        long otherGe = 0, otherAlch = 0;
        for (Item item : items) {
            long ge, alch;
            if (item.getId() < 0 || item.getQuantity() <= 0 || item.getId() == ItemID.BANK_FILLER)
                continue;
            int canonId = itemManager.canonicalize(item.getId());
            ItemDefinition data = itemManager.getItemDefinition(canonId);
            switch (canonId) {
                case ItemID.COINS_995:
                    ge = item.getQuantity();
                    alch = item.getQuantity();
                    break;
                case ItemID.PLATINUM_TOKEN:
                    ge = item.getQuantity() * 1000L;
                    alch = item.getQuantity() * 1000L;
                    break;
                default:
                    final long storePrice = data.getPrice();
                    final long alchPrice = (long) (storePrice * Constants.HIGH_ALCHEMY_MULTIPLIER);
                    alch = alchPrice * item.getQuantity();
                    ge = (long) itemManager.getItemPrice(canonId) * item.getQuantity();
                    break;
            }
            totalGe += ge;
            totalAlch += alch;
            boolean highValue = ge > THRESHOLD || alch > THRESHOLD;
            if (highValue) {
                String key = itemToKey(data);
                addToMap(gePrice, key, ge);
                addToMap(haPrice, key, alch);
                addToMap(count, key, item.getQuantity());
            } else {
                otherGe += ge;
                otherAlch += alch;
            }
        }


        return Stream.of(Measurement.builder().series(createItemSeries(inventoryID, InvValueType.GE))
                        .numericValues(gePrice)
                        .numericValue("total", totalGe)
                        .numericValue("other", otherGe)
                        .build(),
                Measurement.builder().series(createItemSeries(inventoryID, InvValueType.HA))
                        .numericValues(haPrice)
                        .numericValue("total", totalAlch)
                        .numericValue("other", otherAlch)
                        .build(),
                Measurement.builder().series(createItemSeries(inventoryID, InvValueType.COUNT))
                        .numericValues(count)
                        .build());
    }

    public Series createSelfLocSeries() {
        return createSeries().measurement(SERIES_SELF_LOC).build();
    }

    public Measurement createSelfLocMeasurement() {
        Player local = client.getLocalPlayer();
        WorldPoint location = WorldPoint.fromLocalInstance(client, local.getLocalLocation());
        return Measurement.builder()
                .series(createSelfLocSeries())
                .numericValue(SELF_KEY_X, location.getX())
                .numericValue(SELF_KEY_Y, location.getY())
                .numericValue("plane", location.getPlane())
                .numericValue("instance", client.isInInstancedRegion() ? 1 : 0)
                .build();
    }

    public Series createSelfSeries() {
        return createSeries().measurement(SERIES_SELF).build();
    }

    private static final int VARBIT_LEAGUE_TASKS = 10046;
    private static final int VARP_LEAGUE_POINTS = 2614;

    public Measurement createSelfMeasurement() {
        Player local = client.getLocalPlayer();
        Measurement.MeasurementBuilder builder = Measurement.builder()
                .series(createSelfSeries())
                .numericValue("combat", Experience.getCombatLevelPrecise(
                        client.getRealSkillLevel(Skill.ATTACK),
                        client.getRealSkillLevel(Skill.STRENGTH),
                        client.getRealSkillLevel(Skill.DEFENCE),
                        client.getRealSkillLevel(Skill.HITPOINTS),
                        client.getRealSkillLevel(Skill.MAGIC),
                        client.getRealSkillLevel(Skill.RANGED),
                        client.getRealSkillLevel(Skill.PRAYER)
                ))
                .numericValue("questPoints", client.getVar(VarPlayer.QUEST_POINTS))
                .numericValue("skulled", local.getSkullIcon() != null ? 1 : 0)
                .stringValue("name", MoreObjects.firstNonNull(local.getName(), "none"))
                .stringValue("overhead", local.getOverheadIcon() != null ? local.getOverheadIcon().name() : "NONE");
        if (client.getWorldType().contains(WorldType.LEAGUE)) {
            int tasksComplete = client.getVarbitValue(VARBIT_LEAGUE_TASKS);
            int leaguePoints = client.getVarpValue(VARP_LEAGUE_POINTS);
            builder.numericValue("leagueTasksComplete", tasksComplete)
                    .numericValue("leaguePoints", leaguePoints);
        }
        return builder.build();
    }

    public Series createKillCountSeries(String boss) {
        return createSeries().measurement(SERIES_KILL_COUNT)
                .tag("boss", boss).build();
    }

    static final String KILL_COUNT_CFG_PREFIX = "killcount.";
    static final String PERSONAL_BEST_CFG_PREFIX = "personalbest.";

    public Optional<Measurement> createKillCountMeasurement(String bossMixed) {
        // Piggyback off of chat commands plugin
        String user = client.getUsername().toLowerCase();
        String boss = bossMixed.toLowerCase();
        Integer killCount = configManager.getConfiguration(KILL_COUNT_CFG_PREFIX + user,
                boss, int.class);
        if (killCount == null)
            return Optional.empty();
        Integer personalBest = configManager.getConfiguration(PERSONAL_BEST_CFG_PREFIX + user,
                boss, int.class);
        Measurement.MeasurementBuilder measurement = Measurement.builder()
                .series(createKillCountSeries(boss))
                .numericValue("kc", killCount);
        if (personalBest != null) {
            measurement.numericValue("pb", personalBest);
        }
        return Optional.of(measurement.build());
    }

    enum InvValueType {
        GE,
        HA,
        COUNT
    }

    private static final int THRESHOLD = 50_000;

    public Optional<Series> createActivitySeries() {
        Series series = createSeries().measurement(SERIES_ACTIVITY).build();
        if (Strings.isNullOrEmpty(series.getTags().getOrDefault("user", null)))
            return Optional.empty();
        return Optional.of(series);
    }

    public Optional<Measurement> createActivityMeasurement(ActivityState.State lastState) {
        Optional<Series> series = createActivitySeries();
        if (!series.isPresent()) {
            return Optional.empty();
        }
        Measurement.MeasurementBuilder mb = Measurement.builder().series(series.get());
        if (!Strings.isNullOrEmpty(lastState.getSkill())) {
            mb.stringValue("skill", lastState.getSkill());
        }
        if (!Strings.isNullOrEmpty(lastState.getLocationType())) {
            mb.stringValue("type", lastState.getLocationType());
        }
        if (!Strings.isNullOrEmpty(lastState.getLocation())) {
            mb.stringValue("location", lastState.getLocation());
        }
        Measurement measure = mb.build();
        if (measure.getStringValues().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(measure);
    }

    public Series createLootSeries(LootRecordType type, String source, int combatLevel) {
        return createSeries()
                .measurement(SERIES_LOOT)
                .tag("type", type.name())
                .tag("source", source)
                .tag("combat", Integer.toString(combatLevel))
                .build();
    }

    public Optional<Measurement> createLootMeasurement(LootReceived event) {
        Measurement.MeasurementBuilder measurement = Measurement.builder().series(createLootSeries(event.getType(), event.getName(), event.getCombatLevel()));
        Optional<WorldPoint> worldPoint = event.getItems().stream()
                .filter(Objects::nonNull)
                .map(stack -> WorldPoint.fromLocalInstance(client, stack.getLocation()))
                .findAny();
        if (!worldPoint.isPresent()) {
            return Optional.empty();
        }

        WorldPoint location = worldPoint.get();
        measurement.numericValue(SELF_KEY_X, location.getX())
                .numericValue(SELF_KEY_Y, location.getY())
                .numericValue("plane", location.getPlane())
                .numericValue("killcount", 1);

        Multiset<String> counts = HashMultiset.create(event.getItems().size());
        for (ItemStack stack : event.getItems()) {
            if (stack.getQuantity() <= 0) {
                continue;
            }
            int canonId = itemManager.canonicalize(stack.getId());
            ItemDefinition data = itemManager.getItemDefinition(canonId);
            counts.add(itemToKey(data), stack.getQuantity());
        }
        if (counts.isEmpty()) {
            return Optional.empty();
        }
        for (Multiset.Entry<String> count : counts.entrySet()) {
            measurement.numericValue(count.getElement(), count.getCount());
        }
        return Optional.of(measurement.build());
    }
}
