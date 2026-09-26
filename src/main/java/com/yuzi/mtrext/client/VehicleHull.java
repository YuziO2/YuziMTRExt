package com.yuzi.mtrext.client;

import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;

import com.yuzi.mtrext.Config;

/**
 * 一节车厢的「碰撞轮廓」：位姿 + 局部尺寸 + 这一帧开着的门。
 *
 * <h2>为什么要缓存</h2>
 *
 * <p>车厢位置只在渲染里推进 —— {@code MainRenderer.render} 调
 * {@code vehicle.simulate(millisElapsed)}，每节车的 {@link PositionAndRotation} 由
 * {@code RenderVehicles.getSmoothedVehicleCarsAndPositions} 算出；「哪些门开着」同样只在
 * 渲染里由 {@code RenderVehicleHelper.canOpenDoors} 逐 doorway 判定。tick 阶段没有第二份实现。
 * 而我们要拦的是 {@code ClientPlayerEntity.move}，那是 tick 阶段。
 *
 * <p>好在客户端 tick 与 render 在同一线程交替执行，渲染阶段填好的轮廓拿到 tick 用最多旧一帧；
 * 对停站的车是精确的。{@link #EXPIRY_MILLIS} 之后没再刷新就丢弃 —— 车被卸载 / 被遮挡剔除 /
 * 掉帧到没渲染时，墙跟着消失，退回没有这个功能时的行为，绝不会留一堵看不见的墙拦人。
 *
 * <h2>坐标系</h2>
 *
 * <p>全部尺寸都在<b>车厢局部坐标系</b>里：原点是车厢中心（{@code PositionAndRotation.position}，
 * 即轨道路径上的高度），+Z 指车头，X 是横向。{@code floorsAndDoorways} 里的盒子本来就是这个
 * 坐标系的（{@code RenderVehicleHelper.canOpenDoors} 拿到后才 {@code transformForwards} 到世界），
 * 所以门洞判定和墙面判定天然对齐，不会出现「墙挡在 MTR 允许上车的位置上」。
 */
public final class VehicleHull {

	/**
	 * 「玩家正朝车厢里走」这个判断的有效期。渲染和 tick 交替执行，隔一两帧很正常。
	 */
	private static final long INWARD_GRACE_MILLIS = 300;

	/** 最近一次「朝车厢里走」是哪节车、什么时候。见 {@link #widenForBoarding}。 */
	private static long inwardKey;
	private static long inwardMillis;

	/**
	 * 门盒外沿要比墙面再多出这么一点。被墙夹住的玩家停在 {@code halfWidth + 1e-3} 处，
	 * 差一点点就落在门盒外了 —— 这点余量保证「贴着墙滑到门口」当场就能上车。
	 */
	private static final double BOARDING_MARGIN = 0.1;

	/** 超过这么久没被渲染刷新就作废。tick 20 Hz、渲染 ≥30 fps 时永远够用。 */
	private static final long EXPIRY_MILLIS = 250;

	/**
	 * 键 = {@code vehicleId * 31 + carNumber}。两组不同的 (id, car) 撞键要求两个
	 * 车辆 id 相差 1 以内，而 id 是随机 long，实际不会发生；即便发生也只是某节车这一帧没有墙。
	 */
	private static final Long2ObjectOpenHashMap<VehicleHull> CACHE = new Long2ObjectOpenHashMap<>();

	public final long vehicleId;
	public final int carNumber;
	public final PositionAndRotation pose;
	/** 局部 |x| 超过它才算在车外。已含玩家半宽，所以墙面贴在车皮外侧。 */
	public final double halfWidth;
	/** 局部 |z| 小于它才算在这节车的纵向范围内。用 totalLength，把车钩/连接处一并盖住。 */
	public final double halfLength;
	public final double minY;
	public final double maxY;
	/** 这一帧开着的门（局部坐标），{@code floorsAndDoorways} 里布尔位为 false 的那些。 */
	public final ObjectArrayList<Box> openDoorways;
	public final boolean moving;
	public final long stampMillis;

	private VehicleHull(
			long vehicleId, int carNumber, PositionAndRotation pose,
			double halfWidth, double halfLength, double minY, double maxY,
			ObjectArrayList<Box> openDoorways, boolean moving, long stampMillis
	) {
		this.vehicleId = vehicleId;
		this.carNumber = carNumber;
		this.pose = pose;
		this.halfWidth = halfWidth;
		this.halfLength = halfLength;
		this.minY = minY;
		this.maxY = maxY;
		this.openDoorways = openDoorways;
		this.moving = moving;
		this.stampMillis = stampMillis;
	}

