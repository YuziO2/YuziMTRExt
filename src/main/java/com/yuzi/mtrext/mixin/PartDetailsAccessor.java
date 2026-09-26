package com.yuzi.mtrext.mixin;

import org.mtr.mapping.holder.Box;
import org.mtr.mod.resource.OptimizedModelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读 ModelPropertiesPart$PartDetails 的私有字段，用 targets 是因为它是私有嵌套类。 */
@Mixin(targets = "org.mtr.mod.resource.ModelPropertiesPart$PartDetails", remap = false)
public interface PartDetailsAccessor {

	/** 开门时唯一被渲染的东西；为 null 的话门就完全不动。 */
	@Accessor("optimizedModelDoor")
	OptimizedModelWrapper yuzimtr$optimizedModelDoor();

	@Accessor("box")
	Box yuzimtr$box();

	@Accessor("doorway")
	Box yuzimtr$doorway();

	@Accessor("flipped")
	boolean yuzimtr$flipped();

	/** flipped 是 final，用 @Mutable 让 mixin 去掉 final 标志。 */
	@Accessor("flipped")
	@Mutable
	void yuzimtr$setFlipped(boolean flipped);
}
