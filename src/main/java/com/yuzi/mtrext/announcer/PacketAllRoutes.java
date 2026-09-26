package com.yuzi.mtrext.announcer;

import com.yuzi.mtrext.Config;
import com.yuzi.mtrext.YuziMtrExt;
import com.yuzi.mtrext.mixin.ListDataResponseAccessor;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.registry.PacketHandler;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;

import java.util.Random;

/**
 * 给广播器取全量交路的专用通道 —— 不受半径限制，也包含隐藏交路。
 *
 * <h2>上游为什么不够用</h2>
 *
 * <p>选择器原本读的是客户端的 {@code MinecraftClientData.simplifiedRoutes}，而那份数据是
 * 服务端按两道关卡筛出来的（TSC）：
 * <ul>
 * <li>{@code DataRequest.addPlatform} —— 只有落在客户端请求半径内的站台才会带出它的交路，
 *     而且 {@code if (!route.getHidden() && ...)}；</li>
 * <li>{@code SimplifiedRoute.addToList} —— 再过滤一次 {@code if (!route.getHidden())}。</li>
 * </ul>
 * 所以「只能选附近的交路」「选不到隐藏交路」是同一份数据的两个后果。
 *
 * <h2>为什么不直接放宽那两道关卡</h2>
 *
 * <p>因为 {@code simplifiedRoutes} 不是广播器专用的。{@code RouteMapGenerator.getRouteStream}
 * 只按「这条交路停不停本站台」筛（{@code getPlatformIndex(platformId) >= 0}），
 * {@code SimplifiedRoute} 本身<b>又没有 hidden 字段</b>，客户端根本分不出来 ——
 * 一旦把隐藏交路灌进去，它们就会出现在途经站台的线路图和 PIDS 上。所以另开这条通道，
 * MTR 那份共享数据一个字节都不动。
 *
 * <h2>怎么取到全量</h2>
 *
 * <p>MTR 的模组侧并不持有 TSC 的 {@code Simulator}，一切都要经
 * {@code Init.sendMessageC2S(操作, ...)} 转给模拟器线程。现成的 {@code LIST_DATA} 正好合用 ——
 * 它落到 {@code new ListDataResponse(jsonReader, simulator).list()}，而 {@code list()} 里是
 * {@code routes.addAll(data.routes)}：<b>全量、不过滤隐藏、不看半径</b>，仪表板用的就是它。
 *
 * <p>请求体的内容会被 {@code ListDataResponse} 的 schema 忽略（它只认自己那几个键），
 * 所以送一个空的 {@link SerializedDataBase} 即可。回程是<b>进程内直接传对象</b>、不走序列化
 * （{@code QueueObject.runCallback} 只做一次 {@code isInstance} 转型），
 * 所以拿到的就是带着真 {@code Route} 的那个响应。
 *
 * <p>回调在服务端主线程上跑（{@code sendMessageC2S} 里包了 {@code minecraftServer.execute}），
 * 发包安全。
 */
public final class PacketAllRoutes extends PacketHandler {

	private final long callbackId;
	private final ObjectArrayList<RouteEntry> routes;

	/** 请求体，内容会被忽略，只是 LIST_DATA 需要一个 data 参数。 */
	private static final SerializedDataBase EMPTY_REQUEST = new SerializedDataBase() {
		@Override
		public void updateData(ReaderBase readerBase) {
		}

		@Override
		public void serializeData(WriterBase writerBase) {
		}
	};

	/** 客户端发起请求：只带一个回执号。 */
	public PacketAllRoutes() {
		callbackId = new Random().nextLong();
		routes = new ObjectArrayList<>();
	}

	/** 反序列化（两个方向共用这一个构造函数）。 */
	public PacketAllRoutes(PacketBufferReceiver packetBufferReceiver) {
		callbackId = packetBufferReceiver.readLong();
		final int count = packetBufferReceiver.readInt();
		routes = new ObjectArrayList<>();
		for (int i = 0; i < count; i++) {
			final long id = packetBufferReceiver.readLong();
			final String name = packetBufferReceiver.readString();
			final int color = packetBufferReceiver.readInt();
			final int platformCount = packetBufferReceiver.readInt();
			final LongArrayList platformIds = new LongArrayList();
			for (int j = 0; j < platformCount; j++) {
				platformIds.add(packetBufferReceiver.readLong());
			}
			routes.add(new RouteEntry(id, name, color, platformIds));
		}
	}

