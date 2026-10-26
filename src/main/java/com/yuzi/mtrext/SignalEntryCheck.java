package com.yuzi.mtrext;

import com.yuzi.mtrext.mixin.RailSchemaSignalColorsAccessor;
import com.yuzi.mtrext.mixin.RailSignalStateAccessor;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;

import java.util.List;

/**
 * 进闭塞前把整段闭塞查一遍：路线上属于这个闭塞的任何一段被别的车占着（不论颜色），就不进。
 *
 * <p>上游 {@code Vehicle.checkAndBlockSignal} 只查闭塞<b>首段</b>自己的占用表。首段是纯绿时表里只有绿色，
 * 看不到闭塞中间多色段 X 上的<b>蓝色</b>占用 —— 于是车进了绿闭塞、把整个绿分量占掉、停在 X 前；
 * 蓝色那边的车要经 X 出来时又被这个绿色占用挡住，两车永久互锁。MTR3 进闭塞是 all-or-nothing
 * （{@code SignalBlocks.occupy}：所属闭塞里任何一段被别的车占就整个不登记），没有这个问题。
 * 详见 {@code docs/agent-notes/scheduling-and-speed.md} 的「多色闭塞互锁」一节。
 *
 * <p>只在列车<b>还没进入</b>这个闭塞时加查；已经进入的保持上游行为（遇到被占段就停在它前面）。
 */
public final class SignalEntryCheck {

	private SignalEntryCheck() {
	}

	/**
	 * @param path         车次的完整路径
	 * @param firstIndex   {@code checkAndBlockSignal} 正在查的那一段（闭塞首段）
	 * @param railProgress 车头当前的路径里程
	 * @return {@code true} 表示应当停在首段之前
	 */
	public static boolean blocksEntry(List<PathData> path, int firstIndex, long vehicleId, double railProgress) {
		final PathData first = path.get(firstIndex);
		// 已经进入就不加查：车头越过了首段起点，或首段的每种颜色都已归自己。
		// 不能用「表里出现过自己的 id」：车身压在多色段上时，另一种颜色会被动泛洪到下一个闭塞的首段，
		// 那不代表进入过它（三色串联场景下这样判定照样互锁，GameTest 里有这个场景）。
		// 车头判定放最前：前瞻里大多数调用查的就是车头所在段，省得每次都新建颜色集。
		if (first.getStartDistance() < railProgress) {
			return false;
		}
		// getSignalColors 每次都新建一个 set，可以放心原地取交集
		final IntAVLTreeSet chain = first.getSignalColors();
		if (chain.isEmpty() || holdsEveryColor(first.getRail(), vehicleId)) {
			return false;
		}

		for (int i = firstIndex + 1; i < path.size(); i++) {
			final PathData pathData = path.get(i);
			// 逐段取交集：只沿「与首段同色、且沿路线相连」的段走。
			// 上游的走法是「与首段颜色集有交集就算同一闭塞」，首段多色时会把后面另一个不相连的同色闭塞也算进来。
			chain.retainAll(pathData.getSignalColors());
			if (chain.isEmpty()) {
				return false;
			}
			if (pathData.isSignalBlocked(vehicleId, Rail.BlockReservation.DO_NOT_RESERVE)) {
				return true;
			}
		}
		return false;
	}

	private static boolean holdsEveryColor(Rail rail, long vehicleId) {
		final RailSignalStateAccessor state = (RailSignalStateAccessor) (Object) rail;
		for (final long color : ((RailSchemaSignalColorsAccessor) (Object) rail).yuzimtr$signalColors()) {
			if (!heldBy(state.yuzimtr$preBlocked(), color, vehicleId)
					&& !heldBy(state.yuzimtr$currentlyBlocked(), color, vehicleId)
					&& !heldBy(state.yuzimtr$preBlockedOld(), color, vehicleId)
					&& !heldBy(state.yuzimtr$currentlyBlockedOld(), color, vehicleId)) {
				return false;
			}
		}
		return true;
	}

	private static boolean heldBy(Long2LongAVLTreeMap blockedVehicleIds, long color, long vehicleId) {
		return blockedVehicleIds.containsKey(color) && blockedVehicleIds.get(color) == vehicleId;
	}
}
