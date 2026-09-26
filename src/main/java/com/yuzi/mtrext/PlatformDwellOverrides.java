package com.yuzi.mtrext;

import java.util.Map;

/**
 * 鸭子类型接口，由 {@link com.yuzi.mtrext.mixin.PlatformDwellMixin} 实现在
 * {@code org.mtr.core.generated.data.PlatformSchema} 上，所以每个 {@code Platform}
 * 实例都带着一张「交路 id → 停站毫秒」的表。
 *
 * <p>为什么挂在 Platform 上而不是另存一个文件：
 * <ul>
 * <li>Platform 本来就是「停站时长」的归属者（platform.json 里就一个 dwellTime），
 *     分交路只是把它从一个值扩成一张表；</li>
 * <li>它随 MTR 自己的 {@code platforms/*.msgpack} 落盘，不用第二份存档；</li>
 * <li>客户端仪表板本来就整个 Platform 收发（PlatformScreen.onClose2 发
 *     {@code UpdateDataRequest.addPlatform}），所以 UI 不需要任何自定义包。</li>
 * </ul>
 *
 * <p>注意不能挂在 {@code PathData} 上：那是路径生成的产物，
 * {@code Depot.generateMainRoute} 每次都 {@code path.clear()}（Depot.java:359）
 * 再从 {@code platform.getDwellTime()} 重算，写进去的值下一次生成就没了。
 * 覆盖值必须是生成的**输入**。
 */
public interface PlatformDwellOverrides {

	/** 写进 MTR msgpack / JSON 的键名，加前缀避免和上游将来的字段撞名。 */
	String KEY_DWELL_OVERRIDES = "yuzimtrDwellOverrides";
	String KEY_ROUTE_ID = "routeId";
	String KEY_DWELL_TIME = "dwellTime";

	/** @return 该交路的自定义停站毫秒；{@code null} 表示跟随站台总值（真正的继承）。 */
	Long yuzimtr$getDwellOverride(long routeId);

	void yuzimtr$setDwellOverride(long routeId, long millis);

	void yuzimtr$clearDwellOverride(long routeId);

	/** 只读视图，给 UI 和调试用。 */
	Map<Long, Long> yuzimtr$getDwellOverrides();
}
