# 给维护者 / AI 的说明

这个文件记录**每处改动是怎么做到的**：上游为什么会那样、注入点选在哪、为什么不选别的、
以及走过的弯路。面向用户的「这个 mod 能干什么」在 [README.md](README.md)。

改这个仓库之前请先读下面两节（「工作方式」和「注入点约束」），它们是反复踩坑总结出来的，
不是客套话。

## 工作方式

**先看字节码，再动手。** 这个 mod 打的是**编译后的** MTR jar，本地虽然有源码仓库，
但发布版和源码可能不一致（重载、内联、lambda 编号、mapping 层包装）。每个注入点在写代码前
用 `javap` 核对一遍：

```
javap -c -p -s <类文件> | sed -n '/方法名/,/^$/p'
```

系统里**没有 `unzip`**，解 jar 用 `jar xf`。JDK 在
`~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2/bin/`。

**区分「已验证」和「推测」。** 写注释、写提交信息、跟人汇报时都要标。这条不是形式主义 ——
这个项目已经因为把推测当结论吃过好几次亏，最典型的一次：列车重影被判断成 MTR 的问题，
实际是我们自己那个 `@Redirect` 引起的，白查了一轮。

**症状要能反推病因。** 尽量给每个 bug 找一个「如果是 A 就会看到 X，是 B 就会看到 Y」的判别，
写进注释。例如「屏蔽门能开但车门不能开」直接把范围缩到 `partDetails.doorway`；
「门关着就已经错位」是几何问题、「关着正常只是开错侧」是映射问题。

**部署要原子替换。** 客户端在跑的时候 `cp -f` 会截断同一个 inode，JVM 读到半截 jar，
表现是 `ZipException: invalid LOC header`，随后仪表板右键失灵、物品图标消失。
写临时文件再 `mv`（同分区，真 rename，旧 inode 对已打开的句柄仍然有效）：

```bash
cp -f build/libs/yuzimtr-0.1.0+1.20.1.jar "$D/.tmp" && mv -f "$D/.tmp" "$D/yuzimtr-0.1.0+1.20.1.jar"
```

注意用户的 shell 里 `cp` 别名到了 `cp -i`，脚本里写 `/usr/bin/cp -f`。

**改默认值要同时改用户的配置文件。** `Config.save()` 一旦写过盘，**磁盘上的值就压过代码默认值**，
只改代码默认值不会生效（新增字段不受影响：JSON 里没有的键保持字段初始值）。
改了默认值就顺手 patch 一下 `config/yuzimtr.json`，否则会得到「代码明明改了却没效果」的假象。

**崩溃了先分清是谁的锅。** `logs/latest.log` 没有 Java 异常、`crash-reports/` 里最新的一份
时间戳对不上、也没有 `hs_err_pid*.log` —— 那大概率不是 JVM 崩的。用 `coredumpctl list` /
`coredumpctl info <pid>` 看栈，出现 `libgallium` / `radeonsi` / `amdgpu` 就是显卡驱动
自己 abort，跟这个 mod 无关（这套环境是 AMD + Mesa + Distant Horizons + Iris + Sodium，
出过一次）。

**版本号规则。** `gradle.properties` 的 `mod_version`，语义化三位：

| 位 | 什么时候动 |
|---|---|
| 第三位 | 小修小补。**每次 commit 都要动一下**，哪怕只改了一行注释 |
| 第二位 | 做完一个功能（第三位归零） |
| 第一位 | 做完一个大功能。**升之前先问用户**，不要自己决定 |

改了版本号，jar 的文件名跟着变。`./gradlew deployToClient` 会先删掉 `yuzimtr-*.jar` 再拷，
但**手工部署时必须自己删掉旧文件名的那个** —— 同一个 mods 目录里出现两个 `yuzimtr` id，
Fabric 会直接拒绝启动。

## 构建与部署

```
./gradlew build                 # 产物在 build/libs/
./gradlew deployToClient        # 构建并丢进客户端 mods
./gradlew deployToServer
./gradlew deployAll
```

需要 JDK 17。系统只有 JRE 时，`settings.gradle` 的 foojay resolver 会自动下一个。

**缓存寿命是在对象构造时烤进去的，改完要重启客户端** —— 游戏内重载资源包不够，而且有风险
（见下面「模型缓存被回收」一节的代价说明）。

服务端只在改了服务端功能（分交路停站时长 / 最小刷新 / 改停站不刷新 / 锚点防跳 /
网页地图头像 / 立柱碰撞）时才需要重启；纯客户端的改动重进游戏即可。

## MTR3 资源包在 MTR4 里的适配

用户的自定义车全是 MTR3 格式、由 MTR4 自动转换。转换路径和原生 MTR4 资源不同，下面每一条都是那条路径上的坑。

### NTE 部件名前缀 · `stripNtePartNamePrefix`

**症状**：整车不渲染，只剩几个零碎部件；日志有 `[<id>] No floors or doorways found in vehicle models`。

NTE 把 OBJ 部件注册成 `<模型文件名>.obj/<部件名>`
（`mtr-nte` 的 `DynamicTrainModelLoader.java:97`），所以为它写的包在 model_properties 里
写的是 `crt5.obj/xuyin1` 这种名字。MTR4 的 `LegacyVehicleResource.java:280` 把名字**原样**
抄进 `names`，再拿去查一个键是**裸 group 名**的 map —— 全部 miss，连 FLOOR/DOORWAY 也 miss，
所以那条日志是征兆而非原因。

修法：`ModelPropertiesPartMixin` 在 OBJ 版 `writeCache` 入口把传进来的 `Map` 换成
`NtePartNameMap` 包装，只在精确查找失败时取最后一个 `/` 之后的部分再试一次
（也覆盖 NTE 的 `reversed` 变体 `crt5.obj/reversed/xxx`）。本来能用的包不受影响。

不去动 `ModelResourceLoader.loadModel` 返回的 map：`DynamicVehicleModel` 构造函数会对它做
`addPartsIfEmpty(keySet())`，往那里加别名会让没有显式 parts 的模型部件数翻倍、几何体重复渲染。

### 模型缓存被回收 · `keepVehicleModelsResident`

**症状**：离开某辆车超过 60 秒再回来，先猛卡一阵，然后**那辆车的门永久打不开**（站台屏蔽门照常开）。

`MODEL_LIFESPAN` = 60000 在 4.0.5 里有 6 个使用点，全是
`shouldPreload ? Integer.MAX_VALUE : MODEL_LIFESPAN`。其中 `VehicleModel` 的三个构造函数、
`RailResource:21`、`ObjectResource:23` 都在**构造函数里**读 `shouldPreload` —— 而那个字段由
`CustomResourceLoader` 事后才赋值，构造时恒为 `false`，所以三元表达式永远走 60000 那一支，
`preloadResourcePattern` 对它们完全无效。

后果有两层：
- `RenderVehicles.java:222` 每帧调 `iterateModels` → `cachedModel.getData(false)`，
  一被回收就重建 —— 重庆5 的 `crt5.obj` 有 34.93 MB，单次解析约 2 秒。
- 重建出来的 `DynamicVehicleModel` 是**全新的** `ModelPropertiesPart`，而 `mapDoors` 只在
  `VehicleResource` 第 2 层 supplier 里跑一次、**不会重跑** → `partDetails.doorway` 是 `null`
  → `renderNormal` 里 `canOpen` 恒 false → 门永久打不开。

判别依据（屏蔽门能开、车门不能开）：`RenderVehicleHelper` 里给屏蔽门发信号的
`canOpenDoors(...)` 调用点在 `RenderVehicles.java:168`，位于
`vehicleResourceCache.doorways.forEach(...)` **内部**。所以屏蔽门能开就证明
`vehicleResourceCache` 不是 null、`openDoorways` 非空 —— 问题只能在 `partDetails.doorway`。

修法：`CachedResourceMixin` 在构造函数 TAIL 按**值**兜底，lifespan 恰好 60000 的改成
`Integer.MAX_VALUE`。按值而不是按构造函数，是因为逐个堵漏过一次（只改 `VehicleModel` 时
实测仍有 `lifespan=60000 原因=被回收` 的资源）。

**代价**：轨道、摆件、车辆模型全部常驻内存。曾观察到打开后在**游戏内**重载资源包时
`SpriteLoader` 抛 `Image is not allocated`（`NativeImage` 是堆外内存），整套资源包被清空，
`options.txt` 的 `resourcePacks` 被清成 `[]`。**换资源包请重启客户端。**

### OBJ 门部件被翻转两次 · `fixObjDoorDoubleFlip`

**症状**：按车厢奇偶 `reversed` 的包（重庆2 的 crt2，4 节编组的第 1、3 节），开门时该开的一侧
不开、对侧开，且门渲染成对面门的样子；关门恢复。

`ModelPropertiesPart` 两个 `writeCache` 重载对 `optimizedModelDoor` 的处理不对称：
- Blockbench：`addCube(modelPart, 0, 0, 0, false, …)` —— 门模型干净，渲染时
  `translate + rotateYDegrees(180)` 正好翻一次。
- OBJ：`fromObjModels(objModels)` 复用的是已经被 `addObjModelPosition` →
  `objModel.addTransformation(…, flipped)` **就地改过**的 wrapper（supplier 在那之后才求值）。
  门模型里已经烤进一次翻转，渲染时再翻一次 —— **翻两次等于没翻**。

修法两步，缺一不可：
1. 把门部件的 `PartDetails.flipped` 置 `false`，渲染时不再 `rotateYDegrees(180)`。
   `box` 不受影响 —— 构造 `PartDetails` 时 `addBox` 是实参，早已用真实的 flipped 算完。
2. **把 `doorXMultiplier` / `doorZMultiplier` 取反。** 第 1 步把
   `(flipped ? -1 : 1) * multiplier` 里的取反一并去掉了，而那一半是对的（几何镜像后滑动方向
   本来就该跟着镜像）。不补回来的话双开门的左右扇会对调 —— 实测过。

取反后乘积不变，所以 `getOpenDoorBounds`（骑乘碰撞，第 241-242 行）行为一致。

这条对**所有 OBJ 车辆的翻转门部件**生效，不限于 MTR3 转换包。

### 缓存桶错配 · `preloadRealConsistBuckets` / `consistSizes` / `reuseBucketForBogies`

MTR4 按 `(carNumber, totalCars)` 给车辆模型分缓存桶，但 `VehicleResource.java:200` 只在
`extraModelsSupplier == null`（原生车）时才归一化到 `(0,0)`。MTR3 转换车一定带 supplier
（`LegacyVehicleResource.java:252`），所以每个桶都要重新 `new VehicleModel`、重走一遍
`ModelResourceLoader.loadModel` —— 那个方法**零缓存**。

三处错配：
- `CustomResourceLoader.java:164` 预加载只预热 `(0,0)`，游戏里用的是真实桶 → 预热白做。
  修法：按变体后缀推断真实桶（沿用 MTR 自己 `VehicleResource.java:298-299` 的推断）。
  **注意 `LegacyVehicleResource` 里有两个不同的 id** —— 第 55 行给 `VehicleResource` 的是
  `mtr_custom_train_crt5_trailer`，第 252 行给 `VehicleModel` 的是原始的 `crt5`。
- 编组长度要配对：`consistSizes` 按车型正则配，值取自存档 sidings 的 `vehicleCars` 长度。
  **n 节编组的中间车有 n-2 个桶，不是一个**；有的车型同时存在多种编组（crt22 有 2 节和 4 节）。
- `queueBogie`（`VehicleResource.java:233`）硬编码请求 `(0, 1)` 桶，和车体的桶完全不同，
  每帧多一次完整 OBJ 解析。而它只读 `optimizedModelsBogie1/2`，那两份数据在
  `cachedVehicleResourceInitializer` 里与 `carNumber`/`totalCars` 无关 —— 每个桶内容相同，
  所以 `@Redirect` 复用任意一个已存在的桶即可。

### 贯通道尺寸硬编码 · `gangwayOverrides`

`LegacyVehicleResource.java:238-241` 把 `gangwayWidth=1.5` / `Height=2.25` / `YOffset=1` /
`ZOffset=0.5` 写死，完全不看车辆尺寸；而转换时读到的 model_properties 里的 `width` 本身
可能与实际几何不符（重庆5 声明 2，实测车体 2.900），所以没法靠公式推，只能按车配。

几何（`RenderVehicles.renderConnection`）：
```
halfLength = vehicleLength / 2
端面四角 = (±width/2, yOffset[+height] + SMALL_OFFSET, zOffset - halfLength)
```

`hideGangway` 用于「车体自带连接处」的车（重庆5 的 `zjwai1`/`zjnei1`）：把六张贴图置 `null`，
`MainRenderer.java:170` 的 `if (identifier != null)` 会整段跳过。
**不要把 width 或 height 设成 0** —— 那四个面是「本车端面 ↔ 前车端面」之间的四边形，
任何一维归零只会把面积转到另一个方向（width=0 → 中轴线上一整面竖墙，实测过）。

可行走地板不受影响：`GangwayMovementPositions` 是从模型的 floor 盒子算的，
只由 `hasGangway1/2` 这个布尔开关，与尺寸和贴图都无关。所以**不能**用
`disableGangwayPattern` 关贯通道 —— 人会走不过车厢。

### 镜像部件的 box 与几何不在同一侧 · `fixMirroredBoxX`

**症状**：上海磁浮 TR08（S3 线），一个方向两边门全开、反方向两边全不开（都该只开左边）。

