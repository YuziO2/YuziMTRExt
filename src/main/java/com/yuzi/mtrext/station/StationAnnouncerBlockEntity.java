package com.yuzi.mtrext.station;

import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.mapper.SoundHelper;
import org.mtr.mod.data.ArrivalsCacheServer;

import java.util.Iterator;
import java.util.List;

/**
 * 站台广播器：按到站/发车时刻，向四周放资源包里的音频。
 *
 * <h2>为什么不用 MTR 自带的广播器</h2>
 *
 * <p>{@code BlockTrainAnnouncer} 是<b>车载</b>广播器，三条硬限制全都挡路
 * （{@code data/VehicleExtension.java:209-222}）：
 * <ul>
 * <li>触发条件带 {@code VehicleRidingMovement.isRiding(id)} —— 只有<b>车上的乘客</b>会触发；</li>
 * <li>放音用 {@code clientPlayerEntity.playSound(…, 1000, 1)}，音量 1000 是贴耳播放，
 *     站台上的人听不到；</li>
 * <li>触发点是车头 3×3×3 扫方块，没有提前量，做不了「到站前 X 秒」。</li>
 * </ul>
 *
 * <h2>为什么整套逻辑放在服务端</h2>
 *
 * <p>这是站台广播，必须<b>所有人同时听到同一声</b>。放客户端就是每个客户端各自预测、
 * 各自去重，人一多就会此起彼伏。服务端拿到到站表毫无障碍：
 * {@code ArrivalsCacheServer} 本来就存在，且 {@code Init.java:234} 在
 * {@code registerStartServerTick} 里<b>无条件</b>每 tick 调 {@code tickAll()} ——
 * 不需要有人打开 PID 才有数据。
 *
 * <h2>放音的那个重载不能选错</h2>
 *
 * <p>{@code World} 上两个 playSound 落到的原版方法是不同的：
 * <pre>
 * playSound(PlayerEntity, BlockPos, …)  → class_1937.method_8396 = Level.playSound()
 *                                          服务端：广播给周围所有人（第一个参数是「除了他」）
 * playSound(double,double,double, …)    → class_1937.method_8486 = Level.playLocalSound()
 *                                          服务端是<b>空实现</b>，只有 ClientLevel 覆写了
 * </pre>
 * 我们在服务端，所以用<b>前者</b>、第一个参数传 {@code null}。
 * （绳索屏蔽门 {@code RopeBarrierBlockEntity#playSoundOnChange} 用的是后者，
 * 因为它是纯客户端渲染态 —— 两边参数语义正好相反，别照抄。）
 *
 * <p>资源包里的音效服务端并不认识，但照样能广播：{@code SoundHelper.createSoundEvent}
 * 编译到 {@code SoundEvent.createVariableRangeEvent}，造的是<b>未注册</b>的 SoundEvent；
 * 1.20.1 的 {@code ClientboundSoundPacket} 用 {@code Holder<SoundEvent>} 编码，
 * 未注册的走 {@code Holder.direct}，把 id 原样发给客户端自己去 sounds.json 里查。
 * JCM 的「声音循环器」就是这么干的，线上一直在跑。
 */
public class StationAnnouncerBlockEntity extends BlockEntityExtension {

	private final ObjectArrayList<AnnouncerRule> rules = new ObjectArrayList<>();
	private float volume = DEFAULT_VOLUME;
	private boolean realtimeOnly;
	private boolean limitRange;
	private BlockPos corner1;
	private BlockPos corner2;

	/**
	 * 已经播过的班次。防止同一趟车反复触发。
	 *
	 * <p>上游 {@code BlockTrainScheduleSensor} 用的是「剩余秒数 == 设定值」的精确相等判断，
	 * 在 20 tick/s 下同一秒内会连判 20 次真 —— 那个别学。
	 *
	 * <p>用线性表而不是哈希表：条数是个位到几十条，线性扫完全够，而且
	 * <b>避免把三元组压成一个 long</b> —— {@code routeId} 是随机 64 位，
	 * {@code (i * 31 + routeId) * 31 + departureIndex} 这种有损哈希会让不同班次撞键、
	 * 表现成随机漏播。
	 */
	private final ObjectArrayList<FiredService> firedServices = new ObjectArrayList<>();

