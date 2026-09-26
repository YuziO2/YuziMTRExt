package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.client.NarratorGate;
import org.mtr.mod.config.Client;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 consistSizes 里配了的车自动进入 MTR 的预加载，不必再去改 MTR 自己的
 * {@code config/mtr.json} 里的 {@code preloadResourcePattern}。
 *
 * <p>预热真实编组桶（见 VehicleResourceMixin）只在 MTR 的预加载调用点上生效，
 * 而那个循环由 {@code shouldPreload = Config.getClient().matchesPreloadResourcePattern(id)}
 * 控制（CustomResourceLoader.java:162-167、VehicleResource.java:174）。
 * 在这里对 consistSizes 命中的车返回 true，mod 就自包含了 —— 装上即可，
 * 不需要手工改 MTR 的配置。
 *
 * <p>顺带在这里挂了讲述人闸门（{@code getTextToSpeechAnnouncements}）——
 * 目标类相同，没必要再起一个 mixin。那一段与预加载无关，见 {@link NarratorGate}。
 *
 * <p>与 MTR 自己的 pattern 是「或」关系：MTR 配了的照样预加载。
 * 反过来说，把 preloadResourcePattern 清空反而更快 —— consistSizes 只列了存档里
 * 真正在用的车，而 {@code mtr_custom_train_.*} 会把没用到的变体也一起预热。
 */
@Mixin(value = Client.class, remap = false)
public abstract class ClientConfigMixin {

	@Inject(method = "matchesPreloadResourcePattern", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$preloadConfiguredVehicles(String id, CallbackInfoReturnable<Boolean> cir) {
		final Config config = Config.get();
		if (config.preloadConfiguredVehicles && config.hasConsistSizesEntry(id)) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * 报站期间让讲述人闭嘴，见 {@link com.yuzi.mtrext.mixin.RouteAnnouncementMuteMixin}。
	 *
	 * <p>旗子只在一次同步回调期间举起，那期间唯一会读这个开关的就是
	 * {@code IDrawing.narrateOrAnnounce}，所以不会误伤别处（配置界面读它是在旗子落下之后）。
	 */
	@Inject(method = "getTextToSpeechAnnouncements", at = @At("HEAD"), cancellable = true)
	private void yuzimtr$muteNarratorWhileGateRaised(CallbackInfoReturnable<Boolean> cir) {
		if (NarratorGate.isRaised()) {
			cir.setReturnValue(false);
		}
	}
}
