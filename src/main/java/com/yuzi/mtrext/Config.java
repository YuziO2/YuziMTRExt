package com.yuzi.mtrext;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * config/yuzimtr.json。每个功能一个开关，方便出问题时二分定位。
 *
 * <p>被 mixin 在类初始化阶段读取，所以只做一次加载、不支持热重载 ——
 * 缓存寿命是在对象构造时烤进去的，运行时改也没用。
 */
public final class Config {

	// ---- 已实现 ----

	/**
	 * 让 VehicleModel 的模型缓存常驻，不再 60 秒过期。
	 *
	 * <p><b>默认开启 —— 关掉会有严重后果。</b>MTR4 的
	 * {@code shouldPreload ? Integer.MAX_VALUE : MODEL_LIFESPAN} 本意是让预加载的模型常驻，
	 * 但构造函数读 shouldPreload 时它还是 false（CustomResourceLoader 事后才赋值），
	 * 所以寿命恒为 60 秒。而 RenderVehicles.java:222 每帧都调 iterateModels →
	 * cachedModel.getData(false)，一过期就重建：
	 * <ul>
	 * <li>大 OBJ 车会卡顿（重庆5 单次解析约 2 秒）；</li>
	 * <li><b>更糟的是</b>重建出来的 DynamicVehicleModel 是全新的 ModelPropertiesPart，
	 *     而 mapDoors 只在 VehicleResource 第 2 层 supplier 里跑一次、不会重跑，
	 *     所以 partDetails.doorway 是 null，canOpen 永远 false ——
	 *     <b>那辆车的门永久打不开</b>。表现是离开某辆车超过 60 秒再回来就坏掉。</li>
	 * </ul>
	 *
	 * <p>代价：模型常驻内存。曾观察到打开后在<b>游戏内</b>重载资源包时
	 * SpriteLoader 抛 "Image is not allocated"（NativeImage 是堆外内存），
	 * 整套资源包被清空。需要换资源包时重启客户端，不要用游戏内重载。
	 * 修的是 MTR 4.0.5 VehicleModel.java:29,37,58 —— 构造函数里读 shouldPreload，
	 * 而那个字段是 CustomResourceLoader 事后才赋值的，构造时恒为 false，
	 * 于是 preloadResourcePattern 对这层缓存完全无效。
	 */
	public boolean keepVehicleModelsResident = true;

	/**
	 * 预加载时按真实编组预热模型缓存。
	 * MTR 4.0.5 CustomResourceLoader.java:164 只预热 (carNumber=0, totalCars=0)，
	 * 但 MTR3 转换来的车 extraModelsSupplier != null，游戏里实际取的是
	 * (0,3)/(1,3)/(2,3) —— 完全不同的桶，预热白做。
	 */
	public boolean preloadRealConsistBuckets = true;

	/**
	 * 预热用的编组长度。键是匹配 vehicle id（如 mtr_custom_train_crt26_trailer）的正则，
	 * 值是该车型实际的编组节数；没有命中就用 defaultConsistSizes。
	 *
	 * <p>必须写对，否则预热的是用不到的桶：MTR4 按 (carNumber, totalCars) 分桶
	 * （VehicleResource.java:200），编组数不对就全是冷桶，进视野现场建 —— 大 OBJ 车会卡顿。
	 * 一个 n 节编组需要 cab_1→(0,n)、cab_2→(n-1,n)、trailer→(1,n)…(n-2,n)，
	 * <b>中间车有 n-2 个桶，不是一个</b>。
	 */
	public Map<String, java.util.List<Integer>> consistSizes = new LinkedHashMap<>();

	/**
	 * 让 consistSizes 里配了的车自动进入 MTR 的预加载，不必手工改 MTR 的
	 * config/mtr.json 里的 preloadResourcePattern。装上 mod 即可用。
	 */
	public boolean preloadConfiguredVehicles = true;

	/** consistSizes 没命中时用的编组长度。 */
	public java.util.List<Integer> defaultConsistSizes = new java.util.ArrayList<>(java.util.Collections.singletonList(3));

