# 站台广播器 · `station_announcer`

新方块 `yuzimtr:station_announcer`。逻辑全在 `station/StationAnnouncerBlockEntity`，
界面在 `client/StationAnnouncerScreen` + `client/AnnouncerRuleScreen` + `client/SoundPickerScreen`。

## 为什么不能复用 MTR 自己的广播器

`BlockTrainAnnouncer` 是**车载**广播器，三条限制全都挡路
（触发点在 `data/VehicleExtension.java:209-222`）：

| 限制 | 位置 |
|---|---|
| 触发条件带 `VehicleRidingMovement.isRiding(id)` —— 只有车上乘客会触发 | VehicleExtension:222 |
| `clientPlayerEntity.playSound(…, 1000, 1)`，音量 1000 是贴耳播放 | BlockTrainAnnouncer:103 |
| 触发点是车头 3×3×3 扫方块，没有提前量 | VehicleExtension:209-217 |
| `ANNOUNCE_COOLDOWN_MILLIS = 20000` | BlockTrainAnnouncer:44 |

## 时间基准：两个字段，和站台上的显示设备同源

```
ArrivalResponse.getArrival()    ← PID 那个「X 分/X 秒」的口径（RenderPIDS.java:177）
ArrivalResponse.getDeparture()  ← JCM 发车倒数器的口径（DepartureTimerRenderer 反编译确认）
```

TSC 侧的来源是 `Siding.getArrivals`（字节码）：
`arrival = stopTime.startTime + deviation`、`departure = stopTime.endTime + deviation`，
所以 departure 就是「离开本站台的时刻」，含停站时间。两者都要减 `getMillisOffset()`
才是本地时钟。

**都是绝对时间戳**，所以到站表一秒才刷一次并不影响触发精度 —— 刷新率只影响预测的新鲜度。

## 为什么整套逻辑在服务端

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

## 「限制范围」那档的音量不能用用户设的值

`ServerPlayerEntity.playSound` 编译到 `ServerPlayer.playNotifySound`，它发的是一个
**定位在玩家当时坐标**的音源，不是「贴耳播放」。客户端 `SoundEngine` 的衰减距离是
`max(volume, 1) × 16` 格、增益是 `clamp(volume × 分类音量, 0, 1)`。

所以传 1 的话衰减距离只有 16 格 —— 十几秒的报站放到一半，人走出 16 格就静音了。
这一档固定传 1000：增益照样被钳到 1，只是把衰减距离拉到听不出衰减。
MTR 自己的 `BlockTrainAnnouncer` 传 1000 正是这个道理。

以上是**旧客户端的退路**。1.6.0 起改成「离框淡出」（下一节），只有不认识新通道的客户端还走 `playNotifySound` + 1000。

## 「限制范围」离框淡出 · `stationAnnouncerFadeMillis`

用户现象（3 号线，站台广播器限制范围，框罩住站台和车厢）：后车跟得近时，后车的「即将到达」在前车还停着时响起，
前车上的人在框里，于是一路听到放完 —— 车早开走了，和下一站的报站叠在一起。要的是**离开站台就听不到**。

`playNotifySound` 做不到：声音一发出去就归客户端管，服务端没有「调音量」的指令，只能 `StopSound` 硬切。
所以改成服务端只发「放哪个音、框在哪、淡出几 tick」，客户端用一个 `MovingSoundInstance` 自己播：

- 不定位（`relative = true`、`AttenuationType.NONE`），没有方位和距离衰减，框里哪儿都一样响 —— 和老路径一致。
- 每 tick 看客户端玩家坐标（脚下，和服务端选人一致）在不在框里；离框按 `RangeFade` 线性降，降到 0 `setDone()`。
  淡出途中走回框里按同样速度升回来。默认 500 ms（10 tick），0 = 离框当 tick 就停。
