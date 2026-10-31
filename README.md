# TCAutoResearchUnofficial

神秘时代 1.7.10 研究台辅助：自动求解笔记、解锁全部要素、按需合成要素。
本仓库是 Emil 原作 `TCAutoResearch`（MIT）的非官方续作，由 Mr.Chang 重构与维护。

产物：`build/libs/TCAutoResearchUnofficial-1.7.10-1.0.0.jar`（modid `tcautoresearchunofficial`）

> modid 与原版不同（原版为 `TCAutoResearchByEmil`），两者可以同时安装、互不覆盖配置；
> 但本版不会读取原版的 `config/TCAutoResearch.cfg`，升级时需重新设置。

---

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

## 4. 更新记录

### 1.1.0-r9：浅色要素（灵气等）也能看出颜色

**现象**：上色之后，`灵气 auram` 的图标依然像灰白色。

**原因**：颜色本身太浅。`Aspect.AURA` 的色值是 `0xFFC0FF`，**饱和度只有 0.25**；
而要素贴图是 ~50% 灰的遮罩，两者相乘得到 `(130, 98, 130)` —— 一个灰紫色，
在深色面板上看着就是“没上色”。

**修复**：`AspectIcon.vivid()` 对「亮度 ≥ 0.8 且饱和度 0.08~0.45」的颜色做提纯
（饱和度拉到 0.55），其余原样：

| 要素 | 原色 | 渲染色 |
| --- | --- | --- |
| 灵气 auram | `FFC0FF`（s=0.25） | `FF73FF`（s=0.55） |
| 寒冰 gelum | `E1FFFF`（s=0.12） | `73FFFF` |
| 能量 potentia | `C0FFFF`（s=0.25） | `73FFFF` |
| 灵魂 spiritus | `EBEBFB`（s=0.06） | 不变 |
| 天气/混沌/虚空（纯灰白） | — | 不变 |
| 火/光等本来鲜艳的颜色 | — | 不变 |

纯灰色（`r==g==b`）直接跳过，避免把“混沌/虚空/天气”这种本就无彩的要素强行染色。



### 1.1.0-r8：解法先校验、再呈现（错解自动重解）

**背景**：求解器偶尔给出“看起来对、但永远完不成”的方案——把要素放上去后笔记依然不完成。

**校验依据**（反编译神秘时代服务端 `ResearchManager.checkResearchCompletion`）：

```java
main = 所有 type == 1 的格子（笔记自带的目标要素）
remains = {}
从任意一个 type==1 格出发 DFS：
    对每个相邻格（type >= 1，且双方要素都已被玩家发现）：
        仅当「a 是 b 的组件」或「b 是 a 的组件」时才连通
        若相邻格是 type==1，从 main 移除并继续
笔记完成 <=> main 被清空
```

也就是说：目标要素必须靠“组件关系”的相邻链串起来，空格的要素（type 0）不参与连通。

**实现**：新增 `PlanValidator`，在笔记的**本地副本**上：

1. 应用方案（目标格写入 type=2；若企图覆盖 type=1 且要素不同 → 直接判非法）；
2. 跑上面的连通性算法；
3. 返回 `valid` / 原因（`unknown-hex`、`overwrite-target`、`disconnected:N`、`ok`）。

**流程变化**：

- 求解器每行候选都要先过 `PlanValidator` + `PlanEvaluator`，只有**校验通过**的候选才进入评分；
- 全部候选都不通过 → 自动重解（`SolverRetries`，默认 2 次），仍不通过则提示并放弃，不会把错解丢给玩家；
- 预览面板底部标注 `✓ 已通过研究台规则校验`，只有这种方案才允许点「采用」；
- 「重新解锁上次」同样先校验。



### 1.1.0-r7：方案择优 / 预览确认 / 要素着色

**1）方案择优（少消耗要素）**

求解过程在闭源的 `AutoResearch.dll` 里，本模组做不了“改搜索算法”，但可以做**方案选择**：
求解器一次可能输出多行候选，现在每行都解析成一整套方案，用 `PlanEvaluator` 在本地空跑一遍
（模拟合成链），得到「要合成几次 / 有多少能直接用现成要素 / 每步消耗什么」，再按分数挑最优：

```
preferExisting = 开：先比「需要新合成的数量」，再比合成次数
preferExisting = 关：先比合成次数，再比新合成数量
```

设置面板新增两个开关：`优先现成要素`、`方案预览`。

**2）默认预览模式**

求解完成后不再直接动手，而是：

- 在笔记的**每个格子上画闪烁的要素虚影**（坐标复刻神秘时代自己画要素的位置：
  `guiLeft + 161 + hex.toPixel(9).x`、`guiTop + 75 + ...`），直接看到结果长什么样；
