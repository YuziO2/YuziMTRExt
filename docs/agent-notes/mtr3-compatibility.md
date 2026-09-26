# MTR3 兼容与操作手感

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
