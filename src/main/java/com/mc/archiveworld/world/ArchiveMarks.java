package com.mc.archiveworld.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ArchiveMarks {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Mark>>(){}.getType();

    public static class Mark {
        public double x, y, z;
        public long createdAt;
        public Mark(double x, double y, double z, long createdAt) {
            this.x = x; this.y = y; this.z = z; this.createdAt = createdAt;
        }
    }

    public static void add(double x, double y, double z) {
        var marks = loadAll();
        marks.add(new Mark(x, y, z, System.currentTimeMillis()));
        save(marks);
    }

    public static List<Mark> loadAll() {
        var p = getPath();
        if (!Files.exists(p)) return new ArrayList<>();
        try (Reader r = Files.newBufferedReader(p)) {
            return GSON.fromJson(r, LIST_TYPE);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static void save(List<Mark> marks) {
        try (Writer w = Files.newBufferedWriter(getPath())) {
            GSON.toJson(marks, w);
        } catch (IOException e) {
            LOGGER.error("[ArchiveMarks] Failed to save", e);
        }
    }

    private static Path getPath() {
        return ArchiveWorldStorage.getWorldPath().resolve("marks.json");
    }
}
