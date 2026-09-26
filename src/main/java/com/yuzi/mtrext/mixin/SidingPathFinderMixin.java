package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.PlatformDwellOverrides;
import com.yuzi.mtrext.RouteIdHolder;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.core.data.Platform;
import org.mtr.core.path.SidingPathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 分交路停站时长的落地点。
 *
 * <p>MTR4 把停站时长压平在 SidingPathFinder.tick 第 109 行：
 * <pre>
 * path.add(new PathData(rail, endSavedRail.getId(),
 *     endSavedRail instanceof Platform ? ((Platform) endSavedRail).getDwellTime() : 1,
 *     stopIndex + 1, position1, position2));
 * </pre>
 * 只认站台自己的 dwellTime，交路信息在这一层已经丢了。
 * 这里 {@code @Redirect} 那一次 getDwellTime()（字节码核对过：tick 里恰好一处，
 * owner 是 {@code Lorg/mtr/core/data/Platform;getDwellTime()J}），
 * 用 DepotMixin 贴上的交路 id 去查这个站台自己的覆盖表。
 *
 * <p>查不到就返回原值 —— 这就是「真正的继承」：改站台总值，所有未自定义的交路跟着变。
 *
 * <p>注在生成期而不是运行期，是为了让运行时行为和时刻表算出来的到发时刻一致：
 * 覆盖值一旦烤进 {@code PathData.dwellTime}，Vehicle.java:324 的实际停站
 * 和 Siding.java:821/854 的时刻表推算读的就是同一个数。
 * 代价是改完值要重新生成一次该车厂的路径才生效。
 */
@Mixin(value = SidingPathFinder.class, remap = false)
public abstract class SidingPathFinderMixin implements RouteIdHolder {

	@Unique
	private long yuzimtr$routeId;

	@Override
	public void yuzimtr$setRouteId(long routeId) {
		this.yuzimtr$routeId = routeId;
	}

	@Override
	public long yuzimtr$getRouteId() {
		return yuzimtr$routeId;
	}

	@Redirect(
			method = "tick(J)Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Platform;getDwellTime()J")
	)
	private long yuzimtr$perRouteDwellTime(Platform platform) {
		final long defaultDwellTime = platform.getDwellTime();
		if (!Config.get().perRouteDwellTime || yuzimtr$routeId == 0) {
			return defaultDwellTime;
		}
		final Long override = ((PlatformDwellOverrides) (Object) platform).yuzimtr$getDwellOverride(yuzimtr$routeId);
		if (Config.get().debugDwellTime) {
			YuziMtrExt.LOGGER.info("停站时长: 交路 {} 站台 {} -> {} ms{}",
					yuzimtr$routeId, platform.getId(),
					override == null ? defaultDwellTime : override,
					override == null ? "（跟随总值）" : "（自定义）");
		}
		return override == null ? defaultDwellTime : override;
	}
}
