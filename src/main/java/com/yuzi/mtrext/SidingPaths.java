package com.yuzi.mtrext;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * 把 Siding 的三段路径暴露出来。它们都声明在 Siding 自己身上，所以
 * {@link com.yuzi.mtrext.mixin.SidingMinimalRefreshMixin} 里 {@code @Shadow} 得到，
 * 只是包外拿不到，用鸭子接口转出来。
 */
public interface SidingPaths {

	ObjectArrayList<PathData> yuzimtr$pathSidingToMainRoute();

	ObjectArrayList<PathData> yuzimtr$pathMainRoute();

	ObjectArrayList<PathData> yuzimtr$pathMainRouteToSiding();

	/**
	 * 在跑的车次。改停站时间要连它们手上那份路径快照一起改 ——
	 * {@code Siding.iterateVehiclesAndRidingEntities} 只给得到载着人的车，不够用。
	 */
	ObjectArraySet<Vehicle> yuzimtr$vehicles();

	/** 发车表，只读，给发车诊断用。 */
	LongArrayList yuzimtr$departures();
}
