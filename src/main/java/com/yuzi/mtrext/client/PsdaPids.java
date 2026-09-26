package com.yuzi.mtrext.client;

import com.yuzi.mtrext.Config;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.operation.ArrivalResponse;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongImmutableList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.Direction;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.ArrivalsCacheClient;
import org.mtr.mod.data.IGui;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;

/**
 * 立柱正面那块屏幕上的简易 PIDS。
 *
 * <p>这块面板是 {@code movea.obj} 的 {@code sc} 组（ANTE 拿它显示 PIDS）。派生模型时
 * 点阵屏版把它改指深色材质当屏幕底，纯白版则连同周围的凹槽一起填平。
 * （{@code t1}/{@code t2} 不是屏幕，是柱脚的两块小告示牌，一律填白。）
 * 屏幕在模型坐标里是 X∈[-0.280,0.280]、Y∈[0.468,1.348]、Z=0.190，
 * 也就是 <b>8.96 × 14.08 像素</b>，按 MTR PIDS 的行高密度大约放得下 8 行。
 *
 * <p>版式：
 * <ol>
 * <li>线路名（{@code ArrivalResponse.getRouteNumber()}，没设置就留空）+ <b>右对齐</b>的
 *     到站时间 / 已到站</li>
 * <li>{@code 开往：终点站}</li>
 * <li>{@code 前方依次到站：}</li>
 * <li>往下每行一个后续车站，共 {@value #STATION_LINES} 个</li>
 * </ol>
 *
 * <p>多语言轮播照 {@code RenderPIDS} 的做法：数据里的站名/终点是用 {@code |} 分隔的多语言串，
 * 按 {@code 游戏刻 / SWITCH_LANGUAGE_TICKS} 取模轮换；界面文字则按当前内容是不是 CJK
 * 选中英两套（{@code IGui.isCjk}）。矩阵与文字绘制也照抄 {@code RenderPIDS.render}。
 */
public final class PsdaPids {

	private PsdaPids() {
	}

	/** 和 MTR {@code RenderPIDS.SWITCH_LANGUAGE_TICKS} 一致（60 刻 = 3 秒）。 */
	private static final int SWITCH_LANGUAGE_TICKS = 60;

	private static final int HEADER_LINES = 3;
	/** 表头之外还能放几个到站 —— 屏幕 14.08px 高，8 行时每行 1.76px。 */
	public static final int STATION_LINES = 5;
	private static final int TOTAL_LINES = HEADER_LINES + STATION_LINES;

	// 屏幕在模型坐标里的位置，换算成 MTR PIDS 那套「0..16、8 为中心」的像素坐标。
	// 注意 PsdaModels.at 用的是 rotateYDegrees(180 - facing)，而 RenderPIDS 用
	// rotateYDegrees(-facing)，两者差 180°，所以 X/Z 都要取反后再换算。
	private static final float START_X = 8 - 0.280F * 16;   // 3.52
	private static final float START_Y = 1.348F * 16;       // 21.57，屏幕上沿
	private static final float START_Z = 8 - 0.190F * 16;   // 4.96
	private static final float SCREEN_WIDTH = 0.560F * 16;  // 8.96
	private static final float SCREEN_HEIGHT = 0.880F * 16; // 14.08

	private static final float SMALL_OFFSET = 0.0001F;

