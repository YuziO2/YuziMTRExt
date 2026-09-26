package com.yuzi.mtrext.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.data.PersistentVehicleData;

import java.nio.file.Path;

public class MtrVehicleDoorSignalGameTest {

	@GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
	public void realMtrVehicleOpensAndClosesDoorsAtPlatform(TestContext context) {
		final Simulator simulator = new Simulator("gametest", new String[]{"gametest"},
				Path.of("build", "mtr-gametest"), false);
		final Position sidingStart = new Position(0, 0, 0);
		final Position platformStart = new Position(20, 0, 0);
		final Position platformEnd = new Position(60, 0, 0);
		final Position sidingEnd = new Position(80, 0, 0);
		final ObjectArrayList<String> styles = new ObjectArrayList<>();
		final PathData entry = path(sidingStart, platformStart, 0, 0, 20, styles);
		final PathData platform = path(platformStart, platformEnd, 20_000, 20, 60, styles);
		final PathData exit = path(platformEnd, sidingEnd, 0, 60, 80, styles);
		final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
		cars.add(new VehicleCar("mtr:train_1", 8, 3, 2, -2, 0, 0));
		final VehicleExtraData extraData = VehicleExtraData.create(1, 2, 20, cars,
				list(entry), list(platform), list(exit), entry, false,
				1, 1, false, 0.01, 0);
		final Vehicle vehicle = new Vehicle(extraData, null, TransportMode.TRAIN, simulator);
		final PersistentVehicleData doorState = new PersistentVehicleData(
				extraData.immutableVehicleCars, TransportMode.TRAIN);

		context.assertTrue(extraData.getDoorMultiplier() < 0, "the MTR train starts with doors closed");
		vehicle.simulate(50, null, null);
		vehicle.startUp(0, 0);
		context.assertTrue(vehicle.getIsOnRoute(), "the MTR train must depart the siding");

		boolean openingSignal = false;
		boolean doorOpened = false;
		boolean closingSignal = false;
		boolean doorClosed = false;
		double openingX = Double.NaN;
		for (int i = 0; i < 10_000; i++) {
			vehicle.simulate(50, null, null);
			if (extraData.getDoorMultiplier() > 0) {
				if (!openingSignal) {
					openingX = vehicle.getHeadPosition().x();
				}
				openingSignal = true;
			} else if (openingSignal) {
				closingSignal = true;
			}
			doorState.tick(0, 50, extraData);
			if (doorState.getDoorValue() >= 0.9) {
				doorOpened = true;
			}
			if (closingSignal && doorState.getDoorValue() == 0) {
				doorClosed = true;
				break;
			}
		}
		context.assertTrue(openingSignal, "MTR must emit an open-door signal at the platform; vehicle="
				+ vehicle + "; extra=" + extraData);
		context.assertTrue(openingX >= platformStart.getX() && openingX <= platformEnd.getX() + 1,
				"the opening signal must occur while the train is at the platform; x=" + openingX);
		context.assertTrue(doorOpened, "MTR must animate the vehicle doors open");
		context.assertTrue(closingSignal, "MTR must emit a close-door signal before departure");
		context.assertTrue(doorClosed, "MTR must animate the vehicle doors closed");
		context.complete();
	}

	private static PathData path(Position start, Position end, long dwellTime,
	                             double startDistance, double endDistance,
	                             ObjectArrayList<String> styles) {
		final Rail rail = dwellTime > 0
				? Rail.newPlatformRail(start, Angle.E, end, Angle.E,
					Rail.Shape.QUADRATIC, 80, styles, TransportMode.TRAIN)
				: Rail.newSidingRail(start, Angle.E, end, Angle.E,
					Rail.Shape.QUADRATIC, 80, styles, TransportMode.TRAIN);
		return new PathData(new PathData(rail, 0, dwellTime, 0, start, end),
				startDistance, endDistance);
	}

	private static <T> ObjectArrayList<T> list(T value) {
		final ObjectArrayList<T> result = new ObjectArrayList<>();
		result.add(value);
		return result;
	}
}
