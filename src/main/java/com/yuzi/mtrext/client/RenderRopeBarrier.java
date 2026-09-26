package com.yuzi.mtrext.client;

import com.yuzi.mtrext.client.PsdaCountdown;
import com.yuzi.mtrext.psd.RopeBarrierBlockEntity;
import com.yuzi.mtrext.psd.RopeBarrierPositions;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.BlockEntityRenderer;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.block.IBlock;

/**
 * 绳索方块的方块实体渲染器 —— 但它<b>不画任何东西</b>。
 *
 * <p>实际绘制在 {@link RopeBarrierDrawing}，由 {@link com.yuzi.mtrext.mixin.MainRendererMixin}
 * 调用。原因见那个 mixin 的注释：只有 MTR 的 {@code MainRenderer} 那条路径会进光影的
 * 阴影 pass，方块实体渲染器走不到，OBJ 就没有影子。
 *
 * <p>这里只做两件事：每帧推进门开度动画（{@code tick} 必须由渲染帧驱动，同 MTR 的
 * {@code RenderPSDAPGDoor.java:42}），以及把自己的位置登记进 {@link RopeBarrierPositions}
 * 供 mixin 遍历。
 */
public class RenderRopeBarrier extends BlockEntityRenderer<RopeBarrierBlockEntity> implements IBlock {

	public RenderRopeBarrier(Argument dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(RopeBarrierBlockEntity entity, float tickDelta, GraphicsHolder graphicsHolder, int light, int overlay) {
		final World world = entity.getWorld2();
		if (world == null) {
			return;
		}
		final BlockPos blockPos = entity.getPos2();
		RopeBarrierPositions.mark(blockPos);

		if (IBlock.getStatePropertySafe(world, blockPos, HALF) == DoubleBlockHalf.UPPER) {
			return;
		}
		entity.tick(tickDelta);
		entity.updateFromCountdown(PsdaCountdown.millisUntilDeparture(blockPos));
	}

	@Override
	public boolean rendersOutsideBoundingBox2(RopeBarrierBlockEntity blockEntity) {
		return true;
	}
}
