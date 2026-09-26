package com.yuzi.mtrext.client;

/**
 * 一个只在「生成下一站报站」那一小段同步调用期间举起的旗子，用来让讲述人闭嘴而不影响聊天栏。
 *
 * <p>存在的理由见 {@link com.yuzi.mtrext.mixin.RouteAnnouncementMuteMixin}：交路上那个
 * 「关掉下一站播报」原本把聊天栏文字和讲述人语音<b>一起</b>关掉，我们要把它改成只关语音。
 * 上游生成这两样东西的代码在同一个 lambda 里、最后一起交给
 * {@code IDrawing.narrateOrAnnounce(narrateMessage, chatMessages)}，而那个方法本身已经支持
 * 「只发聊天不念」（{@code narrateMessage} 为空就跳过 {@code Narrator.say}）—— 只是它读的是
 * MTR 自己的全局开关 {@code Config.getClient().getTextToSpeechAnnouncements()}。
 *
 * <p>所以做法是：放行上游那段代码照常跑（聊天栏文字因此照常生成），同时在这段期间让那个
 * 全局开关读出 {@code false}。旗子举起的窗口就是一次 {@code Consumer.accept} 调用，
 * 期间唯一会读这个开关的就是 {@code narrateOrAnnounce} 本身。
 *
 * <p>{@code IDrawing} 是接口、{@code narrateOrAnnounce} 是它的静态方法，直接注入接口静态方法
 * 在 mixin 里限制多；改为拦一个普通类的 getter，注入点稳得多。
 *
 * <p><b>必须成对使用，而且 {@link #lower()} 要放在 finally 里。</b>
 * 旗子漏举会让讲述人永久失声 —— 那是个很难排查的故障。
 */
public final class NarratorGate {

	private NarratorGate() {
	}

	private static boolean raised;

	public static void raise() {
		raised = true;
	}

	public static void lower() {
		raised = false;
	}

	public static boolean isRaised() {
		return raised;
	}
}
