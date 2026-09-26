package com.yuzi.mtrext;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 只在查不到时兜一手的 Map 包装：把 NTE 的别名前缀剥掉再查一次。
 *
 * <p>NTE 把 OBJ 部件注册成 {@code <模型文件名>.obj/<部件名>}
 * （mtr-nte 的 DynamicTrainModelLoader.java:97），reversed 变体还会多一层
 * {@code <文件名>.obj/reversed/<部件名>}。为 NTE 写的资源包因此在
 * model_properties 里写的是带前缀的名字。
 *
 * <p>MTR4 的 LegacyVehicleResource.java:280 把这个名字原样抄进 names，
 * 然后拿去查一个键是<b>裸 group 名</b>的 map —— 全部 miss，整个车体不渲染。
 *
 * <p>这里只处理「精确查找失败」的情况，取最后一个 '/' 之后的部分再试一次。
 * 本来能用的包完全不受影响。
 */
public final class NtePartNameMap<V> implements Map<String, V> {

	private final Map<String, V> delegate;

	public NtePartNameMap(Map<String, V> delegate) {
		this.delegate = delegate;
	}

	@Override
	public V get(Object key) {
		final V direct = delegate.get(key);
		if (direct != null || !(key instanceof String)) {
			return direct;
		}
		final String name = (String) key;
		final int slash = name.lastIndexOf('/');
		if (slash <= 0 || slash == name.length() - 1) {
			return null;
		}
		final V stripped = delegate.get(name.substring(slash + 1));
		if (stripped != null) {
			Diag.ntePrefixHit(name);
		}
		return stripped;
	}

	@Override
	public boolean containsKey(Object key) {
		return get(key) != null;
	}

	// ---- 其余全部直通 ----
	@Override public int size() { return delegate.size(); }
	@Override public boolean isEmpty() { return delegate.isEmpty(); }
	@Override public boolean containsValue(Object value) { return delegate.containsValue(value); }
	@Override public V put(String key, V value) { return delegate.put(key, value); }
	@Override public V remove(Object key) { return delegate.remove(key); }
	@Override public void putAll(Map<? extends String, ? extends V> m) { delegate.putAll(m); }
	@Override public void clear() { delegate.clear(); }
	@Override public Set<String> keySet() { return delegate.keySet(); }
	@Override public Collection<V> values() { return delegate.values(); }
	@Override public Set<Entry<String, V>> entrySet() { return delegate.entrySet(); }
}
