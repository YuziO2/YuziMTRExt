package com.yuzi.mtrext.gametest;

import com.yuzi.mtrext.station.AnnouncerRule;
import com.yuzi.mtrext.station.RangeFade;
import com.yuzi.mtrext.station.StationAnnouncerBlockEntity;
import com.yuzi.mtrext.station.StationAnnouncers;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class StationAnnouncerGameTest {

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void placedAnnouncerKeepsItsRuleAfterSaving(TestContext context) {
		final BlockPos pos = new BlockPos(1, 1, 1);
		context.setBlockState(pos, StationAnnouncers.BLOCK.get().data);

		final BlockEntity placed = context.getBlockEntity(pos);
		context.assertTrue(placed instanceof StationAnnouncerBlockEntity,
				"placing the station announcer must create its block entity");
		final StationAnnouncerBlockEntity announcer = (StationAnnouncerBlockEntity) placed;

		final AnnouncerRule rule = new AnnouncerRule();
		rule.platformId = 123L;
		rule.routeId = 456L;
		rule.soundId = "yuzimtr:test_announcement";
		rule.onDeparture = true;
		rule.offsetSeconds = -3;
		rule.alignEnd = true;
		rule.durationMillis = 1700;
		announcer.setData(List.of(rule), 2.5F, true, false, null, null);

		final BlockEntity loaded = BlockEntity.createFromNbt(
				context.getAbsolutePos(pos), context.getBlockState(pos), announcer.createNbtWithId());
		context.assertTrue(loaded instanceof StationAnnouncerBlockEntity,
				"the saved block entity must reload as a station announcer");
		final StationAnnouncerBlockEntity restored = (StationAnnouncerBlockEntity) loaded;
		context.assertTrue(restored.getRules().size() == 1, "the saved rule must be restored");
		final AnnouncerRule restoredRule = restored.getRules().get(0);
		context.assertTrue(restoredRule.platformId == 123L && restoredRule.routeId == 456L,
				"the platform and route must survive saving");
		context.assertTrue("yuzimtr:test_announcement".equals(restoredRule.soundId),
				"the sound selection must survive saving");
		context.assertTrue(restoredRule.onDeparture && restoredRule.alignEnd
					&& restoredRule.offsetSeconds == -3 && restoredRule.durationMillis == 1700,
				"the playback timing must survive saving");
		context.assertTrue(restored.getVolume() == 2.5F && restored.getRealtimeOnly(),
				"the speaker settings must survive saving");
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void joinRangeSurvivesSavingAndDefaultsToStation(TestContext context) {
		final BlockPos pos = new BlockPos(1, 1, 1);
		context.setBlockState(pos, StationAnnouncers.BLOCK.get().data);
		final StationAnnouncerBlockEntity announcer = (StationAnnouncerBlockEntity) context.getBlockEntity(pos);
		context.assertTrue(announcer.getJoinMode() == StationAnnouncerBlockEntity.JOIN_STATION,
				"a new announcer must default to the station join range");

		announcer.setData(List.of(), 2, false, true, null, null, StationAnnouncerBlockEntity.JOIN_CUSTOM,
				new org.mtr.mapping.holder.BlockPos(-5, 60, 7), new org.mtr.mapping.holder.BlockPos(40, 80, -20));
		final StationAnnouncerBlockEntity restored = (StationAnnouncerBlockEntity) BlockEntity.createFromNbt(
				context.getAbsolutePos(pos), context.getBlockState(pos), announcer.createNbtWithId());
		context.assertTrue(restored.getJoinMode() == StationAnnouncerBlockEntity.JOIN_CUSTOM, "the custom join mode must survive saving");
		context.assertTrue(restored.getJoinCorner1().getX() == -5 && restored.getJoinCorner1().getY() == 60 && restored.getJoinCorner1().getZ() == 7
						&& restored.getJoinCorner2().getX() == 40 && restored.getJoinCorner2().getY() == 80 && restored.getJoinCorner2().getZ() == -20,
				"the custom join box must survive saving");

		// 1.6.0 及以前存的方块没有 join_mode —— 用户要求老方块也一律按「所在车站」续播
		final NbtCompound oldSave = announcer.createNbtWithId();
		oldSave.remove("join_mode");
		final StationAnnouncerBlockEntity old = (StationAnnouncerBlockEntity) BlockEntity.createFromNbt(
				context.getAbsolutePos(pos), context.getBlockState(pos), oldSave);
		context.assertTrue(old.getJoinMode() == StationAnnouncerBlockEntity.JOIN_STATION, "an old save without join_mode must load as the station join range");

		// 旧客户端发来的保存（不带续播参数）不能把续播设置冲掉
		restored.setData(List.of(), 3, true, true, null, null);
		context.assertTrue(restored.getJoinMode() == StationAnnouncerBlockEntity.JOIN_CUSTOM && restored.getJoinCorner1().getX() == -5,
				"a save from an old client must keep the join range");
		context.complete();
	}

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void rangeModeFadesOutAfterLeavingTheBox(TestContext context) {
		// 10 tick 淡出：在框里一直满格、不会自己停；离框后每 tick 降 0.1，第 10 tick 停
		final RangeFade fade = new RangeFade(10);
		for (int i = 0; i < 100; i++) {
			context.assertFalse(fade.tick(true), "inside the box the sound must never stop on its own");
		}
		context.assertTrue(fade.level() == 1, "inside the box the level must stay at full volume");
		for (int i = 1; i < 10; i++) {
			context.assertFalse(fade.tick(false), "must still be fading after " + i + " ticks outside");
			context.assertTrue(Math.abs(fade.level() - (1 - i * 0.1F)) < 1e-4, "linear fade expected at tick " + i + ", got " + fade.level());
		}
		context.assertTrue(fade.tick(false), "the sound must stop once the fade reaches zero");

		// 淡出途中走回框里：按同样速度升回满格，之后不会停
		final RangeFade back = new RangeFade(10);
		for (int i = 0; i < 4; i++) {
			back.tick(false);
		}
		for (int i = 0; i < 4; i++) {
			context.assertFalse(back.tick(true), "walking back in must not stop the sound");
		}
		context.assertTrue(Math.abs(back.level() - 1) < 1e-4, "walking back in must restore full volume, got " + back.level());

		// 续播从 0 淡入：在框里逐 tick 升到满格、不会停；还没升起来就离框，当 tick 就停
		final RangeFade join = new RangeFade(4, 0);
		for (int i = 1; i <= 4; i++) {
			context.assertFalse(join.tick(true), "a fade-in must not stop while inside");
			context.assertTrue(Math.abs(join.level() - i * 0.25F) < 1e-4, "linear fade-in expected at tick " + i + ", got " + join.level());
		}
		context.assertTrue(new RangeFade(4, 0).tick(false), "a fade-in that starts outside the box must stop at once");

		// 0 ms（1 tick）：离框当 tick 就停
		context.assertTrue(new RangeFade(1).tick(false), "a one-tick fade must stop on the first tick outside");
		context.complete();
	}
}