	/** 登记了、还没到点的广播。见 {@link #upsertPending}。 */
	private final ObjectArrayList<PendingAnnouncement> pendingAnnouncements = new ObjectArrayList<>();

	/** 所有规则用到的站台 id。只在规则变动时重建，不必每 tick 攒一遍。 */
	private final LongAVLTreeSet queriedPlatformIds = new LongAVLTreeSet();
	private long lastCleanupMillis;
	private long lastEmptyBoxWarnMillis;

	/** 默认音量 2 → 半径 16×2 = 32 格，够覆盖一个普通站台。 */
	public static final float DEFAULT_VOLUME = 2;

	/**
	 * 「限制范围」那档传给客户端的音量。增益会被钳到 1，这个大数只是把衰减距离拉到
	 * 听不出衰减（16 × 它）。和 MTR {@code BlockTrainAnnouncer} 传的 1000 同一个用意。
	 */
	private static final float NON_POSITIONAL_VOLUME = 1000;

	/**
	 * 触发窗口：错过起点这么久之内还允许补播。
	 *
	 * <p>存在的意义是容忍服务端卡顿和到站表跳变；超过就<b>宁可不播</b> ——
	 * 区块刚加载、或者 ETA 突然往前跳时，补一条早就该放完的广播只会更乱。
	 */
	private static final long FIRE_GRACE_MILLIS = 2000;

	/**
	 * 两条到站记录的<b>基准时刻</b>差在这个数以内，就认为是同一趟车的同一次停靠。
	 *
	 * <h3>为什么身份要落在「时刻」上，而不是 departureIndex</h3>
	 *
	 * <p>{@code departureIndex} <b>不是班次的唯一身份</b>（TSC 字节码核实）：
	 * <ul>
	 * <li>它是<b>本 siding</b> 的 {@code departures} 列表下标，每条 siding 各自从 0 数 ——
	 *     一个车库 4 条 siding 各停一列车，4 列车的 departureIndex 全是 0；</li>
	 * <li>车库勾了「无限重复」时，{@code Siding.lambda$iterateArrivals$21} 里有一层
	 *     {@code repeatInterval × 周期序号} 的内层循环，<b>同一个 StopTime 会按周期发出多条</b>
	 *     到站记录，departureIndex 全都一样，只有时刻不同；</li>
	 * <li>手动车库一律是 -1。</li>
	 * </ul>
	 *
	 * <p>所以不能用「还在到站表里就续期」—— 第一趟播完之后，<b>下一圈</b>的同号班次会把记录
	 * 一直续着，这条规则从此再也不响。也不能用固定 TTL —— 会把下一趟吞掉。
	 * 唯一可靠的区分是到站/发车<b>时刻</b>本身。
	 *
	 * <p>取值要大于预测抖动、小于同站台两趟车的最小间隔。20 秒两头都留得住；
	 * 而且命中之后会把记下来的基准<b>跟到新值</b>，所以再慢的漂移也不会累积到超出容差。
	 */
	private static final long SAME_SERVICE_TOLERANCE_MILLIS = 20_000;

	/** 去重记录在基准时刻之后再留多久。要覆盖「基准之后 X 秒」那档规则的触发点。 */
	private static final long RECORD_LINGER_MILLIS = 300_000;

	/**
	 * 到站记录消失时，base 最多可以比「最后一次看到它」晚这么多，仍算正常消失（车走了）。
	 * 超过就当作废处理。见 {@link #processPending}。
	 *
	 * <p>正常消失时 base ≤ 最后一次看到它（到站表 1 秒一刷，发车后记录最多多留 1 秒左右），
	 * 这里的余量只给时钟换算的抖动。<b>不要放大</b>：余量越大，「临近到站时预测突然跳了超过容差」
	 * 那种情况下旧待播项越容易被误判成正常消失，结果新旧各播一次。
	 */
	private static final long VANISHED_SLACK_MILLIS = 5_000;

	/** 清理去重表的间隔。 */
	private static final long CLEANUP_INTERVAL_MILLIS = 30_000;

	/** 去重表的硬上限，兜底防止异常数据把内存撑爆。正常规模是个位到几十条。 */
	private static final int MAX_FIRED_RECORDS = 512;

	/** 默认范围框的半宽 / 半高（格）。 */
	private static final int DEFAULT_RANGE_HORIZONTAL = 32;
	private static final int DEFAULT_RANGE_VERTICAL = 16;

