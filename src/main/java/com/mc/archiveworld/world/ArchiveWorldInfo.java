package com.mc.archiveworld.world;

import java.util.UUID;

public class ArchiveWorldInfo {

    private UUID id;
    private String name;
    private long createdAt;
    private boolean deleted;
    private String storagePath;

    public ArchiveWorldInfo() {}

    public ArchiveWorldInfo(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
}
