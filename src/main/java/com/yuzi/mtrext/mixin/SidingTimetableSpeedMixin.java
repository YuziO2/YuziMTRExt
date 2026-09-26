package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.SidingMaxSpeedHolder;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 时刻表推算按「车辆最高速」限速 —— PIDS 上的到站/发车时刻、交路运行时长、无限重复的一圈时长
 * 全都从这里来（{@code Trip.StopTime} → {@code Siding.getArrivals}）。
 *
 * <p>{@code generatePathDistancesAndTimeSegments}（TSC {@code Siding.java:798}）里对
 * {@code getSpeedLimitMetersPerMillisecond} 只有<b>一处</b>调用（字节码确认），是「当前段限速」。
 * 同一个方法里还有一处「前方更慢的段」（静态 {@code getUpcomingSlowerSpeed}），<b>不用改</b>：
 * 推算里车速从 0 起步、目标又被这里限住，所以永远 ≤ 上限；而在车速 ≤ 上限时
 * {@code min(线路, 上限) < 车速 ⇔ 线路 < 车速}，返回值也一样 —— 加不加上限逐位相同。
 *
 * <p>推算只在「生成路径」时跑。保存侧线只是原地 {@code updateData}，<b>不会</b>重推，
 * 所以最高速一变就要重新生成所在车厂的路径 —— 见 {@link UpdateDataRequestMixin}。
 */
@Mixin(value = Siding.class, remap = false)
public abstract class SidingTimetableSpeedMixin {

	@Redirect(
			method = "generatePathDistancesAndTimeSegments",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/PathData;getSpeedLimitMetersPerMillisecond()D")
	)
	private double yuzimtr$capTimetableSpeed(PathData pathData) {
		return Math.min(pathData.getSpeedLimitMetersPerMillisecond(), SidingMaxSpeedHolder.capMetersPerMillisecond(this));
	}
}
