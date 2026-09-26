package com.yuzi.mtrext.client;

import com.yuzi.mtrext.AvatarUrlRewriter;
import com.yuzi.mtrext.Config;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.holder.ScreenAbstractMapping;
import org.mtr.mapping.mapper.CheckboxWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mapping.tool.TextCase;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.IGui;
import org.mtr.mod.screen.MTRScreenBase;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 「模组菜单」里的配置界面，写法照抄 MTR 自己的 {@code ConfigScreen}
 * （{@code MTRScreenBase} + {@code IGui}，控件在构造时建好、{@code init2} 里摆位）。
 *
 * <p>界面直接改 {@link Config} 那个单例的字段，所以 mixin 下一次读
 * {@code Config.get().xxx} 立刻是新值；关闭时 {@link Config#save()} 落盘。
 *
 * <p><b>坑一：CheckboxWidgetExtension 的两个 boolean 参数在 mapping 层被写反了。</b>
 * 它最终调的是 {@code CheckboxWidgetAbstractMapping.<init>(IIII Text 
 * checked showMessage)}，而字节码里压的是 {@code iconst_0} 然后 {@code iload 6} ——
 * 也就是 {@code checked} 被硬编码成 false，调用者传的值落到了 {@code showMessage} 上。
 * 后果是「标签只在勾上时才显示」。所以这里一律传空文本 + true，
 * <b>标签自己画</b>，勾选状态一律用 {@code setChecked()} 显式设。
 * MTR 自己的界面全是这个用法（传 true 再手画标签），大概也是踩过。
 *
 * <p><b>坑二：不能假设窗口够高。</b> 行按可用高度自动流到多列，
 * 列宽有上限，免得在宽屏上摊成一条。
 *
 * <p><b>服务端那一组在这里改是没用的。</b> 客户端和服务端是两份独立的
 * {@code config/yuzimtr.json}，路径生成跑在服务端。这里仍然列出来，是因为之前吃过一次亏 ——
 * 客户端那份留着旧默认值 false，站台仪表板里那一列输入框整个不出现，查了半天。
 *
 * <p>{@code consistSizes} / {@code gangwayOverrides} / {@code defaultConsistSizes}
 * 是「正则 → 结构」的映射，做图形界面不划算，继续只在 json 里改。
 */
public class YuziConfigScreen extends MTRScreenBase implements IGui {

	private final Config config = Config.get();
	private final List<Row> rows = new ArrayList<>();
	private final TextFieldWidgetExtension textFieldFloorInflateX;
	private final TextFieldWidgetExtension textFieldDisableGangwayPattern;
	private final TextFieldWidgetExtension textFieldPlayerAvatarUrl;

	private static final int ROW_HEIGHT = SQUARE_SIZE;
	private static final int COLUMN_WIDTH_MAX = 210;
	private static final int FIELD_LABEL_WIDTH = 84;
	private static final int TOP = SQUARE_SIZE + TEXT_HEIGHT;
	private static final Identifier LOGO = new Identifier("yuzimtr:textures/gui/logo.png");
	private static final int LOGO_SIZE = 20;

	public YuziConfigScreen(@Nullable Screen previousScreen) {
		super(previousScreen);

		textFieldFloorInflateX = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, 8, TextCase.DEFAULT, "[^\\d.\\-]", null);
		textFieldFloorInflateX.setText2(String.valueOf(config.floorInflateX));
		textFieldDisableGangwayPattern = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, 128, TextCase.DEFAULT, null, null);
		textFieldDisableGangwayPattern.setText2(config.disableGangwayPattern == null ? "" : config.disableGangwayPattern);
		textFieldPlayerAvatarUrl = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE, 256, TextCase.DEFAULT, null, null);
		textFieldPlayerAvatarUrl.setText2(config.playerAvatarUrl == null ? "" : config.playerAvatarUrl);

		header("客户端 · 模型与渲染");
		toggle("车辆模型常驻内存", () -> config.keepVehicleModelsResident, v -> config.keepVehicleModelsResident = v);
		toggle("按真实编组预热缓存", () -> config.preloadRealConsistBuckets, v -> config.preloadRealConsistBuckets = v);
		toggle("预加载已配置的车", () -> config.preloadConfiguredVehicles, v -> config.preloadConfiguredVehicles = v);
		toggle("转向架复用缓存桶", () -> config.reuseBucketForBogies, v -> config.reuseBucketForBogies = v);
		toggle("剥离 NTE 部件名前缀", () -> config.stripNtePartNamePrefix, v -> config.stripNtePartNamePrefix = v);
		toggle("修镜像部件 box 偏移", () -> config.fixMirroredBoxX, v -> config.fixMirroredBoxX = v);
		toggle("修分侧门条件字符串", () -> config.fixLegacyDoorSideConditions, v -> config.fixLegacyDoorSideConditions = v);
		toggle("修 OBJ 门双重翻转", () -> config.fixObjDoorDoubleFlip, v -> config.fixObjDoorDoubleFlip = v);

		header("客户端 · 车内行为");
		toggle("可走到窗边", () -> config.widenFloorsToInterior, v -> config.widenFloorsToInterior = v);
		toggle("shift 短按坐下", () -> config.shiftToCrouch, v -> config.shiftToCrouch = v);
		field("地板加宽 (格)", textFieldFloorInflateX);
		field("禁贯通道正则", textFieldDisableGangwayPattern);

		header("服务端 · 改服务端那份");
		field("网页头像地址", textFieldPlayerAvatarUrl);
		toggle("分交路停站时长", () -> config.perRouteDwellTime, v -> config.perRouteDwellTime = v);
		toggle("最小刷新 (不清车)", () -> config.minimalPathRefresh, v -> config.minimalPathRefresh = v);
		toggle("改停站免刷新", () -> config.applyDwellTimeWithoutRefresh, v -> config.applyDwellTimeWithoutRefresh = v);
		toggle("侧线车辆最高速", () -> config.vehicleMaxSpeed, v -> config.vehicleMaxSpeed = v);

		header("诊断日志");
		toggle("模型缓存重建统计", () -> config.debugCacheLogging, v -> config.debugCacheLogging = v);
		toggle("车门映射", () -> config.debugDoorMapping, v -> config.debugDoorMapping = v);
		toggle("停站时长", () -> config.debugDwellTime, v -> config.debugDwellTime = v);
		toggle("刷新保车 / 清车", () -> config.debugPathRefresh, v -> config.debugPathRefresh = v);
	}

	@Override
	protected void init2() {
		super.init2();

		final ScreenAbstractMapping screen = (ScreenAbstractMapping) (Object) this;
		final int screenWidth = screen.getWidthMapped();
		final int rowsPerColumn = Math.max(4, (screen.getHeightMapped() - TOP - SQUARE_SIZE) / ROW_HEIGHT);
		final int columns = Math.max(1, (rows.size() + rowsPerColumn - 1) / rowsPerColumn);
		final int columnWidth = Math.min(COLUMN_WIDTH_MAX,
				Math.max(SQUARE_SIZE * 4, (screenWidth - SQUARE_SIZE * (columns + 1)) / columns));

		for (int i = 0; i < rows.size(); i++) {
			final Row row = rows.get(i);
			row.x = SQUARE_SIZE + (i / rowsPerColumn) * (columnWidth + SQUARE_SIZE);
			row.y = TOP + (i % rowsPerColumn) * ROW_HEIGHT;
			row.labelX = row.x;

			if (row.checkbox != null) {
				IDrawing.setPositionAndWidth(row.checkbox, row.x, row.y, ROW_HEIGHT);
				row.checkbox.setChecked(row.getter.get());
				// 复选框自己只画那个方框（文本传的是空的），标签由 render() 画在它右边
				row.labelX = row.x + ROW_HEIGHT + TEXT_PADDING;
				addChild(new ClickableWidget(row.checkbox));
			} else if (row.textField != null) {
				IDrawing.setPositionAndWidth(row.textField,
						row.x + FIELD_LABEL_WIDTH + TEXT_FIELD_PADDING / 2, row.y + TEXT_FIELD_PADDING / 2,
						columnWidth - FIELD_LABEL_WIDTH - TEXT_FIELD_PADDING);
				addChild(new ClickableWidget(row.textField));
			}
		}
	}

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);

		// 标题栏：头像 + 名字，画法照抄 MTR ConfigScreen 的 drawHeader
		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		guiDrawing.beginDrawingTexture(LOGO);
		guiDrawing.drawTexture(SQUARE_SIZE, TEXT_PADDING / 2, SQUARE_SIZE + LOGO_SIZE, TEXT_PADDING / 2 + LOGO_SIZE, 0, 0, 1, 1);
		guiDrawing.finishDrawingTexture();
		graphicsHolder.drawText("yuzi 的 MTR 扩展",
				SQUARE_SIZE + LOGO_SIZE + TEXT_PADDING, TEXT_PADDING / 2 + (LOGO_SIZE - TEXT_HEIGHT) / 2,
				ARGB_WHITE, false, GraphicsHolder.getDefaultLight());

		super.render(graphicsHolder, mouseX, mouseY, delta);

		final int textOffset = (ROW_HEIGHT - TEXT_HEIGHT) / 2;
		for (final Row row : rows) {
			graphicsHolder.drawText(row.label, row.labelX, row.y + textOffset,
					row.checkbox == null && row.textField == null ? ARGB_LIGHT_GRAY : ARGB_WHITE,
					false, GraphicsHolder.getDefaultLight());
		}
	}

	@Override
	public void onClose2() {
		try {
			config.floorInflateX = Double.parseDouble(textFieldFloorInflateX.getText2().trim());
		} catch (NumberFormatException ignored) {
			// 留空或打了一半就当没改
		}
		config.disableGangwayPattern = textFieldDisableGangwayPattern.getText2().trim();
		final String avatarUrl = textFieldPlayerAvatarUrl.getText2().trim();
		config.playerAvatarUrl = avatarUrl.isEmpty() ? AvatarUrlRewriter.DEFAULT_TEMPLATE : avatarUrl;
		Config.save();
		super.onClose2();
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	private void header(String text) {
		rows.add(new Row(text));
	}

	private void toggle(String label, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		final Row row = new Row(label);
		row.getter = getter;
		// 空文本 + true：见类注释里那个参数写反的坑。true 实际落在 showMessage 上，
		// 而文本是空的，所以什么都不画；勾选状态在 init2 里用 setChecked() 设。
		row.checkbox = new CheckboxWidgetExtension(0, 0, 0, ROW_HEIGHT, TextHelper.literal(""), true, setter::accept);
		rows.add(row);
	}

	private void field(String label, TextFieldWidgetExtension textField) {
		final Row row = new Row(label);
		row.textField = textField;
		rows.add(row);
	}

	/** 一行：分组标题、开关、或者取值输入框。 */
	private static final class Row {

		private final String label;
		private CheckboxWidgetExtension checkbox;
		private TextFieldWidgetExtension textField;
		private Supplier<Boolean> getter;
		private int x;
		private int y;
		private int labelX;

		private Row(String label) {
			this.label = label;
		}
	}
}
