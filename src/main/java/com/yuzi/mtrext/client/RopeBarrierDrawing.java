package com.yuzi.mtrext.client;

import com.yuzi.mtrext.psd.BlockRopeBarrier;
import com.yuzi.mtrext.psd.BlockRopeBarrierPost;
import com.yuzi.mtrext.client.PsdaModels;
import com.yuzi.mtrext.psd.RopeBarrierBlockEntity;
import com.yuzi.mtrext.client.RopeBarrierFlood;
import org.mtr.core.data.Platform;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.Block;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.LightType;
import org.mtr.mapping.holder.LightmapTextureManager;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.block.IBlock;
import org.mtr.mod.render.StoredMatrixTransformations;

/**
 * 绳网的实际绘制。
 *
 * <p>这些方法由 {@link com.yuzi.mtrext.mixin.MainRendererMixin} 调用，而不是方块实体
 * 渲染器 —— 因为只有 {@code MainRenderer} 那条路径会进光影的阴影 pass（它是挂在虚拟实体上的
 * {@code EntityRenderer}），OBJ 才有正确的影子。方块实体渲染器现在只负责推进动画和登记位置，
 * 见 {@link com.yuzi.mtrext.psd.RopeBarrierPositions}。
 */
public final class RopeBarrierDrawing {

	private RopeBarrierDrawing() {
	}

	/** 照 {@code RenderRails} 的做法按方块位置取光照。 */
	private static int lightAt(ClientWorld clientWorld, BlockPos blockPos) {
		return LightmapTextureManager.pack(
				clientWorld.getLightLevel(LightType.getBlockMapped(), blockPos),
				clientWorld.getLightLevel(LightType.getSkyMapped(), blockPos));
	}

	public static void draw(ClientWorld clientWorld, BlockPos blockPos) {
		final Block block = clientWorld.getBlockState(blockPos).getBlock();
		// 双高方块：只从下半块画一次，模型会自己往上伸进上半块的空间
		if (IBlock.getStatePropertySafe(clientWorld.getBlockState(blockPos), IBlock.HALF)
				== IBlock.DoubleBlockHalf.UPPER) {
			return;
		}

		final org.mtr.mapping.holder.BlockEntity blockEntity = clientWorld.getBlockEntity(blockPos);
		if (blockEntity == null || !(blockEntity.data instanceof RopeBarrierBlockEntity)) {
			return;
		}
		final RopeBarrierBlockEntity entity = (RopeBarrierBlockEntity) blockEntity.data;
		final Direction facing = IBlock.getStatePropertySafe(clientWorld.getBlockState(blockPos),
				DirectionHelper.FACING);
		final int light = lightAt(clientWorld, blockPos);
		final double eased = PsdaModels.ease(entity.getDoorValue());

		if (block.data instanceof BlockRopeBarrier) {
			drawRopes(blockPos, facing, eased, light, true, true);
		} else if (block.data instanceof BlockRopeBarrierPost) {
			drawPost(clientWorld, blockPos, facing, eased, light,
					((BlockRopeBarrierPost) block.data).pids);
		}
	}

	/** 整格宽的绳子；立柱那边只画朝有邻居那一侧的半根。 */
	private static void drawRopes(BlockPos blockPos, Direction facing, double eased, int light,
			boolean forward, boolean backward) {
		for (int i = 0; i < PsdaModels.LINE_COUNT; i++) {
			final double height = PsdaModels.ropeHeight(eased, i);
			final StoredMatrixTransformations transformations =
					PsdaModels.at(blockPos.getX(), blockPos.getY(), blockPos.getZ(), facing);
			// 局部 -Y 就是世界 +Y —— 矩阵里那个 rotateXRadians(π)，见 PsdaModels.at
			transformations.add(graphicsHolder -> graphicsHolder.translate(0, -height, 0));
			PsdaModels.render(PsdaModels.ropeModel(i), transformations, light);
		}
	}

	private static void drawPost(ClientWorld clientWorld, BlockPos blockPos, Direction facing,
			double eased, int light, boolean withPids) {
		// 立柱两侧各补半根绳 —— ANTE 的 node.js include 了 common.js，立柱那个方块自己也画绳。
		// 只画绳索方块的话，每根立柱处会断开一格。
		final Direction rowDirection = facing.rotateYClockwise();
		final boolean hasForward = RopeBarrierFlood.isPart(clientWorld, blockPos.offset(rowDirection));
		final boolean hasBackward =
				RopeBarrierFlood.isPart(clientWorld, blockPos.offset(rowDirection.getOpposite()));
		for (int i = 0; i < PsdaModels.LINE_COUNT; i++) {
			final double height = PsdaModels.ropeHeight(eased, i);
			for (final boolean forward : new boolean[]{true, false}) {
				if (forward ? !hasForward : !hasBackward) {
					continue;
				}
				final StoredMatrixTransformations rope =
						PsdaModels.at(blockPos.getX(), blockPos.getY(), blockPos.getZ(), facing);
				rope.add(graphicsHolder -> graphicsHolder.translate(0, -height, 0));
				PsdaModels.halfRopeTowards(rope, forward);
				PsdaModels.render(PsdaModels.ropeModel(i), rope, light);
			}
		}

		PsdaModels.render(withPids ? PsdaModels.POST : PsdaModels.POST_BLANK,
				PsdaModels.at(blockPos.getX(), blockPos.getY(), blockPos.getZ(), facing), light);

		final StoredMatrixTransformations head =
				PsdaModels.at(blockPos.getX(), blockPos.getY(), blockPos.getZ(), facing);
		head.add(graphicsHolder -> graphicsHolder.translate(0, -eased, 0));
		PsdaModels.render(PsdaModels.HEAD, head, light);

		if (withPids) {
			final ObjectArrayList<Platform> platforms = new ObjectArrayList<>();
			InitClient.findClosePlatform(blockPos, 5, platforms::add);
			if (!platforms.isEmpty()) {
				PsdaPids.render(blockPos, facing, platforms.get(0).getId());
			}
		}
	}
}