	/** 「框里一个人都没有」的告警节流间隔。 */
	private static final long EMPTY_BOX_WARN_INTERVAL_MILLIS = 30_000;

	private static final String KEY_PLATFORM_IDS = "rule_platform_ids";
	private static final String KEY_ROUTE_IDS = "rule_route_ids";
	private static final String KEY_SOUND_IDS = "rule_sound_ids";
	private static final String KEY_OFFSETS = "rule_offsets";
	private static final String KEY_DURATIONS = "rule_durations";
	private static final String KEY_FLAGS = "rule_flags";
	private static final String KEY_VOLUME = "volume";
	private static final String KEY_REALTIME_ONLY = "realtime_only";
	private static final String KEY_LIMIT_RANGE = "limit_range";
	private static final String KEY_CORNER_1 = "corner_1";
	private static final String KEY_CORNER_2 = "corner_2";

	public StationAnnouncerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	// ------------------------------------------------------------------ 存盘
	//
	// 规则列表存成**平行数组**，而不是嵌套的标签列表。
	// 不是偷懒：mapping 的 CompoundTag 只暴露了 putInt/putLong/putString/putXxxArray，
	// **没有 putCompound、也没有任何列表标签**（javap 确认），嵌套结构根本写不进去。
	// 音效 id 用 '\n' 连成一个字符串 —— 资源 id 的合法字符集里没有换行。

	@Override
	public void readCompoundTag(CompoundTag compoundTag) {
		rules.clear();
		final long[] platformIds = compoundTag.getLongArray(KEY_PLATFORM_IDS);
		final long[] routeIds = compoundTag.getLongArray(KEY_ROUTE_IDS);
		final int[] offsets = compoundTag.getIntArray(KEY_OFFSETS);
		final int[] durations = compoundTag.getIntArray(KEY_DURATIONS);
		final int[] flags = compoundTag.getIntArray(KEY_FLAGS);
		// -1 保留末尾的空串，否则最后一条没配音效的规则会和它前面那条错位。
		final String[] soundIds = compoundTag.getString(KEY_SOUND_IDS).split("\n", -1);

		for (int i = 0; i < platformIds.length; i++) {
			final AnnouncerRule rule = new AnnouncerRule();
			rule.platformId = platformIds[i];
			rule.routeId = i < routeIds.length ? routeIds[i] : 0;
			rule.soundId = i < soundIds.length ? soundIds[i] : "";
			rule.offsetSeconds = i < offsets.length ? offsets[i] : 0;
			rule.durationMillis = i < durations.length ? durations[i] : 0;
			rule.readFlags(i < flags.length ? flags[i] : 0);
			rules.add(rule);
		}

		volume = compoundTag.contains(KEY_VOLUME) ? compoundTag.getFloat(KEY_VOLUME) : DEFAULT_VOLUME;
		realtimeOnly = compoundTag.getBoolean(KEY_REALTIME_ONLY);
		limitRange = compoundTag.getBoolean(KEY_LIMIT_RANGE);
		corner1 = readPos(compoundTag, KEY_CORNER_1);
		corner2 = readPos(compoundTag, KEY_CORNER_2);
		rebuildQueriedPlatformIds();
	}

	@Override
	public void writeCompoundTag(CompoundTag compoundTag) {
		final int count = rules.size();
		final long[] platformIds = new long[count];
		final long[] routeIds = new long[count];
		final int[] offsets = new int[count];
		final int[] durations = new int[count];
		final int[] flags = new int[count];
		final StringBuilder soundIds = new StringBuilder();

		for (int i = 0; i < count; i++) {
			final AnnouncerRule rule = rules.get(i);
			platformIds[i] = rule.platformId;
			routeIds[i] = rule.routeId;
			offsets[i] = rule.offsetSeconds;
			durations[i] = rule.durationMillis;
			flags[i] = rule.toFlags();
			if (i > 0) {
				soundIds.append('\n');
			}
			soundIds.append(rule.soundId);
		}

		compoundTag.putLongArray(KEY_PLATFORM_IDS, platformIds);
		compoundTag.putLongArray(KEY_ROUTE_IDS, routeIds);
		compoundTag.putIntArray(KEY_OFFSETS, offsets);
		compoundTag.putIntArray(KEY_DURATIONS, durations);
		compoundTag.putIntArray(KEY_FLAGS, flags);
		compoundTag.putString(KEY_SOUND_IDS, soundIds.toString());

		compoundTag.putFloat(KEY_VOLUME, volume);
		compoundTag.putBoolean(KEY_REALTIME_ONLY, realtimeOnly);
		compoundTag.putBoolean(KEY_LIMIT_RANGE, limitRange);
		writePos(compoundTag, KEY_CORNER_1, corner1);
		writePos(compoundTag, KEY_CORNER_2, corner2);
	}

