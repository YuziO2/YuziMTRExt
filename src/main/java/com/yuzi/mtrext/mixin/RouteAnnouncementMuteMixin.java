package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.client.NarratorGate;
import org.mtr.mod.packet.PacketCheckRouteIdHasDisabledAnnouncements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * 把交路上的「关掉下一站播报」从「聊天栏和讲述人一起关」改成<b>只关讲述人语音</b>。
 *
 * <h2>上游是怎么写的</h2>
 *
 * <p>{@code VehicleExtension} 在该报站时向服务端问一句「这条交路关了播报没有」，
 * 然后在回调里：
 *
 * <pre>
 * if (!routeIdHasDisabledAnnouncements) {
 *     ... 同时攒出 narrateText（念的）和 chatText（聊天栏的）...
 *     IDrawing.narrateOrAnnounce(merge(narrateText), chatText);
 * }
 * </pre>
 *
 * 两样东西被同一个 {@code if} 罩着，所以那个开关一勾，聊天栏也跟着没了。
 *
 * <h2>做法</h2>
 *
 * <p>回调参数是 {@code false} 时上游才会生成内容，所以我们把 {@code true} 改成
 * {@code false} 放它进去，同时举起 {@link NarratorGate} 让讲述人这一次闭嘴。
 * 聊天栏文字照常发出，语音没有 —— 正是要的效果。
 *
 * <p>注入点是 {@code runClient()} 里<b>唯一</b>一处 {@code Consumer.accept}（字节码确认，
 * offset 40），无歧义、也不用数 ordinal。
 *
 * <h2>为什么要认调用方</h2>
 *
 * <p><b>这个包有两个使用者</b>：除了上面那条报站路径，{@code EditRouteScreen} 打开时也用它
 * 回填那个复选框（{@code new PacketCheckRouteIdHasDisabledAnnouncements(data.getId(), this::setDisableNextStationAnnouncements)}）。
 * 要是不加区分地把 {@code true} 改成 {@code false}，<b>仪表板里的勾选框会永远显示未勾选</b>，
 * 用户根本没法知道自己有没有开这个开关。
 *
 * <p>区分办法是看回调是谁家的 lambda。lambda 的类名形如
 * {@code org.mtr.mod.data.VehicleExtension$$Lambda/0x...}，前缀就是定义它的类 ——
 * 这比按 {@code lambda$xxx$6} 这种会随上游改动重新编号的名字注入稳得多（见 README 的注入点约束）。
 * 认不出来就原样放行，退回上游行为，不会误伤。
 */
@Mixin(value = PacketCheckRouteIdHasDisabledAnnouncements.class, remap = false)
public abstract class RouteAnnouncementMuteMixin {

	/** 上游那条报站路径所在的类。回调 lambda 的类名以它开头。 */
	private static final String YUZIMTR_ANNOUNCEMENT_CALLER = "org.mtr.mod.data.VehicleExtension";

	@Redirect(
			method = "runClient",
			at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V")
	)
	private void yuzimtr$muteNarratorButKeepChat(Consumer<Object> consumer, Object isDisabled) {
		if (!Config.get().routeFlagMutesNarratorOnly
				|| !Boolean.TRUE.equals(isDisabled)
				|| !consumer.getClass().getName().startsWith(YUZIMTR_ANNOUNCEMENT_CALLER)) {
			consumer.accept(isDisabled);
			return;
		}

		// 骗上游说「这条交路没关播报」，它才肯把聊天栏文字攒出来；
		// 与此同时把讲述人按住。旗子必须放在 finally 里降下，漏了会让讲述人永久失声。
		NarratorGate.raise();
		try {
			consumer.accept(Boolean.FALSE);
		} finally {
			NarratorGate.lower();
		}
	}
}
