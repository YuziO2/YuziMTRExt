package com.yuzi.mtrext.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.yuzi.mtrext.client.RangedAnnouncerSoundInstance;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * 站台广播器续播要「从中途开始放」，原版没有这个能力：1.20.1 的 {@code SoundInstance} 还没有 {@code getAudioStream}
 * （新版本才有），流是在 {@code SoundSystem.play(SoundInstance)} 里直接 {@code loadStreamed} 拿的（字节码确认，就一处）。
 * 这里只对我们自己的 {@link RangedAnnouncerSoundInstance} 套一层跳过开头的流，其他声音原样返回。
 */
@Mixin(SoundSystem.class)
public abstract class SoundSystemSeekMixin {

	@WrapOperation(
			method = "play(Lnet/minecraft/client/sound/SoundInstance;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundLoader;loadStreamed(Lnet/minecraft/util/Identifier;Z)Ljava/util/concurrent/CompletableFuture;")
	)
	private CompletableFuture<AudioStream> yuzimtr$seekRangedAnnouncer(
			SoundLoader soundLoader, Identifier id, boolean repeatInstantly, Operation<CompletableFuture<AudioStream>> original,
			@Local(argsOnly = true) SoundInstance soundInstance
	) {
		final CompletableFuture<AudioStream> future = original.call(soundLoader, id, repeatInstantly);
		return soundInstance instanceof RangedAnnouncerSoundInstance ranged ? future.thenApply(ranged::wrapStream) : future;
	}
}
