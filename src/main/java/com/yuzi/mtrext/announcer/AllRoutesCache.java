package com.yuzi.mtrext.announcer;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import javax.annotation.Nullable;

/**
 * 客户端手上的「全量交路」快照，只给广播器的交路选择器用。
 *
 * <p>为什么要单独存一份，而不是放宽 MTR 自己的 {@code simplifiedRoutes}：那份数据同时喂给
 * 站台线路图（{@code RouteMapGenerator.getRouteStream}，只按「这条交路停不停本站台」过滤）
 * 和铁路标志。把隐藏交路灌进去，它们就会出现在途经站台的线路图和 PIDS 上 ——
 * 而那正是「隐藏」要防的事。所以另开一条通道，MTR 的那份一个字节都不动。
 *
 * <p>这个类两端都会被加载（{@link PacketAllRoutes} 引用它），所以<b>不许出现客户端专有类型</b>。
 * 转成 {@code DashboardListItem} 的那一步放在客户端的 mixin 里。
 */
public final class AllRoutesCache {

	private AllRoutesCache() {
	}

	@Nullable
	private static ObjectArrayList<RouteEntry> routes;

	public static void put(ObjectArrayList<RouteEntry> newRoutes) {
		routes = newRoutes;
	}

	/** 还没收到服务端答复时返回 null —— 调用方应当退回上游原本的列表。 */
	@Nullable
	public static ObjectArrayList<RouteEntry> get() {
		return routes;
	}

	/** 退出世界时清掉，免得换存档后拿到上一个世界的交路。 */
	public static void clear() {
		routes = null;
	}
}
