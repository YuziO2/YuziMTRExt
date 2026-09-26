package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.client.VehicleHull;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectBooleanImmutablePair;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.client.GangwayMovementPositions;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.render.PositionAndRotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 采集车厢碰撞轮廓的钩子，见 {@link VehicleHull}。
 *
 * <p>选 {@code movePlayer} 是因为它是<b>唯一</b>一个「渲染阶段、每节被渲染的车都会调一次、
 * 参数里同时有车厢位姿和这一帧的开门状态」的地方（{@code RenderVehicles} 在 {@code if (canRide)}
 * 里调它，旁观者不调）。它自己在非乘车分支第二行就 return 了，我们在 HEAD 只读参数，没有副作用。
 *
 * <p><b>必须写全描述符</b>：同类里还有一个 {@code private static void movePlayer(double, double, double)}，
 * 只写方法名会报 {@code InvalidInjectionException: Multiple methods}（见 README「注入点约束」）。
 * 参数顺序按字节码核实过：{@code (millisElapsed, vehicleId, carNumber, floorsAndDoorways, ×3 gangway, pose)} ——
 * 第一个 long 被拿去乘 {@code 0.005f} 当移动速度，第二个 long 直接喂给 {@code isRiding(J)Z}。
 */
@Mixin(value = VehicleRidingMovement.class, remap = false)
public abstract class VehicleHullCaptureMixin {

	@Inject(
			method = "movePlayer(JJILorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectArrayList;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/client/GangwayMovementPositions;Lorg/mtr/mod/render/PositionAndRotation;)V",
			at = @At("HEAD")
	)
	private static void yuzimtr$captureHull(
			long millisElapsed,
			long vehicleId,
			int carNumber,
			ObjectArrayList<ObjectBooleanImmutablePair<Box>> floorsAndDoorways,
			GangwayMovementPositions gangwayMovementPositions1,
			GangwayMovementPositions gangwayMovementPositions2,
			GangwayMovementPositions gangwayMovementPositions3,
			PositionAndRotation positionAndRotation,
			CallbackInfo callbackInfo
	) {
		VehicleHull.capture(vehicleId, carNumber, floorsAndDoorways, positionAndRotation);
	}
}
