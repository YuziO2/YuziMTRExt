package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import org.mtr.mod.InitClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修上游 bug：同一个客户端进程里重连之后，<b>legacy 车的走行音整体静音</b>，
 * 要等很久才自己回来。
 *
 * <h2>成因</h2>
 *
 * <p>{@code InitClient.canPlaySound()} 是这样的（字节码确认）：
 * <pre>
 * if (getGameMillis() - lastPlayedTrainSoundsMillis &gt;= 200) {
 *     lastPlayedTrainSoundsMillis = getGameMillis();
 * }
 * return getGameMillis() == lastPlayedTrainSoundsMillis &amp;&amp; !isPaused();
 * </pre>
 *
 * <p>而 {@code gameMillis} 在<b>客户端加入服务器时被归零</b>
 * （{@code registerClientJoin} 的回调里 {@code lconst_0; putstatic gameMillis}），
 * {@code lastPlayedTrainSoundsMillis} 却没有跟着归零 —— 全类只有 {@code <clinit>} 和
 * {@code canPlaySound} 自己写它。
 *
 * <p>于是重连后 {@code 0 - 一个很大的数} 是负数、不更新，{@code 0 == 很大的数} 为假，
 * <b>{@code canPlaySound()} 恒返回 false</b>，直到 {@code gameMillis} 重新爬回旧值。
 * 静音时长 ≈ 本进程里最长那段旧连接的时长 —— 这就是「玩一会又自己好了」，
 * 契机不是玩了什么，是时间走够了。MTR3 用的是只增不减、从不归零的 {@code gameTick}，
 * 是升级引入的回归。
 *
 * <h2>为什么只有走行音、只有 legacy 车</h2>
 *
 * <p>{@code canPlaySound} 在整个 MTR 里<b>只有一个调用点</b>：
 * {@code LegacyVehicleSound.playMotorSound}。所以：
 * <ul>
 * <li>门音走 {@code playDoorSound → ScheduledSound.schedule}，不经过这道闸 —— 一直正常；</li>
 * <li>{@code BveVehicleSound} 里 {@code canPlaySound} 零命中 —— BVE 循环音的车
 *     （重庆5、重庆2、AEL-Mini 等）完全不受影响。</li>
 * </ul>
 * 所以差异不是「自定义包 vs 内置」，是<b>「BVE 音效 vs legacy 音效」</b>。
 *
 * <h2>修法</h2>
 *
 * <p>入口检测「上次播放时间戳跑到了当前时间前面」这个不可能状态，就把它清零。
 * 自愈式，不依赖上游 lambda 的编号或注册顺序；上游哪天自己修了，这个判断也永不成立。
 */
@Mixin(value = InitClient.class, remap = false)
public abstract class LegacySoundThrottleMixin {

	@Shadow
	private static long lastPlayedTrainSoundsMillis;

	@Inject(method = "canPlaySound()Z", at = @At("HEAD"))
	private static void yuzimtr$healSoundThrottle(CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
		if (Config.get().fixLegacySoundThrottle && lastPlayedTrainSoundsMillis > InitClient.getGameMillis()) {
			lastPlayedTrainSoundsMillis = 0;
		}
	}
}