	/**
	 * 让 queueBogie 复用已建好的桶，而不是自己开一个 (0,1)。
	 * MTR 4.0.5 VehicleResource.java:233 的 queueBogie 硬编码请求 (0, 1) 桶，
	 * 和车体的 (carNumber, totalCars) 是完全不同的桶。而 LegacyVehicleResource
	 * 只设 bogie1Position/bogie2Position，从不设 bogie1Models/bogie2Models ——
	 * MTR3 转换车的转向架模型列表恒为空。于是每帧为了读两个空 map，
	 * 把整个 7 层链条（含 35 MB OBJ 解析）重建一遍。
	 */
	public boolean reuseBucketForBogies = true;

	/**
	 * 部件名查不到时，剥掉 NTE 的 {@code <文件名>.obj/} 别名前缀再查一次。
	 *
	 * <p>为 NTE 写的 OBJ 车辆包（重庆5 等）在 model_properties 里写的是
	 * {@code crt5.obj/xuyin1} 这种带前缀的名字，MTR4 拿它去查裸 group 名的 map
	 * 会全部 miss，整个车体渲染不出来。开着它就不用为每个包单独做覆盖资源包。
	 */
	public boolean stripNtePartNamePrefix = true;

	/**
	 * 匹配这个正则的车辆，关掉 MTR4 生成的贯通道（hasGangway1/hasGangway2 强制 false）。
	 * 空字符串 = 不生效。
	 *
	 * <p>MTR3 在 TrainClient.java:101-116 按车厢间隙画一次贯通道，尺寸从车辆自己的
	 * width 算出来。MTR4 改成每节车各带 hasGangway1/hasGangway2，而
	 * LegacyVehicleResource.java:238-241 把尺寸硬编码成
	 * gangwayWidth=1.5 / gangwayHeight=2.25 / gangwayYOffset=1 / gangwayZOffset=0.5，
	 * 完全不看车辆实际尺寸。窄车（重庆5 的 width=2）就会多出两个错位的面。
	 */
	public String disableGangwayPattern = "";

	/**
	 * 按车辆覆盖贯通道尺寸：键是匹配 vehicle id 的正则，值是要覆盖的项。
	 * 比 disableGangwayPattern 更该用这个 —— 直接关掉贯通道会让人走不过车厢。
	 * 见 {@link GangwayOverride} 里的几何说明。
	 */
	public Map<String, GangwayOverride> gangwayOverrides = new LinkedHashMap<>();


	/**
	 * 修复镜像部件的 box 与几何不在同一侧（addBox 与 addCube 对 flipped 时的 x 处理不一致）。
	 *
	 * <p>不要试图改 position 来修这个：MTR3 的 renderOnceFlipped 两参数版本
	 * （ModelTrainBase.java:196）对 positionX 是取反的，MTR4 的 position 处理与它一致、
	 * 几何本来就对。改 position 会把几何一起推歪 2x（实测磁浮的门跑到车厢外面）。
	 */
	public boolean fixMirroredBoxX = true;

	/**
	 * 把 MTR3 的 DOOR_LEFT_OPEN 等分侧门条件改写成 MTR4 认得的 DOORS_OPEN / DOORS_CLOSED。
	 * MTR4 的 switch 匹配的是复数 DOORS_LEFT_OPEN，那字符串在 MTR3 里不存在，
	 * 于是落进 default: NORMAL = 无条件渲染（磁浮的门状态指示灯常亮）。
	 * MTR4 的 PartCondition 没有分侧的门条件，所以只能改成不分侧的版本。
	 */
	public boolean fixLegacyDoorSideConditions = true;

	/**
	 * 修复 OBJ 门部件被翻转两次（MTR4 两个 writeCache 重载对 optimizedModelDoor 不对称）。
	 * 症状是按车厢 reversed 的 MTR3 包开门时左右侧错乱，关门恢复。
	 */
	public boolean fixObjDoorDoubleFlip = true;

	/** 诊断日志：打出门部件的 box 与它被分配到的 doorway，定位左右侧错乱。 */
	public boolean debugDoorMapping = false;

	/** 诊断日志：统计缓存重建的次数、原因、lifespan 分布。定位问题用，平时关掉。 */
	public boolean debugCacheLogging = false;

