package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import org.mtr.mod.resource.CachedResource;
import org.mtr.mod.resource.VehicleResource;
import org.mtr.mod.resource.VehicleResourceCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Pattern;

/**
 * 两个修复，都是围绕 getCachedVehicleResource 的「桶」。
 *
 * <p>MTR 4.0.5 按 (carNumber, totalCars) 给车辆模型分缓存桶，但只有
 * {@code extraModelsSupplier == null}（MTR4 原生车）才归一化到 (0,0)
 * （VehicleResource.java:200）。MTR3 转换车一定带 supplier
 * （LegacyVehicleResource.java:252），所以每个桶都要重新 new VehicleModel、
 * 重新走一遍 ModelResourceLoader.loadModel —— 那个方法零缓存。
 * 重庆5 的 crt5.obj 有 34.93 MB，单次解析约 2 秒。
 *
 * <p>实测（3 节编组、6 个 VehicleResource 在视野内）渲染每帧请求两个桶：
 * <pre>
 * crt5_cab_1  (0,3) × 7184   ← 车体，预加载过
 * crt5_cab_1  (0,1) × 7184   ← 转向架，从没预加载过
 * </pre>
 */
@Mixin(value = VehicleResource.class, remap = false)
public abstract class VehicleResourceMixin {

	// 注意：只能 @Shadow 目标类自己声明的字段。bogie1Models/bogie2Models 声明在父类
	// VehicleResourceSchema，@Shadow 对字段不会沿继承链上溯，写了会 InvalidMixinException。
	@Shadow private Int2ObjectAVLTreeMap<Int2ObjectAVLTreeMap<CachedResource<CachedResource<CachedResource<Object>>>>> cachedVehicleResource;

	/** LegacyVehicleResource.Variation 的四个值，见 LegacyVehicleResource.java:55。 */
	@Unique
	private static final String[] YUZIMTR_LEGACY_SUFFIXES = {"_trailer", "_cab_1", "_cab_2", "_cab_3"};

	/** 防止改写预热参数后重入自己。 */
	@Unique
	private boolean yuzimtr$warming;

