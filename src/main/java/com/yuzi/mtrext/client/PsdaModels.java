package com.yuzi.mtrext.client;

import com.yuzi.mtrext.Config;
import org.mtr.mapping.holder.Direction;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.render.StoredMatrixTransformations;

/**
 * 绳索屏蔽门的模型 id 与动画曲线。
 *
 * <p>模型由 {@code tools/gen_psda_pack.py} 从 RailwayAesthetics-Future（AGPL-3.0，
 * 作者 aphrodite281）派生，打进本模组的 {@code assets/mtrsteamloco/eyecandies/yuzimtr_psda/}。
 * mod 的 assets 本身就是一个资源包，MTR4 的 {@code CustomResourcesConverter.convertObjects}
 * 走 {@code readDirectory("eyecandies", …)} + {@code findAllResources}，会扫到它们，
 * 所以不需要用户另外装资源包。
 *
 * <p>动画参数逐条抄自 psda 的 {@code common.js}（第 3-5、17-24 行）。唯一的偏离是升起速度：
 * {@code RopeBarrierBlockEntity} 会先把门开度乘 {@code ropeBarrierSpeedMultiplier}
 * （默认 2.0）再送进这里的缓动，所以绳子比车门早到位。参数本身如下：
 * <ul>
 * <li>缓动 {@code v = (sin(-π/2 + min(1, doorValue·1.2)·π) + 1) / 2} —— 正弦缓入缓出，
 *     且 {@code ·1.2} 让绳子比车门略早到位</li>
 * <li>基准高度 {@code v + 5 × 0.28}，即落下时 1.4 格、升起时 2.4 格</li>
 * <li>绳间距 {@code 0.08 + (1-v) × 0.20} —— 落下 0.28、升起收拢到 0.08</li>
 * <li>第 0 和第 4 根红色，中间三根黄色</li>
 * </ul>
 */
public final class PsdaModels {

	private PsdaModels() {
	}

	public static final String POST = "yuzimtr_psda_post";
	public static final String POST_BLANK = "yuzimtr_psda_post_blank";
	public static final String HEAD = "yuzimtr_psda_head";
	public static final String ROPE_RED = "yuzimtr_psda_rope_red";
	public static final String ROPE_YELLOW = "yuzimtr_psda_rope_yellow";

	/** common.js:3-5 */
	private static final double INTERVAL = 0.28;
	private static final double MIN_INTERVAL = 0.08;
	public static final int LINE_COUNT = 5;

	/** common.js:17 的缓动。入参是 MTR 的门开度，出参 0..1。 */
	public static double ease(double doorValue) {
		return (Math.sin(-Math.PI / 2 + Math.min(1, doorValue * 1.2) * Math.PI) + 1) / 2;
	}

	/** 第 i 根绳相对方块底面的高度（格）。common.js:19-23。 */
	public static double ropeHeight(double eased, int index) {
		final double spacing = MIN_INTERVAL + (1 - eased) * (INTERVAL - MIN_INTERVAL);
		return eased + LINE_COUNT * INTERVAL - index * spacing;
	}

	/** common.js:23：两端红、中间黄。 */
	public static String ropeModel(int index) {
		return index == 0 || index == LINE_COUNT - 1 ? ROPE_RED : ROPE_YELLOW;
	}

	/**
	 * 按 MTR 画装饰物件的矩阵约定建一个变换（{@code RenderEyeCandy.java:33,53-58}）：
	 * 平移到方块中心 → {@code rotateYDegrees(180 - facing)} → {@code rotateXRadians(π)}。
	 * <b>那个 π 的 X 翻转把局部 +Y 变成世界 -Y</b>，所以升高要往局部 -Y 平移。
	 */
	public static StoredMatrixTransformations at(int x, int y, int z, Direction facing) {
		final StoredMatrixTransformations storedMatrixTransformations =
				new StoredMatrixTransformations(0.5 + x, y, 0.5 + z);
		storedMatrixTransformations.add(graphicsHolder -> {
			graphicsHolder.rotateYDegrees(180 - facing.asRotation());
			graphicsHolder.rotateXRadians((float) Math.PI);
		});
		return storedMatrixTransformations;
	}

	/**
	 * 局部 +X 就是这排绳网的走向（{@code facing.rotateYClockwise()}）。
	 *
	 * <p>可以推出来而不用试：{@link #at} 里是 {@code rotateYDegrees(180 - facing.asRotation())}。
	 * facing=NORTH 时 asRotation()=180，旋转量为 0，局部 +X = 世界 +X = {@code NORTH.rotateYClockwise()}；
	 * facing=EAST 时 asRotation()=270，旋转 -90°，把局部 +X 映到世界 +Z = {@code EAST.rotateYClockwise()}。
	 */
	public static void halfRopeTowards(StoredMatrixTransformations transformations, boolean towardsRowDirection) {
		final double offset = towardsRowDirection ? 0.25 : -0.25;
		transformations.add(graphicsHolder -> {
			graphicsHolder.translate(offset, 0, 0);
			graphicsHolder.scale(0.5F, 1, 1);
		});
	}

	/** 画一个派生模型；模型没加载出来就返回 false，让调用方退回程序化几何体。 */
	public static boolean render(String modelId, StoredMatrixTransformations transformations, int light) {
		if (!Config.get().ropeBarrierUseModels) {
			return false;
		}
		final boolean[] drawn = {false};
		CustomResourceLoader.getObjectById(modelId, objectResource -> {
			objectResource.render(transformations, light);
			drawn[0] = true;
		});
		return drawn[0];
	}
}
