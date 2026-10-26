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
 * 客户端 → 服务端：整份配置一次覆盖，比 {@link PacketUpdateStationAnnouncer} 多了续播范围（1.7.0 起）。
 *
 * <p><b>为什么是新类而不是往旧包末尾加字段</b>：mapping 层的 {@code PacketBufferReceiver} 没有「还剩几个字节」的接口，
 * 新服务端没法判断旧客户端发来的包里有没有这几个字段，照新格式读会越界、在网络线程上抛异常。
 * 换成新类名后，旧客户端照旧发旧包（服务端照旧处理，续播设置保持原样），新客户端发这个；
 * 新客户端连旧服务端时，旧服务端不认识这个类名，静默丢弃（编辑不生效，服务端先升级就不会遇到）。
 */
public final class PacketUpdateStationAnnouncerV2 extends PacketHandler {

	private final BlockPos blockPos;
	private final float volume;
	private final boolean realtimeOnly;
	private final boolean limitRange;
	private final BlockPos corner1;
	private final BlockPos corner2;
	private final int joinMode;
	private final BlockPos joinCorner1;
	private final BlockPos joinCorner2;
	private final ObjectArrayList<AnnouncerRule> rules;

	public PacketUpdateStationAnnouncerV2(PacketBufferReceiver packetBufferReceiver) {
		blockPos = BlockPos.fromLong(packetBufferReceiver.readLong());
		volume = packetBufferReceiver.readFloat();
		realtimeOnly = packetBufferReceiver.readBoolean();
		limitRange = packetBufferReceiver.readBoolean();
		corner1 = BlockPos.fromLong(packetBufferReceiver.readLong());
		corner2 = BlockPos.fromLong(packetBufferReceiver.readLong());
		joinMode = packetBufferReceiver.readInt();
		joinCorner1 = BlockPos.fromLong(packetBufferReceiver.readLong());
		joinCorner2 = BlockPos.fromLong(packetBufferReceiver.readLong());
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

	public PacketUpdateStationAnnouncerV2(BlockPos blockPos, List<AnnouncerRule> rules, float volume, boolean realtimeOnly, boolean limitRange,
										  BlockPos corner1, BlockPos corner2, int joinMode, BlockPos joinCorner1, BlockPos joinCorner2) {
		this.blockPos = blockPos;
		this.volume = volume;
		this.realtimeOnly = realtimeOnly;
		this.limitRange = limitRange;
		this.corner1 = corner1;
		this.corner2 = corner2;
		this.joinMode = joinMode;
		this.joinCorner1 = joinCorner1;
		this.joinCorner2 = joinCorner2;
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
		packetBufferSender.writeInt(joinMode);
		packetBufferSender.writeLong(joinCorner1.asLong());
		packetBufferSender.writeLong(joinCorner2.asLong());
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
			((StationAnnouncerBlockEntity) blockEntity.data).setData(rules, volume, realtimeOnly, limitRange, corner1, corner2, joinMode, joinCorner1, joinCorner2);
		}
	}
}
