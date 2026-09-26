package com.yuzi.mtrext;

/**
 * 按车辆覆盖 MTR4 生成的贯通道尺寸。字段为 null = 该项不覆盖，用 MTR4 原值。
 *
 * <p>几何含义取自 MTR 4.0.5 {@code RenderVehicles.renderConnection}：
 * <pre>
 * halfLength = vehicleLength / 2;
 * 端面四角 = (±width/2, yOffset[+height] + SMALL_OFFSET, zOffset - halfLength)
 * </pre>
 * 所以 {@code width} 是全宽（不是半宽），{@code zOffset - halfLength} 是端面
 * 在车辆局部坐标里的 z。想让端面正好落在车体末端，就令
 * {@code zOffset = 车体半长 - 声明length/2}。
 *
 * <p>想彻底不画贯通道，用 {@code hideGangway}，<b>不要</b>把 width 或 height 设成 0：
 * 那四个面是「本车端面 ↔ 前车端面」之间的四边形，任何一维归零都只会让它退化成
 * 另一个方向上有面积的面（width=0 → 中轴线上一整面竖墙，实测过）。
 * hideGangway 走的是把六张贴图置 null，MainRenderer.java:170 的
 * {@code if (identifier != null)} 会整段跳过，什么都不排队。
 *
 * <p>可行走地板不受影响：GangwayMovementPositions 是从模型的 floor 盒子算的
 * （只取 z 最极端那个再按 HALF_PLAYER_WIDTH 外扩），只由 hasGangway1/2 这个布尔开关，
 * 与这里的尺寸和贴图都无关。
 *
 * <p>为什么需要这个：LegacyVehicleResource.java:238-241 把这四个值硬编码成
 * 1.5 / 2.25 / 1 / 0.5，完全不看车辆尺寸；而转换时它能读到的
 * model_properties 里的 width 本身就可能和实际几何不符
 * （重庆5 声明 2，实测车体 2.900），所以没法靠公式推，只能按车配。
 */
public final class GangwayOverride {

	/** true = 六张�edge贴图置 null，贯通道完全不可见，但人照样能走过去。 */
	public Boolean hideGangway;

	public Double width;
	public Double height;
	public Double yOffset;
	public Double zOffset;

	@Override
	public String toString() {
		return String.format("hideGangway=%s width=%s height=%s yOffset=%s zOffset=%s", hideGangway, width, height, yOffset, zOffset);
	}
}
