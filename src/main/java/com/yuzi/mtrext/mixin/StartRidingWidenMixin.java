package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.client.VehicleHull;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.client.VehicleRidingMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 让 MTR 的上车触发面和隐形墙的洞重合，见 {@link VehicleHull#widenForBoarding}。
 *
 * <p>{@code startRiding} 用 {@code boxContains} 拿玩家在车厢局部坐标系里的位置去撞这些盒子，
 * 撞上就上车。我们把盒子朝车外拉到墙面，于是「墙放行的位置」就是「上车的位置」，
 * 玩家不会在两者之间那条空隙里掉下去。
 *
 * <p>全描述符是必须的：这个类里 {@code movePlayer} 有重载，{@code startRiding} 虽然没有，
 * 但保持一致更省事（见 README「注入点约束」）。参数里只有一个 {@code ObjectArrayList}，
 * 所以 {@code argsOnly} 不会歧义；处理函数尾部再捕获目标方法
 * 开头的 5 个参数，就能拿到 {@code vehicleId} 和 {@code carNumber}。
 */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public abstract class StartRidingWidenMixin {

	@ModifyVariable(
			method = "startRiding(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;JJJIDDDD)V",
			at = @At("HEAD"),
			argsOnly = true
	)
	private static ObjectArrayList<Box> yuzimtr$widenDoorways(
			ObjectArrayList<Box> value,
			ObjectArrayList<Box> openFloorsAndDoorways,
			long depotId, long sidingId, long vehicleId, int carNumber
	) {
		return VehicleHull.widenForBoarding(vehicleId, carNumber, value);
	}
}
