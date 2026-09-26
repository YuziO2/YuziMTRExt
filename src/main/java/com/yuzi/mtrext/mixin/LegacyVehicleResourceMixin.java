package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import org.mtr.legacy.resource.LegacyVehicleResource;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把 MTR3 的分侧门条件改写成 MTR4 认得的字符串。
 *
 * <p>注入点选 processModel 的 HEAD 而不是里面的 lambda：出错的 render_condition switch 在
 * {@code lambda$processModel$6} 里，而 synthetic lambda 的编号会随上游改动变化。
 * 改输入（HEAD）能绕开它。
 */
@Mixin(value = LegacyVehicleResource.class, remap = false)
public abstract class LegacyVehicleResourceMixin {

	private static final String PROCESS_MODEL =
			"processModel(IIZZLorg/mtr/libraries/com/google/gson/JsonArray;Lorg/mtr/libraries/com/google/gson/JsonArray;Lorg/mtr/libraries/com/google/gson/JsonArray;DLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

	/**
	 * 把 MTR3 的分侧门条件改写成 MTR4 认得的字符串。
	 *
	 * <p>MTR3 的枚举（ResourcePackCreatorProperties.java:436）是
	 * {@code DOOR_LEFT_OPEN / DOOR_RIGHT_OPEN / DOOR_LEFT_CLOSED / DOOR_RIGHT_CLOSED}
	 * —— <b>单数 DOOR_</b>。而 MTR4 的 switch（LegacyVehicleResource.java:290-300）
	 * 匹配的是 {@code DOORS_LEFT_OPEN} 等<b>复数</b>形式，那些字符串在 MTR3 里从来不存在，
	 * 于是全部落进 {@code default: NORMAL} = 无条件渲染。
	 * 上海磁浮 TR08 的门状态指示灯（door_open / door_open2，stage LIGHTS）因此常亮，
	 * 开灯和关灯同时亮。
	 *
	 * <p>MTR4 的 PartCondition 没有分左右的门条件，只有 DOORS_OPENED / DOORS_CLOSED，
	 * 所以只能改写成不分侧的版本 —— 丢了左右区分，但至少尊重门的开关状态。
	 */
	@Inject(method = PROCESS_MODEL, at = @At("HEAD"))
	private void yuzimtr$beforeProcessModel(
			int currentCar, int totalCars, boolean isObj, boolean reversed,
			JsonArray propertiesPartsArray, JsonArray positionDefinitionsArray, JsonArray partsArray,
			double doorMax, String renderConditionOverride, String whitelistedCarsOverride,
			String blacklistedCarsOverride, CallbackInfo ci
	) {
		if (!Config.get().fixLegacyDoorSideConditions || propertiesPartsArray == null) {
			return;
		}
		// 直接改输入。同一个 JsonArray 会被四个 Variation 各处理一遍，
		// 但改写是幂等的（第二遍看到的已经是 DOORS_ 形式）。
		for (final JsonElement element : propertiesPartsArray) {
			if (!element.isJsonObject()) {
				continue;
			}
			final JsonObject part = element.getAsJsonObject();
			if (!part.has("render_condition")) {
				continue;
			}
			final String condition;
			try {
				condition = part.get("render_condition").getAsString().toUpperCase(java.util.Locale.ENGLISH);
			} catch (Exception e) {
				continue;
			}
			if ("DOOR_LEFT_OPEN".equals(condition) || "DOOR_RIGHT_OPEN".equals(condition)) {
				part.addProperty("render_condition", "DOORS_OPEN");
			} else if ("DOOR_LEFT_CLOSED".equals(condition) || "DOOR_RIGHT_CLOSED".equals(condition)) {
				part.addProperty("render_condition", "DOORS_CLOSED");
			}
		}
	}
}
