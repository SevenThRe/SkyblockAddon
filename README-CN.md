# Sky Vaulters Support — 汉化版

Wold's Vaults 服务端模组的汉化版。Fork 自 [YorickBM/SkyblockAddon](https://github.com/YorickBM/SkyblockAddon)。

服务端专用，客户端不用装。

## 安装

丢进服务端 `mods/`，替换原版 `sky-vaulters-support`。

汉化需要服务端 JVM 是中文，否则回退英文：

```
-Duser.language=zh -Duser.country=CN
```

## 改了什么

- 界面汉化（命令反馈、岛屿 GUI、权限分类名）
- 修好了 `level-type=天空宝库` 生成不出地形的问题（Terralith 会把世界权重归零）
- 主城换成程序化生成的悬浮宝库祭坛，和玩家自己领的岛长得不一样
- 不再因为装了 Terralith 而启动崩溃

## 配置

`config/skyblockaddon/config.toml`

| 键 | 默认 | 说明 |
|---|---|---|
| `hub.spawn.height` | 128 | 主城高度。玩家岛是 110，别设成一样 |
| `island.spawn.height` | 110 | 玩家岛高度（上游原有） |

主城每个世界只生成一次，改高度后需要删掉该区域区块才会重建。

## 编译

需要 Java 17。

```bat
set "JAVA_HOME=<你的 jdk-17 路径>"
gradlew.bat buildAll
```

产物在 `1.18.2/build/libs/`。

## 出问题

**启动崩溃，日志里有 Terralith 相关** — 确认 `level-type` 是不是被手动改过。本模组只放行天空宝库世界，其他 level-type 下 Terralith 的权重冲突修不了，日志会有说明。

**天空宝库生成出来是纯虚空** — 看日志有没有 `vanilla_overworld_region_weight ... 恢复为 100`。没有就是拦截没生效，报 issue 时把日志附上。

**界面还是英文** — 检查 JVM 参数，以及游戏语言是否设为简体中文。

**回滚** — 换回原版 jar，没有数据格式变更。
