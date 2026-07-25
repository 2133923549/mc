package com.mc.archiveworld.world;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ArchiveWorldStorage {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WORLD_NAME = "archive_world";

    public static Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path getWorldPath() {
        return getGameDir().resolve(WORLD_NAME);
    }

    public static boolean exists() {
        return Files.exists(getWorldPath().resolve("level.dat"));
    }

    public static LevelStorageSource.LevelStorageAccess createAccess() throws IOException {
        var source = LevelStorageSource.createDefault(getGameDir());
        var access = source.createAccess(WORLD_NAME);
        Files.createDirectories(access.getWorldDir());
        var lockFile = access.getWorldDir().resolve("session.lock");
        try { Files.deleteIfExists(lockFile); } catch (IOException ignored) {}
        return access;
    }
}
