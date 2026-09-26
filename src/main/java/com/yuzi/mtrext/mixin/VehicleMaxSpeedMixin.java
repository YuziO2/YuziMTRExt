package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.SidingMaxSpeedHolder;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.annotation.Nullable;

/**
 * 车实际跑的时候按「车辆最高速」限速。只管<b>自动运行</b>，手动驾驶照旧只受「最高手动速度」约束
 * （那个分支读的是 {@code getMaxManualSpeed}，不经过这里的两处调用）。
 *
 * <p>{@code simulateMoving}（TSC {@code Vehicle.java:421/426}）的自动分支：
 * <pre>
 * upcoming = Siding.getUpcomingSlowerSpeed(...)                      ← 注入点 1
 * if (upcoming >= 0 && upcoming < speed) speedTarget = upcoming * adj
 * else speedTarget = path.get(i).getSpeedLimitMetersPerMillisecond() * adj   ← 注入点 2
 * </pre>
 * 两处在这个方法里各只有一次调用（字节码确认）。
 *
 * <h2>硬上限：追点加速也不许超过</h2>
 *
 * <p>{@code adj} 是 {@code deviationSpeedAdjustment}，晚点时 MTR 用它 >1 地放大目标速度追点。
 * 车辆最高速是车本身的极限，晚点也跑不过它，所以要的是 {@code min(线路 × adj, 上限)}。
 * 注入点只能改 {@code × adj} 之前的那个数，于是返回 {@code min(线路, 上限 / adj)}：
 * 乘回去正好是 {@code min(线路 × adj, 上限)}。
 *
 * <p>第 1 处也要限，不能只限第 2 处：车速 ≤ 上限时「前方更慢」的结果本身不超上限，
 * 但乘上 adj 之后可以超。
 *
 * <h2>从侧线实时读，不拷进车次</h2>
 *
 * <p>MTR 的加速度是发车时拷进 {@code VehicleExtraData} 的，改了只对之后发的车生效。
 * 最高速不跟：我们的「最小刷新」会让在途的车熬过路径刷新继续跑（无限重复的线路上是永远），
 * 拷进去就会出现「时刻表按新上限、老车按旧上限」一直对不上。{@code Vehicle.siding} 本来就持有，
 * 实时读它，改完在途的车立刻生效。
 *
 * <p>客户端不用管：客户端走 {@code isClientside} 分支，直接用服务端同步下来的 {@code speedTarget}。
 */
@Mixin(value = Vehicle.class, remap = false)
public abstract class VehicleMaxSpeedMixin {

	@Shadow
	@Final
	@Nullable
	private Siding siding;

	@Shadow
	private double deviationSpeedAdjustment;

	@Redirect(
			method = "simulateMoving",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/Siding;getUpcomingSlowerSpeed(Lorg/mtr/libraries/it/unimi/dsi/fastutil/objects/ObjectList;IDDD)D")
	)
	private double yuzimtr$capUpcomingSlowerSpeed(ObjectList<PathData> path, int currentIndex, double railProgress, double currentSpeed, double deceleration) {
		final double upcomingSlowerSpeed = Siding.getUpcomingSlowerSpeed(path, currentIndex, railProgress, currentSpeed, deceleration);
		// -1 表示「前方不用减速」，原样返回，交给下面那个分支按当前段限速处理
		return upcomingSlowerSpeed < 0 ? upcomingSlowerSpeed : yuzimtr$capBeforeAdjustment(upcomingSlowerSpeed);
	}

	@Redirect(
			method = "simulateMoving",
			at = @At(value = "INVOKE", target = "Lorg/mtr/core/data/PathData;getSpeedLimitMetersPerMillisecond()D")
	)
	private double yuzimtr$capRailSpeed(PathData pathData) {
		return yuzimtr$capBeforeAdjustment(pathData.getSpeedLimitMetersPerMillisecond());
	}

	/** 返回值随后会被乘上 {@code deviationSpeedAdjustment}，所以先除掉它，乘回去正好是硬上限。 */
	@Unique
	private double yuzimtr$capBeforeAdjustment(double speed) {
		final double cap = SidingMaxSpeedHolder.capMetersPerMillisecond(siding);
		if (cap == Double.POSITIVE_INFINITY) {
			return speed;
		}
		final double adjustment = deviationSpeedAdjustment > 0 ? deviationSpeedAdjustment : 1;
		return Math.min(speed, cap / adjustment);
	}
}
