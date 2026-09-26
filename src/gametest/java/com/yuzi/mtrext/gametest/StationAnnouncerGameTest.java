package com.yuzi.mtrext.gametest;

import com.yuzi.mtrext.station.AnnouncerRule;
import com.yuzi.mtrext.station.StationAnnouncerBlockEntity;
import com.yuzi.mtrext.station.StationAnnouncers;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.entity.BlockEntity;
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
}
