package com.yuzi.mtrext;

/**
 * 鸭子类型接口，由 SidingPathFinderMixin 实现。
 *
 * <p>SidingPathFinder 只拿到起点/终点站台和 stopIndex，不知道属于哪个交路。
 * 但 Depot.generateMainRoute 构造它们时手上有 —— platformsInRoute 里带着 route
 * （Depot.java:179），而且 sidingPathFinders 与 platformsInRoute 的索引是 i ↔ i+1
 * 对齐的，所以在那里一次性贴上。
 */
public interface RouteIdHolder {

	void yuzimtr$setRouteId(long routeId);

	long yuzimtr$getRouteId();
}
