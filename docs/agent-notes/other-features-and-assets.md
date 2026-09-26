# 其他功能与资源

## 新增内容

### 网页地图的头像地址可配置 · `playerAvatarUrl`

自定义登录（LittleSkin 之类）的 UUID 在官方源查不到，网页地图上全是史蒂夫。
MTR 把 `https://mc-heads.net/avatar/<uuid>` **写死在 Angular 前端里，一共 5 处，没有配置项**。

下手点是 `Main.java:73` 的 `new MainWebServlet(WebserverResources::get, "/")` ——
`WebserverResources.get(String)` 是 `public static` 返回 `String`（字节码核对过），
所有静态文件都从这里出去。注在它的 RETURN 上做字符串替换即可。

**为什么必须能按名字取**（实测）：

| 接口 | 结果 |
|---|---|
| `littleskin.cn/avatar/{uuid}` | HTTP 500 |
| `littleskin.cn/avatar/player/{name}` | 200，image/webp 100×100 ✅ |
| `littleskin.cn/api/yggdrasil/.../profile/{uuid}` | 200 但是 JSON（base64 textures），`<img>` 用不了 |

（`littlesk.in` 是短域名，会 301 到 `littleskin.cn`。）
好在 MTR 的 `clients` 接口本来就带 `name`（schema 里 required），所以按名字够用。

压缩后的 JS 里那 5 处是三种形式，`AvatarUrlRewriter` 分别处理：

```js
y("ngSrc","https://mc-heads.net/avatar/"+e.id)…("alt",e.name)          ← 3 处
{key:l.id,icons:[`https://mc-heads.net/avatar/${l.id}`],name:l.name}     ← 1 处
getImageSrc(){let n=this.clientService.getSelectedData();
              return n?`https://mc-heads.net/avatar/${n}`:void 0}        ← 1 处，只有裸 id
```

前两种的正则对压缩后的变量名不敏感。第三种是侧边栏那个大头像，手上只有 id，
把插值表达式换成 `this.<service>.getClient()?.name??<id>`（保留原来的 null 判断）：

```js
getImageSrc(){let n=this.clientService.getSelectedData();return n?`https://littleskin.cn/avatar/player/${this.clientService.getClient()?.name??n}`:void 0}
```

这一处对 MTR 版本敏感，匹配不上会打 WARN 并报还剩几处没换，其余照常替换，不会把网页搞坏。
用 jar 里真实的字符串常量在 JDK 下跑过：5 处全中、0 处残留，`{name}` / `{uuid}` /
带 `?size=64` 后缀三种模板都验过。

模板占位符是 `{uuid}` 或 `{name}`，默认值等于 MTR 原来那个（默认零改动，直接原样返回）。
**这条改服务端** —— 网页服务器跑在服务端。

### 补上 180–500 km/h 的高速轨道

MTR 只有 20/40/60/80/100/120/140/160/200/300 十档。这里补 **180 / 250 / 280 / 350 /
380 / 400 / 450 / 500**，每档普通 + 单向共 16 个物品，注册进 MTR 自己的「核心」创造标签页。

**难点：速度烤在 enum 里。** `RailType` 是个枚举，`WOODEN(20…)`、`DIAMOND(300…)`，
往里加常量要动 `$VALUES`，又脏又容易崩。但真正落地那一行只吃 int
（`ItemRailModifier.createRail` 第 125 行）：

```java
rail = Rail.newRail(…, newRailType.railShape, 0, new ObjectArrayList<>(),
        isOneWay ? 0 : newRailType.speedLimit, newRailType.speedLimit, …);
```

所以借 `RailType.QUARTZ` 当模板注册物品，再 `@Redirect` 读 `speedLimit` 那个字段就够了
（字节码核对过：`createRail` 里 2 处 GETFIELD、`addTooltips` 里 1 处）。
不是我们的物品就原样返回，MTR 那十档完全不受影响。
`forContinuousMovementNode` 传 `false`，免得被拿去连缆车 ——
那条路上 MTR 会把 railType 换成 `CABLE_CAR`，速度就不该由我们说了。

**颜色**：`RailType.getRailColor` 是拿限速去 `values()` 里线性查找，查不到就
`return ARGB_BLACK`。所以在它 RETURN 上注一刀，**只在返回值正好是 ARGB_BLACK 时**接手
（那正好是「一个都没匹配上」的兜底分支），站台/侧线/折返/跑道那四个前置判断不受影响。

| km/h | 名字 | 颜色 |
|---|---|---|
| 180 | 紫水晶 Amethyst | `#B45CE0` |
| 250 | 青金石 Lapis | `#3A5CE0` |
| 280 | 黄金 Gold | `#F0C341` |
| 350 | 海晶灯 Sea Lantern | `#6FD3F0` |
| 380 | 末影 Ender | `#2FBF8F` |
| 400 | 发光地衣 Glow Lichen | `#8FD93A` |
| 450 | 下界之星 Nether Star | `#F5F0B0` |
| 500 | 下界合金 Netherite | `#4A4048` |