	/** 服务端回程。 */
	private PacketAllRoutes(long callbackId, ObjectArrayList<RouteEntry> routes) {
		this.callbackId = callbackId;
		this.routes = routes;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		packetBufferSender.writeLong(callbackId);
		packetBufferSender.writeInt(routes.size());
		routes.forEach(routeEntry -> {
			packetBufferSender.writeLong(routeEntry.id);
			packetBufferSender.writeString(routeEntry.name);
			packetBufferSender.writeInt(routeEntry.color);
			packetBufferSender.writeInt(routeEntry.platformIds.size());
			// 用下标循环而不是 forEach：fastutil 的 LongArrayList.forEach 同时匹配
			// Consumer<Long> 和 LongConsumer 两个重载，方法引用在这里是歧义的。
			for (int j = 0; j < routeEntry.platformIds.size(); j++) {
				packetBufferSender.writeLong(routeEntry.platformIds.getLong(j));
			}
		});
	}

	@Override
	public void runServer(MinecraftServer minecraftServer, ServerPlayerEntity serverPlayerEntity) {
		if (!Config.get().announcerAllRoutes) {
			return;
		}
		Init.sendMessageC2S(
				OperationProcessor.LIST_DATA,
				minecraftServer,
				serverPlayerEntity.getEntityWorld(),
				EMPTY_REQUEST,
				(ListDataResponse listDataResponse) -> {
					final ObjectArrayList<RouteEntry> routeEntries = new ObjectArrayList<>();
					// routes 声明在生成的 ListDataResponseSchema 上、是 protected，
					// 所以用打在那个类上的 @Accessor 取（@Shadow 不沿继承链上溯）。
					// 编译期 ListDataResponse 还没实现这个接口（mixin 运行时才加），要经 Object 中转。
					for (final Route route : ((ListDataResponseAccessor) (Object) listDataResponse).yuzimtr$getRoutes()) {
						final LongArrayList platformIds = new LongArrayList();
						// LIST_DATA 回程是**进程内直传的活对象**，开了 useThreadedSimulation 时
						// 模拟器线程可能正在改这个列表（有人在仪表板里编交路）。fastutil 的迭代器
						// 不是 fail-fast，最坏是漏元素或越界。
						//
						// 出错时**整条交路不收**，而不是收一个空的站台列表：所有消费者都把空列表读成
						// 「哪个站台都不停」，那会让这条交路从选择器里消失、粘贴时被判成本站没有。
						// 不收的话，StationRouteIndex 和选择器会对缓存里查不到的交路退回 simplifiedRoutes。
						try {
							for (final RoutePlatformData routePlatformData : route.getRoutePlatforms()) {
								// platformId 在生成的 schema 上是 protected、没有 getter，
								// 只能走已解析好的 platform 对象。模拟器侧 Data.sync() 之后它一定非空，
								// 但交路刚建、站台还没连上时会是 null，跳过即可。
								final org.mtr.core.data.Platform platform = routePlatformData.getPlatform();
								if (platform != null) {
									platformIds.add(platform.getId());
								}
							}
						} catch (Exception e) {
							YuziMtrExt.LOGGER.warn("读交路 {} 的停靠站台时出错，这次答复里不带它", route.getId(), e);
							continue;
						}
						routeEntries.add(new RouteEntry(route.getId(), route.getName(), route.getColor(), platformIds));
					}
					YuziMtrExt.REGISTRY.sendPacketToClient(serverPlayerEntity, new PacketAllRoutes(callbackId, routeEntries));
				},
				ListDataResponse.class
		);
	}

	@Override
	public void runClient() {
		AllRoutesCache.put(routes);
	}
}