MTR4 对同一个 `(x, flipped)` 有两套算法：

| | flipped 时 | 代入 cube∈[1.375,2.390]，x/16 = −3.2625 |
|---|---|---|
| `addCube`（烘焙几何） | 等效 `−x − cube` | [0.872, 1.888] 右侧 ✓ |
| `addBox`（算 box 给 mapDoors） | `−cube + x` | [−4.80, −4.64] 左外侧 ✗ |

x 为 0 时两式一致，所以原生模型和绝大多数 MTR3 包都碰不到；只有 `position.x` 非零的镜像部件
会错开 2x —— 磁浮的 `dr1`/`dr2`（`mirror: true`，`position.x = 52.2`）正是这种。
box 落到对侧后 `mapDoors` 把右侧门分到左侧 doorway：平台在局部左侧时四个门一起动
（看起来两边都开），在局部右侧时右 doorway 开但没有部件映射过去（两边都不开）。

修法：`@Inject` 到 `addBox` 的 HEAD，flipped 时让 x 项跟着一起取反，与 `addCube` 对齐。
**只改 box，几何一个字节不动。**

⚠️ **不要试图改 position 来修这个。** 我走过这条弯路：MTR3 的 `renderOnceFlipped` 有三个
重载，单参数版本（`ModelTrainBase.java:192`）x 恒为 0、看不出取反，据此误判「MTR3 不取反
position」。两参数版本（第 196 行）是取反的：

```java
bone.render(matrices, vertices, -positionX, positionZ, (float) Math.PI, light, …);
```

MTR4 的 position 处理与 MTR3 一致、几何本来就对。改 position 会把几何一起推歪 2x
（实测磁浮的门整排跑到车厢外面）。**判别方法**：门关着就已经错位 → 是几何问题；
关着正常、只是开错侧 → 是 box/映射问题。

### 分侧门条件的字符串不匹配 · `fixLegacyDoorSideConditions`

MTR3 的枚举（`ResourcePackCreatorProperties.java:436`）是

```java
ALL, DOORS_OPEN, DOORS_CLOSED, DOOR_LEFT_OPEN, DOOR_RIGHT_OPEN,
DOOR_LEFT_CLOSED, DOOR_RIGHT_CLOSED, MOVING_FORWARDS, MOVING_BACKWARDS
```

**单数 `DOOR_`**。而 MTR4 的 switch（`LegacyVehicleResource.java:290-300`）匹配的是
**复数 `DOORS_LEFT_OPEN`** —— 那些字符串在 MTR3 里从来不存在，四个分侧条件全部落进
`default: NORMAL` = 无条件渲染。磁浮的门状态指示灯（`door_open` / `door_open2`，stage
`LIGHTS`）因此常亮，开灯和关灯同时亮。

MTR4 的 `PartCondition` 没有分左右的门条件（只有 `DOORS_OPENED` / `DOORS_CLOSED`），
所以只能改写成不分侧的版本 —— 丢了左右区分，但至少尊重门的开关状态。
做法是在 `processModel` 入口直接改输入 JSON 的字符串（幂等，四个 Variation 各过一遍无妨）。

### 不必再配 `preloadResourcePattern`

`config/mtr.json` 的 `preloadResourcePattern` 曾经是必需的 —— 预热真实编组桶挂在 MTR 自己的
预加载调用点上，而那个循环由 `matchesPreloadResourcePattern` 控制。现在 `ClientConfigMixin`
会让 `consistSizes` 里配了的车自动进入预加载，所以那项可以留空。

留空反而更快：`consistSizes` 只列存档里真正在用的车，而 `mtr_custom_train_.*` 会把没用到的
变体（`crt22_nl` 之类）也一起预热。

不配 `consistSizes` 也能用 —— 只是每个缓存桶第一次进视野会卡一次。因为模型现在常驻
（见「模型缓存被回收」一节），**只卡这一次**，不会像原来那样每 60 秒重来。

## 找回 MTR3 的操作手感

### 走到窗边 · `widenFloorsToInterior`

**没有座位碰撞箱这回事** —— 限制来自地板。`VehicleRidingMovement.clampPosition` 把玩家夹在
<b>单个</b> FLOOR 盒子里：

```java
Utilities.clamp(x, floorOrDoorway.left().getMinXMapped(), floorOrDoorway.left().getMaxXMapped())
```

MTR 的模型只在过道铺 FLOOR，座位底下没有，所以过道地板的 x 边界就成了「座位碰撞箱」。

修法：`@Inject` 到 `DynamicVehicleModel.writeFloorsAndDoorways` 的 HEAD/TAIL，
把同一模型贡献的所有 FLOOR 盒子统一撑到最宽那个的 x 范围。车门附近的 floor 通常本来
就到车壁（要能从侧面上车），所以这个自动适配车宽，不用给每种车配数字。
不够就再用 `floorInflateX` 左右各加一点（太大会穿墙）。

floors 是跨模型累加的，所以 HEAD 记长度、TAIL 只处理本次新增那一段。

### shift 短按坐下 / 长按下车 · `shiftToCrouch`

MTR3 在 shift **按下**那一帧切换（`TrainClient.java:159`）：

```java
if (isShifting && !previousShifting) isSitting = !isSitting;
clientPlayer.setPose(isSitting && !detached ? Pose.CROUCHING : Pose.STANDING);
```

但 MTR4 把长按 shift 用作下车，所以不能照搬 —— 否则每次下车前都会先坐一下。
改成在**松手**时判断：`shiftHoldingTicks` 累计不足 `SHIFT_ACTIVATE_TICKS` = 短按 → 切换；
到了阈值就已经走下车逻辑、`ridingVehicleId` 被清零，走不到这里。

阈值 `@Shadow` 上游常量而非硬编码 30，上游改了会跟着变。
姿态每 tick 都要设一次 —— 原版会按输入把姿态改回 `STANDING`。

## 排图与运营（服务端）

### 分交路停站时长 · `perRouteDwellTime`

同一个站台，不同交路停不同时长 —— 编排缓急接续的前提。MTR4 没有这个概念：
`platform.json` 里就一个 `dwellTime`，`routePlatformData.json` 只有
`platformId` + `customDestination`。

**上游在哪丢的**：停站时长在路径生成时就被压平了，`SidingPathFinder.java:109`（TSC）：

```java
path.add(new PathData(rail, endSavedRail.getId(),
    endSavedRail instanceof Platform ? ((Platform) endSavedRail).getDwellTime() : 1,
    stopIndex + 1, position1, position2));
```

到这一层只剩站台，交路信息没了。但 `Depot.generateMainRoute` 构造这些 SidingPathFinder
时手上是有的 —— `platformsInRoute` 里带着 route（`Depot.java:179`），而且
`sidingPathFinders[i]` 与 `platformsInRoute[i+1]` 索引对齐。所以 `DepotMixin` 在
`generateMainRoute` 的 TAIL 给每个 finder 贴 routeId，`SidingPathFinderMixin` 再
`@Redirect` 那一次 `getDwellTime()`。查不到覆盖值就返回原值 —— 这就是真正的继承：
改站台总值，所有未自定义的交路跟着变。

**存哪：挂在 MTR 自己的 `Platform` 上，没有第二份存档。** `PlatformDwellMixin` 给
`PlatformSchema.updateData` / `serializeData` 各加一段，往 MTR 的 msgpack/JSON 里多写一个
`yuzimtrDwellOverrides` 数组。两个 reader 都按键查表，多余的键上游直接忽略，
所以没装本 mod 也能读这份存档（但它保存时会把这个键丢掉）。

注两点：
- 注 `serializeData` 而不是 `serializeFullData` —— 后者默认就是调前者，而客户端↔服务端
  走的是 `Utilities.getJsonObjectFromData`（`Utilities.java:164`，明确调 `serializeData`）。
  注在 `serializeData` 上，落盘和联网一起覆盖。
- 数组**总是**写出去，哪怕是空的。`ReaderBase.iterateReaderArray` 只在键存在时才跑
  clearList（`JsonReader.java:106`），键缺失等于「没提到这个字段」，
  服务端不会清空 —— 那样在 UI 里删掉最后一条覆盖就永远删不掉了。

**为什么不能存进 `PathData.dwellTime`**（那里本来就是每站一个值，而且真的落了盘，
在 `depots/*.msgpack` 的 `path` 键下）：`Depot.generateMainRoute` 每次都
`path.clear()`（`Depot.java:359`）再从 `platform.getDwellTime()` 重算；
`Siding.pathMainRoute` 干脆不持久化，运行时从 `area.getPath()` 拷（`Siding.java:713`）。
那是生成的产物，不是设置。手改能用到下一次生成路径为止。

**UI**：站台仪表板「途径本站台之路线」列表里，每个交路一个秒数输入框 + 一个重置按钮
（`PlatformScreenMixin`）。留空 = 跟随上面的总时长，灰字实时显示继承来的值（跟着滑块走）。
零自定义网络包 —— `PlatformScreen.onClose2` 本来就整个 Platform 发回服务端
（`UpdateDataRequest.addPlatform`），覆盖表挂在 Platform 上就跟着同一个包走了。
上游那个 10px 行距的画名字循环用 `@Redirect` 它的 `routes.iterator()` 空转掉
（render 里只有这一处 `iterator()`，比去挑三个 `drawText` 里的哪一个稳），
名字改由自己按 22px 行距画。

**注意：改完值要重新生成一次该车厂的路径才生效。** 覆盖值是烤进 `PathData` 的 ——
这么设计是为了让运行时停站（`Vehicle.java:324`）和时刻表推算
（`Siding.java:821`、`Siding.java:854`）读到同一个数，
不会出现车停 30 秒而站台屏显按 17 秒算。

### 最小刷新：路径没变就不动车次 · `minimalPathRefresh`

MTR3 刷新路线时，path / timeSegments / platformTimes 照样整个换掉，但**正在跑的车留着**
（`Siding.java`，MTR-3.2.2）：

```java
if (tempRepeatIndex1 != repeatIndex1 || tempRepeatIndex2 != repeatIndex2) {
    trains.clear();
}
```

MTR4 改成了无条件清，而且清得更早 —— `Siding.generateRoute` 的**第一行**就是
`vehicles.clear()`（字节码 invoke[0]，寻路还没开始），
`generatePathDistancesAndTimeSegments` 开头又清一次。
结果是只改一个停站时间也得把全线的车重新等出来，排图没法做。

**做法：两处 clear 都用 `@Redirect` 拦掉，让车带着自己的路径照常跑完整个重新生成过程，
末尾再比指纹，变了才清。** 两个方法里各只有一处 `ObjectArraySet.clear()`（都是 invoke[0]），
注入点无歧义。

之所以能这么干：`Vehicle` **压根不碰** Siding 的 `pathMainRoute` /
`pathSidingToMainRoute` / `pathMainRouteToSiding`（全文 grep 为 0），它跑的是自己那份
`vehicleExtraData.immutablePath`。重新生成期间 Siding 的三个列表是空的也不影响它。

这比「快照 → 清 → 反序列化恢复」小且好，因为那样有两个绕不开的副作用：

- `Vehicle` 有 13 个字段而 `vehicle.json` 只序列化 8 个。`deviation`、`doorCooldown`、
  `stoppingCooldown`、`atoOverride`、`manualCooldown`、`deviationSpeedAdjustment`
  加整个 `vehicleExtraData` 全都会丢。丢 `deviation` 会让车不再追点（永久带偏移跑），
  丢 `doorCooldown` 可能让车提前一个 `DOOR_MOVE_TIME` 发车。
- 重新生成要跨若干 tick，那期间车如果不存在就等于原地冻结，而挂钟照走 ——
  每刷一次攒一点晚点。

**判据比 MTR3 更严也更对。** MTR3 只比 repeatIndex，主线轨道换了而 repeatIndex 恰好
没变时，车的 railProgress 就指到别处去了。车次持久化的状态是
`speed / railProgress / elapsedDwellTime / nextStoppingIndexAto /
nextStoppingIndexManual / reversed / departureIndex / sidingDepartureTime`
（`vehicle.json`），其中只有 `railProgress` 依赖路径 —— 它是沿拼接后路径的距离。
所以判据就是「距离映射没变」：逐段比 `(hexId, stopIndex, startDistance, endDistance)`，
**不比 dwellTime，也不比 speedLimit**（那两个都不影响 railProgress 的含义，
startDistance/endDistance 是几何累加距离）。于是改停站时间 / 改速度等级 / 改信号量都保车，
改轨道几何或寻路结果变了才清。

时序核对过：`Depot.tick` 只在车厂主路径跑完之后才对每条侧线调 `generateRoute`
（`Depot.java:186-196`），而 `Siding.finishGeneratingPath` 又要等两个 SidingPathFinder
都空了才调 `generatePathDistancesAndTimeSegments`。所以一次刷新里它只跑一次，
且那时 `area.getPath()` 已是最终值。

**已知残留**：`timeSegments` 照样重建，所以**已经驶过改动点**的车下一次进站时
`updateDeviation()` 会拿旧的 `sidingDepartureTime` 去比新时刻表，算出一个偏差，
再被 `earlyVehicleIncreaseDwellTime`（压站等点）或
`delayedVehicleReduceDwellTimePercentage`（缩短停站）吃掉。
**还没驶到改动点的车不受影响** —— `getTimeAlongRoute(railProgress)` 在改动点之前逐位相同。
严格排图的话把侧线上那三个自动调整都关掉即可（`earlyVehicleIncreaseDwellTime` 默认是开的，
容易漏）。

