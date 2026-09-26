package com.yuzi.mtrext.client;

import com.yuzi.mtrext.Config;
import net.minecraft.util.math.Vec3d;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.client.VehicleRidingMovement;

/**
 * 车厢侧面的隐形墙：没对准车门时走不进车体，对准了就照常上车。
 *
 * <h2>解决的问题</h2>
 *
 * <p>MTR4 只做了「只能从车门下车」，没做「只能从车门上车」。配上升降式绳索屏蔽门
 * （整排一起升、门位不固定）就会出事：玩家没对准车门往前走会直接穿过车体掉进轨道。
 *
 * <h2>为什么改位移而不是改位置</h2>
 *
 * <p>第一版是在 {@code VehicleRidingMovement.startRiding} 入口 {@code updatePosition} 把人推回来 ——
 * 那是<b>事后纠正</b>：原版碰撞（{@code ClientPlayerEntity.tick} → {@code LivingEntity.travel} →
 * {@code Entity.move}）早就把人挪进车体、相机也进去了，下一帧才推出来；而且
 * {@code Entity.updatePosition} 不改 {@code prevX/prevZ}，相机在本 tick 内按 prev→now 插值，
 * 视觉上就是「先陷进去再弹出来」。用户的原话是「掉入了轨道才开始往外推」。
 *
 * <p>现在改在 {@code ClientPlayerEntity.move} 的<b>入口</b>改写位移向量，位移还没被应用，
 * 所以是一堵真正的墙：撞上去就是走不动，没有迟滞、没有弹开。夹完之后原版的方块碰撞、
 * 台阶、autoJump 都在夹过的位移上正常工作。
 *
 * <h2>只夹法向分量</h2>
 *
 * <p>把位移变换到车厢局部坐标系之后，侧墙的法向就是局部 X。<b>只把 X 分量夹住、Z 分量原样保留</b>，
 * 于是贴着车身走是顺滑的滑行而不是被卡死 —— 人会一路滑到车门口再进去。弯道、坡道上车厢
 * 是歪的，但局部坐标系里墙永远是轴对齐的矩形，旋转和俯仰由
 * {@code PositionAndRotation.transformBackwards/Forwards} 一起处理，不需要拼 AABB。
 *
 * <h2>「已经在里面就不夹」</h2>
 *
 * <p>只在「起点在轮廓外、终点在轮廓内」时才夹。这条保证了墙只管拦越界，永远不会把人困住：
 * 车上的乘客、刚下车站在门边的人、被开进来的车罩住的人，都能正常走出去。
 */
public final class VehicleHullCollision {

	private VehicleHullCollision() {
	}

	/** 夹到墙面外侧一点点，避免浮点误差让人正好压在边界上反复进出。 */
	private static final double EPSILON = 1e-3;

	public static Vec3d clamp(double playerX, double playerY, double playerZ, Vec3d movement) {
		final Config config = Config.get();
		if (!config.boardOnlyThroughDoors || movement == null) {
			return movement;
		}
		// 位移为零时没有「越界」可言，省掉每 tick 的遍历
		if (movement.x == 0 && movement.y == 0 && movement.z == 0) {
			return movement;
		}
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity == null || clientPlayerEntity.isSpectator()) {
			return movement;
		}

		final ObjectArrayList<VehicleHull> hulls = VehicleHull.fresh();
		if (hulls.isEmpty()) {
			return movement;
		}

		double dx = movement.x;
		double dy = movement.y;
		double dz = movement.z;

		for (final VehicleHull hull : hulls) {
			// 乘着的那辆车由 MTR 自己摆位置，别插手
			if (VehicleRidingMovement.isRiding(hull.vehicleId)) {
				continue;
			}
			// 运行中的车：缓存最多旧一帧，20 m/s 时就是 0.33 m 的误差，墙面会抖。
			// 默认不拦，和 MTR 自己「运行中不判车门」的取舍一致。
			if (hull.moving && !config.boardingWallWhenMoving) {
				continue;
			}

			final Vector3d p0 = hull.toLocal(playerX, playerY, playerZ);
			final Vector3d p1 = hull.toLocal(playerX + dx, playerY + dy, playerZ + dz);

			final double x0 = p0.getXMapped();
			final double x1 = p1.getXMapped();
			final double y1 = p1.getYMapped();
			final double z1 = p1.getZMapped();

			if (y1 < hull.minY || y1 > hull.maxY) {
				continue;
			}
			if (Math.abs(z1) >= hull.halfLength) {
				continue;
			}
			// 记下「正朝车厢里走」。上车触发面靠它区分进和出 —— 见 VehicleHull.widenForBoarding。
			// 乘车中的车在上面就 continue 了，所以下车那一刻不会留下记录。
			if (Math.abs(x1) < Math.abs(x0) - 1e-4) {
				VehicleHull.noteInward(hull.cacheKey());
			}
			// 起点已经在轮廓里 → 不是越界，放行（见类注释「已经在里面就不夹」）
			if (Math.abs(x0) < hull.halfWidth) {
				continue;
			}
			if (Math.abs(x1) >= hull.halfWidth) {
				continue;
			}
			final double side = Math.signum(x0);
			if (hull.hasOpenDoorwayAt(z1, y1, side)) {
				continue;
			}

			// 只夹法向（局部 X），切向（局部 Z）和竖直分量原样保留 → 沿墙滑行
			final Vector3d worldDelta = hull.toWorldDelta(
					side * (hull.halfWidth + EPSILON) - x0,
					p1.getYMapped() - p0.getYMapped(),
					z1 - p0.getZMapped()
			);
			dx = worldDelta.getXMapped();
			dy = worldDelta.getYMapped();
			dz = worldDelta.getZMapped();
		}

		if (dx == movement.x && dy == movement.y && dz == movement.z) {
			return movement;
		}
		return new Vec3d(dx, dy, dz);
	}
}
