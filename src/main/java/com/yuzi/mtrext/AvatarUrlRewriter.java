package com.yuzi.mtrext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 MTR 网页地图里硬编码的头像地址改成可配置的。
 *
 * <p>MTR 把 {@code https://mc-heads.net/avatar/<uuid>} 写死在 Angular 前端里，一共 5 处，
 * 没有任何配置项。但网页是 {@code Main.java:73} 的
 * {@code new MainWebServlet(WebserverResources::get, "/")} 服务的，而
 * {@code WebserverResources.get(String)} 是 public static、返回 String ——
 * 在它返回时做字符串替换，就等于给 MTR 加了个可配置的头像接口。
 *
 * <p>为什么要能换成按<b>名字</b>取：自定义登录（LittleSkin 之类）的 UUID 在官方源上查不到，
 * 网页地图上全是史蒂夫。而实测 LittleSkin 只有名字接口能用：
 * <ul>
 * <li>{@code littleskin.cn/avatar/{uuid}} → HTTP 500</li>
 * <li>{@code littleskin.cn/avatar/player/{name}} → 200，image/webp 100×100 ✅</li>
 * <li>{@code littleskin.cn/api/yggdrasil/.../profile/{uuid}} → 200 但是 JSON，
 *     还得自己解 base64 再从整张皮肤里裁脸，{@code <img>} 用不了</li>
 * </ul>
 * 好在 MTR 的 {@code clients} 接口本来就带 {@code name}（schema 里是 required），
 * 所以换成名字是够的。
 *
 * <p>压缩后的 JS 里那 5 处长这三种样子（都是从 jar 里挖出来核对过的）：
 * <pre>
 * y("ngSrc","https://mc-heads.net/avatar/"+e.id)…("alt",e.name)      ← 3 处，旁边就有 .name
 * {key:l.id,icons:[`https://mc-heads.net/avatar/${l.id}`],name:l.name} ← 1 处
 * getImageSrc(){let n=this.clientService.getSelectedData();
 *               return n?`https://mc-heads.net/avatar/${n}`:void 0}    ← 1 处，只有裸 id
 * </pre>
 * 前两种正则很稳（变量名怎么压缩都匹配得上）。第三种是侧边栏那个大头像，手上只有 id，
 * 得把插值表达式换成 {@code this.<service>.getClient()?.name??<id>} —— 这一处对
 * MTR 的版本敏感，匹配不上会打日志，其余照常替换，不会把整个网页搞坏。
 */
public final class AvatarUrlRewriter {

	private AvatarUrlRewriter() {
	}

	/** MTR 写死的那个前缀。 */
	private static final String UPSTREAM = "https://mc-heads.net/avatar/";
	public static final String DEFAULT_TEMPLATE = UPSTREAM + "{uuid}";

	/** {@code "https://mc-heads.net/avatar/"+e.id} */
	private static final Pattern CONCAT = Pattern.compile("\"" + Pattern.quote(UPSTREAM) + "\"\\+(\\w+)\\.id");
	/** <code>`https://mc-heads.net/avatar/${l.id}`</code> */
	private static final Pattern TEMPLATE_FIELD = Pattern.compile("`" + Pattern.quote(UPSTREAM) + "\\$\\{(\\w+)\\.id}`");
	/** client-panel 的 getImageSrc()，手上只有裸 id。 */
	private static final Pattern SELECTED = Pattern.compile(
			"(getImageSrc\\(\\)\\{let (\\w+)=this\\.(\\w+)\\.getSelectedData\\(\\);return \\2\\?)"
					+ "`" + Pattern.quote(UPSTREAM) + "\\$\\{\\2}`"
	);

	/**
	 * @param content 要服务出去的网页文件内容
	 * @return 替换后的内容；没有可替换的地方就原样返回同一个引用
	 */
	public static String rewrite(String content) {
		final String template = Config.get().playerAvatarUrl;
		if (content == null || template == null || template.isEmpty()
				|| DEFAULT_TEMPLATE.equals(template) || !content.contains(UPSTREAM)) {
			return content;
		}

		final boolean byName = template.contains("{name}");
		final String placeholder = byName ? "{name}" : "{uuid}";
		final int index = template.indexOf(placeholder);
		if (index < 0) {
			YuziMtrExt.LOGGER.error("playerAvatarUrl 里没有 {uuid} 或 {name} 占位符，不替换: {}", template);
			return content;
		}
		final String prefix = template.substring(0, index);
		final String suffix = template.substring(index + placeholder.length());
		final String field = byName ? ".name" : ".id";

		int count = 0;
		String result = content;

		// "PRE"+e.name+"SUF"
		String concatReplacement = "\"" + quote(prefix) + "\"+$1" + field;
		if (!suffix.isEmpty()) {
			concatReplacement += "+\"" + quote(suffix) + "\"";
		}
		count += countMatches(CONCAT, result);
		result = CONCAT.matcher(result).replaceAll(concatReplacement);

		// `PRE${l.name}SUF`
		count += countMatches(TEMPLATE_FIELD, result);
		result = TEMPLATE_FIELD.matcher(result).replaceAll("`" + quote(prefix) + "\\$\\{$1" + field + "}" + quote(suffix) + "`");

		// getImageSrc()：只有裸 id，按名字取时要绕一下
		final int selectedFound = countMatches(SELECTED, result);
		if (selectedFound > 0) {
			final String expression = byName ? "this.$3.getClient()?.name??$2" : "$2";
			result = SELECTED.matcher(result).replaceAll(
					"$1`" + quote(prefix) + "\\$\\{" + expression + "}" + quote(suffix) + "`");
			count += selectedFound;
		}

		if (result.contains(UPSTREAM)) {
			YuziMtrExt.LOGGER.warn("网页头像地址还有 {} 处没换掉（MTR 版本变了？），那几处仍走 mc-heads",
					countMatches(Pattern.compile(Pattern.quote(UPSTREAM)), result));
		}
		if (count > 0) {
			YuziMtrExt.LOGGER.info("网页头像地址已替换 {} 处 -> {}", count, template);
		}
		return result;
	}

	private static int countMatches(Pattern pattern, String text) {
		final Matcher matcher = pattern.matcher(text);
		int count = 0;
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	/** 替换串里的 {@code $} 和 {@code \} 要转义，但我们自己写的 $1/$2/$3 不能被转义。 */
	private static String quote(String literal) {
		return literal.replace("\\", "\\\\").replace("$", "\\$");
	}
}
