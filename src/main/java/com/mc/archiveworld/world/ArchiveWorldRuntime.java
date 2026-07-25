package com.mc.archiveworld.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class ArchiveWorldRuntime {

    private final UUID worldId;
    private final ResourceKey<Level> dimensionKey;
    private volatile ServerLevel level;

    public ArchiveWorldRuntime(UUID worldId, ResourceKey<Level> dimensionKey, ServerLevel level) {
        this.worldId = worldId;
        this.dimensionKey = dimensionKey;
        this.level = level;
    }

    public UUID getWorldId() { return worldId; }

    public ResourceKey<Level> getDimensionKey() { return dimensionKey; }

    public ServerLevel getLevel() { return level; }

    public void setLevel(ServerLevel level) { this.level = level; }
}
