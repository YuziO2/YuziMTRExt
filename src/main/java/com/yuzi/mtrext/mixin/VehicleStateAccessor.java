package com.yuzi.mtrext.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 只读地看几个车次状态，给发车诊断用。这几个字段都声明在 VehicleSchema 上（protected）。 */
@Mixin(value = VehicleSchema.class, remap = false)
public interface VehicleStateAccessor {

	@Accessor("railProgress")
	double yuzimtr$railProgress();

	@Accessor("sidingDepartureTime")
	long yuzimtr$sidingDepartureTime();

	@Accessor("speed")
	double yuzimtr$speed();
}
