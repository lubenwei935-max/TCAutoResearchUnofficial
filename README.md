# TCAutoResearchUnofficial

神秘时代 4（Minecraft 1.7.10）研究台辅助：自动求解笔记、解锁全部要素、按需合成要素。

本项目是 [cdj68765/TC4AutoResearch](https://github.com/cdj68765/TC4AutoResearch) 的非官方续作，
后者衍生自 [wohaopa/TC4Helper](https://github.com/wohaopa/TC4Helper)。

## 血缘

```
TC4Helper                       wohaopa              MIT
  └─ TC4AutoResearch            cdj68765 (Emil)      MIT
      └─ TCAutoResearchUnofficial   wohaopa, Mr.Chang   MIT ← 本项目
```

## 功能

- **自动求解**：一键求解当前笔记，按 tick 分步放置，可随时停止
- **方案预览**：执行前在格子上画要素虚影并列出消耗，确认后才动手
- **解锁全部要素**：自动补齐基础要素，逐个解锁
- **要素合成**：面板内按需合成指定要素
- **自动模式**：开着研究台时，遇到新笔记自动求解

界面只挂 Forge 事件（无 Mixin），工作线程不触碰客户端对象 —— 这是与原版最本质的区别，
详见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 安装

1. Minecraft **1.7.10** + Forge **10.13.4.1614**
2. 神秘时代 **Thaumcraft 4.2.3.5**
3. 把 `TCAutoResearchUnofficial-1.7.10-1.0.0.jar` 放进 `mods/`

> 内置求解器是 Windows x64 原生程序，其他系统会给出「不可用」提示。

## 与原版的关系

- modid 为 `tcautoresearchunofficial`，与原版 `TCAutoResearchByEmil` **不同**，两者可同时安装
- 配置独立：`config/tcautoresearchunofficial.cfg`，不读取旧配置，升级需重新设置

## 兼容性修复

**Hodgepodge + 神秘时代的研究台崩溃**（`ConcurrentModificationException`）：
渲染线程在 `GuiResearchTable.drawAspectText` 里遍历玩家的「已发现要素」列表，
而神秘时代的 `PacketAspectDiscovery` 会在 Netty 线程往同一个 `LinkedHashMap` 里写；
Hodgepodge 把 `getAspectsSorted()` 改成 stream 排序后，遍历窗口变大，崩得更勤。

本模组默认把该列表的 `aspects` 字段换成读写同锁、遍历返回快照的实现（`SafeAspectMap`），
从源头消除这条竞态。配置项 `compat.SafeAspectIteration`（默认 `true`）可关闭。

## 构建

需要 JDK 8 与 Gradle 2.x（ForgeGradle 1.2 的限制）：

```bash
# 先把 Thaumcraft-1.7.10-4.2.3.5.jar 放进 libs/（闭源，不随仓库分发）
gradle build
```

产物：`build/libs/TCAutoResearchUnofficial-1.7.10-1.0.0.jar`

> 必须用 `gradle build`（会跑 reobf），不要用 `gradle jar`；`verifyReobf` 会在打包后校验 SRG 名。

## 授权

MIT，见 [LICENSE](LICENSE)。原作者归属与第三方组件清单见 [NOTICE](NOTICE)。
本项目与 Azanor、Mojang、MinecraftForge 均无隶属关系。

更新记录见 [CHANGELOG.md](CHANGELOG.md)。
