# Phase 3：多世界架构设计（修订版）

## 1. 数据模型

### ArchiveWorldInfo（仅持久化，存 registry.json）

```java
// world/ArchiveWorldInfo.java (NEW)
public class ArchiveWorldInfo {
    UUID id;                // 主键
    String name;            // 显示名称
    long seed;              // WorldOptions 种子
    boolean genStructures;  // 是否生成结构
    long createdAt;         // 创建时间戳
    boolean deleted;        // 软删除标记
    // storagePath 从 UUID 派生，不存储
    // ResourceKey 运行时生成，不存储
}
```

### ArchiveWorldRuntime（仅内存）

```java
// world/ArchiveWorldRuntime.java (NEW)
public class ArchiveWorldRuntime {
    UUID worldId;
    ResourceKey<Level> dimensionKey;  // 运行时从 UUID 派生
    ServerLevel level;                // null = 未加载
}
```

### 职责分离

| | ArchiveWorldInfo | ArchiveWorldRuntime |
|---|---|---|
| 存储位置 | registry.json（磁盘） | 内存 |
| 管理者 | Registry | Manager |
| 内容 | UUID/name/seed/structure/时间戳/删除标记 | UUID/key/ServerLevel |

---

## 2. 目录结构

```
{gameDir}/
├── archive_world/              ← v1.0 遗留路径（迁移后保留）
└── archive_worlds/
    ├── registry.json           ← 世界列表 + 默认世界ID
    └── worlds/
        ├── <uuid1>/            ← 世界1
        └── <uuid2>/            ← 世界2
```

### registry.json

```json
{
  "defaultWorldId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "worlds": [
    {
      "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "name": "Default World",
      "seed": 0,
      "genStructures": true,
      "createdAt": 1721560000000,
      "deleted": false
    }
  ]
}
```

### ResourceKey 生成

```
UUID "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
  → hex = "a1b2c3d4e5f6"
  → archiveworld:world_a1b2c3d4e5f6
```

运行时派生，不持久化。

---

## 3. 类职责

| 类 | 操作 | 职责 |
|----|------|------|
| `ArchiveWorldInfo` | **NEW** | Gson POJO。序列化到 registry.json |
| `ArchiveWorldRuntime` | **NEW** | 运行时持有者。一个 UUID 对应一个 Runtime |
| `ArchiveWorldRegistry` | **NEW** | Gson 读写。Map<UUID,Info>。初始化/迁移/CRUD。不持有 Level |
| `ArchiveWorldManager` | **MODIFY** | Map<UUID,Runtime>。loadWorld/unloadWorld。enterArchiveWorld 路由 |
| `ArchiveWorldStorage` | **MODIFY** | getWorldPath(UUID); 保留 v1.0 路径兼容 |
| `ArchiveWorldMod` | **MINOR** | onServerStarted → Registry.initialize() |
| `ArchiveBookItem` | **NO CHANGE** | |
| `ModItems` | **NO CHANGE** | |
| `accesstransformer.cfg` | **NO CHANGE** | |

---

## 4. 数据流

### 启动初始化

```
ServerStartedEvent
  → Registry.initialize(gameDir)
      ├─ archive_worlds/registry.json 存在？
      │   YES → Gson 读取 → 填充 worlds Map
      │
      ├─ archive_world/level.dat 存在？（v1.0迁移）
      │   YES → 创建 Info（storagePath = archive_world/）→ 设为默认
      │
      └─ 都没有 → 创建默认世界 → 写入 registry.json
```

### 玩家进入

```
Book.use()
  → Manager.enterArchiveWorld(player)
      ├─ 已在 Archive World？→ 返回原位
      └─ 不在
          → Registry.getDefaultWorld() → Info
          → Manager.runtimes.get(id)
              ├─ 已加载 → 直接传送
              └─ 未加载
                  → Storage.createAccess(id)
                  → new PrimaryLevelData(info.seed, ...)
                  → new ServerLevel(12 params)
                  → server.levels.put(key, level)
                  → runtimes.put(id, new Runtime(...))
                  → player.teleportTo(level, ...)
```

---

## 5. 软删除

```
deleteWorld(id):
  1. Manager.unloadWorld(id)   ← 从 levels Map 移除
  2. info.deleted = true       ← 标记
  3. save()                    ← 持久化
  4. 磁盘目录保留

未来:
  restoreWorld(id)  → deleted = false
  purgeWorld(id)    → 删除目录 + 移除条目
```

---

## 6. v1.0 迁移

```
首次启动：
  archive_worlds/registry.json 不存在
  + archive_world/level.dat 存在
  → 生成 UUID → 创建 Info（deleted=false, seed=0）
  → storagePath 指向 archive_world/（旧路径保留）
  → 写入 registry.json → 设为默认世界
  → 不移动文件
```

---

## 7. Phase 3 开发步骤

| 阶段 | 内容 |
|------|------|
| 3-A | 架构设计 ✓ |
| **3-B** | ArchiveWorldInfo + Runtime + Registry + Gson 持久化 |
| 3-C | Manager 重构（通过 Registry 路由） |
| 3-D | v1.0 迁移 + 向后兼容 |
| 3-E | 多世界并存验证 |

---

## 8. Phase 3-B 范围

只实现数据结构 + Registry，不修改 Manager 核心逻辑：
- `ArchiveWorldInfo.java`（NEW）
- `ArchiveWorldRuntime.java`（NEW）
- `ArchiveWorldRegistry.java`（NEW，含 Gson 读写 + 迁移）
- `ArchiveWorldStorage.java`（MODIFY，多路径）
- **不修改** `enterArchiveWorld()` 和 `ArchiveBookItem`
