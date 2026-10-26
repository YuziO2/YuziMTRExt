# 构建与部署

## 构建

构建与部署任务以 `build.gradle` 为准：

```bash
./gradlew build           # 发布 jar，产物在 build/libs/
./gradlew runGametest     # 独立测试服务器，结果写入 build/gametest-results.xml
./gradlew deployToClient  # 构建并放进客户端 mods
./gradlew deployToServer
./gradlew deployAll
```

部署目录因机器而异，**不放仓库**：写在自己的 `~/.gradle/gradle.properties`（`client_mods_dir=…`、`server_mods_dir=…`）。
没设时构建照常，只有跑对应部署任务才报错并提示要加哪一行。`tools/` 下的脚本同理，路径都走命令行参数（不带参数运行会打印用法）。
`tools/` 只在开发仓库里，不发布到公开仓库（见下文）。

### JDK：编译和守护进程分开管

- **编译**：`build.gradle` 的 `java.toolchain` 指定 17；本机没有时，`settings.gradle` 的 Foojay resolver 会自动下载。
- **Gradle 守护进程**：`gradle/gradle-daemon-jvm.properties` 指定 `toolchainVersion=17`。启动 `./gradlew` 的 JVM 可以是任何版本，守护进程会用本机找到的 JDK 17（包括 Gradle 下载到 `~/.gradle/jdks/` 的那份），找不到就按文件里的 `toolchainUrl` 下载。这个文件由 `./gradlew updateDaemonJvm --jvm-version=17` 生成，改版本时重跑这条命令，不要手改。

守护进程必须跑在 17 上，版本高了会在两个地方挂：

- **JVM 25**（2026-09-27 实测）：Gradle 8.14 自带的 Groovy 3 不认 class 文件版本 69，编译构建脚本时报 `Unsupported class file major version 69`，任何任务都跑不起来。
- **JVM 21**（加 modmenu 依赖时遇到）：项目能配置，但 Loom 用的 Mercury/JDT 在 21 下读不了 JRT（`Jdk.readJdkReleaseFile` 直接崩），只要有依赖带 sources jar 需要重映射就会挂。之前删掉 `withSourcesJar()` 是同一个病的另一个症状。

不要在仓库的 `gradle.properties` 里钉 `org.gradle.java.home`：绝对路径只在一台机器上有效，CI 上必挂。以前就是这么钉的，已换成上面的守护进程配置。

`javap` 用任意 JDK 17+ 都行；本机只有 JRE 时，用 `~/.gradle/jdks/` 下那份 JDK 的 `bin/javap`。

## 版本号

`gradle.properties` 的 `mod_version` 用三位语义版本：

| 位 | 什么时候动 |
|---|---|
| 第三位 | 小修小补。**每次 commit 都要动**，即使只改注释 |
| 第二位 | 做完一个功能（第三位归零） |
| 第一位 | 做完一个大功能。**升之前先问用户**，不要自己决定 |

改了版本号，jar 的文件名跟着变，部署时要处理旧文件（见下文）。

## CI 与发布

- `.github/workflows/build.yml`：PR 打开或更新时，以及 PR 合并进 `master`/`main` 后，跑 `build` 和 `runGametest`。**直接推到 master 的提交不会触发构建**，推之前在本地跑一遍这两个任务。
- `.github/workflows/release.yml`：在要发布的提交上推 `v<mod_version>+<minecraft_version>` tag，工作流核对 tag 与 `gradle.properties` 一致后构建并上传 jar。它只跑 `clean build`，不跑 GameTest。

## 发布到公开仓库

日常开发在私有的 `YuziMTRExt-dev`；公开仓库 `YuziMTRExt` 只放发版快照，不带开发历史。在开发仓库打好版本 tag 后：

```bash
tools/publish-public.sh v<mod_version>+<minecraft_version>          # 演练：打印要发布的文件和提交说明
tools/publish-public.sh v<mod_version>+<minecraft_version> --push   # 推到公开仓库的 main 并打同名 tag
```

公开历史**按人分提交**：上次发布到这次 tag 之间的提交沿主线（first-parent）分段，连续由同一个人做的算一段（合并提交算它合进来的那些提交的作者），每段一个公开提交，作者是那个人，内容是这一段结束时的代码。提交说明列出这一段的开发提交标题，Co-authored-by 照搬，末尾 `Source-Commit:` 记下来源提交（最后一个再加 `Source-Tag:`），下次发布从那里接着分。第一次发布就是三段：1.4.0 及之前（YuziO2）、situ2001 的 PR（到 1.4.9）、之后到这次 tag（YuziO2）。

两个规则文件都用 tag 里那一版，对每一段快照都生效：

- `tools/public-exclude.txt`：不公开的路径，目前是整个 `tools/`。
- `tools/public-scrub.txt`：要改写的行，格式是「路径 <Tab> sed 表达式」。1.7.1 之前的 `gradle.properties` 钉着本机 JDK 和部署目录，靠这里换成占位。

脚本会拒绝：公开地址和 origin 相同、tag 与 `gradle.properties` 版本不符、公开仓库已有同名 tag、改写后任何一段里还有本机绝对路径。只动了 `tools/` 的段没有公开内容可发，会被跳过。
**别把开发仓库的历史直接推到公开仓库**（`YuziO2/YuziMTRExt` 这个名字以前是开发仓库，改名后旧地址不再重定向）。

## 部署

客户端运行时替换 jar 必须原子：写同分区的临时文件再 `mv`（真 rename，已打开的旧 inode 对 JVM 仍然有效）：

```bash
/usr/bin/cp -f build/libs/yuzimtr-<版本>+1.20.1.jar "$D/.tmp" && mv -f "$D/.tmp" "$D/yuzimtr-<版本>+1.20.1.jar"
```

直接 `cp -f` 覆盖会截断原 inode，JVM 读到半个 jar，报 `ZipException: invalid LOC header`，随后仪表板右键失灵、物品图标消失。写 `/usr/bin/cp` 是为了避开用户 shell 里 `cp` → `cp -i` 的别名。

`deployToClient` / `deployToServer` 会先删掉目标目录里的 `yuzimtr-*.jar` 再拷。**手动部署时必须自己删掉旧文件名的那个**：同一个 mods 目录里出现两个 `yuzimtr` id，Fabric 会直接拒绝启动。

## 改完要重启什么

模型缓存寿命在对象构造时确定，改了客户端渲染或缓存相关的东西要**重启客户端**；游戏内重载资源包不足以验证，还可能触发[MTR3 兼容笔记](mtr3-compatibility.md)记录的堆外图片错误。

服务端只在改了服务端功能时才需要重启：分交路停站时长、最小刷新、即时停站时长、时钟锚点、侧线车辆最高速、多色闭塞防互锁、基础信号模式、站台广播器、广播器全量交路、网页地图头像、立柱碰撞。纯客户端的改动重进游戏即可。
