package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.DwellTimeApplier;
import com.yuzi.mtrext.SidingMaxSpeedHolder;
import com.yuzi.mtrext.YuziMtrExt;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

/**
 * 仪表板保存之后，把停站时长原地推进已生成的路径，省掉「改完还得刷新路径」。
 *
 * <p>不去分辨到底改了哪个站台 —— {@code UpdateDataResponse.getPlatforms()} 是包级可见，
 * 而 {@code platforms} 那个列表声明在 {@code UpdateDataRequestSchema} 上（{@code @Shadow}
 * 不上溯）。反正整个遍历是幂等的、且只在人点保存时跑一次，直接全量重算一遍最省事。
 * {@code data} 这个字段是 {@code UpdateDataRequest} 自己声明的，所以 {@code @Shadow} 能拿到。
 */
@Mixin(value = UpdateDataRequest.class, remap = false)
public abstract class UpdateDataRequestMixin {

	@Shadow
	@Final
	private Data data;

	// 三处注入都写全描述符：这个类里还有一个 private static update(SerializedDataBase, boolean, …)，
	// 裸名 "update" 匹配的是 class 文件里第一个同名方法，现在恰好是对的，但不该依赖这个顺序。

	/**
	 * 保存前每条侧线的车辆最高速。只在服务端、且开了这个功能时才记，见 {@link #yuzimtr$regenerateIfMaxSpeedChanged}。
	 */
	@Unique
	@Nullable
	private Long2LongOpenHashMap yuzimtr$maxSpeedsBefore;

	@Inject(method = "update()Lorg/mtr/core/operation/UpdateDataResponse;", at = @At("HEAD"))
	private void yuzimtr$rememberMaxSpeeds(CallbackInfoReturnable<UpdateDataResponse> cir) {
		if (!Config.get().vehicleMaxSpeed || !(data instanceof Simulator)) {
			return;
		}
		yuzimtr$maxSpeedsBefore = new Long2LongOpenHashMap();
		for (final Siding siding : data.sidings) {
			yuzimtr$maxSpeedsBefore.put(siding.getId(), ((SidingMaxSpeedHolder) (Object) siding).yuzimtr$getMaxSpeedKmh());
		}
	}

	@Inject(method = "update()Lorg/mtr/core/operation/UpdateDataResponse;", at = @At("RETURN"))
	private void yuzimtr$applyDwellTimeInPlace(CallbackInfoReturnable<UpdateDataResponse> cir) {
		if (Config.get().applyDwellTimeWithoutRefresh && data instanceof Simulator) {
			DwellTimeApplier.applyAll((Simulator) data);
		}
	}

	/**
	 * 车辆最高速变了，就重新生成它所在车厂的路径 —— 也就是替用户点一下「刷新路线」。
	 *
	 * <p>必须重新生成：时刻表（{@code timeSegments} / {@code platformTripStopTimes}，PIDS 的来源）
	 * 只在生成路径时推算，而保存侧线只是 {@code existingData.updateData(...)} 原地改字段
	 * （{@code UpdateDataRequest.update}），<b>不会</b>重推。MTR 自己改加速度也是这样，要手动刷新。
	 * 单独重推一条侧线的时刻表不行：{@code generatePathDistancesAndTimeSegments} 重复调用不幂等
	 * （每调一次从 {@code pathMainRoute} 往 {@code pathSidingToMainRoute} 搬一格）。
	 *
	 * <p>调的就是「刷新路线」按钮的后端 {@code Depot.generateDepots}（{@code DepotOperationByIds.generate}
	 * 调的也是它），和保存在同一个模拟器线程上，异步生成、完成后照常给客户端发生成状态。
	 * 开着「最小刷新」时路径几何没变就不清车；关着的话会像手动刷新一样清掉这个车厂的车。
	 *
	 * <p>在途的车不用等这次刷新：它们从侧线上<b>实时</b>读最高速（见 {@link VehicleMaxSpeedMixin}），
	 * 保存那一刻就已经按新上限跑了。刷新只是让时刻表跟上。
	 */
	@Inject(method = "update()Lorg/mtr/core/operation/UpdateDataResponse;", at = @At("RETURN"))
	private void yuzimtr$regenerateIfMaxSpeedChanged(CallbackInfoReturnable<UpdateDataResponse> cir) {
		final Long2LongOpenHashMap before = yuzimtr$maxSpeedsBefore;
		yuzimtr$maxSpeedsBefore = null;
		if (before == null) {
			return;
		}

		final ObjectArrayList<Depot> depotsToGenerate = new ObjectArrayList<>();
		for (final Siding siding : data.sidings) {
			// 这次保存新建的侧线不在快照里，跳过 —— 它们会走自己的初始化。
			if (!before.containsKey(siding.getId())) {
				continue;
			}
			final long now = ((SidingMaxSpeedHolder) (Object) siding).yuzimtr$getMaxSpeedKmh();
			final long was = before.get(siding.getId());
			if (now != was && siding.area instanceof Depot && !depotsToGenerate.contains((Depot) siding.area)) {
				depotsToGenerate.add((Depot) siding.area);
				YuziMtrExt.LOGGER.info("[车辆最高速] 侧线 {} 从 {} 改成 {} km/h（0 = 不限），重新生成车厂「{}」的路径",
						siding.getName(), was, now, siding.area.getName());
			}
		}
		if (!depotsToGenerate.isEmpty()) {
			Depot.generateDepots((Simulator) data, depotsToGenerate);
		}
	}
}
