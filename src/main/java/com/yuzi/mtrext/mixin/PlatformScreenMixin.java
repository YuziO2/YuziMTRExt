package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.PlatformDwellOverrides;
import com.yuzi.mtrext.client.PlatformDwellRow;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.TransportMode;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.ScreenAbstractMapping;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.IGui;
import org.mtr.mod.screen.PlatformScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 把分交路停站时长的 UI 加进站台仪表板的「途径本站台之路线」列表。
 *
 * <p>上游那个列表（PlatformScreen.java:48-54）只画彩色交路名，10px 一行。
 * 这里把每行撑到 22px，右边挂一个秒数输入框 + 一个重置按钮：
 * <pre>
 * 途径本站台之路线   19号线特快 北            [    ] [Reset]   ← 空 = 跟随总值，灰字显示继承来的秒数
 *                    18号线普通 北            [30.0] [Reset]   ← 自定义
 * </pre>
 *
 * <p>不需要任何自定义网络包：PlatformScreen.onClose2 本来就整个 Platform 发回服务端
 * （{@code UpdateDataRequest.addPlatform}），而覆盖表就挂在 Platform 上
 * （见 {@link PlatformDwellOverrides}），跟着同一个包走。
 *
 * <p>取消上游画名字的循环用的是 {@code @Redirect} 那次 {@code routes.iterator()} ——
 * render 里只有这一处 iterator()（字节码核对过），比去挑三个 drawText 里的哪一个稳。
 * 连续移动的交通方式（缆车之类）上游本来就没有停站时长，那时这个 redirect
 * 原样返回真迭代器，界面就是上游的样子。
 *
 * <p>UI **不看** {@code perRouteDwellTime} 开关。那个开关管的是服务端生成路径时
 * 要不要查覆盖值，而客户端和服务端是两份 config 文件，可以不一致 ——
 * 一开始就是因为客户端那份还留着旧默认值 false，界面整个不出现。
 * 存取覆盖值（PlatformDwellMixin）本来也是无条件的，界面跟着它就行。
 */
@Mixin(value = PlatformScreen.class, remap = false)
public abstract class PlatformScreenMixin implements IGui {

	@Shadow
	@Final
	private ObjectOpenHashSet<Route> routes;

	@Unique
	private Platform yuzimtr$platform;
	@Unique
	private List<PlatformDwellRow> yuzimtr$rows;
	@Unique
	private int yuzimtr$visibleRows;
	@Unique
	private boolean yuzimtr$active;


	@Inject(method = "<init>(Lorg/mtr/core/data/Platform;Lorg/mtr/core/data/TransportMode;Lorg/mtr/mapping/mapper/ScreenExtension;)V", at = @At("TAIL"))
	private void yuzimtr$buildRows(Platform platform, TransportMode transportMode, ScreenExtension previousScreenExtension, CallbackInfo ci) {
		yuzimtr$platform = platform;
		yuzimtr$active = !transportMode.continuousMovement;
		yuzimtr$rows = new ArrayList<>();
		if (!yuzimtr$active) {
			return;
		}
		final PlatformDwellOverrides overrides = (PlatformDwellOverrides) (Object) platform;
		for (final Route route : routes) {
			yuzimtr$rows.add(new PlatformDwellRow(route, overrides.yuzimtr$getDwellOverride(route.getId())));
		}
	}

	@Inject(method = "init2", at = @At("TAIL"))
	private void yuzimtr$layoutRows(CallbackInfo ci) {
		if (!yuzimtr$active) {
			return;
		}
		final ScreenAbstractMapping screen = (ScreenAbstractMapping) (Object) this;
		final int screenWidth = screen.getWidthMapped();
		final int screenHeight = screen.getHeightMapped();
		final int listTop = PlatformDwellRow.LIST_TOP;
		final int buttonX = screenWidth - SQUARE_SIZE - PlatformDwellRow.BUTTON_WIDTH;
		final int fieldX = buttonX - TEXT_FIELD_PADDING - PlatformDwellRow.FIELD_WIDTH;

		yuzimtr$visibleRows = Math.max(0, Math.min(yuzimtr$rows.size(), (screenHeight - SQUARE_SIZE - listTop) / PlatformDwellRow.ROW_HEIGHT));
		for (int i = 0; i < yuzimtr$visibleRows; i++) {
			final PlatformDwellRow row = yuzimtr$rows.get(i);
			final int rowTop = listTop + i * PlatformDwellRow.ROW_HEIGHT;
			IDrawing.setPositionAndWidth(row.field, fieldX + TEXT_FIELD_PADDING / 2, rowTop + TEXT_FIELD_PADDING / 2, PlatformDwellRow.FIELD_WIDTH - TEXT_FIELD_PADDING);
			IDrawing.setPositionAndWidth(row.resetButton, buttonX, rowTop, PlatformDwellRow.BUTTON_WIDTH);
			((ScreenExtension) (Object) this).addChild(new ClickableWidget(row.field));
			((ScreenExtension) (Object) this).addChild(new ClickableWidget(row.resetButton));
		}
	}

