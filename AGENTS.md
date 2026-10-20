# Agent 须知

GTOUtils 是 GregTech Odyssey 系列模组（GTOCore、GTM fork、新生魔艺 fork、植物魔法 fork 等）共用的算法库，Minecraft 1.20.1 Forge，JDK 21，ForgeGradle 6。

- 包名 `com.suried.gtoutils`，模组 ID `gtoutils`，Maven 坐标 `com.gto:gtoutils-forge-1.20.1:<版本>`（gtopublish 插件按 `archivesName` 加 `-forge-1.20.1` 生成）。
- 只放与具体玩法无关的通用算法和数据结构，不得依赖任何其它模组。
- 每个算法类在类声明前写功能清单（每个公开功能一条），并为清单里的每项功能写 JUnit 测试，含边界情况和与暴力参照模型的随机对拍；方法和字段上不写注释。
- 性能与线程：查询不分配内存（收集到调用方给的集合除外）；`BlockIndex` 不是线程安全的，只在拥有该维度的线程上使用。
- 授权 PolyForm Noncommercial 1.0.0（与 GTOCore 代码相同），只允许非商业使用；LICENSE 首行是 `Required Notice`。
- 版本号写在 `gradle.properties` 的 `mod_version`，格式 `yy.mm.n`。本地联调用 `./gradlew publishToMavenLocal`；发布用 `./gradlew gtoValidate` 后 `./gradlew gtoPublish`，凭据从 `~/.gradle/gradle.properties` 读取，不得写入仓库。
