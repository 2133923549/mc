package com.mc.archiveworld.world;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public class ArchiveWorldManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final ResourceKey<Level> ARCHIVE_KEY = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("archiveworld", "archive_shared"));

    private static final Map<UUID, Position> returnDestinations = new HashMap<>();
    private static final Map<UUID, Position> lastArchivePositions = new HashMap<>();

    // --- public API ---

    public static void onServerStopping() {
        returnDestinations.clear();
        lastArchivePositions.clear();
        LOGGER.info("[ArchiveWorld] Server stopping, cleared all positions");
    }

    public static ServerLevel loadSharedArchiveWorld(MinecraftServer server) {
        try {
            var existing = server.getLevel(ARCHIVE_KEY);
            if (existing != null) {
                LOGGER.info("[ArchiveWorld] Already loaded");
                return existing;
            }

            var access = ArchiveWorldStorage.createAccess();
            var isNew = !ArchiveWorldStorage.exists();

            var settings = new LevelSettings("Archive World", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            var levelData = new PrimaryLevelData(settings,
                    net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                    PrimaryLevelData.SpecialWorldProperty.FLAT, Lifecycle.stable());

            var dimType = server.overworld().dimensionTypeRegistration();
            var reg = server.registryAccess();

            var biomeReg = reg.registryOrThrow(Registries.BIOME);
            var flatSettings = FlatLevelGeneratorSettings.getDefault(
                    biomeReg.asLookup(),
                    reg.registryOrThrow(Registries.STRUCTURE_SET).asLookup(),
                    reg.registryOrThrow(Registries.PLACED_FEATURE).asLookup());
            var generator = new FlatLevelSource(flatSettings);
            var stem = new LevelStem(dimType, generator);

            var executor = Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "Archive-World-Worker");
                t.setDaemon(true);
                return t;
            });

            var level = new ServerLevel(server, executor, access, levelData, ARCHIVE_KEY, stem,
                    new LoggerChunkProgressListener(0), false, 0L,
                    java.util.List.of(), false, new net.minecraft.world.RandomSequences(0L));

            server.levels.put(ARCHIVE_KEY, level);
            configureArchiveRules(level, server);
            MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(level));

            var meta = ArchiveMeta.loadOrCreate();
            if (isNew) {
                access.saveDataTag(server.registryAccess(), levelData);
                LOGGER.info("[ArchiveWorld] Created {} (createdAt={})", access.getWorldDir(), meta.createdAt);
            } else {
                meta.touch();
                LOGGER.info("[ArchiveWorld] Loaded {} (lastAccess={})", access.getWorldDir(), meta.lastAccessedAt);
            }
            return level;
        } catch (Exception e) {
            LOGGER.error("[ArchiveWorld] Failed to load", e);
            return null;
        }
    }

    public static void enterArchiveWorld(ServerPlayer player) {
        var server = player.getServer();
        if (server == null) return;

        if (player.level().dimension().equals(ARCHIVE_KEY)) {
            returnToOriginalWorld(player);
            return;
        }

        var level = loadSharedArchiveWorld(server);
        if (level == null) {
            player.sendSystemMessage(Component.literal("Archive World unavailable."));
            return;
        }

        returnDestinations.put(player.getUUID(), new Position(
                player.level().dimension(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));

        var lastPos = lastArchivePositions.get(player.getUUID());
        double dx, dy, dz;
        float dyaw, dpitch;
        if (lastPos != null) {
            dx = lastPos.x; dy = lastPos.y; dz = lastPos.z;
            dyaw = lastPos.yRot; dpitch = lastPos.xRot;
        } else {
            dx = 0.5; dy = level.getMinBuildHeight() + 5; dz = 0.5;
            dyaw = player.getYRot(); dpitch = player.getXRot();
        }

        player.teleportTo(level, dx, dy, dz, dyaw, dpitch);
        player.sendSystemMessage(Component.literal("Entering Archive World."));
        LOGGER.info("{} entered Archive World", player.getName().getString());
    }

    // --- internals ---

    private static void configureArchiveRules(ServerLevel level, MinecraftServer server) {
        var rules = level.getGameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_MOBGRIEFING).set(false, server);
        level.setDayTime(6000);
    }

    private static void returnToOriginalWorld(ServerPlayer player) {
        var pos = returnDestinations.remove(player.getUUID());
        lastArchivePositions.put(player.getUUID(), new Position(
                ARCHIVE_KEY,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()));

        if (pos == null) {
            var overworld = player.getServer().overworld();
            var spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
            player.sendSystemMessage(Component.literal("Returning to Overworld."));
            return;
        }
        var target = player.getServer().getLevel(pos.dimension);
        if (target == null) {
            player.sendSystemMessage(Component.literal("Could not find return destination."));
            return;
        }
        player.teleportTo(target, pos.x, pos.y, pos.z, pos.yRot, pos.xRot);
        player.sendSystemMessage(Component.literal("Returned to previous location."));
        LOGGER.info("{} returned from Archive World", player.getName().getString());
    }

    private record Position(ResourceKey<Level> dimension, double x, double y, double z,
                            float yRot, float xRot) {}
}