### 改停站时间不用刷新路径 · `applyDwellTimeWithoutRefresh`

`VehicleExtraData.createPathData` 拼车次路径用的是 `addAll`，所以 `Depot.path`、
`Siding.pathMainRoute` 和每辆在跑的车的 `immutablePath` 持有的是**同一批 PathData 对象**
（只有 repeatInfinitely 的收尾段是新造的）。`PathDataSchema.dwellTime` 是
`protected final`，`@Accessor @Mutable` 就能写。

于是仪表板一保存（`UpdateDataRequest.update()` 的 RETURN）就把停站时长原地写回去，
运行时停站（`Vehicle.java:324` 读 `currentPathData.getDwellTime()`）立刻变，不用刷新。
遍历是幂等的 —— 每次都从站台重算应有的值再写回，所以不必知道改了哪个站台。

`stopIndex` → 交路的反查用的是和 `DepotMixin` 同一套对齐：第 i 个 SidingPathFinder
产出的到站段带 `stopIndex + 1`，而它的交路来自 `platformsInRoute.get(i + 1)`，
所以 `stopIndex == s` 对应 `platformsInRoute.get(s)`。

**必须连在跑的车一起改。** `SidingPathFinder.generatePathDataDistances` 每次生成路径都会
把所有 `PathData` 换成新拷贝：

```java
final ObjectArrayList<PathData> tempPath = new ObjectArrayList<>(path);
path.clear();
for (final PathData oldPathData : tempPath) {
    path.add(new PathData(oldPathData, startDistance, endDistance));   // 全是新对象
}
```

而最小刷新（第 11 项）又不动车次，所以刷新之后在途的车手上那份 `immutablePath`
是一批**被孤立的旧对象**，已经不在 Depot / Siding 的列表里了。只改列表的话，症状是：
刷新后新发的车按新时间停、老车一直按旧时间跑，**只有把车全删了才恢复** ——
「有没有过掉头轨道」只是「这辆车够不够老、有没有熬过一次刷新」的代用指标。
所以遍历时把每条侧线的 `vehicles` 也走一遍，改它们的 `vehicleExtraData.immutablePath`。

**代价说清楚**：`Siding.timeSegments` / `trips` / `platformTripStopTimes` 不重算 ——
`generatePathDistancesAndTimeSegments` 重复调用不幂等
（`pathSidingToMainRoute.add(pathMainRoute.removeFirst())` 和
`pathMainRouteToSiding.removeFirst()` 每调一次搬一格）。所以**车的实际停站立刻变，
屏显/时刻表的到发推算要等下一次刷新路径才跟上**。而刷新现在不清车（见第 11 项），
随时刷一次即可，代价只是一次寻路。

### 上游 bug：给已被占用的轨道加信号闭塞会互锁

`Rail.reserveRail` 会沿 `connectedRails1` **和** `connectedRails2` 递归，把整个同色
连通分量都标成自己的：

```java
private static void reserveRail(long vehicleId, long color, ObjectOpenHashSet<Rail> visitedRails, Rail rail, boolean currentlyBlocked) {
    if (!visitedRails.contains(rail) && rail.signalColors.contains(color)) {
        (currentlyBlocked ? rail.currentlyBlockedVehicleIds : rail.preBlockedVehicleIds).put(color, vehicleId);
        ...递归两个方向...
```

而 `isNotBlocked` 要求映射里**所有**值都等于自己的 id：

```java
private static boolean isNotBlocked(Long2LongAVLTreeMap blockedVehicleIds, long vehicleId) {
    return blockedVehicleIds.values().longStream().allMatch(blockedVehicleId -> blockedVehicleId == vehicleId);
}
```

正常运行时两辆车不会同处一个闭塞（进闭塞前 `checkAndBlockSignal` 会先确认空闲）。
但**用信号连接器往已经有两辆车的轨道上刷一个新颜色**，就等于在两辆车周围现造出一个闭塞：
两辆车各自把整个分量标成自己的，于是各自都看到对方的 id → 都停 → 互锁。

上游「刷新路径就好了」之所以有效，是因为**刷新会把车全删掉**。
本 mod 第 11 项让刷新不再删车，所以这个土办法也跟着失效了 ——
在已通车的线路上加信号闭塞前，先把那段的车清掉，或临时把 `minimalPathRefresh` 关掉。

`Rail.applyModification` 是原地改 `signalColors` 的（`SignalModification` 走的不是
rails 替换那条路），所以这**不是**引用变旧导致的；`PathData.getSignalColors()` 一直读的是活对象。

### 锚点防跳：时钟跳变不再吞班次 · `stabiliseScheduleAnchor`

**症状**：按游戏时间发车（useRealTime=false）的线路上，车流里出现一个宽度好几个班距的
空洞，绕环线转、不自愈；起点站 PIDS **提前**就显示很长的发车间隔，像是早就知道有洞。
`useRealTime=true` 的车厂从不出现。

**上游机制**（TSC @ 4.0.5）：发车表锚定在「游戏午夜的真实时刻」上 ——
`Depot.generatePlatformDirectionsAndWriteDeparturesToSidings` 在 `Depot.java:283` 取
`simulator.getMillisOfGameMidnight()`、`:304` 写进每条 departure。那个锚 =
`(currentMillis - gameMillis) mod gameMillisPerDay`（`Simulator.java:187-189`），
gameMillis 来自 mod 端每 25 秒一次的 SET_TIME（`Init.java:196,230`，发的是
`(dayTime + 6000) * 50`），而 `setGameTime` 末尾会**重建所有车厂的发车表**
（`Simulator.java:173`）。于是 `/time set`、睡觉跳夜、重启补时都让锚点突变几十万毫秒，
发车表相位跟着平移。

平移方向是回退、且回退量小于单程时间 J 时，被回放的每一班都撞上
`Siding.simulateTrain:315` 的同号在途去重：

```java
if (... && vehicles.stream().anyMatch(checkVehicle -> checkVehicle.getDepartureIndex() == departureIndex)) {
    Main.LOGGER.debug("Already deployed vehicle from {} for departure index {}", ...);   // ← 静默吞掉
}
```

`matchMillis` 是严格单 tick 窗口（`Simulator.java:139-145`），窗口一过永久错过 ——
连续吞掉的班次数 = 回退量 ÷ 班距，这就是洞。PIDS 能「预知」是因为被吞班次在回绕那一刻
就被 `iterateArrivals` 的两个过滤器藏掉（`Siding.java:659` outOfRange /
`:660` missedDeparture），而它们在沿线各站的计划到站还在几分钟到二十几分钟后。

**实证**（8月24日 latest.log，10号线环线，班距 100 秒、单程 ≈23 分钟）：
22:12:41 与 22:21:57 两次 `/time set 0`。第一次让 offsetMillis 1,034,547 → 461,547
（delta −573,000），但相位恰好跨过 2.4M 的双日周期边界，重匹配间隔 1827 秒 > J，
班次 4–8 照常重发、无洞；第二次回退 556 秒 < J，班次 4–8 **连续五班被吞**
（22:23:37 / 22:25:17 / 22:26:57 / 22:28:40 / 22:30:20，发车诊断特征：
`待发=1` + 占号车在线路深处），22:21:01 → 22:32:00 整整 11 分钟全线断发 ——
洞恰好卡在班次 8 和 9 之间，宽约 5.5 个班距，与线上实测的间距比 10,257/2,093 ≈ 4.9 吻合。

**MTR3 为什么免疫**（这是修法的依据，MTR-3.2.2 对照）：

- **真实挂钟 vs 游戏时钟**：MTR3 的判据用 `System.currentTimeMillis() % 86,400,000`
  （`Depot.java:359`，`MILLISECONDS_PER_DAY` 是真实 24 小时），相位锚在
  `lastDeployedMillis` —— 上一次**实际发车**的真实时刻（`:388`）。游戏时钟只用来查
  频率档位（`:383`），碰不到相位。
- **电平触发 vs 边沿触发**：MTR3 每 tick 重查「真实时刻落在本槽区间内且本槽未服务」
  （`:358-373`），条件满足会一直保持到被服务；MTR4 是单 tick 边沿匹配，错过即永失。
- **单调标量 vs 可占用班次号**：MTR3 的去重只有 `lastDeployedMillis` 一个随真实时间
  单调前进的标量（`:370`），车不携带班次身份，不存在「号被占」；MTR4 给每辆车发了
  departureIndex（为了 OBA/GTFS 式的 trip 身份、全天时刻表和晚点显示），
  这个身份正是被时钟回绕欺骗的东西。

三件套（可回绕的锚 + 边沿触发 + 身份去重）缺一不成洞，MTR3 三件全不占。

**做法**：`DepotScheduleAnchorMixin` 用 `@Redirect` 拦 `Depot.java:283` 那次
`getMillisOfGameMidnight()` 调用 —— 全 jar 只有 Simulator（定义）和 Depot（调用）两个类
出现这个名字，方法内只有一处 invoke（字节码 offset 236），影响面封闭。

每个 Depot 各存一份「上次的**原始**锚点 + 累计修正量」，只看**相邻两次原始值之差**
（环形差用 `Utilities.circularDifference`，必须用它 —— 自然过午夜时 offsetMillis 回卷，
直接相减是 ±120 万的假跳变）：差在阈值内就照常跟随（TPS 漂移每 25 秒 ~200ms，
日志里 departure 尾数 947→119 可证）；超过阈值时，全厂无在途车就顺势重新对齐
（无车可撞，回绕只是换号），有在途车则把这一跳计入修正量并打一条 warn，
返回 `raw - 修正量` —— **发车表保持原相位，照常每个班距发一班**。

这里刻意不缓存「上次返回的锚点」。那种写法有个陷阱：一旦拒绝过一次跳变，缓存就被冻死，
raw 继续以 ~200ms/25s 漂走、两者的差越拉越大 —— 既再也跟不上正常漂移，
告警节流也会因为 delta 持续增长而每隔一二十分钟 × 每个车厂重新触发一次。
用「原始值 + 修正量」这个标准的去跳变写法，跳变之后漂移照样跟得上，
告警也天然只在跳变那一 tick 响一次，不需要额外的节流逻辑。

不模仿 MTR3 的「锚到上次发车」：那要和 repeatDepartures 的双日折叠、24 条槽位的稳定编号
共存，相位会随每次发车漂移，反而更扰动去重，且无额外收益。

**验收**：连打两次 `/time set 0`（间隔 5–10 分钟，复现上面的触发），发车诊断里
不应再出现「待发≥1 + 占号车在线路深处」的真拒发，班次号连续递增不回绕；
日志里每次跳变每个车厂恰好一条「已拒绝跟随」。

**代价**：非均匀频率的车厂在一次被拒绝的跳变后，高峰时段和太阳位置错位，直到某次
全厂无在途车时自动重对齐（全天频率均匀则完全无感）。重启后状态清空、以重启后的
第一个锚为准 —— 停机 < 1 小时且存档里有在途车（`vehicle.json` 序列化了
departureIndex）时仍可能撞一次去重；停机 > 1 小时上游自己会清车
（`Simulator.tick:111-112`），窗口很小。

### 侧线车辆最高速 · `vehicleMaxSpeed`

侧线面板上一个 km/h 数（0 = 不限），实际限速 = min(线路限速, 车辆最高速)，时刻表按同一个上限推算。

**限速的唯一来源是 `PathData.getSpeedLimitMetersPerMillisecond()`**，全仓（TSC）只有四处读它：

| 位置 | 作用 | 处理 |
|---|---|---|
| `Siding.generatePathDistancesAndTimeSegments`（:798） | 时刻表推算 → `Trip.StopTime` → PIDS | `SidingTimetableSpeedMixin` 限住 |
| `Siding.getUpcomingSlowerSpeed`（:906，静态） | 前方更慢的段 | 时刻表里**不用改**，车里限住（见下） |
| `Vehicle.simulateMoving`（:421/:426） | 车实际跑的目标速度 | `VehicleMaxSpeedMixin` 两处都限住 |
| `SidingPathFinder`（:45） | 寻路代价（读的是 Rail） | **不改**：同车厂共用一份路径，没法按侧线挑路 |

**不能改 `PathData`**：同一个车厂的所有侧线共用一份路径（`Depot.path`，车次的 `immutablePath`
持有的也是同一批对象），而最高速是按侧线设的，写进去会串到同车厂的其他侧线上。

**时刻表里那处「前方更慢」为什么不用改**：推算里车速从 0 起步、当前段目标又被限住，所以永远 ≤ 上限；
而车速 ≤ 上限时 `min(线路, 上限) < 车速 ⇔ 线路 < 车速`，返回值也一样 —— 加不加上限逐位相同。

**车里为什么两处都要限、而且返回 `min(线路, 上限 / adj)`**：自动分支是
`speedTarget = (upcoming 或 当前段限速) × deviationSpeedAdjustment`，adj 在晚点时 > 1。
要的是**硬上限** `min(线路 × adj, 上限)`（用户定的：追点也不能超过车本身的极限），而注入点只能改
`× adj` 之前那个数，于是返回 `min(线路, 上限 / adj)`。「前方更慢」那处也得限：
它本身不超上限，但乘上 adj 之后可以超。

**只管自动运行**（用户定的）：手动分支读的是 `getMaxManualSpeed`，不经过这两处调用，天然不受影响。