	/**
	 * 分交路停站时长：同一站台，不同交路停不同时长。
	 * 覆盖值挂在 MTR 自己的 Platform 上（见 {@link PlatformDwellOverrides}），
	 * 跟着 {@code platforms/*.msgpack} 走，没有第二份存档。
	 * 某个交路没有条目就跟随站台自己的 dwellTime —— 真正的继承，改总值未自定义的跟着变。
	 *
	 * <p>这个开关只管**服务端**生成路径时要不要查覆盖值。
	 * 站台仪表板的 UI 和覆盖值的存取都不看它 —— 客户端和服务端是两份 config，
	 * 让界面依赖客户端那份的话，两边不一致时界面会莫名消失。
	 */
	public boolean perRouteDwellTime = true;

	/** 诊断：路径生成时打出每个 (交路, 站台) 用了哪个停站时长。 */
	public boolean debugDwellTime = false;

	/**
	 * 最小刷新：刷新路径后，如果路径的距离映射其实没变（比如只改了停站时间），
	 * 就把正在跑的车次放回去，而不是清空。MTR3 的行为，见
	 * {@link com.yuzi.mtrext.mixin.SidingMinimalRefreshMixin}。**这条改服务端。**
	 */
	public boolean minimalPathRefresh = true;

	/** 诊断：每次刷新路径打出是保住了车次还是清空了。 */
	public boolean debugPathRefresh = false;

	/**
	 * 诊断：定期打出匹配车厂的发车状态（对<b>车厂名</b>做正则，空串关闭）。
	 * 全服 129 条侧线全打会淹掉日志，所以要挑线。见 {@link DispatchDiag}。
	 */
	public String debugDispatchPattern = "";

	/**
	 * 锚点防跳：不让 {@code /time set}、睡觉跳夜这类游戏时钟跳变平移发车表。
	 *
	 * <p>useRealTime=false 的车厂，发车表锚定在「游戏午夜的真实时刻」上
	 * （TSC Depot.java:283 → Simulator.getMillisOfGameMidnight），时钟一跳锚点就跟着跳；
	 * 相位回退小于单程时间时，被回放的班次会撞上 Siding.simulateTrain:315 的同号在途去重
	 * 被静默吞掉 —— 环线上「宽度好几个班距、绕圈不自愈的空洞」就是这么来的。
	 * 开着它，锚点只跟随 ±{@link #scheduleAnchorJumpThresholdMillis} 以内的正常 TPS 漂移；
	 * 突变时若车厂还有在途车就拒绝跟随（发车表保持原相位），没有在途车才顺势重新对齐。
	 * 见 {@link com.yuzi.mtrext.mixin.DepotScheduleAnchorMixin}。**这条改服务端。**
	 *
	 * <p>代价：非均匀频率的车厂在一次被拒绝的跳变后，高峰时段和太阳位置错位。
	 * 全天频率均匀（例如 24 小时都是同一个值）则完全无感。
	 */
	public boolean stabiliseScheduleAnchor = true;

	/**
	 * 锚点防跳的阈值（毫秒）：环形差超过它算「跳变」，否则算「漂移」照常跟随。
	 * 实测 TPS 漂移每 25 秒 ~200ms，10 秒的默认值给得很宽；同时远小于班距（100 秒），
	 * 不会把真正的跳变漏成漂移。
	 */
	public long scheduleAnchorJumpThresholdMillis = 10000;

	/**
	 * 网页地图上玩家头像的地址模板，占位符 {@code {uuid}} 或 {@code {name}}。
	 * 默认就是 MTR 硬编码的那个（等于不改）。自定义登录的服务器把它换成按名字取的，
	 * 例如 LittleSkin：{@code https://littleskin.cn/avatar/player/{name}}。
	 * 见 {@link AvatarUrlRewriter}。**这条改服务端**（网页服务器跑在服务端）。
	 */
	public String playerAvatarUrl = AvatarUrlRewriter.DEFAULT_TEMPLATE;

	/**
	 * 改完停站时间不用刷新路径 —— 直接原地改已生成路径里的 {@code PathData.dwellTime}。
	 * 见 {@link DwellTimeApplier}。**这条改服务端。**
	 */
	public boolean applyDwellTimeWithoutRefresh = true;

