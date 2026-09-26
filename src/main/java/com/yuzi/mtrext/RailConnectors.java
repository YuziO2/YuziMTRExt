package com.yuzi.mtrext;

import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Item;
import org.mtr.mapping.holder.ItemSettings;
import org.mtr.mapping.registry.Registry;
import org.mtr.mod.CreativeModeTabs;
import org.mtr.mod.data.RailType;
import org.mtr.mod.item.ItemRailModifier;

/**
 * 补上 MTR 没有的高速轨道：180 / 250 / 280 / 350 / 380 / 400 / 450 / 500 km/h，
 * 每档都有普通和单向两种，注册进 MTR 自己的「核心」创造标签页。
 *
 * <p>配色刻意和 MTR 现有的十档拉开（它们占了橡木棕、石灰、绿宝石绿、铁灰、砖红棕、紫、
 * 青、橙、灰白、钻石青），这里用的是紫罗兰 / 蓝 / 金 / 天蓝 / 青绿 / 黄绿 / 米白 / 近黑。
 * 这个颜色同时用于两处：物品贴图的染色，和轨道在世界里/地图上的颜色
 * （见 {@link com.yuzi.mtrext.mixin.RailTypeMixin}），所以两边一定一致。
 *
 * <p>速度不走 enum，见 {@link CustomRailSpeed} 的说明。
 */
public final class RailConnectors {

	private RailConnectors() {
	}

	/** {速度, 轨道颜色 ARGB}。颜色和 assets 里贴图的亮色是同一个值。 */
	private static final int[][] RAILS = {
			{180, 0xFFB45CE0}, // 紫水晶
			{250, 0xFF3A5CE0}, // 青金石
			{280, 0xFFF0C341}, // 黄金
			{350, 0xFF6FD3F0}, // 海晶灯
			{380, 0xFF2FBF8F}, // 末影
			{400, 0xFF8FD93A}, // 发光地衣
			{450, 0xFFF5F0B0}, // 下界之星
			{500, 0xFF4A4048}, // 下界合金
	};

	public static void register(Registry registry) {
		for (final int[] rail : RAILS) {
			final int speedLimit = rail[0];
			for (int i = 0; i < 2; i++) {
				final boolean oneWay = i == 1;
				final String name = "rail_connector_" + speedLimit + (oneWay ? "_one_way" : "");
				registry.registerItem(
						new Identifier(YuziMtrExt.MOD_ID, name),
						itemSettings -> new Item(new CustomSpeedRailModifier(oneWay, speedLimit, itemSettings)),
						CreativeModeTabs.CORE
				);
			}
		}
		YuziMtrExt.LOGGER.info("已注册 {} 档高速轨道（各含单向）", RAILS.length);
	}

	/** @return 该速度对应的轨道颜色；不是我们加的档位就返回 0。 */
	public static int colorForSpeed(long speedLimit) {
		for (final int[] rail : RAILS) {
			if (rail[0] == speedLimit) {
				return rail[1];
			}
		}
		return 0;
	}

	/**
	 * 借用 {@code RailType.QUARTZ}（200，能加速、有信号、二次曲线）当模板，
	 * 真正的速度靠 {@link com.yuzi.mtrext.mixin.ItemRailModifierMixin} 在读
	 * {@code speedLimit} 时换掉。{@code forContinuousMovementNode} 传 false，
	 * 免得被拿去连缆车 —— 那条路上 MTR 会把 railType 换成 CABLE_CAR，速度就不该由我们说了。
	 */
	public static final class CustomSpeedRailModifier extends ItemRailModifier implements CustomRailSpeed {

		private final int speedLimit;

		private CustomSpeedRailModifier(boolean oneWay, int speedLimit, ItemSettings itemSettings) {
			super(true, false, false, oneWay, RailType.QUARTZ, itemSettings);
			this.speedLimit = speedLimit;
		}

		@Override
		public int yuzimtr$speedLimit() {
			return speedLimit;
		}
	}
}