	/** 功能开着时让上游那个 10px 行距的画名字循环空转，名字由下面的 TAIL 自己画。 */
	@Redirect(
			method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;IIF)V",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectOpenHashSet;iterator()Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectIterator;"
			)
	)
	private ObjectIterator<Route> yuzimtr$skipVanillaRouteList(ObjectOpenHashSet<Route> instance) {
		return yuzimtr$active ? new ObjectOpenHashSet<Route>().iterator() : instance.iterator();
	}

	@Inject(method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;IIF)V", at = @At("TAIL"))
	private void yuzimtr$drawRows(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!yuzimtr$active) {
			return;
		}
		final SavedRailScreenAccessor base = (SavedRailScreenAccessor) (Object) this;
		final int screenWidth = ((ScreenAbstractMapping) (Object) this).getWidthMapped();
		final int listTop = PlatformDwellRow.LIST_TOP;
		final int nameX = SQUARE_SIZE + base.yuzimtr$textWidth();
		final int buttonX = screenWidth - SQUARE_SIZE - PlatformDwellRow.BUTTON_WIDTH;
		final int fieldX = buttonX - TEXT_FIELD_PADDING - PlatformDwellRow.FIELD_WIDTH;
		final String inheritedText = PlatformDwellRow.formatSeconds(yuzimtr$liveDefaultMillis(base));

		for (int i = 0; i < yuzimtr$visibleRows; i++) {
			final PlatformDwellRow row = yuzimtr$rows.get(i);
			final int textY = listTop + i * PlatformDwellRow.ROW_HEIGHT + (SQUARE_SIZE - TEXT_HEIGHT) / 2;
			graphicsHolder.drawText(
					yuzimtr$clip(IGui.formatStationName(row.route.getName()), fieldX - nameX - TEXT_PADDING),
					nameX, textY, ARGB_BLACK + row.route.getColor(), false, GraphicsHolder.getDefaultLight()
			);
			if (row.isInheriting()) {
				graphicsHolder.drawText(inheritedText, fieldX + TEXT_FIELD_PADDING, textY, ARGB_GRAY, false, GraphicsHolder.getDefaultLight());
			}
		}
		if (yuzimtr$visibleRows < yuzimtr$rows.size()) {
			graphicsHolder.drawText(
					String.format("… +%d", yuzimtr$rows.size() - yuzimtr$visibleRows),
					nameX, listTop + yuzimtr$visibleRows * PlatformDwellRow.ROW_HEIGHT + (SQUARE_SIZE - TEXT_HEIGHT) / 2,
					ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight()
			);
		}
	}

	/**
	 * 提交到 Platform 上，然后让上游的 onClose2 把整个 Platform 发回服务端。
	 * 注在 HEAD，所以一定跑在那个 sendPacketToServer 之前。
	 */
	@Inject(method = "onClose2", at = @At("HEAD"))
	private void yuzimtr$commitRows(CallbackInfo ci) {
		if (!yuzimtr$active) {
			return;
		}
		final PlatformDwellOverrides overrides = (PlatformDwellOverrides) (Object) yuzimtr$platform;
		// 注意这里遍历全部行而不是只遍历可见行 —— 屏幕塞不下的那些行控件没上屏，
		// 但它们的输入框里存着原有的值，跳过就等于把它们删了。
		for (final PlatformDwellRow row : yuzimtr$rows) {
			final Long millis = row.parseMillis();
			if (millis == null) {
				overrides.yuzimtr$clearDwellOverride(row.route.getId());
			} else {
				overrides.yuzimtr$setDwellOverride(row.route.getId(), millis);
			}
		}
	}

	/** 灰字显示的继承值跟着上面两个滑块实时走，算法和上游 onClose2 一致。 */
	@Unique
	private static long yuzimtr$liveDefaultMillis(SavedRailScreenAccessor base) {
		final int minutes = base.yuzimtr$sliderDwellTimeMin().getIntValue();
		final float seconds = base.yuzimtr$sliderDwellTimeSec().getIntValue() / 2F;
		return (long) ((seconds + (long) minutes * 60) * 1000);
	}

	@Unique
	private static String yuzimtr$clip(String text, int maxWidth) {
		if (maxWidth <= 0 || GraphicsHolder.getTextWidth(text) <= maxWidth) {
			return text;
		}
		String result = text;
		while (!result.isEmpty() && GraphicsHolder.getTextWidth(result + "…") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result + "…";
	}
}