	/**
	 * 侧线面板上的「车辆最高速」：实际限速 = min(线路限速, 车辆最高速)，时刻表（PIDS 的预估时刻）
	 * 按同一个上限推算；改了之后自动重新生成所在车厂的路径。见 {@link SidingMaxSpeedHolder}。
	 * <b>两端都读</b>：服务端管限速和刷新，客户端管面板上那一行显不显示。
	 */
	public boolean vehicleMaxSpeed = true;

	/**
	 * 把同一模型的所有 FLOOR 盒子统一撑到最宽那个的 x 范围，让人能走到窗边。
	 * 所谓「座位碰撞箱」其实是地板边界 —— VehicleRidingMovement.clampPosition
	 * 把玩家夹在单个 FLOOR 盒子内，而座位底下没铺 FLOOR。
	 */
	public boolean widenFloorsToInterior = true;

	/** 在 widenFloorsToInterior 之外再给 FLOOR 盒子左右各加这么多格。太大会穿墙。 */
	public double floorInflateX = 0.0;

	/**
	 * 给车厢侧面加隐形墙：没对准车门就走不进车体。
	 *
	 * <p>MTR4 已经限制了只能从门上车，但<b>没有碰撞</b> —— 在非门位置往前走既不会上车、
	 * 也不会被挡住，直接穿过车体。配上整排升起的绳索屏蔽门之后就变成直接掉进轨道。
	 * 见 {@link com.yuzi.mtrext.client.VehicleHullCollision}。**纯客户端。**
	 */
	public boolean boardOnlyThroughDoors = true;

	/**
	 * 墙面在<b>车辆声明宽度</b>之外再往外推多少格。
	 *
	 * <p>为什么必须有这一项：墙面本来定在 {@code VehicleCar.getWidth() / 2 + 0.3}，
	 * 但 {@code getWidth()} 是车辆配置里声明的宽度，对 MTR3 转换来的自定义车常常
	 * <b>比模型实际车宽窄</b> —— 表现是贴着墙时第一人称视角已经穿进车皮了。
	 * 更要命的是墙面因此落在站台边缘之外、车厢下方那条空隙上方：贴着墙滑行时人一直在往下掉，
	 * 滑到门口时 Y 早就掉出上车判定的 ±0.75 容差，门盒拉多宽都接不住，直接掉进轨道。
	 *
	 * <p>调大到「墙面略宽于车厢」为止：站在墙边应该还稳稳踩在站台上，视角也不该穿进车皮。
	 */
	public double boardingWallExtraWidth = 0.5;

	/** 隐形墙从车厢地板往上延伸多少格。要盖住整个车厢高度。 */
	public double boardingWallHeight = 3.0;

	/** 隐形墙从车厢地板往下延伸多少格。挡住车底转向架那一段，免得从底下钻进去。 */
	public double boardingWallDepth = 1.0;

	/**
	 * 运行中的列车也拦。默认关：车厢轮廓来自渲染阶段、最多旧一帧，
	 * 20 m/s 时就是 0.33 m 的误差，墙面会抖；MTR 自己在车运行时也不判车门。
	 */
	public boolean boardingWallWhenMoving = false;

	/**
	 * 修 MTR4 的走行音静音 bug：同一进程内重连后 legacy 车走行音整体消失，要等很久才回来。
	 * 见 {@link com.yuzi.mtrext.mixin.LegacySoundThrottleMixin}。**纯客户端。**
	 */
	public boolean fixLegacySoundThrottle = true;

	/**
	 * 修 MTR4 的音效重复播放 bug：每个待播音效被播 N 遍并相干叠加，幅度 ×N 导致削波失真。
	 * 见 {@link com.yuzi.mtrext.mixin.ScheduledSoundMixin}。
	 * 打开后 legacy 走行音会明显变小（回到 MTR3 的响度），这是预期内的。**纯客户端。**
	 */
	public boolean fixScheduledSoundDuplication = true;

	/** 车厢隐形墙的调试日志：每秒一行，打墙面位置、玩家局部坐标、每个门盒的局部范围。 */
	public boolean debugBoardingWall = false;

	/**
	 * 把交路上的「关掉下一站播报」改成<b>只关讲述人语音</b>，聊天栏的下一站文字照常显示。
	 *
	 * <p>上游把两者罩在同一个判断里，一勾就一起没。配合自制 TTS 时需要的是
	 * 「语音我自己来、文字留着」。见 {@link com.yuzi.mtrext.mixin.RouteAnnouncementMuteMixin}。
	 * <b>纯客户端。</b>
	 */
	public boolean routeFlagMutesNarratorOnly = true;

