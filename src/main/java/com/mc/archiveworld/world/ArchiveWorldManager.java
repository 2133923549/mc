package com.mc.archiveworld.world;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public class ArchiveWorldManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, ReturnPosition> RETURN_POSITIONS = new HashMap<>();

    public static void enterArchiveWorld(ServerPlayer player) {
        var server = player.getServer();
        if (server == null) {
            return;
        }

        if (player.level().dimension().equals(SHARED_ARCHIVE_KEY)) {
            returnToOriginalWorld(player);
            return;
        }

        var archiveLevel = loadSharedArchiveWorld(server);
        if (archiveLevel == null) {
            player.sendSystemMessage(Component.literal("Archive World is not available."));
            return;
        }

        RETURN_POSITIONS.put(player.getUUID(), new ReturnPosition(
                player.level().dimension(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()
        ));

        var spawnPos = findSafeSpawn(archiveLevel);

        player.teleportTo(
                archiveLevel,
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5,
                player.getYRot(),
                player.getXRot()
        );

        player.sendSystemMessage(Component.literal("Entered Archive World."));
        LOGGER.info("{} entered Archive World", player.getName().getString());
    }

    private static void configureWorldRules(ServerLevel archiveLevel, MinecraftServer server) {
        archiveLevel.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        archiveLevel.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        archiveLevel.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
    }

    private static BlockPos findSafeSpawn(ServerLevel level) {
        level.getChunk(0, 0);

        var surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);

        if (surfaceY > level.getMinBuildHeight() + 1) {
            return new BlockPos(0, surfaceY + 1, 0);
        }

        LOGGER.warn("Heightmap returned invalid Y={} at (0,0), using fallback position", surfaceY);
        return new BlockPos(0, 70, 0);
    }

    private static void returnToOriginalWorld(ServerPlayer player) {
        var pos = RETURN_POSITIONS.remove(player.getUUID());

        if (pos == null) {
            var overworld = player.getServer().overworld();
            var spawnPos = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    player.getYRot(), player.getXRot());
            player.sendSystemMessage(Component.literal("Returned to Overworld."));
            return;
        }

        var targetLevel = player.getServer().getLevel(pos.dimension);
        if (targetLevel == null) {
            player.sendSystemMessage(Component.literal("Could not find return destination."));
            return;
        }

        player.teleportTo(targetLevel, pos.x, pos.y, pos.z, pos.yRot, pos.xRot);
        player.sendSystemMessage(Component.literal("Returned to previous location."));
        LOGGER.info("{} returned from Archive World", player.getName().getString());
    }

    private record ReturnPosition(
            ResourceKey<Level> dimension,
            double x, double y, double z,
            float yRot, float xRot
    ) {}

    public static final ResourceKey<Level> SHARED_ARCHIVE_KEY = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("archiveworld", "archive_shared")
    );

    public static ServerLevel loadSharedArchiveWorld(MinecraftServer server) {
        try {
            var alreadyLoaded = server.getLevel(SHARED_ARCHIVE_KEY);
            if (alreadyLoaded != null) {
                LOGGER.info("Shared Archive World already loaded");
                return alreadyLoaded;
            }

            var storageAccess = ArchiveWorldStorage.createAccess();
            var worldPath = ArchiveWorldStorage.getWorldPath();
            var isNew = !ArchiveWorldStorage.exists();

            var levelSettings = new LevelSettings(
                    "Archive World", GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(),
                    WorldDataConfiguration.DEFAULT
            );
            var levelData = new PrimaryLevelData(
                    levelSettings,
                    new WorldOptions(0L, true, false),
                    PrimaryLevelData.SpecialWorldProperty.NONE,
                    Lifecycle.stable()
            );

            if (isNew) {
                LOGGER.info("Initializing new shared Archive World at {}", worldPath);
            } else {
                LOGGER.info("Loading existing shared Archive World from {}", worldPath);
            }

            var overworld = server.overworld();
            var stem = new LevelStem(
                    overworld.dimensionTypeRegistration(),
                    overworld.getChunkSource().getGenerator()
            );

            var randomSequences = new RandomSequences(0L);
            var executor = Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "Archive-World-Worker");
                t.setDaemon(true);
                return t;
            });

            var archiveLevel = new ServerLevel(
                    server, executor, storageAccess, levelData, SHARED_ARCHIVE_KEY, stem,
                    new LoggerChunkProgressListener(0),
                    false, 0L, List.of(), false, randomSequences
            );

            server.levels.put(SHARED_ARCHIVE_KEY, archiveLevel);
            configureWorldRules(archiveLevel, server);
            MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(archiveLevel));

            if (isNew) {
                storageAccess.saveDataTag(server.registryAccess(), levelData);
                LOGGER.info("Shared Archive World initialized and saved to {}", worldPath);
            }

            LOGGER.info("Shared Archive World loaded. Path: {}", worldPath);
            return archiveLevel;

        } catch (Exception e) {
            LOGGER.error("Failed to load shared Archive World", e);
            return null;
        }
    }
}