	/**
	 * 渲染阶段每节车调一次（{@code VehicleRidingMovement.movePlayer} 的入口）。
	 *
	 * <p>开光影时每帧会调两次（阴影 pass 一次、主 pass 一次），两次写同一个键，无害。
	 */
	public static void capture(
			long vehicleId, int carNumber,
			ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways,
			PositionAndRotation pose
	) {
		if (!Config.get().boardOnlyThroughDoors || pose == null || floorsAndDoorways == null) {
			return;
		}

		// 地板决定墙的高度基准：站台面与车厢地板大致同高，比猜一个常数靠谱
		double floorY = Double.MAX_VALUE;
		ObjectArrayList<Box> openDoorways = null;
		for (final ObjectBooleanImmutablePair<Box> pair : floorsAndDoorways) {
			final Box box = pair.left();
			if (box == null) {
				continue;
			}
			if (pair.rightBoolean()) {
				// true = 地板
				floorY = Math.min(floorY, box.getMinYMapped());
			} else {
				// false = 开着的门
				if (openDoorways == null) {
					openDoorways = new ObjectArrayList<>();
				}
				openDoorways.add(box);
			}
		}
		if (floorY == Double.MAX_VALUE) {
			// 没有地板的车厢（模型没定义）—— 定不出墙的高度，放弃这节
			return;
		}

		final VehicleExtension vehicle = findVehicle(vehicleId);
		if (vehicle == null || carNumber < 0) {
			return;
		}
		final ObjectImmutableList<org.mtr.core.data.VehicleCar> cars = vehicle.vehicleExtraData.immutableVehicleCars;
		if (carNumber >= cars.size()) {
			return;
		}
		final org.mtr.core.data.VehicleCar car = cars.get(carNumber);

		final Config config = Config.get();
		CACHE.put(key(vehicleId, carNumber), new VehicleHull(
				vehicleId, carNumber, pose,
				car.getWidth() / 2 + RenderVehicleHelper.HALF_PLAYER_WIDTH + config.boardingWallExtraWidth,
				// 注意参数语义是「<b>跳过</b>这个车钩」：getCouplingPadding1/2(boolean) 里
				// iload_1; ifeq 8; dconst_0 —— 传 true 返回 0。所以要含车钩得传 (false, false)，
				// 传 (true, true) 拿到的是纯车身长度，相邻两节之间会漏出一条没有墙的缝。
				// 含车钩之后相邻轮廓首尾相接，车厢连接处才真的被盖住。
				// 用户明确说了头尾不做端墙，所以这里只影响侧墙的纵向范围。
				car.getTotalLength(false, false) / 2 + RenderVehicleHelper.HALF_PLAYER_WIDTH,
				floorY - config.boardingWallDepth,
				floorY + config.boardingWallHeight,
				openDoorways == null ? new ObjectArrayList<>() : openDoorways,
				vehicle.isMoving(),
				System.currentTimeMillis()
		));
	}

	/** 拿到所有还新鲜的轮廓，顺手清掉过期的。 */
	public static ObjectArrayList<VehicleHull> fresh() {
		final ObjectArrayList<VehicleHull> result = new ObjectArrayList<>();
		if (CACHE.isEmpty()) {
			return result;
		}
		final long now = System.currentTimeMillis();
		CACHE.values().removeIf(hull -> now - hull.stampMillis > EXPIRY_MILLIS);
		result.addAll(CACHE.values());
		return result;
	}

