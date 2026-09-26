package com.yuzi.mtrext;

import org.mtr.core.tool.Utilities;

import javax.annotation.Nullable;

/**
 * 侧线上的「车辆最高速」。由 {@link com.yuzi.mtrext.mixin.SidingMaxSpeedSchemaMixin} 挂到
 * {@code SidingSchema} 上，跟着 MTR 自己的 Siding 数据落盘、联网。
 *
 * <p>单位 km/h，<b>0 = 不限</b>。实际生效的限速 = min(线路限速, 车辆最高速)。
 *
 * <h2>为什么要在「读」的地方套，而不是改 PathData</h2>
 *
 * <p>整个 TSC 里「这段轨道限速多少」只有一个来源 {@code PathData.getSpeedLimitMetersPerMillisecond()}。
 * 但同一个车厂的所有侧线<b>共用一份路径</b>（{@code Depot.path}，车次的 {@code immutablePath}
 * 持有的也是同一批对象），而最高速是按侧线设的 —— 写进 {@code PathData} 会串到同车厂的
 * 其他侧线上。所以只能在两个读它的地方按侧线套上限：时刻表推算和车实际跑。
 */
public interface SidingMaxSpeedHolder {

	long yuzimtr$getMaxSpeedKmh();

	void yuzimtr$setMaxSpeedKmh(long maxSpeedKmh);

	/** 界面上允许填的上限。再大就超过所有轨道等级了，填了等于没填。 */
	long MAX_SPEED_KMH_LIMIT = 9999;

	/**
	 * @return 这条侧线的车辆最高速（m/ms）；不限时返回 {@link Double#POSITIVE_INFINITY}
	 */
	static double capMetersPerMillisecond(@Nullable Object siding) {
		if (!(siding instanceof SidingMaxSpeedHolder) || !Config.get().vehicleMaxSpeed) {
			return Double.POSITIVE_INFINITY;
		}
		final long kmh = ((SidingMaxSpeedHolder) siding).yuzimtr$getMaxSpeedKmh();
		return kmh <= 0 ? Double.POSITIVE_INFINITY : Utilities.kilometersPerHourToMetersPerMillisecond(kmh);
	}
}
