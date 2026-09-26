package com.yuzi.mtrext.client;

import com.yuzi.mtrext.YuziMtrExt;
import com.yuzi.mtrext.announcer.PacketAllRoutes;
import com.yuzi.mtrext.station.AnnouncerRule;
import com.yuzi.mtrext.station.PacketUpdateStationAnnouncer;
import com.yuzi.mtrext.station.StationAnnouncerBlockEntity;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.CheckboxWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mapping.mapper.TexturedButtonWidgetExtension;
import org.mtr.mapping.tool.TextCase;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.IGui;
import org.mtr.mod.screen.DashboardList;
import org.mtr.mod.screen.DashboardListItem;
import org.mtr.mod.screen.MTRScreenBase;
import org.mtr.mod.screen.TexturedButtonWidgetHelper;

import java.util.List;

/**
 * 站台广播器的主界面：喇叭本身的属性 + 一张规则表。
 *
 * <p>一块一条规则的话，一个站台要摆五六个方块（到站前、到站、发车前 ×每条交路），
 * 站台和交路还得重复选很多遍，所以做成一块多条。这里的「新增规则」就是用户描述的那条
 * 「站台 → 交路 → 音频 → 时机」流程，落在 {@link AnnouncerRuleScreen} 里。
 *
 * <p>界面直接改 {@link AnnouncerRule} 对象本身，子界面拿到的是同一个引用，所以返回时
 * 不需要回传；列表每帧重建（规则个数是个位数，重建比做脏标记便宜）。
 *
 * <p>关界面时整份发给服务端，见 {@link PacketUpdateStationAnnouncer}。
 */
public class StationAnnouncerScreen extends MTRScreenBase implements IGui {

	private final BlockPos blockPos;
	private final ObjectArrayList<AnnouncerRule> rules = new ObjectArrayList<>();

	private final TextFieldWidgetExtension volumeField;
	private final CheckboxWidgetExtension realtimeOnlyCheckbox;
	private final CheckboxWidgetExtension limitRangeCheckbox;
	private final TextFieldWidgetExtension[] cornerFields = new TextFieldWidgetExtension[6];
	private final DashboardList rulesList;
	private final ButtonWidgetExtension buttonAddRule;

	/**
	 * 行上的「复制」按钮。
	 *
	 * <p>{@code DashboardList} 的行按钮是写死的七个（查找/画区/编辑/上下/加/删），没有复制，
	 * 图标也是构造时定死的。所以自己做一个，摆到它<b>本来会放 buttonFind 的那个位置</b>：
	 * 只开编辑 + 删除时，那个位置是 {@code x + width - SQUARE_SIZE * 3}，正好在铅笔左边。
	 * 悬停的是哪一行照抄 {@code DashboardList.mouseMoved} 的算法，点下去时的下标
	 * 用它公开的 {@code getHoverItemIndex()} 取 —— 那个方法已经处理好了翻页和搜索过滤。
	 */
	private final TexturedButtonWidgetExtension buttonCopy;
	private final ButtonWidgetExtension buttonPaste;
	private int yRulesHeader;

	/** {@code DashboardList.TOP_OFFSET} 是 private 的，照抄：列表顶上那一行是翻页 + 搜索框。 */
	private static final int LIST_TOP_OFFSET = SQUARE_SIZE + TEXT_FIELD_PADDING;

	private boolean realtimeOnly;
	private boolean limitRange;

	/**
	 * 输入框里的原始文本。
	 *
	 * <p><b>不能在构造函数里 setText2</b>：{@code TextFieldWidget.setText} 末尾会
	 * {@code setCursorToEnd()}，而它拿 {@code getInnerWidth()}（= {@code width - 8}）算可见窗口。
	 * 构造时 width 还是 0 → innerWidth 是 -8 → {@code firstCharacterIndex} 被推到字符串末尾，
	 * 渲染出来是<b>空的</b>，点一下才重算回来。MTR 自己的界面全是在 {@code init2()} 里、
	 * {@code IDrawing.setPositionAndWidth} 之<b>后</b>才 setText2，就是这个原因。
	 *
	 * <p>而 {@code init2()} 在改变窗口大小时会再跑一遍，所以文本要自己留一份，
	 * 不能每次都从方块实体重读 —— 否则一改窗口大小就把编到一半的值冲掉。
	 */
	private String volumeText;
	private final String[] cornerTexts = new String[6];

	private static final int COORD_FIELD_WIDTH = 46;