	private static BlockPos readPos(CompoundTag compoundTag, String key) {
		final int[] xyz = compoundTag.getIntArray(key);
		return xyz.length == 3 ? new BlockPos(xyz[0], xyz[1], xyz[2]) : null;
	}

	private static void writePos(CompoundTag compoundTag, String key, BlockPos blockPos) {
		if (blockPos != null) {
			compoundTag.putIntArray(key, new int[]{blockPos.getX(), blockPos.getY(), blockPos.getZ()});
		}
	}

	// ------------------------------------------------------------------ 读写

	public List<AnnouncerRule> getRules() {
		return rules;
	}

	public float getVolume() {
		return volume;
	}

	public boolean getRealtimeOnly() {
		return realtimeOnly;
	}

	public boolean getLimitRange() {
		return limitRange;
	}

	/**
	 * 没设过就给一个以本方块为中心的默认框。
	 *
	 * <p>默认值要<b>宁大勿小</b>：框小了的表现是「完全没有声音」，而且没有任何提示，
	 * 很难联想到是范围问题（第一版给的 ±(8,3,8) 就踩了这个坑 —— 站台上随便走两步就出框）。
	 * ±32 横向能盖住一整条站台，±16 纵向能容忍把喇叭挂在天花板上。
	 */
	public BlockPos getCorner1() {
		return corner1 != null ? corner1 : getPos2().add(-DEFAULT_RANGE_HORIZONTAL, -DEFAULT_RANGE_VERTICAL, -DEFAULT_RANGE_HORIZONTAL);
	}

	public BlockPos getCorner2() {
		return corner2 != null ? corner2 : getPos2().add(DEFAULT_RANGE_HORIZONTAL, DEFAULT_RANGE_VERTICAL, DEFAULT_RANGE_HORIZONTAL);
	}

	public void setData(List<AnnouncerRule> newRules, float volume, boolean realtimeOnly, boolean limitRange, BlockPos corner1, BlockPos corner2) {
		rules.clear();
		newRules.forEach(rule -> rules.add(new AnnouncerRule(rule)));
		this.volume = volume;
		this.realtimeOnly = realtimeOnly;
		this.limitRange = limitRange;
		this.corner1 = corner1;
		this.corner2 = corner2;
		// 改了配置就把去重表和待播表清空：规则序号变了，旧记录对应的已经不是同一条规则了。
		firedServices.clear();
		pendingAnnouncements.clear();
		rebuildQueriedPlatformIds();
		markDirty2();
	}

	private void rebuildQueriedPlatformIds() {
		queriedPlatformIds.clear();
		for (final AnnouncerRule rule : rules) {
			if (rule.isComplete()) {
				queriedPlatformIds.add(rule.platformId);
			}
		}
	}

	// ------------------------------------------------------------------ 触发

