# 排图与运营（服务端）

## 分交路停站时长 · `perRouteDwellTime`

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

## 最小刷新：路径没变就不动车次 · `minimalPathRefresh`

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

## 改停站时间不用刷新路径 · `applyDwellTimeWithoutRefresh`

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

## 上游 bug：给已被占用的轨道加信号闭塞会互锁

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

## 锚点防跳：时钟跳变不再吞班次 · `stabiliseScheduleAnchor`

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

## 侧线车辆最高速 · `vehicleMaxSpeed`

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
`setPositionAndWidth` 之后（见[站台广播器的界面回显记录](station-announcer.md)）。
