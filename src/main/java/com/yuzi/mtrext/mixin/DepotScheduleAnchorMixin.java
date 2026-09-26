package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.SidingPaths;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 锚点防跳：不让「游戏时钟跳变」把发车表整体平移。
 *
 * <p><b>为什么。</b>useRealTime=false 的车厂，发车表锚定在
 * {@code Simulator.getMillisOfGameMidnight()}（Depot.java:283 取值、:304 写进每条 departure），
 * 而那个值 = {@code (currentMillis - gameMillis) mod gameMillisPerDay}（Simulator.java:187-189），
 * gameMillis 来自 mod 端每 25 秒一次的 SET_TIME（Init.java:196,230，发的是
 * {@code (dayTime + 6000) * 50}）。于是 {@code /time set}、睡觉跳夜、重启补时都会让锚点
 * 突变几十万毫秒 —— 发车表相位跟着回退。回退量小于单程时间时，被回放的每一班都撞上
 * {@code Siding.simulateTrain:315} 的同号在途去重（anyMatch departureIndex 相同）被<b>静默吞掉</b>，
 * 连续吞掉的班次数 = 回退量 ÷ 班距，这就是环线上「宽度好几个班距、绕圈不自愈的空洞」。
 * 实证（8月24日 latest.log）：22:12:41 与 22:21:57 两次 {@code /time set 0}，
 * offsetMillis 1,034,547 → 461,547；第二次回退 556 秒 &lt; 单程 ≈23 分钟，
 * 班次 4–8 连续五班被吞（特征：待发=1 + 占号车在线路深处），22:21:01 → 22:32:00 全线断发。
 *
 * <p><b>为什么修在这里。</b>MTR3 天生免疫，因为它的锚是「上一次实际发车」这个单调真实时刻
 * （MTR3 Depot.java:388），游戏时钟只用来查频率档位（:383）；MTR4 唯一可回绕的输入就是这个锚。
 * 全 jar 范围 {@code getMillisOfGameMidnight} 只有两个类出现：Simulator（定义）和 Depot（调用），
 * Depot 里也只有 {@code generatePlatformDirectionsAndWriteDeparturesToSidings} 一处 invoke
 * （字节码 offset 236，javap 核对过），所以一个 {@code @Redirect} 就把影响面完全罩住。
 * 拦调用点而不是 Simulator 的定义，是为了拿到 {@code this} = Depot —— 锚点缓存按车厂各存一份，
 * 不同维度多个 Simulator、同维度多个车厂互不串扰。
 *
 * <p><b>逻辑（去跳变）。</b>只看<b>相邻两次原始锚点之差</b>：小的照常跟随 —— TPS 慢于真实
 * 时间会让锚点每 25 秒漂 ~200ms（日志里 departure 尾数 947→119 可证），这是正常的、必须跟；
 * 大的则计入一个累计修正量，返回 {@code raw - 修正量}，发车表相位纹丝不动。
 * 这样跳变之后<b>依然跟得上漂移</b>，而且告警天然只在跳变那一 tick 响一次
 * （若改成缓存「上次返回的锚点」，一旦拒绝过一次就会被冻死：raw 持续漂走、差值越拉越大，
 * 既跟不了漂移，告警也会每隔一二十分钟 × 每个车厂重新触发一次）。
 * 突变时若全厂没有在途车则顺势跟随（无车可撞，回绕只是换号，正好重新对齐）。环形差必须用
 * {@code Utilities.circularDifference}（折到 ±半天）：offsetMillis 在 [0, gameMillisPerDay)
 * 内循环，自然过午夜的回卷直接相减是 ±120 万，会被误判成大跳。
 *
 * <p><b>代价。</b>非均匀频率的车厂在一次被拒绝的跳变后，高峰时段和太阳位置错位
 * （本服频率全天均匀，无影响）。重启后状态清空、以重启后的第一个锚为准 ——
 * 停机期间世界时间不走而挂钟照走，这个跳变是「播种」而不是「拒绝」，
 * 存档里带 departureIndex 的在途车仍可能撞去重（vehicle.json 序列化了它）；
 * 好在停机 &gt; 1 小时时上游自己会清车（Simulator.tick:111-112），窗口很小。
 */
@Mixin(value = Depot.class, remap = false)
public abstract class DepotScheduleAnchorMixin {

