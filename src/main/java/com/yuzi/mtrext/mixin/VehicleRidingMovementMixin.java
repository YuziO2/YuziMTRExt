package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.EntityPose;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.client.VehicleRidingMovement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 MTR3 的「shift 短按坐下 / 起立」加回来，同时保留 MTR4 的「长按下车」。
 *
 * <p>MTR3 是在 shift <b>按下</b>的那一帧切换（TrainClient.java:159）：
 * <pre>
 * if (isShifting &amp;&amp; !previousShifting) isSitting = !isSitting;
 * clientPlayer.setPose(isSitting &amp;&amp; !detached ? Pose.CROUCHING : Pose.STANDING);
 * </pre>
 * 但 MTR4 把长按 shift 用作下车（VehicleRidingMovement.tick 累加 shiftHoldingTicks，
 * 到 SHIFT_ACTIVATE_TICKS 就在下一 tick 顶部下车），所以不能在按下时就切换 ——
 * 否则每次下车前都会先坐一下。
 *
 * <p>改成在<b>松手</b>时判断：累计不足阈值 = 短按 → 切换坐/站；到了阈值 = 长按，
 * 那一路已经走下车逻辑了，不会走到这里（ridingVehicleId 会被清零）。
 */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public abstract class VehicleRidingMovementMixin {

	@Shadow private static float shiftHoldingTicks;
	@Shadow private static long ridingVehicleId;
	@Shadow @Final private static int SHIFT_ACTIVATE_TICKS;

	@Unique
	private static float yuzimtr$previousShiftHoldingTicks;

	@Unique
	private static boolean yuzimtr$sitting;

	@Inject(method = "tick", at = @At("TAIL"))
	private static void yuzimtr$shiftToSit(CallbackInfo ci) {
		if (!Config.get().shiftToCrouch) {
			return;
		}

		if (ridingVehicleId == 0) {
			// 下车了（含长按下车），状态清干净
			yuzimtr$sitting = false;
			yuzimtr$previousShiftHoldingTicks = 0;
			return;
		}

		if (yuzimtr$previousShiftHoldingTicks > 0 && shiftHoldingTicks == 0
				&& yuzimtr$previousShiftHoldingTicks < SHIFT_ACTIVATE_TICKS) {
			yuzimtr$sitting = !yuzimtr$sitting;
		}
		yuzimtr$previousShiftHoldingTicks = shiftHoldingTicks;

		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity != null) {
			// 每 tick 都要设：原版会按输入把姿态改回 STANDING
			clientPlayerEntity.setPose(yuzimtr$sitting ? EntityPose.getCrouchingMapped() : EntityPose.getStandingMapped());
		}
	}
}