	@Override
	public void blockEntityTick() {
		final World world = getWorld2();
		if (world == null || world.isClient() || rules.isEmpty()) {
			return;
		}

		final long now = System.currentTimeMillis();
		if (now - lastCleanupMillis > CLEANUP_INTERVAL_MILLIS) {
			lastCleanupMillis = now;
			firedServices.removeIf(firedService -> firedService.expiresAt <= now);
		}

		// 待播表只会由配全了的规则产生，而 setData 会清空它 —— 没有要查的站台就一定没有待播项。
		if (queriedPlatformIds.isEmpty()) {
			return;
		}

		// 一次把所有规则用到的站台问齐。requestArrivals 既是**读缓存的入口**，
		// 也顺带把站台续在请求队列里 —— 后者其实每 5 秒调一次就够
		// （PERSISTENT_AGE = 5 数的是「请求次数」，而 tick() 每 1000 ms 才发一次），
		// 每 tick 调是为了前者：触发判定需要每 tick 拿到最新的到站表。
		//
		// 注意这个服务端缓存和 MTR 自己的 PacketFetchArrivals（客户端 PIDS 的数据源）
		// 是**同一个实例**，队列里混着所有客户端正在看的站台。
		final ArrivalsCacheServer cache = ArrivalsCacheServer.getInstance(ServerWorld.cast(world));
		final ObjectArrayList<ArrivalResponse> arrivals = cache.requestArrivals(queriedPlatformIds);
		final long millisOffset = cache.getMillisOffset();

		// 第一步：把到站表里每一条「这条规则还没为它播过」的班次登记进待播表，并跟上最新预测。
		// 到站表空了也要往下走 —— 最后一班车发车之后表就空了，「发车后 X 秒」恰恰要在那时候播。
		for (int i = 0; i < rules.size(); i++) {
			final AnnouncerRule rule = rules.get(i);
			if (!rule.isComplete()) {
				continue;
			}
			for (final ArrivalResponse arrival : arrivals) {
				if (arrival.getPlatformId() != rule.platformId) {
					continue;
				}
				if (rule.routeId != 0 && arrival.getRouteId() != rule.routeId) {
					continue;
				}
				if (realtimeOnly && !arrival.getRealtime()) {
					continue;
				}

				final long base = (rule.onDeparture ? arrival.getDeparture() : arrival.getArrival()) - millisOffset;
				if (alreadyFired(i, arrival.getRouteId(), arrival.getDepartureIndex(), base)) {
					continue;
				}
				final long fireAt = rule.fireAtMillis(base);
				if (now > fireAt + FIRE_GRACE_MILLIS) {
					continue; // 已经错过了，宁可不播
				}
				upsertPending(i, arrival.getRouteId(), arrival.getDepartureIndex(), base, fireAt, now);
			}
		}

		// 第二步：到点的待播项放出去。
		processPending(world, now);
	}

	/**
	 * 登记（或更新）一条待播项。
	 *
	 * <h3>为什么需要待播表，而不是「到站表里有、且正好到点」就播</h3>
	 *
	 * <p>到站表里的记录<b>车一发车就没了</b>：{@code Siding.lambda$iterateArrivals$21}
	 * 里跳过条件是 {@code departure + deviation < currentMillis - minMillis}，
	 * 而 {@code getArrivals} 传的 {@code minMillis} 是 0（字节码 {@code lconst_0}）。
	 * 所以只看「表里有且到点」的话：
	 * <ul>
	 * <li>「发车后 X 秒」<b>永远不响</b> —— 到点时记录早没了；</li>
	 * <li>「到站后 X 秒」只在 X 小于停站时间时响。</li>
	 * </ul>
	 * 第一版就是这么写的。现在改成：班次还在表里时把触发时刻登记下来、每 tick 跟着预测更新；
	 * 车走了、记录消失了，待播项照样到点就播。
	 */
	private void upsertPending(int ruleIndex, long routeId, long departureIndex, long base, long fireAt, long now) {
		for (final PendingAnnouncement pendingAnnouncement : pendingAnnouncements) {
			if (pendingAnnouncement.ruleIndex == ruleIndex
					&& pendingAnnouncement.routeId == routeId
					&& pendingAnnouncement.departureIndex == departureIndex
					&& Math.abs(pendingAnnouncement.base - base) < SAME_SERVICE_TOLERANCE_MILLIS) {
				// 跟到新值，道理同 alreadyFired：慢漂移永远不会累积到超出容差。
				pendingAnnouncement.base = base;
				pendingAnnouncement.fireAt = fireAt;
				pendingAnnouncement.lastSeenMillis = now;
				return;
			}
		}
		if (pendingAnnouncements.size() >= MAX_FIRED_RECORDS) {
			pendingAnnouncements.remove(0);
		}
		final PendingAnnouncement pendingAnnouncement = new PendingAnnouncement();
		pendingAnnouncement.ruleIndex = ruleIndex;
		pendingAnnouncement.routeId = routeId;
		pendingAnnouncement.departureIndex = departureIndex;
		pendingAnnouncement.base = base;
		pendingAnnouncement.fireAt = fireAt;
		pendingAnnouncement.lastSeenMillis = now;
		pendingAnnouncements.add(pendingAnnouncement);
	}

