package com.yuzi.mtrext.station;

/**
 * 站台广播器的一条规则：<b>在哪个站台、哪条交路、放哪段音频、什么时候放</b>。
 *
 * <p>可变 POJO，因为 GUI 要就地改。<b>不许出现客户端专有类型</b> ——
 * 服务端的 {@link StationAnnouncerBlockEntity} 和客户端的编辑界面共用这一个类。
 *
 * <h2>两个基准</h2>
 *
 * <p>都取自 MTR 的 {@code ArrivalResponse}，和站台上那些显示设备<b>同源</b>，
 * 不是我们自己重算的：
 * <ul>
 * <li>{@code getArrival()} —— PID 上那个「X 分/X 秒」的口径，见
 *     {@code RenderPIDS.java:177}；</li>
 * <li>{@code getDeparture()} —— JCM 发车倒数器的口径，反编译
 *     {@code DepartureTimerRenderer.renderCurated} 确认它读的就是这个字段、
 *     用的就是同一个 {@code getMillisOffset()}。</li>
 * </ul>
 * TSC 那边这两个值来自 {@code Siding.getArrivals}：
 * {@code arrival = stopTime.startTime + deviation}、
 * {@code departure = stopTime.endTime + deviation}，
 * 所以 departure 就是「这趟车离开本站台的时刻」，含停站时间。
 *
 * <p>两者都是<b>绝对时间戳</b>（模拟器时钟），这一点很关键：到站表一秒才刷新一次，
 * 但触发时刻是算出来的绝对值，所以精度是 tick 级的，刷新率只影响预测的新鲜度。
 */
public final class AnnouncerRule {

	/** 站台 id。0 表示这条规则没配完，直接跳过。 */
	public long platformId;

	/**
	 * 交路 id。<b>0 表示不限交路</b>，本站台任何一趟车都触发；
	 * {@link #ROUTE_UNSET} 表示<b>还没选</b>，规则不生效。
	 */
	public long routeId;

	/**
	 * 「交路还没选」。粘贴规则时目标交路在本站找不到，就落到这个状态。
	 *
	 * <p><b>不能拿 0 代替</b>：0 的意思是「全部交路」—— 那会把一条原本只对某条交路响的规则
	 * 悄悄变成对所有车都响，恰好是「置空」的反面。
	 *
	 * <p>MTR 的 id 是随机 64 位，和 0 一样，理论上存在撞上真实 id 的可能（2⁻⁶⁴），不予考虑。
	 */
	public static final long ROUTE_UNSET = -1;

	/** 资源包里的音效 id，例如 {@code mtr_custom:s2/next_xujiahui_cmn}。空串表示没配完。 */
	public String soundId = "";

	/** false = 以到站时刻为基准；true = 以发车时刻为基准。 */
	public boolean onDeparture;

	/** 基准<b>前</b>多少秒。负数表示基准<b>之后</b>那么多秒。 */
	public int offsetSeconds;

	/** false = 在那个时刻<b>开始播</b>；true = 在那个时刻<b>播完</b>（起点往前倒推一个时长）。 */
	public boolean alignEnd;

	/**
	 * 音频时长（毫秒），只在 {@link #alignEnd} 时参与计算。
	 *
	 * <p>由客户端打开界面时解析 ogg 得到（见 {@code OggDuration}）并随配置存下来 ——
	 * <b>服务端没有资源包，自己量不了</b>。上游遇到同样的问题时的答案是让用户手填
	 * （{@code sound/BveConfigFile.java:49} 的 {@code doorCloseSoundLength}），
	 * 我们只是把手填那步自动化了，界面里仍可改。
	 */
	public int durationMillis;

	public AnnouncerRule() {
	}

	public AnnouncerRule(AnnouncerRule other) {
		platformId = other.platformId;
		routeId = other.routeId;
		soundId = other.soundId;
		onDeparture = other.onDeparture;
		offsetSeconds = other.offsetSeconds;
		alignEnd = other.alignEnd;
		durationMillis = other.durationMillis;
	}

	/** 这条规则配全了没有。没配全的规则会被服务端跳过，但仍然存盘，免得编到一半退出就丢。 */
	public boolean isComplete() {
		return platformId != 0 && routeId != ROUTE_UNSET && !soundId.isEmpty();
	}

	/** 指定了某一条交路（既不是「全部交路」也不是「未选择」）。 */
	public boolean hasSpecificRoute() {
		return routeId != 0 && routeId != ROUTE_UNSET;
	}

	/**
	 * @param baseMillis 基准时刻，<b>已经换算到本地时钟</b>（减过 {@code getMillisOffset()}）
	 * @return 应当开始播放的本地时刻
	 */
	public long fireAtMillis(long baseMillis) {
		final long alignedAt = baseMillis - offsetSeconds * 1000L;
		return alignEnd ? alignedAt - durationMillis : alignedAt;
	}

	/** 位标志，给 NBT / 数据包用一个 int 装下三个 boolean。 */
	public int toFlags() {
		return (onDeparture ? 1 : 0) | (alignEnd ? 2 : 0);
	}

	public void readFlags(int flags) {
		onDeparture = (flags & 1) != 0;
		alignEnd = (flags & 2) != 0;
	}
}