	/**
	 * 把 MTR 拿去判上车的门盒，朝<b>车外</b>那一侧拉到墙面。
	 *
	 * <p>这是「墙的洞」和「MTR 的上车触发面」必须重合的那条不变量 —— 之前只在注释里写了，
	 * 没真正落实，于是出了这个 bug：玩家贴着墙滑到门口、墙放行，但门盒的外沿还在车皮里面，
	 * 中间隔着站台与车之间的空隙。玩家一迈出站台边就悬空了，而空中几乎没有水平控制力
	 * （贴着墙滑过来时水平速度本来就被夹成 0），于是原地掉进轨道，
	 * 根本走不到能触发 {@code startRiding} 的位置 —— 用户的原话是「滑到门口后直接掉进轨道」。
	 *
	 * <p>拉到 {@code halfWidth + MARGIN} 之后，贴在墙上（局部 |x| ≈ halfWidth）本身就落在门盒里，
	 * 玩家不需要迈进空隙就被接住，一步都不用掉。
	 *
	 * <p>只对开着的门生效：普通玩家的这个列表里<b>只有</b>开着的门。地板要满足额外条件才会被
	 * 加进来（{@code RenderVehicles.lambda$render$10} 先查 {@code getValidHoldingKey}）——
	 * 持能开任意车的驾驶钥匙、或持匹配本车库的钥匙且该车允许手动驾驶。这些情况下玩家本来就能
	 * 从任意位置上车，拉宽地板盒不改变什么。普通乘客的「只能从车门上车」不受影响。
	 */
	public static ObjectArrayList<Box> widenForBoarding(long vehicleId, int carNumber, ObjectArrayList<Box> boxes) {
		if (!Config.get().boardOnlyThroughDoors || boxes == null || boxes.isEmpty()) {
			return boxes;
		}
		final long key = key(vehicleId, carNumber);
		// 只在玩家正朝车厢里走时才拉宽。
		//
		// 位置分不出「进」和「出」：刚下车的人站的地方和贴着墙准备上车的人是同一片区域，
		// 拉宽的门盒会把他当场吸回车里 —— 表现就是「从门口下不了车，卡在墙处」。
		// MTR 自己没有再上车冷却（ridingVehicleCooldown 是「车还在被渲染」的看门狗，
		// 不是下车保护），原版靠门盒够窄、一步就走出去；我们把门盒拉到墙面就没这层保护了。
		// 移动方向是干净的判据：进来的人一直在按着往车里走，出去的人不会。
		if (key != inwardKey || System.currentTimeMillis() - inwardMillis > INWARD_GRACE_MILLIS) {
			return boxes;
		}
		final VehicleHull hull = CACHE.get(key);
		// 没有轮廓就没有墙，也就不该改上车判定
		if (hull == null || System.currentTimeMillis() - hull.stampMillis > EXPIRY_MILLIS) {
			return boxes;
		}
		final double outer = hull.halfWidth + BOARDING_MARGIN;
		final ObjectArrayList<Box> widened = new ObjectArrayList<>();
		for (final Box box : boxes) {
			final double minX = box.getMinXMapped();
			final double maxX = box.getMaxXMapped();
			// 每一侧各自判断：盒子伸到哪一侧，就把那一侧拉到墙面。
			// 跨过 X=0 的盒子两侧都拉，单侧的盒子只拉自己那侧（不会让人从错误的一侧上车）。
			widened.add(new Box(
					minX < 0 ? Math.min(minX, -outer) : minX,
					box.getMinYMapped(),
					box.getMinZMapped(),
					maxX > 0 ? Math.max(maxX, outer) : maxX,
					box.getMaxYMapped(),
					box.getMaxZMapped()
			));
		}
		if (Config.get().debugBoardingWall) {
			hull.logBoarding(widened);
		}
		return widened;
	}

	/** 供 {@link VehicleHullCollision} 记「这一 tick 玩家在朝这节车里走」。 */
	static void noteInward(long key) {
		inwardKey = key;
		inwardMillis = System.currentTimeMillis();
	}

	public long cacheKey() {
		return key(vehicleId, carNumber);
	}

