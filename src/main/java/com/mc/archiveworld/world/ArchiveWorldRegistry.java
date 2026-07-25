package com.mc.archiveworld.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ArchiveWorldRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, ArchiveWorldInfo> worlds = new ConcurrentHashMap<>();
    private UUID defaultWorldId;
    private Path registryFile;

    private static class RegistryData {
        String defaultWorldId;
        List<ArchiveWorldInfo> worlds = new ArrayList<>();
    }

    // --- init ---

    public void initialize() {
        var basePath = FMLPaths.GAMEDIR.get().resolve("archive_worlds");
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            LOGGER.error("Failed to create archive_worlds directory", e);
            return;
        }
        this.registryFile = basePath.resolve("registry.json");
        loadRegistry();
        validateEntries();
        checkOrphanDirs(basePath);

        if (worlds.isEmpty()) {
            var legacyPath = FMLPaths.GAMEDIR.get().resolve("archive_world").resolve("level.dat");
            if (Files.exists(legacyPath)) {
                var id = UUID.randomUUID();
                var info = new ArchiveWorldInfo(id, "Default World");
                info.setStoragePath("archive_world");
                worlds.put(id, info);
                defaultWorldId = id;
                saveRegistry();
                LOGGER.info("[ArchiveWorldRegistry] Migrated legacy archive_world as {}", id);
            } else {
                createWorld("Default World");
                LOGGER.info("[ArchiveWorldRegistry] Created default world");
            }
        } else {
            LOGGER.info("[ArchiveWorldRegistry] Using registered world: {} worlds", worlds.size());
        }
    }

    private void validateEntries() {
        for (var entry : worlds.entrySet()) {
            var info = entry.getValue();
            if (info.isDeleted()) continue;
            if (info.getStoragePath() != null) continue; // legacy path
            if (!ArchiveWorldStorage.exists(info)) {
                LOGGER.warn("[ArchiveWorldRegistry] Invalid world entry: uuid={} name={} - directory missing",
                        info.getId(), info.getName());
                info.setDeleted(true);
            }
        }
    }

    private void checkOrphanDirs(Path basePath) {
        var worldsDir = basePath.resolve("worlds");
        if (!Files.exists(worldsDir)) return;
        try (Stream<Path> dirs = Files.list(worldsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                var dirName = dir.getFileName().toString();
                try {
                    UUID.fromString(dirName);
                } catch (IllegalArgumentException e) {
                    return;
                }
                boolean found = worlds.keySet().stream()
                        .anyMatch(id -> dirName.equals(id.toString()));
                if (!found) {
                    LOGGER.warn("[ArchiveWorldRegistry] Orphan world directory: {}", dir);
                }
            });
        } catch (IOException ignored) {}
    }

    // --- persist ---

    public void saveRegistry() {
        if (registryFile == null) return;
        try (Writer writer = Files.newBufferedWriter(registryFile)) {
            var data = new RegistryData();
            data.defaultWorldId = defaultWorldId != null ? defaultWorldId.toString() : null;
            data.worlds = new ArrayList<>(worlds.values());
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save registry.json", e);
        }
    }

    private void loadRegistry() {
        if (registryFile == null || !Files.exists(registryFile)) {
            LOGGER.info("No registry.json found, starting with empty registry");
            return;
        }
        try (Reader reader = Files.newBufferedReader(registryFile)) {
            var data = GSON.fromJson(reader, RegistryData.class);
            if (data.defaultWorldId != null) {
                defaultWorldId = UUID.fromString(data.defaultWorldId);
            }
            if (data.worlds != null) {
                for (var info : data.worlds) {
                    worlds.put(info.getId(), info);
                }
            }
            LOGGER.info("Loaded {} worlds from registry", worlds.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load registry.json, data preserved on disk", e);
        }
    }

    // --- CRUD ---

    public ArchiveWorldInfo createWorld(String name) {
        var id = UUID.randomUUID();
        var info = new ArchiveWorldInfo(id, name);
        worlds.put(id, info);
        if (defaultWorldId == null) {
            defaultWorldId = id;
        }
        saveRegistry();
        LOGGER.info("[ArchiveWorldRegistry] Created world: name={} uuid={}", name, id);
        return info;
    }

    public ArchiveWorldInfo getWorld(UUID id) {
        return worlds.get(id);
    }

    public List<ArchiveWorldInfo> listWorlds() {
        return worlds.values().stream()
                .filter(info -> !info.isDeleted())
                .sorted(Comparator.comparingLong(ArchiveWorldInfo::getCreatedAt))
                .toList();
    }

    public boolean deleteWorld(UUID id) {
        var info = worlds.get(id);
        if (info == null) return false;

        if (id.equals(defaultWorldId)) {
            LOGGER.warn("[ArchiveWorldRegistry] Cannot delete default world: uuid={}", id);
            return false;
        }

        ArchiveWorldManager.unloadWorld(id);

        if (info.getStoragePath() == null) {
            var worldPath = ArchiveWorldStorage.getWorldPath(id);
            try {
                if (Files.exists(worldPath)) {
                    try (Stream<Path> walk = Files.walk(worldPath)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                    }
                }
            } catch (IOException e) {
                LOGGER.error("[ArchiveWorldRegistry] Failed to delete world directory: {}", worldPath, e);
            }
        }

        worlds.remove(id);
        saveRegistry();
        LOGGER.info("[ArchiveWorldRegistry] Deleted world: uuid={}", id);
        return true;
    }

    public void setDefaultWorld(UUID id) {
        if (worlds.containsKey(id)) {
            defaultWorldId = id;
            saveRegistry();
        }
    }

    public ArchiveWorldInfo getDefaultWorld() {
        if (defaultWorldId == null) return null;
        var info = worlds.get(defaultWorldId);
        if (info != null && info.isDeleted()) return null;
        return info;
    }
}
