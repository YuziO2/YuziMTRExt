package com.yuzi.mtrext.gametest;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.mixin.VehicleStateAccessor;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.SignalModification;
import org.mtr.core.data.Siding;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多色闭塞互锁（{@code signalEntryCheck}）。手搓一个 TSC Simulator，按 {@code Simulator.tick} 的顺序推进：
 * 轮换 vehiclePositions → 所有轨道 tick1/tick2 → 各车 simulate。每 tick 50 ms。
 *
 * <p>「互锁」的判据是模拟时间到了所有车都没开到终点段；「通了」是每列车都到了。
 * 另有一个终点站台场景测 {@code basicSignalMode}（出口检查），用观察点记录「车头驶入 / 车尾驶离」某段的时刻。场景布局与 {@code docs/agent-notes/scheduling-and-speed.md}
 * 「多色闭塞互锁」一节一致。
 */
public class SignalMultiColorGameTest {

	private static final int BLUE = 0x3355FF;
	private static final int GREEN = 0x33CC33;
	private static final int RED = 0xDD3333;
	private static final int SIMULATED_SECONDS = 240;

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void upstreamDeadlocksWhenBlockEntryIgnoresOtherColours(TestContext context) {
		// 记录上游 bug：关掉修复时必须互锁。哪天 MTR 自己修了，这条会失败，届时评估能否删掉本修复。
		final Map<String, Integer> arrivals = withEntryCheck(false, () -> branchMerge().run());
		context.assertTrue(allStuck(arrivals),
				"expected the upstream deadlock at the shared blue+green rail without signalEntryCheck; arrivals=" + arrivals);
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void entryCheckResolvesBranchMergeDeadlock(TestContext context) {
		assertAllArrive(context, "branch merge", withEntryCheck(true, () -> branchMerge().run()));
		assertAllArrive(context, "branch merge (default flags)", withConfig(true, true, () -> branchMerge().run()));
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void entryCheckResolvesReverseOrderDeadlock(TestContext context) {
		final Map<String, Integer> upstream = withEntryCheck(false, () -> reverseOrder().run());
		context.assertTrue(allStuck(upstream), "reverse-order layout should deadlock upstream; arrivals=" + upstream);
		assertAllArrive(context, "reverse order", withEntryCheck(true, () -> reverseOrder().run()));
		assertAllArrive(context, "reverse order (default flags)", withConfig(true, true, () -> reverseOrder().run()));
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void entryCheckResolvesThreeColourChain(TestContext context) {
		// 这个场景专门卡「已进入」的判定：用「首段表里出现过自己」当已进入，照样互锁
		final Map<String, Integer> upstream = withEntryCheck(false, () -> threeColourChain().run());
		context.assertTrue(allStuck(upstream), "three-colour chain should deadlock upstream; arrivals=" + upstream);
		assertAllArrive(context, "three-colour chain", withEntryCheck(true, () -> threeColourChain().run()));
		assertAllArrive(context, "three-colour chain (default flags)", withConfig(true, true, () -> threeColourChain().run()));
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void basicModeLetsTrainIntoTerminalPlatformBlock(TestContext context) {
		// 湾湾岛：L 的绿闭塞 = 进站段 + 终点站台，出口外是蓝色渡线；R 从另一站台经蓝色渡线上同一条蓝色正线。
		// 上游（高级模式那条出口检查）：R 在蓝区间里时 L 连站台都进不去，R 车尾一出蓝区间 L 才进站。
		final Scenario upstream = terminalPlatform();
		final Map<String, Integer> upstreamArrivals = withConfig(false, true, upstream::run);
		final int upstreamEnter = upstream.milestone("L enters GIN");
		final int blueClear = upstream.milestone("R clears BR");
		context.assertTrue(!upstreamArrivals.containsValue(-1), "terminal platform must not deadlock upstream; arrivals=" + upstreamArrivals);
		context.assertTrue(upstreamEnter >= 0 && blueClear >= 0, "milestones must be observed upstream; enter=" + upstreamEnter + " clear=" + blueClear);
		context.assertTrue(upstreamEnter >= blueClear, "upstream should hold L at the green entry until R clears blue; L entered at tick "
				+ upstreamEnter + ", R cleared at tick " + blueClear);

		final Scenario basic = terminalPlatform();
		final Map<String, Integer> basicArrivals = withConfig(true, true, basic::run);
		final int basicEnter = basic.milestone("L enters GIN");
		context.assertTrue(basicEnter >= 0 && basic.milestone("R clears BR") >= 0, "milestones must be observed in basic mode; enter=" + basicEnter);
		context.assertTrue(!basicArrivals.containsValue(-1), "terminal platform must not deadlock in basic mode; arrivals=" + basicArrivals);
		context.assertTrue(basicEnter < basic.milestone("R clears BR"), "basic mode should let L into the platform while R is still on blue; L entered at tick "
				+ basicEnter + ", R cleared at tick " + basic.milestone("R clears BR"));
		context.assertTrue(basicEnter < upstreamEnter, "basic mode should admit L earlier than upstream; basic=" + basicEnter + " upstream=" + upstreamEnter);
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void basicModeKeepsExitCheckForAlphaColours(TestContext context) {
		// 上游新版的「高级模式」：首段颜色带 alpha 位时，基础模式也照样做出口检查
		final Scenario alpha = terminalPlatform(GREEN | 0xFF000000);
		final Map<String, Integer> arrivals = withConfig(true, true, alpha::run);
		final int enter = alpha.milestone("L enters GIN");
		final int clear = alpha.milestone("R clears BR");
		context.assertTrue(!arrivals.containsValue(-1), "alpha terminal must not deadlock; arrivals=" + arrivals);
		context.assertTrue(enter >= 0 && clear >= 0, "milestones must be observed; enter=" + enter + " clear=" + clear);
		context.assertTrue(enter >= clear, "alpha colour must keep the exit check even in basic mode; L entered at " + enter + ", R cleared at " + clear);
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void singleColourLayoutsAreUnchanged(TestContext context) {
		for (final boolean basic : new boolean[]{false, true}) {
			for (final boolean merge : new boolean[]{false, true}) {
				final Map<String, Integer> off = withConfig(basic, false, () -> (merge ? singleColourMerge() : singleColourFollow()).run());
				final Map<String, Integer> on = withConfig(basic, true, () -> (merge ? singleColourMerge() : singleColourFollow()).run());
				context.assertTrue(!off.containsValue(-1), "single-colour layout must not deadlock; basic=" + basic + " arrivals=" + off);
				context.assertTrue(off.equals(on), "signalEntryCheck must not change single-colour layouts; basic=" + basic + " off=" + off + " on=" + on);
			}
		}
		context.complete();
	}

	// ---- 场景 ----

	/** 支线 B1(蓝, 站台停站 60s) 汇入 X(蓝+绿)；主线 G9 → G0 → X → G1 全绿。A 先在支线停站，C 随后沿主线直行。 */
	private static Scenario branchMerge() {
		final Scenario s = new Scenario("branch-merge");
		s.rail("depotC", -520, 0, -500, 0, 0);
		s.rail("leadC", -500, 0, -400, 0, 0);
		s.rail("G9", -400, 0, -200, 0, 0, GREEN);
		s.rail("G0", -200, 0, 0, 0, 0, GREEN);
		s.rail("X", 0, 0, 100, 0, 0, BLUE, GREEN);
		s.rail("G1", 100, 0, 300, 0, 0, GREEN);
		s.rail("exit", 300, 0, 700, 0, 0);
		s.rail("depotA", -320, 60, -300, 60, 0);
		s.rail("leadA", -300, 60, -200, 60, 0);
		s.rail("B1", -200, 60, 0, 0, 60, BLUE);
		s.train("A", 0, "depotA", "leadA", "B1", "X", "G1", "exit");
		s.train("C", 10, "depotC", "leadC", "G9", "G0", "X", "G1", "exit");
		return s;
	}

	/** 顺序反过来：C 先进绿闭塞，A 随后到支线。X 之后接无色段，上游「出闭塞后一个车长」检查拦不住。 */
	private static Scenario reverseOrder() {
		final Scenario s = new Scenario("reverse-order");
		s.rail("depotC", -520, 0, -500, 0, 0);
		s.rail("leadC", -500, 0, -400, 0, 0);
		s.rail("G9", -400, 0, -200, 0, 0, GREEN);
		s.rail("G0", -200, 0, 0, 0, 0, GREEN);
		s.rail("X", 0, 0, 100, 0, 0, BLUE, GREEN);
		s.rail("N1", 100, 0, 300, 0, 0);
		s.rail("exit", 300, 0, 700, 0, 0);
		s.rail("depotA", -320, 60, -300, 60, 0);
		s.rail("leadA", -300, 60, -200, 60, 0);
		s.rail("B1", -200, 60, 0, 0, 60, BLUE);
		s.train("A", 8, "depotA", "leadA", "B1", "X", "N1", "exit");
		s.train("C", 0, "depotC", "leadC", "G9", "G0", "X", "N1", "exit");
		return s;
	}

	/** T 走 F1(蓝) → M(蓝+绿) → F2(绿) → Y(绿+红)；R 在红色站台 R0 停站 30s 后经 Y 离开。 */
	private static Scenario threeColourChain() {
		final Scenario s = new Scenario("three-colour");
		s.rail("depotT", -520, 0, -500, 0, 0);
		s.rail("leadT", -500, 0, -400, 0, 0);
		s.rail("F1", -400, 0, -200, 0, 0, BLUE);
		s.rail("M", -200, 0, 0, 0, 0, BLUE, GREEN);
		s.rail("F2", 0, 0, 100, 0, 0, GREEN);
		s.rail("Y", 100, 0, 200, 0, 0, GREEN, RED);
		s.rail("exitT", 200, 0, 600, 0, 0);
		s.rail("depotR", -220, 60, -200, 60, 0);
		s.rail("leadR", -200, 60, -100, 60, 0);
		s.rail("R0", -100, 60, 100, 0, 30, RED);
		s.rail("exitR", 200, 0, 600, 60, 0);
		s.train("R", 0, "depotR", "leadR", "R0", "Y", "exitR");
		s.train("T", 5, "depotT", "leadT", "F1", "M", "F2", "Y", "exitT");
		return s;
	}

	/**
	 * 湾湾岛的简化版：L 走 进站段 GIN(绿) → 终点站台 P(绿, 停 20s) → 渡线 X(蓝) → 正线 BR(蓝)；
	 * R 走 站台 RP(无色, 停 5s) → 渡线 X2(蓝) → 同一条 BR。X、X2、BR 在节点 N 相连，同属一个蓝分量。
	 */
	private static Scenario terminalPlatform() {
		return terminalPlatform(GREEN);
	}

	/** @param platformColour 进站段和站台的颜色；带 alpha 位就是上游新版的「高级模式」闭塞 */
	private static Scenario terminalPlatform(int platformColour) {
		final Scenario s = new Scenario("terminal-platform-" + Integer.toHexString(platformColour));
		s.rail("depotL", -620, 0, -600, 0, 0);
		s.rail("leadL", -600, 0, -400, 0, 0);
		s.rail("GIN", -400, 0, -300, 0, 0, platformColour);
		s.rail("P", -300, 0, -200, 0, 20, platformColour);
		s.rail("X", -200, 0, -100, -10, 0, BLUE);
		s.rail("BR", -100, -10, 300, -10, 0, BLUE);
		s.rail("exit", 300, -10, 700, -10, 0);
		s.rail("depotR", -420, -20, -400, -20, 0);
		s.rail("RP", -400, -20, -300, -20, 5);
		s.rail("X2", -300, -20, -100, -10, 0, BLUE);
		s.train("R", 0, "depotR", "RP", "X2", "BR", "exit");
		// L 晚 15 秒发车：到进站段时 R 已经在蓝区间里
		s.train("L", 15, "depotL", "leadL", "GIN", "P", "X", "BR", "exit");
		s.watchEnter("L enters GIN", "L", "GIN");
		s.watchClear("R clears BR", "R", "BR");
		return s;
	}

	/** 纯单色：两列同向车先后经过绿闭塞（含站台）和红闭塞（含站台）。 */
	private static Scenario singleColourFollow() {
		final Scenario s = new Scenario("single-follow");
		s.rail("depot", -120, 0, -100, 0, 0);
		s.rail("lead", -100, 0, 0, 0, 0);
		s.rail("A1", 0, 0, 200, 0, 0, GREEN);
		s.rail("A2", 200, 0, 400, 0, 15, GREEN);
		s.rail("B1", 400, 0, 600, 0, 0, RED);
		s.rail("B2", 600, 0, 800, 0, 15, RED);
		s.rail("exit", 800, 0, 1200, 0, 0);
		s.train("T1", 0, "depot", "lead", "A1", "A2", "B1", "B2", "exit");
		s.train("T2", 15, "depot", "lead", "A1", "A2", "B1", "B2", "exit");
		return s;
	}

	/** 纯单色汇合：支线 B1(蓝, 站台) 汇入纯绿的 X。 */
	private static Scenario singleColourMerge() {
		final Scenario s = new Scenario("single-merge");
		s.rail("depotC", -520, 0, -500, 0, 0);
		s.rail("leadC", -500, 0, -400, 0, 0);
		s.rail("G9", -400, 0, -200, 0, 0, GREEN);
		s.rail("G0", -200, 0, 0, 0, 0, GREEN);
		s.rail("X", 0, 0, 100, 0, 0, GREEN);
		s.rail("G1", 100, 0, 300, 0, 0, GREEN);
		s.rail("exit", 300, 0, 700, 0, 0);
		s.rail("depotA", -320, 60, -300, 60, 0);
		s.rail("leadA", -300, 60, -200, 60, 0);
		s.rail("B1", -200, 60, 0, 0, 30, BLUE);
		s.train("A", 0, "depotA", "leadA", "B1", "X", "G1", "exit");
		s.train("C", 10, "depotC", "leadC", "G9", "G0", "X", "G1", "exit");
		return s;
	}

	// ---- 工具 ----

	/** 多色互锁的场景只切 signalEntryCheck；basicSignalMode 保持关，维持这些场景写定时的上游出口检查。 */
	private static Map<String, Integer> withEntryCheck(boolean enabled, java.util.function.Supplier<Map<String, Integer>> run) {
		return withConfig(false, enabled, run);
	}

	private static Map<String, Integer> withConfig(boolean basicSignalMode, boolean signalEntryCheck, java.util.function.Supplier<Map<String, Integer>> run) {
		final Config config = Config.get();
		final boolean oldBasic = config.basicSignalMode;
		final boolean oldEntry = config.signalEntryCheck;
		config.basicSignalMode = basicSignalMode;
		config.signalEntryCheck = signalEntryCheck;
		try {
			return run.get();
		} finally {
			config.basicSignalMode = oldBasic;
			config.signalEntryCheck = oldEntry;
		}
	}

	/** 互锁 = 所有车都没到。只看「有车没到」分不清互锁和某列车压根没发车。 */
	private static boolean allStuck(Map<String, Integer> arrivals) {
		return !arrivals.isEmpty() && arrivals.values().stream().allMatch(tick -> tick == -1);
	}

	private static void assertAllArrive(TestContext context, String name, Map<String, Integer> arrivals) {
		context.assertTrue(!arrivals.containsValue(-1), name + ": every train must reach its exit with signalEntryCheck; arrivals=" + arrivals);
	}

	/** 一个布局 + 若干列车。{@link #run()} 返回每列车开到终点段的时刻（tick），没到是 -1。 */
	private static final class Scenario {

		private final Simulator simulator;
		private final Map<String, Rail> rails = new HashMap<>();
		private final Map<String, Position[]> ends = new HashMap<>();
		private final Map<String, Long> dwellMillis = new HashMap<>();
		private final List<String> trainNames = new ArrayList<>();
		private final List<String[]> trainPaths = new ArrayList<>();
		private final List<Integer> startSeconds = new ArrayList<>();
		/** 名字 → {车名, 轨道名, 0=车头驶入 / 1=车尾驶离} */
		private final Map<String, String[]> watches = new HashMap<>();
		private final Map<String, Integer> milestones = new HashMap<>();

		private Scenario(String name) {
			simulator = new Simulator("gametest-signal-" + name, new String[]{"gametest-signal-" + name}, Path.of("build", "mtr-gametest"), false);
		}

		private void rail(String name, long x1, long z1, long x2, long z2, long dwellSeconds, int... colors) {
			final Position a = new Position(x1, 0, z1);
			final Position b = new Position(x2, 0, z2);
			final ObjectArrayList<String> styles = new ObjectArrayList<>();
			final Rail rail = dwellSeconds > 0
					? Rail.newPlatformRail(a, Angle.E, b, Angle.E, Rail.Shape.QUADRATIC, 0, styles, TransportMode.TRAIN)
					: Rail.newRail(a, Angle.E, b, Angle.E, Rail.Shape.QUADRATIC, 0, styles, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
			final SignalModification modification = new SignalModification(a, b, false);
			for (final int color : colors) {
				modification.putColorToAdd(color);
			}
			rail.applyModification(modification);
			simulator.rails.add(rail);
			rails.put(name, rail);
			ends.put(name, new Position[]{a, b});
			dwellMillis.put(name, dwellSeconds * 1000);
		}

		private void train(String name, int startSecond, String... railNames) {
			trainNames.add(name);
			trainPaths.add(railNames);
			startSeconds.add(startSecond);
		}

		private void watchEnter(String name, String train, String rail) {
			watches.put(name, new String[]{train, rail, "enter"});
		}

		private void watchClear(String name, String train, String rail) {
			watches.put(name, new String[]{train, rail, "clear"});
		}

		/** 某个观察点第一次满足时的 tick，没发生是 -1。 */
		private int milestone(String name) {
			return milestones.getOrDefault(name, -1);
		}

		private Map<String, Integer> run() {
			simulator.sync();
			final List<Vehicle> vehicles = new ArrayList<>();
			final List<Double> exitStarts = new ArrayList<>();
			final List<ObjectArrayList<PathData>> paths = new ArrayList<>();
			for (final String[] railNames : trainPaths) {
				final ObjectArrayList<PathData> path = path(railNames);
				paths.add(path);
				vehicles.add(vehicle(path));
				exitStarts.add(path.get(path.size() - 1).getStartDistance());
			}

			final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> vehiclePositions = new ObjectArrayList<>();
			vehiclePositions.add(new Object2ObjectAVLTreeMap<>());
			vehiclePositions.add(new Object2ObjectAVLTreeMap<>());
			vehicles.forEach(vehicle -> vehicle.simulate(50, vehiclePositions, null));

			final int[] arrivedTick = new int[vehicles.size()];
			java.util.Arrays.fill(arrivedTick, -1);
			for (int tick = 0; tick < SIMULATED_SECONDS * 20; tick++) {
				for (int i = 0; i < vehicles.size(); i++) {
					if (tick == startSeconds.get(i) * 20) {
						vehicles.get(i).startUp(0, 0);
					}
				}
				vehiclePositions.remove(0);
				vehiclePositions.add(new Object2ObjectAVLTreeMap<>());
				simulator.rails.forEach(rail -> rail.tick1(simulator));
				simulator.rails.forEach(rail -> rail.tick2(50));
				for (int i = 0; i < vehicles.size(); i++) {
					final Vehicle vehicle = vehicles.get(i);
					vehicle.simulate(50, vehiclePositions, null);
					final double railProgress = ((VehicleStateAccessor) (Object) vehicle).yuzimtr$railProgress();
					if (arrivedTick[i] < 0 && railProgress > exitStarts.get(i)) {
						arrivedTick[i] = tick;
					}
					recordMilestones(tick, trainNames.get(i), trainPaths.get(i), paths.get(i), railProgress, vehicle.vehicleExtraData.getTotalVehicleLength());
				}
			}

			final Map<String, Integer> result = new HashMap<>();
			for (int i = 0; i < vehicles.size(); i++) {
				result.put(trainNames.get(i), arrivedTick[i]);
			}
			return result;
		}

		private void recordMilestones(int tick, String train, String[] railNames, ObjectArrayList<PathData> path, double railProgress, double trainLength) {
			watches.forEach((name, watch) -> {
				if (milestones.containsKey(name) || !watch[0].equals(train)) {
					return;
				}
				final int index = java.util.Arrays.asList(railNames).indexOf(watch[1]);
				final boolean reached = watch[2].equals("enter")
						? railProgress > path.get(index).getStartDistance()
						: railProgress - trainLength > path.get(index).getEndDistance();
				if (reached) {
					milestones.put(name, tick);
				}
			});
		}

		private ObjectArrayList<PathData> path(String[] railNames) {
			final ObjectArrayList<PathData> path = new ObjectArrayList<>();
			double distance = 0;
			for (final String name : railNames) {
				final Position a = ends.get(name)[0];
				final Position b = ends.get(name)[1];
				final double length = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
				path.add(new PathData(new PathData(rails.get(name), 0, dwellMillis.get(name), 0, a, b), distance, distance + length));
				distance += length;
			}
			// 游戏里由侧线生成路径时写限速；不写的话 PathData 限速是 1 km/h
			PathData.writePathCache(path, simulator, TransportMode.TRAIN);
			return path;
		}

		private Vehicle vehicle(ObjectArrayList<PathData> path) {
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("mtr:train_1", 20, 3, 2, -2, 0, 0));
			final VehicleExtraData extraData = VehicleExtraData.create(1, 2, 20, cars,
					new ObjectArrayList<>(path.subList(0, 1)),
					new ObjectArrayList<>(path.subList(1, path.size() - 1)),
					new ObjectArrayList<>(path.subList(path.size() - 1, path.size())),
					path.get(0), false, Siding.ACCELERATION_DEFAULT, Siding.ACCELERATION_DEFAULT, false, 0.01, 0);
			return new Vehicle(extraData, null, TransportMode.TRAIN, simulator);
		}
	}
}
