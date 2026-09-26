package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.AvatarUrlRewriter;
import org.mtr.core.generated.WebserverResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 网页地图的所有静态文件都从这里出去（{@code Main.java:73} 的
 * {@code new MainWebServlet(WebserverResources::get, "/")}），
 * 所以这里就是给 MTR 加「可配置头像接口」的唯一下手点。
 *
 * <p>{@code get} 是 {@code public static}（字节码核对过），所以注在 RETURN 上
 * 用 {@code CallbackInfoReturnable} 改返回值。真正的替换逻辑在
 * {@link AvatarUrlRewriter}，默认模板等于 MTR 原来那个，所以默认零改动、直接原样返回。
 */
@Mixin(value = WebserverResources.class, remap = false)
public abstract class WebserverResourcesMixin {

	@Inject(method = "get(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true)
	private static void yuzimtr$rewriteAvatarUrl(String path, CallbackInfoReturnable<String> cir) {
		final String content = cir.getReturnValue();
		final String rewritten = AvatarUrlRewriter.rewrite(content);
		if (rewritten != content) {
			cir.setReturnValue(rewritten);
		}
	}
}