	/**
	 * 广播器的交路选择器列出<b>全部</b>交路：不限距离、包含隐藏交路。
	 *
	 * <p>上游那份列表由服务端按「客户端请求半径内的站台」筛出、且两道关卡都跳过隐藏交路，
	 * 所以只能选到附近的。这里另开一条专用通道取全量，不动 MTR 的共享数据 ——
	 * 那份数据同时喂着站台线路图和铁路标志，灌进隐藏交路会让它们显示在不该显示的地方。
	 * 见 {@link com.yuzi.mtrext.announcer.PacketAllRoutes}。
	 *
	 * <p>只作用于广播器；基础列车感应器和列车时刻表感应器保持原样。
	 * <b>两端都要开</b>（客户端负责发起请求，服务端负责取数）。
	 */
	public boolean announcerAllRoutes = true;

	/** 车内 shift 短按坐下 / 起立（长按仍然是下车）。 */
	public boolean shiftToCrouch = true;

	// ---- 实验中 ----

	/**
	 * 升降式绳索屏蔽门（最小验证版）。
	 *
	 * <p>目的是让同一站台停靠不同门距的车。MTR4 原生屏蔽门是
	 * {@code RenderVehicleHelper.canOpenDoors} 按<b>每个 doorway</b> 在 ±1/±2 格内找
	 * {@code BlockPSDAPGDoorBase}，所以门位是固定的；绳网走的是反过来的思路 ——
	 * 收到的是<b>列车级</b>门开度，整排一起升，压根不看门位。
	 *
	 * <p>已实现：双高方块与客户端碰撞、派生自 RailwayAesthetics-Future 的模型、五根绳收拢、
	 * 立柱（点阵屏 / 纯白两种）、关门前三声提示音、立柱正面的简易 PIDS。
	 */
	public boolean ropeBarrier = true;

	/**
	 * 修上游 bug：开光影后由方块实体渲染器画的东西（屏蔽门、电梯门等）整个消失。
	 *
	 * <p>{@code MainRenderer.render} 的双缓冲没考虑一帧被调用两次 —— 阴影 pass 那次把
	 * {@code RENDERS} 队列搬走并清空，轮到主 pass 就没东西可画了。这里在阴影 pass 前快照
	 * {@code RENDERS}、那次调用结束后还原，于是两个 pass 都能拿到同一份。
	 * 详见 {@link com.yuzi.mtrext.mixin.MainRendererMixin}（里面也记了第一版「让阴影 pass
	 * 不清空」的写法为什么会造成列车重影）。
	 *
	 * <p>关掉这个开关，就得靠 MTR 自己的 {@code disableShadowsForShaders}（那个开关会让
	 * 阴影 pass 直接 return，代价是 MTR 渲染的一切都没有影子）。
	 */
	public boolean fixShadowPassEatingRenderQueue = true;

	/** 沿绳排方向把门开度推给多少格以内的方块。ANTE 原版泛洪上限是 40。 */
	public int ropeBarrierSpread = 48;

	/**
	 * 开门时绳子相对车门的升起速度倍率。1.0 = 和车门同步，1.5 = 早于车门到位。
	 *
	 * <p>注意 MTR 传给我们的本来就是 {@code 车门开度 × 2}（{@code RenderVehicles.java:168}），
	 * 所以这里填 1.0 才是真正同步，不填的话默认行为是 2 倍。
	 */
	public double ropeBarrierSpeedMultiplier = 2.0;

	/**
	 * 绳子在<b>车门关闭完成</b>之前多少毫秒就已经落到底。
	 *
	 * <p>时间轴以「距发车还有多久」为准（见 {@link com.yuzi.mtrext.client.PsdaCountdown}）：
	 * 车门 t=4200 开始关、t=1000 关完，所以绳子要在 t = 1000 + 这个值 时落到底。
	 */
	public long ropeBarrierCloseLeadMillis = 1500;

