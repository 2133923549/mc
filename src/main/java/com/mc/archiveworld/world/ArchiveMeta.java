package com.mc.archiveworld.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ArchiveMeta {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int version = 1;
    public String worldName = "Archive World";
    public String generatorType = "FlatLevelSource";
    public long createdAt;
    public long lastAccessedAt;

    public static ArchiveMeta loadOrCreate() {
        var path = getPath();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                return GSON.fromJson(r, ArchiveMeta.class);
            } catch (IOException e) {
                LOGGER.warn("[ArchiveMeta] Failed to read meta.json, creating new");
            }
        }
        var meta = new ArchiveMeta();
        meta.createdAt = System.currentTimeMillis();
        meta.lastAccessedAt = meta.createdAt;
        meta.save();
        return meta;
    }

    public void touch() {
        lastAccessedAt = System.currentTimeMillis();
        save();
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(getPath())) {
            GSON.toJson(this, w);
        } catch (IOException e) {
            LOGGER.error("[ArchiveMeta] Failed to save meta.json", e);
        }
    }

    private static Path getPath() {
        return ArchiveWorldStorage.getWorldPath().resolve("meta.json");
    }
}
