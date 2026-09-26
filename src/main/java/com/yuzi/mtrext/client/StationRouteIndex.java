package com.yuzi.mtrext.client;

import com.yuzi.mtrext.announcer.AllRoutesCache;
import com.yuzi.mtrext.announcer.RouteEntry;
import org.mtr.core.data.Platform;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.Station;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;

import java.util.Collections;

/**
 * 「这个方块所在车站有哪些站台」「这个站台停哪些交路」—— 站台广播器的界面和剪贴板共用。
 *
 * <p>交路数据优先用我们自己那条全量通道（{@link AllRoutesCache}，含隐藏交路、不限距离），
 * 答复还没到时退回 {@code simplifiedRoutes}。后者<b>不含隐藏交路</b>
 * （{@code SimplifiedRoute.addToList} 第一条字节码就是 {@code getHidden() → ifne return}），
 * 而且只带客户端请求半径内的站台 —— 退化成「查不到远处和隐藏的交路」，不报错。
 */
public final class StationRouteIndex {

	private StationRouteIndex() {
	}

	/** 找不到所在车站时，退回找这个半径内最近的站台。 */
	private static final int CLOSE_PLATFORM_RADIUS = 8;

	/**
	 * 方块所在车站的全部站台，<b>已排序</b>（和 MTR 站台选择器的排法一致），
	 * 所以「第一个匹配的站台」是确定的，不随 {@code ObjectArraySet} 的迭代顺序变。
	 */
	public static ObjectArrayList<Platform> platformsNear(BlockPos blockPos) {
		final ObjectArrayList<Platform> platforms = new ObjectArrayList<>();
		final Station station = InitClient.findStation(blockPos);
		if (station != null) {
			platforms.addAll(station.savedRails);
		} else {
			InitClient.findClosePlatform(blockPos, CLOSE_PLATFORM_RADIUS, platforms::add);
		}
		Collections.sort(platforms);
		return platforms;
	}

	/**
	 * 停靠这个站台的交路（{@code platformId} 为 0 时不筛站台，返回全部）。
	 *
	 * <p>以全量缓存为准；缓存里<b>没有</b>的交路再从 {@code simplifiedRoutes} 补。
	 * 「缓存里没有」有三种来源，都该退回而不是当作不存在：
	 * <ul>
	 * <li>全量答复还没到；</li>
	 * <li>缓存是打开主界面时取的，之后才在仪表板里新建的交路；</li>
	 * <li>服务端读它的停靠站台时出错，整条跳过了（见 {@code PacketAllRoutes}）。</li>
	 * </ul>
	 * 已删除的交路两边都没有，自然查不到。
	 */
	public static ObjectArrayList<RouteEntry> routeEntriesAt(long platformId) {
		final ObjectArrayList<RouteEntry> routeEntries = new ObjectArrayList<>();
		final LongAVLTreeSet cachedRouteIds = new LongAVLTreeSet();
		final ObjectArrayList<RouteEntry> allRoutes = AllRoutesCache.get();
		if (allRoutes != null) {
			for (final RouteEntry routeEntry : allRoutes) {
				cachedRouteIds.add(routeEntry.id);
				if (platformId == 0 || routeEntry.platformIds.contains(platformId)) {
					routeEntries.add(routeEntry);
				}
			}
		}
		for (final SimplifiedRoute simplifiedRoute : MinecraftClientData.getInstance().simplifiedRoutes) {
			if (!cachedRouteIds.contains(simplifiedRoute.getId()) && (platformId == 0 || simplifiedRoute.getPlatformIndex(platformId) >= 0)) {
				routeEntries.add(new RouteEntry(simplifiedRoute.getId(), simplifiedRoute.getName(), simplifiedRoute.getColor(), new LongArrayList()));
			}
		}
		return routeEntries;
	}

	/** 停靠这个站台的全部交路 id。 */
	public static LongAVLTreeSet routesAt(long platformId) {
		final LongAVLTreeSet routeIds = new LongAVLTreeSet();
		for (final RouteEntry routeEntry : routeEntriesAt(platformId)) {
			routeIds.add(routeEntry.id);
		}
		return routeIds;
	}

	public static boolean routeStopsAt(long routeId, long platformId) {
		final ObjectArrayList<RouteEntry> allRoutes = AllRoutesCache.get();
		if (allRoutes != null) {
			for (final RouteEntry routeEntry : allRoutes) {
				if (routeEntry.id == routeId) {
					return routeEntry.platformIds.contains(platformId);
				}
			}
		}
		// 缓存里没有这条交路 —— 退回，理由见 routeEntriesAt。
		final SimplifiedRoute simplifiedRoute = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
		return simplifiedRoute != null && simplifiedRoute.getPlatformIndex(platformId) >= 0;
	}
}
