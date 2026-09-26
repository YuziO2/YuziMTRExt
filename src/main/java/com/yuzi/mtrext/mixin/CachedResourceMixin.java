package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.Diag;
import org.mtr.mod.resource.CachedResource;
import org.mtr.mod.resource.VehicleModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * 纯探针，不改行为。回答两个问题：
 * 1. VehicleModelMixin 生效了吗 —— 看统计里还有没有 lifespan=60000
 * 2. 重建是因为 data==null（没建成/supplier 返 null）还是因为过期
 */
@Mixin(value = CachedResource.class, remap = false)
public abstract class CachedResourceMixin {

	@Shadow private Object data;
	@Shadow private long expiry;
	@Shadow @Final @Mutable private long lifespan;
	@Shadow private static boolean canFetchCache;

	/**
	 * 把 60 秒寿命的模型缓存改成常驻。
	 *
	 * <p>为什么在 CachedResource 这一层做，而不是逐个改构造函数：MODEL_LIFESPAN(60000)
	 * 在 MTR 4.0.5 里有 6 个使用点（VehicleModel 三个构造函数、VehicleResource:399、
	 * RailResource:21、ObjectResource:23），全都是
	 * {@code shouldPreload ? Integer.MAX_VALUE : MODEL_LIFESPAN} 这个模式。而其中几处
	 * 在构造函数里读 shouldPreload —— 那个字段由 CustomResourceLoader 事后才赋值，
	 * 构造时恒为 false，所以三元表达式永远走 60000 那一支。
	 * 我原先只在 VehicleModel 的构造函数上 @ModifyArg，实测仍有 lifespan=60000 的资源被回收，
	 * 说明没堵全。在这里按值兜底，来源无关。
	 *
	 * <p>为什么必须常驻：RenderVehicles.java:222 每帧调 iterateModels →
	 * cachedModel.getData(false)，一被回收就重建（大 OBJ 车会卡顿）；更要命的是重建出来的
	 * DynamicVehicleModel 是全新的 ModelPropertiesPart，而 mapDoors 只在 VehicleResource
	 * 第 2 层 supplier 里跑一次、不会重跑，于是 partDetails.doorway 是 null、canOpen 恒 false
	 * —— <b>那辆车的门永久打不开</b>。实测日志：
	 * <pre>
	 * [20:16:44] lifespan=60000 原因=被回收(超期11772ms) force=false × 1
	 * [20:16:44] 构建车辆模型: crt5
	 * </pre>
	 * 且期间没有任何 "No floors or doorways"，证明第 2 层确实没跟着重跑。
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void yuzimtr$extendLifespan(Supplier<?> dataSupplier, long lifespanArg, CallbackInfo ci) {
		if (Config.get().keepVehicleModelsResident && lifespan == VehicleModel.MODEL_LIFESPAN) {
			lifespan = Integer.MAX_VALUE;
		}
	}

	@Inject(method = "getData", at = @At("HEAD"))
	private void yuzimtr$logRebuild(boolean force, CallbackInfoReturnable<Object> cir) {
		if (!Config.get().debugCacheLogging) {
			return;
		}
		// 复刻 CachedResource.getData 的判断，不改它
		if (!(force || canFetchCache)) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (data == null || now > expiry) {
			Diag.rebuild(lifespan, data == null, expiry, now, force);
		}
	}
}
