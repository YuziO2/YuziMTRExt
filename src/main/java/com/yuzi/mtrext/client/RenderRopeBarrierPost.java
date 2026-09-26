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
 * 立柱的方块实体渲染器 —— 和 {@link RenderRopeBarrier} 一样<b>不画任何东西</b>，
 * 只推进动画、播提示音、登记位置。绘制见 {@link RopeBarrierDrawing}。
 */
public class RenderRopeBarrierPost extends BlockEntityRenderer<RopeBarrierBlockEntity> implements IBlock {

	public RenderRopeBarrierPost(Argument dispatcher) {
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
		// 提示音只由立柱播 —— 一排十几个绳索方块各播一遍会叠成噪音。
		// ANTE 也是在 node.js（立柱）里播的。
		entity.playSoundOnChange();
	}

	@Override
	public boolean rendersOutsideBoundingBox2(RopeBarrierBlockEntity blockEntity) {
		return true;
	}
}
