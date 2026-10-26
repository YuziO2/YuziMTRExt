package com.yuzi.mtrext.client;

import com.yuzi.mtrext.YuziMtrExt;
import com.yuzi.mtrext.announcer.AllRoutesCache;
import com.yuzi.mtrext.psd.RopeBarriers;
import net.fabricmc.api.ClientModInitializer;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.registry.RegistryClient;

/**
 * 客户端入口。只负责注册方块实体渲染器 —— 照 MTR 自己
 * {@code InitClient.REGISTRY_CLIENT = new RegistryClient(Init.REGISTRY)} 的写法，
 * 包一层共享的 {@link YuziMtrExt#REGISTRY}，然后各自 init。
 */
public class YuziMtrExtClient implements ClientModInitializer {

	public static final RegistryClient REGISTRY_CLIENT = new RegistryClient(YuziMtrExt.REGISTRY);

	@Override
	public void onInitializeClient() {
		REGISTRY_CLIENT.registerBlockEntityRenderer(RopeBarriers.ROPE_ENTITY_TYPE, RenderRopeBarrier::new);
		REGISTRY_CLIENT.registerBlockEntityRenderer(RopeBarriers.POST_ENTITY_TYPE, RenderRopeBarrierPost::new);
		// 和主入口那次 setupPackets 配对：那边开的是服务端收 C2S，这边开的是客户端收 S2C
		// （ClientPlayNetworking.registerGlobalReceiver）。id 必须和主入口一致。
		REGISTRY_CLIENT.setupPackets(new Identifier(YuziMtrExt.MOD_ID, "packet"));
		// 站台广播器限制范围的「离框淡出 + 续播」走单独的 Fabric 通道，服务端靠它判断这个客户端支不支持
		RangedAnnouncerPlayback.register();
		// 换世界/重连时清掉广播器的全量交路缓存，免得在新世界的答复回来之前
		// 拿上一个世界的交路。MTR 自己的 MinecraftClientData.reset() 也挂在这个事件上。
		REGISTRY_CLIENT.eventRegistryClient.registerClientJoin(AllRoutesCache::clear);
		REGISTRY_CLIENT.init();
		YuziMtrExt.LOGGER.info("绳索屏蔽门渲染器已注册（绳索 + 立柱）");
	}
}
