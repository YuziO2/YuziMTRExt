package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.announcer.AllRoutesCache;
import com.yuzi.mtrext.announcer.RouteEntry;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongCollection;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mod.screen.DashboardListItem;
import org.mtr.mod.screen.DashboardListSelectorScreen;
import org.mtr.mod.screen.TrainAnnouncerScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;

/**
 * 只在<b>广播器</b>打开交路选择器时，把候选列表换成全量交路（含隐藏、不限距离）。
 *
 * <p>数据从哪来、为什么不直接放宽 MTR 的共享数据，见 {@link com.yuzi.mtrext.announcer.PacketAllRoutes}。
 *
 * <h2>注入点的选择</h2>
 *
 * <p>候选列表是在 {@code TrainSensorScreenBase} 的按钮回调里现攒的，那是个 lambda ——
 * 按 {@code lambda$new$3} 这种会随上游改动重新编号的名字注入是本仓库明令避免的。
 * 换个位置：列表最终要传给这个屏幕的构造函数，而构造函数是稳定的注入点。
 *
 * <p>5 参构造函数只是转发给 6 参那个（字节码确认），所以打在 6 参上就够，两条路都覆盖。
 *
 * <p>选 {@code RETURN} 而不是 {@code HEAD}：构造函数注入 HEAD 在 mixin 里有限制，
 * 而这里完全不需要抢在前面 —— {@code allData} 要到 {@code init2()} 末尾的
 * {@code updateList()} 才被读（字节码确认构造期间不调 {@code updateList}），
 * 构造函数返回时改还来得及。
 *
 * <p>{@code allData} 是 {@code protected final}，所以要 {@code @Mutable}（它会把
 * {@code ACC_FINAL} 抹掉，否则从注入方法里 {@code putfield} 过不了校验）。
 *
 * <h2>为什么只认广播器</h2>
 *
 * <p>{@code TrainSensorScreenBase} 被三个界面共用 —— 广播器、基础列车感应器、列车时刻表感应器。
 * 用户要的是广播器，所以这里只认 {@code TrainAnnouncerScreen}，另外两个保持原样。
 * 要放开的话把这个 {@code instanceof} 去掉即可。
 */
@Mixin(value = DashboardListSelectorScreen.class, remap = false)
public abstract class DashboardListSelectorScreenMixin {

	@Shadow
	@Final
	@Mutable
	protected ObjectImmutableList<DashboardListItem> allData;

	@Inject(
			method = "<init>(Ljava/lang/Runnable;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectImmutableList;Lorg/mtr/libraries/it/unimi/dsi/fastutil/longs/LongCollection;ZZLorg/mtr/mapping/mapper/ScreenExtension;)V",
			at = @At("RETURN")
	)
	private void yuzimtr$useAllRoutesForAnnouncer(
			Runnable onClose,
			ObjectImmutableList<DashboardListItem> originalData,
			LongCollection selectedIds,
			boolean isSingleSelect,
			boolean canRepeat,
			ScreenExtension parent,
			CallbackInfo callbackInfo
	) {
		if (!Config.get().announcerAllRoutes || !(parent instanceof TrainAnnouncerScreen)) {
			return;
		}
		final ObjectArrayList<RouteEntry> routeEntries = AllRoutesCache.get();
		if (routeEntries == null || routeEntries.isEmpty()) {
			// 服务端还没答复（或者那边关了这个功能）—— 退回上游原本的列表
			return;
		}

		final ObjectArrayList<DashboardListItem> items = new ObjectArrayList<>();
		routeEntries.forEach(routeEntry -> items.add(new DashboardListItem(routeEntry.id, routeEntry.name, routeEntry.color)));
		items.sort(Comparator.naturalOrder());
		allData = new ObjectImmutableList<>(items);
	}
}
