package com.yuzi.mtrext.client;

import com.yuzi.mtrext.mixin.VehicleRidingStateAccessor;
import com.yuzi.mtrext.station.RangedAnnouncerSound;
import com.yuzi.mtrext.station.StationAnnouncerBlockEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.mod.client.MinecraftClientData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 站台广播器限制范围模式在客户端的调度：收到播放指令时定资格，之后每 tick 看要不要（重新）开播。
 *
 * <h2>资格（按收到指令那一刻）</h2>
 * <ul>
 *     <li>人在播放框里 → 有资格，马上满音量播放（乘不乘车都一样）。</li>
 *     <li>不在框里：续播关着、正在乘车、或不在续播范围里 → 没资格，这条直接扔掉。
 *     续播范围是车站时，按指令里的站台 id 从客户端数据找所属车站（平面矩形，上下不限高）。</li>
 * </ul>
 *
 * <h2>之后</h2>
 *
 * <p>有资格的人只要人在框里、又没有正在放的实例，就开一个新实例，<b>播放位置 = 收到指令以来实际过去的时间</b>，
 * 从 0 音量淡入；已经超过音频时长就不放了。离框照旧由实例自己淡出、停掉。所以来回进出框，
 * 每次听到的都是「现在这一刻该放到的地方」，不是上次停下的地方。
 *
 * <p>不预先静音播放：报站音频全是流式的，客户端同时最多 8 路流，大站好几个站台一起播时会把真该听的挤掉。
 */
public final class RangedAnnouncerPlayback {

	/** 量不出时长时，资格最多保留这么久（之后进框也不放了）。 */
	private static final long UNKNOWN_DURATION_MILLIS = 120_000;
	/** 时长快到时就别再开新实例了：开出来也只剩一点点尾巴，还要先淡入。 */
	private static final long TAIL_GUARD_MILLIS = 300;
	/** 开播失败（比如流式声道满了）时别每 tick 都重试刷警告。 */
	private static final long RESTART_INTERVAL_MILLIS = 1000;

	private static final List<Playback> PLAYBACKS = new ArrayList<>();

	private RangedAnnouncerPlayback() {
	}

	/** 在客户端入口里调一次。通道注册了，Fabric 进服时才会告诉服务端「这个客户端认识」。 */
	public static void register() {
		// 1.6.0 服务端的格式：只发给框里的人，没有续播
		ClientPlayNetworking.registerGlobalReceiver(RangedAnnouncerSound.CHANNEL, (client, handler, buf, responseSender) -> {
			final Identifier soundId = Identifier.tryParse(buf.readString());
			final Box box = readBox(buf);
			final int fadeTicks = buf.readVarInt();
			if (soundId != null) {
				client.execute(() -> trigger(soundId, 0, box, fadeTicks, StationAnnouncerBlockEntity.JOIN_NONE, 0, null));
			}
		});
		// 1.7.0 起的格式：多了时长、续播模式、站台 id、自定义续播框
		ClientPlayNetworking.registerGlobalReceiver(RangedAnnouncerSound.CHANNEL_JOIN, (client, handler, buf, responseSender) -> {
			final Identifier soundId = Identifier.tryParse(buf.readString());
			final int durationMillis = buf.readVarInt();
			final Box box = readBox(buf);
			final int fadeTicks = buf.readVarInt();
			final int joinMode = buf.readByte();
			final long platformId = buf.readLong();
			final Box joinBox = joinMode == StationAnnouncerBlockEntity.JOIN_CUSTOM ? readBox(buf) : null;
			if (soundId != null) {
				client.execute(() -> trigger(soundId, durationMillis, box, fadeTicks, joinMode, platformId, joinBox));
			}
		});
		YuziMtrExtClient.REGISTRY_CLIENT.eventRegistryClient.registerEndClientTick(RangedAnnouncerPlayback::tick);
		// 换世界 / 断线时清掉：资格是按上一个世界的坐标定的
		YuziMtrExtClient.REGISTRY_CLIENT.eventRegistryClient.registerClientDisconnect(PLAYBACKS::clear);
	}

	private static void trigger(Identifier soundId, int durationMillis, Box box, int fadeTicks, int joinMode, long platformId, Box joinBox) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) {
			return;
		}
		final boolean inBox = contains(box, player);
		if (!inBox && (joinMode == StationAnnouncerBlockEntity.JOIN_NONE || isRiding() || !inJoinRange(player, joinMode, platformId, joinBox))) {
			return;
		}

		final long now = System.currentTimeMillis();
		final int measured = durationMillis > 0 ? durationMillis : OggDuration.millis(soundId.toString());
		final Playback playback = new Playback(soundId, box, fadeTicks, now, now + (measured > 0 ? measured : UNKNOWN_DURATION_MILLIS));
		if (inBox) {
			playback.start(0, 1);
		}
		PLAYBACKS.add(playback);
	}

	private static void tick() {
		if (PLAYBACKS.isEmpty()) {
			return;
		}
		final MinecraftClient client = MinecraftClient.getInstance();
		final ClientPlayerEntity player = client.player;
		final long now = System.currentTimeMillis();
		final Iterator<Playback> iterator = PLAYBACKS.iterator();
		while (iterator.hasNext()) {
			final Playback playback = iterator.next();
			if (now >= playback.endMillis) {
				// 正在放的实例不用管，放到头自己会停；这里只是不再续播
				iterator.remove();
			} else if (player != null && now < playback.endMillis - TAIL_GUARD_MILLIS && now - playback.lastStartMillis >= RESTART_INTERVAL_MILLIS
					&& contains(playback.box, player) && (playback.instance == null || !client.getSoundManager().isPlaying(playback.instance))) {
				playback.start(now - playback.startMillis, 0);
			}
		}
	}

	private static boolean inJoinRange(ClientPlayerEntity player, int joinMode, long platformId, Box joinBox) {
		if (joinMode == StationAnnouncerBlockEntity.JOIN_CUSTOM) {
			return joinBox != null && contains(joinBox, player);
		}
		final Platform platform = MinecraftClientData.getInstance().platformIdMap.get(platformId);
		final Station station = platform == null ? null : platform.area;
		return station != null && station.inArea(new Position((long) Math.floor(player.getX()), (long) Math.floor(player.getY()), (long) Math.floor(player.getZ())));
	}

	/** MTR 下车时把 ridingVehicleId 清零，所以非 0 就是在车上。 */
	private static boolean isRiding() {
		return VehicleRidingStateAccessor.yuzimtr$getRidingVehicleId() != 0;
	}

	private static boolean contains(Box box, ClientPlayerEntity player) {
		return box.contains(player.getX(), player.getY(), player.getZ());
	}

	private static Box readBox(net.minecraft.network.PacketByteBuf buf) {
		return new Box(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
	}

	private static final class Playback {

		private final Identifier soundId;
		private final Box box;
		private final int fadeTicks;
		private final long startMillis;
		private final long endMillis;
		private RangedAnnouncerSoundInstance instance;
		private long lastStartMillis = Long.MIN_VALUE / 2;

		private Playback(Identifier soundId, Box box, int fadeTicks, long startMillis, long endMillis) {
			this.soundId = soundId;
			this.box = box;
			this.fadeTicks = fadeTicks;
			this.startMillis = startMillis;
			this.endMillis = endMillis;
		}

		private void start(long skipMillis, float initialLevel) {
			lastStartMillis = System.currentTimeMillis();
			instance = new RangedAnnouncerSoundInstance(soundId, box, fadeTicks, initialLevel, skipMillis);
			MinecraftClient.getInstance().getSoundManager().play(instance);
		}
	}
}
