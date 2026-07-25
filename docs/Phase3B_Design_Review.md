# Phase 3-B 修订后实现设计

## 1. Phase 3-A 架构调整说明

### 问题 1：ArchiveWorldInfo 不应该保存 ServerLevel

原设计中 `ArchiveWorldInfo` 同时持有元数据和 `ServerLevel` 引用，导致：

- 持久化对象与运行时对象混淆，Gson 序列化时 `ServerLevel` 字段无法处理
- 同一个类既负责磁盘读写又负责内存状态，违反单一职责
- `ServerLevel` 生命周期（加载/卸载/tick）与元数据（创建/删除/查询）耦合

**修正**：拆分为 `ArchiveWorldInfo`（磁盘）和 `ArchiveWorldRuntime`（内存）。

### 问题 2：ResourceKey 不应该持久化

`ResourceKey<Level>` 是运行时概念，由 `Registries.DIMENSION` 注册表管理。持久化它带来：

- JSON 中存 `"minecraft:archive_world"` 字符串，重新解析时依赖注册表状态
- UUID 本身就是唯一标识，ResourceKey 可以确定性派生
- 存两份标识（UUID + Key）增加了不一致的风险

**修正**：ResourceKey 从 UUID 运行时派生，`archiveworld:world_<12hex>`。

### 问题 3：deleteWorld 不应该立即删除物理目录

立即删除的风险：

- 误删后无法恢复
- 多人环境中其他玩家可能正在访问
- 调试时需要检查已删除世界的数据

**修正**：软删除。`deleted = true`，保留磁盘目录。未来提供 `restore()` 和 `purge()`。

### 问题 4：registry.json 数据结构优化

原设计将所有数据平铺在顶层，难以扩展。修正后的目录结构：

```
archive_worlds/
├── registry.json
└── worlds/
    └── <uuid>/
```

`worlds/` 子目录隔离，未来可增加 `thumbnails/`、`backups/`、`metadata/` 等。

---

## 2. 新架构设计

### 2.1 类职责

```
┌─────────────────────────────────────────────────────────────┐
│                     ArchiveWorldMod                         │
│  onServerStarted → Registry.initialize()                    │
│  持有: 无状态                                               │
└──────────────────────┬──────────────────────────────────────┘
                       │
          ┌────────────┴────────────┐
          ▼                         ▼
┌──────────────────┐      ┌──────────────────┐
│ Registry (磁盘)   │      │  Manager (内存)   │
│                  │      │                  │
│ Map<UUID,Info>   │◄────►│ Map<UUID,Runtime>│
│ defaultWorldId   │      │                  │
│                  │      │ loadWorld()      │
│ createWorld()    │      │ unloadWorld()    │
│ deleteWorld()    │      │ enterArchive()   │
│ listWorlds()     │      │ returnToOrigin() │
│ save()/load()    │      │ findSafeSpawn()  │
└────────┬─────────┘      └────────┬─────────┘
         │                         │
         ▼                         ▼
┌──────────────────┐      ┌──────────────────┐
│ ArchiveWorldInfo │      │ArchiveWorldRuntime│
│ (POJO)           │      │                  │
│                  │      │ UUID             │
│ UUID             │      │ ResourceKey      │
│ name             │      │ ServerLevel      │
│ seed             │      └──────────────────┘
│ genStructures    │
│ createdAt        │      ┌──────────────────┐
│ deleted          │      │ArchiveWorldStorage│
└──────────────────┘      │                  │
                          │ getWorldPath()   │
                          │ createAccess()   │
                          └──────────────────┘
```

### 2.2 类关系文本描述

- `ArchiveWorldMod` — 启动时触发 `Registry.initialize()`。无持久状态
- `ArchiveWorldRegistry` — 拥有 `Map<UUID, ArchiveWorldInfo>`。Gson 读写 `registry.json`。提供 CRUD 但不触碰 `ServerLevel`
- `ArchiveWorldManager` — 拥有 `Map<UUID, ArchiveWorldRuntime>`。负责 `ServerLevel` 的加载/卸载/传送。通过 Registry 查询 Info，通过 Runtime 管理 Level
- `ArchiveWorldInfo` — 纯数据 POJO。Gson 序列化。不引用任何 Minecraft 类
- `ArchiveWorldRuntime` — 持有 `ServerLevel` 和 `ResourceKey`。纯运行时，不持久化
- `ArchiveWorldStorage` — 工具类：路径计算 + `LevelStorageAccess` 创建

---

## 3. 文件结构

