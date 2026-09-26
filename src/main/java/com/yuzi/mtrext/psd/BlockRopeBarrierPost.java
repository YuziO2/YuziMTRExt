package com.yuzi.mtrext.psd;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.ShapeContext;
import org.mtr.mapping.holder.VoxelShape;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.block.BlockDirectionalDoubleBlockBase;
import org.mtr.mod.block.IBlock;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * 升降式绳索屏蔽门 · 立柱。
 *
 * <p>柱身本身不动、碰撞恒定存在（服务端客户端都挡人），但带一个方块实体 ——
 * 因为柱头（movea.obj 的 {@code m} 分组）要随门开度上升，见 {@code node.js:141-142}。
 *
 * <p>它在这套系统里的作用：把绳子在视觉上锚住、往两侧各补半根绳、让
 * {@link com.yuzi.mtrext.client.RopeBarrierFlood#pushAlongRow} 的泛洪能<b>穿过</b>它（见 {@link RopeBarrierPart}）、
 * 播关门提示音，以及 {@link #pids} 为 true 时在正面显示简易 PIDS
 * （见 {@link com.yuzi.mtrext.client.PsdaPids}）。{@code pids} 为 false 的变体正面是一整片
 * 纯白，不显示任何信息。
 *
 * <p>和绳索一样是<b>双高</b>方块，理由同 {@link BlockRopeBarrier} 类注释里那个
 * 「跳跃能穿过超出格子的碰撞盒」的 bug：立柱 1.5 格高，若做成单高方块，
 * 上半截的碰撞在玩家起跳后同样会被 MC 跳过。
 */
public class BlockRopeBarrierPost extends BlockDirectionalDoubleBlockBase implements BlockWithEntity, RopeBarrierPart {

	/**
	 * <b>选择框</b>对齐模型实际轮廓（碰撞另算，见 {@link #getCollisionShape2}）：
	 * X/Z 取 ANTE 自己在 {@code psda.json} 里给 psda2l 声明的
	 * {@code leastCollisions}（{@code 1,0,4,15,24,11}），实测柱身包围盒 X[0.8,15.2]px、
	 * Z[5.6,11.4]px 与之吻合；高度取闭合状态下柱头（{@code movea.obj} 的 m 分组）的顶端
	 * 1.671 格 ≈ 27px，而不是 ANTE 截断的 24px。
	 */
	private static final double X1 = 1;
	private static final double X2 = 15;
	private static final double Z1 = 4;
	private static final double Z2 = 11;
	private static final double TOTAL_HEIGHT = 27;

	/** 正面那块屏是不是显示 PIDS。false 就是纯白版，给不想要屏幕的场景用。 */
	public final boolean pids;

	/**
	 * 立柱自身发 8 级光，照亮站台边缘 —— 对齐 ANTE 的 {@code psda.json}：
	 * 三个立柱条目（{@code psda2l}/{@code psda2r}/{@code psda3}）都写了
	 * {@code "lightLevel": 8}，而绳索段（{@code psda1}）没有这个字段。
	 */
	public static final int LIGHT_LEVEL = 8;

	public BlockRopeBarrierPost(boolean pids) {
		super(org.mtr.mod.Blocks.createDefaultBlockSettings(false, blockState -> LIGHT_LEVEL).nonOpaque());
		this.pids = pids;
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(HALF);
	}

	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return shape(state);
	}

	@Nonnull
	@Override
	public VoxelShape getCollisionShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		// 立柱是实体结构，两端都挡人 —— 和绳索那种「客户端按门开度切换」不同。
		//
		// 碰撞占满整格（而不是贴合柱身的 X[1,15] / Z[4,11]）是有意的：柱身只有 7px 厚，
		// 玩家能从柱子和车厢之间那条缝里挤进去，然后贴着车身在站台与列车的空隙里一路穿行 ——
		// 车厢侧面的隐形墙拦得住「横穿」，拦不住这种「已经在缝里、沿着缝走」。
		// 整格碰撞把这条缝彻底堵死。选择框仍然贴合模型（见 getOutlineShape2），所以观感不变。
		return collisionShape(state);
	}

	@Nonnull
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new RopeBarrierBlockEntity(RopeBarriers.POST_ENTITY_TYPE.get(), blockPos, blockState);
	}

	private static VoxelShape shape(BlockState state) {
		return IBlock.getVoxelShapeByDirection(X1, 0, Z1, X2, height(state), Z2, IBlock.getStatePropertySafe(state, FACING));
	}

	private static VoxelShape collisionShape(BlockState state) {
		return IBlock.getVoxelShapeByDirection(0, 0, 0, 16, height(state), 16, IBlock.getStatePropertySafe(state, FACING));
	}

	/** 每一半都只在自己那一格内出盒子 —— 超出格子的部分会被 MC 的碰撞遍历跳过。 */
	private static double height(BlockState state) {
		final boolean upper = IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER;
		return upper ? Math.min(16, TOTAL_HEIGHT - 16) : 16;
	}
}
