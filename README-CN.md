# Sky Vaulters Support — CN 分支

Wold's Vaults 服务端模组的汉化 + 天空宝库强化。Fork 自 [YorickBM/SkyblockAddon](https://github.com/YorickBM/SkyblockAddon)。

只装**服务端**。客户端不需要本模组。

## 改了什么

**1. 汉化** — 语言表 60 条 + 10 个 GUI 的按钮/标题/lore + 权限分类名。

顺带修了上游一个坑：它把语言表写死成 `lang/en_us.json`，且只在文件缺失时才写，
所以服务器启动过一次后英文表永久留存。改为按 locale 选表，且**仅当磁盘文件与内置英文表
逐字节相同**才替换 —— 你手改过的配置不会被覆盖。

**2. Terralith 拦截** — 修 `level-type=天空宝库` 生成不出来地形的问题。

Terralith 2.2.6 的 `MixinTerrablenderConfig` 对 `TerraBlenderConfig` 里调用
`Config.addNumber` 的位置做了 `@Redirect`，把 `vanilla_overworld_region_weight`
短路成 `0`。配合 `terralith.toml` 默认 `terrablender_compatibility = true`，
普通世界 region 权重被全局归零 → 非 Terralith 的 level-type 拿不到 region → 退化为纯虚空。

本模组以 `priority = 1100` 抢同一个调用点，仅在天空宝库世界写回 `100`，
其他世界原样转发。level-type 靠生成器类名结构化识别，不写死 ID。

> 上游另有一处：发现 Terralith 就抛异常让服务端起不来。而 Terralith 就在官方包里，
> 所以原版模组在自家包上根本没法启用。已改为延后到服务器启动时判定并放行。

**3. 主城改造** — 主城改为程序化生成的「悬浮宝库祭坛」。

上游把**同一份 `island.nbt`** 同时当世界出生点和玩家空岛模板，地形完全一样，
新玩家登录又正好落在出生点，于是分不清身在主城还是自己的岛。

现在主城由 `HubStructurePlacer` 逐块生成（三层同心阶梯 + 8 根宝库立柱 + 中央水晶柱 +
玻璃底光，取自 `the_vault`，缺模组时降级为原版方块），且**不注册进 `IslandManager`** ——
所以它不可能被 `/island create` 发出、不进旅行地图、不会被 purge。

玩家空岛保持原版 VH3 出生岛不变。高度也错开：主城 128 / 玩家岛 110。

## 编译

需 **Java 17**（MC 1.18.2 / Forge 40.3.11 的 class 版本是 61）。

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
gradlew.bat buildAll
```

产物在 `1.18.2/build/libs/`。`libs/` 下的 TerraBlender jar 供 mixin 编译期解析目标类，
不参与运行。

## 验收

- [ ] 服务端能启动，日志有「Terralith 可与其共存」，**没有**崩溃
- [ ] 日志出现「vanilla_overworld_region_weight ... 恢复为 100」
- [ ] 天空宝库世界能生成地形（不是纯虚空）
- [ ] `/island` 界面全中文；权限分类显示「管理控制 / 基础 / 红石 / 存储 / 机械动力 / 杂项 / 宝库猎人」
- [ ] `config/skyblockaddon/language.json` 与 `guis/*.json` 内容为中文
- [ ] 首次进入是**多层圆形祭坛**，与 `/island create` 拿到的岛明显不同
- [ ] 有岛的玩家登录后落在自己岛；无岛的落在主城
- [ ] `/island hub` 回主城；掉进虚空回自己岛

**回滚**：换回官方 `sky-vaulters-support` jar，无数据格式变更。

## 已知限制

- 主城每个世界只生成一次，重启不重建（方块已在区块里）。要重建需删掉该区域区块。
- 主城高度由 `config/skyblockaddon/config.toml` 的 `hub.spawn.height` 控制（默认 128）。
- 权限分类名覆盖 7 个内置分类；上游新增分类会回退成英文原名，不报错。
- 汉化需服务端 JVM locale 为中文（`-Duser.language=zh -Duser.country=CN`），
  否则回退英文。

---

上游项目遵循其自定义 LICENSE，本 Fork 仅供本服务端使用。
