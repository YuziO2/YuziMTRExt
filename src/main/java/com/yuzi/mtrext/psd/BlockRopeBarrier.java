package com.yuzi.mtrext.psd;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.BlockView;
import org.mtr.mapping.holder.ShapeContext;
import org.mtr.mapping.holder.VoxelShape;
import org.mtr.mapping.holder.VoxelShapes;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.block.BlockDirectionalDoubleBlockBase;
import org.mtr.mod.block.IBlock;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * 升降式绳索屏蔽门 · 绳索。
 *
 * <p>和 MTR4 原生屏蔽门的根本区别：原生的
 * {@code RenderVehicleHelper.canOpenDoors} 是按<b>每个 doorway</b> 在 ±1/±2 格内找
 * {@code BlockPSDAPGDoorBase} 才开那一块，所以门位必须和车对齐。绳网反过来 ——
 * 只要有<b>任何一个</b> doorway 落在绳排附近，就把<b>列车级</b>门开度沿整排推开
 * （见 {@link com.yuzi.mtrext.client.RopeBarrierFlood#pushAlongRow}），整排一起升，不看门位。这就是它能兼容
 * 不同门距的原因，也是 ANTE 那套 psda 的核心思路。
 *
 * <h2>为什么必须是双高方块</h2>
 *
 * <p>第一版做成单高、碰撞盒 0.6~1.48 格，结果是「第一次跳被拦下，第二次跳会卡进地里
 * 再弹出去穿过屏蔽门」。原因是 MC 的碰撞查询：一个方块的碰撞盒**超出自己那一格**时，
 * 只有在实体的扫掠包围盒仍然覆盖该方块所在格子时才会被查询到。玩家起跳后脚底一旦超过
 * {@code y + 1.0}，那一格就不在查询范围内了 —— 屏障当帧等于不存在，人横向穿过去；
 * 下落时又重新检测到，碰撞解算把人挤进地里再弹出。
 *
 * <p>所以照 MTR 自己的做法：{@code BlockPSDAPGBase extends BlockDirectionalDoubleBlockBase}，
 * 双高、每一半的碰撞盒都<b>绝不超过 16px</b>。{@code DoubleVerticalBlock} 负责放置时
 * 连带放上半块、拆一半时拆掉另一半。
 *
 * <p>门值和衰减逻辑逐行照抄 {@code BlockPSDAPGDoorBase.BlockEntityBase}：渲染器每帧驱动
 * tick，{@code doorTarget} 倒数到负之后按 {@code DOOR_MOVE_TIME/2} 落回 0；门值只存在
 * <b>下半块</b>的方块实体上，上半块通过 {@link #getBottomBlockEntity} 取（同上游 162-165 行）。
 *
 * <p>碰撞也照抄它（含那句原文注释「serverside collision shape is always empty, clientside
 * determined by the vehicle door positions the client sees」）：<b>服务端恒空、客户端只在
 * 门开度恰好为 0 时才挡人。</b>所以这是纯客户端碰撞，服务端不会真的阻挡玩家 ——
 * MTR 原生屏蔽门也是这个性质。
 */
public class BlockRopeBarrier extends BlockDirectionalDoubleBlockBase implements BlockWithEntity, RopeBarrierPart {

	/**
	 * 绳带的碰撞盒。取 ANTE 自己在 {@code psda.json} 里给 psda1 声明的 {@code shape}
	 * （{@code 0,4,7,16,23,9}），和由 {@link com.yuzi.mtrext.client.PsdaModels} 的常数算出来的绳子实际位置吻合：
	 * 落下时五根绳在 0.28~1.40 格，加上绳径 ±0.019 就是 4.2~22.7px。
	 *
	 * <p>之前这里是用 Config 里那几个字段算的（0.6~1.48 格），但渲染早已改用
	 * {@link com.yuzi.mtrext.client.PsdaModels} 的常数，两边对不上 —— 碰撞盒比绳子高了一截、底下又差一截。
	 */
	private static final double Z1 = 7;
	private static final double Z2 = 9;
	private static final double BAND_BOTTOM = 4;
	private static final double BAND_TOP = 23;

	public BlockRopeBarrier() {
		super(org.mtr.mod.Blocks.createDefaultBlockSettings(false).nonOpaque());
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(HALF);
	}

	/**
	 * 轮廓：下半块从底座一路盖到格顶，上半块盖到最高那根绳。
	 * <b>不随门开度变化</b> —— 否则绳子升起后就得瞄着半空才能选中方块，甚至根本拆不掉。
	 */
	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return isUpper(state) ? bandShape(state, 0, BAND_TOP - 16) : bandShape(state, 0, 16);
	}

	@Nonnull
	@Override
	public VoxelShape getCollisionShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		// 注意必须写全限定名：本类的内部类也叫 BlockEntity，会把 holder 那个遮蔽掉。
		// MTR 自己在 BlockEyeCandy.onUse2 里同样被这个坑到，写法一致。
		final org.mtr.mapping.holder.BlockEntity entity = world.getBlockEntity(pos);
		if (entity == null || !(entity.data instanceof RopeBarrierBlockEntity) || entity.getWorld() == null
				|| !entity.getWorld().isClient()) {
			return VoxelShapes.empty();
		}
		final RopeBarrierBlockEntity bottom = ((RopeBarrierBlockEntity) entity.data).bottom();
		if (bottom == null || bottom.getDoorValue() != 0) {
			return VoxelShapes.empty();
		}
		// 每一半都只在自己那一格内出碰撞盒，绝不越界 —— 见类注释里那个跳跃穿越的 bug
		return isUpper(state)
				? bandShape(state, 0, BAND_TOP - 16)
				: bandShape(state, BAND_BOTTOM, 16);
	}

	private static boolean isUpper(BlockState state) {
		return IBlock.getStatePropertySafe(state, HALF) == DoubleBlockHalf.UPPER;
	}

	/** 整格宽、y1..y2（像素，夹在 0..16）、进深 Z1..Z2 的盒子，按朝向旋转。 */
	private static VoxelShape bandShape(BlockState state, double y1, double y2) {
		final double lo = Math.max(0, Math.min(16, y1));
		final double hi = Math.max(0, Math.min(16, y2));
		if (hi - lo < 0.001) {
			return VoxelShapes.empty();
		}
		return IBlock.getVoxelShapeByDirection(0, lo, Z1, 16, hi, Z2, IBlock.getStatePropertySafe(state, FACING));
	}

	@Nonnull
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new RopeBarrierBlockEntity(RopeBarriers.ROPE_ENTITY_TYPE.get(), blockPos, blockState);
	}

}
