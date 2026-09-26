package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.SidingMaxSpeedHolder;
import org.mtr.core.generated.data.SidingSchema;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.WriterBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「车辆最高速」挂在 MTR 自己的 Siding 数据上，没有第二份存档 —— 做法同
 * {@link PlatformDwellMixin}（分交路停站时长）。
 *
 * <p>注在生成的 {@code SidingSchema} 上：{@code Siding} 只覆写了 {@code updateData}（且会调 super），
 * 没覆写 {@code serializeData}，所以两段都一定会跑到。注 {@code serializeData} 而不是
 * {@code serializeFullData}：客户端 ↔ 服务端走的是 {@code Utilities.getJsonObjectFromData}，
 * 明确调前者，这样落盘和联网一起覆盖。{@code Siding(ReaderBase, Data)} 构造里也会调
 * {@code updateData}（字节码确认），所以读档也走这里。
 *
 * <p>多出来的键上游直接忽略，没装本 mod 也能读这份存档（但它保存时会把这个键丢掉）。
 *
 * <p>这个类服务端也会加载，所以只碰 TSC 类型。
 */
@Mixin(value = SidingSchema.class, remap = false)
public abstract class SidingMaxSpeedSchemaMixin implements SidingMaxSpeedHolder {

	@Unique
	private static final String KEY_MAX_SPEED = "yuzimtrMaxSpeedKmh";

	@Unique
	private long yuzimtr$maxSpeedKmh;

	/**
	 * 这个值有没有被<b>显式</b>设置过（读到过这个键，或者在面板里保存过）。
	 *
	 * <h3>为什么需要它：不能「总是写」</h3>
	 *
	 * <p>服务端处理保存走的是 {@code UpdateDataRequest.update()} 里的
	 * {@code existingData.updateData(new JsonReader(Utilities.getJsonObjectFromData(newData)))}，
	 * 而 {@code newData} 是<b>在服务端</b>从客户端 JSON {@code new Siding(reader, data)} 出来的，
	 * 本 mixin 在它身上也生效。客户端没有这个功能时（比如还是 1.3.0 的玩家 —— 1.3.0 和 1.4.0
	 * 注册表一样，能正常进服），JSON 里没有这个键 → {@code newData} 是默认的 0 →
	 * 「总是写」就会写出 {@code 0} → {@code existingData} 读到键 → <b>静默清零</b>。
	 * 那个玩家打开任意一条侧线面板按 Esc（或者在车辆选择器里点一下车型，也会发 {@code addSiding}），
	 * 上限就没了，没有任何日志。
	 *
	 * <p>只在设置过时才写，四条路径都对：面板改回 0 → 设置过 → 写 0；没这功能的客户端 → 没设置过 →
	 * 不写 → 服务端原值不动；旧存档 → 不写（本来就是 0）；服务端下发 → 设置过就带着键。
	 *
	 * <p>分交路停站时长（{@link PlatformDwellMixin}）是同一个「总是写」的模式，有同样的问题，还没改。
	 */
	@Unique
	private boolean yuzimtr$maxSpeedSet;

	/**
	 * 低于这个数（但不是 0）一律当它。
	 *
	 * <p>不是为了语义，是为了代价：时刻表推算的内层循环<b>按 1 ms 步进</b>
	 * （{@code railProgress += speed; time++}），迭代次数 = 全程毫秒数，每步还调一次
	 * {@code getUpcomingSlowerSpeed}。1 km/h 跑 10 km 是 3.6×10⁷ 次，而且改一条侧线会重推整个车厂、
	 * 全在模拟线程上同步跑，期间所有车冻结。10 km/h 是 MTR 最慢轨道（20 km/h）的一半，
	 * 代价最多是那种轨道的两倍，可以接受。
	 */
	@Unique
	private static final long MIN_SPEED_KMH = 10;

	@Override
	public long yuzimtr$getMaxSpeedKmh() {
		return yuzimtr$maxSpeedKmh;
	}

	@Override
	public void yuzimtr$setMaxSpeedKmh(long maxSpeedKmh) {
		final long clamped = Math.max(0, Math.min(MAX_SPEED_KMH_LIMIT, maxSpeedKmh));
		yuzimtr$maxSpeedKmh = clamped > 0 && clamped < MIN_SPEED_KMH ? MIN_SPEED_KMH : clamped;
		yuzimtr$maxSpeedSet = true;
	}

	/**
	 * 用 {@code unpackLong}：<b>键存在才回调</b>（回调里会把「设置过」置上）。缺键就不动当前值。
	 */
	@Inject(method = "updateData(Lorg/mtr/core/serializer/ReaderBase;)V", at = @At("TAIL"))
	private void yuzimtr$readMaxSpeed(ReaderBase readerBase, CallbackInfo ci) {
		readerBase.unpackLong(KEY_MAX_SPEED, this::yuzimtr$setMaxSpeedKmh);
	}

	/** 只在设置过时写（包括 0），理由见 {@link #yuzimtr$maxSpeedSet}。 */
	@Inject(method = "serializeData(Lorg/mtr/core/serializer/WriterBase;)V", at = @At("TAIL"))
	private void yuzimtr$writeMaxSpeed(WriterBase writerBase, CallbackInfo ci) {
		if (yuzimtr$maxSpeedSet) {
			writerBase.writeLong(KEY_MAX_SPEED, yuzimtr$maxSpeedKmh);
		}
	}
}
