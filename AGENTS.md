# Yuzi MTR Ext 开发入口

本项目修改的是编译后的 MTR 4，而本地上游源码与发布 jar 可能不同。功能和用法见 [README.md](README.md)；下列笔记记录已查明的上游行为、注入选择和已知代价。

## 修改时

1. 按下方触发条件读取**每个受影响领域**的笔记，先确认已有修法和限制。完成条件：改动涉及的领域都已查过，不依赖对上游行为的记忆。
2. 改 MTR 注入点前，用目标版本的**编译后 jar** 核对类、方法描述符、同名重载和调用位置，再写 mixin。本地源码只用于理解意图：发布版和源码可能不一致（重载、内联、lambda 编号、mapping 层包装）。`javap -c -p -s <类文件> | sed -n '/方法名/,/^$/p'` 可查看单个方法的字节码；系统里没有 `unzip`，解 jar 用 `jar xf`。完成条件：每个新增或修改的注入点都能在实际目标字节码中定位。
3. 排 bug 时写出可区分候选原因的观测，再验证：尽量找「如果是 A 就会看到 X，是 B 就会看到 Y」的判别，并写进注释。例如「屏蔽门能开但车门不能开」直接把范围缩到 `partDetails.doorway`；「门关着就已经错位」是几何问题，「关着正常只是开错侧」是映射问题。注释、提交信息和交付说明中区分**已验证**与**推测**：列车重影曾被判断成 MTR 的问题，实际是我们自己的 `@Redirect` 引起的，白查了一轮。完成条件：结论有对应的日志、字节码、测试或复现证据；未验证的部分明确标记。

## 按任务读取

- **MTR3 资源包兼容、NTE 部件名、OBJ 车门、模型缓存、贯通道、车内移动**：读 [MTR3 兼容笔记](docs/agent-notes/mtr3-compatibility.md)。
- **排图、班次、停站时长、最小刷新、时钟锚点、侧线最高速、信号闭塞**：读 [排图与限速笔记](docs/agent-notes/scheduling-and-speed.md)。
- **绳索屏蔽门、光影渲染、车厢侧面碰撞和上下车**：读 [站台与乘车笔记](docs/agent-notes/platform-and-boarding.md)。
- **列车音效、下一站播报、广播器全量交路通道**：读 [车辆音频笔记](docs/agent-notes/vehicle-audio.md)。
- **站台广播器的时序、规则、界面、网络包和已知限制**：读 [站台广播器笔记](docs/agent-notes/station-announcer.md)。
- **网页地图头像、高速轨道、资源包轨道性能、模组菜单配置**：读 [其他功能与资源笔记](docs/agent-notes/other-features-and-assets.md)。
- **Mixin、Accessor、自定义网络包、客户端/服务端类加载、崩溃、模型缓存或车门映射排错**：读 [注入与排错](docs/agent-notes/mixin-and-diagnostics.md)。
- **构建、JDK / Gradle 环境、GameTest、提交版本号、CI、部署、发布或改完要重启什么**：读 [构建与部署](docs/agent-notes/build-and-deploy.md)。

修改配置默认值时，同步更新目标运行环境已落盘的 `config/yuzimtr.json`；已有键会覆盖代码默认值，新增键才沿用字段初始值。

## 参考源码

维护者本机把这些仓库放在本仓库的同级目录（`../`）。源码只用来理解意图，注入点以编译后 jar 为准（见上面第 2 条）。

| 仓库 | 目录 | 检出位置 |
|---|---|---|
| MTR 4.0.5 | `Minecraft-Transit-Railway` | 与发布 jar 可能不一致 |
| MTR 3.2.2 | `MTR-3.2.2-hotfix-1` | worktree @ `5d18ccf6` |
| Transport-Simulation-Core | `TSC-mtr405` | worktree @ `ecde724`，对应 MTR 4.0.5 内置的 jar |
| NTE | `mtr-nte` | |
| JCM（第三方 patch MTR 的现成范例） | `Joban-Client-Mod` | |