**客户端不用管**：`simulateMoving` 在客户端走 `isClientside` 分支，直接用服务端同步下来的 `speedTarget`。

**从侧线实时读，不拷进车次**：MTR 的加速度是发车时拷进 `VehicleExtraData` 的，只对之后发的车生效。
最高速不跟：「最小刷新」会让在途的车熬过路径刷新继续跑（无限重复的线路上是永远），拷进去就会出现
「时刻表按新上限、老车按旧上限」一直对不上。`Vehicle.siding` 本来就持有（`@Nullable final`），实时读它。

> 顺带：**加速度也有同样的问题**。开着「最小刷新」时，改侧线加速度再刷新，在途的老车会一直按旧加速度跑，
> 而时刻表按新的推。以前（MTR 原版）刷新会清车，所以没暴露。还没修。

**存哪**：`SidingMaxSpeedSchemaMixin` 挂在 `SidingSchema` 的 `updateData` / `serializeData` 上，
多写一个 `yuzimtrMaxSpeedKmh`，做法同分交路停站时长。两点：

- 读用 `unpackLong`（**键存在才回调**），缺键就不动当前值；
- 写**只在显式设置过时**写（`yuzimtr$maxSpeedSet`，含 0）。**不能「总是写」**：服务端处理保存时
  `newData` 是在服务端从客户端 JSON `new Siding(reader, data)` 出来的，本 mixin 在它身上也生效；
  没这个功能的客户端（例如 1.3.0，注册表和 1.4.0 相同能进服）JSON 里没这个键 → `newData` 是 0 →
  总是写就写出 0 → `existingData` 读到键 → **静默清零**。打开侧线面板按 Esc、在车辆选择器里点车型都会触发。
  **分交路停站时长（`PlatformDwellMixin`）是同一个「总是写」模式，有同样的问题，还没改。**
- 最低钳到 10 km/h：时刻表推算内层循环按 1 ms 步进，迭代次数 = 全程毫秒数，1 km/h 跑 10 km 是 3.6×10⁷ 次，
  且改一条侧线重推整个车厂、全在模拟线程上同步跑。

**保存后自动刷新车厂**：保存侧线只是 `existingData.updateData(...)` 原地改字段（`UpdateDataRequest.update`），
**不会**重推时刻表；单独重推一条侧线也不行（`generatePathDistancesAndTimeSegments` 不幂等）。
所以 `UpdateDataRequestMixin` 在 `update` 的 HEAD 给 `data.sidings` 全量记一份最高速（请求里的 `sidings`
列表声明在生成的 schema 上，`@Shadow` 够不着），RETURN 时比对，变了的就对 `siding.area`（Depot）调
`Depot.generateDepots` —— 就是「刷新路线」按钮的后端。开着「最小刷新」时路径几何没变就不清车。

自动刷新让「刷新」从「用户挑时机按」变成「改个数字就发生」，暴露面变大，带出两件事：

- **连着两次刷新会误清车**（「最小刷新」原有的 bug，已修）：`SidingMinimalRefreshMixin` 在 `generateRoute`
  里记旧指纹，原来是无条件覆盖。刷新没做完又来一次时，`generateRoute` 开头已清掉两段侧线路径，记下的「旧指纹」
  是残缺的 → 生成完必然对不上 → 清车。现在刷新进行中不再覆盖，保留第一次之前的基准（在途的车跑的正是那份路径）。
- **非无限重复的车厂仍可能丢车**（没修，刷新本身的行为）：`Depot.generatePlatformDirectionsAndWriteDeparturesToSidings`
  每次重新分配班次，`addDeparture` 按 `departure + journeyTime` 占槽；单程变长 → 能接的班次变少 →
  在途车 `departureIndex >= departures.size()` → `simulateTrain` 移除。无限重复车厂班次数恒 = maxVehicles，不受影响。
  改加速度也一样，只是最高速的杠杆大得多。

`update` 的三处注入都写全描述符 `update()Lorg/mtr/core/operation/UpdateDataResponse;`：这个类里还有一个
`private static update(SerializedDataBase, boolean, …)`，裸名匹配的是 class 文件里第一个同名方法。

**面板**：`SidingScreen` 按固定行号排，减速度是第 5 行。`SidingScreenMaxSpeedMixin` 在 `init2` 的 RETURN
把第 6 行起的 7 个控件各 `setY2(+SQUARE_SIZE)`（`init2` 每次先写绝对坐标，resize 不会越挪越多；
两个停站滑块在父类上，走 `SavedRailScreenAccessor`）；`render` 里 8 个 `drawText` 描述符相同，
一个 `@Redirect` 全接住，y 在第 5 行标签之下的 +SQUARE_SIZE。`onClose2` 的 HEAD 把值写进 Siding，
原方法末尾才构造 `PacketUpdateData`，所以跟着同一个包走，零自定义网络包。输入框的 `setText2` 放在
`setPositionAndWidth` 之后（见站台广播器一节那个回显的坑）。

## 站台设施：升降式绳索屏蔽门

代码在 `psd/`（双端）和 `client/`（渲染与判定）。三个方块：绳索 `rope_barrier`、
带 PIDS 的立柱 `rope_barrier_post`、纯白立柱 `rope_barrier_post_blank`。

**为什么要它**：同一个站台停不同门距的车时，固定门位的屏蔽门没法用。现实里的解法是绳式屏蔽网 ——
整排一起升降，不区分门位。

### 整排同步是全部意义所在

MTR 原生的 `RenderVehicleHelper.canOpenDoors` 只把门开度推给**车门正对的那一块**方块，
所以门位必须固定。`RenderVehicleHelperMixin` 在它的 RETURN 之后用**同样的四个角点**
重算一遍 doorway 的世界 AABB，扫到绳网方块就沿整排推门开度并强制返回 `true`。

「沿整排」要能**穿过立柱** —— 一排真实的绳网是 `立柱-绳-绳-绳-立柱-绳-绳-立柱`，
泛洪只认绳索的话立柱另一侧永远收不到开度。`RopeBarrierPart` 这个空接口就是「属于同一排」
的标记，`client/RopeBarrierFlood.pushAlongRow` 靠它导通。

### 双高方块，不是单高

MC 的 `BlockCollisionSpliterator` 只遍历 `floor(minY − 1e-7) .. floor(maxY + 1e-7)`，
**没有按方块形状扩展遍历范围**。所以超出自己格子的碰撞盒，在实体的扫掠盒离开那一格之后
就被整个跳过 —— 症状是「第一次被拦下，第二次起跳就卡进地里再穿过去」。
MTR 自己的屏蔽门门扇是 `BlockDirectionalDoubleBlockBase`、每半不超过 16px，同一个原因。
所以绳网和立柱都做成双高，每一半只在自己格子内出盒子。

绳索的碰撞**服务端返回空**、只在客户端按门开度切换（门开度是客户端渲染阶段才有的量）；
立柱是实体结构，两端都挡人。

### 立柱碰撞占满整格（选择框仍贴合模型）

柱身只有 7px 厚，玩家能从柱子和车厢之间挤进去，然后贴着车身在站台与列车的缝里一路穿行 ——
车厢侧面的隐形墙拦得住「横穿」，拦不住「已经在缝里、沿着缝走」。
所以 `getCollisionShape2` 用整格，`getOutlineShape2` 保持贴合模型。

### 时序

- 绳索上升速度 = 车门速度 × `ropeBarrierSpeedMultiplier`
- 关门前 `ropeBarrierCloseLeadMillis` 绳网就已经完全落下
- 落下前 `ropeBarrierWarningLeadMillis` 开始响 `ropeBarrierWarningCount` 声提示音

`RopeBarrierBlockEntity` 里有 `armed` 守卫和倒计时闩：只有最近 1 秒内收到过「门全开」
的推送才算 armed，避免刚加载进来的方块凭空开始动。**方向是黏性的** ——
`pushAlongRow` 每帧每个 doorway 都会跑一次，开度相等时会落进「正在开」分支把「正在关」
覆盖掉，所以要记住上一次的方向。

音效用 `ClientWorld.playSound` 的**坐标重载**：带 `PlayerEntity except` 的那个重载
只在 `except == 本地玩家` 时才出声，用错了就完全听不见。

### 模型与 PIDS

`tools/gen_psda_pack.py` 从 RailwayAesthetics-Future 派生模型到
`assets/mtrsteamloco/eyecandies/yuzimtr_psda/`。纯白立柱把原来那个 26 面的凹槽换成一张平面 ——
**记得给它 `vn` 法线**，漏了会渲染成不受光的样子，看着像半透明材质。

PIDS 复用 JCM `DepartureTimerRenderer` 的那套取数：`ArrivalsCacheClient.INSTANCE.requestArrivals`
+ `InitClient.findClosePlatform` + `getMillisOffset()`。四行布局：线路名 + 右对齐的到达时间 /
开往终点站 / 前方依次到站 / 后续站点，多语言轮播模仿 MTR 自己的 PIDS。

### 许可

模型资源派生自 AGPL-3.0 的 RailwayAesthetics-Future，所以本 mod 整体也是 AGPL-3.0。

## 上游 bug：开光影后方块实体渲染的东西整个消失 · `fixShadowPassEatingRenderQueue`

**症状**：开光影后 MTR 的屏蔽门门扇、PIDS 文字整个不见（自己写的绳网还在）。

`MainRenderer.render` 用双缓冲：`CURRENT ← RENDERS`，清空 `RENDERS`，再绘制 `CURRENT`。
方块实体渲染器通过 `scheduleRender` 往 `RENDERS` 里塞，而原版**实体先于方块实体渲染**。
开光影时这个方法每帧被调两次：阴影 pass 那次先把队列搬走并清空，轮到主 pass 已经空了。

**第一版修法是错的，别再走一遍**：当时用 `@Redirect` 让阴影 pass 跳过 `RENDERS.clear()`。
但车辆和轨道是在 `MainRenderer.render` **内部**调度的（`:112`、`:114`，在 `:116-122` 的交换之前），
而阴影 pass 里 `millisElapsed = 0` 不推进位置、主 pass 才 `simulate`。于是主 pass 的队列里
同时有「阴影 pass 那份旧位置」和「自己这份新位置」，两份差一帧行程一起画出来 ——
**列车一跑就是两个车叠着**，停站时重合看不出来。

现在的做法：阴影 pass 开始前**快照** `RENDERS`（值列表也要复制），这次调用结束后**还原**。
阴影 pass 照常消费方块实体的队列，主 pass 又能拿到同一份；两个 pass 各自在 `render` 内部
调度的东西只属于自己那一次。`@Inject` 打在 RETURN 会命中所有 return 点，包括
`disableShadowsForShaders` 打开时的提前 return —— 那种情况下 `RENDERS` 没被清过，
还原是把同样的内容放回去，无害。

**影子要生效还得关掉 MTR 自己的 `disableShadowsForShaders`**（`config/mtr.json`）。
`MainRenderer extends EntityRenderer<EntityRendering>`、挂在一个虚拟实体上（`InitClient.java:192`），
所以它画的东西能进阴影 pass —— MTR 自己的轨道和车辆 OBJ 就是这么投影的，方块实体渲染器走不到。

`OptimizedRenderer.renderingShadows()` 内部是 `Thread.currentThread().getStackTrace()` 再逐帧比
`net.irisshaders` / `net.optifine` 前缀（字节码已核实），**一次 render 调用里只算一遍**，
别每个对象都调。

## 车厢侧面隐形墙 · `boardOnlyThroughDoors`

MTR4 做了「只能从车门下车」，但**没做「只能从车门上车」** —— 在非门位置往前走既不会上车、
也不会被挡住，直接穿过车体。配上整排升降的绳网就变成直接掉进轨道。

### 轮廓在渲染阶段采集

车厢位置只在渲染里推进（`MainRenderer.render` 调 `vehicle.simulate`），「哪些门开着」也只在
渲染里由 `canOpenDoors` 逐 doorway 判定，tick 阶段没有第二份实现。
`VehicleHullCaptureMixin` 挂在 `VehicleRidingMovement.movePlayer` 的 HEAD ——
那是**唯一**一个「渲染阶段、每节被渲染的车都调一次、参数里同时有车厢位姿和当帧开门状态」
的地方，而且它在非乘车分支第二行就 return，读参数没有副作用。

客户端 tick 与 render 同线程交替，缓存最多旧一帧。250ms 过期：车不再被渲染就没有墙，
退回没这个功能时的行为，绝不会留一堵看不见的墙。

`movePlayer` **必须写全描述符** —— 同类里还有 `private static void movePlayer(double,double,double)`。
参数顺序按字节码核实：`(millisElapsed, vehicleId, carNumber, floorsAndDoorways, ×3 gangway, pose)`，
第一个 long 被乘 `0.005f` 当移动速度，第二个 long 喂给 `isRiding(J)Z`。

### 夹的是位移，不是位置

**第一版修法是错的**：在 `startRiding` 入口 `updatePosition` 把人推回来，那是**事后纠正** ——
原版碰撞早就把人挪进车体、相机也进去了，下一帧才推出来；而且 `Entity.updatePosition` 不改
`prevX/prevZ`，相机在本 tick 内按 prev→now 插值，看起来就是「先陷进去再弹出来」。