	/** 世界坐标 → 车厢局部坐标。 */
	public Vector3d toLocal(double x, double y, double z) {
		return pose.transformBackwards(new Vector3d(x, y, z), Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
	}

	/**
	 * 车厢局部的<b>位移</b> → 世界位移。位移只转不平移，所以 Translate 传恒等函数 ——
	 * 和 {@code VehicleRidingMovement.movePlayer} 里处理输入向量的写法一致。
	 */
	public Vector3d toWorldDelta(double dx, double dy, double dz) {
		return pose.transformForwards(new Vector3d(dx, dy, dz), Vector3d::rotateX, Vector3d::rotateY, (v, x, y, z) -> v);
	}

	/**
	 * 这个局部坐标处是不是有一扇开着的、朝向 {@code side} 的门。
	 *
	 * <p>Y 容差 0.75 抄的是 {@code RenderVehicleHelper.boxContains}（它对 Y 用的是
	 * {@code Utilities.isBetween(y, minY, maxY, 0.75)}）—— 保证「墙放行的位置」和
	 * 「MTR 会让你上车的位置」严格是同一处，两者不会打架。
	 */
	public boolean hasOpenDoorwayAt(double localZ, double localY, double side) {
		for (final Box door : openDoorways) {
			if (!facesSide(door, side)) {
				continue;
			}
			if (localZ >= door.getMinZMapped() && localZ <= door.getMaxZMapped()
					&& localY >= door.getMinYMapped() - 0.75 && localY <= door.getMaxYMapped() + 0.75) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 门盒是不是朝 {@code side} 这一侧。跨过 X=0 的盒子（有些模型的门洞是整幅定义的）
	 * 两侧都算 —— 否则整排门都会被判成「不朝这边」，墙永远不开。
	 */
	private static boolean facesSide(Box box, double side) {
		final double minX = box.getMinXMapped();
		final double maxX = box.getMaxXMapped();
		if (minX < 0 && maxX > 0) {
			return true;
		}
		return Math.signum(minX + maxX) == side;
	}

	/** 一秒一行，够看清「墙面在哪 / 人在哪 / 门盒在哪」而不刷屏。 */
	private static long lastLogMillis;

	private void logBoarding(ObjectArrayList<Box> widened) {
		final long now = System.currentTimeMillis();
		if (now - lastLogMillis < 1000) {
			return;
		}
		final org.mtr.mapping.holder.ClientPlayerEntity player =
				org.mtr.mapping.holder.MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		final Vector3d local = toLocal(player.getX(), player.getY(), player.getZ());
		// 只打玩家真正贴着的那节车。不过滤的话每秒挑到的是任意一节被渲染的车，
		// 日志里全是几百格外、局部坐标 (-352, 6, 310) 这种，什么也看不出来。
		if (Math.abs(local.getXMapped()) > halfWidth + 3 || Math.abs(local.getZMapped()) > halfLength) {
			return;
		}
		lastLogMillis = now;
		final StringBuilder boxes = new StringBuilder();
		for (int i = 0; i < widened.size(); i++) {
			final Box box = widened.get(i);
			boxes.append(String.format(" 门盒%d=X[%.2f,%.2f] Y[%.2f,%.2f] Z[%.2f,%.2f] 命中=%s",
					i, box.getMinXMapped(), box.getMaxXMapped(),
					box.getMinYMapped(), box.getMaxYMapped(),
					box.getMinZMapped(), box.getMaxZMapped(),
					RenderVehicleHelper.boxContains(box, local.getXMapped(), local.getYMapped(), local.getZMapped())));
		}
		com.yuzi.mtrext.YuziMtrExt.LOGGER.info(
				"隐形墙: 车{}节{} 墙面|x|={} 墙高Y[{},{}] 玩家局部=({}, {}, {}){}",
				vehicleId, carNumber,
				String.format("%.2f", halfWidth),
				String.format("%.2f", minY), String.format("%.2f", maxY),
				String.format("%.2f", local.getXMapped()),
				String.format("%.2f", local.getYMapped()),
				String.format("%.2f", local.getZMapped()),
				boxes.length() == 0 ? " 无开着的门" : boxes.toString());
	}

	private static long key(long vehicleId, int carNumber) {
		return vehicleId * 31 + carNumber;
	}

	/**
	 * <b>每次都重新扫，不要缓存。</b> 服务端每次开关门、变速目标、有人上下车都会重发车辆
	 * （TSC {@code VehicleExtraData.checkForUpdate}），而 {@code PacketUpdateVehiclesLifts}
	 * 是对 {@code MinecraftClientData.vehicles} 先 {@code removeIf} 再加新对象 ——
	 * 记住的引用会变成一个已被移除的孤儿。
	 *
	 * <p>孤儿对象的 {@code speed} 永不推进（只有集合里的对象会被 {@code simulate}），
	 * 于是 {@code isMoving()} 冻结在第一次查到时的值。车通常是运行中进入渲染的，
	 * 所以停站之后 {@code moving} 仍是 true，默认配置下 {@link VehicleHullCollision} 直接
	 * {@code continue} —— <b>这列车整个没有墙、也不会拉宽门盒</b>。而且只要那一帧只有一列车
	 * 被渲染，缓存就永远命中、永远不刷新。
	 *
	 * <p>集合只有几十个元素，线性扫的开销可以忽略，不值得为它冒这个险。
	 */
	private static VehicleExtension findVehicle(long vehicleId) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == vehicleId) {
				return vehicle;
			}
		}
		return null;
	}
}