- 音量：引擎增益是 `clamp(volume × 「方块」分类音量, 0, 1)`。传 `比例 ÷ 分类音量`：满格时增益是 1，
  和老路径的 1000 一样不受分类滑块影响；淡出时增益就是比例本身。分类音量 0 时两条路都没声。
- 开始播放时谁在框里仍按**触发那一刻**选人（框外的人收不到这条指令）；之后才走进框的人照旧听不到。

**为什么单开一条 Fabric 通道 `yuzimtr:ranged_sound`，不走 mapping 层的 `yuzimtr:packet`**：兼容没升级的客户端。
mapping 层客户端收到不认识的包是静默丢弃（`RegistryClient.lambda$setupPackets$9`：`Map.get` 为 null 直接 return，字节码确认），
服务端也分不出对方认不认识 —— 等于旧客户端在框里什么都听不到。单开通道后，客户端注册了接收器，Fabric 进服时会把通道名
报给服务端，`ServerPlayNetworking.canSend` 就能逐人判断：认识的发淡出指令，不认识的退回 `playNotifySound` + 1000（放完为止）。

GameTest `rangeModeFadesOutAfterLeavingTheBox` 测 `RangeFade` 的线性淡出、走回框里恢复、1 tick 立即停。
客户端那半（声音实例、通道注册）GameTest 测不到，要进游戏听。

## 续播范围 · 1.7.0（`join_mode`）

用户现象：站台只有一半划进了播放框，人从站台另一头走过来，进框时广播已经开始了，就一点也听不到。
又不能放开成「谁进框都接着听」：坐车进站的乘客不该听到站台刚放的那段。

**资格按触发那一刻定**（客户端收到指令时）：
- 人在播放框里 → 有资格，马上满音量播放，乘不乘车都一样（和以前一样）。
- 不在框里：续播关着、正在乘车（MTR 的 `VehicleRidingMovement.ridingVehicleId != 0`，下车时清零）、
  或不在续播范围里 → 没资格。坐车进站的人要么触发时在站外，要么在车上，都被挡掉。
- 续播范围三档：不续播 / **所在车站（缺省，老方块读档也按它）** / 自定义长方体。

**之后**：有资格的人只要人在框里、又没有正在放的实例，就开一个新实例，
**播放位置 = 触发以来实际过去的时间**（不是上次停下的地方），从 0 音量淡入；超过音频时长就不放。
离框照旧淡出停掉，所以来回进出，每次听到的都是「此刻该放到的地方」。时长用规则里存的 `durationMillis`，
没量过就客户端读 ogg（`OggDuration`），都没有就最多保留 2 分钟。

**车站范围只能在客户端判**：MTR 的服务端 mod 不持有 TSC 的 `Simulator`（见 `PacketAllRoutes` 的说明），
拿不到车站；客户端 `MinecraftClientData.platformIdMap` 里有。所以车站档下服务端把指令发给水平 512 格内所有支持的玩家，
由客户端按规则的站台 id 找所属车站（`Platform.area`）、`Station.inArea` 判断。MTR 的车站范围是**平面矩形、上下不限高**
（本地存档 208 个车站全是 `Long.MIN_VALUE..MAX_VALUE`），没关系：播放框本身限了高度。自定义档服务端自己筛。

**从中途开始放，原版做不到**：1.20.1 的 `SoundInstance` 还没有 `getAudioStream`。`SoundSystemSeekMixin`
（客户端）用 `@WrapOperation` 包住 `SoundSystem.play(SoundInstance)` 里唯一一处 `SoundLoader.loadStreamed`，
对我们的实例套一层 `SkippingAudioStream`：先解码丢掉开头那么多毫秒（按帧对齐），跳过量超过全长时第一次读就是空的、
引擎当作放完。只有流式加载能这么做，所以需要跳过时把非流式的 `Sound` 换成 `stream=true` 的拷贝（报站包本来就全是流式）。