现在挂在 `ClientPlayerEntity.move` 的 HEAD，用 `@ModifyVariable` 在位移被应用**之前**改写它。
把位移变换到车厢局部坐标系，**只夹法向（局部 X）分量、保留切向**，所以贴着车身走是滑行不是卡死。
旋转和俯仰由 `transformBackwards/Forwards` 一起处理，不需要拼 AABB。
**只在「起点在轮廓外、终点在轮廓内」时才夹** —— 墙只管拦越界，永远不会把人困住。

`ClientPlayerEntity` 只有一个 `move`（覆写 `Entity.move`），`argsOnly` 的 `@ModifyVariable`
唯一命中那个 `Vec3d`。不选 `Entity.adjustMovementForCollisions`：三个同名重载，而且是 `Entity`
级别的，会波及掉落物、生物、其他玩家 —— 那些实体在别人客户端上没有这堵墙，位置立刻分歧。

**这是本仓库第一个以原版类为目标的 mixin**，`remap` 走默认 `true`，refmap 从此才有内容
（已确认生成 `class_746.method_5784` 的映射）。

### 墙面要略宽于车厢

墙面定在 `VehicleCar.getWidth() / 2 + 0.3 + boardingWallExtraWidth`。
那个 `+ boardingWallExtraWidth`（默认 0.5）不是可有可无的：`getWidth()` 是车辆配置里**声明**的
宽度，对 MTR3 转换来的自定义车常常比模型实际车宽窄不少（实测某车声明 2.0，而门盒外沿就已经
在 1.25）。窄了有两个后果：贴着墙时第一人称视角穿进车皮；更要命的是墙面落在站台边缘之外、
车厢下方那条空隙上方，贴着墙滑行时人一直在往下掉，滑到门口时 Y 早就掉出上车判定的 ±0.75 容差。

**调整判据**：贴着墙站着不掉、视角不穿车皮。

### 「墙的洞」必须和「上车触发面」重合

`StartRidingWidenMixin` 把喂给 `startRiding` 的门盒朝车外拉到 `halfWidth + 0.1`。
不拉的话，门盒外沿还在车皮里面，中间隔着站台与车之间那条缝：玩家一迈出站台边就悬空，
空中几乎没有水平加速能力，于是基本原地掉进轨道，走不到能触发 `startRiding` 的位置。

注意**我们并没有清零玩家的速度** —— `@ModifyVariable` 改的是传给 `move` 的位移参数，
而原版只在真的撞上方块时才清 `velocity`。所以贴着墙时残余的向内速度一直留着，
门口一放行就会被带进去。这既是「一迈出去就飘进缝里」的原因，也意味着任何漏掉的缝
（比如车厢连接处没被墙盖住）都会被这股速度直接穿过去。

安全性：普通玩家拿到的那个列表里**只有开着的门** —— 地板要持 `canBoardAnyVehicle` 驾驶钥匙
才会被加进去（`RenderVehicles.lambda$render$10` 里先查 `getValidHoldingKey`），
所以「只能从车门上车」的限制不受影响。

**拉宽必须用「玩家正朝车里走」做闸。** 位置分不出「进」和「出」：刚下车的人站的地方和贴着墙
准备上车的人是同一片区域，拉宽的门盒会把他当场吸回车里 —— 症状是「从门口下不了车，卡在墙处」。
MTR 自己没有再上车冷却可用（`ridingVehicleCooldown` 是「车还在被渲染」的看门狗，每帧被
`movePlayer` 清零），原版靠门盒够窄、一步就走出去。所以 `VehicleHullCollision` 每 tick 记一次
「玩家请求的位移在朝这节车的中线走」，`widenForBoarding` 只在 300ms 内有这个记录时才拉宽。
乘车中的车在夹取循环开头就 `continue` 了，下车那一刻不会留下记录。

## 上游 bug：走行音重连后整体消失 · `fixLegacySoundThrottle`

**症状**：同一个客户端进程里重连之后，部分车的走行音消失，玩一会又自己回来，看不出契机。

`InitClient.canPlaySound()`：

```java
if (getGameMillis() - lastPlayedTrainSoundsMillis >= 200) {
    lastPlayedTrainSoundsMillis = getGameMillis();
}
return getGameMillis() == lastPlayedTrainSoundsMillis && !isPaused();
```

`gameMillis` 在客户端加入服务器时被归零（`registerClientJoin` 的回调里 `lconst_0; putstatic`），
`lastPlayedTrainSoundsMillis` **没有跟着归零** —— 全类只有 `<clinit>` 和 `canPlaySound` 自己写它。
于是重连后 `0 - 很大的数` 是负数不更新、`0 == 很大的数` 为假，**`canPlaySound()` 恒返回 false**，
直到 `gameMillis` 爬回旧值。静音时长 ≈ 本进程里最长那段旧连接的时长 —— 所谓「玩一会又好了」，
契机不是玩了什么，是时间走够了。MTR3 用的是只增不减、从不归零的 `gameTick`，是升级引入的回归。

**为什么只有走行音、只有部分车**：`canPlaySound` 全 mod **只有一个调用点** ——
`LegacyVehicleSound.playMotorSound`。门音走 `playDoorSound → ScheduledSound.schedule`，不经过它；
`BveVehicleSound` 里零命中。所以用户看到的「自定义包的车没事」其实是**「BVE 音效的车没事」**，
差异不是自定义 vs 内置。

修法：入口检测「上次播放时间戳跑到了当前时间前面」这个不可能状态就清零。自愈式，
不依赖上游 lambda 编号或注册顺序，上游哪天自己修了这个判断也永不成立。

## 上游 bug：音效被重复播放并相干叠加 · `fixScheduledSoundDuplication`

**症状**：走行音响度很大、音质极差，像严重削波。

`ScheduledSound.play()` 的循环体 lambda **从头到尾没用过自己的参数**
（字节码 `lambda$play$0(ClientWorld, ScheduledSound)` 里只有 `aload_0`，那个 `ScheduledSound`
参数一次都没被 load），用的是 `this`：

```java
SCHEDULED_SOUNDS.values().forEach(scheduledSound ->
    world.playSoundAtBlockCenter(this.blockPos, this.soundEvent, ..., this.gain, this.pitch, false));
```

而 `playScheduledSounds()` 又是 `values().forEach(ScheduledSound::play)`。两层一乘：
**N 个待播音效播 N² 次，每个音效自己被播 N 份**，同帧同坐标同增益同音高完全相干叠加，
幅度 ×N 过载。`schedule` 的去重键是 `"<音效id>_<gain>_<pitch>"`，而走行音的 gain/pitch
随速度连续变化，多节车速度稍有差异就各占一个键，N 越大炸得越狠。

修法：`@Redirect` 把 `play()` 里那次 `forEach` 换成只调一次。**传 `this` 而不是 `null`** ——
现在的 lambda 不看参数，传什么都一样；万一上游哪天改成真的用参数，传 `this` 依然是正确语义
（`playScheduledSounds` 本来就已经逐个调 `play()`），传 `null` 会 NPE。
`@Redirect` 限定在 `play()` 里 —— `playScheduledSounds()` 自己那次 `forEach` 描述符完全相同，
靠 `method` 限定区分，那一次是对的、不能动。

**修完 legacy 走行音会明显变小**，回到 MTR3 的响度，这是预期内的。

## 交路的「关掉下一站播报」只静音讲述人 · `routeFlagMutesNarratorOnly`

配合自制 TTS：语音自己来，聊天栏的下一站文字要留着。

**上游为什么是「一起关」**：`VehicleExtension.simulate` 里那个回调把两样东西攒在同一个
`if (!routeIdHasDisabledAnnouncements)` 里，最后一起交给
`IDrawing.narrateOrAnnounce(narrateMessage, chatMessages)`。而那个方法**本身已经支持
「只发聊天不念」**——`narrateMessage` 为空就跳过 `Narrator.say`，只是它读的是 MTR 的全局
TTS 开关 `Config.getClient().getTextToSpeechAnnouncements()`。

**做法**：`@Redirect` 掉 `PacketCheckRouteIdHasDisabledAnnouncements.runClient()` 里唯一那处
`Consumer.accept`（字节码 offset 40），把 `true` 改成 `false` 放上游进去生成聊天栏文字，
同时举起 `NarratorGate`；`ClientConfigMixin` 里让那个 getter 在旗子举起时返回 `false`。
旗子必须放在 `finally` 里降下 —— 漏了会让讲述人**永久**失声。

**必须认调用方。** 这个包有两个使用者：除了报站，`EditRouteScreen` 打开时也用它回填复选框
（`new PacketCheckRouteIdHasDisabledAnnouncements(data.getId(), this::setDisableNextStationAnnouncements)`）。
不加区分地把 `true` 改成 `false`，**仪表板里的勾选框会永远显示未勾选**。判据是回调 lambda 的
宿主类名 —— 比按 `lambda$xxx$N` 注入稳，那种名字会随上游改动重新编号。
隐藏类名的形态随 JDK 变（JDK 17 是 `...VehicleExtension$$Lambda$N/0x...`，
JDK 21+ 是 `...VehicleExtension$$Lambda/0x...`），但**前缀都是宿主类名**，
所以用 `startsWith` 两种都覆盖。认不出来就原样放行，退回上游行为。

链路是**全程客户端主线程且同步**的（`runClient` 经 `class_1255.execute` 派发，
到 `narrateOrAnnounce` 之间没有任何二次 `execute`），所以一面普通 static 旗子够用。
全 jar 里只有 `IDrawing` / `Client` / `ConfigScreen` 三处读那个 getter，窗口内不会误伤。

## 广播器的全量交路通道 · `announcerAllRoutes`

让广播器能选到**所有**交路：不限距离、包含隐藏交路。

**上游的限制是双重的，都在 TSC 服务端**：
- `DataRequest.addPlatform` —— 只有落在客户端请求半径内的站台才会带出它的交路，
  且 `if (!route.getHidden() && ...)`；
- `SimplifiedRoute.addToList` —— 再过滤一次 `if (!route.getHidden())`。

**为什么不直接放宽那两处**：客户端那份 `simplifiedRoutes` 不是广播器专用的。
`RouteMapGenerator.getRouteStream` 只按「这条交路停不停本站台」筛
（`getPlatformIndex(platformId) >= 0`），而 `SimplifiedRoute` **没有 hidden 字段**，
客户端根本分不出来 —— 一旦灌进隐藏交路，它们就会出现在途经站台的线路图和 PIDS 上，
正是「隐藏」要防的事。所以另开一条通道，MTR 那份共享数据一个字节都不动。

**怎么取全量**：MTR 模组侧不持有 TSC 的 `Simulator`，一切经
`Init.sendMessageC2S(操作, ...)` 转给模拟器线程。现成的 `LIST_DATA` 正合用 ——
它落到 `new ListDataResponse(jsonReader, simulator).list()`，而 `list()` 是
`routes.addAll(data.routes)`：全量、不过滤隐藏、不看半径，仪表板用的就是它。
请求体内容会被忽略，送一个空 `SerializedDataBase` 即可（空 JsonObject → `unpack` 取 null →
`unpackValue` 的 `ifnull` 守卫，不会 NPE）。回程是**进程内直接传对象**、不序列化
（`QueueObject.runCallback` 只做一次 `isInstance` 转型），开销可忽略。
回调包在 `minecraftServer.execute` 里，发包安全；玩家掉线时 `ClientConnection.send`
走 `isOpen()==false` 分支入队，不抛异常。

**注入点都避开了 lambda**：候选列表原本在 `TrainSensorScreenBase` 的按钮回调里现攒，
改到 `DashboardListSelectorScreen` 的构造函数上做。5 参构造函数转发给 6 参那个，打 6 参覆盖两条路。
选 `RETURN` 而不是 `HEAD`（构造函数注入 HEAD 有限制），因为 `allData` 要到 `init2()` 末尾的
`updateList()` 才被读，构造函数返回时改还来得及。`allData` 是 `protected final`，
所以 `@Shadow @Final @Mutable`。

**只认广播器**：`TrainSensorScreenBase` 被三个界面共用（广播器、基础列车感应器、
列车时刻表感应器），这里只认 `TrainAnnouncerScreen`。要放开把那个 `instanceof` 去掉即可。

**已选交路不会因为列表变小而丢**（核实过）：`updateList` 只读，`onDelete` 走 `rem(id)`，
`onClose2` 发的是同一个 `LongAVLTreeSet`，服务端 `setData` 原样 `clear + addAll`。
配置不会被改写。

不过有两处「看不见」，是两套不同的机制，别混为一谈：
- **选择器的「已选」一侧**：`updateList` 是拿 id 去 `allData` 里 `findFirst().ifPresent`，
  找不到就不往列表里加。所以答复未到、退回上游那份「附近的」列表时，
  已选的远处/隐藏交路在这一侧不显示。
- **广播器主界面的交路清单**：`TrainSensorScreenBase.render` 对查不到的 id 是
  `ifnull` 跳过绘制，结果是**画成一行空行**，不是不占位。

## 站台广播器 · `station_announcer`

新方块 `yuzimtr:station_announcer`。逻辑全在 `station/StationAnnouncerBlockEntity`，
界面在 `client/StationAnnouncerScreen` + `client/AnnouncerRuleScreen` + `client/SoundPickerScreen`。

### 为什么不能复用 MTR 自己的广播器

`BlockTrainAnnouncer` 是**车载**广播器，三条限制全都挡路
（触发点在 `data/VehicleExtension.java:209-222`）：

