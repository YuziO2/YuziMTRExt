package com.yuzi.mtrext.mixin;

import org.mtr.core.data.SavedRailBase;
import org.mtr.mod.screen.SavedRailScreenBase;
import org.mtr.mod.screen.WidgetShorterSlider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 取 SavedRailScreenBase 上那几个 protected 字段。
 *
 * <p>为什么不在 PlatformScreenMixin 里直接 {@code @Shadow}：{@code @Shadow}
 * 不会沿类继承链往上找字段（这条之前在 VehicleResourceMixin 上踩过一次，
 * bogie1Models 声明在父类 VehicleResourceSchema 里，直接 InvalidMixinException）。
 * 所以按老规矩 —— 字段声明在哪个类，{@code @Accessor} 就打在哪个类上。
 */
@Mixin(value = SavedRailScreenBase.class, remap = false)
public interface SavedRailScreenAccessor {

	@Accessor("textWidth")
	int yuzimtr$textWidth();

	/** 字段声明是泛型 {@code T}，擦除后描述符是 {@code Lorg/mtr/core/data/SavedRailBase;}，返回类型要对上。 */
	@Accessor("savedRailBase")
	SavedRailBase<?, ?> yuzimtr$savedRailBase();

	@Accessor("showScheduleControls")
	boolean yuzimtr$showScheduleControls();

	@Accessor("sliderDwellTimeMin")
	WidgetShorterSlider yuzimtr$sliderDwellTimeMin();

	@Accessor("sliderDwellTimeSec")
	WidgetShorterSlider yuzimtr$sliderDwellTimeSec();
}
