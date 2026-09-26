package com.yuzi.mtrext;

import com.yuzi.mtrext.mixin.VehicleStateAccessor;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 发车诊断：在<b>真正做发车决策的那一刻</b>把侧线状态整个打出来。
 *
 * <p>第一版是每 15 秒定时采样，结果 8 次采样 {@code matchDeparture()} 全是 -1 ——
 * 班距 100 秒，定时采样几乎不可能撞上发车那一瞬间，等于什么都没测到。
 * 所以改成事件触发：{@code matchDeparture()} 一返回有效下标就打，
 * 同一个下标在同一个匹配窗口内只打一次（窗口关掉后重新计），外加 60 秒一次心跳看稳态。
 *
 * <p>要分清的两种情况：
 * <ul>
 * <li><b>号在正常轮转</b>：每次决策的号都不同、都发得出去 —— 没卡，只是班距本来就长。</li>
 * <li><b>同一个号反复被占</b>：决策时总撞上同一个号，占它的还是那辆车、railProgress
 *     也不怎么动 —— 那才是真卡，得去看那辆车为什么跑不完。</li>
 * </ul>
 *
 * <p>用 {@code debugDispatchPattern}（对车厂名做正则）挑线，空串关闭。
 */
public final class DispatchDiag {

	private DispatchDiag() {
	}

	private static final long HEARTBEAT_MILLIS = 60000;
	private static final Map<Long, Integer> LAST_INDEX = new HashMap<>();
	private static final Map<Long, Long> LAST_HEARTBEAT = new HashMap<>();

	public static void tick(Siding siding, int matchedDepartureIndex, long repeatInterval) {
		final Pattern pattern = Config.get().debugDispatchPatternCompiled();
		if (pattern == null || !pattern.matcher(siding.getDepotName()).find()) {
			return;
		}

		final long sidingId = siding.getId();
		final boolean isDecision;
		if (matchedDepartureIndex < 0) {
			LAST_INDEX.remove(sidingId);   // 匹配窗口关了，同一个号下次再来还要打
			isDecision = false;
		} else {
			isDecision = !Integer.valueOf(matchedDepartureIndex).equals(LAST_INDEX.get(sidingId));
			LAST_INDEX.put(sidingId, matchedDepartureIndex);
		}

		final long now = System.currentTimeMillis();
		final Long lastHeartbeat = LAST_HEARTBEAT.get(sidingId);
		final boolean isHeartbeat = lastHeartbeat == null || now - lastHeartbeat >= HEARTBEAT_MILLIS;
		if (!isDecision && !isHeartbeat) {
			return;
		}
		if (isHeartbeat) {
			LAST_HEARTBEAT.put(sidingId, now);
		}

		final SidingPaths paths = (SidingPaths) (Object) siding;
		final LongArrayList departures = paths.yuzimtr$departures();

		Vehicle blocker = null;
		int idle = 0;
		int orphaned = 0;
		for (final Vehicle vehicle : paths.yuzimtr$vehicles()) {
			if (matchedDepartureIndex >= 0 && vehicle.getDepartureIndex() == matchedDepartureIndex) {
				blocker = vehicle;
			}
			if (!vehicle.getIsOnRoute()) {
				idle++;
			} else if (vehicle.getDepartureIndex() < 0) {
				orphaned++;
			}
		}

		final StringBuilder builder = new StringBuilder();
		// 发车表跨度 vs 循环周期：跨度更大的话，超出周期的那些下标永远轮不到
		final long span = departures.isEmpty() ? 0
				: departures.getLong(departures.size() - 1) - departures.getLong(0);
		final long reachable = repeatInterval <= 0 ? departures.size()
				: Math.min(departures.size(), span == 0 ? departures.size()
						: repeatInterval * departures.size() / Math.max(1, span) + 1);
		builder.append(String.format("发车诊断%s [%s / 侧线 %s] 发车表 %d 条（跨度 %d，周期 %d，估计可达前 %d 个），车次 %d 辆（待发 %d，无号在途 %d）",
				isDecision ? "★决策" : "·心跳", siding.getDepotName(), siding.getName(),
				departures.size(), span, repeatInterval, reachable,
				paths.yuzimtr$vehicles().size(), idle, orphaned));

		if (matchedDepartureIndex < 0) {
			builder.append("，当前无班次到点");
		} else if (blocker == null) {
			builder.append(String.format("，班次 %d 到点 → 可发车", matchedDepartureIndex));
		} else {
			final VehicleStateAccessor state = (VehicleStateAccessor) (Object) blocker;
			builder.append(String.format("，班次 %d 到点 → ★被占住不发车（占号的车 进度=%.1f 速度=%.4f 在途=%s 发车时刻=%d）",
					matchedDepartureIndex, state.yuzimtr$railProgress(), state.yuzimtr$speed(),
					blocker.getIsOnRoute(), state.yuzimtr$sidingDepartureTime()));
		}

		if (isDecision) {
			for (final Vehicle vehicle : paths.yuzimtr$vehicles()) {
				final VehicleStateAccessor state = (VehicleStateAccessor) (Object) vehicle;
				builder.append(String.format("%n    号=%-4d 在途=%-5s 进度=%10.1f 速度=%.4f 发车时刻=%d",
						vehicle.getDepartureIndex(), vehicle.getIsOnRoute(),
						state.yuzimtr$railProgress(), state.yuzimtr$speed(), state.yuzimtr$sidingDepartureTime()));
			}
		}
		YuziMtrExt.LOGGER.info(builder.toString());
	}
}
