package com.mc.archiveworld.world;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ArchiveBackup {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static Path createBackup(MinecraftServer server) {
        try {
            var level = server.getLevel(ArchiveWorldManager.ARCHIVE_KEY);
            if (level != null) {
                level.save(null, true, false);
            }

            var ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            var backupDir = FMLPaths.GAMEDIR.get().resolve("archive_backups").resolve(ts);
            Files.createDirectories(backupDir);

            var src = ArchiveWorldStorage.getWorldPath();
            copyWorldFiles(src, backupDir);

            LOGGER.info("[ArchiveBackup] Created: {}", backupDir);
            return backupDir;
        } catch (Exception e) {
            LOGGER.error("[ArchiveBackup] Failed", e);
            return null;
        }
    }

    private static void copyWorldFiles(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().equals("session.lock"))
                .forEach(f -> {
                    try {
                        var rel = src.relativize(f);
                        var target = dst.resolve(rel);
                        Files.createDirectories(target.getParent());
                        Files.copy(f, target);
                    } catch (IOException ignored) {}
                });
        }
    }
}
