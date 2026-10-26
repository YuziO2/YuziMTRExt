package com.yuzi.mtrext.client;

import com.yuzi.mtrext.station.RangeFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

/**
 * 站台广播器限制范围模式在客户端的一次播放：不定位（跟着听者走、没有方位和距离衰减），
 * 每 tick 看玩家还在不在框里，离开就按 {@link RangeFade} 淡出，淡到 0 就停。谁、什么时候开播由 {@link RangedAnnouncerPlayback} 管。
 *
 * <h2>音量</h2>
 *
 * <p>引擎给声源的增益是 {@code clamp(volume × 「方块」分类音量, 0, 1)}。老路径传 1000，
 * 等于无视分类音量（只要不是 0）一律满格。这里要能淡出就得用真实的数，于是传 {@code 比例 ÷ 分类音量}：
 * 满格时增益照样是 1，和老路径一样响；淡出时增益就是比例本身，线性降到 0。分类音量是 0 时老路径也没声，这里同样。
 *
 * <h2>从中途开始（续播）</h2>
 *
 * <p>{@link #skipMillis} &gt; 0 时，{@code SoundSystemSeekMixin} 在加载音频流时套一层 {@link SkippingAudioStream}，
 * 先解码丢掉这么多毫秒。只有流式加载才能这么做（1.20.1 的 {@code SoundInstance} 还没有 {@code getAudioStream}），
 * 所以这种情况下把非流式的 {@link Sound} 换成一份 {@code stream=true} 的拷贝。
 */
public final class RangedAnnouncerSoundInstance extends MovingSoundInstance {

	private final Box box;
	private final RangeFade fade;
	private final long skipMillis;

	RangedAnnouncerSoundInstance(Identifier soundId, Box box, int fadeTicks, float initialLevel, long skipMillis) {
		super(SoundEvent.of(soundId), SoundCategory.BLOCKS, SoundInstance.createRandom());
		this.box = box;
		this.skipMillis = skipMillis;
		fade = new RangeFade(fadeTicks, initialLevel);
		relative = true;
		attenuationType = AttenuationType.NONE;
		x = 0;
		y = 0;
		z = 0;
		repeat = false;
		volume = gain(fade.level());
	}

	@Override
	public void tick() {
		final ClientPlayerEntity player = MinecraftClient.getInstance().player;
		// 判定点和服务端选人时一致：玩家坐标（脚下），框的 max 已经含 +1
		if (fade.tick(player != null && box.contains(player.getX(), player.getY(), player.getZ()))) {
			setDone();
		} else {
			volume = gain(fade.level());
		}
	}

	/** 续播从音量 0 淡入，没有这个的话引擎会以「音量为 0」为由直接跳过不播。 */
	@Override
	public boolean shouldAlwaysPlay() {
		return true;
	}

	@Override
	public WeightedSoundSet getSoundSet(SoundManager soundManager) {
		final WeightedSoundSet weightedSoundSet = super.getSoundSet(soundManager);
		if (skipMillis > 0 && sound != null && !sound.isStreamed()) {
			sound = new Sound(sound.getIdentifier().toString(), sound.getVolume(), sound.getPitch(), sound.getWeight(),
					sound.getRegistrationType(), true, sound.isPreloaded(), sound.getAttenuation());
		}
		return weightedSoundSet;
	}

	/** 给 {@code SoundSystemSeekMixin} 用：需要从中途开始的话包一层跳过开头的流。 */
	public AudioStream wrapStream(AudioStream stream) {
		return skipMillis > 0 ? new SkippingAudioStream(stream, skipMillis) : stream;
	}

	private static float gain(float level) {
		final float categoryVolume = MinecraftClient.getInstance().options.getSoundVolume(SoundCategory.BLOCKS);
		return categoryVolume <= 0 ? 0 : level / categoryVolume;
	}
}
