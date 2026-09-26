package com.yuzi.mtrext.mixin;

import com.yuzi.mtrext.PlatformDwellOverrides;
import org.mtr.core.generated.data.PlatformSchema;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.WriterBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把「分交路停站时长」存进 MTR 自己的 Platform 数据里。
 *
 * <p>目标是生成类 {@code PlatformSchema} 而不是 {@code Platform}：
 * {@code Platform} 并不声明 updateData/serializeData（字节码核对过，它只有
 * setDwellTime/getDwellTime/setAngles/getStationName/getOBAStopElement），
 * 两个方法都在 schema 上。{@code Platform extends PlatformSchema}，
 * 所以接口照样落在每个 Platform 实例上。
 *
 * <p>为什么注 {@code serializeData} 而不是 {@code serializeFullData}：
 * {@code SerializedDataBase.serializeFullData} 默认就是调 {@code serializeData}，
 * 而且客户端↔服务端那条路走的是 {@code Utilities.getJsonObjectFromData}
 * （Utilities.java:164，明确调 serializeData）。注在 serializeData 上，
 * 落盘和联网两条路一起覆盖。
 *
 * <p>数组**总是**写出去，哪怕是空的。因为 {@code ReaderBase.iterateReaderArray}
 * 只在键存在时才跑 clearList（JsonReader.java:106 的 unpack 语义），
 * 键缺失就等于「没提到这个字段」，服务端不会清空 —— 那样用户在 UI 里把
 * 最后一条覆盖删掉就永远删不掉了。
 *
 * <p>上游不认识这个键：msgpack/json 两个 reader 都是按键查表，多余的键直接忽略，
 * 所以没装本 mod 的客户端/服务端能正常读这份存档（只是它保存时会把键丢掉）。
 */
@Mixin(value = PlatformSchema.class, remap = false)
public abstract class PlatformDwellMixin implements PlatformDwellOverrides {

	@Unique
	private Map<Long, Long> yuzimtr$dwellOverrides;

	@Override
	public Long yuzimtr$getDwellOverride(long routeId) {
		return yuzimtr$dwellOverrides == null ? null : yuzimtr$dwellOverrides.get(routeId);
	}

	@Override
	public void yuzimtr$setDwellOverride(long routeId, long millis) {
		if (yuzimtr$dwellOverrides == null) {
			yuzimtr$dwellOverrides = new LinkedHashMap<>();
		}
		yuzimtr$dwellOverrides.put(routeId, millis);
	}

	@Override
	public void yuzimtr$clearDwellOverride(long routeId) {
		if (yuzimtr$dwellOverrides != null) {
			yuzimtr$dwellOverrides.remove(routeId);
		}
	}

	@Override
	public Map<Long, Long> yuzimtr$getDwellOverrides() {
		return yuzimtr$dwellOverrides == null ? Collections.emptyMap() : Collections.unmodifiableMap(yuzimtr$dwellOverrides);
	}

	@Inject(method = "updateData(Lorg/mtr/core/serializer/ReaderBase;)V", at = @At("TAIL"))
	private void yuzimtr$readDwellOverrides(ReaderBase readerBase, CallbackInfo ci) {
		readerBase.iterateReaderArray(
				KEY_DWELL_OVERRIDES,
				() -> {
					if (yuzimtr$dwellOverrides != null) {
						yuzimtr$dwellOverrides.clear();
					}
				},
				child -> {
					final long routeId = child.getLong(KEY_ROUTE_ID, 0);
					final long millis = child.getLong(KEY_DWELL_TIME, 0);
					if (routeId != 0 && millis > 0) {
						yuzimtr$setDwellOverride(routeId, millis);
					}
				}
		);
	}

	@Inject(method = "serializeData(Lorg/mtr/core/serializer/WriterBase;)V", at = @At("TAIL"))
	private void yuzimtr$writeDwellOverrides(WriterBase writerBase, CallbackInfo ci) {
		final WriterBase.Array array = writerBase.writeArray(KEY_DWELL_OVERRIDES);
		if (yuzimtr$dwellOverrides != null) {
			yuzimtr$dwellOverrides.forEach((routeId, millis) -> {
				final WriterBase child = array.writeChild();
				child.writeLong(KEY_ROUTE_ID, routeId);
				child.writeLong(KEY_DWELL_TIME, millis);
			});
		}
	}
}
