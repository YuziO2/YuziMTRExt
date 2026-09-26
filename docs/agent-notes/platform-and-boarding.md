# 站台、渲染与乘车

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
