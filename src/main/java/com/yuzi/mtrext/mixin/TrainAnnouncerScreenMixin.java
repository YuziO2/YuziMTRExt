package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.announcer.PacketAllRoutes;
import com.yuzi.mtrext.client.YuziMtrExtClient;
import org.mtr.mod.screen.TrainAnnouncerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 广播器界面一打开就去要全量交路，见 {@link PacketAllRoutes}。
 *
 * <p>取数是异步的（客户端 → 服务端 → TSC 模拟器线程 → 服务端 → 客户端），所以要趁早发。
 * 玩家打开界面之后还要再点一下「交路」按钮才会用到这份数据，中间隔着至少一次点击的时间，
 * 一轮往返足够了。万一还没回来，{@link DashboardListSelectorScreenMixin} 会退回上游原本的
 * 列表 —— 退化成现在的行为，不会报错也不会空列表；再打开一次就有了。
 *
 * <p>每次打开都重新取，免得中途在仪表板里改了交路之后这里还是旧的。
 *
 * <p>这个类只有一个构造函数（javap 确认），所以 {@code method = "<init>"} 无歧义。
 */
@Mixin(value = TrainAnnouncerScreen.class, remap = false)
public abstract class TrainAnnouncerScreenMixin {

	@Inject(method = "<init>", at = @At("RETURN"))
	private void yuzimtr$requestAllRoutes(CallbackInfo callbackInfo) {
		if (Config.get().announcerAllRoutes) {
			YuziMtrExtClient.REGISTRY_CLIENT.sendPacketToServer(new PacketAllRoutes());
		}
	}
}
