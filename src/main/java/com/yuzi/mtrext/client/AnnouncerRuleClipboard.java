package com.yuzi.mtrext.client;

import com.yuzi.mtrext.station.AnnouncerRule;
import org.mtr.core.data.Platform;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.BlockPos;

import javax.annotation.Nullable;

/**
 * 站台广播器规则的剪贴板：在一个方块里复制，粘到任何一个方块里（包括别的车站）。
 *
 * <p>存在客户端静态字段里，跨界面、跨方块都在；重进世界也不清 —— 连回同一个服务器是常态，
 * 清了反而不方便；换了世界的话 id 对不上，按下面的规则会自然退化成「站台/交路待选」。
 *
 * <h2>粘贴时怎么把站台和交路对上</h2>
 *
 * <p>音频、时机、时长原样照搬；站台和交路按<b>交路</b>去对 —— 同一条交路在每个站都停，
 * 这正是沿线复用的场景：
 * <ol>
 * <li><b>源站台就在本站</b>（同站复制到另一个方块）：原样保留。</li>
 * <li><b>指定了交路</b>：在本站站台里找这条交路停靠的那个，站台跟着换过去；
 *     本站找不到这条交路 → 交路<b>置为「未选择」</b>，站台也清掉，规则不生效，等人补。</li>
 * <li><b>全部交路</b>：没有「目标交路」可对，就用<b>复制时源站台上停的那些交路</b>去对 ——
 *     本站哪个站台和它们重合得最多就选哪个（同一条线同一个方向的站台，交路集合几乎一样）。
 *     一条都不重合 → 站台清掉，交路保持「全部交路」。</li>
 * </ol>
 *
 * <p>源站台的交路集合在<b>复制那一刻</b>就快照下来：复制时人站在源站旁边，数据一定齐；
 * 粘贴时可能已经走远了，而 {@code simplifiedRoutes} 只带请求半径内的站台。
 */
public final class AnnouncerRuleClipboard {

	private AnnouncerRuleClipboard() {
	}

	@Nullable
	private static AnnouncerRule copied;
	private static long sourcePlatformId;
	private static final LongAVLTreeSet sourceRouteIds = new LongAVLTreeSet();

	public static void copy(AnnouncerRule rule) {
		copied = new AnnouncerRule(rule);
		sourcePlatformId = rule.platformId;
		sourceRouteIds.clear();
		if (rule.hasSpecificRoute()) {
			sourceRouteIds.add(rule.routeId);
		} else if (rule.platformId != 0) {
			sourceRouteIds.addAll(StationRouteIndex.routesAt(rule.platformId));
		}
	}

	@Nullable
	public static AnnouncerRule peek() {
		return copied;
	}

	/**
	 * @return 按目标方块所在车站重新对过站台和交路的<b>新</b>规则；剪贴板为空时返回 null
	 */
	@Nullable
	public static AnnouncerRule pasteFor(BlockPos blockPos) {
		if (copied == null) {
			return null;
		}
		final AnnouncerRule rule = new AnnouncerRule(copied);
		final ObjectArrayList<Platform> platforms = StationRouteIndex.platformsNear(blockPos);
		boolean sourceIsHere = false;
		for (final Platform platform : platforms) {
			if (platform.getId() == sourcePlatformId) {
				sourceIsHere = true;
				break;
			}
		}

		if (rule.routeId == AnnouncerRule.ROUTE_UNSET) {
			// 复制的本来就是一条没配完的规则：没有交路可对，站台在本站就留、不在就清。
			if (!sourceIsHere) {
				rule.platformId = 0;
			}
			return rule;
		}

		if (rule.hasSpecificRoute()) {
			if (sourceIsHere && StationRouteIndex.routeStopsAt(rule.routeId, sourcePlatformId)) {
				return rule;
			}
			for (final Platform platform : platforms) {
				if (StationRouteIndex.routeStopsAt(rule.routeId, platform.getId())) {
					rule.platformId = platform.getId();
					return rule;
				}
			}
			// 本站找不到这条交路。站台也要清：留着别站的站台，这个方块就会替别的车站报站。
			rule.routeId = AnnouncerRule.ROUTE_UNSET;
			if (!sourceIsHere) {
				rule.platformId = 0;
			}
			return rule;
		}

		// 全部交路
		if (sourceIsHere) {
			return rule;
		}
		long bestPlatformId = 0;
		int bestOverlap = 0;
		for (final Platform platform : platforms) {
			int overlap = 0;
			for (final long routeId : StationRouteIndex.routesAt(platform.getId())) {
				if (sourceRouteIds.contains(routeId)) {
					overlap++;
				}
			}
			// 严格大于：平手时保留排序靠前的那个，结果是确定的。
			if (overlap > bestOverlap) {
				bestOverlap = overlap;
				bestPlatformId = platform.getId();
			}
		}
		rule.platformId = bestPlatformId;
		return rule;
	}
}
