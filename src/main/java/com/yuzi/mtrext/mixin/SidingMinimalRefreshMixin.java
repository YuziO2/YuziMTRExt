package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.SidingPaths;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 MTR3 的「最小刷新」加回来：刷新路径后，如果路径其实没变，就不动车次。
 *
 * <p>MTR3 的做法在 {@code Siding.generateRoute} 末尾（MTR-3.2.2）：
 * <pre>
 * if (tempRepeatIndex1 != repeatIndex1 || tempRepeatIndex2 != repeatIndex2) {
 *     trains.clear();
 * }
 * </pre>
 * path / timeSegments / platformTimes 照样整个换掉，但正在跑的车留着。
 * MTR4 变成了无条件清，而且清得更早 —— {@code generateRoute} 的第一行就是
 * {@code vehicles.clear()}（字节码 invoke[0]，寻路还没开始），
 * {@code generatePathDistancesAndTimeSegments} 开头又清一次。
 *
 * <p><b>做法：两处 clear 都拦掉，让车带着自己的路径照常跑完整个重新生成过程，
 * 等末尾再比指纹，变了才清。</b>
 *
 * <p>之所以能这么干：{@code Vehicle} <b>压根不碰</b> Siding 的
 * {@code pathMainRoute} / {@code pathSidingToMainRoute} / {@code pathMainRouteToSiding}
 * （全文 grep 为 0），它跑的是自己那份 {@code vehicleExtraData.immutablePath}。
 * 所以重新生成期间 Siding 的三个列表是空的也不影响它。
 *
 * <p>这比「快照 → 清 → 反序列化恢复」小且好，因为那样有两个必然的副作用：
 * <ul>
 * <li>{@code Vehicle} 有 13 个字段而 {@code vehicle.json} 只序列化 8 个，
 *     {@code deviation} / {@code doorCooldown} / {@code stoppingCooldown} /
 *     {@code atoOverride} / {@code manualCooldown} / {@code deviationSpeedAdjustment}
 *     加整个 {@code vehicleExtraData} 全都会丢。丢 {@code deviation} 会让车不再追点，
 *     丢 {@code doorCooldown} 可能让车提前一个 DOOR_MOVE_TIME 发车。</li>
 * <li>重新生成要跨若干 tick，那期间车如果不存在就等于原地冻结，
 *     而挂钟照走 —— 每刷一次就攒一点晚点。</li>
 * </ul>
 * 现在这两条都不存在了：车对象从头到尾没被碰过。
 *
 * <p>判据比 MTR3 更严也更对。MTR3 只比 repeatIndex，主线轨道换了而 repeatIndex 恰好
 * 没变时，车的 railProgress 就指到别处去了。车次持久化的状态里只有 {@code railProgress}
 * 依赖路径（沿拼接后路径的距离），所以判据就是「距离映射没变」：逐段比
 * {@code (hexId, stopIndex, startDistance, endDistance)}，<b>不比 dwellTime，也不比 speedLimit</b>。
 * 于是改停站时间 / 改速度等级 / 改信号量都保车，改轨道几何或寻路结果变了才清。
 *
 * <p>留一个已知残留：{@code timeSegments} 照样重建，所以<b>已经驶过改动点</b>的车
 * 下一次进站时 {@code updateDeviation()} 会拿旧的 {@code sidingDepartureTime} 去比新时刻表。
 * 还没驶到改动点的车不受影响 —— {@code getTimeAlongRoute(railProgress)} 在改动点之前逐位相同。
 */
@Mixin(value = Siding.class, remap = false)
public abstract class SidingMinimalRefreshMixin implements SidingPaths {

	@Shadow
	@Final
	private ObjectArrayList<PathData> pathSidingToMainRoute;
	@Shadow
	@Final
	private ObjectArrayList<PathData> pathMainRoute;
	@Shadow
	@Final
	private ObjectArrayList<PathData> pathMainRouteToSiding;
	@Shadow
	@Final
	private ObjectArraySet<Vehicle> vehicles;
	@Shadow
	@Final
	private LongArrayList departures;

	@Unique
	private boolean yuzimtr$refreshing;
	@Unique
	private String yuzimtr$oldFingerprint;

	@Override
	public ObjectArraySet<Vehicle> yuzimtr$vehicles() {
		return vehicles;
	}

	@Override
	public LongArrayList yuzimtr$departures() {
		return departures;
	}

	@Override
	public ObjectArrayList<PathData> yuzimtr$pathSidingToMainRoute() {
		return pathSidingToMainRoute;
	}

	@Override
	public ObjectArrayList<PathData> yuzimtr$pathMainRoute() {
		return pathMainRoute;
	}

