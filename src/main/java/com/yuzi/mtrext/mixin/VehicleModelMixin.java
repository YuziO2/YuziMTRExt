package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.VehicleIdHolder;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.mod.render.DynamicVehicleModel;
import org.mtr.mod.resource.ModelProperties;
import org.mtr.mod.resource.PositionDefinitions;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.mtr.mod.resource.VehicleModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 修复：车辆模型每 60 秒被丢弃重建。
 *
 * <p>MTR 4.0.5 的三个 VehicleModel 构造函数都写：
 * <pre>cachedModel = new CachedResource&lt;&gt;(..., shouldPreload ? Integer.MAX_VALUE : MODEL_LIFESPAN);</pre>
 * 但 {@code shouldPreload} 是个初始为 false 的实例字段，由 CustomResourceLoader
 * 在对象构造<b>之后</b>才赋值。所以三元表达式永远走 else 分支，寿命恒为
 * MODEL_LIFESPAN = 60000 毫秒 —— 而这层缓存装的正是 OBJ 解析结果。
 *
 * <p>后果：每 60 秒，视野内所有车辆模型被 CachedResource.tick() 清空并重新解析。
 * 重庆5 的 crt5.obj 有 34.93 MB，单次解析约 2 秒，而 ModelResourceLoader.loadModel
 * 没有任何缓存。3 个变体轮流重建 ≈ 每分钟卡死 6 秒以上，且永远循环。
 *
 * <p>参考：NTE 的 ModelManager.loadRawModel 按文件路径缓存，永不过期，
 * 只在资源重载时 clear()。这里做的是同一件事，只是保持每个 VehicleModel
 * 各持一份 —— 因为 MTR4 的 ObjModel 会被 addTransformation 就地修改
 * （ModelPropertiesPart.java:536），实例不能共享。
 */
@Mixin(value = VehicleModel.class, remap = false)
public class VehicleModelMixin {

	private static int yuzimtr$firedCount;

	/**
	 * ModelProperties 自己不知道属于哪辆车，而按车覆盖贯通道尺寸需要这个身份。
	 * createModel 同时拿着两者，在这里贴上去（见 VehicleIdHolder）。
	 */
	@Inject(
			method = "createModel(Lorg/mtr/mod/resource/ModelProperties;Lorg/mtr/mod/resource/PositionDefinitions;Ljava/lang/String;)Lorg/mtr/mod/render/DynamicVehicleModel;",
			at = @At("HEAD")
	)
	private void yuzimtr$tagVehicleId(ModelProperties modelProperties, PositionDefinitions positionDefinitions, String id, CallbackInfoReturnable<DynamicVehicleModel> cir) {
		// 实测这里的 id 是原始 MTR3 id（如 crt5），不是 LegacyVehicleResource.java:55
		// 给 VehicleResource 的 mtr_custom_train_crt5_trailer —— 写 gangwayOverrides 的正则时注意
		((VehicleIdHolder) (Object) modelProperties).yuzimtr$setVehicleId(id);
		if (Config.get().debugCacheLogging) {
			// createModel 就是 VehicleModel.cachedModel 的 supplier —— 每打一行就是
			// 一次完整的模型构建（含 OBJ 解析）。用来给重建统计补上车辆身份。
			YuziMtrExt.LOGGER.info("构建车辆模型: {}", id);
		}
	}

	/**
	 * 注意用 Integer.MAX_VALUE 而不是 Long.MAX_VALUE：
	 * CachedResource.getData 里算的是 {@code expiry = currentMillis + lifespan}，
	 * Long.MAX_VALUE 会整数溢出成负数，于是 {@code currentMillis > expiry} 恒真，
	 * 变成「立刻过期」—— 比不改还糟。MTR 自己的 preload 分支也用 Integer.MAX_VALUE
	 * （约 24.8 天），沿用它。
	 */
	@ModifyArg(
			method = "<init>",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/mod/resource/CachedResource;<init>(Ljava/util/function/Supplier;J)V"
			),
			index = 1
	)
	private long yuzimtr$keepModelResident(long lifespan) {
		if (!Config.get().keepVehicleModelsResident) {
			return lifespan;
		}
		// 只打前几条，证明注入点确实命中；475 辆车会调很多次
		if (yuzimtr$firedCount < 3) {
			yuzimtr$firedCount++;
			YuziMtrExt.LOGGER.info("VehicleModelMixin 生效: lifespan {} -> {}", lifespan, Integer.MAX_VALUE);
		}
		return Integer.MAX_VALUE;
	}
}
