# 排错与注入点约束

崩溃时先对照 `logs/latest.log`、最新的 `crash-reports/` 时间戳和 `hs_err_pid*.log`。没有对应的 Java 异常时，用 `coredumpctl list` / `coredumpctl info <pid>` 看原生栈；`libgallium`、`radeonsi`、`amdgpu` 指向显卡驱动路径，本环境曾出现过。

`debugCacheLogging` 打开后会输出：
- `构建车辆模型: <id>` —— 每行等于一次完整模型构建（含 OBJ 解析），用来定位是哪辆车在重建
- `=== 缓存重建统计 ===` —— 按 `(lifespan, 原因)` 聚合。原因区分三种：
  `首次构建`（expiry==0，从没建成过）/ `被回收`（expiry!=0，建过又被 `tick()` 清掉）/ `过期`。
  这个区分很关键 —— `tick()` 是先置 null 再由下次 `getData` 触发重建，
  光看 `data==null` 分不出「从没建过」和「建过又被回收」。

`debugDoorMapping` 打开后输出每个门部件的 `flipped`、两个 multiplier、box、以及
`mapDoors` 分配到的 doorway，用于定位左右侧错乱。

## 注入点约束（踩过的坑）

- **能用 `@Redirect` 一个唯一调用点，就别去数 `ordinal`。** 取消上游画交路名的循环
  用的是它那次 `routes.iterator()`，不是三个 `drawText` 里的第几个。
- **mixin 里少写带初始化的 static final 字段**（要靠合并 `<clinit>`）。
- **目标类继承了别的类时，`method` 只写方法名可能匹配到继承来的重载。**
  `MainRenderer extends EntityRenderer<EntityRendering>`，于是它有三个 `render`：
  我们要的静态版 `(GraphicsHolder, Vector3d)V`，以及两个实例版
  `(EntityRendering, F, F, GraphicsHolder, I)V` / `(EntityExtension, F, F, GraphicsHolder, I)V`。
  只写 `method = "render"` 会选中实例版，启动即 `InvalidInjectionException` 崩溃。
  **写全描述符**：`method = "render(Lorg/mtr/mapping/mapper/GraphicsHolder;Lorg/mtr/mapping/holder/Vector3d;)V"`。

  光 javap 目标方法本身不够 —— 得看**整个类有没有同名重载**（含继承来的）：
  `javap -p -s <类> | grep -B1 -A2 " 方法名("`。

- **`@Shadow` 对字段不沿继承链上溯。** `bogie1Models`、`names`、`doorXMultiplier` 都声明在
  生成的 `*Schema` 父类里，在子类的 mixin 上 `@Shadow` 会
  `InvalidMixinException` → 类加载失败 → 资源重载崩溃 → `resourcePacks` 被清空。
  跨类读私有/protected 字段用 `@Mixin(targets = "...")` + `@Accessor`。
- `@Accessor` 的返回类型必须和字段**精确匹配**（写 `Object` 会在编译期被注解处理器拦下 ——
  比运行时炸好得多）。
- 写 `final` 字段要 `@Mutable`。
- 部署前用目标 jar 的字节码核对注入点。synthetic lambda（`lambda$xxx$6`）的编号会随上游改动变化，尽量别按名字注入 ——
  改局部变量/参数（`@ModifyVariable argsOnly`）往往能绕开。
- **`@ModifyVariable` 的参数捕获是个计数，不是全有全无。**
  `ModifyVariableInjector` 里的 `captureTargetArgs` 决定往处理函数尾部追加目标方法
  **开头的几个**参数，所以只捕获需要的那几个就行，不必把整个签名抄全。
- **`@Inject` 打在 `RETURN` 上会命中所有 return 点**，包括上游的提前 return。
  写还原/清理逻辑时要保证在提前 return 的路径上也是无害的。
- **以原版类为目标的 mixin 走默认 `remap = true`**，refmap 由 Loom 在编译期生成，
  不用改构建配置。部署后可以解开产物核对：`yuzimtr-refmap.json` 里应能看到
  yarn 名 → intermediary 名的映射（例如 `move` → `class_746.method_5784`），
  `yuzimtr.mixins.json` 里应有 Loom 注入的 `"refmap"` 键。
- **自定义网络包：`registerPacket` 之外还必须调 `setupPackets`，而且两端各一次。**
  `registerPacket` 只是往一张按类名索引的表里塞条目；真正开通道的是
  `Registry.setupPackets(Identifier)`（设置 `packetsIdentifier` + 注册服务端 C2S 接收器）和
  `RegistryClient.setupPackets(同一 id)`（注册客户端 S2C 接收器）。
  **`Registry.init()` 不会替你调**（字节码确认：`init()` 只 forEach `objectsToRegister`
  再注册一个命令回调）。
  漏调的后果是**完全静默**的：`RegistryClient.sendPacketToServer` 第一件事就是
  `getfield packetsIdentifier; ifnull` 然后直接 return —— 包发不出去、不报错、无日志，
  表现成「功能像是没写」。`sendPacketToClient` 有同样的守卫。
  用自己的 id（`yuzimtr:packet`），别蹭 `mtr:packet`；同一 id 重复注册是
  `putIfAbsent`、返回 false 保留第一个，不抛异常。

  **`registerPacket` 和 `setupPackets` 的先后无所谓**：两侧的接收 lambda
  （`Registry.lambda$setupPackets$18`、`RegistryClient.lambda$setupPackets$9`）都是
  收到包时才 `getfield packets; Map.get`，`invokedynamic` 只捕获 `this`、不快照那张表。
  只有<b>发包</b>是顺序敏感的 —— 必须在 `setupPackets` 之后。

  注意 `Registry.setupPackets` 在**物理客户端上也要跑**：它内部虽然是
  `ServerPlayNetworking.registerGlobalReceiver`（单人存档的内置服务端要用），
  但客户端发包依赖的 `packetsIdentifier` 也是它写的。将来别给它加「只在服务端执行」的条件。

- **服务端也会加载这个 mod**，`mixins.json` 分 `mixins`（双端）和 `client`（仅客户端）两段。
  客户端专有类型（`ClientWorld`、`net.minecraft.class_746` 等）不能出现在服务端会加载的类里 ——
  `org.mtr.mapping.holder.ClientWorld` 包的是 `net.minecraft.class_638`，专用服务端根本没有。
  核对办法是扫产物的常量池，注意**别用子串匹配**（字段名 `fixScheduledSoundDuplication`
  会让 `grep ScheduledSound` 误报），要看完整的类描述符。
  改完记得 `clean build` —— 增量编译会留下旧 class，让扫描结果失真。
