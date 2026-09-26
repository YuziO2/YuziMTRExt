# 车辆音频与播报

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
