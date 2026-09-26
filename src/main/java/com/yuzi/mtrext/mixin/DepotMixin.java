package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.DepotRouteLookup;
import com.yuzi.mtrext.RouteIdHolder;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Route;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给每个 SidingPathFinder 贴上它对应的交路 id，供分交路停站时长使用。
 *
 * <p>Depot.generateMainRoute 第 362-363 行：
 * <pre>
 * for (int i = 0; i &lt; platformsInRoute.size() - 1; i++) {
 *     sidingPathFinders.add(new SidingPathFinder&lt;&gt;(data, platformsInRoute.get(i).platform, platformsInRoute.get(i + 1).platform, i));
 * }
 * </pre>
 * 两个列表的索引是 <b>i ↔ i+1</b> 对齐的，而停站时长取的是<b>终点</b>站台
 * （SidingPathFinder.tick 第 109 行用 endSavedRail），所以第 i 个 pathFinder 要配
 * platformsInRoute.get(i+1) 上带的 route。
 *
 * <p>在 TAIL 一次性遍历，不用去动那个 for 循环里的 add()。
 *
 * <p>Depot 把该车厂所有交路的站台<b>串接</b>成一条 platformsInRoute（第 173-183 行两层循环），
 * 所以每个 stopIndex 唯一属于一个交路 —— (routeId, platformId) 这个键是良定义的。
 */
@Mixin(value = Depot.class, remap = false)
public abstract class DepotMixin implements DepotRouteLookup {

	@Shadow @Final private ObjectArrayList<Object> platformsInRoute;
	@Shadow @Final private ObjectArrayList<SidingPathFinder<?, ?, ?, ?>> sidingPathFinders;

	@Override
	public long yuzimtr$routeIdAt(long stopIndex) {
		if (stopIndex < 0 || stopIndex >= platformsInRoute.size()) {
			return 0;
		}
		final Route route = ((PlatformRouteDetailsAccessor) (Object) platformsInRoute.get((int) stopIndex)).yuzimtr$route();
		return route == null ? 0 : route.getId();
	}

	@Inject(method = "generateMainRoute", at = @At("TAIL"))
	private void yuzimtr$tagRouteIds(CallbackInfo ci) {
		if (!Config.get().perRouteDwellTime) {
			return;
		}
		for (int i = 0; i < sidingPathFinders.size(); i++) {
			final int endIndex = i + 1;
			if (endIndex >= platformsInRoute.size()) {
				break;
			}
			final Route route = ((PlatformRouteDetailsAccessor) (Object) platformsInRoute.get(endIndex)).yuzimtr$route();
			// route 为 null 是正常的：i == 0 那一项故意置空（Depot.java:177-178 的注释）
			((RouteIdHolder) (Object) sidingPathFinders.get(i)).yuzimtr$setRouteId(route == null ? 0 : route.getId());
		}
		if (Config.get().debugDwellTime) {
			YuziMtrExt.LOGGER.info("分交路停站时长：已给 {} 个 SidingPathFinder 贴上交路 id", sidingPathFinders.size());
		}
	}
}
