package com.yuzi.mtrext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存重建的采样统计。CachedResource.getData 每 tick 会被调上千次，
 * 逐条打日志会淹掉整个 log，所以按 (lifespan, 原因) 聚合，定期吐一次汇总。
 */
public final class Diag {

	private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
	private static final long FLUSH_INTERVAL_MILLIS = 5000;
	private static long nextFlush;

	/** 有一次真正的 dataSupplier.get() 即将发生。 */
	public static void rebuild(long lifespan, boolean dataWasNull, long expiry, long now, boolean force) {
		// tick() 是先把 data 置 null、再由下次 getData 触发重建，所以光看 data==null
		// 分不出「从没建过」和「建过又被回收」。expiry 能区分：只有 getData 成功进过
		// 一次才会写 expiry，所以 expiry != 0 就说明这个资源曾经建成过。
		final String reason;
		if (!dataWasNull) {
			reason = "过期(" + (now - expiry) + "ms)";
		} else if (expiry == 0) {
			reason = "首次构建";
		} else {
			reason = "被回收(超期" + (now - expiry) + "ms)";
		}
		final String key = String.format("lifespan=%-11d 原因=%-18s force=%s", lifespan, reason, force);
		COUNTS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
		flushIfDue(now);
	}

	private static final java.util.Set<String> NTE_PREFIX_REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** NTE 别名前缀兜底命中。每个名字只报一次，用来确认修复真的在起作用。 */
	public static void ntePrefixHit(String name) {
		if (NTE_PREFIX_REPORTED.size() < 200 && NTE_PREFIX_REPORTED.add(name)) {
			YuziMtrExt.LOGGER.info("剥离 NTE 前缀后命中部件: {}", name);
		}
	}

	private static synchronized void flushIfDue(long now) {
		if (now < nextFlush) {
			return;
		}
		nextFlush = now + FLUSH_INTERVAL_MILLIS;
		if (COUNTS.isEmpty()) {
			return;
		}
		YuziMtrExt.LOGGER.info("=== 缓存重建统计（近 {} 秒累计）===", FLUSH_INTERVAL_MILLIS / 1000);
		COUNTS.entrySet().stream()
				.sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
				.limit(12)
				.forEach(e -> YuziMtrExt.LOGGER.info("  {} × {}", e.getKey(), e.getValue().get()));
		COUNTS.clear();
	}

	private Diag() {
	}
}