**不预先静音播放**：本可以对有资格的人从触发起就静音放着、进框再把音量拉起来，省掉跳读。但报站包 551 条全是流式，
客户端同时最多 8 路流（`SoundEngine`：`clamp(sqrt(总声道), 2, 8)`），按车站范围铺开，大站几个站台一起播时
会把真该听的挤掉，而且是静默失败。

**兼容（格式变了就换通道名 / 包名）**：
- 播放指令：新通道 `yuzimtr:ranged_sound_join`。服务端按 `canSend` 降级：新通道 → 1.6.0 的 `ranged_sound`（只给框里的人）→ `playNotifySound`。
  客户端两个通道都注册，所以连 1.6.0 的服务端也照旧离框淡出。
- 保存配置：新包 `PacketUpdateStationAnnouncerV2`。mapping 层的 `PacketBufferReceiver` 没有「还剩几个字节」，
  往旧包末尾加字段的话，新服务端读旧客户端的包会越界。旧包照旧注册，旧客户端的保存走不带续播参数的 `setData`，续播设置保持原样。

GameTest `joinRangeSurvivesSavingAndDefaultsToStation` 测存读档、老存档缺省为车站、旧客户端保存不冲掉续播；
`rangeModeFadesOutAfterLeavingTheBox` 加了从 0 淡入。跳读、资格判断、车站查找都在客户端，要进游戏验证。

另外 `Box(BlockPos, BlockPos)` 直接拿整数坐标当 min/max、**大角不加 1**（原版构造函数字节码），
而 `contains` 判的是 `< max`，所以照直传两个角点的话站在「坐标 2」那一格上算框外。
`rangeBox()` 显式给大角 +1。（JCM 的声音循环器有同样的毛病，没跟。）

## playSound 的两个重载语义相反，别照抄绳索屏蔽门

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

## 音频时长：MTR 没有这个能力，自己读 ogg

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

## NBT 存的是平行数组，不是嵌套结构

mapping 的 `CompoundTag` 只暴露 `putInt/putLong/putString/putXxxArray`，
**没有 `putCompound`、也没有任何列表标签**（javap 确认）。所以规则列表拆成
`rule_platform_ids` / `rule_route_ids` / `rule_offsets` / `rule_durations` / `rule_flags`
五个平行数组，音效 id 用 `'\n'` 连成一个字符串（资源 id 的合法字符集里没有换行）。
读回来时 `split("\n", -1)` —— **`-1` 不能省**，否则最后一条没配音效的规则会和前面错位。

## 去重：身份必须落在「时刻」上，不能落在 departureIndex 上

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

## 规则的复制粘贴

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

## 「未选择交路」为什么需要单独的状态

`AnnouncerRule.ROUTE_UNSET = -1`。**不能拿 0 代替** —— 0 是「全部交路」，拿它当「置空」
会把一条只对某条交路响的规则悄悄变成对所有车都响，恰好反了。`isComplete()` 把它算作没配完，
服务端跳过；列表里没配完的规则带「（未生效）」前缀。

顺带修了第一版的一个同类问题：规则编辑界面里**换站台**时，原来会把交路重置成 0（全部交路），
现在只在原交路不停新站台时才置为 `ROUTE_UNSET`，停的话保留。

## 复制按钮是自己摆的

`DashboardList` 的行按钮写死七个（查找/画区/编辑/上下/加/删），**没有复制**，图标也是构造时定死的，
换不了。所以在 `StationAnnouncerScreen` 里自己建一个 `TexturedButtonWidgetExtension`，
摆到 `buttonFind` **本来会在的位置**：只开编辑 + 删除时那是 `x + width - SQUARE_SIZE * 3`，正好在铅笔左边。

- 悬停判定照抄 `DashboardList.mouseMoved`；它的 `TOP_OFFSET` 是 private，照抄成 `SQUARE_SIZE + TEXT_FIELD_PADDING`。
- 点下去时的下标用公开的 `getHoverItemIndex()` 取，它已经处理好翻页和搜索过滤。
- 图标 `assets/yuzimtr/textures/gui/sprites/icon_copy{,_highlighted}.png` 按 MTR 图标的规格画：
  40×40，底色 `(18,18,18)` / 高亮 `(54,54,54)`，白色 2px 线条。