- 右侧弹出「方案预览」面板：`放置 N 个 · 合成 M 次` + 将要消耗的要素图标与数量；
- `采用` / `取消` 由玩家决定；采用后才开始按 tick 分步执行。

要素不够时：`采用` 置灰，面板改为列出**六种基础要素各自的缺口**（把方案里的要素沿合成树
展开到基础要素后减去现有量），并标注为估算值 —— 因为加成要素/中间产物会让实际结果有出入。

**3）要素图标着色**

神秘时代的要素贴图是**灰度遮罩**（实测 `ignis.png` 像素 R=G=B），必须乘 `Aspect.getColor()`
才会上色。`AspectIcon` 现在同时设置 GL 颜色与 Tessellator 顶点色，右侧要素栏、预览面板、
格子虚影都按各自要素的颜色渲染，并支持 alpha（虚影闪烁）。



### 1.1.0-r6：NEI 物品布局恢复 / 合成后不收起面板

**现象 1**：右侧「要素合成」面板展开时，NEI 会把与面板重叠的物品格让开；面板收起后，
右侧物品布局却回不到原样（物品格仍然空缺/错位）。

**根因**（在 NEI 里）：`codechicken.nei.api.GuiInfo#hideItemPanelSlot` 是这么判断的：

```java
for (Object item : gui.buttonList) {           // ← 遍历 GUI 的全部按钮
    GuiButton button = (GuiButton) item;
    if (button.x + button.width  <= rect.x || button.x >= rect.x + rect.w ||
        button.y + button.height <= rect.y || button.y >= rect.y + rect.h) continue;
    return true;                               // 与物品格重叠 -> NEI 隐藏该格
}
```

它**只看坐标，不看 `visible`**。本模组的面板格子是常驻 `buttonList` 的 `GuiButton`，
收起面板时只是置为不可见、坐标仍停在 NEI 的物品格上，于是 NEI 一直认为那些格子被压住，
永久隐藏 —— 布局自然“回不去”。

**修复**：

- 隐藏的控件统一挪到屏幕外（`park()`：`x = y = -10000`），NEI 的重叠判断立刻失效；
- 面板开合后主动请求 NEI 重算一次布局（`NeiBridge`，反射调用
  `ItemPanels.itemPanel.resize(gui)`，没装 NEI 时静默跳过，且在客户端 tick 里执行，
  不碰渲染阶段）。

这样展开面板时 NEI 照旧让开被盖住的格子，收起后物品格恢复；工具条常驻，它压住的
那一列仍会被 NEI 让开（本来就看不见，属于正确行为）。

**现象 2**：点「合成」后面板自动收起。

**修复**：`合成` 只执行合成，不再关面板；面板只由「合」标签（或设置里的“完成”）收起。



### 1.1.0-r5：修复「请求 N 个只合成约 N/2 个」

**现象**：面板写 1 → 出 1；写 2 → 出 1；写 3、4 → 出 2。即实际数量 ≈ `ceil(N/2)`。

**原因**：`PlanRunner` 把同一次合成**算了两遍**。

- 发包时 `pending[要素] += 1`（“已发出、服务端还没回”的增量）；
- 服务端处理后 `live` 也 `+1`；
- 而 `available = live + pending` —— 一次合成让它涨了 **2**。

于是“目标 = 现有 + N”在涨到 `现有 + 2k ≥ 现有 + N` 时就停了，`k = ceil(N/2)`。

**修复**：`pending` 要随着服务端数据回收 —— 新增 `reconcile()`：

```java
// live 涨了 1，就把 pending 里的 +1 抵掉；跌了同理
if (delta > 0 && diff > 0) pending = max(0, delta - diff);
else if (delta < 0 && diff < 0) pending = min(0, delta - diff);
```

`available` 就始终等于“服务端值 + 真正还在路上的增量”，不再重复计数。
另加两道保险：5 秒完全没有同步就从服务端值重新校准；连续两次仍无变化则判定
`no-sync`（服务器拒绝/要素不足）并提示，而不是无限重发。

**验证**：用同一套记账逻辑做了离线仿真（模拟服务端 1/3/8/20 tick 的同步延迟）：

| 请求 | 修复后 | 旧逻辑 |
| --- | --- | --- |
| 1 | 1 ✓ | 1 |
| 2 | 2 ✓ | **1** |
| 3 | 3 ✓ | **2** |
| 4 | 4 ✓ | **2** |
| 5~8 | 5~8 ✓ | 3~4 |

和玩家观察到的现象完全吻合。

### 1.1.0-r4：修复打开研究台崩溃（jar 未 reobf）

**现象**：一打开研究台就崩，`Description: Ticking screen`：

```
NPE: Cannot invoke "GuiContainerManager.updateScreen()" because "this.manager" is null
 at GuiContainer.updateScreen(GuiContainer.java:698)
```

