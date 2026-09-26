package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.CustomRailSpeed;
import org.mtr.mod.data.RailType;
import org.mtr.mod.item.ItemRailModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让我们自己加的那几档轨道用自己的速度，而不是模板 {@code RailType} 上的那个。
 *
 * <p>MTR 的速度烤在 enum 里，但真正落地只吃 int（{@code createRail} 第 125 行的
 * {@code Rail.newRail(…, isOneWay ? 0 : newRailType.speedLimit, newRailType.speedLimit, …)}），
 * 所以拦住读那个字段就够了 —— 不用往 enum 里塞常量。
 *
 * <p>两个方法各拦一次：{@code createRail} 里那个字段读两次（限速的两个方向），
 * {@code addTooltips} 里读一次（物品提示上的「最高车速」）。
 * 不是我们的物品就原样返回，所以 MTR 自己那十档完全不受影响。
 */
@Mixin(value = ItemRailModifier.class, remap = false)
public abstract class ItemRailModifierMixin {

	@Redirect(
			method = "createRail",
			at = @At(value = "FIELD", target = "Lorg/mtr/mod/data/RailType;speedLimit:I", opcode = org.objectweb.asm.Opcodes.GETFIELD)
	)
	private int yuzimtr$customSpeedForRail(RailType railType) {
		return this instanceof CustomRailSpeed ? ((CustomRailSpeed) this).yuzimtr$speedLimit() : railType.speedLimit;
	}

	@Redirect(
			method = "addTooltips",
			at = @At(value = "FIELD", target = "Lorg/mtr/mod/data/RailType;speedLimit:I", opcode = org.objectweb.asm.Opcodes.GETFIELD)
	)
	private int yuzimtr$customSpeedForTooltip(RailType railType) {
		return this instanceof CustomRailSpeed ? ((CustomRailSpeed) this).yuzimtr$speedLimit() : railType.speedLimit;
	}
}