	/**
	 * 绳子从顶落到底花多少毫秒。
	 *
	 * <p>这个能独立于提前量调，全靠预判：靠倒计时提前开始下降，而不是等车门动了才反应。
	 * 纯反应式的话，2000ms 的提前量只剩 1200ms 的下降窗口，怎么调都是「唰一下掉下来」。
	 */
	public long ropeBarrierCloseDurationMillis = 2500;

	/** 提示音在绳子<b>开始下降之前</b>多少毫秒响第一声。 */
	public long ropeBarrierWarningLeadMillis = 3000;

	/** 关门提示音响几声。 */
	public int ropeBarrierWarningCount = 3;

	/** 关门提示音每声间隔多少毫秒。 */
	public long ropeBarrierWarningIntervalMillis = 1000;

	/**
	 * 在立柱正面那块白色面板上显示简易 PIDS。
	 *
	 * <p>那块面板本来是 {@code movea.obj} 的 PIDS 屏，我们派生模型时把它填成了白色；
	 * 与其空着，不如拿来显示到站信息。版式和多语言轮播见
	 * {@link com.yuzi.mtrext.client.PsdaPids}。
	 */
	public boolean ropeBarrierPids = true;

	/** PIDS 文字颜色（RGB）。默认是点阵屏那种琥珀橙，配深色面板。 */
	public int ropeBarrierPidsTextColor = 0xFFA023;

	/**
	 * PIDS 字号留白系数。同 MTR {@code RenderPIDS} 构造函数的第 7 个参数 textPadding：
	 * 值越大字越小、行间留白越多。1.0 时字高几乎顶满行距，显得拥挤。
	 */
	public double ropeBarrierPidsTextPadding = 1.45;

	// 绳子的高度、间距、根数、配色不在这里 —— 那些常数逐条抄自 psda 的 common.js，
	// 集中在 PsdaModels，改了会和碰撞盒、和参考实现对不上。

	/**
	 * 用派生自 RailwayAesthetics-Future 的 OBJ 模型（柱身/柱头/红黄绳）。
	 * 关掉会退回程序化几何体（一根细杠 + 方块柱），出问题时可以用来二分定位。
	 */
	public boolean ropeBarrierUseModels = true;

	/**
	 * 绳网的提示音（派生自 psda 的 t.ogg），只由立柱播、按 1 秒节流。
	 *
	 * <p>开门时在动作起点响一声；关门时由发车倒计时提前发
	 * {@link #ropeBarrierWarningCount} 声。拿不到倒计时（旁边没站台/没班次）时关门无声。
	 */
	public boolean ropeBarrierSound = true;

	/** 打日志：绳网收到门开度时打一行，用来确认信号链通了。很吵，验证完记得关。 */
	public boolean debugRopeBarrier = false;

	// ------------------------------------------------------------------

	private transient Map<Pattern, GangwayOverride> gangwayOverridesCompiled;

	/** 第一个匹配上的覆盖项；没有就返回 null。 */
	private transient Map<Pattern, java.util.List<Integer>> consistSizesCompiled;

	/** 该车型要预热哪些编组长度。 */
	public java.util.List<Integer> findConsistSizes(String vehicleId) {
		if (consistSizesCompiled == null) {
			consistSizesCompiled = new LinkedHashMap<>();
			if (consistSizes != null) {
				consistSizes.forEach((regex, sizes) -> {
					try {
						consistSizesCompiled.put(Pattern.compile(regex), sizes);
						YuziMtrExt.LOGGER.info("编组长度覆盖: {} -> {}", regex, sizes);
					} catch (Exception e) {
						YuziMtrExt.LOGGER.error("consistSizes 的键不是合法正则: {}", regex, e);
					}
				});
			}
		}
		for (final Map.Entry<Pattern, java.util.List<Integer>> entry : consistSizesCompiled.entrySet()) {
			if (entry.getKey().matcher(vehicleId).matches()) {
				return entry.getValue();
			}
		}
		return defaultConsistSizes;
	}

	/** consistSizes 里有没有匹配这个车的条目（供 ClientConfigMixin 决定要不要预加载）。 */
	public boolean hasConsistSizesEntry(String vehicleId) {
		findConsistSizes(vehicleId);   // 触发一次编译
		for (final Pattern pattern : consistSizesCompiled.keySet()) {
			if (pattern.matcher(vehicleId).matches()) {
				return true;
			}
		}
		return false;
	}