## 界面的坑：`setText2` 必须在 `setPositionAndWidth` 之后（通用，不限本功能）

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

## 「限制范围」框小了的表现是「完全没有声音」

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

## 「之后 X 秒」必须走待播表：到站记录车一发车就没了

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

## 已知代价：非实时班次晚出库会重播一次

`realtimeOnly = false` 时，规则可能先对着**计划**时刻（deviation = 0）触发；这趟车随后晚出库，
记录变成 realtime、deviation ≈ 实际出库 − 计划出库。晚了超过 20 秒，base 就对不上已播记录，
被当成新班次再播一次（`getPredictedAndDeviation` 字节码）。容差调大治标不治本 —— 本质是同一趟车换了身份。

**没有把 `realtimeOnly` 默认改成 true**：那样提前量大的规则（「到站前 8 分钟」而车 5 分钟前才出库）
会**完全不响**，而「完全不响」比「偶尔重播」难查得多（限制范围框太小那次就是这么耗掉的）。
两个取舍都写进 README 了，让用户按站台自己选。

## 已知限制：每站台只取 10 条到站

`ArrivalsCacheServer` 写死 `new ArrivalsRequest(ids, 10, -1)`。换乘站十几条交路挤一个站台时，
前 10 条可能只覆盖几分钟 —— 提前量很大的规则（比如「到站前 8 分钟」）在班次挤进前 10 之前
`fireAt` 就已经过了，会**静默漏播**，界面上没有任何提示。要改得动 MTR 的
`ArrivalsCacheServer`（或者自己开一条 `ARRIVALS` 通道）。

触发窗口是 `[fireAt, fireAt + 2000ms]`，错过就**宁可不播** —— 区块刚加载或 ETA 突然往前跳时，
补一条早该放完的广播只会更乱。

上游 `BlockTrainScheduleSensor` 用的是「剩余秒数 == 设定值」的精确相等判断，
20 tick/s 下同一秒内会连判 20 次真，**那个别学**。

## 界面数据从哪来

- **站台**：`InitClient.findStation(blockPos)` → `station.savedRails`
  （`Station extends AreaBase<Station, Platform>`），落在车站外时退回 `findClosePlatform`。
  列表项排版直接复用 `PIDSConfigScreen.getPlatformsForList`。
- **交路**：优先用我们自己那条全量通道（`announcer/PacketAllRoutes`，现在把
  `route.getRoutePlatforms()` 的站台一起带下来），按站台过滤。答复没到时退回
  `simplifiedRoutes` —— 退化成「选不到隐藏交路」，不报错。
  （`SimplifiedRoute.addToList` 第一条字节码就是 `getHidden() → ifne return`。）
- **音效**：`AbstractSoundInstanceExtension.iterateSoundIds`，编译到
  `SoundManager.getAvailableSounds()`。

## 数据包不许引用界面类

数据包在服务端注册，类里一旦出现界面类型，专用服务端在**类验证**阶段就会炸
（MTR 的 `ClientPacketHelper` 类注释原话）。`PacketOpenStationAnnouncerScreen.runClient()`
把开界面推给 `client/StationAnnouncerClientHelper`，靠 JVM 惰性类加载隔离。

## 已知的配置陷阱

三语报站动辄 13–17 秒，而停站可能只有 20 秒。「发车前 3 秒播完」的 17 秒音频，
起点会落到车刚进站甚至没进站的时候。规则界面底部会把算出来的**实际起点**显示出来
（`AnnouncerRuleScreen#summary`），配的时候所见即所得。

非实时班次（PID 上带 `*` 的）的 ETA 会随模拟更新前后跳，所以有「仅实时班次」开关 ——
和上游 `BlockTrainScheduleSensor` 的 `realtimeOnly` 同一个用意。
