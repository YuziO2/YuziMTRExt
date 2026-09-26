package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.YuziMtrExt;
import com.yuzi.mtrext.client.RopeBarrierFlood;
import org.mtr.mapping.holder.Box;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.Init;
import org.mtr.mod.render.PositionAndRotation;
import org.mtr.mod.render.RenderVehicleHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 绳索屏蔽门唯一需要碰 MTR 的地方。
 *
 * <p>{@code RenderVehicleHelper.canOpenDoors} 是 MTR4 判断「这个车门能不能开」的地方：
 * 它对每个 doorway 算世界坐标 AABB，然后在 ±1(XZ)/±2(Y) 格内扫方块，扫到
 * {@code PlatformHelper} 或未锁的 {@code BlockPSDAPGDoorBase} 就返回 true
 * （后者还会被推入门开度）。我们在 RETURN 之后再扫一遍同样的盒子找绳网方块，
 * 命中就<b>沿整排</b>推门开度并强制返回 true。
 *
 * <p>「沿整排」是关键：原生逻辑只开车门正对的那一块，所以门位必须固定；
 * 绳网收到的是列车级门开度、整排一起升，于是不同门距的车都能用同一排绳网。
 *
 * <p>注入点是 {@code public static}、无重载（javap 已确认），RETURN 注入无歧义。
 * 不要 {@code @Shadow} 那两个私有常量 {@code CHECK_DOOR_RADIUS_*}，直接写 1/2 ——
 * 见 README「注入点约束」。
 */
@Mixin(value = RenderVehicleHelper.class, remap = false)
public abstract class RenderVehicleHelperMixin {

	/** 和 MTR 私有常量 CHECK_DOOR_RADIUS_XZ / _Y 保持一致。 */
	private static final int YUZIMTR_CHECK_RADIUS_XZ = 1;
	private static final int YUZIMTR_CHECK_RADIUS_Y = 2;

	/**
	 * 调试日志节流。这个注入点每帧、每个 doorway 都会跑，不节流会打到 1000 行/秒
	 * （第一次验证时 47MB / 45 万行）。只在「推到的块数变了」或「门开度跨过 5% 台阶」
	 * 时打一行，足够看清链路又不刷屏。
	 */
	private static int yuzimtr$lastLoggedPushed;
	private static int yuzimtr$lastLoggedStep;

	@Inject(method = "canOpenDoors", at = @At("RETURN"), cancellable = true)
	private static void yuzimtr$pushRopeBarriers(
			Box doorway,
			PositionAndRotation positionAndRotation,
			double doorValue,
			CallbackInfoReturnable<Boolean> callbackInfoReturnable
	) {
		if (!Config.get().ropeBarrier) {
			return;
		}
		final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
		if (clientWorld == null) {
			return;
		}

		// 和 RenderVehicleHelper.canOpenDoors 用完全相同的四个角点算包围盒
		final Vector3d corner1 = positionAndRotation.transformForwards(
				new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()),
				Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
		final Vector3d corner2 = positionAndRotation.transformForwards(
				new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMinZMapped()),
				Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
		final Vector3d corner3 = positionAndRotation.transformForwards(
				new Vector3d(doorway.getMaxXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped()),
				Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);
		final Vector3d corner4 = positionAndRotation.transformForwards(
				new Vector3d(doorway.getMinXMapped(), doorway.getMaxYMapped(), doorway.getMaxZMapped()),
				Vector3d::rotateX, Vector3d::rotateY, Vector3d::add);

		final double minX = Math.min(Math.min(corner1.getXMapped(), corner2.getXMapped()), Math.min(corner3.getXMapped(), corner4.getXMapped()));
		final double maxX = Math.max(Math.max(corner1.getXMapped(), corner2.getXMapped()), Math.max(corner3.getXMapped(), corner4.getXMapped()));
		final double minY = Math.min(Math.min(corner1.getYMapped(), corner2.getYMapped()), Math.min(corner3.getYMapped(), corner4.getYMapped()));
		final double maxY = Math.max(Math.max(corner1.getYMapped(), corner2.getYMapped()), Math.max(corner3.getYMapped(), corner4.getYMapped()));
		final double minZ = Math.min(Math.min(corner1.getZMapped(), corner2.getZMapped()), Math.min(corner3.getZMapped(), corner4.getZMapped()));
		final double maxZ = Math.max(Math.max(corner1.getZMapped(), corner2.getZMapped()), Math.max(corner3.getZMapped(), corner4.getZMapped()));

		// 找到第一块绳网就沿排推完，不必把盒子里每一块都当起点（同排结果一样）
		for (double checkX = minX - YUZIMTR_CHECK_RADIUS_XZ; checkX <= maxX + YUZIMTR_CHECK_RADIUS_XZ; checkX++) {
			for (double checkY = minY - YUZIMTR_CHECK_RADIUS_Y; checkY <= maxY + YUZIMTR_CHECK_RADIUS_Y; checkY++) {
				for (double checkZ = minZ - YUZIMTR_CHECK_RADIUS_XZ; checkZ <= maxZ + YUZIMTR_CHECK_RADIUS_XZ; checkZ++) {
					final org.mtr.mapping.holder.BlockPos checkPos = Init.newBlockPos(checkX, checkY, checkZ);
					if (RopeBarrierFlood.isPart(clientWorld, checkPos)) {
						final int pushed = RopeBarrierFlood.pushAlongRow(clientWorld, checkPos, doorValue);
						if (pushed > 0) {
							callbackInfoReturnable.setReturnValue(true);
							if (Config.get().debugRopeBarrier) {
								final int step = (int) (Math.min(Math.max(doorValue, 0), 1) * 20);
								if (pushed != yuzimtr$lastLoggedPushed || step != yuzimtr$lastLoggedStep) {
									yuzimtr$lastLoggedPushed = pushed;
									yuzimtr$lastLoggedStep = step;
									YuziMtrExt.LOGGER.info("绳网: doorway 命中 ({}, {}, {})，沿排推给 {} 块，门开度 {}",
											checkPos.getX(), checkPos.getY(), checkPos.getZ(),
											pushed, String.format("%.2f", doorValue));
								}
							}
							return;
						}
					}
				}
			}
		}
	}
}