**真正的起因（日志里的第一条异常，不是最终崩溃点）**：

```
NoSuchMethodError: 'Minecraft net.minecraft.client.Minecraft.getMinecraft()'
 at ResearchTableAccess.player(ResearchTableAccess.java:32)
 at ResearchAssistant.attach(...)
 at ClientEvents.onInitGui(...)
```

链条：

1. 上一次打包用的是 `gradle jar`，产物是**开发 jar**（MCP 名 `getMinecraft` / `thePlayer`）；
   1.7.10 生产环境的成员名是 SRG（`func_71410_x` / `field_71439_g`），于是运行期 `NoSuchMethodError`。
2. 我们的监听器在 `InitGuiEvent.Post` 的**第一行**就挂了。
3. FML 的 `InitGuiEvent.Post` 是在 `GuiScreen.setWorldAndResolution` 内部同步派发的，
   异常穿出 → NEI 注入在 `GuiContainer.setWorldAndResolution` 末尾的
   `this.manager = new GuiContainerManager(this)` 被整段跳过。
4. 下一 tick `GuiContainer.updateScreen()` 里 `this.manager.updateScreen()` → 空指针崩端。

**修复**：

- 一律用 `gradle build` 打包（会自动跑 `reobf`），当前 jar 内已确认是 `func_71410_x` / `field_71439_g` / `field_146294_l` 等 SRG 名；
- `build.gradle` 新增 `verifyReobf` 任务并挂到 `build`：扫描产物字节码，若仍含 MCP 名直接构建失败，避免再发出不能用的 jar；
- 所有事件回调（`InitGuiEvent` / `DrawScreenEvent` / 客户端 tick）都加了 try/catch：
  以后即使自身出错，最坏也只是没有面板，**不会再破坏 NEI 的 manager 初始化**。


### 1.1.0-r3：修复「合成数量小于现有数量时没反应」

**现象**：在「合」面板里选中要素、把数量调到 3 再点合成，只要当前拥有量 ≥ 3 就毫无动作。

**原因**：`PlanRunner.forCombine` 把面板上的数字当成了**目标总量**：

```java
if (count > have) { incrementNeed(aspect, count - have); }   // count <= have 时 need 为空 -> DONE
```

而面板（以及原版模组）的语义是**增量**——「再做 N 个」。

**修复**：

```java
// need 表示“保证至少有多少”，目标 = 现有 + 还要做的数量
runner.incrementNeed(aspect, have + count);
```

同时修掉两个连带问题：

- 纯合成 / 解锁全部要素的任务不再要求研究台上必须有笔记（之前合成结束后会因为
  `note == null` 被判定为 `CANCELLED`，连完成提示都没有）；
- 进度条分母改成「还缺多少」，不再出现 `3 / 42` 这种以现有量为基数的怪比例；
  单次任务上限从 600 步放宽到 2000 步，方便一次补几十个深层要素。
- 面板显示改为「合成 N 个」+ 右上角「已有 M」，语义一眼可辨。


### 1.1.0-r2：修复要素图标不显示

**现象**：打开「合」面板后，要素格子里的图标是空白/糊斑。

**原因**：图标当初是用 `Gui.drawTexturedModalRect(x, y, 0, 0, 32, 32)` 画的。这个方法的 UV
按 **256×256 图集** 换算（`u = 像素坐标 / 256`），而神秘时代的要素贴图是
**32×32 的独立贴图**，于是只采样到左上角 `0~0.125` 那一小块（4×4 像素），看起来就是空白。

**修复**：新增 `client/widget/AspectIcon`，和神秘时代自己的 `UtilsFX.drawTag` 一样用
`Tessellator` 给出 `0..1` 的完整 UV；格子图标与面板底部「已选要素」图标都改为走它。
没有贴图的自定义要素退化为一块主色方块。

顺带把「已选要素」的名字显示在面板标题上（`要素合成 · 火`），方便确认选中的是哪一个。

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

## 6. 授权与第三方组件

- **本仓库代码以 MIT 协议发布**，见 [`LICENSE`](LICENSE)。
  原版 `TCAutoResearch` 由 **Emil** 以 MIT 发布，本仓库是其非官方续作，保留原作者署名。
- **不包含神秘时代（Thaumcraft）的 jar**。它是 Azanor 的闭源模组（All Rights Reserved），
  本项目仅把它作为编译期依赖；请自行放入 `libs/`，该目录下的 `*.jar` 已列入 `.gitignore`。
- `src/main/resources/AutoResearch.zip` 是原版模组内置的求解器二进制（`AutoResearch.dll`），
  随原项目 MIT 授权一并保留，本项目未修改其算法，仅把它隔离成可替换后端。
- 本项目与 Azanor、Mojang、MinecraftForge 均无隶属关系；Minecraft 相关素材未包含在本仓库中。