```
src/main/java/com/mc/archiveworld/world/
├── ArchiveWorldInfo.java           (NEW)  持久化元数据 POJO
├── ArchiveWorldRuntime.java        (NEW)  运行时 ServerLevel 持有者
├── ArchiveWorldRegistry.java       (NEW)  Gson CRUD + 迁移 + 初始化
├── ArchiveWorldManager.java        (MODIFY)  持有 Runtime Map + 路由
└── ArchiveWorldStorage.java        (MODIFY)  多路径支持
```

---

## 4. registry.json 设计

### 完整示例

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
    },
    {
      "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
      "name": "Creative Flat",
      "seed": 12345,
      "genStructures": false,
      "createdAt": 1721560001000,
      "deleted": false
    },
    {
      "id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
      "name": "Old World",
      "seed": 99999,
      "genStructures": true,
      "createdAt": 1721560002000,
      "deleted": true
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `defaultWorldId` | UUID | 右键书默认进入的世界 |
| `id` | UUID | 世界主键，同时作为目录名和 ResourceKey 来源 |
| `name` | String | 显示名称 |
| `seed` | long | 世界种子 |
| `genStructures` | boolean | 是否生成村庄/要塞等结构 |
| `createdAt` | long | Unix 毫秒时间戳 |
| `deleted` | boolean | 软删除标记。true 时 `listWorlds()` 默认不返回 |

### WorldOptions 重建

```java
// 从 Info 字段重建 WorldOptions（第三个参数 generateBonusChest = false）
new WorldOptions(info.seed, info.genStructures, false)
```

---

## 5. archive_worlds 目录结构

```
{gameDir}/archive_worlds/
│
├── registry.json                 ← 世界索引
│
├── worlds/                       ← 世界数据目录
│   ├── a1b2c3d4-e5f6-7890-abcd-ef1234567890/
│   │   ├── level.dat
│   │   ├── session.lock
│   │   └── dimensions/
│   │       └── archiveworld/
│   │           └── world_a1b2c3d4e5f6/
│   │               ├── region/
│   │               ├── entities/
│   │               ├── poi/
│   │               └── data/
│   │
│   └── b2c3d4e5-f6a7-8901-bcde-f12345678901/
│       └── ...
│
├── thumbnails/                   ← 未来：世界缩略图
│   └── <uuid>.png
│
├── backups/                      ← 未来：世界备份
│   └── <uuid>/
│       └── <timestamp>.zip
│
└── metadata/                     ← 未来：额外元数据
    └── <uuid>.json
```

### v1.0 兼容路径

```
{gameDir}/archive_world/          ← v1.0 旧路径
    ├── level.dat
    └── dimensions/
```

迁移后：`archive_worlds/registry.json` 中有一条记录，但 `storagePath` 指向 `archive_world/`（不移动文件）。

---

## 6. 生命周期流程

### 6.1 服务端启动

```
ServerStartedEvent 触发
        │
        ▼
Registry.initialize(gameDir)
        │
        ├─ Step 1: 检查 archive_worlds/registry.json
        │     │
        │     ├─ 存在 → Gson.fromJson() → 填充 worlds Map
        │     │         → 验证默认世界 ID 是否有效
        │     │         → 过滤 deleted=true 的世界
        │     │         → LOG "Loaded N worlds from registry"
        │     │
        │     └─ 不存在 → 执行迁移/创建流程（Step 2）
        │
        ├─ Step 2: 检查 archive_world/level.dat（v1.0 迁移）
        │     │
        │     ├─ 存在 → 生成新 UUID
        │     │         → 创建 Info:
        │     │             id = newUUID
        │     │             name = "Default World"
        │     │             seed = 0 (v1.0 固定值)
        │     │             genStructures = true
        │     │             storagePath = "archive_world" (旧路径)
        │     │         → 加入 worlds Map
        │     │         → defaultWorldId = newUUID
        │     │         → save() 写入 registry.json
        │     │         → LOG "Migrated v1.0 world to registry"
        │     │
        │     └─ 不存在 → 创建默认世界（Step 3）
        │
        └─ Step 3: 创建默认世界
                  → 生成 UUID
                  → 创建 Info（seed=0, name="Default World"）
                  → 创建目录 archive_worlds/worlds/<uuid>/
                  → 写入 registry.json
                  → LOG "Created default world"
```

### 6.2 玩家传送

```
ArchiveBookItem.use()
        │  (level.isClientSide → return early)
        │  (player instanceof ServerPlayer)
        ▼
ArchiveWorldManager.enterArchiveWorld(player)
        │
        ├─ 检测: player.level().dimension() 是否匹配任意已加载 Runtime 的 dimensionKey
        │     │
        │     ├─ 匹配 → returnToOriginalWorld(player)
        │     └─ 不匹配 → 继续
        │
        ├─ Registry.getDefaultWorld() → ArchiveWorldInfo info
        │     │
        │     └─ info == null → player.sendMessage("No world available") → return
        │
        ├─ Manager.runtimes.get(info.id)
        │     │
        │     ├─ 存在 + level != null → archiveLevel = runtime.level
        │     │
        │     └─ 不存在 or level == null
        │           │
        │           ├─ ArchiveWorldStorage.createAccess(info.id)
        │           ├─ 计算 WorldOptions(info.seed, info.genStructures, false)
        │           ├─ new PrimaryLevelData(levelSettings, worldOptions, ...)
        │           ├─ new LevelStem(overworld.dimTypeRegistration(), overworld.chunkGen)
        │           ├─ new ServerLevel(server, executor, access, levelData, key, stem, ...)
        │           ├─ server.levels.put(key, level)          ← AT
        │           ├─ MinecraftForge.EVENT_BUS.post(LevelEvent.Load)
        │           ├─ configureWorldRules(level, server)
        │           ├─ runtimes.put(info.id, new Runtime(info.id, key, level))
        │           └─ archiveLevel = level
        │
        ├─ 保存返回位置: RETURN_POSITIONS.put(uuid, ReturnPosition(...))
        │
        ├─ findSafeSpawn(archiveLevel) → BlockPos
        │
        └─ player.teleportTo(archiveLevel, x, y, z, yRot, xRot)
```

### 6.3 在 Archive World 内检测

当前 v1.0 使用 `player.level().dimension().equals(SHARED_ARCHIVE_KEY)` 检测。

Phase 3-B 改为遍历 `Manager.runtimes`：

```java
boolean isInArchiveWorld = Manager.runtimes.values().stream()
    .anyMatch(r -> r.level != null
        && player.level().dimension().equals(r.dimensionKey));
```

---

## 7. Phase 3-B 开发拆分

### 3-B1：数据类

| 项目 | 内容 |
|------|------|
| **目标** | 创建 `ArchiveWorldInfo` 和 `ArchiveWorldRuntime` 纯数据类 |
| **修改文件** | `world/ArchiveWorldInfo.java` (NEW) |
| | `world/ArchiveWorldRuntime.java` (NEW) |
| **验收** | `gradlew build` 通过。Info 可被 Gson 序列化/反序列化 |

### 3-B2：Registry 持久化

| 项目 | 内容 |
|------|------|
| **目标** | 实现 `ArchiveWorldRegistry`：Gson 读写 `registry.json`、CRUD |
| **修改文件** | `world/ArchiveWorldRegistry.java` (NEW) |
| | `world/ArchiveWorldStorage.java` (MODIFY: `getWorldPath(UUID)`) |
| **验收** | `gradlew build` 通过。单元逻辑：create → save → load 后数据一致 |

### 3-B3：默认世界迁移

| 项目 | 内容 |
|------|------|
| **目标** | Registry.initialize() 处理 v1.0 迁移 → 创建默认世界 → 写入 registry.json |
| **修改文件** | `ArchiveWorldMod.java` (MINOR: onServerStarted 调用 Registry.initialize) |
| **验收** | 删除 `archive_worlds/` → 启动游戏 → `registry.json` 生成 → 默认世界条目存在 |

**注意**：3-B 阶段不修改 `enterArchiveWorld()` 和 `ArchiveBookItem`。Manager 重构留在 Phase 3-C。

---

## 8. 风险分析

### 8.1 多 ServerLevel 内存占用

| 项目 | 分析 |
|------|------|
| **风险** | 每个加载的 `ServerLevel` 占用 50-200MB（含 chunk cache） |
| **等级** | 中 |
| **缓解** | 3-B 阶段仅保持单世界加载（默认世界）。3-E 实现按需加载 + 闲置卸载 |

### 8.2 世界卸载

| 项目 | 分析 |
|------|------|
| **风险** | Player 仍在世界内时卸载会导致崩溃/NPE |
| **等级** | 中 |
| **缓解** | `unloadWorld()` 前检查 `level.players.isEmpty()`。有玩家时拒绝卸载 |

### 8.3 Mod 兼容

| 项目 | 分析 |
|------|------|
| **风险** | 多个 `archiveworld:world_*` 维度 Key 可能被 Mod 缓存 |
| **等级** | 低 |
| **缓解** | 所有 Key 使用标准 `ResourceKey.create()`，与 Phase 2 无差异。每个世界使用独立的 `LevelStorageAccess` |

### 8.4 registry.json 损坏恢复

| 项目 | 分析 |
|------|------|
| **风险** | 手动编辑或磁盘故障导致 JSON 损坏，所有世界不可用 |
| **等级** | 低 |
| **缓解** | 加载失败时备份损坏文件为 `registry.json.broken`，然后扫描 `archive_worlds/worlds/` 目录重建 Registry |