	/** 列表搜索框的内容存在界面外面，这样来回进出子界面不会被清掉。 */
	private static String rulesSearch = "";

	public StationAnnouncerScreen(BlockPos blockPos, StationAnnouncerBlockEntity blockEntity) {
		super();
		this.blockPos = blockPos;

		blockEntity.getRules().forEach(rule -> rules.add(new AnnouncerRule(rule)));
		realtimeOnly = blockEntity.getRealtimeOnly();
		limitRange = blockEntity.getLimitRange();

		volumeField = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, 5, TextCase.DEFAULT, "[^\\d.]", "2");
		volumeText = trimFloat(blockEntity.getVolume());

		realtimeOnlyCheckbox = new CheckboxWidgetExtension(0, 0, 0, SQUARE_SIZE, true, checked -> realtimeOnly = checked);
		realtimeOnlyCheckbox.setMessage2(new Text(TextHelper.translatable("gui.yuzimtr.announcer_realtime_only").data));

		limitRangeCheckbox = new CheckboxWidgetExtension(0, 0, 0, SQUARE_SIZE, true, checked -> limitRange = checked);
		limitRangeCheckbox.setMessage2(new Text(TextHelper.translatable("gui.yuzimtr.announcer_limit_range").data));

		final BlockPos corner1 = blockEntity.getCorner1();
		final BlockPos corner2 = blockEntity.getCorner2();
		final int[] coords = {corner1.getX(), corner1.getY(), corner1.getZ(), corner2.getX(), corner2.getY(), corner2.getZ()};
		for (int i = 0; i < cornerFields.length; i++) {
			// 允许负号：坐标可以在 0 以西/以北，而且 Y 在 1.20 里能到 -64。
			cornerFields[i] = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, 7, TextCase.DEFAULT, "[^-\\d]", "0");
			cornerTexts[i] = String.valueOf(coords[i]);
		}

		rulesList = new DashboardList(
				null, null,
				(dashboardListItem, index) -> editRule(index),
				null, null,
				(dashboardListItem, index) -> rules.remove(index),
				null,
				() -> rulesSearch, text -> rulesSearch = text
		);

		buttonAddRule = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.yuzimtr.announcer_add_rule"), button -> {
			rules.add(new AnnouncerRule());
			editRule(rules.size() - 1);
		});

		buttonCopy = TexturedButtonWidgetHelper.create(0, 0, 0, SQUARE_SIZE,
				new Identifier(YuziMtrExt.MOD_ID, "textures/gui/sprites/icon_copy.png"),
				new Identifier(YuziMtrExt.MOD_ID, "textures/gui/sprites/icon_copy_highlighted.png"),
				button -> copyHoveredRule());
		buttonCopy.visible = false;

		buttonPaste = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TextHelper.translatable("gui.yuzimtr.announcer_paste"), button -> pasteRule());

		// 交路选择器要用全量交路（含隐藏），取数是异步的，趁打开主界面就发出去 ——
		// 用户还要点「新增规则」再点「选择交路」，中间够一轮往返了。
		// 万一没回来，AnnouncerRuleScreen 会退回 simplifiedRoutes（不含隐藏交路）。
		YuziMtrExtClient.REGISTRY_CLIENT.sendPacketToServer(new PacketAllRoutes());
	}

	@Override
	protected void init2() {
		super.init2();

		final int yVolume = SQUARE_SIZE + TEXT_HEIGHT + TEXT_PADDING;
		IDrawing.setPositionAndWidth(volumeField, SQUARE_SIZE + TEXT_FIELD_PADDING / 2, yVolume + TEXT_FIELD_PADDING / 2, PANEL_WIDTH / 2 - TEXT_FIELD_PADDING);
		volumeField.setText2(volumeText); // 必须在 setPositionAndWidth 之后，见 volumeText 的注释
		addChild(new ClickableWidget(volumeField));

		IDrawing.setPositionAndWidth(realtimeOnlyCheckbox, SQUARE_SIZE + PANEL_WIDTH / 2 + TEXT_PADDING, yVolume, PANEL_WIDTH);
		realtimeOnlyCheckbox.setChecked(realtimeOnly);
		addChild(new ClickableWidget(realtimeOnlyCheckbox));

		final int yLimitRange = yVolume + SQUARE_SIZE + TEXT_FIELD_PADDING;
		IDrawing.setPositionAndWidth(limitRangeCheckbox, SQUARE_SIZE, yLimitRange, PANEL_WIDTH);
		limitRangeCheckbox.setChecked(limitRange);
		addChild(new ClickableWidget(limitRangeCheckbox));

		final int yCorners = yLimitRange + SQUARE_SIZE + TEXT_HEIGHT + TEXT_PADDING;
		for (int i = 0; i < cornerFields.length; i++) {
			// 第 4 个起是第二个角点，之间空一格好认。
			final int x = SQUARE_SIZE + i * (COORD_FIELD_WIDTH + TEXT_FIELD_PADDING) + (i >= 3 ? SQUARE_SIZE : 0);
			IDrawing.setPositionAndWidth(cornerFields[i], x, yCorners, COORD_FIELD_WIDTH);
			cornerFields[i].setText2(cornerTexts[i]); // 同上，顺序不能反
			addChild(new ClickableWidget(cornerFields[i]));
		}

		// 「广播规则（N 条）」这一行要放粘贴按钮，所以给它一整行高度。
		yRulesHeader = yCorners + SQUARE_SIZE + TEXT_FIELD_PADDING;
		IDrawing.setPositionAndWidth(buttonPaste, pasteButtonX(), yRulesHeader, PANEL_WIDTH / 2);
		addChild(new ClickableWidget(buttonPaste));

		rulesList.x = SQUARE_SIZE;
		rulesList.y = yRulesHeader + SQUARE_SIZE;
		rulesList.width = width - SQUARE_SIZE * 2;
		rulesList.height = height - rulesList.y - SQUARE_SIZE * 2 - TEXT_FIELD_PADDING;
		rulesList.init(this::addChild);
		// 放在列表自己的按钮之后加，和它们同一层。
		addChild(new ClickableWidget(buttonCopy));

		IDrawing.setPositionAndWidth(buttonAddRule, SQUARE_SIZE, height - SQUARE_SIZE - TEXT_FIELD_PADDING / 2, PANEL_WIDTH);
		addChild(new ClickableWidget(buttonAddRule));
	}

	@Override
	public void tick2() {
		volumeField.tick2();
		volumeText = volumeField.getText2();
		for (int i = 0; i < cornerFields.length; i++) {
			cornerFields[i].tick2();
			cornerTexts[i] = cornerFields[i].getText2();
		}
		// 规则可能刚在子界面里改过，每帧重建最省心 —— 个位数的规模，不值得做脏标记。
		final ObjectArrayList<DashboardListItem> items = new ObjectArrayList<>();
		for (int i = 0; i < rules.size(); i++) {
			final AnnouncerRule rule = rules.get(i);
			items.add(new DashboardListItem(i, AnnouncerRuleScreen.describe(rule), AnnouncerRuleScreen.routeColor(rule.routeId)));
		}
		buttonPaste.active = AnnouncerRuleClipboard.peek() != null;
		// 标签里带着规则条数，宽度会变，按钮跟着挪。
		buttonPaste.setX2(pasteButtonX());
		rulesList.setData(items, false, false, true, false, false, true);
		rulesList.tick();
		// 坐标输入在没勾「限制范围」时藏起来，省得以为它们一直生效。
		for (final TextFieldWidgetExtension cornerField : cornerFields) {
			cornerField.visible = limitRange;
		}
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		final int yVolume = SQUARE_SIZE;
		graphicsHolder.drawText(TextHelper.translatable("gui.yuzimtr.announcer_volume"),
				SQUARE_SIZE, yVolume, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		if (limitRange) {
			final int yCornersLabel = SQUARE_SIZE + TEXT_HEIGHT + TEXT_PADDING + SQUARE_SIZE * 2 + TEXT_FIELD_PADDING;
			// 把框的实际尺寸直接写出来：只看六个坐标很难意识到自己框了多大一块，
			// 框小了的表现是「完全没声音」，很难往这上面想。
			graphicsHolder.drawText(TextHelper.translatable("gui.yuzimtr.announcer_corners",
							Math.abs(parseCoord(3) - parseCoord(0)) + 1,
							Math.abs(parseCoord(4) - parseCoord(1)) + 1,
							Math.abs(parseCoord(5) - parseCoord(2)) + 1),
					SQUARE_SIZE, yCornersLabel, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		}
		final int textOffset = (SQUARE_SIZE - TEXT_HEIGHT) / 2;
		graphicsHolder.drawText(rulesLabel(), SQUARE_SIZE, yRulesHeader + textOffset, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		// 粘贴按钮右边写出剪贴板里是什么。只写时机和音频 —— 站台和交路粘贴时会按本站重新对。
		final AnnouncerRule copied = AnnouncerRuleClipboard.peek();
		graphicsHolder.drawText(copied == null
						? TextHelper.translatable("gui.yuzimtr.announcer_clipboard_empty").getString()
						: TextHelper.translatable("gui.yuzimtr.announcer_clipboard", AnnouncerRuleScreen.describeTiming(copied)).getString(),
				pasteButtonX() + PANEL_WIDTH / 2 + TEXT_PADDING, yRulesHeader + textOffset, ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
		rulesList.render(graphicsHolder);
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	@Override
	public void mouseMoved2(double mouseX, double mouseY) {
		rulesList.mouseMoved(mouseX, mouseY);
		// 照抄 DashboardList.mouseMoved 的悬停判定，让复制按钮和它自己的行按钮同进同出。
		final int top = rulesList.y + LIST_TOP_OFFSET;
		final int rows = (rulesList.height - LIST_TOP_OFFSET) / SQUARE_SIZE;
		final boolean overRows = mouseX >= rulesList.x && mouseX < rulesList.x + rulesList.width
				&& mouseY >= top && mouseY < top + SQUARE_SIZE * rows;
		if (overRows && rulesList.getHoverItemIndex() >= 0) {
			final int row = ((int) mouseY - top) / SQUARE_SIZE;
			IDrawing.setPositionAndWidth(buttonCopy, rulesList.x + rulesList.width - SQUARE_SIZE * 3, top + row * SQUARE_SIZE, SQUARE_SIZE);
			buttonCopy.visible = true;
		} else {
			buttonCopy.visible = false;
		}
	}

	@Override
	public boolean mouseScrolled2(double mouseX, double mouseY, double amount) {
		rulesList.mouseScrolled(mouseX, mouseY, amount);
		return super.mouseScrolled2(mouseX, mouseY, amount);
	}

	@Override
	public void onClose2() {
		YuziMtrExtClient.REGISTRY_CLIENT.sendPacketToServer(new PacketUpdateStationAnnouncer(
				blockPos, rules, parseVolume(), realtimeOnly, limitRange,
				new BlockPos(parseCoord(0), parseCoord(1), parseCoord(2)),
				new BlockPos(parseCoord(3), parseCoord(4), parseCoord(5))
		));
		super.onClose2();
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	List<AnnouncerRule> getRules() {
		return rules;
	}

	private void copyHoveredRule() {
		// getHoverItemIndex 返回的是 dataSorted 的下标；列表是按 rules 的顺序建的、
		// setData(ObjectArrayList) 又不重排，所以它就是 rules 的下标（搜索过滤时也对）。
		final int index = rulesList.getHoverItemIndex();
		if (index >= 0 && index < rules.size()) {
			AnnouncerRuleClipboard.copy(rules.get(index));
		}
	}

	private void pasteRule() {
		final AnnouncerRule pasted = AnnouncerRuleClipboard.pasteFor(blockPos);
		if (pasted != null) {
			rules.add(pasted);
		}
	}

	private String rulesLabel() {
		return TextHelper.translatable("gui.yuzimtr.announcer_rules", rules.size()).getString();
	}

	private int pasteButtonX() {
		return SQUARE_SIZE + GraphicsHolder.getTextWidth(rulesLabel()) + TEXT_PADDING;
	}

	private void editRule(int index) {
		if (index >= 0 && index < rules.size()) {
			MinecraftClient.getInstance().openScreen(new Screen(new AnnouncerRuleScreen(blockPos, rules.get(index), this)));
		}
	}

	private float parseVolume() {
		try {
			// 0 会让所有人都听不见，等于把方块变成哑巴；夹在一个说得通的区间里。
			// 上限 8 → 半径 128 格，再大也超过实体加载距离了。
			return Math.max(0.1F, Math.min(8, Float.parseFloat(volumeField.getText2())));
		} catch (Exception e) {
			return StationAnnouncerBlockEntity.DEFAULT_VOLUME;
		}
	}

	private int parseCoord(int index) {
		try {
			return Integer.parseInt(cornerFields[index].getText2());
		} catch (Exception e) {
			return 0;
		}
	}

	/** 2.0 显示成 "2"，2.5 保持 "2.5" —— 免得输入框里一上来就是个刺眼的小数点。 */
	private static String trimFloat(float value) {
		return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
	}
}
