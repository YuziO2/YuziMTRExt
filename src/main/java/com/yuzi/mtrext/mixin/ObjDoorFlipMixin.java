package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Box;
import org.mtr.mod.resource.ModelPropertiesPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复 OBJ 门部件被翻转两次，外加一个可选的映射探针。
 *
 * <p><b>上游 bug</b>：ModelPropertiesPart 两个 writeCache 重载对 optimizedModelDoor 的
 * 处理不对称。
 * <ul>
 * <li>Blockbench 重载：{@code addCube(modelPart, 0, 0, 0, false, …)} —— 门模型干净，
 *     渲染时 translate + rotateYDegrees(180) 正好翻转一次。</li>
 * <li>OBJ 重载：{@code fromObjModels(objModels)} 用的是同一批 ObjModelWrapper，
 *     而它们已经被 addObjModelPosition → objModel.addTransformation(…, flipped)
 *     就地改过（supplier 在那之后才求值）。门模型里已经烤进了一次翻转，
 *     渲染时又翻一次 —— <b>翻两次等于没翻</b>，门画回原侧。</li>
 * </ul>
 *
 * <p>症状：MTR3 资源包里按车厢奇偶 reversed 的车（重庆2 的 crt2，4 节编组的第 1、3 节），
 * 开门时该开的一侧不开、对侧开，且门渲染成对面门的样子；关门恢复（关门走主模型那条路）。
 *
 * <p>修法两步，缺一不可：
 * <ol>
 * <li>把门部件的 PartDetails.flipped 置 false —— 渲染时不再 rotateYDegrees(180)，
 *     几何只保留烤进去的那一次翻转。box 不受影响：构造 PartDetails 时
 *     addBox 是实参，早已用真实的 flipped 算完并存好。</li>
 * <li><b>把 doorXMultiplier / doorZMultiplier 取反。</b>上一步把
 *     {@code (flipped ? -1 : 1) * multiplier} 里的取反一并去掉了，而那一半是对的 ——
 *     几何镜像后，滑动方向本来就该跟着镜像。不补回来的话「几何」和「滑动」互相矛盾，
 *     表现是双开门的左右扇对调（实测过）。</li>
 * </ol>
 *
 * <p>取反后的乘积和原来一致，所以 getOpenDoorBounds（骑乘碰撞，第 241-242 行）行为不变。
 * 关光影走的非优化路径用 partDetails.modelParts，OBJ 路径那个列表是空的，碰不到。
 *
 * <p>另有一个可选探针：打出每个门部件的 box 和它被 mapDoors 分配到的 doorway。
 *
 * <p>要回答的问题：reversed（flipped=true）的车厢，门部件的 box 是不是被镜像到了
 * 对侧，从而分配到对侧的 doorway —— 那样平台侧的门就不会开，对侧反而开。
 *
 * <p>mapDoors 是 ModelPropertiesPart 的真方法（不在 lambda 里），注入点稳定。
 */
@Mixin(value = ModelPropertiesPart.class, remap = false)
public abstract class ObjDoorFlipMixin {

	@Shadow @Final private ObjectArrayList<Object> partDetailsList;

	/**
	 * OBJ 重载的 writeCache 结束后，把门部件的 flipped 置 false，抵消双重翻转。
	 * 只挂 OBJ 那个重载 —— Blockbench 重载本来就是对的，别去动它。
	 */
	@Inject(
			method = "writeCache(Ljava/util/Map;Lorg/mtr/mod/resource/PositionDefinitions;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap;D)V",
			at = @At("TAIL")
	)
	private void yuzimtr$fixObjDoorDoubleFlip(CallbackInfo ci) {
		if (!Config.get().fixObjDoorDoubleFlip) {
			return;
		}
		final PartSchemaAccessor schema = (PartSchemaAccessor) this;
		if (schema.yuzimtr$doorXMultiplier() == 0 && schema.yuzimtr$doorZMultiplier() == 0) {
			return; // 不是门，主模型那条路没有这个问题
		}
		if (yuzimtr$doubleFlipFixed) {
			return; // 幂等保护：multiplier 只能取反一次
		}
		boolean anyFlipped = false;
		for (final Object raw : partDetailsList) {
			final PartDetailsAccessor partDetails = (PartDetailsAccessor) raw;
			if (partDetails.yuzimtr$flipped()) {
				partDetails.yuzimtr$setFlipped(false);
				anyFlipped = true;
			}
		}
		if (anyFlipped) {
			yuzimtr$doubleFlipFixed = true;
			schema.yuzimtr$setDoorXMultiplier(-schema.yuzimtr$doorXMultiplier());
			schema.yuzimtr$setDoorZMultiplier(-schema.yuzimtr$doorZMultiplier());
		}
	}