刻意和 MTR 现有十档的色系拉开（它们占了橡木棕、石灰、绿宝石绿、铁灰、砖红棕、紫、青、橙、
灰白、钻石青）。同一个值既用于物品贴图染色，也用于轨道在世界里/地图上的颜色，两边必然一致。

**贴图**由 `tools/gen_rail_textures.py` 生成，底图是 MTR 的 `rail_connector_300`（钻石）。

换色不能简单替换色号 —— **轨道色其实有 4 档**（像素数 240/224/160/144），单向那张还多
4 个抗锯齿混色。第一版只换了主色和暗色，结果另外两档的青色全留在图上。
正确做法：共享调色板 = `300` 和 `160` 两张贴图颜色集合的**交集**，正好 6 个
（白 + 5 档枕木色，两个变体都一样），凡不在这个集合里的像素就是轨道色；对它们做 HSV
变换 —— 色相直接换成目标色的，饱和度和明度按「目标色 / 底图主色」的比例缩放。
这样 4 档明暗关系自动保住，抗锯齿混色也一起处理了。

自检方式：用这套方法拿 `300` 去重建 MTR 真实的 `160` 贴图 ——
平均 |ΔRGB| = 2.3，67% 像素完全一致，90% 在 12 以内。

数字字形从 MTR 现有贴图里抠（3 位数字格 10×14，x = 16/27/38）。现有档位只提供
0/1/2/3/4/6/8，**偏偏缺 5**（250/350/450/500 都要），按 `6` 的笔法手描了一个。

语言文件是**合并**写入而不是覆盖 —— 里面还有手写的 `modmenu.nameTranslation` 等条目，
第一版的生成脚本直接 `json.dump` 把它们冲掉了。

## 资源包侧：单轨轨道的 draw call 优化

这一节不是模组改动，是排查「靠近 6 号线帧数掉到个位数、同样自定义的 S3 磁浮却没事」时
搞清楚的渲染成本模型，以及对应的资源包修法。工具在 `tools/merge_rail_atlas.py`。

### MTR4 的轨道 draw call 公式

`OptimizedRenderer.hasOptimizedRendering()` 在 4.0.5 里是 `iconst_1; ireturn`（恒为 true），
NTE 那套渲染器已经被吸收进 mtr-mapping（`org.mtr.mapping.render.batch/model/obj/…`）。但它
**不做实例化合批**：`BatchManager.queue` 给每个 VertexArray 塞一个 `RenderCall`，
`drawAll` 里是 `list.forEach(RenderCall::draw)` —— 每条都是独立 draw call，按材质分组只省了
着色器状态切换。而 `OptimizedModel.fromObjModels` 是**逐个 ObjModel 各自 `upload()` 再
`addAll`**，ObjModel 按 OBJ 的 `g` 分组切、组内再按材质切。所以

    draw call / 实例 = Σ（每个 g 组内的不同材质数）
    实例数           = 轨道长度 / repeatInterval

### 坑一：`repeatInterval` 缺失 → 几何体翻倍且互相重叠

legacy 轨道 schema（`schema/legacy/railResource.json`）没给 `repeatInterval` 声明默认值，
`LegacyRailResource.convert()` 原样抄过去 → 0 → `RailMath.renderSegment` 的
`interval <= 0 ? 0.5 : …` 回退成 **0.5 格**。

而 `RenderRails.renderRailStandard` 放置模型时**只有平移和旋转、没有 scale**。所以一格长的
模型按 0.5 格摆放 = 每节和邻节重叠一半，几何体白画一倍，还叠出共面 z-fighting（上游那句
`rotateZDegrees((x1*z1)%10/100)` 就是给这个打的歪补丁）。行进轴是模型的 **Z**，X 是横向宽度。

**但 `repeatInterval` 的语义是「摆放周期」，不总等于模型长度** —— CatenaryPro 的 `rcr`/`rcl`
声明 5.0 而模型 Z 只有 0.298（接触网杆是离散摆放的）。所以「按模型 Z 跨度自动推断」这种
模组侧兜底是错的，只能逐个包按语义填。

### 坑二：SketchUp 导出的 OBJ 分组 × 材质会乘起来

`dangui_n.obj` 是 7 个 `g` 组 × 6 个材质 = **13 个 (组,材质) 组合 = 13 个 VertexArray**。
注意 OBJ 里那 53 个 `usemtl` **行**不重要（加载器按材质合并），重要的是组合数。

