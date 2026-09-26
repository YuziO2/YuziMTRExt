package com.yuzi.mtrext.client;

import com.yuzi.mtrext.announcer.AllRoutesCache;
import com.yuzi.mtrext.announcer.RouteEntry;
import com.yuzi.mtrext.station.AnnouncerRule;
import org.mtr.core.data.Platform;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mapping.tool.TextCase;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.screen.DashboardListItem;
import org.mtr.mod.screen.DashboardListSelectorScreen;
import org.mtr.mod.screen.MTRScreenBase;
import org.mtr.mod.screen.PIDSConfigScreen;

import javax.annotation.Nullable;

/**
 * 编辑一条广播规则：站台 → 交路 → 音频 → 时机。
 *
 * <p>直接改传进来的 {@link AnnouncerRule} 对象，主界面拿的是同一个引用，所以不回传。
 *
 * <h2>站台从哪来</h2>
 *
 * <p>{@code InitClient.findStation(blockPos)} 找到方块所在的车站，
 * {@code station.savedRails} 就是它的所有站台（{@code Station extends AreaBase<Station, Platform>}）。
 * 方块没落在任何车站范围内时退回「找附近站台」。这套流程连同列表项的排版
 * 直接复用 MTR 的 {@code PIDSConfigScreen.getPlatformFilterButton} / {@code getPlatformsForList}。
 *
 * <h2>交路从哪来（以及为什么不用 simplifiedRoutes）</h2>
 *
 * <p>客户端手上带「站台 → 交路」映射的只有 {@code simplifiedRoutes}，但
 * {@code SimplifiedRoute.addToList} 第一条字节码就是 {@code getHidden() → ifne return}，
 * <b>隐藏交路根本不在里面</b>。所以优先用我们自己那条全量通道
 * （{@link com.yuzi.mtrext.announcer.PacketAllRoutes}，现在连站台一起带下来），
 * 它还没回来时才退回 {@code simplifiedRoutes} —— 退化成「选不到隐藏交路」，不会报错。
 */
public class AnnouncerRuleScreen extends MTRScreenBase implements IGui {

	private final BlockPos blockPos;
	private final AnnouncerRule rule;

	private final ButtonWidgetExtension buttonPlatform;
	private final ButtonWidgetExtension buttonRoute;
	private final ButtonWidgetExtension buttonSound;
	private final ButtonWidgetExtension buttonPreview;
	private final ButtonWidgetExtension buttonDetectDuration;
	private final ButtonWidgetExtension buttonBase;
	private final ButtonWidgetExtension buttonAlign;
	private final TextFieldWidgetExtension offsetField;
	private final TextFieldWidgetExtension durationField;

	private static final int VALUE_X = SQUARE_SIZE + PANEL_WIDTH + TEXT_PADDING;
	private static final int ROW = SQUARE_SIZE + TEXT_FIELD_PADDING;

