package com.yuzi.mtrext;

import com.yuzi.mtrext.announcer.PacketAllRoutes;
import com.yuzi.mtrext.psd.RopeBarriers;
import com.yuzi.mtrext.station.PacketOpenStationAnnouncerScreen;
import com.yuzi.mtrext.station.PacketUpdateStationAnnouncer;
import com.yuzi.mtrext.station.StationAnnouncers;
import net.fabricmc.api.ModInitializer;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.registry.Registry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * yuzi 的 MTR 扩展 —— 用 mixin 给 MTR 4.0.5 打补丁，把 MTR3 时代好用的东西补回来。
 *
 * <p>路线图和每条修复对应的上游 bug 见 README.md。
 *
 * <p>没有自己的存档文件：分交路停站时长挂在 MTR 自己的 Platform 数据上
 * （见 {@link PlatformDwellOverrides}），跟着 {@code platforms/*.msgpack} 走。
 */
public class YuziMtrExt implements ModInitializer {

	public static final String MOD_ID = "yuzimtr";
	public static final Logger LOGGER = LoggerFactory.getLogger("YuziMTRExt");

	/**
	 * 全模组共用一个 Registry，最后统一 {@code init()} —— 照 MTR 自己
	 * {@code Init.REGISTRY} 的做法。客户端那侧的 {@code RegistryClient} 也包这一个，
	 * 见 {@link com.yuzi.mtrext.client.YuziMtrExtClient}。
	 */
	public static final Registry REGISTRY = new Registry();

	@Override
	public void onInitialize() {
		final Config config = Config.get();
		RailConnectors.register(REGISTRY);
		RopeBarriers.register(REGISTRY);
		StationAnnouncers.register(REGISTRY);
		// 广播器的全量交路通道。
		REGISTRY.registerPacket(PacketAllRoutes.class, PacketAllRoutes::new);
		// 站台广播器：开界面（S2C）和存配置（C2S）。
		REGISTRY.registerPacket(PacketOpenStationAnnouncerScreen.class, PacketOpenStationAnnouncerScreen::new);
		REGISTRY.registerPacket(PacketUpdateStationAnnouncer.class, PacketUpdateStationAnnouncer::new);
		// registerPacket 只是往一张按类名索引的表里塞条目，真正开通道的是 setupPackets ——
		// 它设置 packetsIdentifier 并注册服务端的 C2S 接收器。**Registry.init() 不会替你调它**
		// （字节码确认：init() 只 forEach objectsToRegister 再注册一个命令回调）。
		// 不调的后果是静默的：RegistryClient.sendPacketToServer 开头就是
		// `getfield packetsIdentifier; ifnull` 直接 return，包根本发不出去，不报错也无日志。
		// 客户端那半边在 YuziMtrExtClient 里，两边都要调，用同一个 id。
		REGISTRY.setupPackets(new Identifier(MOD_ID, "packet"));
		REGISTRY.init();
		LOGGER.info("yuzi 的 MTR 扩展已加载");
		LOGGER.info("  车辆模型常驻内存        : {}", config.keepVehicleModelsResident);
		LOGGER.info("  按真实编组预热模型缓存  : {}", config.preloadRealConsistBuckets);
		LOGGER.info("  分交路停站时长          : {}", config.perRouteDwellTime);
		LOGGER.info("  升降式绳索屏蔽门（实验）: {}", config.ropeBarrier);
		LOGGER.info("  广播器可选全部交路      : {}", config.announcerAllRoutes);
		LOGGER.info("  站台广播器              : 已注册");
		LOGGER.info("  侧线车辆最高速          : {}", config.vehicleMaxSpeed);
	}
}
