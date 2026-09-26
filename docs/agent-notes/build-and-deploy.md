# 构建与部署

## 构建

构建与部署任务以 `build.gradle` 为准。常用入口为 `./gradlew build`、`deployToClient`、`deployToServer` 和 `deployAll`，产物在 `build/libs/`。构建需要 JDK 17；`settings.gradle` 的 Foojay resolver 可提供编译 toolchain。

## 版本与发布

`gradle.properties` 的 `mod_version` 用三位语义版本：每次 commit 将第三位递增，即使只改注释；完成一个功能时递增第二位并将第三位归零；第一位用于大功能，升级前先问用户。

发布时，在要发布的提交上推送 `v<mod_version>+<minecraft_version>` tag。`.github/workflows/release.yml` 会核对 tag 与项目版本，构建并上传对应 jar。

## 部署

客户端运行时，通过同分区临时文件加 `mv` 原子替换 jar。直接 `cp -f` 覆盖会截断原 inode，JVM 可能读到半个 jar，报 `ZipException: invalid LOC header`。脚本里用 `/usr/bin/cp -f`，避开用户 shell 的交互式 `cp` 别名。

手动部署新版本时，先清理 mods 目录里旧文件名的 `yuzimtr-*.jar`，再放入新 jar；两个相同 mod id 会让 Fabric 拒绝启动。`deployToClient` 会处理旧 jar。

模型缓存寿命在对象构造时确定。改动后重启客户端；游戏内重载资源包不足以验证，还可能触发[MTR3 兼容笔记](mtr3-compatibility.md)记录的堆外图片错误。服务端功能改动要重启服务端；纯客户端改动重进游戏即可。
