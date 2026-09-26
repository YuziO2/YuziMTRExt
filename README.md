# yuzi 的 MTR 扩展

用于 **[Minecraft Transit Railway](https://www.minecraftransitrailway.com/) 4.0.5 / Minecraft 1.20.1 / Fabric** 的私人 mod：修复兼容与渲染问题，恢复部分 MTR3 操作方式，并增加排图和站台设施功能。客户端和服务端都安装本 mod 的 jar；无需修改 MTR 自身配置。

许可证为 **AGPL-3.0**。绳索屏蔽门模型派生自 [RailwayAesthetics-Future](https://github.com/Jomeyer/RailwayAesthetics-Future)。

## 功能

### MTR3 自定义车辆

- 修复 NTE 部件名导致的整车不渲染、OBJ 车门开错侧、镜像门判定盒错位，以及门状态灯常亮。
- 车辆模型常驻缓存，避免离开 60 秒后反复解析模型、车门永久打不开；按实际编组预热，减少车厢首次进入视野时的卡顿。
- 可按车型覆盖贯通道尺寸；自动拓宽车内可行走地板。短按 Shift 坐下或起立，长按仍可下车。

`consistSizes`（编组长度）和 `gangwayOverrides`（贯通道尺寸）需要按车型配置。不配也能使用，但首次加载某些车厢可能卡顿。模型常驻会增加内存占用；**更换资源包请重启客户端**，游戏内重载曾导致资源包被清空。

### 排图与运营

以下功能在服务端生效；面板输入仍需要客户端安装本 mod。

- **分交路停站时长** `perRouteDwellTime`：在站台仪表板按交路填写秒数；留空跟随站台总时长。修改后重新生成一次车厂路径。
- **最小刷新** `minimalPathRefresh`：停站时长、限速或信号量变化时保留在途车；轨道几何变化时才清车。
- **即时停站时长** `applyDwellTimeWithoutRefresh`：保存后在途车立即按新时长停站；PIDS 和时刻表的推算仍需刷新路径。
- **时钟锚点** `stabiliseScheduleAnchor`：避免 `/time set`、睡觉或重启补时吞掉按游戏时间发车的班次。
- **侧线车辆最高速** `vehicleMaxSpeed`：侧线面板输入 km/h；0 为不限，1～9 按 10 处理。自动驾驶和时刻表都采用该硬上限，晚点加速也不能超速；手动驾驶不受影响。保存时会自动刷新车厂，在途车立即采用新速度。

**刷新前检查线路可通行。** 施工导致寻路失败时，自动或手动刷新都可能清空车厂路径。非无限重复车厂降低最高速后，单程变长可能使部分在途车的班次号失效并消失。在已有列车的轨道上加信号闭塞前，先清掉该段列车，以避开 MTR 的互锁问题。

### 站台与上下车

- **升降式绳索屏蔽门**：创造模式「铁路设施」页提供绳索、带 PIDS 立柱和纯白立柱。按「立柱—绳索—立柱」连续摆放，整排随车门一起升降；立柱可把开度传到另一侧。方块占两格高。默认以车门的两倍速度升起、提前 1.5 秒关门，并提前 3 秒播放 3 声提示音；这些值均可配置。
- **只能从车门上下车** `boardOnlyThroughDoors`：车厢侧面挡住玩家，但可沿车身滑到门口。运行中的列车默认不拦；车头车尾未覆盖。若仍穿进车皮或掉入缝隙，调大 `boardingWallExtraWidth`（默认 0.5）。此功能仅在客户端生效。

### 报站与广播

- `routeFlagMutesNarratorOnly`：交路的「关掉下一站播报」仅关闭语音，保留聊天栏文字。
- `announcerAllRoutes`：MTR 广播器可选远处和隐藏交路；站台线路图、PIDS 等列表保持原样。打开选择器的前一两帧可能仍显示附近交路，稍后重开即可。
- **站台广播器**：新方块向周围玩家播放资源包音频。每条规则选择站台、交路（含隐藏交路或全部交路）、音频，以及相对于到站或发车时刻的播放时间。可指定「开始播」或「播完」；负偏移表示基准时刻之后。悬停规则点复制，再到其他广播器点粘贴；无法匹配的站台或交路会标为「未生效」。

站台广播器可设音量（播放半径为音量的 16 倍）、长方体播放范围和「仅实时班次」。规则界面显示计算后的**实际起点**，方便检查长音频的播放时机。关闭「仅实时班次」可提前触发尚未出库的计划班次，但晚出库超过 20 秒时可能重播；开启后只处理已出库列车。

### 其他修复与内容

- 修复开启光影后屏蔽门与 PIDS 文字消失、重连后走行音暂时消失，以及重复播放造成的走行音过响。修复重复播放后，正常音量会比以前小。
- 增加 180、250、280、350、380、400、450、500 km/h 八档轨道，每档有普通和单向物品。
- `playerAvatarUrl` 可为网页地图设置头像模板，支持 `{uuid}` 和 `{name}`，例如 `https://littleskin.cn/avatar/player/{name}`；此项在服务端生效。

## 配置与排错

配置文件是 `config/yuzimtr.json`；安装[模组菜单](https://modrinth.com/mod/modmenu)后，大部分开关也可在界面中设置。客户端与服务端各有一份配置，排图功能应在服务端设置。`consistSizes`、`gangwayOverrides` 和 `defaultConsistSizes` 只在 JSON 中配置。

MTR 的 `config/mtr.json` 中，`preloadResourcePattern` 可以留空：配置了 `consistSizes` 的车型会自动预热。修改模型缓存相关设置后重启客户端。

出现问题时，先关闭对应开关确认影响范围。`debugCacheLogging`、`debugDoorMapping`、`debugDwellTime`、`debugPathRefresh`、`debugRopeBarrier` 和 `debugBoardingWall` 可输出针对性日志。实现细节与上游成因见 [AGENTS.md](AGENTS.md)。

## 开发测试

`./gradlew build` 构建发布 jar；`./gradlew runGametest` 启动独立测试服务器，结果写入 `build/gametest-results.xml`。GameTest 覆盖站台广播器存档、绳索屏蔽门的服务端碰撞，以及 MTR 列车进站后的车门信号；客户端绳网信号与渲染仍需在游戏中验证。测试模组不会进入发布 jar。