	public static void render(BlockPos blockPos, Direction facing, long platformId) {
		if (!Config.get().ropeBarrierPids) {
			return;
		}
		final ObjectArrayList<ArrivalResponse> arrivals =
				ArrivalsCacheClient.INSTANCE.requestArrivals(LongImmutableList.of(platformId));
		MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) ->
				draw(graphicsHolder, offset, blockPos, facing, arrivals));
	}

	private static void draw(GraphicsHolder graphicsHolder, Vector3d offset, BlockPos blockPos,
			Direction facing, ObjectArrayList<ArrivalResponse> arrivals) {
		final int languageTicks = (int) Math.floor(InitClient.getGameTick()) / SWITCH_LANGUAGE_TICKS;
		final ArrivalResponse arrival = Utilities.getElement(arrivals, 0);
		// 每行是「左侧文字 + 可选的右对齐文字」
		final ObjectArrayList<String> lines = new ObjectArrayList<>();
		final ObjectArrayList<String> rightLines = new ObjectArrayList<>();

		if (arrival == null) {
			lines.add(TextHelper.translatable("gui.yuzimtr.psda_pids_no_service").getString());
			rightLines.add("");
		} else {
			final String destination = pick(arrival.getDestination(), languageTicks);
			final boolean cjk = IGui.isCjk(destination);
			final long secondsToArrival =
					(arrival.getArrival() - ArrivalsCacheClient.INSTANCE.getMillisOffset()
							- System.currentTimeMillis()) / 1000;

			// 线路名放左边（同 MTR PIDS 上终点站左侧那个位置），没设置就整段留空；
			// 到站时间右对齐
			lines.add(pick(arrival.getRouteNumber(), languageTicks));
			rightLines.add(arrivalText(secondsToArrival, arrival.getRealtime(), cjk));
			lines.add(label("psda_pids_bound_for", cjk, destination));
			rightLines.add("");
			lines.add(label("psda_pids_calling_at", cjk));
			rightLines.add("");

			final SimplifiedRoute route =
					MinecraftClientData.getInstance().simplifiedRouteIdMap.get(arrival.getRouteId());
			int added = 0;
			if (route != null) {
				for (int i = route.getPlatformIndex(arrival.getPlatformId()) + 1;
						i < route.getPlatforms().size() && added < STATION_LINES; i++) {
					final SimplifiedRoutePlatform platform = route.getPlatforms().get(i);
					lines.add(pick(platform.getStationName(), languageTicks));
					rightLines.add("");
					added++;
				}
			}
			if (added == 0) {
				lines.add(label("psda_pids_terminates", cjk));
				rightLines.add("");
			}
		}

		// 矩阵照抄 RenderPIDS.render：平移到方块中心 → rotateY(-facing) → rotateZ(180)
		// → 平移到屏幕左上角 → 缩放。rotateZ(180) 之后局部 +Y 就是世界向下，所以逐行 +Y。
		// textPadding 同 MTR RenderPIDS 的第 7 个构造参数：把字号压得比行高更小，留白
		final float scale = (float) (160 * TOTAL_LINES / SCREEN_HEIGHT * Config.get().ropeBarrierPidsTextPadding);
		final float availableWidth = SCREEN_WIDTH * scale / 16;

		for (int i = 0; i < lines.size() && i < TOTAL_LINES; i++) {
			graphicsHolder.push();
			graphicsHolder.translate(
					blockPos.getX() - offset.getXMapped() + 0.5,
					blockPos.getY() - offset.getYMapped(),
					blockPos.getZ() - offset.getZMapped() + 0.5);
			graphicsHolder.rotateYDegrees(-facing.asRotation());
			graphicsHolder.rotateZDegrees(180);
			graphicsHolder.translate((START_X - 8) / 16,
					-START_Y / 16 + i * SCREEN_HEIGHT / TOTAL_LINES / 16,
					(START_Z - 8) / 16 - SMALL_OFFSET * 2);
			graphicsHolder.scale(1 / scale, 1 / scale, 1 / scale);

			final int color = Config.get().ropeBarrierPidsTextColor | 0xFF000000;
			final String right = i < rightLines.size() ? rightLines.get(i) : "";
			final int rightWidth = right.isEmpty() ? 0 : GraphicsHolder.getTextWidth(right);

			// 右对齐那段先画，位置固定在右边缘；左侧文字只能占剩下的宽度
			if (rightWidth > 0) {
				graphicsHolder.drawText(right, (int) (availableWidth - rightWidth), 0, color,
						false, GraphicsHolder.getDefaultLight());
			}

			final String text = lines.get(i);
			if (!text.isEmpty()) {
				final float leftWidth = availableWidth - rightWidth - (rightWidth > 0 ? 4 : 0);
				final int textWidth = GraphicsHolder.getTextWidth(text);
				graphicsHolder.push();
				if (leftWidth < textWidth && textWidth > 0) {
					graphicsHolder.scale(leftWidth / textWidth, 1, 1);
				}
				graphicsHolder.drawText(text, 0, 0, color, false, GraphicsHolder.getDefaultLight());
				graphicsHolder.pop();
			}
			graphicsHolder.pop();
		}
	}

	/** 数据里的多语言串是 {@code |} 分隔的，按语言刻轮换 —— 同 {@code RenderPIDS} 的做法。 */
	private static String pick(String multiLanguage, int languageTicks) {
		final String[] split = multiLanguage.split("\\|");
		return split.length == 0 ? multiLanguage : split[languageTicks % split.length];
	}

	/** 界面文字按当前内容是不是 CJK 选中英两套，同 MTR 的 {@code _CJK} 变体做法。 */
	private static String label(String key, boolean cjk, Object... args) {
		return TextHelper.translatable("gui.yuzimtr." + key + (cjk ? "_cjk" : ""), args).getString();
	}

	private static String arrivalText(long seconds, boolean realtime, boolean cjk) {
		if (seconds <= 0) {
			return label("psda_pids_arrived", cjk);
		}
		final String prefix = realtime ? "" : "*";
		return prefix + (seconds >= 60
				? label("psda_pids_min", cjk, seconds / 60)
				: label("psda_pids_sec", cjk, seconds));
	}
}
