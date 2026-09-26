package com.yuzi.mtrext.psd;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.mapping.holder.Block;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.BlockState;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.registry.BlockEntityTypeRegistryObject;
import org.mtr.mapping.registry.BlockRegistryObject;
import org.mtr.mapping.registry.Registry;
import org.mtr.mapping.registry.SoundEventRegistryObject;
import org.mtr.mod.CreativeModeTabs;
import org.mtr.mod.block.IBlock;

/**
 * 绳索屏蔽门的注册。
 *
 * <p><b>这个类服务端也会加载</b>（主入口调 {@link #register}），所以里面不能出现
 * 任何客户端专有类型 —— {@code org.mtr.mapping.holder.ClientWorld} 包的是
 * {@code net.minecraft.class_638}，专用服务端根本没有这个类。「沿排推值」因此拆到了
 * {@link com.yuzi.mtrext.client.RopeBarrierFlood}。
 *
 */
public final class RopeBarriers {

	private RopeBarriers() {
	}

	public static BlockRegistryObject ROPE;
	public static BlockRegistryObject POST;
	public static BlockRegistryObject POST_BLANK;
	public static BlockEntityTypeRegistryObject<RopeBarrierBlockEntity> ROPE_ENTITY_TYPE;
	public static BlockEntityTypeRegistryObject<RopeBarrierBlockEntity> POST_ENTITY_TYPE;
	public static SoundEventRegistryObject SOUND_OPEN;
	public static SoundEventRegistryObject SOUND_CLOSE;

	/** 和 TSC {@code Vehicle.java:48} 的 DOOR_DELAY 一致。那个常量是 private 的，只能照抄。 */
	public static final int DOOR_DELAY = 1000;

	/** 车门开始关闭时，距发车还有多少毫秒。 */
	public static final int MILLIS_BEFORE_DEPARTURE_DOORS_CLOSE =
			org.mtr.core.data.Vehicle.DOOR_MOVE_TIME + DOOR_DELAY;

	public static void register(Registry registry) {
		ROPE = registry.registerBlockWithBlockItem(
				new Identifier(YuziMtrExt.MOD_ID, "rope_barrier"),
				() -> new Block(new BlockRopeBarrier()),
				CreativeModeTabs.RAILWAY_FACILITIES
		);
		POST = registry.registerBlockWithBlockItem(
				new Identifier(YuziMtrExt.MOD_ID, "rope_barrier_post"),
				() -> new Block(new BlockRopeBarrierPost(true)),
				CreativeModeTabs.RAILWAY_FACILITIES
		);
		POST_BLANK = registry.registerBlockWithBlockItem(
				new Identifier(YuziMtrExt.MOD_ID, "rope_barrier_post_blank"),
				() -> new Block(new BlockRopeBarrierPost(false)),
				CreativeModeTabs.RAILWAY_FACILITIES
		);
		ROPE_ENTITY_TYPE = registry.registerBlockEntityType(
				new Identifier(YuziMtrExt.MOD_ID, "rope_barrier"),
				(pos, state) -> new RopeBarrierBlockEntity(ROPE_ENTITY_TYPE.get(), pos, state),
				ROPE::get
		);
		POST_ENTITY_TYPE = registry.registerBlockEntityType(
				new Identifier(YuziMtrExt.MOD_ID, "rope_barrier_post"),
				(pos, state) -> new RopeBarrierBlockEntity(POST_ENTITY_TYPE.get(), pos, state),
				POST::get, POST_BLANK::get
		);
		SOUND_OPEN = registry.registerSoundEvent(new Identifier(YuziMtrExt.MOD_ID, "psda_open"));
		SOUND_CLOSE = registry.registerSoundEvent(new Identifier(YuziMtrExt.MOD_ID, "psda_close"));
	}

	/** 这排绳网的走向：垂直于 FACING 的那条水平轴。绳索和立柱都有 FACING，所以两者都适用。 */
	public static Direction rowDirection(BlockState blockState) {
		return IBlock.getStatePropertySafe(blockState, DirectionHelper.FACING).rotateYClockwise();
	}
}