	@Unique
	private boolean yuzimtr$doubleFlipFixed;

	/**
	 * 修复镜像部件的 box 和几何不在同一侧。
	 *
	 * <p>MTR4 对同一个 (x, flipped) 有两套算法：
	 * <ul>
	 * <li>{@code addCube}（烘焙几何）：等效于 {@code -x - cube}</li>
	 * <li>{@code addBox}（算 box，供 mapDoors 用）：{@code -cube + x}</li>
	 * </ul>
	 * x 为 0 时两者一致，所以原生模型和大多数 MTR3 包都碰不到。但 position.x 非零的
	 * 镜像部件（上海磁浮 TR08 的 dr1/dr2，mirror=true、position.x=52.2）就会错开 2x：
	 * <pre>
	 * cube ∈ [1.375, 2.390]，x/16 = -3.2625
	 * 几何: -(-3.2625) - cube = [0.872, 1.888]   → 右侧，正确
	 * box :  -cube + (-3.2625) = [-4.80, -4.64]  → 左外侧，错
	 * </pre>
	 * 于是 mapDoors 把右侧的门分到左侧 doorway —— 平台在局部左侧时四个门一起动
	 * （看起来两边都开），在局部右侧时右 doorway 开但没有部件映射过去（两边都不开）。
	 *
	 * <p>只改 box，几何一个字节都不动。参考 MTR3 的 renderOnceFlipped
	 * （ModelTrainBase.java:196）：{@code bone.render(…, -positionX, positionZ, PI, …)}
	 * —— positionX 确实是取反的，所以几何那一侧本来就对。
	 */
	@Inject(
			method = "addBox(Lorg/mtr/mapping/holder/Box;DDDZ)Lorg/mtr/mapping/holder/Box;",
			at = @At("HEAD"),
			cancellable = true
	)
	private static void yuzimtr$fixFlippedBoxX(Box box, double x, double y, double z, boolean flipped, CallbackInfoReturnable<Box> cir) {
		if (!flipped || !Config.get().fixMirroredBoxX) {
			return;
		}
		// 与 MTR4 原式的唯一差别：x 项跟着 flipped 一起取反，和 addCube 对齐
		cir.setReturnValue(new Box(
				-box.getMinXMapped() - x / 16, box.getMinYMapped() + y / 16, box.getMinZMapped() + z / 16,
				-box.getMaxXMapped() - x / 16, box.getMaxYMapped() + y / 16, box.getMaxZMapped() + z / 16
		));
	}

	@Inject(method = "mapDoors", at = @At("TAIL"))
	private void yuzimtr$logDoorMapping(ObjectArrayList<Box> doorways, CallbackInfo ci) {
		if (!Config.get().debugDoorMapping) {
			return;
		}
		final PartSchemaAccessor schema = (PartSchemaAccessor) this;
		if (schema.yuzimtr$doorXMultiplier() == 0 && schema.yuzimtr$doorZMultiplier() == 0) {
			return; // 不是门
		}
		final String names = String.join(",", schema.yuzimtr$names());
		partDetailsList.forEach(raw -> {
			final PartDetailsAccessor pd = (PartDetailsAccessor) raw;
			final Box box = pd.yuzimtr$box();
			final Box doorway = pd.yuzimtr$doorway();
			YuziMtrExt.LOGGER.info(
					"门映射 {} flipped={} xMult={} zMult={} 门模型={} | box x[{}, {}] z[{}, {}] | doorway {}",
					names, pd.yuzimtr$flipped(),
					schema.yuzimtr$doorXMultiplier(), schema.yuzimtr$doorZMultiplier(),
					pd.yuzimtr$optimizedModelDoor() == null ? "null" : "有",
					String.format("%.3f", box.getMinXMapped()), String.format("%.3f", box.getMaxXMapped()),
					String.format("%.3f", box.getMinZMapped()), String.format("%.3f", box.getMaxZMapped()),
					doorway == null ? "null" : String.format("x[%.3f, %.3f] z[%.3f, %.3f]",
							doorway.getMinXMapped(), doorway.getMaxXMapped(),
							doorway.getMinZMapped(), doorway.getMaxZMapped())
			);
		});
	}
}
