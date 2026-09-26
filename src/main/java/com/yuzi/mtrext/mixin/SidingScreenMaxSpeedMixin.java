package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.SidingMaxSpeedHolder;
import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.mapper.CheckboxWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mapping.mapper.TextFieldWidgetExtension;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mapping.tool.TextCase;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.data.IGui;
import org.mtr.mod.screen.SidingScreen;
import org.mtr.mod.screen.WidgetShorterSlider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 侧线面板上加一行「车辆最高速」，插在「车辆减速度」下面，其余各行整体下移一格。
 *
 * <p>{@code SidingScreen} 是按固定行号排的（{@code SQUARE_SIZE * n + TEXT_FIELD_PADDING * 2}），
 * 减速度是第 5 行。于是：
 * <ul>
 * <li>控件：{@code init2} 的 RETURN 把第 6 行起的 7 个控件各 {@code setY2(+SQUARE_SIZE)}。
 *     {@code init2} 每次都先写绝对坐标，所以改窗口大小再跑一遍也不会越挪越多。
 *     其中两个停站时间滑块声明在父类 {@code SavedRailScreenBase} 上，{@code @Shadow} 不上溯，
 *     走 {@link SavedRailScreenAccessor}。</li>
 * <li>标签：{@code render} 里 8 个 {@code drawText} 描述符完全相同（字节码确认），一个
 *     {@code @Redirect} 全接住，y 在第 5 行标签之下的就 +SQUARE_SIZE。</li>
 * </ul>
 *
 * <p>零自定义网络包：{@code onClose2} 本来就把整个 Siding 发回服务端
 * （{@code UpdateDataRequest.addSiding}），最高速挂在 Siding 上（{@link SidingMaxSpeedSchemaMixin}），
 * 在 HEAD 写进去就跟着同一个包走了。
 *
 * <p>和面板上其余调度参数一样，只在 {@code showScheduleControls} 时出现（缆车这类连续运动的交通方式没有）。
 */
@Mixin(value = SidingScreen.class, remap = false)
public abstract class SidingScreenMaxSpeedMixin {

	@Shadow
	@Final
	private WidgetShorterSlider sliderDelayedVehicleSpeedIncreasePercentage;
	@Shadow
	@Final
	private WidgetShorterSlider sliderDelayedVehicleReduceDwellTimePercentage;
	@Shadow
	@Final
	private CheckboxWidgetExtension buttonEarlyVehicleIncreaseDwellTime;
	@Shadow
	@Final
	private CheckboxWidgetExtension buttonIsManual;
	@Shadow
	@Final
	private WidgetShorterSlider sliderMaxManualSpeed;

	@Unique
	private TextFieldWidgetExtension yuzimtr$maxSpeedField;

	/**
	 * 输入框里的原始文本，{@code tick2} 每 tick 镜像一次。{@code init2} 在改窗口大小、
	 * 从「选择车辆」子界面回来时都会重跑，直接从侧线重读会把刚输入还没保存的值冲掉
	 * （打开子界面走的是 {@code removed}，不经过 {@code onClose2}，值还没写回侧线）。
	 */
	@Unique
	private String yuzimtr$maxSpeedText;

	/** 插入的是第几行（0 起算，和 SidingScreen 的行号一致）。 */
	@Unique
	private static final int MAX_SPEED_ROW = 6;
	@Unique
	private static final int FIELD_WIDTH = 40;
	/** 第 5 行（减速度）标签的 y。比它靠下的标签都要下移。 */
	@Unique
	private static final int SHIFT_LABELS_BELOW_Y = IGui.SQUARE_SIZE * (MAX_SPEED_ROW - 1) + IGui.TEXT_FIELD_PADDING * 2 + IGui.TEXT_PADDING;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void yuzimtr$createMaxSpeedField(CallbackInfo ci) {
		yuzimtr$maxSpeedField = new TextFieldWidgetExtension(0, 0, 0, IGui.SQUARE_SIZE, 4, TextCase.DEFAULT, "\\D", "0");
		yuzimtr$maxSpeedText = String.valueOf(yuzimtr$holder().yuzimtr$getMaxSpeedKmh());
	}

