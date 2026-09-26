package com.yuzi.mtrext.announcer;

import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * 交路在选择器里需要的最小信息：id、名字、颜色，外加它停靠的站台。
 *
 * <p>刻意<b>不</b>用 MTR 的 {@code SimplifiedRoute} 或 {@code DashboardListItem}：前者由服务端
 * 按半径和隐藏与否筛过（正是我们要绕开的），后者是客户端专有类型而这个包服务端也要加载。
 * 只带原始类型和一个 long 列表，两端都安全。
 *
 * <p>{@link #platformIds} 是给站台广播器用的 —— 它要问的是「这个站台停哪些交路」，
 * 而客户端手上唯一带这个映射的是 {@code SimplifiedRoute.getPlatformIndex}，
 * 那份数据又<b>不含隐藏交路</b>（{@code SimplifiedRoute.addToList} 第一条字节码就是
 * {@code getHidden() → ifne return}）。所以顺着这条已有的通道把站台也带下来。
 */
public final class RouteEntry {

	public final long id;
	public final String name;
	public final int color;

	/** 这条交路依次停靠的站台 id。顺序保留，虽然选择器目前只用 contains。 */
	public final LongArrayList platformIds;

	public RouteEntry(long id, String name, int color, LongArrayList platformIds) {
		this.id = id;
		this.name = name;
		this.color = color;
		this.platformIds = platformIds;
	}
}