| 限制 | 位置 |
|---|---|
| 触发条件带 `VehicleRidingMovement.isRiding(id)` —— 只有车上乘客会触发 | VehicleExtension:222 |
| `clientPlayerEntity.playSound(…, 1000, 1)`，音量 1000 是贴耳播放 | BlockTrainAnnouncer:103 |
| 触发点是车头 3×3×3 扫方块，没有提前量 | VehicleExtension:209-217 |
| `ANNOUNCE_COOLDOWN_MILLIS = 20000` | BlockTrainAnnouncer:44 |

### 时间基准：两个字段，和站台上的显示设备同源

```
ArrivalResponse.getArrival()    ← PID 那个「X 分/X 秒」的口径（RenderPIDS.java:177）
ArrivalResponse.getDeparture()  ← JCM 发车倒数器的口径（DepartureTimerRenderer 反编译确认）
```

TSC 侧的来源是 `Siding.getArrivals`（字节码）：
`arrival = stopTime.startTime + deviation`、`departure = stopTime.endTime + deviation`，
所以 departure 就是「离开本站台的时刻」，含停站时间。两者都要减 `getMillisOffset()`
才是本地时钟。

**都是绝对时间戳**，所以到站表一秒才刷一次并不影响触发精度 —— 刷新率只影响预测的新鲜度。

### 为什么整套逻辑在服务端

站台广播必须所有人同时听到同一声。服务端拿到站表没有障碍：`ArrivalsCacheServer` 本来就存在，
且 `Init.java:234` 在 `registerStartServerTick` 里**无条件**每 tick 调 `tickAll()`，
不需要有人打开 PID 才有数据。注意这个服务端缓存和 MTR 自己的 `PacketFetchArrivals`
（客户端 PIDS 的数据源）**是同一个实例**。

`requestArrivals` 每 tick 调，是因为它同时是**读缓存的入口**；至于把站台续在请求队列里，
`PERSISTENT_AGE = 5` 数的是「请求次数」而 `tick()` 每 1000 ms 才发一次，所以那一半
其实每 5 秒调一次就够。`ServerWorld.cast(world)` 能从 `blockEntityTick()` 的 `World` 拿到。

方块实体的 tick 是 `BlockWithEntity.getTicker`（mapping 的 default 方法，字节码确认
它永远返回一个调 `blockEntityTick()` 的 ticker）驱动的，两端都跑，所以开头要
`if (world.isClient()) return`。

### 「限制范围」那档的音量不能用用户设的值

`ServerPlayerEntity.playSound` 编译到 `ServerPlayer.playNotifySound`，它发的是一个
**定位在玩家当时坐标**的音源，不是「贴耳播放」。客户端 `SoundEngine` 的衰减距离是
`max(volume, 1) × 16` 格、增益是 `clamp(volume × 分类音量, 0, 1)`。

所以传 1 的话衰减距离只有 16 格 —— 十几秒的报站放到一半，人走出 16 格就静音了。
这一档固定传 1000：增益照样被钳到 1，只是把衰减距离拉到听不出衰减。
MTR 自己的 `BlockTrainAnnouncer` 传 1000 正是这个道理。

另外 `Box(BlockPos, BlockPos)` 直接拿整数坐标当 min/max、**大角不加 1**（原版构造函数字节码），
而 `contains` 判的是 `< max`，所以照直传两个角点的话站在「坐标 2」那一格上算框外。
`rangeBox()` 显式给大角 +1。（JCM 的声音循环器有同样的毛病，没跟。）

### playSound 的两个重载语义相反，别照抄绳索屏蔽门

```
World.playSound(PlayerEntity, BlockPos, …)  → class_1937.method_8396 = Level.playSound()
        服务端：广播给周围所有人，第一个参数是「**除了**他」，传 null = 所有人
World.playSound(double,double,double, …)    → class_1937.method_8486 = Level.playLocalSound()
        **服务端是空实现**，只有 ClientLevel 覆写了
```

`RopeBarrierBlockEntity#playSoundOnChange` 用的是后者（它是纯客户端渲染态），
站台广播器用前者。两边参数语义正好相反。

**资源包里的音效服务端并不认识，但照样能广播**：`SoundHelper.createSoundEvent` 编译到
`SoundEvent.createVariableRangeEvent`，造的是**未注册**的 SoundEvent；1.20.1 的
`ClientboundSoundPacket` 用 `Holder<SoundEvent>` 编码，未注册的走 `Holder.direct`，
把 id 原样发给客户端自己去 sounds.json 里查。JCM 的「声音循环器」线上一直这么跑。

广播半径 = `SoundEvent.getRange(volume)`，即 `volume > 1 ? 16 * volume : 16` 格。

### 音频时长：MTR 没有这个能力，自己读 ogg

上游遇到同样问题时的答案是让用户手填（`sound/BveConfigFile.java:49` 的
`doorCloseSoundLength`），`SoundManager` 也只管播放不暴露时长。

`client/OggDuration` 直接读容器、不解码：首包里找 `0x01 "vorbis"`，偏移 +12 读 4 字节小端
采样率；从文件尾倒着找最后一个 `"OggS"`，偏移 +6 读 8 字节小端 granule position；相除即秒数。
拿 MTR-TTS 的 481 个成品 ogg 全量验过。

两处必须注意的细节：

- **Ogg 不转义 capture pattern**，音频数据里出现 `"OggS"` 这四个字节完全可能。所以还要校验
  version 字节为 0、headerType 带 EOS 位（0x04）—— 反正我们要的就是最后一页。
  （加上校验后 481 个文件的结果一个都没变，说明只挡掉了误命中。）
- **只读头 4 KB + 尾 64 KB**，用滑动尾窗流式读，不要整个文件读进内存 ——
  音效列表里什么都有，选到一个几十 MB 的 BGM 会在渲染线程上卡一下。
- `granule` 为 -1（`0xFFFF…`）表示「该页没有结束任何包」，只有截断文件才会出现在末页，
  当作量不出来处理。

链路：`SoundManager.get(Identifier)` → `WeightedSoundSet.getSound(Random)` → `Sound.getLocation()`
（= `sounds/<path>.ogg`）→ `ResourceManager.getResource`。**只对 `RegistrationType.FILE` 有意义**，
`type=event` 的条目指向另一个音效。

**时长必须存进方块实体**，因为服务端没有资源包，触发时只能用存下来的那个数。
界面上留了手改的输入框兜底。

### NBT 存的是平行数组，不是嵌套结构

mapping 的 `CompoundTag` 只暴露 `putInt/putLong/putString/putXxxArray`，
**没有 `putCompound`、也没有任何列表标签**（javap 确认）。所以规则列表拆成
`rule_platform_ids` / `rule_route_ids` / `rule_offsets` / `rule_durations` / `rule_flags`
五个平行数组，音效 id 用 `'\n'` 连成一个字符串（资源 id 的合法字符集里没有换行）。
读回来时 `split("\n", -1)` —— **`-1` 不能省**，否则最后一条没配音效的规则会和前面错位。

### 去重：身份必须落在「时刻」上，不能落在 departureIndex 上

**`departureIndex` 不是班次的唯一身份**（TSC 字节码核实，这一点前后踩了两次）：

| 事实 | 出处 | 后果 |
|---|---|---|
| 它是**本 siding** 的 `departures` 列表下标，每条 siding 各自从 0 数 | `Siding.lambda$iterateArrivals$21` 的外层循环变量；`Depot.generatePlatformDirectionsAndWriteDeparturesToSidings` 按 siding 分配 | 一个车库 4 条 siding 各停一列车 → 4 列车的 departureIndex **全是 0**，而 `ArrivalResponse` 里没有 siding id |
| 车库勾了「无限重复」时，同一个 StopTime 会按 `repeatInterval × 周期序号` **发出多条**到站记录 | `lambda$iterateArrivals$21` 的内层循环（字节码里那段 `lmul` + `ladd`） | 到站表里同时存在多条同 `(routeId, departureIndex)`、只有时刻不同的记录 |
| 手动车库一律传 `-1` | 同上 | 同交路所有手动车共用一个值 |
| 环线/回头线同一趟车两次停靠同一站台 | 两条 `StopTime` | 同 departureIndex |

所以：

- **「还在到站表里就续期」是错的**（写过一版）。第一趟播完之后，**下一圈**的同号记录会把
  去重记录一直续着，这条规则从此再也不响。
- **固定 TTL 也是错的**（也写过一版）。短交路同号班次几分钟内就回来，会被整个吞掉。

现在的做法：记录的身份是 `(规则序号, routeId, departureIndex, 基准时刻)`，
**两条记录的基准时刻差在 20 秒以内才算同一次停靠**。命中时把记下来的基准**跟到新值**，
所以再慢的预测漂移也不会累积到超出容差。记录在基准时刻 + 5 分钟后过期，不续期。

20 秒这个值要大于预测抖动、小于同站台两趟车的最小间隔，两头都留得住。

**另外别把三元组压成一个 long**：`routeId` 是随机 64 位，
`(i * 31 + routeId) * 31 + departureIndex` 这种有损哈希会让不同班次撞键、表现成随机漏播。
条数是个位到几十条，直接线性扫一个 `ObjectArrayList` 就行。

### 规则的复制粘贴

`client/AnnouncerRuleClipboard` —— 客户端静态字段，跨界面、跨方块、跨车站都在。重进世界不清：
连回同一个服务器是常态；换了世界 id 对不上，会自然退化成「站台/交路待选」。

**粘贴时站台和交路按交路去对**（`pasteFor`）：

| 复制的规则 | 源站台在本站 | 本站有匹配 | 本站没有匹配 |
|---|---|---|---|
| 指定交路 R | 原样 | 换到 R 停靠的那个站台（排序后第一个） | 交路 → `ROUTE_UNSET`，站台 → 0 |
| 全部交路 | 原样 | 换到与源站台交路集合**重合最多**的站台（平手取排序靠前的） | 站台 → 0，交路保持全部 |
| 未选择交路 | 原样 | — | 站台 → 0 |

「站台 → 0」是有意的：留着别站的站台，这个方块就会替别的车站报站。

源站台的交路集合在**复制那一刻**快照（`sourceRouteIds`）：复制时人站在源站旁边，数据一定齐；
粘贴时可能已经走远，而 `simplifiedRoutes` 只带请求半径内的站台。

### 「未选择交路」为什么需要单独的状态

`AnnouncerRule.ROUTE_UNSET = -1`。**不能拿 0 代替** —— 0 是「全部交路」，拿它当「置空」
会把一条只对某条交路响的规则悄悄变成对所有车都响，恰好反了。`isComplete()` 把它算作没配完，
服务端跳过；列表里没配完的规则带「（未生效）」前缀。

顺带修了第一版的一个同类问题：规则编辑界面里**换站台**时，原来会把交路重置成 0（全部交路），
现在只在原交路不停新站台时才置为 `ROUTE_UNSET`，停的话保留。

### 复制按钮是自己摆的

`DashboardList` 的行按钮写死七个（查找/画区/编辑/上下/加/删），**没有复制**，图标也是构造时定死的，
换不了。所以在 `StationAnnouncerScreen` 里自己建一个 `TexturedButtonWidgetExtension`，
摆到 `buttonFind` **本来会在的位置**：只开编辑 + 删除时那是 `x + width - SQUARE_SIZE * 3`，正好在铅笔左边。

- 悬停判定照抄 `DashboardList.mouseMoved`；它的 `TOP_OFFSET` 是 private，照抄成 `SQUARE_SIZE + TEXT_FIELD_PADDING`。
- 点下去时的下标用公开的 `getHoverItemIndex()` 取，它已经处理好翻页和搜索过滤。
- 图标 `assets/yuzimtr/textures/gui/sprites/icon_copy{,_highlighted}.png` 按 MTR 图标的规格画：
  40×40，底色 `(18,18,18)` / 高亮 `(54,54,54)`，白色 2px 线条。

### 界面的坑：`setText2` 必须在 `setPositionAndWidth` 之后（通用，不限本功能）

**症状**：打开界面输入框是空的，点一下才显示出值。

`TextFieldWidget.setText(String)` 结尾会 `setCursorToEnd()` → `setSelectionEnd()`，
而它拿 `getInnerWidth()`（= `width - 8`）算可见窗口。在构造函数里 setText 时 `width` 还是 0，
`innerWidth` 是 **-8**，`trimToWidth` 返回空串，于是 `firstCharacterIndex` 被推到字符串末尾 ——
渲染的是 `text.substring(length)`，也就是空。点一下触发 `setCursor`，用真实宽度重算才回来。

**值本身是设进去了的**（`getText2()` 一直正确），坏的只有显示。所以这个 bug 很隐蔽：
配置能存能读，只是界面骗人。

MTR 自己所有界面都是在 `init2()` 里、`IDrawing.setPositionAndWidth` 之**后**才 setText2
（`TrainAnnouncerScreen.init2` 是最典型的例子），照着做即可。

注意 `init2()` 在改窗口大小时会再跑一遍，所以文本要**自己留一份**
（`StationAnnouncerScreen.volumeText` / `cornerTexts`），不能每次都从方块实体重读 ——
否则一改窗口大小就把编到一半的值冲掉。

### 「限制范围」框小了的表现是「完全没有声音」

第一版的默认框是 `pos ± (8, 3, 8)`，站台上随便走两步就出框，实测就是「打开限制范围之后
一点声音都没有」。和「根本没触发」在体感上完全没法区分，非常难查。

三处一起改掉了：

