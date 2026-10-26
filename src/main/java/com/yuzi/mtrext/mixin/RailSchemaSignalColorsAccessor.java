package com.yuzi.mtrext.mixin;

import org.mtr.core.generated.data.RailSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 轨道信号颜色的原始 long 值。{@code Rail.getSignalColors()} 会强转成 int 再返回，
 * 而占用表的键是这里的 long 原值，要逐色对表就得用原值。字段声明在 RailSchema 上（protected），
 * {@code @Shadow} 不沿继承链，所以单独一个 accessor。
 */
@Mixin(value = RailSchema.class, remap = false)
public interface RailSchemaSignalColorsAccessor {

	@Accessor("signalColors")
	LongArrayList yuzimtr$signalColors();
}