	/**
	 * 放出到点的待播项，丢掉作废的。
	 *
	 * <h3>「这一 tick 没在到站表里看到它」要分两种</h3>
	 *
	 * <p>记录正常消失的时机是<b>发车那一刻</b>，那时上一次看到它的时刻 ≈ 发车时刻。
	 * 而到站基准 ≤ 发车时刻、发车基准 = 发车时刻，所以<b>正常消失时 base 不会晚于最后一次看到它</b>。
	 * 反过来，如果它消失时 base 还在未来（班次取消了、预测一下子跳了超过容差所以换了一条新的待播项、
	 * 挤出了每站台前 10 条），那它就作废了 —— 留着会在旧时刻放一条没人要的广播。
	 * {@link #VANISHED_SLACK_MILLIS} 留给到站表 1 秒一刷的粒度和时钟换算的抖动。
	 */
	private void processPending(World world, long now) {
		final Iterator<PendingAnnouncement> iterator = pendingAnnouncements.iterator();
		while (iterator.hasNext()) {
			final PendingAnnouncement pendingAnnouncement = iterator.next();
			if (pendingAnnouncement.lastSeenMillis != now && pendingAnnouncement.base > pendingAnnouncement.lastSeenMillis + VANISHED_SLACK_MILLIS) {
				iterator.remove();
				continue;
			}
			if (now < pendingAnnouncement.fireAt) {
				continue;
			}
			iterator.remove();
			if (now > pendingAnnouncement.fireAt + FIRE_GRACE_MILLIS || pendingAnnouncement.ruleIndex >= rules.size()) {
				continue; // 错过了（服务端卡了、区块刚加载），宁可不播
			}
			if (alreadyFired(pendingAnnouncement.ruleIndex, pendingAnnouncement.routeId, pendingAnnouncement.departureIndex, pendingAnnouncement.base)) {
				continue;
			}
			recordFired(pendingAnnouncement.ruleIndex, pendingAnnouncement.routeId, pendingAnnouncement.departureIndex, pendingAnnouncement.base, now);
			play(world, rules.get(pendingAnnouncement.ruleIndex).soundId);
		}
	}

	/**
	 * 这条规则是不是已经为「同一趟车的同一次停靠」播过了。
	 *
	 * <p>命中时顺手把记下来的基准<b>跟到新值</b>：预测慢慢漂移时，每次都只比上一次差一点点，
	 * 永远不会累积到超出容差而误判成新班次。
	 */
	private boolean alreadyFired(int ruleIndex, long routeId, long departureIndex, long base) {
		for (final FiredService firedService : firedServices) {
			if (firedService.ruleIndex == ruleIndex
					&& firedService.routeId == routeId
					&& firedService.departureIndex == departureIndex
					&& Math.abs(firedService.base - base) < SAME_SERVICE_TOLERANCE_MILLIS) {
				firedService.base = base;
				firedService.expiresAt = Math.max(firedService.expiresAt, base + RECORD_LINGER_MILLIS);
				return true;
			}
		}
		return false;
	}

	private void recordFired(int ruleIndex, long routeId, long departureIndex, long base, long now) {
		if (firedServices.size() >= MAX_FIRED_RECORDS) {
			firedServices.remove(0);
		}
		final FiredService firedService = new FiredService();
		firedService.ruleIndex = ruleIndex;
		firedService.routeId = routeId;
		firedService.departureIndex = departureIndex;
		firedService.base = base;
		firedService.expiresAt = Math.max(base, now) + RECORD_LINGER_MILLIS;
		firedServices.add(firedService);
	}

	/** 一条「已经播过」的记录。身份 = 规则序号 + 交路 + 班次序号 + <b>基准时刻</b>。 */
	private static final class FiredService {
		private int ruleIndex;
		private long routeId;
		private long departureIndex;
		private long base;
		private long expiresAt;
	}

	/** 一条「还没到点」的广播。身份和 {@link FiredService} 同构。 */
	private static final class PendingAnnouncement {
		private int ruleIndex;
		private long routeId;
		private long departureIndex;
		private long base;
		private long fireAt;
		private long lastSeenMillis;
	}

