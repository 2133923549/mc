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
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class ArchiveWorldManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    static ArchiveWorldRegistry registry;
    private static final Map<UUID, ArchiveWorldRuntime> runtimes = new ConcurrentHashMap<>();
    private static final Map<UUID, ReturnPosition> returnPositions = new HashMap<>();

    public static void setRegistry(ArchiveWorldRegistry reg) {
        registry = reg;
    }

    public static void onServerStopping() {
        LOGGER.info("[ArchiveWorldDebug] Server stopping, clearing {} loaded runtimes", runtimes.size());
        runtimes.clear();
        returnPositions.clear();
    }

    public static void unloadWorld(UUID id) {
        var runtime = runtimes.remove(id);
        if (runtime != null && runtime.getLevel() != null) {
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.levels.remove(runtime.getDimensionKey());
            }
            LOGGER.info("[ArchiveWorldDebug] Unloaded world: uuid={}", id);
        }
        returnPositions.clear();
    }

    static final ResourceKey<Level> LEGACY_DIM_KEY = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("archiveworld", "archive_shared"));

    static ResourceKey<Level> createDimensionKey(ArchiveWorldInfo info) {
        if (info.getStoragePath() != null) {
            return LEGACY_DIM_KEY;
        }
        var hex = info.getId().toString().replace("-", "").substring(0, 12);
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("archiveworld", "world_" + hex));
    }

    public static void enterArchiveWorld(ServerPlayer player) {
        var server = player.getServer();
        if (server == null || registry == null) return;

        var info = registry.getDefaultWorld();
        if (info == null) {
            player.sendSystemMessage(Component.literal("No Archive World available."));
            return;
        }

        var dimKey = createDimensionKey(info);
        if (player.level().dimension().equals(dimKey)) {
            returnToOriginalWorld(player);
            return;
        }

        var archiveLevel = loadWorld(server, info);
        if (archiveLevel == null) {
            player.sendSystemMessage(Component.literal("Archive World is not available."));
            return;
        }

        returnPositions.put(player.getUUID(), new ReturnPosition(
                player.level().dimension(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot()
        ));

        var spawnPos = findSafeSpawn(archiveLevel);
        player.teleportTo(archiveLevel,
                spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                player.getYRot(), player.getXRot());

        player.sendSystemMessage(Component.literal("Entered Archive World."));
        LOGGER.info("{} entered Archive World '{}'", player.getName().getString(), info.getName());
    }

    private static ServerLevel loadWorld(MinecraftServer server, ArchiveWorldInfo info) {
        try {
            var dimKey = createDimensionKey(info);

            var existingRuntime = runtimes.get(info.getId());
            LOGGER.info("[ArchiveWorldDebug] World id={} server={} existingRuntime={}",
                    info.getId(),
                    System.identityHashCode(server),
                    existingRuntime != null ? System.identityHashCode(existingRuntime.getLevel()) : null);

            var alreadyLoaded = server.getLevel(dimKey);
            if (alreadyLoaded != null) {
                LOGGER.info("[ArchiveWorldDebug] Reusing already-loaded level {}", System.identityHashCode(alreadyLoaded));
                runtimes.put(info.getId(), new ArchiveWorldRuntime(info.getId(), dimKey, alreadyLoaded));
                return alreadyLoaded;
            }

            var storageAccess = ArchiveWorldStorage.createAccess(info);
            var isNew = !ArchiveWorldStorage.exists(info);

            PrimaryLevelData levelData;
            LevelStem stem;

            long worldSeed;
            if (isNew) {
                worldSeed = WorldOptions.randomSeed();
                LOGGER.info("[ArchiveWorldDiagnostic] CREATE seed={}", worldSeed);
            } else {
                var levelFile = ArchiveWorldStorage.getWorldDir(info).resolve("level.dat").toFile();
                var rootTag = net.minecraft.nbt.NbtIo.readCompressed(levelFile);
                var dataTag = rootTag.getCompound("Data");
                var worldGenTag = dataTag.getCompound("WorldGenSettings");
                worldSeed = worldGenTag.getLong("seed");
                LOGGER.info("[ArchiveWorldDiagnostic] LOAD seed={} from level.dat", worldSeed);

                try {
                    var ops = net.minecraft.nbt.NbtOps.INSTANCE;
                    var stemReg = server.registries().compositeAccess()
                            .registryOrThrow(Registries.LEVEL_STEM);
                    var result = storageAccess.getDataTag(ops,
                            WorldDataConfiguration.DEFAULT, stemReg, Lifecycle.stable());
                    var loadedDims = result.getSecond().dimensions();
                    var stemKey = ResourceKey.create(Registries.LEVEL_STEM, dimKey.location());
                    var loadedStem = loadedDims.get(stemKey);
                    LOGGER.info("[ArchiveWorldDiagnostic] getDataTag OK loadedStem={}", loadedStem != null);
                } catch (Exception e) {
                    LOGGER.error("[ArchiveWorldDiagnostic] getDataTag FAILED", e);
                }
            }

            var levelSettings = new LevelSettings(
                    info.getName(), GameType.CREATIVE, false,
                    Difficulty.PEACEFUL, true, new GameRules(),
                    WorldDataConfiguration.DEFAULT
            );
            levelData = new PrimaryLevelData(levelSettings,
                    new WorldOptions(worldSeed, true, false),
                    PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());

            var overworld = server.overworld();
            var noiseReg = server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
            var noiseSettings = noiseReg.getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
            var paramReg = server.registryAccess()
                    .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            var paramHolder = paramReg.getHolderOrThrow(ResourceKey.create(
                    Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                    ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
            ));
            var biomeSource = MultiNoiseBiomeSource.createFromPreset(paramHolder);
            var chunkGen = new NoiseBasedChunkGenerator(biomeSource, noiseSettings);
            stem = new LevelStem(overworld.dimensionTypeRegistration(), chunkGen);

            LOGGER.info("[ArchiveWorldDiagnostic] {} seed={} dimKey={} generator={} biomeSource={}",
                    isNew ? "CREATE" : "LOAD", worldSeed, dimKey.location(),
                    chunkGen.getClass().getSimpleName(), biomeSource.getClass().getSimpleName());

            var executor = Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "Archive-World-" + info.getId().toString().substring(0, 8));
                t.setDaemon(true);
                return t;
            });

            var level = new ServerLevel(server, executor, storageAccess, levelData, dimKey, stem,
                    new LoggerChunkProgressListener(0),
                    false, 0L, List.of(), false,
                    new RandomSequences(levelData.worldGenOptions().seed()));

            server.levels.put(dimKey, level);
            configureWorldRules(level, server);
            MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(level));

            var gen = level.getChunkSource().getGenerator();
            LOGGER.info("[ArchiveWorldDiagnostic] PostConstruct generatorId={} biomeSourceId={}",
                    System.identityHashCode(gen),
                    System.identityHashCode(gen.getBiomeSource()));
            if (gen instanceof NoiseBasedChunkGenerator noiseGen) {
                LOGGER.info("[ArchiveWorldDiagnostic] PostConstruct noiseSettingsId={}",
                        System.identityHashCode(noiseGen.generatorSettings().value()));
            }

            if (isNew) {
                storageAccess.saveDataTag(server.registryAccess(), levelData);
            }

            runtimes.put(info.getId(), new ArchiveWorldRuntime(info.getId(), dimKey, level));
            LOGGER.info("[ArchiveWorldDebug] level={} isNew={} generatorSeed={}",
                    System.identityHashCode(level), isNew, levelData.worldGenOptions().seed());
            return level;

        } catch (Exception e) {
            LOGGER.error("Failed to load Archive World '{}'", info.getName(), e);
            return null;
        }
    }

    private static void configureWorldRules(ServerLevel level, MinecraftServer server) {
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
    }

    private static BlockPos findSafeSpawn(ServerLevel level) {
        level.getChunk(0, 0);
        var surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        if (surfaceY > level.getMinBuildHeight() + 1) {
            return new BlockPos(0, surfaceY + 1, 0);
        }
        LOGGER.warn("Heightmap returned invalid Y={} at (0,0), using fallback", surfaceY);
        return new BlockPos(0, 70, 0);
    }

    private static void returnToOriginalWorld(ServerPlayer player) {
        var pos = returnPositions.remove(player.getUUID());
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
}
