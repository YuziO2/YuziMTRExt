package com.yuzi.mtrext.station;

import com.yuzi.mtrext.YuziMtrExt;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 限制范围模式的「离框淡出」播放：服务端只告诉客户端放哪个音、框在哪、淡出几 tick，
 * 由客户端每 tick 看玩家还在不在框里，离开就淡出到停（客户端那半在 {@code client/RangedAnnouncerSoundInstance}）。
 *
 * <p>以前限制范围用的是 {@code playNotifySound}：触发那一刻在框里的人会一直听到放完，
 * 车开走了也一样 —— 后车跟得近时，前一站的「即将到达」还在放，下一站的报站已经开始，两段叠在一起。
 *
 * <p><b>单独一条 Fabric 通道，不走 mapping 层的 {@code yuzimtr:packet}。</b>为的是兼容没升级的客户端：
 * 客户端注册了这个通道的接收器，Fabric 会在进服时把通道名报给服务端，服务端用
 * {@link ServerPlayNetworking#canSend} 就能知道对方认不认识；不认识就退回原来的 {@code playNotifySound}。
 * 走 mapping 层的话服务端分不出来，而旧客户端收到不认识的包是静默丢弃（{@code RegistryClient} 里
 * {@code Map.get} 拿到 null 直接 return，字节码确认）—— 等于没升级的人在框里什么都听不到。
 *
 * <p>这个类服务端和客户端都会加载，不许引用 {@code net.minecraft.client.*}。
 */
public final class RangedAnnouncerSound {

	/** 1.6.0 的格式：只给框里的人发，客户端离框淡出。 */
	public static final Identifier CHANNEL = new Identifier(YuziMtrExt.MOD_ID, "ranged_sound");
	/**
	 * 1.7.0 起的格式，多了续播：发给框里的人和续播范围里的候选人，由客户端按触发那一刻的位置和乘车状态定资格。
	 * <b>格式变了就必须换通道名</b>：旧客户端用旧读法读新格式会读错位，新客户端读旧格式会读越界。
	 * 服务端按 {@link #CHANNEL_JOIN} → {@link #CHANNEL} → playNotifySound 的顺序降级。
	 */
	public static final Identifier CHANNEL_JOIN = new Identifier(YuziMtrExt.MOD_ID, "ranged_sound_join");

	private RangedAnnouncerSound() {
	}

	public static boolean canJoin(ServerPlayerEntity player) {
		return ServerPlayNetworking.canSend(player, CHANNEL_JOIN);
	}

	/**
	 * 发一条可续播的播放指令（调用方先用 {@link #canJoin} 确认对方认识）。
	 *
	 * @param durationMillis 规则里存的音频时长，0 表示没量过，客户端自己读 ogg
	 * @param joinBounds     自定义续播框，其他模式传 null
	 */
	public static void sendJoinable(ServerPlayerEntity player, String soundId, int durationMillis, double[] bounds, int fadeTicks,
									int joinMode, long platformId, double[] joinBounds) {
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeString(soundId);
		buf.writeVarInt(durationMillis);
		for (final double bound : bounds) {
			buf.writeDouble(bound);
		}
		buf.writeVarInt(fadeTicks);
		buf.writeByte(joinMode);
		buf.writeLong(platformId);
		if (joinMode == StationAnnouncerBlockEntity.JOIN_CUSTOM) {
			for (final double bound : joinBounds) {
				buf.writeDouble(bound);
			}
		}
		ServerPlayNetworking.send(player, CHANNEL_JOIN, buf);
	}

	/**
	 * 对方客户端支持的话发一条「离框淡出」播放，返回 {@code true}；不支持返回 {@code false}，由调用方走老路。
	 *
	 * @param bounds 框的 {minX, minY, minZ, maxX, maxY, maxZ}，max 已经含 +1
	 */
	public static boolean send(ServerPlayerEntity player, String soundId, double[] bounds, int fadeTicks) {
		if (!ServerPlayNetworking.canSend(player, CHANNEL)) {
			return false;
		}
		final PacketByteBuf buf = PacketByteBufs.create();
		buf.writeString(soundId);
		for (final double bound : bounds) {
			buf.writeDouble(bound);
		}
		buf.writeVarInt(fadeTicks);
		ServerPlayNetworking.send(player, CHANNEL, buf);
		return true;
	}
}
