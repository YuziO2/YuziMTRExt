package com.yuzi.mtrext;

import com.yuzi.mtrext.mixin.PathDataAccessor;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * 把站台上的停站时长（含分交路覆盖）原地写进已生成的路径里，不用重新生成路径。
 *
 * <p>为什么这样可行：{@code VehicleExtraData.createPathData} 是 {@code addAll}，
 * 所以 {@code Depot.path} / {@code Siding.pathMainRoute} / 每辆在跑的车的
 * {@code immutablePath} 持有的是同一批 {@code PathData} 对象。
 * 改一个对象的 {@code dwellTime}，全都立刻看得见。
 *
 * <p><b>一个说清楚的代价</b>：{@code Siding.timeSegments} / {@code trips} /
 * {@code platformTripStopTimes} 是 {@code generatePathDistancesAndTimeSegments}
 * 按旧停站时长算出来的，这里不重算 —— 那个方法重复调用不幂等
 * （{@code pathSidingToMainRoute.add(pathMainRoute.removeFirst())} 和
 * {@code pathMainRouteToSiding.removeFirst()} 每调一次就搬一格）。
 * 所以<b>车的实际停站立刻变，屏显/时刻表的到发推算要等下一次刷新路径才跟上</b>。
 * 而刷新现在不再清车（见 {@link com.yuzi.mtrext.mixin.SidingMinimalRefreshMixin}），
 * 所以想让推算也跟上，随时刷一次就行，代价只是一次寻路。
 *
 * <p>整个遍历是幂等的：每次都从站台重新算一遍应有的值再写回去，
 * 所以在 {@code UpdateDataRequest.update()} 之后无脑跑一遍就够，不必知道改了哪个站台。
 */
public final class DwellTimeApplier {

	private DwellTimeApplier() {
	}

	public static void applyAll(Simulator simulator) {
		int patched = 0;
		for (final Depot depot : simulator.depots) {
			patched += apply(simulator, depot, depot.getPath());
			for (final Siding siding : depot.savedRails) {
				final SidingPaths paths = (SidingPaths) (Object) siding;
				// pathMainRoute 和 depot.getPath() 是同一批对象，上面那行已经覆盖；
				// 但为了不依赖这个巧合（比如 repeatInfinitely 时会搬动首段），还是各走一遍。
				patched += apply(simulator, depot, paths.yuzimtr$pathSidingToMainRoute());
				patched += apply(simulator, depot, paths.yuzimtr$pathMainRoute());
				patched += apply(simulator, depot, paths.yuzimtr$pathMainRouteToSiding());
				// 关键：在跑的车手上是自己那份**快照**，不一定还是上面三个列表里的对象。
				// SidingPathFinder.generatePathDataDistances 每次生成路径都会把所有
				// PathData 换成新拷贝（它是 path.clear() 之后 new PathData(old, …) 重建的），
				// 而最小刷新又不动车次 —— 所以刷新之后，在途的车拿着的是一批被孤立的旧对象。
				// 不连它们一起改的话，症状就是「新发的车按新时间停，老车一直按旧时间跑，
				// 只有把车全删了才恢复」。
				for (final Vehicle vehicle : paths.yuzimtr$vehicles()) {
					patched += apply(simulator, depot, vehicle.vehicleExtraData.immutablePath);
				}
			}
		}
		if (patched > 0 && Config.get().debugDwellTime) {
			YuziMtrExt.LOGGER.info("停站时长：原地更新了 {} 个到站段，未重新生成路径", patched);
		}
	}

	private static int apply(Simulator simulator, Depot depot, java.util.List<PathData> path) {
		int patched = 0;
		for (int i = 0; i < path.size(); i++) {
			final PathData pathData = path.get(i);
			final long savedRailBaseId = pathData.getSavedRailBaseId();
			if (savedRailBaseId == 0) {
				// 区间中间的普通轨道段，不是到站段（SidingPathFinder.java:110-113 传的是 0）
				continue;
			}
			final Platform platform = simulator.platformIdMap.get(savedRailBaseId);
			if (platform == null) {
				// 侧线（车厂端）之类，不是站台
				continue;
			}
			final long routeId = ((DepotRouteLookup) (Object) depot).yuzimtr$routeIdAt(pathData.getStopIndex());
			final Long override = routeId == 0 || !Config.get().perRouteDwellTime
					? null
					: ((PlatformDwellOverrides) (Object) platform).yuzimtr$getDwellOverride(routeId);
			final long dwellTime = Math.max(1, override == null ? platform.getDwellTime() : override);
			if (dwellTime != pathData.getDwellTime()) {
				((PathDataAccessor) (Object) pathData).yuzimtr$setDwellTime(dwellTime);
				patched++;
			}
		}
		return patched;
	}
}
