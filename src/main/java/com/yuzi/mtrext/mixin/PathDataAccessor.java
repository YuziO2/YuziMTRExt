package com.yuzi.mtrext.mixin;

import org.mtr.core.generated.data.PathDataSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 让 {@code PathData.dwellTime} 可写，用来原地改停站时间。
 *
 * <p>之所以能原地改就生效：{@code VehicleExtraData.createPathData} 拼车次路径用的是
 * {@code addAll}，拿的是**同一批 PathData 对象**的引用（只有 repeatInfinitely 的那个
 * 收尾段是新造的）。所以 {@code Depot.path}、{@code Siding.pathMainRoute} 和每辆在跑的车的
 * {@code immutablePath} 指的都是同一批对象 —— 改一处，全都看得见，
 * 运行时停站（{@code Vehicle.java:324} 读 {@code currentPathData.getDwellTime()}）立刻变。
 *
 * <p>字段在字节码里是 {@code protected final}，所以要 {@code @Mutable}。
 */
@Mixin(value = PathDataSchema.class, remap = false)
public interface PathDataAccessor {

	@Accessor("dwellTime")
	@Mutable
	void yuzimtr$setDwellTime(long dwellTime);
}
