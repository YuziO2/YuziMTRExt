package com.yuzi.mtrext.mixin;

import org.mtr.core.data.Route;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读 Depot$PlatformRouteDetails 的 private final route（私有嵌套类，只能用 targets）。 */
@Mixin(targets = "org.mtr.core.data.Depot$PlatformRouteDetails", remap = false)
public interface PlatformRouteDetailsAccessor {

	@Accessor("route")
	Route yuzimtr$route();
}
