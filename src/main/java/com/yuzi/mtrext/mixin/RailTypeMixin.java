package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.RailConnectors;
import org.mtr.core.data.Rail;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.RailType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 给我们自己加的那几档轨道补上颜色。
 *
 * <p>{@code RailType.getRailColor} 是拿轨道的限速去 {@code values()} 里线性查找匹配的
 * enum 常量，查不到就 {@code return ARGB_BLACK}。我们的 180/250/… 当然查不到，
 * 所以不补的话全是黑的。
 *
 * <p>注在 RETURN 上、并且<b>只在返回值正好是 ARGB_BLACK 时</b>才接手 ——
 * 那正好就是「一个都没匹配上」的兜底分支。这样站台/侧线/折返/跑道那四个前置判断
 * （在方法开头）完全不受影响。
 */
@Mixin(value = RailType.class, remap = false)
public abstract class RailTypeMixin {

	@Inject(method = "getRailColor", at = @At("RETURN"), cancellable = true)
	private static void yuzimtr$colorForCustomSpeeds(Rail rail, CallbackInfoReturnable<Integer> cir) {
		if (cir.getReturnValueI() != IGui.ARGB_BLACK) {
			return;
		}
		final int color = RailConnectors.colorForSpeed(
				Math.max(rail.getSpeedLimitKilometersPerHour(false), rail.getSpeedLimitKilometersPerHour(true)));
		if (color != 0) {
			cir.setReturnValue(color);
		}
	}
}