	@Override
	public ObjectArrayList<PathData> yuzimtr$pathMainRouteToSiding() {
		return pathMainRouteToSiding;
	}

	/**
	 * {@code generateRoute} 里唯一的 {@code ObjectArraySet.clear()} 就是
	 * {@code vehicles.clear()}（字节码核对过，invoke[0]）。拦下来，同时记住旧指纹 ——
	 * 这个点上三个 path 列表还都是旧的。
	 */
	@Redirect(
			method = "generateRoute(Lorg/mtr/core/data/Platform;Lorg/mtr/core/data/Platform;IJ)V",
			at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;clear()V")
	)
	private void yuzimtr$keepVehiclesWhileGenerating(ObjectArraySet<Vehicle> instance) {
		if (!Config.get().minimalPathRefresh) {
			instance.clear();
			return;
		}
		// 刷新还没做完又来一次（比如连着保存两条侧线，各自触发一次自动刷新）：保留第一次记下的旧指纹。
		// 第二次进来时 generateRoute 开头已经把 pathSidingToMainRoute / pathMainRouteToSiding 清掉了，
		// 这时算出来的「旧指纹」是残缺的，生成完必然对不上 → 误判路径变了、把车清掉。
		// 而在途的车跑的正是第一次刷新之前那份路径，拿它当基准本来就是对的。
		if (!yuzimtr$refreshing) {
			yuzimtr$refreshing = true;
			yuzimtr$oldFingerprint = yuzimtr$fingerprint();
		}
	}

	/**
	 * 同一个方法里也只有一处 {@code ObjectArraySet.clear()}（invoke[0]）。
	 * 只在「不是我们发起的刷新」时才照常清 —— 也就是 {@code Siding.init()}
	 * 那条路（开服加载，那时 vehicles 本来就是空的，车在之后才从存档挂上去）。
	 */
	@Redirect(
			method = "generatePathDistancesAndTimeSegments",
			at = @At(value = "INVOKE", target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArraySet;clear()V")
	)
	private void yuzimtr$keepVehiclesWhileRecomputing(ObjectArraySet<Vehicle> instance) {
		if (!yuzimtr$refreshing) {
			instance.clear();
		}
	}

	/**
	 * 用 {@code @At("RETURN")} 而不是 TAIL：这个方法有提前返回的分支（寻路失败时清空三个列表，
	 * 那时新指纹是空的，和旧的必然不等，于是清车 —— 正确）。标志位用完即清，多次命中只生效一次。
	 */
	@Inject(method = "generatePathDistancesAndTimeSegments", at = @At("RETURN"))
	private void yuzimtr$clearOnlyIfPathChanged(CallbackInfo ci) {
		if (!yuzimtr$refreshing) {
			return;
		}
		yuzimtr$refreshing = false;
		if (vehicles.isEmpty()) {
			return;
		}
		final Siding siding = (Siding) (Object) this;
		if (yuzimtr$fingerprint().equals(yuzimtr$oldFingerprint)) {
			if (Config.get().debugPathRefresh) {
				YuziMtrExt.LOGGER.info("最小刷新: {} 侧线 {} 路径未变，{} 个班次原样保留",
						siding.getDepotName(), siding.getName(), vehicles.size());
			}
		} else {
			if (Config.get().debugPathRefresh) {
				YuziMtrExt.LOGGER.info("最小刷新: {} 侧线 {} 路径变了，清空 {} 个班次",
						siding.getDepotName(), siding.getName(), vehicles.size());
			}
			vehicles.clear();
		}
	}

	/**
	 * 路径的距离映射指纹。刻意不含 dwellTime 和 speedLimit —— 那两个正是要允许改的东西，
	 * 而且都不影响 railProgress 的含义（startDistance/endDistance 是几何累加距离）。
	 */
	@Unique
	private String yuzimtr$fingerprint() {
		final StringBuilder builder = new StringBuilder();
		yuzimtr$appendFingerprint(builder, pathSidingToMainRoute);
		builder.append('|');
		yuzimtr$appendFingerprint(builder, pathMainRoute);
		builder.append('|');
		yuzimtr$appendFingerprint(builder, pathMainRouteToSiding);
		return builder.toString();
	}

	@Unique
	private static void yuzimtr$appendFingerprint(StringBuilder builder, ObjectArrayList<PathData> path) {
		for (final PathData pathData : path) {
			builder.append(pathData.getHexId(false)).append(',')
					.append(pathData.getStopIndex()).append(',')
					.append(pathData.getStartDistance()).append(',')
					.append(pathData.getEndDistance()).append(';');
		}
	}
}
