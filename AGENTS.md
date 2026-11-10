# Agent 须知

GTOUtils 是 GregTech Odyssey 系列模组（GTOCore、GTM fork、新生魔艺 fork、植物魔法 fork 等）共用的算法库，Minecraft 1.20.1 Forge，JDK 21，ForgeGradle 6。

- 包名 `com.suried.gtoutils`，模组 ID `gtoutils`，Maven 坐标 `com.gto:gtoutils-forge-1.20.1:<版本>`（gtopublish 插件按 `archivesName` 加 `-forge-1.20.1` 生成）。
- 只放与具体玩法无关的通用算法和数据结构，不得依赖任何其它模组。
- 每个算法类在类声明前写功能清单（每个公开功能一条），并为清单里的每项功能写 JUnit 测试，含边界情况和与暴力参照模型的随机对拍；方法和字段上不写注释。
- 性能与线程：查询不分配内存（收集到调用方给的集合除外）；`BlockIndex` 不是线程安全的，只在拥有该维度的线程上使用。
- 方块实体的自动登记只有一个入口：`mixin/LevelChunkMixin` 在区块方块实体表放入、替换、移除和清空时调用 `BlockEntityTracking`，再按类分发给各索引；客户端另由 `mixin/client` 在视野中心移动、视野半径变化时把范围外的区块从索引摘掉、回到范围内再登记（`ClientChunkVisibility`），与原版的可见范围一致；按维度的数据一律存在 `LevelStore`（挂在维度实例上，维度卸载时整体丢弃），不要另建以维度为键的全局表。`BlockEntityIndex`/`ProbedBlockEntityIndex` 只能在模组加载阶段创建。按能力的索引不另存待探测对象，从 `BlockEntityIndex.all()` 按 16³ 区段补探测，扫过的区段一直有效，之后落进来的对象记进一棵新增树等查询覆盖时再探测，探测结果记在方块实体上（`mixin/BlockEntityMixin` 的标记位，每个按能力的索引一位，最多 32 个）。
- 按维度的数据统一用 `level.LevelStore`（挂在维度实例上，任何线程可读写，可带释放回调，维度卸载时逐个回调后丢弃）；只读已加载区块用 `level.LoadedChunks`（不触发加载、不阻塞主线程，任何线程可调）。延迟、周期、去重与批量任务统一走 `schedule` 包：`Schedulers.of/async/server/client` 取车道；`TickScheduler` 是算法本体（周期任务按周期分组、组内按相位分桶且条目常驻，一次性任务与超长周期走 256 槽时间轮，可单测），所有者是方块实体或实体时随其移除停止；异步车道 `AsyncLane` 跟随维度 tick 投递、上一轮未完就跳过；`CoalescedTask` 是同轮去重与窗口节流，`TickBatch` 是每维度去重批量；有生命周期的副作用（任务、监听、共享资源借用）挂在作用域 `Scope`/`OwnedScope` 上，结束时倒序收回，`watch`/`select`/`when`/`each` 派生按读到的 `Signal`、`Cell` 自动追踪依赖（无事件的读取用 `Poll.every`；单个值的状态用 `Cell`，读即追踪、写即触发），挂载体的第一个参数一律是它所在的作用域；共享资源用 `scope.pool` 建的 `SharedPool`（工厂只在建池处给一次，池随作用域结束）配 `scope.lease`；按拥有者（玩家、维度、屏幕……）区分的同类作用域用 `ScopeKind`（模块 `install` 一次、每个实例建立时自动挂在自己的子作用域里，`get` 取当前实例，`lookup`/`require` 直接取实例上的服务，开关能力 `Control` 只给负责生命周期的一方），作用域里带类型的服务用 `ScopeKey` 配 `provide`（一律提供给所属实例，提供方结束时撤下）与 `lookup`/`require`。`Signal` 是密封类，不得继承。各模组不要再自建静态任务队列或以维度为键的静态表。
- 按方块状态在范围里找格子用 `SectionScan`（算法本体，经 `Sections` 接口读区段，可单测）和 `LevelSections`（接到维度上，取值与 `Level.getBlockState` 一致；`Unloaded.SKIP` 版本只读已加载区块）；先用区段调色板跳过不可能命中的区段，结果按原版 `betweenClosed`/`withinManhattan` 顺序给出。
- 授权 PolyForm Noncommercial 1.0.0（与 GTOCore 代码相同），只允许非商业使用；LICENSE 首行是 `Required Notice`。
- 版本号写在 `gradle.properties` 的 `mod_version`，格式 `yy.mm.n`。本地联调用 `./gradlew publishToMavenLocal`；发布用 `./gradlew gtoValidate` 后 `./gradlew gtoPublish`，凭据从 `~/.gradle/gradle.properties` 读取，不得写入仓库。
