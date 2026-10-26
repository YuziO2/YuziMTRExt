package com.yuzi.mtrext.mixin;

import org.mtr.mod.client.VehicleRidingMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 MTR 客户端「正在坐哪辆车」。字段是 private static，下车时清零（{@code isRiding(id)} 就是拿它和 id 比），
 * 所以非 0 = 在车上。给站台广播器的续播判资格用：触发时正在乘车的人不续播。
 */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public interface VehicleRidingStateAccessor {

	@Accessor("ridingVehicleId")
	static long yuzimtr$getRidingVehicleId() {
		throw new AssertionError();
	}
}
