package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.DispatchDiag;
import org.mtr.core.data.Siding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 发车诊断的挂载点。只读，不改任何行为；{@code debugDispatchPattern} 为空时直接返回。
 *
 * <p>{@code matchDeparture()} 是 private，用 {@code @Invoker} 借出来 —— 它没有副作用
 * （只是拿当前时刻去发车表里找匹配的下标），在 TAIL 再调一次是安全的。
 */
@Mixin(value = Siding.class, remap = false)
public abstract class SidingDispatchDebugMixin {

	@Invoker("matchDeparture")
	abstract int yuzimtr$matchDeparture();

	/** 发车表的循环周期。用来判断 departures 里是不是有一部分下标永远轮不到。 */
	@Invoker("getRepeatInterval")
	abstract long yuzimtr$getRepeatInterval(long defaultAmount);

	@Inject(method = "simulateTrain", at = @At("TAIL"))
	private void yuzimtr$dispatchDiag(CallbackInfo ci) {
		DispatchDiag.tick((Siding) (Object) this, yuzimtr$matchDeparture(), yuzimtr$getRepeatInterval(0));
	}
}
