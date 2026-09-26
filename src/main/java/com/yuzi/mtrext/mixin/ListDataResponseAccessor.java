package com.yuzi.mtrext.mixin;

import org.mtr.core.data.Route;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读 {@code ListDataResponse} 里那份全量交路，见 {@link com.yuzi.mtrext.announcer.PacketAllRoutes}。
 *
 * <p>字段 {@code routes} 是 {@code protected final}，而且声明在<b>生成的父类</b>
 * {@code ListDataResponseSchema} 上。按本仓库的注入点约束：{@code @Shadow} / {@code @Accessor}
 * 不沿继承链往上找，字段在哪个类就打在哪个类上 —— 所以这里用
 * {@code @Mixin(targets = ...)} 指向那个 schema 类，而不是指向 {@code ListDataResponse}。
 *
 * <p>返回类型必须和字段<b>精确匹配</b>（写成 {@code Object} 会被注解处理器在编译期拦下）。
 * 只读不写，所以不需要 {@code @Mutable}。
 */
@Mixin(targets = "org.mtr.core.generated.operation.ListDataResponseSchema", remap = false)
public interface ListDataResponseAccessor {

	@Accessor("routes")
	ObjectArrayList<Route> yuzimtr$getRoutes();
}
