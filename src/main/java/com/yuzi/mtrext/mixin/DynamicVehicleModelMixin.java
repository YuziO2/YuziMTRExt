package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.render.DynamicVehicleModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 去掉座位的「碰撞箱」，让人能走到窗边。
 *
 * <p>其实没有座位碰撞箱这回事 —— 限制来自地板。VehicleRidingMovement.clampPosition
 * 把玩家夹在<b>单个</b> FLOOR 盒子里：
 * <pre>
 * Utilities.clamp(x, floorOrDoorway.left().getMinXMapped(), floorOrDoorway.left().getMaxXMapped())
 * </pre>
 * MTR 的模型只在过道铺 FLOOR，座位底下没有，所以过道地板的 x 边界就成了「座位碰撞箱」。
 *
 * <p>做法：把同一个模型贡献的所有 floor 盒子统一撑到其中最宽那个的 x 范围。
 * 车门附近的 floor 通常本来就到车壁（要能从侧面上车），所以这个自动适配车宽，
 * 不用给每种车配一个数字。不够的话再用 floorInflateX 额外加一点。
 *
 * <p>注入 writeFloorsAndDoorways 的 HEAD/TAIL：floors 是跨模型累加的，
 * 所以在 HEAD 记下长度，TAIL 只处理本次新增的那一段。
 */
@Mixin(value = DynamicVehicleModel.class, remap = false)
public abstract class DynamicVehicleModelMixin {

	@Unique
	private int yuzimtr$floorsBefore;

	@Inject(method = "writeFloorsAndDoorways", at = @At("HEAD"))
	private void yuzimtr$recordFloorCount(
			ObjectArrayList<Box> floors, ObjectArrayList<Box> doorways,
			Object2ObjectOpenHashMap<?, ?> materialGroups, Object2ObjectOpenHashMap<?, ?> materialGroupsDoorsClosed,
			Object2ObjectOpenHashMap<?, ?> objModels, Object2ObjectOpenHashMap<?, ?> objModelsDoorsClosed,
			CallbackInfo ci
	) {
		yuzimtr$floorsBefore = floors.size();
	}

	@Inject(method = "writeFloorsAndDoorways", at = @At("TAIL"))
	private void yuzimtr$widenFloors(
			ObjectArrayList<Box> floors, ObjectArrayList<Box> doorways,
			Object2ObjectOpenHashMap<?, ?> materialGroups, Object2ObjectOpenHashMap<?, ?> materialGroupsDoorsClosed,
			Object2ObjectOpenHashMap<?, ?> objModels, Object2ObjectOpenHashMap<?, ?> objModelsDoorsClosed,
			CallbackInfo ci
	) {
		final Config config = Config.get();
		final double extra = config.floorInflateX;
		if (!config.widenFloorsToInterior && extra <= 0) {
			return;
		}
		if (yuzimtr$floorsBefore >= floors.size()) {
			return;
		}

		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		if (config.widenFloorsToInterior) {
			for (int i = yuzimtr$floorsBefore; i < floors.size(); i++) {
				minX = Math.min(minX, floors.get(i).getMinXMapped());
				maxX = Math.max(maxX, floors.get(i).getMaxXMapped());
			}
		}

		for (int i = yuzimtr$floorsBefore; i < floors.size(); i++) {
			final Box box = floors.get(i);
			final double newMinX = (config.widenFloorsToInterior ? Math.min(box.getMinXMapped(), minX) : box.getMinXMapped()) - extra;
			final double newMaxX = (config.widenFloorsToInterior ? Math.max(box.getMaxXMapped(), maxX) : box.getMaxXMapped()) + extra;
			floors.set(i, new Box(
					newMinX, box.getMinYMapped(), box.getMinZMapped(),
					newMaxX, box.getMaxYMapped(), box.getMaxZMapped()
			));
		}
	}
}
