# 架构与重构说明

本文记录 1.0.0 重构时的崩溃根因、新架构与已知限制，供后续维护参考。

## 1. 为什么必须重构：崩溃根因

原版崩溃（`crash-2026-10-20_18.46.30-client.txt`）：

```
NPE: GuiContainerManager.getManager() is null
 at LayoutManager.wrapOperation$deo000$neverenoughanimations$renderObjects   ← NEA 的 Mixin
 at LayoutManager.renderObjects → GuiContainerManager.renderObjects
 at GuiContainer.drawScreen → GuiResearchTable.drawScreen
```

拆开看是两件事叠加：

1. **NEA 的缺陷**：它给 NEI 的 `LayoutManager.renderObjects` 加了个 `@WrapOperation`，
   直接 `GuiContainerManager.getManager().window`，没有判空。
2. **旧模组触发了它**：旧代码在**后台线程**里调用 `mc.displayGuiScreen(new GuiMessageBox(...))`：

   ```java
   // GetAllAspectButton$1 / ResearchCurrectNote$1（反编译）
   new Thread(() -> {
       ...
       mc.func_147108_a(new GuiMessageBox(mc.field_71462_r, "...", ...));   // ← 工作线程改界面
   }).start();
   ```

   于是「研究台还在绘制」的那一帧里，当前界面被换成了普通 `GuiScreen`，
   NEI 清空了 `GuiContainerManager`；紧接着 NEI 的渲染尾钩 + NEA 的无判空解引用 → 空指针崩端。

旧实现里同类的结构性问题还有：

| 问题 | 旧实现 | 后果 |
| --- | --- | --- |
| GUI 跨线程操作 | 工作线程 `displayGuiScreen`、读 `guiResearchTable.note` | 崩端、竞态 |
| 求解器进程管理 | 每次求解 `tasklist` 抓 PID，`taskkill /F /PID`，`kill -kill` | 每解一次笔记额外起一个进程；依赖系统语言与输出格式；Linux 上 `kill` 参数还是错的 |
| 步进节奏 | `Thread.sleep(500)` + 后台包 | 服务器没处理完就发下一步；关界面后线程还在跑 |
| 线程生命周期 | `initGui` 里 `new AutoResearch(this,...).start()`，每开一次界面开一个线程 | 线程泄漏，`Stop` 静态量互相踩 |
| 全局状态 | `ResearchCurrectNote.player/mc/guiResearchTable` 等一堆 `public static` | 关界面后仍持有 GUI 引用 |
| 二进制位置 | 解压到游戏工作目录 `AutoResearch.dll` | 污染根目录，多实例互相覆盖 |
| GUI | 4 个 `GuiButtonExt` 硬编码中文、`guiLeft-80` 固定偏移 | 小分辨率/大 GUI 缩放时跑到屏幕外，无 i18n |

## 2. 新架构

**一条铁律：工作线程永不碰 `Minecraft` / 神秘时代 GUI 对象；本模组从不调用 `displayGuiScreen`。**

```
求解器进程（工作线程）
   └─ SolverService（单线程池 + 看门狗超时 + destroyForcibly）
        └─ ClientTasks.run(...)      ← 唯一跨线程入口，tick 里 drain
             └─ ResearchAssistant（客户端状态机，主线程）
                  ├─ SolvePayload      组包（协议与旧求解器一致）
                  ├─ PlanRunner        每 tick 走一步：合成 → 放置（有 pending 记账）
                  └─ ResearchTableAccess  发包（完全复用神秘时代自己的包）
```

界面部分**没有 Mixin**，只挂 Forge 事件：

| 需求 | 实现 |
| --- | --- |
| 往研究台加控件 | `GuiScreenEvent.InitGuiEvent.Post` 的 `buttonList` |
| 面板 / 工具条绘制 | `DrawScreenEvent.Post`（在整屏包括 NEI 叠层之后画，不被背景压暗） |
| 点击 | 自定义 `GuiButton.mousePressed`（`visible` 同时作为“可点”开关） |
| 每帧推进 | `TickEvent.ClientTickEvent`（END，先 drain 任务队列） |