	@Inject(method = "init2", at = @At("RETURN"))
	private void yuzimtr$layoutMaxSpeedRow(CallbackInfo ci) {
		if (!yuzimtr$shown()) {
			return;
		}
		final SavedRailScreenAccessor accessor = (SavedRailScreenAccessor) (Object) this;

		for (final WidgetShorterSlider slider : new WidgetShorterSlider[]{
				sliderDelayedVehicleSpeedIncreasePercentage, sliderDelayedVehicleReduceDwellTimePercentage, sliderMaxManualSpeed,
				accessor.yuzimtr$sliderDwellTimeMin(), accessor.yuzimtr$sliderDwellTimeSec()}) {
			slider.setY2(slider.getY2() + IGui.SQUARE_SIZE);
		}
		buttonEarlyVehicleIncreaseDwellTime.setY2(buttonEarlyVehicleIncreaseDwellTime.getY2() + IGui.SQUARE_SIZE);
		buttonIsManual.setY2(buttonIsManual.getY2() + IGui.SQUARE_SIZE);

		IDrawing.setPositionAndWidth(yuzimtr$maxSpeedField,
				IGui.SQUARE_SIZE + accessor.yuzimtr$textWidth() + IGui.TEXT_FIELD_PADDING / 2,
				IGui.SQUARE_SIZE * MAX_SPEED_ROW + IGui.TEXT_FIELD_PADDING * 2,
				FIELD_WIDTH);
		// 必须在 setPositionAndWidth 之后：构造期 width=0 时 setText 会把可见窗口推到末尾，渲染成空的。
		yuzimtr$maxSpeedField.setText2(yuzimtr$maxSpeedText);
		((ScreenExtension) (Object) this).addChild(new ClickableWidget(yuzimtr$maxSpeedField));
	}

	@Inject(method = "tick2", at = @At("TAIL"))
	private void yuzimtr$tickMaxSpeedField(CallbackInfo ci) {
		if (yuzimtr$shown()) {
			yuzimtr$maxSpeedField.tick2();
			yuzimtr$maxSpeedText = yuzimtr$maxSpeedField.getText2();
		}
	}

	@Redirect(
			method = "render",
			at = @At(value = "INVOKE", target = "Lorg/mtr/mapping/mapper/GraphicsHolder;drawText(Lorg/mtr/mapping/holder/MutableText;IIIZI)V")
	)
	private void yuzimtr$shiftLabels(GraphicsHolder graphicsHolder, MutableText text, int x, int y, int color, boolean shadow, int light) {
		graphicsHolder.drawText(text, x, yuzimtr$shown() && y > SHIFT_LABELS_BELOW_Y ? y + IGui.SQUARE_SIZE : y, color, shadow, light);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void yuzimtr$drawMaxSpeedLabel(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!yuzimtr$shown()) {
			return;
		}
		final int labelY = IGui.SQUARE_SIZE * MAX_SPEED_ROW + IGui.TEXT_FIELD_PADDING * 2 + IGui.TEXT_PADDING;
		graphicsHolder.drawText(TextHelper.translatable("gui.yuzimtr.siding_max_speed"),
				IGui.SQUARE_SIZE, labelY, IGui.ARGB_WHITE, false, GraphicsHolder.getDefaultLight());
		final int hintX = IGui.SQUARE_SIZE + ((SavedRailScreenAccessor) (Object) this).yuzimtr$textWidth() + IGui.TEXT_FIELD_PADDING / 2 + FIELD_WIDTH + IGui.TEXT_PADDING;
		graphicsHolder.drawText(TextHelper.translatable("gui.yuzimtr.siding_max_speed_hint"),
				hintX, labelY, IGui.ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
	}

	/** 在 HEAD 写进 Siding：原方法末尾才构造并发送 {@code PacketUpdateData}，那时已经带上了。 */
	@Inject(method = "onClose2", at = @At("HEAD"))
	private void yuzimtr$saveMaxSpeed(CallbackInfo ci) {
		if (!yuzimtr$shown()) {
			return; // 没显示这一行就别动它原来的值
		}
		long maxSpeedKmh;
		try {
			maxSpeedKmh = Long.parseLong(yuzimtr$maxSpeedField.getText2());
		} catch (Exception e) {
			maxSpeedKmh = 0; // 清空 = 不限
		}
		yuzimtr$holder().yuzimtr$setMaxSpeedKmh(maxSpeedKmh);
	}

	@Unique
	private boolean yuzimtr$shown() {
		return Config.get().vehicleMaxSpeed && ((SavedRailScreenAccessor) (Object) this).yuzimtr$showScheduleControls();
	}

	@Unique
	private SidingMaxSpeedHolder yuzimtr$holder() {
		return (SidingMaxSpeedHolder) ((SavedRailScreenAccessor) (Object) this).yuzimtr$savedRailBase();
	}
}