	/**
	 * 上一次看到的**原始**锚点值，以及累计「拒绝跟随」的量。
	 *
	 * <p>这里刻意不缓存「上次返回的锚点」，而是缓存原始值 + 修正量。前者的写法有个陷阱：
	 * 一旦拒绝过一次跳变，缓存就被冻死，raw 继续以 ~200ms/25s 漂走，两者的差只会越拉越大 ——
	 * 于是既再也跟随不了正常漂移，告警节流也会因为 delta 持续增长而每隔一二十分钟重新触发一次
	 * （× 每个车厂）。用「原始值 + 修正量」则是标准的去跳变写法：
	 * 每次只看**相邻两次原始值之差**，小的照常跟随、大的计入修正量，
	 * 返回 {@code raw - 修正量}。跳变之后漂移照样跟得上，而告警天然只在跳变那一刻响一次。
	 */
	@Unique
	private long yuzimtr$lastRawAnchor;
	@Unique
	private long yuzimtr$anchorCorrection;
	/** 0 是合法锚点值，不能拿它当哨兵，得单独一个标志位。 */
	@Unique
	private boolean yuzimtr$hasLastRawAnchor;

	@Redirect(
			method = "generatePlatformDirectionsAndWriteDeparturesToSidings",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/simulation/Simulator;getMillisOfGameMidnight()J")
	)
	private long yuzimtr$stabiliseScheduleAnchor(Simulator simulator) {
		final long raw = simulator.getMillisOfGameMidnight();
		if (!Config.get().stabiliseScheduleAnchor) {
			return raw;
		}

		final long gameMillisPerDay = simulator.getGameMillisPerDay();
		if (!simulator.isTimeMoving() || gameMillisPerDay <= 0) {
			// 这两种情况下原函数恒返回 0（Simulator.java:188 的三元条件），本身就是稳定值。
			// 同时丢掉状态：开关日夜循环是管理员的显式操作，重新开启时该以新时钟为准重新播种，
			// 而不是拿停表前的旧锚去「拒绝」它。
			yuzimtr$hasLastRawAnchor = false;
			yuzimtr$anchorCorrection = 0;
			return raw;
		}

		if (!yuzimtr$hasLastRawAnchor) {
			yuzimtr$lastRawAnchor = raw;
			yuzimtr$hasLastRawAnchor = true;
			return raw;
		}

		// 只看相邻两次原始值之差。环形差必须绕近路：offsetMillis 在 [0, gameMillisPerDay) 内循环，
		// 自然过午夜的回卷直接相减是 ±120 万，会被误判成大跳。
		final long step = Utilities.circularDifference(raw, yuzimtr$lastRawAnchor, gameMillisPerDay);
		yuzimtr$lastRawAnchor = raw;

		if (Math.abs(step) > Config.get().scheduleAnchorJumpThresholdMillis) {
			final Depot depot = (Depot) (Object) this;
			if (yuzimtr$hasVehicleOnRoute(depot)) {
				// 有在途车：这一跳计入修正量，发车表相位保持不变。
				// step 只在跳变那一 tick 才是大值，所以这条 warn 每次跳变恰好响一次，不用额外节流。
				yuzimtr$anchorCorrection += step;
				YuziMtrExt.LOGGER.warn("锚点防跳: [{}] 检测到游戏时钟跳变 {} ms，已拒绝跟随，发车表保持原相位（累计修正 {} ms）",
						depot.getName(), step, yuzimtr$anchorCorrection);
			} else {
				// 空闲自动跟随：没有在途车就没有可被撞的班次号，回绕最多是换号，顺势重新对齐。
				// 这也让「夜间停运时段跳时」自然恢复到与游戏时钟一致的相位。
				YuziMtrExt.LOGGER.info("锚点防跳: [{}] 无在途车，跟随时钟跳变 {} ms 重新对齐", depot.getName(), step);
			}
		}

		// 正常漂移不进上面的分支，修正量不变，返回值跟着 raw 走 —— 跳变之后依然跟得上漂移。
		return Math.floorMod(raw - yuzimtr$anchorCorrection, gameMillisPerDay);
	}

	/**
	 * 本车厂有没有任何一辆在途车。走 {@link SidingPaths}（SidingMinimalRefreshMixin 实现在
	 * Siding 上）拿完整的 vehicles 集合 —— {@code iterateVehiclesAndRidingEntities}
	 * 只给得到载人的车，不够用。{@code Depot.savedRails} 是 public（AreaBase.java:13），
	 * {@code Vehicle.getIsOnRoute()} 是 public，不需要新 accessor。
	 */
	@Unique
	private static boolean yuzimtr$hasVehicleOnRoute(Depot depot) {
		for (final Siding siding : depot.savedRails) {
			for (final Vehicle vehicle : ((SidingPaths) (Object) siding).yuzimtr$vehicles()) {
				if (vehicle.getIsOnRoute()) {
					return true;
				}
			}
		}
		return false;
	}
}