### 实测对比与结果

| | interval | 实例/格 | VertexArray | draw call/格 | 三角形/格 |
|---|---|---|---|---|---|
| `dangui_n`（6号线单轨，修复前） | 缺失→0.5 | 2 | 13 | **26** | 942 |
| `dangui_n`（补 interval 后） | 1.0 | 1 | 13 | **13** | 471 |
| `dangui_n`（再合图集并组） | 1.0 | 1 | 1 | **1** | 471 |
| `magelv_track1`（S3 磁浮） | 1.0 | 1 | 1 | **1** | 48 |

两条线轨道总长几乎一样（单轨 19,520 格/175 条，磁浮 21,186 格/30 条），修复前差 26 倍。
单轨平均每条 112 格，一条进视野就是 2,912 次 draw call（原版 MC 一帧才一两千）。

### 图集布局的关键：横向排列，每格占满全高

UV 的 V 轴方向（左上原点还是左下原点）在 MTR/NTE 里不容易确定
（`ObjModelLoader:120` 是 `v = flipV ? 1 - uv.getY() : uv.getY()`，原样透传）。
**把子贴图横向排成一条、每格纵向拉满图集全高**，V 就是恒等映射，只需重映射 U ——
U 的方向两种约定下都一样，这个问题就彻底绕开了。

- 拉伸用 **NEAREST**：轨道走 entity cutout 渲染层（`blur=false` → GL_NEAREST），
  用 NEAREST 拉伸能保证采样结果和原图逐像素一致
- 最高的那张排在 x=0，图集高度取它的原生高度，它完全不被重采样
- U 映射带半像素内缩 `u' = (x0 + 0.5 + u*(w-1)) / W`，采样点落在格子内部像素中心，不跨格
- 纯色材质（`hs`/`bs`/`jm` 是 2×2 单色，占 80% 三角形，UV 还越界）直接改成指向格心一个像素

MTL 的 `map_Kd` 是**相对 OBJ 所在目录**解析的（`ResourceUtil.resolveRelativePath`），
所以 `map_Kd dangui_n/atlas.png` 对应 `assets/mtrsteamloco/rails/dangui_n/atlas.png`。

## 在「模组菜单」里配置

`fabric.mod.json` 注册了 `modmenu` 入口（写法照抄 MTR 自己的 `org.mtr.init.ModMenuConfig`），
界面是 `YuziConfigScreen`，用的也是 MTR 那套 `MTRScreenBase` + `IGui` + `CheckboxWidgetExtension`。

界面直接改 `Config` 那个单例的字段，所以 mixin 下一次读 `Config.get().xxx` 就是新值；
关闭时 `Config.save()` 落盘并清掉那几个正则/编组的编译缓存。默认值就是各字段的初始值。

modmenu 是 `modCompileOnly` —— 那个入口类只有装了「模组菜单」才会被 classload，
没装也不会 `NoClassDefFoundError`，jar 里也不会打进 modmenu 的任何东西。

**服务端那一组（分交路停站时长 / 最小刷新 / 改停站不用刷新）在客户端这里改是没用的。**
两边是各自独立的 `config/yuzimtr.json`，路径生成跑在服务端。界面里仍然列着它们，
是因为之前吃过一次亏：客户端那份留着旧默认值 `false`，站台仪表板里那一列输入框整个不出现。
列出来至少两边不一致时能一眼看到。

`consistSizes` / `gangwayOverrides` / `defaultConsistSizes` 是「正则 → 结构」的映射，
做图形界面不划算，继续只在 json 里改。

### 上游 bug：CheckboxWidgetExtension 的两个 boolean 被写反了

`CheckboxWidgetExtension(x, y, w, h, MutableText, checked, consumer)` 最终调的是
`CheckboxWidgetAbstractMapping.<init>(IIII Text checked showMessage)`，而字节码里压的是：

```
21 iconst_0        ← 常量 false
22 iload 6         ← 调用者传的 checked
24 invokespecial CheckboxWidgetAbstractMapping.<init>(IIIILorg/mtr/mapping/holder/Text;ZZ)V
```

`checked` 被硬编码成 `false`，调用者传的值落到了 `showMessage` 上。后果是
**标签只在勾上时才显示** —— 一屏复选框里勾着的有字、没勾的是个空方框。

绕法：一律传空文本 + `true`（那个 true 实际落在 showMessage 上，而文本是空的，
所以什么都不画），**标签自己画**，勾选状态一律用 `setChecked()` 显式设。
MTR 自己的界面全是「传 true 再手画标签」，大概也踩过。
