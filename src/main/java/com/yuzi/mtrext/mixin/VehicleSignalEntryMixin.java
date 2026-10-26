package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.SignalEntryCheck;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 多色闭塞防互锁：进闭塞前先确认路线上整段闭塞都没被别的车占（不论颜色），逻辑在 {@link SignalEntryCheck}。
 *
 * <p>注入 TSC {@code Vehicle.checkAndBlockSignal}（Vehicle.java:628，private，唯一重载，字节码确认）的 HEAD。
 * 它被 {@code railBlockedDistance} 对前方制动距离内的每一段调用，返回 true 表示「停在这一段之前」。
 * 我们只会多返回 true，不会把上游判定为堵的改成通。
 *
 * <p>第二轮（{@code secondPass}，查闭塞出口后一个车长）不管。客户端也跑 {@code Vehicle.simulate}，
 * 但 {@code vehiclePositions == null} 时 {@code railBlockedDistance} 直接返回，走不到这里。
 */
@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleSignalEntryMixin {

	@Inject(
			method = "checkAndBlockSignal(ILorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;ZZ)Z",
			at = @At("HEAD"),
			cancellable = true
	)
	private void yuzimtr$checkWholeBlockBeforeEntering(int currentIndex, ObjectArrayList<?> vehiclePositions, boolean reserveRail, boolean secondPass, CallbackInfoReturnable<Boolean> cir) {
		if (secondPass || !Config.get().signalEntryCheck) {
			return;
		}
		final Vehicle vehicle = (Vehicle) (Object) this;
		final double railProgress = ((VehicleStateAccessor) (Object) this).yuzimtr$railProgress();
		if (SignalEntryCheck.blocksEntry(vehicle.vehicleExtraData.immutablePath, currentIndex, vehicle.getId(), railProgress)) {
			cir.setReturnValue(true);
		}
	}
}
