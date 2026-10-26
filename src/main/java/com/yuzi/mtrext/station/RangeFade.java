package com.yuzi.mtrext.station;

/**
 * 限制范围模式的淡出：玩家在框里音量保持满格，离开框后每 tick 降一档，降到 0 就停；
 * 淡出途中走回框里，按同样的速度升回来。
 *
 * <p>纯计算、不碰客户端类，客户端的声音实例每 tick 调一次，GameTest 也直接测它。
 */
public final class RangeFade {

	private final float step;
	private float level = 1;

	/** @param fadeTicks 从满格降到 0 要几个 tick，最少 1（等于离框就停） */
	public RangeFade(int fadeTicks) {
		this(fadeTicks, 1);
	}

	/**
	 * @param initialLevel 起始音量比例。中途进框（续播）从 0 起、按同样速度淡入，免得半句话突然炸出来；
	 *                     触发时就在框里的从 1 起
	 */
	public RangeFade(int fadeTicks, float initialLevel) {
		step = 1F / Math.max(1, fadeTicks);
		level = Math.max(0, Math.min(1, initialLevel));
	}

	/**
	 * 推进一个 tick。
	 *
	 * @param inside 这一 tick 玩家在不在框里
	 * @return {@code true} 表示人在框外且已经淡到 0，声音该停了（从 0 起淡入时，人在框里不算）
	 */
	public boolean tick(boolean inside) {
		level = inside ? Math.min(1, level + step) : Math.max(0, level - step);
		return !inside && level <= 0;
	}

	/** 当前音量比例，0～1。 */
	public float level() {
		return level;
	}
}
