package com.yuzi.mtrext.psd;

import org.mtr.mapping.holder.BlockPos;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 客户端视野内绳网方块的位置表。
 *
 * <p>为什么需要它：绳网原本由方块实体渲染器画，那条路径进不了光影的阴影 pass，所以 OBJ
 * 没有影子。MTR 自己的轨道和车辆之所以有正确影子，是因为它们全部从
 * {@code MainRenderer.render} 画 —— 而 {@code MainRenderer extends EntityRenderer}，
 * 挂在一个虚拟实体上（{@code InitClient.java:192}），**实体是会进阴影 pass 的**。
 *
 * <p>所以现在改成从 {@link com.yuzi.mtrext.mixin.MainRendererMixin} 里画。但那个注入点
 * 拿不到「哪些方块是绳网」，于是用这张表：方块实体渲染器每帧把自己登记一次，
 * mixin 遍历这张表来画。
 *
 * <p>过期时间只是个粗筛，<b>正确性不靠它</b> —— 真正兜底的是
 * {@link com.yuzi.mtrext.client.RopeBarrierDrawing#draw} 每次都会重新取方块状态和方块实体，
 * 方块被拆或区块卸载后拿不到就什么都不画。过期只是让表不会无限增长。
 *
 * <p>不同步、不存盘 —— 纯客户端的渲染辅助。
 */
public final class RopeBarrierPositions {

	private RopeBarrierPositions() {
	}

	/** 超过这么久没被登记就认为已经不在视野里了。主/阴影 pass 交替，两秒非常宽裕。 */
	private static final long EXPIRY_MILLIS = 2000;

	/** 键是打包过的坐标 —— mapping 层的 BlockPos 不保证 equals/hashCode，不能直接当键。 */
	private static final Map<Long, Entry> ENTRIES = new ConcurrentHashMap<>();

	private static final class Entry {
		private final BlockPos blockPos;
		private volatile long lastSeenMillis;

		private Entry(BlockPos blockPos, long lastSeenMillis) {
			this.blockPos = blockPos;
			this.lastSeenMillis = lastSeenMillis;
		}
	}

	private static long key(BlockPos blockPos) {
		return ((long) (blockPos.getX() & 0x3FFFFFF) << 38)
				| ((long) (blockPos.getZ() & 0x3FFFFFF) << 12)
				| (blockPos.getY() & 0xFFF);
	}

	/** 由方块实体渲染器每帧调用。 */
	public static void mark(BlockPos blockPos) {
		final long now = System.currentTimeMillis();
		ENTRIES.compute(key(blockPos), (k, existing) -> {
			if (existing == null) {
				return new Entry(blockPos, now);
			}
			existing.lastSeenMillis = now;
			return existing;
		});
	}

	/** 遍历还活着的位置，顺手淘汰过期的。 */
	public static void forEach(Consumer<BlockPos> consumer) {
		final long cutoff = System.currentTimeMillis() - EXPIRY_MILLIS;
		final Iterator<Map.Entry<Long, Entry>> iterator = ENTRIES.entrySet().iterator();
		while (iterator.hasNext()) {
			final Entry entry = iterator.next().getValue();
			if (entry.lastSeenMillis < cutoff) {
				iterator.remove();
			} else {
				consumer.accept(entry.blockPos);
			}
		}
	}
}