	/**
	 * 限制范围用的长方体。
	 *
	 * <p>{@code Box(BlockPos, BlockPos)} 直接拿整数坐标当 min/max，<b>大角不加 1</b>
	 * （原版 {@code Box} 构造函数的字节码确认），而 {@code contains} 判的是 {@code < max}，
	 * 所以照直传两个角点的话，站在「坐标 2」那一格方块上算框外 —— 和界面上
	 * 「两个角点」的说法对不上。这里显式把大角 +1，两个角点所在的方块都算框内。
	 * （JCM 的声音循环器有同样的毛病，没跟。）
	 */
	private Box rangeBox() {
		final BlockPos c1 = getCorner1();
		final BlockPos c2 = getCorner2();
		return new Box(
				Math.min(c1.getX(), c2.getX()), Math.min(c1.getY(), c2.getY()), Math.min(c1.getZ(), c2.getZ()),
				Math.max(c1.getX(), c2.getX()) + 1.0, Math.max(c1.getY(), c2.getY()) + 1.0, Math.max(c1.getZ(), c2.getZ()) + 1.0
		);
	}

	private void play(World world, String soundId) {
		final SoundEvent soundEvent;
		try {
			soundEvent = SoundHelper.createSoundEvent(new Identifier(soundId));
		} catch (Exception e) {
			// id 不合法（界面里手打错了）。每 tick 都会重试，所以只记一次警告不划算，
			// 干脆不记 —— 界面上那一栏是从可用音效列表里选的，正常路径不会走到这。
			return;
		}

		if (limitRange) {
			// 框内均匀播放、框外完全听不到 —— 照抄 JCM 声音循环器的 limitRange 分支。
			// 代价是没有方位感，换来整个站台音量一致。
			//
			// **音量必须给一个大数**，不能给用户设的那个值。
			// ServerPlayerEntity.playSound 编译到 ServerPlayer.playNotifySound，它发的是
			// 一个**定位在玩家当时坐标**的音源；客户端 SoundEngine 的衰减距离是
			// max(volume, 1) × 16 格、增益是 clamp(volume × 分类音量, 0, 1)。
			// 传 1 的话衰减距离只有 16 格 —— 十几秒的报站放到一半，人走出 16 格就静音了。
			// 传大数时增益照样被钳到 1，只是把衰减距离拉到听不出衰减。MTR 自己的
			// BlockTrainAnnouncer 传 1000 正是这个道理。
			final Box box = rangeBox();
			final int[] matched = {0};
			final StringBuilder outsidePlayers = new StringBuilder();
			MinecraftServerHelper.iteratePlayers(ServerWorld.cast(world), serverPlayerEntity -> {
				if (box.contains(serverPlayerEntity.getX(), serverPlayerEntity.getY(), serverPlayerEntity.getZ())) {
					matched[0]++;
					serverPlayerEntity.playSound(soundEvent, SoundCategory.BLOCKS, NON_POSITIONAL_VOLUME, 1);
				} else if (outsidePlayers.length() < 200) {
					outsidePlayers.append(' ').append(serverPlayerEntity.getBlockPos().toShortString());
				}
			});
			// 触发了却一个人都不在框里 —— 这种情况下用户听到的是「完全没声音」，
			// 和「根本没触发」在体感上没法区分。留一条节流过的日志，省得下次又要反推。
			// 注意框内判定是**触发那一刻**的快照：广播开始之后才走进框的人听不到。
			//
			// 坐标一律 toShortString()：mapping 的 BlockPos 继承 HolderBase，**两者都没覆写 toString**，
			// 直接拼出来是「org.mtr.mapping.holder.BlockPos@1a2b3c」。
			if (matched[0] == 0) {
				final long now = System.currentTimeMillis();
				if (now - lastEmptyBoxWarnMillis > EMPTY_BOX_WARN_INTERVAL_MILLIS) {
					lastEmptyBoxWarnMillis = now;
					YuziMtrExt.LOGGER.warn("[{}] 站台广播器触发了 {}，但限制范围 [{}] ~ [{}] 里没有玩家。框外的玩家:{}",
							getPos2().toShortString(), soundId, getCorner1().toShortString(), getCorner2().toShortString(),
							outsidePlayers.length() == 0 ? " （无）" : outsidePlayers.toString());
				}
			}
		} else {
			// 第一个参数是「除了这个玩家」，传 null = 播给所有人。
			// 广播半径由音量决定：volume > 1 时是 16 × volume 格，否则固定 16 格。
			world.playSound(null, getPos2(), soundEvent, SoundCategory.BLOCKS, volume, 1);
		}
	}
}
