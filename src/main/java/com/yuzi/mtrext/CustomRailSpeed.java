package com.yuzi.mtrext;

/**
 * 鸭子类型接口，由 {@link RailConnectors.CustomSpeedRailModifier} 实现。
 *
 * <p>MTR 的铁轨速度不是一个数，而是烤在 {@code RailType} 这个 <b>enum</b> 里的
 * （{@code RailType.WOODEN} = 20、{@code DIAMOND} = 300……）。往 enum 里加常量要动
 * {@code $VALUES}，又脏又容易崩。
 *
 * <p>但真正落地的那一行只吃 int（{@code ItemRailModifier.createRail} 第 125 行）：
 * <pre>
 * rail = Rail.newRail(…, newRailType.railShape, 0, new ObjectArrayList&lt;&gt;(),
 *         isOneWay ? 0 : newRailType.speedLimit, newRailType.speedLimit, …);
 * </pre>
 * 所以只要在读 {@code speedLimit} 那个字段时换个数就够了 ——
 * 见 {@link com.yuzi.mtrext.mixin.ItemRailModifierMixin}。
 */
public interface CustomRailSpeed {

	int yuzimtr$speedLimit();
}
