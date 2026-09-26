package com.yuzi.mtrext.mixin;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.generated.resource.ModelPropertiesPartSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 ModelPropertiesPartSchema 的 protected 字段。
 * 必须挂在声明这些字段的类上 —— @Shadow/@Accessor 对字段不沿继承链上溯。
 */
@Mixin(value = ModelPropertiesPartSchema.class, remap = false)
public interface PartSchemaAccessor {

	@Accessor("names")
	ObjectArrayList<String> yuzimtr$names();

	@Accessor("doorXMultiplier")
	double yuzimtr$doorXMultiplier();

	@Accessor("doorZMultiplier")
	double yuzimtr$doorZMultiplier();

	// 两个 multiplier 都是 final，用 @Mutable 去掉 final 标志
	@Accessor("doorXMultiplier")
	@Mutable
	void yuzimtr$setDoorXMultiplier(double value);

	@Accessor("doorZMultiplier")
	@Mutable
	void yuzimtr$setDoorZMultiplier(double value);
}
