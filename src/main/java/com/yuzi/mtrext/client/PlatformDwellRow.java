package com.yuzi.mtrext.client;

import org.mtr.core.data.Route;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mapping.tool.TextCase;
import org.mtr.mod.data.IGui;
import org.mtr.mod.generated.lang.TranslationProvider;

/**
 * 站台仪表板「途径本站台之路线」列表里的一行：交路名 + 停站秒数输入框 + 重置按钮。
 *
 * <p>输入框留空 = 跟随站台总值（真正的继承）。重置按钮就是把它清空。
 *
 * <p>控件在 PlatformScreen 构造时建好、init2 里摆位，和 MTR 自己的做法一致 ——
 * init2 会在改窗口大小时重跑，控件在那里新建会把用户刚打的字冲掉。
 */
public final class PlatformDwellRow implements IGui {

	public final Route route;
	public final TextFieldWidgetExtension field;
	public final ButtonWidgetExtension resetButton;

	private static final int MAX_LENGTH = 6;

	/** 行高比上游的 10px 大，因为要塞得下一个输入框和一个按钮。 */
	public static final int ROW_HEIGHT = SQUARE_SIZE + 2;
	public static final int FIELD_WIDTH = 40;
	public static final int BUTTON_WIDTH = 40;
	/** 列表起点，和上游那句 SQUARE_SIZE * 4 + TEXT_FIELD_PADDING + TEXT_PADDING 对齐。 */
	public static final int LIST_TOP = SQUARE_SIZE * 4 + TEXT_FIELD_PADDING;

	public PlatformDwellRow(Route route, Long overrideMillis) {
		this.route = route;
		field = new TextFieldWidgetExtension(0, 0, 0, SQUARE_SIZE - TEXT_FIELD_PADDING, MAX_LENGTH, TextCase.DEFAULT, "[^\\d.]", null);
		field.setText2(overrideMillis == null ? "" : formatSeconds(overrideMillis));
		resetButton = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE,
				TranslationProvider.GUI_MTR_RESET_SIGN.getMutableText(), button -> field.setText2(""));
	}

	/** @return 输入框里的毫秒数；{@code null} 表示留空/无效，即跟随总值。 */
	public Long parseMillis() {
		final String text = field.getText2().trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			final double seconds = Double.parseDouble(text);
			return seconds > 0 ? Math.round(seconds * 1000) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public boolean isInheriting() {
		return field.getText2().trim().isEmpty();
	}

	public static String formatSeconds(long millis) {
		return String.format("%.1f", millis / 1000.0);
	}
}