控件全部在 `DrawScreenEvent.Post` 里手绘，`drawButton` 留空：这样面板颜色不会被
`drawDefaultBackground` 的整屏暗色遮罩压暗，也不会被 NEI 的物品面板盖住。

### 目录

```
src/main/java/com/emil/tcautoresearchunofficial/
  TCAutoResearchUnofficial.java  入口
  Config.java                  config/tcautoresearchunofficial.cfg，键名沿用 AutoResearch
  proxy/CommonProxy.java
  client/
    ClientProxy.java           注册事件
    ClientEvents.java          InitGui / DrawScreen 钩子（无 Mixin）
    ClientTickHandler.java     客户端唯一心跳
    ResearchOverlay.java       工具条 + 要素合成面板 + 设置 + 状态行
    widget/Theme.java          暗金配色
    widget/ThemedButton.java   TAB / CELL / CHIP 三种样式
  research/
    ResearchTableAccess.java   读 note / 发包（公开成员，无反射、无 @Shadow）
    SolvePayload.java          入参构造 + 出参解析
    PlanRunner.java            分步执行（合成链 + 放置，pending 记账，防止重复发包）
    ResearchAssistant.java     状态机（自动求解 / 重试 / 解锁全部 / 合成指定要素）
  solver/
    AutoResearchBinary.java    解压到 config/solver/
    SolverService.java         进程生命周期、超时、取消、日志
  util/
    ClientTasks.java           工作线程 → 主线程
    GuiGeometry.java / Reflect.java / Txt.java
```

### 交互

研究台右侧（放不下时自动切左侧）出现 6 个竖排标签：

| 标签 | 作用 |
| --- | --- |
| 自 | 自动解锁开关（金色边框=开启） |
| 解 / 停 | 求解当前笔记；执行中变成“停” |
| 重 | 用上次求解结果重新放置 |
| 全 | 解锁全部要素 |
| 合 | 要素合成面板（点要素 → `-` / `+` 调数量 → 合成） |
| 设 | 自动解锁 / 完成提示 / 操作间隔 |

底部状态行显示「求解中…」「执行中 3 / 12」与进度条；提示走神秘时代自带的
`PlayerNotifications`（HUD 通知），**不再弹自定义界面**。

## 3. 构建

需要 JDK 8 与 Gradle 2.x（ForgeGradle 1.2 的限制）：

```bat
gradle build
```

> **必须用 `gradle build`，不要用 `gradle jar`。**
> 1.7.10 的开发工作区是 MCP 名（`getMinecraft`），生产环境是 SRG 名（`func_71410_x`）。
> `build` 会跑 `reobf` 把产物重映射成 SRG；`jar` 只产出开发 jar，丢进 mods 会在运行期
> `NoSuchMethodError`。`build` 末尾的 `verifyReobf` 会替你把这道关。

依赖：

- `libs/Thaumcraft-1.7.10-4.2.3.5.jar`（编译期用，发布不打包）
- `src/main/resources/AutoResearch.zip`（原作者的求解器，随 jar 内置）

`downloadClient` / `downloadServer` 默认关闭（官方 1.7.10 下载地址已下线）；
本机 `~/.gradle/caches/minecraft` 缓存完整时可直接构建，需要重新下载时加 `-PforceDownload`。

## 5. 已知限制

- **求解算法没有重写**。`AutoResearch.dll` 是作者提供的 **Windows 原生 PE x64** 程序，
  算法未知，重写有算错的风险；本次只把它隔离成可替换后端（`SolverService`），
  非 Windows 平台会给出明确提示而不是静默失败。
  若以后要移植，替换 `SolverService` 即可，上层 `PlanRunner` 不用动。
- 求解器入参仍通过命令行传递（与原协议一致），因此笔记特别大时会超过 Windows
  32K 参数上限；这种情况会提示「笔记太大」而不是莫名失败。
- 本次改动无法在真实客户端里跑（构建环境只有编译缓存），已通过编译与静态核对：
  FML 1.7.10 的 `GuiScreen` 确实只在 `setWorldAndResolution` 里发 `InitGuiEvent`、
  在 `EntityRenderer` 里发 `DrawScreenEvent`，本模组挂的就是这两个点。