	public AnnouncerRuleScreen(BlockPos blockPos, AnnouncerRule rule, ScreenExtension parent) {
		super(parent);
		this.blockPos = blockPos;
		this.rule = rule;

		// 这两个输入框要先建：下面几个按钮的 lambda 会引用 durationField，
		// 而 final 字段在赋值前被 lambda 捕获过不了编译器的「明确赋值」检查。
		offsetField = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, 5, TextCase.DEFAULT, "[^-\\d]", "0");
		durationField = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, 7, TextCase.DEFAULT, "\\D", "0");
		// 文本一律在 init2() 里、摆好位置之后才设 —— 构造时 width 还是 0，
		// TextFieldWidget.setText 结尾的 setCursorToEnd() 会按 width-8 = -8 算可见窗口，
		// 把 firstCharacterIndex 推到字符串末尾，渲染出来是空的（点一下才回来）。

		buttonPlatform = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.yuzimtr.announcer_pick_platform"), button -> pickPlatform());
		buttonRoute = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.yuzimtr.announcer_pick_route"), button -> pickRoute());
		buttonSound = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.yuzimtr.announcer_pick_sound"), button ->
				MinecraftClient.getInstance().openScreen(new Screen(new SoundPickerScreen(rule.soundId, soundId -> {
					rule.soundId = soundId;
					// 选完立刻量一次时长，「播完」那档才有东西可倒推。
					rule.durationMillis = OggDuration.millis(soundId);
					durationField.setText2(String.valueOf(rule.durationMillis));
				}, this))));
		buttonPreview = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.yuzimtr.announcer_preview"), button -> SoundPickerScreen.preview(rule.soundId));
		buttonDetectDuration = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.yuzimtr.announcer_detect_duration"), button -> {
			rule.durationMillis = OggDuration.millis(rule.soundId);
			durationField.setText2(String.valueOf(rule.durationMillis));
		});
		buttonBase = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, button -> {
			rule.onDeparture = !rule.onDeparture;
			updateToggleLabels();
		});
		buttonAlign = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, button -> {
			rule.alignEnd = !rule.alignEnd;
			updateToggleLabels();
		});
		updateToggleLabels();
	}

	@Override
	protected void init2() {
		super.init2();
		int y = SQUARE_SIZE;
		for (final ButtonWidgetExtension button : new ButtonWidgetExtension[]{buttonPlatform, buttonRoute, buttonSound}) {
			IDrawing.setPositionAndWidth(button, SQUARE_SIZE, y, PANEL_WIDTH);
			addChild(new ClickableWidget(button));
			y += ROW;
		}

		IDrawing.setPositionAndWidth(buttonPreview, SQUARE_SIZE, y, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING / 2);
		addChild(new ClickableWidget(buttonPreview));
		IDrawing.setPositionAndWidth(buttonDetectDuration, SQUARE_SIZE + PANEL_WIDTH / 2 + TEXT_FIELD_PADDING / 2, y, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING / 2);
		addChild(new ClickableWidget(buttonDetectDuration));
		IDrawing.setPositionAndWidth(durationField, VALUE_X, y, PANEL_WIDTH / 2);
		durationField.setText2(String.valueOf(rule.durationMillis));
		addChild(new ClickableWidget(durationField));
		y += ROW;

		IDrawing.setPositionAndWidth(buttonBase, SQUARE_SIZE, y, PANEL_WIDTH);
		addChild(new ClickableWidget(buttonBase));
		y += ROW;

		IDrawing.setPositionAndWidth(offsetField, SQUARE_SIZE, y, PANEL_WIDTH);
		offsetField.setText2(String.valueOf(rule.offsetSeconds));
		addChild(new ClickableWidget(offsetField));
		y += ROW;

		IDrawing.setPositionAndWidth(buttonAlign, SQUARE_SIZE, y, PANEL_WIDTH);
		addChild(new ClickableWidget(buttonAlign));
	}

	@Override
	public void tick2() {
		offsetField.tick2();
		durationField.tick2();
		rule.offsetSeconds = parseInt(offsetField.getText2(), 0);
		rule.durationMillis = Math.max(0, parseInt(durationField.getText2(), 0));
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		final int textOffset = (SQUARE_SIZE - TEXT_HEIGHT) / 2;
		int y = SQUARE_SIZE;
		drawValue(graphicsHolder, platformName(rule.platformId), y + textOffset);
		y += ROW;
		drawValue(graphicsHolder, routeName(rule.routeId), y + textOffset);
		y += ROW;
		drawValue(graphicsHolder, rule.soundId.isEmpty() ? "—" : rule.soundId, y + textOffset);
		y += ROW;
		graphicsHolder.drawText(TextHelper.translatable("gui.yuzimtr.announcer_duration"),
				VALUE_X + PANEL_WIDTH / 2 + TEXT_PADDING, y + textOffset, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		y += ROW;
		// 偏移那一行的标签画在输入框上方会挤，画在右边。
		drawValue(graphicsHolder, TextHelper.translatable("gui.yuzimtr.announcer_offset_hint").getString(), y + ROW + textOffset);
		y += ROW * 3;
		graphicsHolder.drawText(summary(), SQUARE_SIZE, y + TEXT_PADDING, ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	private void drawValue(GraphicsHolder graphicsHolder, String text, int y) {
		graphicsHolder.drawText(text, VALUE_X, y, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
	}

	private void updateToggleLabels() {
		buttonBase.setMessage2(new Text(TextHelper.translatable(rule.onDeparture
				? "gui.yuzimtr.announcer_base_departure" : "gui.yuzimtr.announcer_base_arrival").data));
		buttonAlign.setMessage2(new Text(TextHelper.translatable(rule.alignEnd
				? "gui.yuzimtr.announcer_align_end" : "gui.yuzimtr.announcer_align_start").data));
	}

	/**
	 * 把这条规则换算成一句人话，顺带把「播完」倒推出来的<b>实际起点</b>显示出来。
	 *
	 * <p>这一行是有意加的：三语报站动辄十几秒，「发车前 3 秒播完」的 17 秒音频，
	 * 起点会落到车刚进站甚至还没进站的时候。不显示出来配的人不会发现。
	 */
	private String summary() {
		final long fireOffsetMillis = -rule.fireAtMillis(0);
		return TextHelper.translatable("gui.yuzimtr.announcer_summary",
				baseText(rule), beforeAfter(fireOffsetMillis),
				String.format("%.1f", Math.abs(fireOffsetMillis) / 1000.0)
		).getString();
	}

	private static String baseText(AnnouncerRule rule) {
		return TextHelper.translatable(rule.onDeparture
				? "gui.yuzimtr.announcer_base_departure" : "gui.yuzimtr.announcer_base_arrival").getString();
	}

	/** 正数是「基准之前」，负数是「基准之后」。 */
	private static String beforeAfter(long value) {
		return TextHelper.translatable(value >= 0
				? "gui.yuzimtr.announcer_before" : "gui.yuzimtr.announcer_after").getString();
	}

	// ------------------------------------------------------------------ 选择器

	private void pickPlatform() {
		final ObjectArrayList<Platform> platforms = StationRouteIndex.platformsNear(blockPos);

		final LongAVLTreeSet selected = new LongAVLTreeSet();
		if (rule.platformId != 0) {
			selected.add(rule.platformId);
		}
		openSingleSelect(PIDSConfigScreen.getPlatformsForList(platforms), selected, 0, id -> {
			// 换了站台而原来指定的交路不停这里 —— 置为「未选择」等人补，
			// 而不是像第一版那样重置成 0：0 是「全部交路」，会把一条只对某条交路响的规则
			// 悄悄变成对所有车都响。站台被清空（id == 0）时不动交路：规则反正不生效了，
			// 留着交路，重新选回站台时不用再选一遍。
			if (id != 0 && id != rule.platformId && rule.hasSpecificRoute() && !StationRouteIndex.routeStopsAt(rule.routeId, id)) {
				rule.routeId = AnnouncerRule.ROUTE_UNSET;
			}
			rule.platformId = id;
		});
	}

	private void pickRoute() {
		final ObjectArrayList<DashboardListItem> items = new ObjectArrayList<>();
		// id 0 是「不限交路」的伪条目：本站台任何一趟车都触发。
		items.add(new DashboardListItem(0, TextHelper.translatable("gui.yuzimtr.announcer_any_route").getString(), 0));

		// 和粘贴匹配用同一个数据源：以全量缓存为准，缓存里没有的退回 simplifiedRoutes。
		for (final RouteEntry routeEntry : StationRouteIndex.routeEntriesAt(rule.platformId)) {
			items.add(new DashboardListItem(routeEntry.id, routeEntry.name, routeEntry.color));
		}

		final LongAVLTreeSet selected = new LongAVLTreeSet();
		if (rule.routeId != AnnouncerRule.ROUTE_UNSET) {
			selected.add(rule.routeId);
		}
		// 什么都不选 → 「未选择」，不是 0。0 是「全部交路」，把选择清空当成
		// 「对所有车都响」正是 ROUTE_UNSET 要避免的事。要全部交路就选那个伪条目。
		openSingleSelect(new ObjectImmutableList<>(items), selected, AnnouncerRule.ROUTE_UNSET, id -> rule.routeId = id);
	}

	/** @param emptyValue 用户把选择清空时回传什么 */
	private void openSingleSelect(ObjectImmutableList<DashboardListItem> items, LongAVLTreeSet selected, long emptyValue, java.util.function.LongConsumer onPicked) {
		MinecraftClient.getInstance().openScreen(new Screen(new DashboardListSelectorScreen(
				() -> onPicked.accept(selected.isEmpty() ? emptyValue : selected.firstLong()),
				items, selected, true, false, this
		)));
	}

	// ------------------------------------------------------------------ 显示名

	static String describe(AnnouncerRule rule) {
		final StringBuilder stringBuilder = new StringBuilder();
		// 没配全的规则服务端直接跳过。粘贴出来的规则可能落到这个状态，得一眼看得出来。
		if (!rule.isComplete()) {
			stringBuilder.append(TextHelper.translatable("gui.yuzimtr.announcer_incomplete").getString());
		}
		stringBuilder.append(platformName(rule.platformId));
		stringBuilder.append(" · ").append(routeName(rule.routeId));
		stringBuilder.append(" · ").append(describeTiming(rule));
		return stringBuilder.toString();
	}

	/** 只有时机和音频，不含站台和交路 —— 剪贴板提示用，因为那两项粘贴时会重新对。 */
	static String describeTiming(AnnouncerRule rule) {
		final StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append(baseText(rule)).append(beforeAfter(rule.offsetSeconds))
				.append(Math.abs(rule.offsetSeconds)).append("s ")
				.append(TextHelper.translatable(rule.alignEnd
						? "gui.yuzimtr.announcer_align_end" : "gui.yuzimtr.announcer_align_start").getString());
		if (!rule.soundId.isEmpty()) {
			final int slash = rule.soundId.lastIndexOf('/');
			stringBuilder.append(" · ").append(slash < 0 ? rule.soundId : rule.soundId.substring(slash + 1));
		}
		return stringBuilder.toString();
	}

	/** 列表左边那个色块：指定交路就用交路色，否则 0（黑，和 MTR 列表里「没有颜色」一样）。 */
	static int routeColor(long routeId) {
		if (routeId == 0 || routeId == AnnouncerRule.ROUTE_UNSET) {
			return 0;
		}
		final ObjectArrayList<RouteEntry> allRoutes = AllRoutesCache.get();
		if (allRoutes != null) {
			for (final RouteEntry routeEntry : allRoutes) {
				if (routeEntry.id == routeId) {
					return routeEntry.color;
				}
			}
		}
		final SimplifiedRoute simplifiedRoute = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
		return simplifiedRoute == null ? 0 : simplifiedRoute.getColor();
	}

	private static String platformName(long platformId) {
		if (platformId == 0) {
			return "—";
		}
		final Platform platform = MinecraftClientData.getInstance().platformIdMap.get(platformId);
		return platform == null
				? String.valueOf(platformId)
				: IGui.formatStationName(platform.getStationName()) + " " + platform.getName();
	}

	private static String routeName(long routeId) {
		if (routeId == 0) {
			return TextHelper.translatable("gui.yuzimtr.announcer_any_route").getString();
		}
		if (routeId == AnnouncerRule.ROUTE_UNSET) {
			return TextHelper.translatable("gui.yuzimtr.announcer_route_unset").getString();
		}
		final ObjectArrayList<RouteEntry> allRoutes = AllRoutesCache.get();
		if (allRoutes != null) {
			for (final RouteEntry routeEntry : allRoutes) {
				if (routeEntry.id == routeId) {
					return IGui.formatStationName(routeEntry.name);
				}
			}
		}
		final SimplifiedRoute simplifiedRoute = MinecraftClientData.getInstance().simplifiedRouteIdMap.get(routeId);
		return simplifiedRoute == null ? String.valueOf(routeId) : IGui.formatStationName(simplifiedRoute.getName());
	}

	private static int parseInt(@Nullable String text, int fallback) {
		try {
			return Integer.parseInt(text);
		} catch (Exception e) {
			return fallback;
		}
	}
}
