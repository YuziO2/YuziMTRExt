package com.yuzi.mtrext.mixin;

import org.mtr.core.data.Rail;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongAVLTreeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 只读地看 Rail 的四张闭塞占用表（颜色 → 车 id），给 {@link com.yuzi.mtrext.SignalEntryCheck} 判断
 * 「这段是不是已经整段归自己」。四张表都声明在 Rail 上（private final）。
 *
 * <p>{@code tick1} 每 tick 把本 tick 的两张挪到 {@code *Old} 再清空，{@code isBlocked} 四张一起查，
 * 所以判断「自己持有」也要四张一起看。
 */
@Mixin(value = Rail.class, remap = false)
public interface RailSignalStateAccessor {

	@Accessor("preBlockedVehicleIds")
	Long2LongAVLTreeMap yuzimtr$preBlocked();

	@Accessor("currentlyBlockedVehicleIds")
	Long2LongAVLTreeMap yuzimtr$currentlyBlocked();

	@Accessor("preBlockedVehicleIdsOld")
	Long2LongAVLTreeMap yuzimtr$preBlockedOld();

	@Accessor("currentlyBlockedVehicleIdsOld")
	Long2LongAVLTreeMap yuzimtr$currentlyBlockedOld();
}
