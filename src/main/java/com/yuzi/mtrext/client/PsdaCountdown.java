package com.yuzi.mtrext.client;

import com.yuzi.mtrext.psd.RopeBarriers;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Vehicle;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongImmutableList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.InitClient;
import org.mtr.mod.data.ArrivalsCacheClient;

/**
 * 「这排屏蔽门旁边那列车，还有多久发车」。
 *
 * <p>为什么需要它：门从开始关到关完固定是 {@link Vehicle#DOOR_MOVE_TIME}（3200ms），
 * 而绳子要在关完前若干秒落到底 —— 纯反应式（等车门开度开始下降才动）只剩一千多毫秒的
 * 下降窗口，看上去就是「唰一下掉下来」，更没法在动作<b>之前</b>响提示音。必须往前看。
 *
 * <p>算法照搬 JCM 的发车倒数器（{@code DepartureTimerRenderer.renderCurated}）——
 * 全是 MTR 的公开 API，比自己去重建停站计时可靠得多：
 * {@link InitClient#findClosePlatform} 找到最近的站台，
 * {@link ArrivalsCacheClient#requestArrivals} 拿到到离站时刻，
 * 再减去 {@code getMillisOffset()} 换算到本地时钟。
 *
 * <p>时间轴（t = 距发车还有多少毫秒）：
 * <ul>
 * <li>t = {@code DOOR_MOVE_TIME + DOOR_DELAY} = 4200：车门开始关</li>
 * <li>t = {@code DOOR_DELAY} = 1000：车门关闭完成</li>
 * </ul>
 *
 * <p><b>注意</b>：到站表是缓存 + 网络来的，粒度粗、可能跳变。所以只拿它当<b>触发器</b>
 * （「到点了，开始动」），动画本身用本地时钟插值 —— 直接拿它逐帧驱动会一顿一顿的。
 */
public final class PsdaCountdown {

	private PsdaCountdown() {
	}

	/** 找站台的半径（格），和 JCM 的发车倒数器取一样的值。 */
	private static final int PLATFORM_SEARCH_RADIUS = 5;

	/**
	 * @return 距离发车还有多少毫秒；旁边没有站台、没有班次、或车还没到站就返回
	 *         {@link Long#MIN_VALUE}（调用方据此退回纯反应式行为）
	 */
	public static long millisUntilDeparture(BlockPos blockPos) {
		final ObjectArrayList<Platform> platforms = new ObjectArrayList<>();
		InitClient.findClosePlatform(blockPos, PLATFORM_SEARCH_RADIUS, platforms::add);
		if (platforms.isEmpty()) {
			return Long.MIN_VALUE;
		}

		final ObjectArrayList<ArrivalResponse> arrivals =
				ArrivalsCacheClient.INSTANCE.requestArrivals(LongImmutableList.of(platforms.get(0).getId()));
		final ArrivalResponse firstArrival = Utilities.getElement(arrivals, 0);
		if (firstArrival == null) {
			return Long.MIN_VALUE;
		}

		final long offset = ArrivalsCacheClient.INSTANCE.getMillisOffset();
		final long now = System.currentTimeMillis();
		if (firstArrival.getArrival() - offset > now) {
			return Long.MIN_VALUE; // 还没到站
		}
		return firstArrival.getDeparture() - offset - now;
	}
}