- 默认框放大到 `±(32, 16, 32)` —— 横向盖得住一整条站台，纵向容得下把喇叭挂天花板上；
- 界面上把框的**实际尺寸**（`65 × 33 × 65 格`）直接画出来，只看六个坐标是意识不到框多大的；
- 服务端在「触发了但框里一个人都没有」时打一条节流过的 warn，带上框外玩家的坐标，下次再遇到直接看日志。
  坐标一律 `toShortString()`：mapping 的 `BlockPos` 继承 `HolderBase`，**两者都没覆写 `toString`**，
  直接拼进日志是 `org.mtr.mapping.holder.BlockPos@1a2b3c`。
- 框内判定是**触发那一刻**的快照：广播开始之后才走进框的人听不到。

**注意已经放下去的方块不受默认值改动影响** —— 一旦在界面里保存过，角点就是显式存下来的了，
得进界面重新调。

### 「之后 X 秒」必须走待播表：到站记录车一发车就没了

`Siding.lambda$iterateArrivals$21` 的跳过条件是
`departure + deviation < currentMillis - minMillis || arrival + deviation > currentMillis + maxMillis`，
而 `getArrivals` 传的 `minMillis` 是 **0**（字节码 `lconst_0`）。所以**车一发车，这条记录就从到站表里消失**。

第一版的触发条件是「表里有这条记录 **且** 当下落在 `[fireAt, fireAt + 2s]`」，于是：

- 「发车后 X 秒」**永远不响** —— 到点时记录早没了；
- 「到站后 X 秒」只在 X 小于停站时间时响。

现在分两步（`blockEntityTick`）：

1. 到站表里每一条「这条规则还没为它播过」的班次，`upsertPending` 登记进待播表，每 tick 跟着预测更新
   （身份和去重表同构，base 差 < 20 秒算同一条，命中时跟到新值）；
2. `processPending` 放出到点的，丢掉作废的。

**「这一 tick 没看到它」要分两种**：正常消失的时机是发车那一刻，那时 base（到站基准 ≤ 发车时刻、
发车基准 = 发车时刻）不会晚于最后一次看到它；如果消失时 base 还在未来 5 秒以外
（班次取消、预测跳变换了一条新待播项、被挤出前 10 条），就作废 —— 留着会在旧时刻放一条没人要的广播。

**到站表空了也要走第二步**：最后一班车发车之后表就空了，「发车后 X 秒」恰恰要在那时候播。

### 已知代价：非实时班次晚出库会重播一次

`realtimeOnly = false` 时，规则可能先对着**计划**时刻（deviation = 0）触发；这趟车随后晚出库，
记录变成 realtime、deviation ≈ 实际出库 − 计划出库。晚了超过 20 秒，base 就对不上已播记录，
被当成新班次再播一次（`getPredictedAndDeviation` 字节码）。容差调大治标不治本 —— 本质是同一趟车换了身份。

**没有把 `realtimeOnly` 默认改成 true**：那样提前量大的规则（「到站前 8 分钟」而车 5 分钟前才出库）
会**完全不响**，而「完全不响」比「偶尔重播」难查得多（限制范围框太小那次就是这么耗掉的）。
两个取舍都写进 README 了，让用户按站台自己选。

### 已知限制：每站台只取 10 条到站

`ArrivalsCacheServer` 写死 `new ArrivalsRequest(ids, 10, -1)`。换乘站十几条交路挤一个站台时，
前 10 条可能只覆盖几分钟 —— 提前量很大的规则（比如「到站前 8 分钟」）在班次挤进前 10 之前
`fireAt` 就已经过了，会**静默漏播**，界面上没有任何提示。要改得动 MTR 的
`ArrivalsCacheServer`（或者自己开一条 `ARRIVALS` 通道）。

触发窗口是 `[fireAt, fireAt + 2000ms]`，错过就**宁可不播** —— 区块刚加载或 ETA 突然往前跳时，
补一条早该放完的广播只会更乱。

上游 `BlockTrainScheduleSensor` 用的是「剩余秒数 == 设定值」的精确相等判断，
20 tick/s 下同一秒内会连判 20 次真，**那个别学**。

### 界面数据从哪来

- **站台**：`InitClient.findStation(blockPos)` → `station.savedRails`
  （`Station extends AreaBase<Station, Platform>`），落在车站外时退回 `findClosePlatform`。
  列表项排版直接复用 `PIDSConfigScreen.getPlatformsForList`。
- **交路**：优先用我们自己那条全量通道（`announcer/PacketAllRoutes`，现在把
  `route.getRoutePlatforms()` 的站台一起带下来），按站台过滤。答复没到时退回
  `simplifiedRoutes` —— 退化成「选不到隐藏交路」，不报错。
  （`SimplifiedRoute.addToList` 第一条字节码就是 `getHidden() → ifne return`。）
- **音效**：`AbstractSoundInstanceExtension.iterateSoundIds`，编译到
  `SoundManager.getAvailableSounds()`。

### 数据包不许引用界面类

数据包在服务端注册，类里一旦出现界面类型，专用服务端在**类验证**阶段就会炸
（MTR 的 `ClientPacketHelper` 类注释原话）。`PacketOpenStationAnnouncerScreen.runClient()`
把开界面推给 `client/StationAnnouncerClientHelper`，靠 JVM 惰性类加载隔离。

### 已知的配置陷阱

三语报站动辄 13–17 秒，而停站可能只有 20 秒。「发车前 3 秒播完」的 17 秒音频，
起点会落到车刚进站甚至没进站的时候。规则界面底部会把算出来的**实际起点**显示出来
（`AnnouncerRuleScreen#summary`），配的时候所见即所得。

非实时班次（PID 上带 `*` 的）的 ETA 会随模拟更新前后跳，所以有「仅实时班次」开关 ——
和上游 `BlockTrainScheduleSensor` 的 `realtimeOnly` 同一个用意。

## 新增内容

### 网页地图的头像地址可配置 · `playerAvatarUrl`

自定义登录（LittleSkin 之类）的 UUID 在官方源查不到，网页地图上全是史蒂夫。
MTR 把 `https://mc-heads.net/avatar/<uuid>` **写死在 Angular 前端里，一共 5 处，没有配置项**。

下手点是 `Main.java:73` 的 `new MainWebServlet(WebserverResources::get, "/")` ——
`WebserverResources.get(String)` 是 `public static` 返回 `String`（字节码核对过），
所有静态文件都从这里出去。注在它的 RETURN 上做字符串替换即可。

**为什么必须能按名字取**（实测）：

| 接口 | 结果 |
|---|---|
| `littleskin.cn/avatar/{uuid}` | HTTP 500 |
| `littleskin.cn/avatar/player/{name}` | 200，image/webp 100×100 ✅ |
| `littleskin.cn/api/yggdrasil/.../profile/{uuid}` | 200 但是 JSON（base64 textures），`<img>` 用不了 |

（`littlesk.in` 是短域名，会 301 到 `littleskin.cn`。）
好在 MTR 的 `clients` 接口本来就带 `name`（schema 里 required），所以按名字够用。

压缩后的 JS 里那 5 处是三种形式，`AvatarUrlRewriter` 分别处理：

```js
y("ngSrc","https://mc-heads.net/avatar/"+e.id)…("alt",e.name)          ← 3 处
{key:l.id,icons:[`https://mc-heads.net/avatar/${l.id}`],name:l.name}     ← 1 处
getImageSrc(){let n=this.clientService.getSelectedData();
              return n?`https://mc-heads.net/avatar/${n}`:void 0}        ← 1 处，只有裸 id
```

前两种的正则对压缩后的变量名不敏感。第三种是侧边栏那个大头像，手上只有 id，
把插值表达式换成 `this.<service>.getClient()?.name??<id>`（保留原来的 null 判断）：

```js
getImageSrc(){let n=this.clientService.getSelectedData();return n?`https://littleskin.cn/avatar/player/${this.clientService.getClient()?.name??n}`:void 0}
```

这一处对 MTR 版本敏感，匹配不上会打 WARN 并报还剩几处没换，其余照常替换，不会把网页搞坏。
用 jar 里真实的字符串常量在 JDK 下跑过：5 处全中、0 处残留，`{name}` / `{uuid}` /
带 `?size=64` 后缀三种模板都验过。

模板占位符是 `{uuid}` 或 `{name}`，默认值等于 MTR 原来那个（默认零改动，直接原样返回）。
**这条改服务端** —— 网页服务器跑在服务端。

### 补上 180–500 km/h 的高速轨道

MTR 只有 20/40/60/80/100/120/140/160/200/300 十档。这里补 **180 / 250 / 280 / 350 /
380 / 400 / 450 / 500**，每档普通 + 单向共 16 个物品，注册进 MTR 自己的「核心」创造标签页。

**难点：速度烤在 enum 里。** `RailType` 是个枚举，`WOODEN(20…)`、`DIAMOND(300…)`，
往里加常量要动 `$VALUES`，又脏又容易崩。但真正落地那一行只吃 int
（`ItemRailModifier.createRail` 第 125 行）：

```java
rail = Rail.newRail(…, newRailType.railShape, 0, new ObjectArrayList<>(),
        isOneWay ? 0 : newRailType.speedLimit, newRailType.speedLimit, …);
```

所以借 `RailType.QUARTZ` 当模板注册物品，再 `@Redirect` 读 `speedLimit` 那个字段就够了
（字节码核对过：`createRail` 里 2 处 GETFIELD、`addTooltips` 里 1 处）。
不是我们的物品就原样返回，MTR 那十档完全不受影响。
`forContinuousMovementNode` 传 `false`，免得被拿去连缆车 ——
那条路上 MTR 会把 railType 换成 `CABLE_CAR`，速度就不该由我们说了。

**颜色**：`RailType.getRailColor` 是拿限速去 `values()` 里线性查找，查不到就
`return ARGB_BLACK`。所以在它 RETURN 上注一刀，**只在返回值正好是 ARGB_BLACK 时**接手
（那正好是「一个都没匹配上」的兜底分支），站台/侧线/折返/跑道那四个前置判断不受影响。

| km/h | 名字 | 颜色 |
|---|---|---|
| 180 | 紫水晶 Amethyst | `#B45CE0` |
| 250 | 青金石 Lapis | `#3A5CE0` |
| 280 | 黄金 Gold | `#F0C341` |
| 350 | 海晶灯 Sea Lantern | `#6FD3F0` |
| 380 | 末影 Ender | `#2FBF8F` |
| 400 | 发光地衣 Glow Lichen | `#8FD93A` |
| 450 | 下界之星 Nether Star | `#F5F0B0` |
| 500 | 下界合金 Netherite | `#4A4048` |

刻意和 MTR 现有十档的色系拉开（它们占了橡木棕、石灰、绿宝石绿、铁灰、砖红棕、紫、青、橙、
灰白、钻石青）。同一个值既用于物品贴图染色，也用于轨道在世界里/地图上的颜色，两边必然一致。

**贴图**由 `tools/gen_rail_textures.py` 生成，底图是 MTR 的 `rail_connector_300`（钻石）。

换色不能简单替换色号 —— **轨道色其实有 4 档**（像素数 240/224/160/144），单向那张还多
4 个抗锯齿混色。第一版只换了主色和暗色，结果另外两档的青色全留在图上。
正确做法：共享调色板 = `300` 和 `160` 两张贴图颜色集合的**交集**，正好 6 个
（白 + 5 档枕木色，两个变体都一样），凡不在这个集合里的像素就是轨道色；对它们做 HSV
变换 —— 色相直接换成目标色的，饱和度和明度按「目标色 / 底图主色」的比例缩放。
这样 4 档明暗关系自动保住，抗锯齿混色也一起处理了。

自检方式：用这套方法拿 `300` 去重建 MTR 真实的 `160` 贴图 ——
平均 |ΔRGB| = 2.3，67% 像素完全一致，90% 在 12 以内。

数字字形从 MTR 现有贴图里抠（3 位数字格 10×14，x = 16/27/38）。现有档位只提供
0/1/2/3/4/6/8，**偏偏缺 5**（250/350/450/500 都要），按 `6` 的笔法手描了一个。

语言文件是**合并**写入而不是覆盖 —— 里面还有手写的 `modmenu.nameTranslation` 等条目，
第一版的生成脚本直接 `json.dump` 把它们冲掉了。

## 资源包侧：单轨轨道的 draw call 优化

这一节不是模组改动，是排查「靠近 6 号线帧数掉到个位数、同样自定义的 S3 磁浮却没事」时
搞清楚的渲染成本模型，以及对应的资源包修法。工具在 `tools/merge_rail_atlas.py`。

### MTR4 的轨道 draw call 公式

`OptimizedRenderer.hasOptimizedRendering()` 在 4.0.5 里是 `iconst_1; ireturn`（恒为 true），
NTE 那套渲染器已经被吸收进 mtr-mapping（`org.mtr.mapping.render.batch/model/obj/…`）。但它
**不做实例化合批**：`BatchManager.queue` 给每个 VertexArray 塞一个 `RenderCall`，
`drawAll` 里是 `list.forEach(RenderCall::draw)` —— 每条都是独立 draw call，按材质分组只省了
着色器状态切换。而 `OptimizedModel.fromObjModels` 是**逐个 ObjModel 各自 `upload()` 再
`addAll`**，ObjModel 按 OBJ 的 `g` 分组切、组内再按材质切。所以

    draw call / 实例 = Σ（每个 g 组内的不同材质数）
    实例数           = 轨道长度 / repeatInterval

