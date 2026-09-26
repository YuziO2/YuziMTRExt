package com.yuzi.mtrext.station;

import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.mapping.holder.Block;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.registry.BlockEntityTypeRegistryObject;
import org.mtr.mapping.registry.BlockRegistryObject;
import org.mtr.mapping.registry.Registry;
import org.mtr.mod.CreativeModeTabs;

/**
 * 站台广播器的注册。
 *
 * <p>和 {@link com.yuzi.mtrext.psd.RopeBarriers} 一样，<b>服务端也会加载这个类</b>，
 * 所以里面不能出现任何客户端专有类型。
 */
public final class StationAnnouncers {

	private StationAnnouncers() {
	}

	public static BlockRegistryObject BLOCK;
	public static BlockEntityTypeRegistryObject<StationAnnouncerBlockEntity> ENTITY_TYPE;

	public static void register(Registry registry) {
		BLOCK = registry.registerBlockWithBlockItem(
				new Identifier(YuziMtrExt.MOD_ID, "station_announcer"),
				() -> new Block(new BlockStationAnnouncer()),
				CreativeModeTabs.RAILWAY_FACILITIES
		);
		ENTITY_TYPE = registry.registerBlockEntityType(
				new Identifier(YuziMtrExt.MOD_ID, "station_announcer"),
				(pos, state) -> new StationAnnouncerBlockEntity(ENTITY_TYPE.get(), pos, state),
				BLOCK::get
		);
	}
}
