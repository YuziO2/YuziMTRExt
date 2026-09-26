package com.yuzi.mtrext.station;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;

/**
 * 服务端 → 客户端：打开站台广播器的配置界面。
 *
 * <p><b>这个类不许直接引用界面类。</b>数据包是在服务端注册的，专用服务端上根本没有
 * {@code net.minecraft.client.*}；一旦在这里 import 了界面，类验证阶段就会炸。
 * 解法照抄 MTR 的 {@code ClientPacketHelper}（它类注释里写得很直白：
 * <i>"Since packets are registered serverside, they will fail if any client classes are used"</i>）——
 * 把客户端那一步推进另一个类，靠 JVM 的<b>惰性类加载</b>隔离：
 * {@code runClient()} 在服务端永远不会执行，那个类也就永远不会被加载。
 */
public final class PacketOpenStationAnnouncerScreen extends PacketHandler {

	private final BlockPos blockPos;

	public PacketOpenStationAnnouncerScreen(PacketBufferReceiver packetBufferReceiver) {
		blockPos = BlockPos.fromLong(packetBufferReceiver.readLong());
	}

	public PacketOpenStationAnnouncerScreen(BlockPos blockPos) {
		this.blockPos = blockPos;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(blockPos.asLong());
	}

	@Override
	public void runClient() {
		com.yuzi.mtrext.client.StationAnnouncerClientHelper.openScreen(blockPos);
	}
}
