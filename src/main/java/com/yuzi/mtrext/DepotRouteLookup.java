package com.yuzi.mtrext;

/**
 * 由 stopIndex 反查交路 id。
 *
 * <p>索引关系：{@code Depot.generateMainRoute} 里第 i 个 SidingPathFinder 是
 * {@code new SidingPathFinder<>(data, platformsInRoute.get(i).platform,
 * platformsInRoute.get(i + 1).platform, i)}，而它产出的那个「到站」PathData 带的是
 * {@code stopIndex + 1}（SidingPathFinder.java:109）。所以
 * <b>stopIndex == s 的到站段，对应 platformsInRoute.get(s) 上带的 route</b> ——
 * 和 {@link com.yuzi.mtrext.mixin.DepotMixin} 贴 routeId 时用的是同一套对齐。
 */
public interface DepotRouteLookup {

	/** @return 交路 id；0 表示查不到（比如 index 0 那项上游故意不带 route）。 */
	long yuzimtr$routeIdAt(long stopIndex);
}
