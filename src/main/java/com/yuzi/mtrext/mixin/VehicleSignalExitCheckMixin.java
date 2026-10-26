package com.yuzi.mtrext.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.yuzi.mtrext.Config;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 基础信号模式：普通颜色的闭塞不做「出闭塞后一个车长必须空」检查。照搬上游 TSC 02bfc24（2026-06-18，
 * 「Split signalling to basic and advanced modes」）：那条检查只对颜色带 alpha 位的「高级模式」闭塞生效。
 *
 * <p>4.0.5 里这条检查对所有闭塞都做：{@code checkAndBlockSignal} 沿路径走到闭塞出口后，要求出口外
 * 一个车长也没被占（任何颜色），否则连这个闭塞都不让进。于是终点站台所在的闭塞，会因为出口外另一种颜色的
 * 区间被别的交路占着而进不了站 —— 哪怕车本来就要在站台停二十秒。湾湾岛 4 号线进中间站台被 14 号线挡住就是这个。
 * 4.0.5 没有设置 alpha 位的界面，存档里的颜色全是普通颜色，所以开着它等于整条检查不做，行为回到 MTR3：
 * 闭塞空就能进，进去后下一闭塞被占就停在闭塞末端。
 *
 * <p>注入点：{@code checkAndBlockSignal} 里唯一一次 {@code railBlockedDistance} 调用（字节码确认），
 * 上游新版在同一处写的就是 {@code hasAlpha ? railBlockedDistance(...) : -1}。
 */
@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleSignalExitCheckMixin {

	@WrapOperation(
			method = "checkAndBlockSignal(ILorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;ZZ)Z",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Vehicle;railBlockedDistance(IDDLorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;ZZ)D")
	)
	private double yuzimtr$skipExitCheckForBasicColours(
			Vehicle instance, int index, double checkRailProgress, double checkDistance, ObjectArrayList<?> vehiclePositions, boolean reserveRail, boolean secondPass,
			Operation<Double> original, @Local(argsOnly = true) int currentIndex
	) {
		if (Config.get().basicSignalMode && instance.vehicleExtraData.immutablePath.get(currentIndex).getSignalColors().intStream().noneMatch(color -> (color & 0xFF000000) != 0)) {
			// -1 = 出口外没有东西挡着，上游随后只看首段自己是否被占
			return -1;
		}
		return original.call(instance, index, checkRailProgress, checkDistance, vehiclePositions, reserveRail, secondPass);
	}
}
