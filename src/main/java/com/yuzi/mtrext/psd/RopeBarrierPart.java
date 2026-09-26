package com.yuzi.mtrext.psd;

/**
 * 「这块方块属于同一排绳网」的标记。
 *
 * <p>存在的理由是泛洪必须能<b>穿过立柱</b>：一排真实的绳网长成
 * {@code 立柱-绳-绳-绳-立柱-绳-绳-立柱} 这样，如果 {@link com.yuzi.mtrext.client.RopeBarrierFlood#pushAlongRow}
 * 只认绳索方块，遇到中间的立柱就会断开，那立柱另一侧的绳子永远收不到门开度 ——
 * 整排同步是这套方案能兼容不同门距的全部意义所在，断了就白做了。
 *
 * <p>立柱后来也有了方块实体（柱头要随开度升起），所以它既导通也接收门开度；
 * 这个接口现在纯粹是「属于同一排」的标记，不再暗示有没有方块实体。
 */
public interface RopeBarrierPart {
}
