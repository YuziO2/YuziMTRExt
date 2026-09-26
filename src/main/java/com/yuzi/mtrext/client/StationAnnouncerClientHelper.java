package com.yuzi.mtrext.client;

import org.mtr.mapping.holder.BlockEntity;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Screen;

/**
 * 把「开界面」这一步从数据包类里隔出来。
 *
 * <p>数据包是在服务端注册的，类里一旦出现界面类型，专用服务端在<b>类验证</b>阶段就会炸 ——
 * MTR 自己的 {@code ClientPacketHelper} 类注释写得很直白。靠 JVM 惰性类加载隔离：
 * {@code runClient()} 在服务端永远不执行，这个类也就永远不会被加载。
 */
public final class StationAnnouncerClientHelper {

	private StationAnnouncerClientHelper() {
	}

	public static void openScreen(BlockPos blockPos) {
		final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
		if (clientWorld == null) {
			return;
		}
		final BlockEntity blockEntity = clientWorld.getBlockEntity(blockPos);
		if (blockEntity != null && blockEntity.data instanceof com.yuzi.mtrext.station.StationAnnouncerBlockEntity) {
			MinecraftClient.getInstance().openScreen(new Screen(new StationAnnouncerScreen(
					blockPos, (com.yuzi.mtrext.station.StationAnnouncerBlockEntity) blockEntity.data)));
		}
	}
}
