package com.yuzi.mtrext.station;

import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mod.Blocks;
import org.mtr.mod.block.IBlock;

import javax.annotation.Nonnull;

/**
 * 站台广播器。逻辑全在 {@link StationAnnouncerBlockEntity}，这里只负责开界面。
 *
 * <p>开界面走的是和 MTR 传感器同一条路（{@code BlockTrainSensorBase#onUse2}）：
 * {@code IBlock.checkHoldingBrush} 的回调<b>只在服务端跑</b>
 * （{@code IBlock.java:49} 那个 {@code if (!world.isClient())}），所以这里
 * 先 {@code markDirty2()} 把方块实体的 NBT 推给客户端，再发包让客户端开界面 ——
 * 顺序不能反，否则界面读到的是空配置。
 *
 * <p>{@code markDirty2()} 之所以能同步，是因为 mapping 的
 * {@code BlockEntityExtension.markDirty2()} 在服务端额外调了
 * {@code level.sendBlockUpdated(pos, state, state, 2)}（字节码确认），
 * 会触发 {@code getUpdatePacket}，把 {@link StationAnnouncerBlockEntity#writeCompoundTag} 发下去。
 */
public class BlockStationAnnouncer extends BlockExtension implements BlockWithEntity {

	public BlockStationAnnouncer() {
		super(Blocks.createDefaultBlockSettings(true));
	}

	@Nonnull
	@Override
	public ActionResult onUse2(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		return IBlock.checkHoldingBrush(world, player, () -> {
			final BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity != null && blockEntity.data instanceof StationAnnouncerBlockEntity) {
				((StationAnnouncerBlockEntity) blockEntity.data).markDirty2();
				YuziMtrExt.REGISTRY.sendPacketToClient(ServerPlayerEntity.cast(player), new PacketOpenStationAnnouncerScreen(pos));
			}
		});
	}

	@Nonnull
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new StationAnnouncerBlockEntity(StationAnnouncers.ENTITY_TYPE.get(), blockPos, blockState);
	}
}
