package com.yuzi.mtrext.gametest;

import com.yuzi.mtrext.psd.RopeBarriers;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

public class RopeBarrierGameTest {

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void ropeIsPassableOnServerWhilePostBlocksPassage(TestContext context) {
		final BlockPos ropePos = new BlockPos(1, 1, 1);
		final BlockPos postPos = new BlockPos(3, 1, 1);
		context.setBlockState(ropePos, RopeBarriers.ROPE.get().data);
		context.setBlockState(postPos, RopeBarriers.POST.get().data);

		context.expectBlock(RopeBarriers.ROPE.get().data, ropePos);
		context.expectBlock(RopeBarriers.POST.get().data, postPos);

		final VoxelShape ropeCollision = context.getBlockState(ropePos)
				.getCollisionShape(context.getWorld(), context.getAbsolutePos(ropePos));
		final VoxelShape postCollision = context.getBlockState(postPos)
				.getCollisionShape(context.getWorld(), context.getAbsolutePos(postPos));
		context.assertTrue(ropeCollision.isEmpty(), "the rope must not block server-side movement");
		context.assertFalse(postCollision.isEmpty(), "the post must block server-side movement");
		context.assertTrue(postCollision.getBoundingBox().getXLength() == 1
				&& postCollision.getBoundingBox().getZLength() == 1,
				"the post must close the full-width gap beside the train");
		context.complete();
	}
}
