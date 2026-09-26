package com.yuzi.mtrext.station;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

import java.util.List;

/**
 * 客户端 → 服务端：整份配置一次覆盖。
 *
 * <p>不做增量：规则列表短，整份发比维护「第 n 条改了」的协议简单得多，
 * 也不会出现两个人同时编辑时半份新半份旧的状态。
 */
public final class PacketUpdateStationAnnouncer extends PacketHandler {

	private final BlockPos blockPos;
	private final float volume;
	private final boolean realtimeOnly;
	private final boolean limitRange;
	private final BlockPos corner1;
	private final BlockPos corner2;
	private final ObjectArrayList<AnnouncerRule> rules;

	public PacketUpdateStationAnnouncer(PacketBufferReceiver packetBufferReceiver) {
		blockPos = BlockPos.fromLong(packetBufferReceiver.readLong());
		volume = packetBufferReceiver.readFloat();
		realtimeOnly = packetBufferReceiver.readBoolean();
		limitRange = packetBufferReceiver.readBoolean();
		corner1 = BlockPos.fromLong(packetBufferReceiver.readLong());
		corner2 = BlockPos.fromLong(packetBufferReceiver.readLong());
		final int count = packetBufferReceiver.readInt();
		rules = new ObjectArrayList<>();
		for (int i = 0; i < count; i++) {
			final AnnouncerRule rule = new AnnouncerRule();
			rule.platformId = packetBufferReceiver.readLong();
			rule.routeId = packetBufferReceiver.readLong();
			rule.soundId = packetBufferReceiver.readString();
			rule.offsetSeconds = packetBufferReceiver.readInt();
			rule.durationMillis = packetBufferReceiver.readInt();
			rule.readFlags(packetBufferReceiver.readInt());
			rules.add(rule);
		}
	}

	public PacketUpdateStationAnnouncer(BlockPos blockPos, List<AnnouncerRule> rules, float volume, boolean realtimeOnly, boolean limitRange, BlockPos corner1, BlockPos corner2) {
		this.blockPos = blockPos;
		this.volume = volume;
		this.realtimeOnly = realtimeOnly;
		this.limitRange = limitRange;
		this.corner1 = corner1;
		this.corner2 = corner2;
		this.rules = new ObjectArrayList<>(rules);
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(blockPos.asLong());
		packetBufferSender.writeFloat(volume);
		packetBufferSender.writeBoolean(realtimeOnly);
		packetBufferSender.writeBoolean(limitRange);
		packetBufferSender.writeLong(corner1.asLong());
		packetBufferSender.writeLong(corner2.asLong());
		packetBufferSender.writeInt(rules.size());
		rules.forEach(rule -> {
			packetBufferSender.writeLong(rule.platformId);
			packetBufferSender.writeLong(rule.routeId);
			packetBufferSender.writeString(rule.soundId);
			packetBufferSender.writeInt(rule.offsetSeconds);
			packetBufferSender.writeInt(rule.durationMillis);
			packetBufferSender.writeInt(rule.toFlags());
		});
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		// 照抄 MTR 每个 PacketUpdateXxxConfig 的第一行：区块没加载就别去碰方块实体。
		if (!Init.isChunkLoaded(serverPlayerEntity.getEntityWorld(), blockPos)) {
			return;
		}
		final BlockEntity blockEntity = serverPlayerEntity.getEntityWorld().getBlockEntity(blockPos);
		if (blockEntity != null && blockEntity.data instanceof StationAnnouncerBlockEntity) {
			((StationAnnouncerBlockEntity) blockEntity.data).setData(rules, volume, realtimeOnly, limitRange, corner1, corner2);
		}
	}
}