	@javax.annotation.Nullable
	public GangwayOverride findGangwayOverride(String vehicleId) {
		if (gangwayOverridesCompiled == null) {
			gangwayOverridesCompiled = new LinkedHashMap<>();
			if (gangwayOverrides != null) {
				gangwayOverrides.forEach((regex, override) -> {
					try {
						gangwayOverridesCompiled.put(Pattern.compile(regex), override);
						YuziMtrExt.LOGGER.info("贯通道覆盖: {} -> {}", regex, override);
					} catch (Exception e) {
						YuziMtrExt.LOGGER.error("gangwayOverrides 的键不是合法正则: {}", regex, e);
					}
				});
			}
		}
		for (final Map.Entry<Pattern, GangwayOverride> entry : gangwayOverridesCompiled.entrySet()) {
			if (entry.getKey().matcher(vehicleId).matches()) {
				return entry.getValue();
			}
		}
		return null;
	}

	private transient Pattern disableGangwayPatternCompiled;
	private transient boolean disableGangwayPatternCompileTried;

	/** 编译一次并缓存；正则非法时记一条日志并当作未设置。 */
	public Pattern disableGangwayPatternCompiled() {
		if (!disableGangwayPatternCompileTried) {
			disableGangwayPatternCompileTried = true;
			if (disableGangwayPattern != null && !disableGangwayPattern.isEmpty()) {
				try {
					disableGangwayPatternCompiled = Pattern.compile(disableGangwayPattern);
				} catch (Exception e) {
					YuziMtrExt.LOGGER.error("disableGangwayPattern 不是合法正则: {}", disableGangwayPattern, e);
				}
			}
		}
		return disableGangwayPatternCompiled;
	}

	private transient java.util.regex.Pattern debugDispatchPatternCompiled;
	private transient boolean debugDispatchPatternCompileTried;

	/** 编译一次并缓存；正则非法就记一条日志并当作没配。 */
	public java.util.regex.Pattern debugDispatchPatternCompiled() {
		if (!debugDispatchPatternCompileTried) {
			debugDispatchPatternCompileTried = true;
			if (debugDispatchPattern != null && !debugDispatchPattern.isEmpty()) {
				try {
					debugDispatchPatternCompiled = java.util.regex.Pattern.compile(debugDispatchPattern);
				} catch (Exception e) {
					YuziMtrExt.LOGGER.error("debugDispatchPattern 不是合法正则: {}", debugDispatchPattern, e);
				}
			}
		}
		return debugDispatchPatternCompiled;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config instance;

	public static Config get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	/**
	 * 把当前配置写回磁盘，并清掉那几个正则/编组的编译缓存。
	 * 供「模组菜单」里的配置界面在关闭时调用 —— 界面直接改的就是这个单例，
	 * 所以 mixin 那边下一次读 {@code Config.get().xxx} 立刻是新值。
	 */
	public static void save() {
		final Config config = get();
		config.gangwayOverridesCompiled = null;
		config.consistSizesCompiled = null;
		config.disableGangwayPatternCompiled = null;
		config.disableGangwayPatternCompileTried = false;
		config.debugDispatchPatternCompiled = null;
		config.debugDispatchPatternCompileTried = false;
		final Path path = FabricLoader.getInstance().getConfigDir().resolve("yuzimtr.json");
		try {
			Files.write(path, GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
			YuziMtrExt.LOGGER.info("配置已保存: {}", path);
		} catch (IOException e) {
			YuziMtrExt.LOGGER.error("保存 {} 失败", path, e);
		}
	}

	private static Config load() {
		final Path path = FabricLoader.getInstance().getConfigDir().resolve("yuzimtr.json");
		Config config = null;
		if (Files.exists(path)) {
			try {
				config = GSON.fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), Config.class);
			} catch (Exception e) {
				YuziMtrExt.LOGGER.error("读取 {} 失败，改用默认配置", path, e);
			}
		}
		if (config == null) {
			config = new Config();
		}
		try {
			Files.write(path, GSON.toJson(config).getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			YuziMtrExt.LOGGER.warn("写回 {} 失败", path, e);
		}
		return config;
	}
}
