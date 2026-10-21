# Agent 须知

GTOUtils 是 GregTech Odyssey 系列模组（GTOCore、GTM fork、新生魔艺 fork、植物魔法 fork 等）共用的算法库，Minecraft 1.20.1 Forge，JDK 21，ForgeGradle 6。

- 包名 `com.suried.gtoutils`，模组 ID `gtoutils`，Maven 坐标 `com.gto:gtoutils-forge-1.20.1:<版本>`（gtopublish 插件按 `archivesName` 加 `-forge-1.20.1` 生成）。
- 只放与具体玩法无关的通用算法和数据结构，不得依赖任何其它模组。
- 每个算法类在类声明前写功能清单（每个公开功能一条），并为清单里的每项功能写 JUnit 测试，含边界情况和与暴力参照模型的随机对拍；方法和字段上不写注释。
- 性能与线程：查询不分配内存（收集到调用方给的集合除外）；`BlockIndex` 不是线程安全的，只在拥有该维度的线程上使用。
- 方块实体的自动登记只有一个入口：`mixin/LevelChunkMixin` 在区块方块实体表放入、替换、移除和清空时调用 `BlockEntityTracking`，再按类分发给各索引；客户端另由 `mixin/client` 在视野中心移动、视野半径变化时把范围外的区块从索引摘掉、回到范围内再登记（`ClientChunkVisibility`），与原版的可见范围一致；按维度的数据一律存在 `LevelStore`（挂在维度实例上，维度卸载时整体丢弃），不要另建以维度为键的全局表。`BlockEntityIndex`/`ProbedBlockEntityIndex` 只能在模组加载阶段创建。按能力的索引不另存待探测对象，从 `BlockEntityIndex.all()` 按 16³ 区段补探测，扫过的区段一直有效，之后落进来的对象记进一棵新增树等查询覆盖时再探测，探测结果记在方块实体上（`mixin/BlockEntityMixin` 的标记位，每个按能力的索引一位，最多 32 个）。
- 按方块状态在范围里找格子用 `SectionScan`（算法本体，经 `Sections` 接口读区段，可单测）和 `LevelSections`（接到维度上，取值与 `Level.getBlockState` 一致）；先用区段调色板跳过不可能命中的区段，结果按原版 `betweenClosed`/`withinManhattan` 顺序给出。
- 授权 PolyForm Noncommercial 1.0.0（与 GTOCore 代码相同），只允许非商业使用；LICENSE 首行是 `Required Notice`。
- 版本号写在 `gradle.properties` 的 `mod_version`，格式 `yy.mm.n`。本地联调用 `./gradlew publishToMavenLocal`；发布用 `./gradlew gtoValidate` 后 `./gradlew gtoPublish`，凭据从 `~/.gradle/gradle.properties` 读取，不得写入仓库。