	/**
	 * 修复 1：让 queueBogie 复用已经建好的桶，而不是自己开一个 (0,1)。
	 *
	 * <p>queueBogie（VehicleResource.java:233）硬编码请求 (0, 1)，与车体的
	 * (carNumber, totalCars) 是完全不同的桶。实测每个 VehicleResource 每帧
	 * 各请求一次，两个桶次数一模一样：
	 * <pre>
	 * crt5_cab_1  (0,3) × 7184   ← 车体，预加载过
	 * crt5_cab_1  (0,1) × 7184   ← 转向架，从没预加载过
	 * </pre>
	 * 于是 crt5 那 34.93 MB 的 OBJ 又被完整解析一遍。
	 *
	 * <p>但它只读 optimizedModelsBogie1/2，而那两份数据在
	 * cachedVehicleResourceInitializer 里是从 bogie1Models/bogie2Models 生成的，
	 * 全程不涉及 carNumber/totalCars —— <b>每个桶里的内容完全相同</b>。
	 * 所以随便复用一个已存在的桶都是等价的，还省掉一次完整构建。
	 */
	@Redirect(
			method = "queueBogie",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/mod/resource/VehicleResource;getCachedVehicleResource(IIZ)Lorg/mtr/mod/resource/VehicleResourceCache;"
			)
	)
	private VehicleResourceCache yuzimtr$bogieReuseWarmBucket(VehicleResource self, int carNumber, int totalCars, boolean force) {
		if (!Config.get().reuseBucketForBogies) {
			return self.getCachedVehicleResource(carNumber, totalCars, force);
		}
		// 找任意一个已经建好的桶。原生车走不到这里也无害：它们的桶恒为 (0,0)，
		// getCachedVehicleResource 内部会把参数归一化掉。
		for (final int existingCarNumber : cachedVehicleResource.keySet()) {
			final Int2ObjectAVLTreeMap<CachedResource<CachedResource<CachedResource<Object>>>> inner = cachedVehicleResource.get(existingCarNumber);
			if (inner != null) {
				for (final int existingTotalCars : inner.keySet()) {
					return self.getCachedVehicleResource(existingCarNumber, existingTotalCars, force);
				}
			}
		}
		// 一个桶都还没有，照原样走
		return self.getCachedVehicleResource(carNumber, totalCars, force);
	}

	/**
	 * 修复 2：预加载预热真实编组桶，而不是那个用不到的 (0,0)。
	 * 后缀 → 桶的推断沿用 MTR 自己 VehicleResource.java:298-299 的那套。
	 */
	@Inject(method = "getCachedVehicleResource", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$preloadRealBucket(int carNumber, int totalCars, boolean force, CallbackInfoReturnable<VehicleResourceCache> cir) {
		final Config config = Config.get();
		final VehicleResource self = (VehicleResource) (Object) this;
		final String id = self.getId();

		// 诊断：只在「即将新建一个桶」时打一行，这是唯一有信息量的时刻。
		// 每帧都打的话单个 ID 能刷出八千行。
		if (config.debugCacheLogging) {
			final Int2ObjectAVLTreeMap<CachedResource<CachedResource<CachedResource<Object>>>> inner = cachedVehicleResource.get(carNumber);
			if (inner == null || !inner.containsKey(totalCars)) {
				YuziMtrExt.LOGGER.info("新建缓存桶: {} ({},{}) force={} 已有={}", id, carNumber, totalCars, force, yuzimtr$describeBuckets());
			}
		}

		if (!config.preloadRealConsistBuckets || yuzimtr$warming) {
			return;
		}
		// 只拦预加载那一个调用点：CustomResourceLoader.java:164 传的 force=true 且 (0,0)
		if (!force || carNumber != 0 || totalCars != 0) {
			return;
		}
		if (!yuzimtr$isLegacyConverted(id)) {
			// MTR4 原生车 extraModelsSupplier == null，(0,0) 就是唯一的桶
			return;
		}

		yuzimtr$warming = true;
		try {
			VehicleResourceCache last = null;
			if (id.endsWith("cab_3")) {
				// Variation.CAB_3 是「双头单节」，只有 (0,1) 这一个桶
				last = self.getCachedVehicleResource(0, 1, true);
			} else {
				for (final int consistSize : config.findConsistSizes(id)) {
					if (consistSize <= 0) {
						continue;
					}
					if (id.endsWith("cab_1")) {
						last = self.getCachedVehicleResource(0, consistSize, true);
					} else if (id.endsWith("cab_2")) {
						last = self.getCachedVehicleResource(consistSize - 1, consistSize, true);
					} else {
						// trailer：中间车有 consistSize-2 个位置，每个都是独立的桶
						for (int middleCar = 1; middleCar <= consistSize - 2; middleCar++) {
							last = self.getCachedVehicleResource(middleCar, consistSize, true);
						}
					}
				}
			}
			cir.setReturnValue(last);
		} finally {
			yuzimtr$warming = false;
		}
	}

	/**
	 * 修复 3（可选）：关掉 MTR4 生成的贯通道。
	 * 只对 disableGangwayPattern 匹配的车辆生效，默认空 = 不动。
	 */
	@Inject(method = "hasGangway1", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$suppressGangway1(CallbackInfoReturnable<Boolean> cir) {
		if (yuzimtr$gangwayDisabled()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "hasGangway2", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$suppressGangway2(CallbackInfoReturnable<Boolean> cir) {
		if (yuzimtr$gangwayDisabled()) {
			cir.setReturnValue(false);
		}
	}

	@Unique
	private boolean yuzimtr$gangwayDisabled() {
		final Pattern pattern = Config.get().disableGangwayPatternCompiled();
		return pattern != null && pattern.matcher(((VehicleResource) (Object) this).getId()).matches();
	}

	@Unique
	private String yuzimtr$describeBuckets() {
		final StringBuilder sb = new StringBuilder("[");
		cachedVehicleResource.forEach((car, inner) -> inner.forEach((total, level1) ->
				sb.append('(').append((int) car).append(',').append((int) total).append(')')));
		return sb.append(']').toString();
	}

	@Unique
	private static boolean yuzimtr$isLegacyConverted(String id) {
		for (final String suffix : YUZIMTR_LEGACY_SUFFIXES) {
			if (id.endsWith(suffix)) {
				return true;
			}
		}
		return false;
	}
}
