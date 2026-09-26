package com.yuzi.mtrext.client;

import com.yuzi.mtrext.psd.RopeBarrierPart;
import com.yuzi.mtrext.psd.RopeBarrierBlockEntity;
import com.yuzi.mtrext.psd.RopeBarriers;
import com.yuzi.mtrext.Config;
import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.Direction;

/**
 * 「沿排推值」—— 整个方案的关键。
 *
 * <p>MTR4 原生屏蔽门只开车门正对的那一块，而这里只要有<b>任何一个</b> doorway 命中绳排，
 * 就把门开度沿排推给所有相连的绳网方块。等价于 ANTE {@code mixin/TrainMixin.java:285-296}
 * 的六向泛洪，只是绳排是直线，只需要一条轴（垂直于 FACING 的水平轴）。
 *
 * <p>泛洪判定用的是 {@link RopeBarrierPart} 而不是绳索方块本身，因为一排真实的绳网是
 * {@code 立柱-绳-绳-绳-立柱-…}，必须能穿过中间的立柱。
 *
 * <p><b>放在客户端包里是有意的</b>：这些方法吃 {@code ClientWorld}，而那个类包的是
 * {@code net.minecraft.class_638}，专用服务端没有。放回 {@link RopeBarriers}
 * 会让服务端加载注册代码时有踩到 NoClassDefFoundError 的风险。
 */
public final class RopeBarrierFlood {

	private RopeBarrierFlood() {
	}

	public static boolean isPart(ClientWorld world, BlockPos pos) {
		return world.getBlockState(pos).getBlock().data instanceof RopeBarrierPart;
	}

	public static boolean isPartAt(org.mtr.mapping.holder.World world, BlockPos pos) {
		return world.getBlockState(pos).getBlock().data instanceof RopeBarrierPart;
	}

	/**
	 * 把门开度推给 {@code origin} 所在整排绳网。可以从绳索或立柱起步。
	 *
	 * @return 实际收到门开度的方块数（绳索和立柱都收 —— 立柱的柱头也要跟着升）
	 */
	public static int pushAlongRow(ClientWorld world, BlockPos origin, double doorValue) {
		final BlockState originState = world.getBlockState(origin);
		if (!(originState.getBlock().data instanceof RopeBarrierPart)) {
			return 0;
		}

		int pushed = push(world, origin, doorValue);
		final Direction rowDirection = RopeBarriers.rowDirection(originState);
		final int spread = Math.max(0, Config.get().ropeBarrierSpread);

		// 两个方向各走一遍，遇到不属于绳网的方块就停 —— 这样两排背靠背的绳网不会互相串扰
		for (final Direction direction : new Direction[]{rowDirection, rowDirection.getOpposite()}) {
			BlockPos pos = origin;
			for (int i = 0; i < spread; i++) {
				pos = pos.offset(direction);
				if (!isPart(world, pos)) {
					break;
				}
				pushed += push(world, pos, doorValue);
			}
		}

		return pushed;
	}

	private static int push(ClientWorld world, BlockPos pos, double doorValue) {
		final BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity != null && blockEntity.data instanceof RopeBarrierBlockEntity) {
			((RopeBarrierBlockEntity) blockEntity.data).setDoorValue(doorValue);
			return 1;
		}
		return 0;
	}
}