### 坑一：`repeatInterval` 缺失 → 几何体翻倍且互相重叠

legacy 轨道 schema（`schema/legacy/railResource.json`）没给 `repeatInterval` 声明默认值，
`LegacyRailResource.convert()` 原样抄过去 → 0 → `RailMath.renderSegment` 的
`interval <= 0 ? 0.5 : …` 回退成 **0.5 格**。

而 `RenderRails.renderRailStandard` 放置模型时**只有平移和旋转、没有 scale**。所以一格长的
模型按 0.5 格摆放 = 每节和邻节重叠一半，几何体白画一倍，还叠出共面 z-fighting（上游那句
`rotateZDegrees((x1*z1)%10/100)` 就是给这个打的歪补丁）。行进轴是模型的 **Z**，X 是横向宽度。

**但 `repeatInterval` 的语义是「摆放周期」，不总等于模型长度** —— CatenaryPro 的 `rcr`/`rcl`
声明 5.0 而模型 Z 只有 0.298（接触网杆是离散摆放的）。所以「按模型 Z 跨度自动推断」这种
模组侧兜底是错的，只能逐个包按语义填。

### 坑二：SketchUp 导出的 OBJ 分组 × 材质会乘起来

`dangui_n.obj` 是 7 个 `g` 组 × 6 个材质 = **13 个 (组,材质) 组合 = 13 个 VertexArray**。
注意 OBJ 里那 53 个 `usemtl` **行**不重要（加载器按材质合并），重要的是组合数。

### 实测对比与结果

| | interval | 实例/格 | VertexArray | draw call/格 | 三角形/格 |
|---|---|---|---|---|---|
| `dangui_n`（6号线单轨，修复前） | 缺失→0.5 | 2 | 13 | **26** | 942 |
| `dangui_n`（补 interval 后） | 1.0 | 1 | 13 | **13** | 471 |
| `dangui_n`（再合图集并组） | 1.0 | 1 | 1 | **1** | 471 |
| `magelv_track1`（S3 磁浮） | 1.0 | 1 | 1 | **1** | 48 |

两条线轨道总长几乎一样（单轨 19,520 格/175 条，磁浮 21,186 格/30 条），修复前差 26 倍。
单轨平均每条 112 格，一条进视野就是 2,912 次 draw call（原版 MC 一帧才一两千）。

### 图集布局的关键：横向排列，每格占满全高

UV 的 V 轴方向（左上原点还是左下原点）在 MTR/NTE 里不容易确定
（`ObjModelLoader:120` 是 `v = flipV ? 1 - uv.getY() : uv.getY()`，原样透传）。
**把子贴图横向排成一条、每格纵向拉满图集全高**，V 就是恒等映射，只需重映射 U ——
U 的方向两种约定下都一样，这个问题就彻底绕开了。

- 拉伸用 **NEAREST**：轨道走 entity cutout 渲染层（`blur=false` → GL_NEAREST），
  用 NEAREST 拉伸能保证采样结果和原图逐像素一致
- 最高的那张排在 x=0，图集高度取它的原生高度，它完全不被重采样
- U 映射带半像素内缩 `u' = (x0 + 0.5 + u*(w-1)) / W`，采样点落在格子内部像素中心，不跨格
- 纯色材质（`hs`/`bs`/`jm` 是 2×2 单色，占 80% 三角形，UV 还越界）直接改成指向格心一个像素

MTL 的 `map_Kd` 是**相对 OBJ 所在目录**解析的（`ResourceUtil.resolveRelativePath`），
所以 `map_Kd dangui_n/atlas.png` 对应 `assets/mtrsteamloco/rails/dangui_n/atlas.png`。

## 在「模组菜单」里配置

`fabric.mod.json` 注册了 `modmenu` 入口（写法照抄 MTR 自己的 `org.mtr.init.ModMenuConfig`），
界面是 `YuziConfigScreen`，用的也是 MTR 那套 `MTRScreenBase` + `IGui` + `CheckboxWidgetExtension`。

界面直接改 `Config` 那个单例的字段，所以 mixin 下一次读 `Config.get().xxx` 就是新值；
关闭时 `Config.save()` 落盘并清掉那几个正则/编组的编译缓存。默认值就是各字段的初始值。

modmenu 是 `modCompileOnly` —— 那个入口类只有装了「模组菜单」才会被 classload，
没装也不会 `NoClassDefFoundError`，jar 里也不会打进 modmenu 的任何东西。

**服务端那一组（分交路停站时长 / 最小刷新 / 改停站不用刷新）在客户端这里改是没用的。**
两边是各自独立的 `config/yuzimtr.json`，路径生成跑在服务端。界面里仍然列着它们，
是因为之前吃过一次亏：客户端那份留着旧默认值 `false`，站台仪表板里那一列输入框整个不出现。
列出来至少两边不一致时能一眼看到。

`consistSizes` / `gangwayOverrides` / `defaultConsistSizes` 是「正则 → 结构」的映射，
做图形界面不划算，继续只在 json 里改。

### 上游 bug：CheckboxWidgetExtension 的两个 boolean 被写反了

`CheckboxWidgetExtension(x, y, w, h, MutableText, checked, consumer)` 最终调的是
`CheckboxWidgetAbstractMapping.<init>(IIII Text checked showMessage)`，而字节码里压的是：

```
21 iconst_0        ← 常量 false
22 iload 6         ← 调用者传的 checked
24 invokespecial CheckboxWidgetAbstractMapping.<init>(IIIILorg/mtr/mapping/holder/Text;ZZ)V
```

`checked` 被硬编码成 `false`，调用者传的值落到了 `showMessage` 上。后果是
**标签只在勾上时才显示** —— 一屏复选框里勾着的有字、没勾的是个空方框。

绕法：一律传空文本 + `true`（那个 true 实际落在 showMessage 上，而文本是空的，
所以什么都不画），**标签自己画**，勾选状态一律用 `setChecked()` 显式设。
MTR 自己的界面全是「传 true 再手画标签」，大概也踩过。

### 构建环境的一个坑

`gradle.properties` 里钉了 `org.gradle.java.home` 指向 Gradle 自己装的 JDK 17。
系统只有 JRE 21，而 Loom 用的 Mercury/JDT 在 JVM 21 下读不了 JRT
（`Jdk.readJdkReleaseFile` 直接崩），只要有依赖带 sources jar 需要重映射就会挂 ——
加 modmenu 依赖时就是这么炸的。之前删掉 `withSourcesJar()` 是同一个病的另一个症状。

## 排错

`debugCacheLogging` 打开后会输出：
- `构建车辆模型: <id>` —— 每行等于一次完整模型构建（含 OBJ 解析），用来定位是哪辆车在重建
- `=== 缓存重建统计 ===` —— 按 `(lifespan, 原因)` 聚合。原因区分三种：
  `首次构建`（expiry==0，从没建成过）/ `被回收`（expiry!=0，建过又被 `tick()` 清掉）/ `过期`。
  这个区分很关键 —— `tick()` 是先置 null 再由下次 `getData` 触发重建，
  光看 `data==null` 分不出「从没建过」和「建过又被回收」。

`debugDoorMapping` 打开后输出每个门部件的 `flipped`、两个 multiplier、box、以及
`mapDoors` 分配到的 doorway，用于定位左右侧错乱。

## 注入点约束（踩过的坑）

- **`@Shadow` 不沿继承链往上找字段。** 字段声明在哪个类，`@Accessor` 就打在哪个类上
  （`SavedRailScreenAccessor` 打在 `SavedRailScreenBase` 而不是 `PlatformScreen`）。
- **能用 `@Redirect` 一个唯一调用点，就别去数 `ordinal`。** 取消上游画交路名的循环
  用的是它那次 `routes.iterator()`，不是三个 `drawText` 里的第几个。
- **mixin 里少写带初始化的 static final 字段**（要靠合并 `<clinit>`）。
- **目标类继承了别的类时，`method` 只写方法名可能匹配到继承来的重载。**
  `MainRenderer extends EntityRenderer<EntityRendering>`，于是它有三个 `render`：
  我们要的静态版 `(GraphicsHolder, Vector3d)V`，以及两个实例版
  `(EntityRendering, F, F, GraphicsHolder, I)V` / `(EntityExtension, F, F, GraphicsHolder, I)V`。
  只写 `method = "render"` 会选中实例版，启动即 `InvalidInjectionException` 崩溃。
  **写全描述符**：`method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;Lorg/mtr/mapping/holder/Vector3d;)V"`。

  光 javap 目标方法本身不够 —— 得看**整个类有没有同名重载**（含继承来的）：
  `javap -p -s <类> | grep -B1 -A2 " 方法名("`。
  布局常量放在普通类 `PlatformDwellRow` 里。

- **`@Shadow` 对字段不沿继承链上溯。** `bogie1Models`、`names`、`doorXMultiplier` 都声明在
  生成的 `*Schema` 父类里，在子类的 mixin 上 `@Shadow` 会
  `InvalidMixinException` → 类加载失败 → 资源重载崩溃 → `resourcePacks` 被清空。
  跨类读私有/protected 字段用 `@Mixin(targets = "...")` + `@Accessor`。
- `@Accessor` 的返回类型必须和字段**精确匹配**（写 `Object` 会在编译期被注解处理器拦下 —— 
  比运行时炸好得多）。
- 写 `final` 字段要 `@Mutable`。
- 部署前用字节码核对注入点。`scratchpad/cpscan.py` 能列出类的字段、方法描述符和方法内的
  invoke 目标。synthetic lambda（`lambda$xxx$6`）的编号会随上游改动变化，尽量别按名字注入 ——
  改局部变量/参数（`@ModifyVariable argsOnly`）往往能绕开。
- **`@ModifyVariable` 的参数捕获是个计数，不是全有全无。**
  `ModifyVariableInjector` 里的 `captureTargetArgs` 决定往处理函数尾部追加目标方法
  **开头的几个**参数，所以只捕获需要的那几个就行，不必把整个签名抄全。
- **`@Inject` 打在 `RETURN` 上会命中所有 return 点**，包括上游的提前 return。
  写还原/清理逻辑时要保证在提前 return 的路径上也是无害的。
- **以原版类为目标的 mixin 走默认 `remap = true`**，refmap 由 Loom 在编译期生成，
  不用改构建配置。部署后可以解开产物核对：`yuzimtr-refmap.json` 里应能看到
  yarn 名 → intermediary 名的映射（例如 `move` → `class_746.method_5784`），
  `yuzimtr.mixins.json` 里应有 Loom 注入的 `"refmap"` 键。
- **自定义网络包：`registerPacket` 之外还必须调 `setupPackets`，而且两端各一次。**
  `registerPacket` 只是往一张按类名索引的表里塞条目；真正开通道的是
  `Registry.setupPackets(Identifier)`（设置 `packetsIdentifier` + 注册服务端 C2S 接收器）和
  `RegistryClient.setupPackets(同一 id)`（注册客户端 S2C 接收器）。
  **`Registry.init()` 不会替你调**（字节码确认：`init()` 只 forEach `objectsToRegister`
  再注册一个命令回调）。
  漏调的后果是**完全静默**的：`RegistryClient.sendPacketToServer` 第一件事就是
  `getfield packetsIdentifier; ifnull` 然后直接 return —— 包发不出去、不报错、无日志，
  表现成「功能像是没写」。`sendPacketToClient` 有同样的守卫。
  用自己的 id（`yuzimtr:packet`），别蹭 `mtr:packet`；同一 id 重复注册是
  `putIfAbsent`、返回 false 保留第一个，不抛异常。

  **`registerPacket` 和 `setupPackets` 的先后无所谓**：两侧的接收 lambda
  （`Registry.lambda$setupPackets$18`、`RegistryClient.lambda$setupPackets$9`）都是
  收到包时才 `getfield packets; Map.get`，`invokedynamic` 只捕获 `this`、不快照那张表。
  只有<b>发包</b>是顺序敏感的 —— 必须在 `setupPackets` 之后。

  注意 `Registry.setupPackets` 在**物理客户端上也要跑**：它内部虽然是
  `ServerPlayNetworking.registerGlobalReceiver`（单人存档的内置服务端要用），
  但客户端发包依赖的 `packetsIdentifier` 也是它写的。将来别给它加「只在服务端执行」的条件。

- **服务端也会加载这个 mod**，`mixins.json` 分 `mixins`（双端）和 `client`（仅客户端）两段。
  客户端专有类型（`ClientWorld`、`net.minecraft.class_746` 等）不能出现在服务端会加载的类里 ——
  `org.mtr.mapping.holder.ClientWorld` 包的是 `net.minecraft.class_638`，专用服务端根本没有。
  核对办法是扫产物的常量池，注意**别用子串匹配**（字段名 `fixScheduledSoundDuplication`
  会让 `grep ScheduledSound` 误报），要看完整的类描述符。
  改完记得 `clean build` —— 增量编译会留下旧 class，让扫描结果失真。

## 参考

- MTR 4.0.5：`repo/Minecraft-Transit-Railway`
- MTR 3.2.2：`repo/MTR-3.2.2-hotfix-1`（worktree @ `5d18ccf6`）
- Transport-Simulation-Core：`repo/TSC-mtr405`（worktree @ `ecde724`，对应 4.0.5 内置的 jar）
- NTE：`repo/mtr-nte`
- JCM（第三方 patch MTR 的现成范例）：`repo/Joban-Client-Mod`
