package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectCollection;
import org.mtr.mod.sound.ScheduledSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * 修上游 bug：每个待播音效被播 N 遍（N = 本帧待播音效数），同帧同位同相位叠加，
 * 幅度 ×N 直接削波 —— 听感就是「响度很大、音质极差」。
 *
 * <h2>成因</h2>
 *
 * <p>{@code ScheduledSound.play()} 的循环体是个 lambda，而这个 lambda
 * <b>从头到尾没用过自己的参数</b>（字节码 {@code lambda$play$0(ClientWorld, ScheduledSound)}
 * 里只有 {@code aload_0}，那个 {@code ScheduledSound} 参数一次都没被 load）：
 * <pre>
 * SCHEDULED_SOUNDS.values().forEach(scheduledSound -&gt;
 *     world.playSoundAtBlockCenter(this.blockPos, this.soundEvent, ..., this.gain, this.pitch, false));
 * //                                ^^^^ 用的是 this，不是循环变量
 * </pre>
 * 而 {@code playScheduledSounds()} 又是 {@code values().forEach(ScheduledSound::play)}。
 * 两层一乘：<b>N 个待播音效播 N² 次，每个音效自己被播 N 份</b>。
 *
 * <p>N 份是同一帧、同一坐标、同增益、同音高发出的，完全相干叠加 → 幅度 ×N → 过载。
 * {@code schedule} 的去重键是 {@code "<音效id>_<gain>_<pitch>"}，而走行音的 gain/pitch
 * 随速度连续变化，所以多节车速度稍有差异就各占一个键，N 越大炸得越狠。
 * 停站时车不发走行音、N 小，所以门音听不出异常。
 *
 * <h2>修法</h2>
 *
 * <p>把 {@code play()} 里那次 {@code forEach} 换成「只调用一次」。
 * <b>传的是 {@code this} 而不是 {@code null}</b>：现在的 lambda 不看参数，传什么都一样；
 * 万一上游哪天把 lambda 修成真的用参数，传 {@code this} 依然是正确语义
 * （{@code playScheduledSounds} 本来就已经逐个调 {@code play()} 了），传 {@code null} 则会 NPE。
 *
 * <p>{@code @Redirect} 限定在 {@code play()} 里 —— {@code playScheduledSounds()} 自己那次
 * {@code forEach} 描述符完全相同，靠 {@code method} 限定区分，那一次是对的、不能动。
 *
 * <p><b>修完 legacy 走行音会明显变小</b>，回到 MTR3 的响度。这是预期内的。
 */
@Mixin(value = ScheduledSound.class, remap = false)
public abstract class ScheduledSoundMixin {

	@Redirect(
			method = "play()V",
			at = @At(
					value = "INVOKE",
					target = "Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectCollection;forEach(Ljava/util/function/Consumer;)V"
			)
	)
	private void yuzimtr$playOnce(ObjectCollection<ScheduledSound> scheduledSounds, Consumer<ScheduledSound> consumer) {
		if (Config.get().fixScheduledSoundDuplication) {
			consumer.accept((ScheduledSound) (Object) this);
		} else {
			scheduledSounds.forEach(consumer);
		}
	}
}
