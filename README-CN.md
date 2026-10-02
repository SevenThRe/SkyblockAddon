# Sky Vaulters Support — CN 分支（汉化 + 天空宝库强化）

本仓库是 [YorickBM/SkyblockAddon](https://github.com/YorickBM/SkyblockAddon) 的 Fork，
用于 Wold's Vaults 0.34.2 服务端包。相对上游做了三件事：

1. **汉化** —— 语言表、GUI、配置注释
2. **Terralith 拦截** —— 让 `level-type = 天空宝库` 真正能生成地形
3. **主城改造** —— 主城改为程序化生成的「悬浮宝库祭坛」，与玩家空岛彻底区分

> 上游仓库与作者：YorickBM。原项目遵循其自定义 LICENSE，Fork 仅供本服务端使用。

---

## 一、汉化

### 覆盖范围

| 位置 | 内容 |
|---|---|
| `core/.../lang/zh_cn.json` | 60 条命令/界面反馈 |
| `core/.../guis/*.json` | 10 个 GUI 的全部按钮、标题、lore |
| `%group_category%` | 权限分类名（原本渲染成 `Admin`、`Storage` 等英文） |
| `config.toml` 注释 | 新增 `hub.spawn.height` 的中文说明 |

### ⚠️ 上游的一个坑（已修）

上游 `ResourceManager.commonSetup()` 里写死了：

```java
generateFile(FMLPath, "language.json", "lang/en_us.json");
```

两个后果：
- **语言表恒为英文**，跟游戏语言设置无关；
- `generateFile()` 只在文件不存在时写入，所以**服务器只要启动过一次，
  英文 `language.json` 就永久留存**，升级也换不掉。

本分支改为按 JVM locale 选表，并且**只在该文件与内置英文表逐字节相同时**才替换
（即：服主手动改过的文件不会被覆盖）。

GUI 文件同理 —— 文本是内嵌在 JSON 里的，走同一套「纯净英文则替换」逻辑。

### 生效方式

服务端启动 JVM 的 locale 需为中文，例如 `run.bat` / `user_jvm_args.txt`：

```
-Duser.language=zh -Duser.country=CN
```

若未设置，会回退到 `en_us`（此时界面为英文，属预期）。

---

## 二、Terralith 拦截（level-type 失效的修复）

### 根因（已用字节码实证）

服务端包 0.34.1 里同时装着 **Terralith 2.2.6 + TerraBlender 1.2.0 + Tectonic 2.3.5a**，
而 `level-type = 天空宝库` 由 **SkyblockBuilder 3.3.33** 注册。

Terralith 的 `MixinTerrablenderConfig` 对
`terrablender.config.TerraBlenderConfig` 中调用 `Config.addNumber` 的位置做了 `@Redirect`：

```java
if (key.equals("vanilla_overworld_region_weight") && defaultValue instanceof Integer)
    return Integer.valueOf(0);        // addNumber 根本不会被调用
return instance.addNumber(comment, key, defaultValue, min, max);
```

配合 `config/terralith/terralith.toml` 里的默认 `terrablender_compatibility = true`，
**普通世界 region 权重被全局归零**。非 Terralith 的 level-type 拿不到 region，
天空宝库世界就退化成纯虚空 —— 这就是「level-type 失效」的真正含义，
不是配置写错。

### 修法

`@Redirect` 独占调用点，所以对 `Config.addNumber` 打 `@Inject` 是没用的。
本分支从同一侧 `@Redirect` 同一个调用点
（`mixins/TerralithRegionWeightMixin.java`），仅在**天空宝库世界**把权重写回 `100`；
其他世界原样调用 `addNumber`，与上游行为完全一致。

- 识别方式：检查世界生成器类名是否含 `VoidWorldType` /
  `SkyblockNoiseBasedChunkGenerator`（**结构化识别**，不写死 ID，换模组也照样生效）。
- mixin 配置 `required: false` + `defaultRequire: 0`：没装 TerraBlender/Terralith 就自动跳过。

### 上游的崩溃行为（已改）

上游 `processIMC()` 一旦发现 Terralith 就 `throw new TerralithFoundException()` ——
**直接让服务端起不来**。而 Terralith 就在官方服务端包里，所以原版模组在自家包上
根本没法启用。

本分支改为：
- 启动时打中文警告，说明冲突原因；
- 真正的判定**延后到 `ServerStartingEvent`**（`InterModProcess` 阶段还没有
  `MinecraftServer`，读不到 level-type，此时判定必然是错的）；
- 是天空宝库 → 放行；不是 → 打 error 日志指出该怎么处理。

---

## 三、主城改造（悬浮宝库祭坛）

### 原问题

上游把**同一份 `island.nbt`** 同时用作：
- 世界出生点（主城）
- `/island create` 发给每个玩家的空岛模板

两者地形一模一样，而新玩家登录正好落在世界出生点、领岛后又看到同一片地形 ——
于是「我到底在主城还是在我的岛」无法分辨。

### 现在的做法

| | 主城 | 玩家空岛 |
|---|---|---|
| 来源 | **Java 程序化生成** | 保持原 `island.nbt` 不变 |
| 外观 | 三层同心阶梯 + 8 根立柱 + 中央水晶柱 + 宝库玻璃底光 | Vault Hunters 原版出生岛 |
| 高度 | `hub.spawn.height`，默认 **128** | `island.spawn.height`，默认 **110** |
| 是否登记为 Island | **否** | 是 |

主城**不注册进 `IslandManager`**。这从根上消除了整类问题：它不可能被 `/island create` 发出去、
不可能出现在旅行地图、不可能被 purge —— 「主城冒充玩家岛」无处发生。

方块取自 `the_vault`（宝库石 / 磨制宝库石 / 錾制宝库石 / 宝库玻璃 / 水晶块），
带原版降级 —— 缺模组时退化成普通石头结构，**不会让世界生成崩掉**。

### 一并修掉的回退路径

| 位置 | 原来 | 现在 |
|---|---|---|
| `ModEvents.onPlayerJoin` | 落在世界出生点（有岛也无岛都落这儿） | 有岛 → 回自己岛；无岛 → 主城 |
| `ForgeIsland.kickMember` | 传送到世界出生点 | 传送到主城（该玩家本就无岛，语义正确） |
| `IslandHubCommand` (`/island hub`) | 传送到世界出生点 | 传送到主城 |
| `PlayerEvents.onVoidFall` | 回本岛 | 不变（本就是对的） |

---

## 四、编译

需要 **Java 17**（MC 1.18.2 / Forge 40.3.11 的 class 版本是 61）。

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"
gradlew.bat buildAll
```

产物：`1.18.2/build/libs/`。

> 注意：本机 PATH 里的默认 `java` 是 **Java 8**，`JAVA_HOME` 环境变量又指向 **Java 21**。
> 两者都不能用，脚本里请显式指定上面的 Java 17 绝对路径。

---

## 五、验收清单

启动服务端后逐项确认：

**汉化**
- [ ] `/island` 打开的界面全中文（标题、按钮、lore）
- [ ] 权限分类显示为「管理控制 / 基础 / 红石 / 存储 / 机械动力 / 杂项 / 宝库猎人」
- [ ] 命令反馈为中文（`/island create` 冷却提示等）
- [ ] `config/skyblockaddon/language.json` 内容为中文
- [ ] `config/skyblockaddon/guis/*.json` 内容为中文

**Terralith 拦截**
- [ ] 服务端能正常启动，日志有「Terralith 可与其共存」且**没有**崩溃
- [ ] 日志出现「悬浮宝库祭坛」或「vanilla_overworld_region_weight ... 恢复为 100」
- [ ] `level-type = 天空宝库` 的世界**能生成地形**（不是纯虚空）
- [ ] 传送门 / Vault 传送门功能正常

**主城**
- [ ] 首次进入看到的是**多层圆形祭坛**（阶梯 + 立柱 + 中央水晶柱）
- [ ] `/island hub` 回到同一座祭坛
- [ ] `/island create` 拿到的岛是 VH3 原版出生岛，**与主城明显不同**
- [ ] 有岛的玩家登录后**直接落在自己岛上**，不在主城
- [ ] 无岛的玩家登录后落在主城
- [ ] 被踢出岛后落在主城
- [ ] 掉进虚空后回到自己岛（不是主城）

**回滚**：把 `mods/` 里的本 jar 换回官方 `sky-vaulters-support` 即可，无数据格式变更。

---

## 六、已知限制

- 主城目前**每次世界首次触发时生成一次**，重启后不重建（方块已在区块里）。
  如需重建，删掉主城所在区块或用 `/island admin` 相关命令后重启。
- 主城高度由 `config/skyblockaddon/config.toml` 的 `hub.spawn.height` 控制（默认 128）。
- 权限分类名目前覆盖 7 个内置分类；上游新增分类会自动回退成英文原名，不会报错。
