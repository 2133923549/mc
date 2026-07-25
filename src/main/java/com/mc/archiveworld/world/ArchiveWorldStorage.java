package com.mc.archiveworld.world;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class ArchiveWorldStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD_NAME = "archive_world";

    public static Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path getWorldPath() {
        return getGameDir().resolve(WORLD_NAME);
    }

    public static Path getWorldPath(UUID id) {
        return getGameDir().resolve("archive_worlds").resolve("worlds").resolve(id.toString());
    }

    public static boolean exists() {
        return Files.exists(getWorldPath().resolve("level.dat"));
    }

    public static Path getWorldDir(ArchiveWorldInfo info) {
        return info.getStoragePath() != null
                ? getGameDir().resolve(info.getStoragePath())
                : getWorldPath(info.getId());
    }

    public static boolean exists(ArchiveWorldInfo info) {
        return Files.exists(getWorldDir(info).resolve("level.dat"));
    }

    public static LevelStorageSource.LevelStorageAccess createAccess() throws IOException {
        var source = LevelStorageSource.createDefault(getGameDir());
        var access = source.createAccess(WORLD_NAME);
        Files.createDirectories(access.getWorldDir());
        return access;
    }

    public static LevelStorageSource.LevelStorageAccess createAccess(ArchiveWorldInfo info) throws IOException {
        var source = LevelStorageSource.createDefault(getGameDir());
        var saveName = info.getStoragePath() != null
                ? info.getStoragePath()
                : "archive_worlds/worlds/" + info.getId().toString();
        var access = source.createAccess(saveName);
        Files.createDirectories(access.getWorldDir());
        var lockFile = access.getWorldDir().resolve("session.lock");
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException ignored) {}
        return access;
    }
}
