package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.client.VehicleHullCollision;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 车厢隐形墙的落点：在位移被应用<b>之前</b>改写它，见 {@link VehicleHullCollision}。
 *
 * <p>{@code ClientPlayerEntity} 里只有这一个 {@code move}（javap 确认，它覆写
 * {@code Entity.move} 后先调 super 再做 autoJump），所以 {@code argsOnly} 的
 * {@code @ModifyVariable} 唯一命中那个 {@code Vec3d} 参数，不会歧义。
 *
 * <p>选它而不是 {@code Entity.adjustMovementForCollisions}：后者有三个同名重载，而且是
 * {@code Entity} 级别的，会波及掉落物、生物、其他玩家 —— 那些实体在别人的客户端上没有这堵墙，
 * 位置立刻分歧。{@code ClientPlayerEntity} 只有本地玩家会走到（其他玩家是
 * {@code OtherClientPlayerEntity}）。
 *
 * <p>只处理 {@code SELF} 和 {@code PLAYER}：{@code PISTON} / {@code SHULKER_BOX} 是被外力推的，
 * 拦下来只会把人卡在活塞里。
 *
 * <p>处理函数尾部可以再接目标方法<b>开头若干个</b>参数（{@code ModifyVariableInjector} 里的
 * {@code captureTargetArgs} 是个计数，不是全有全无），所以这里只捕获第一个 {@code MovementType}。
 *
 * <p><b>这是本仓库第一个以原版类为目标的 mixin</b>，因此 {@code remap} 走默认的 {@code true}，
 * 产物里的 refmap 从这个 mixin 开始才有内容（此前所有 mixin 都是 {@code remap = false} 的
 * MTR 目标）。开发环境用 yarn 名、生产用 intermediary 名，两边都要实测一次。
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerMoveMixin {

	@ModifyVariable(method = "move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V", at = @At("HEAD"), argsOnly = true)
	private Vec3d yuzimtr$clampAgainstVehicleHulls(Vec3d movement, MovementType movementType) {
		if (movementType != MovementType.SELF && movementType != MovementType.PLAYER) {
			return movement;
		}
		final ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
		return VehicleHullCollision.clamp(self.getX(), self.getY(), self.getZ(), movement);
	}
}
