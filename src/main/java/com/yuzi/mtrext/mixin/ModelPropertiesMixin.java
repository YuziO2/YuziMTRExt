package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.GangwayOverride;
import com.yuzi.mtrext.VehicleIdHolder;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mod.resource.ModelProperties;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/** 按车辆覆盖贯通道尺寸，详见 {@link GangwayOverride}。 */
@Mixin(value = ModelProperties.class, remap = false)
public abstract class ModelPropertiesMixin implements VehicleIdHolder {

	// 这六个字段是 ModelProperties 自己声明的（不在父类 schema），所以 @Shadow 合法。
	// public final，用 @Mutable 让 mixin 去掉 final 标志以便写入。
	// MTR4 自己在另一个构造函数里也把它们设成 null（ModelProperties.java:58-61），
	// 所以 null 是它支持的状态。
	@Shadow @Final @Mutable public Identifier gangwayInnerSideTexture;
	@Shadow @Final @Mutable public Identifier gangwayInnerTopTexture;
	@Shadow @Final @Mutable public Identifier gangwayInnerBottomTexture;
	@Shadow @Final @Mutable public Identifier gangwayOuterSideTexture;
	@Shadow @Final @Mutable public Identifier gangwayOuterTopTexture;
	@Shadow @Final @Mutable public Identifier gangwayOuterBottomTexture;

	@Unique
	private String yuzimtr$vehicleId = "";

	@Override
	public void yuzimtr$setVehicleId(String vehicleId) {
		this.yuzimtr$vehicleId = vehicleId == null ? "" : vehicleId;
		final GangwayOverride override = yuzimtr$override();
		if (override != null && Boolean.TRUE.equals(override.hideGangway)) {
			// 贴图置 null → MainRenderer.java:170 整段跳过。
			// 不动 hasGangway1/2，所以可行走地板还在。
			gangwayInnerSideTexture = null;
			gangwayInnerTopTexture = null;
			gangwayInnerBottomTexture = null;
			gangwayOuterSideTexture = null;
			gangwayOuterTopTexture = null;
			gangwayOuterBottomTexture = null;
		}
	}

	@Override
	public String yuzimtr$getVehicleId() {
		return yuzimtr$vehicleId;
	}

	@Inject(method = "getGangwayWidth", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$overrideWidth(CallbackInfoReturnable<Double> cir) {
		final GangwayOverride o = yuzimtr$override();
		if (o != null && o.width != null) {
			cir.setReturnValue(o.width);
		}
	}

	@Inject(method = "getGangwayHeight", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$overrideHeight(CallbackInfoReturnable<Double> cir) {
		final GangwayOverride o = yuzimtr$override();
		if (o != null && o.height != null) {
			cir.setReturnValue(o.height);
		}
	}

	@Inject(method = "getGangwayYOffset", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$overrideYOffset(CallbackInfoReturnable<Double> cir) {
		final GangwayOverride o = yuzimtr$override();
		if (o != null && o.yOffset != null) {
			cir.setReturnValue(o.yOffset);
		}
	}

	@Inject(method = "getGangwayZOffset", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$overrideZOffset(CallbackInfoReturnable<Double> cir) {
		final GangwayOverride o = yuzimtr$override();
		if (o != null && o.zOffset != null) {
			cir.setReturnValue(o.zOffset);
		}
	}

	@Unique
	@Nullable
	private GangwayOverride yuzimtr$override() {
		return yuzimtr$vehicleId.isEmpty() ? null : Config.get().findGangwayOverride(yuzimtr$vehicleId);
	}
}
