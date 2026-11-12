# 要真机/真整合包测的（bridge-rest：Indigo + block-view 在 NeoForge 上）

自测只用了 worldtest 的 TerrainProbe（一个方块、一个非原版适配的 FabricBakedModel、一个带 render data 的方块实体）。
都在测试游戏目录里跑，离线账号，不碰真实 .minecraft。

## 现在怎么做的

- Indigo `SectionBuilderMixin.hookBuildRenderBlock` 在清单里剥掉（NeoForge 的 SectionCompiler 结构不同）；
  桥接 `SectionCompilerIndigoMixin` 在 5 参 compile 里 @WrapOperation `renderBatched`：非原版适配的模型交给
  Indigo 的 TerrainRenderContext 每个方块画一次，原版模型照旧走 NeoForge。`-Damber.fabricbridge.noIndigo=true` 关掉桥接。
- Indigo `SectionBuilderMixin.hookBuild` 和 block-view `ChunkRendererRegionBuilderMixin` 不改代码：MixinFixer 的
  「委托重载」规则把注入挪到 NeoForge 真正干活的重载上（第 1 轮就修，普查 0 隔离、15 个修复）。
- 自测结果：NeoForge 7/7、Fabric 7/7；对照 noIndigo → 6/7（BlockRenderContext 而不是 TerrainRenderContext）；
  对照排除 ChunkRendererRegionBuilderMixin → 6/7（render data 不再在渲染线程随区域捕获，NeoForge 上读到 null）。

## 待测

- [ ] 多渲染层模型：同一个 Fabric 模型里有 cutout + translucent 的面（玻璃类、带叶子的方块），层分得对、不重复、不丢面
- [ ] 真 Fabric 渲染模组在 NeoForge 上：连接纹理（Continuity 一类）、自发光、AO、随机偏移的模型显示和 Fabric 上一致
- [ ] 依赖 block entity render data 的 Fabric 模组（伪装/框架类方块）：放下、换材质、区块重新加载后都对
- [ ] 和 Embeddium/Sodium 一类的 NeoForge 渲染优化一起：它们替换 SectionCompiler，桥接不触发；要看是退回原版画法还是丢模型
- [ ] NeoForge ModelData 和 Fabric render data 同一方块并存时互不覆盖
- [ ] 区块构建耗时：大整合包里开/关桥接（noIndigo）对比，没有明显变慢
- [ ] datagen：TagProviderMixin 仍然否决；Fabric 模组跑 datagen 不报错（之前就是这么定的，回归一下）
- [ ] 探针时序：LifecycleProbe（等两个箱子，最多 15 s）和 TerrainProbe（等网格化，最多 20 s）在慢机器、首次进已有世界时不误报

# 映射图缓存（mapping-tables 分支）

- [ ] 第一次启动（缓存为空）和之后的启动：Fabric、NeoForge、Forge 1.20.1 实例都能进世界，日志里第二次是「映射图 … (缓存 …)」
- [ ] 两个实例共用一个游戏目录、同时启动：同一 tableKey 的 .amap 只写一次或安全覆盖，不会读到半个文件
- [ ] 缓存文件被改坏、删掉一半、换成别的版本的 .amap：自动重建，不报错进世界
- [ ] 映射源文件被替换（sha1 不符）或清单里没有：拒绝并给出清楚的提示，不悄悄用坏的表
- [ ] 其他 9 个版本（1.20.2–1.21.10）有了官方游戏 jar 之后：补全后的图和 1.21.1 一样通过（字段合并、无冲突）
- [ ] 烘焙产物固定时间戳、排序后：真 Forge 的 securejarhandler、Fabric 的 jar 读取、带签名的模组都正常

# 烘焙接口 v1.1（bake-interface-v1.1 分支）

- [ ] Amber 启动器真正按 v1.1 调用：requirements（带 platform）→ 下载 → bake-instance（platform + mappingCache）→ 按返回的 launch 启动；Fabric、NeoForge、Forge 1.20.1 三种实例都进世界
- [ ] 中文路径：Amber 经 ASCII 联接传所有路径（含 installDir、mappingsDir、mappingCache），烘焙和启动都不出乱码
- [ ] v1 的旧状态文件（没有 platform）照样按原版底座启动；旧缓存不失效（protocol 仍是 1）
- [ ] 官方 Forge 1.20.1 在 JDK 17 上的基线（本地 JDK 17 / JDK17_HOME）：和我们的 Forge 平台（Java 21）比较日志错误和模组行为；我们的加载器本身要 Java 21
- [ ] platform.production=false 只在开发时用：确认玩家实例从不传它

# 烘焙接口 v1.2：warm 和 gc（warm-gc 分支）

- [ ] Amber 在安装后后台跑 warm，用户中途点「开始游戏」时直接杀掉 warm：启动照常进世界，下次 warm 补齐，只留下 gc 能清的临时文件
- [ ] 多个实例共用一个 cacheRoot：gc 只在传齐所有实例的状态文件时才运行；少传一个实例时，Amber 的界面要能拦住（我们这边只能靠调用方传齐）
- [ ] 真实 Amber 数据目录（中文路径经 ASCII 联接）上 gc 的空跑列表：只列出已删除实例和旧版本留下的东西；确认后 apply，所有剩下的实例都还能进世界
- [ ] 启动时重新烘焙过的实例（日志里有重新烘焙）：再跑一次 warm 后 gc 不会列出它在用的文件
- [ ] 联接/符号链接放在缓存目录里（比如 Amber 把 baked 指到别的盘）：gc 跳过不跟进，不删链接外面的东西
- [ ] 文件被占用（游戏正在运行）时 apply：删不掉的文件在 failed 里报告，退出码 1，不影响正在运行的游戏

# Fabric 模组在 Forge 1.20.1 上（fabric-forge-window 分支）

- [ ] 测试模组注册的方块在存档里：退出再进同一个世界，方块还在、id 不变（Forge 的快照和重映射）
- [ ] 连到另一台机器上的同一套模组的服务器（只在测试网络里）：Fabric 注册的方块 id 两边一致
- [x] 下载的 Fabric API 0.92.12、Mod Menu、Lithium、FerriteCore 跑普查（2026-11-10，桥接第 1 组之后 13 个失败对）
- [ ] 真机上手动：右键实体、白天在床上睡、按 Mod Menu 的按键、挖泥土掉落（有 Fabric 战利品修改时）、看熔岩不崩
- [ ] 不用宽松模式（不带 -Damber.ul.mixinErrors=warn）启动 Forge + Fabric API：现在会停在第一个被 Forge 替换掉目标的注入器上（严格普查 60 个失败对），处理完严格清单后再试
- [x] 带 Fabric API 时 Forge 的两个注册表「加载后为空」：只是日志（Fabric 晚一次 validate）；但 Forge 全局战利品修改器确实失效了（Fabric 重建战利品表丢了表 id），已修，测试模组 glm-on 通过、glm-off 对照失败
- [ ] 真机上手动（严格模式）：在 Mod Menu 的模组列表里打字搜索、按 Esc 关界面；有 Fabric 界面事件的模组（比如在界面上画东西的小地图/提示类模组）显示正常
- [ ] 真机上手动（严格模式）：白天睡觉被 Fabric 模组允许时不会马上被叫醒；用 Fabric 模组的非床方块睡觉（如果整合包里有）起床位置对，原地不出现红床
- [ ] 真机上手动（严格模式）：有 Fabric 领地/保护类模组时，在受保护区域挖方块挖不掉、方块不闪；普通地方挖方块掉落正常（生存和创造都试）
- [ ] 真机上手动（严格模式）：Fabric 模组物品做合成后返还的物品（比如带容器的食材）、自定义攻击力、能挖的方块显示正确；原版水桶合成蛋糕仍返还空桶
- [ ] 真实整合包里的 Forge 模组全局战利品修改器（比如加宝藏/掉落的模组）和 Fabric API 一起：掉落正常
- [ ] 真机上手动（严格模式）：Fabric 模组的方块/物品颜色（比如染色的树叶、草类方块）显示对；挖 Fabric 方块的碎屑粒子颜色对；Fabric 模组自定义粒子能显示（不是紫黑格子）
- [ ] 真机上手动（严格模式）：有 Fabric 自定义着色器的模组（比如光影类或特效类 Fabric 模组）画面正常、日志里没有着色器加载错误；用 Fabric 数据包的 "fabric:remove" 去掉标签项的模组生效；Fabric 模组的自定义音乐/音效流（比如音乐盒类模组）能播放
- [ ] 真机上手动（严格模式）：用 Fabric 方块实体加载/卸载事件的模组（比如管道、机器类 Fabric 模组）放下、挖掉、远离再回来时不报错、不丢状态
- [ ] 真机上手动（严格模式）：漏斗和 Fabric 模组的箱子/机器之间送取物品（比如 Fabric 的存储类模组）；Fabric 模组加的燃料能放进熔炉烧
- [ ] 真机上手动（严格模式）：带 "fabric:load_conditions" 的 Fabric 模组战利品表（依赖另一个模组的那种），缺那个模组时不加载、不报错
- [ ] 真机上手动（严格模式）：用 Fabric 传送 API 的模组（比如 Fabric 的维度/传送类模组）把玩家从末地传回主世界：位置对、不放终末之诗；坏了维度的模组被删掉后存档仍能打开
- [ ] 真机上手动（严格模式）：用 Fabric 模型加载 API 改模型的模组（比如连接纹理、动态模型类 Fabric 模组）方块显示对
- [ ] 真机上手动（严格模式）：Fabric 模组加的液体（比如自定义液体的 Fabric 模组）贴图和颜色对；水、岩浆照常；玻璃/树叶旁边的水面侧面正常
- [ ] 真机上手动（严格模式，需要 维护者同意下载 Forge 1.20.1 专用服务器）：Forge 专用服务器 + 另一台客户端，都装 Fabric API 和一个用 Fabric 网络 API 的模组：进服不断线，模组的收发包正常（双向）
- [ ] 真机上手动（严格模式，同上专用服务器）：Fabric 模组的方块/物品和自定义注册表在远程客户端上 id 对得上（换装模组顺序后也对）；带数据包动态注册表的 Fabric 模组在客户端能读到同步的内容
- [ ] 真机上手动：Forge 1.20.1 实例里放只标了 NeoForge 1.20.1 的模组（声明依赖 neoforge 的那种），能正常启动进世界、模组功能正常
- [ ] 真机上手动：NeoForge 1.21.1 实例里放只出了 Forge 1.21.1 版的小模组（比如 Don't Cut Tripwire、Simple Harvesting、Soulbinding、Daily Expansion）：能进世界，模组功能正常（剪绊线不触发、右键收割、死亡后物品保留）
- [ ] 真机上手动：放一个 Amber 还不支持的 Forge 1.21.1 模组（比如 Wildlife Expanded: Skunk）：界面上报 forge_api_missing，说明哪个模组、为什么，不会崩
- [ ] 真机上手动（v1.3 接口）：Jay 用打包好的加载器文件夹（build/dist/amber-ul-0.3.2）而不是开发构建目录启动三个平台；manifest 的 sha256 校验通过
- [ ] 真机上手动（v1.3 接口）：游戏里有 Mixin 被停用时，Jay 从 runtime-problems.jsonl 读到 mixin_incompatible，界面列出模组名；没有问题时文件只有第一行
- [ ] 真机上手动（v1.3 接口）：选 Java 17 给 Forge 1.20.1 实例时，Jay 收到 java_too_old 并换到 21；第二次点"立即烘焙"时结果是 reused，不再跑 warm
- [ ] 真机上手动：Forge 1.20.1 实例里放只出了 Quilt 版的小模组（比如 Feed the Bees）：能进世界，喂蜜蜂/做粘液球配方等功能正常
- [ ] 真机上手动：Fabric 1.21.1 和 NeoForge 1.21.1 实例里放 Compost Bin（Quilt 专属，1.21 构建）：堆肥箱方块能合成、放下、打开界面，开关有声音
- [ ] 真机上手动：放 QSL（Quilted Fabric API）时 Jay 报 quilt_api_missing，说清是 QSL、为什么不能用，建议换 Fabric API；不崩

- [ ] 真机上手动（2026-11-11 lambda 修复）：Jay 里 NeoForge 1.21.1 实例装 Visuality + Cloth Config + Fabric API 0.116.18：能进世界，不再报 lambda$tick$32；打开/关闭界面、按键、鼠标点击界面都正常（Fabric 界面事件），睡床正常（实体事件里的 lambda）
- [ ] 真机上手动（2026-11-11 forgecompat-2）：Jay 里 NeoForge 1.21.1 实例装 Wildlife Expanded: Skunk（Forge 1.21.1 版）：能进世界；臭鼬能生成、能骑到肩上（能力 skunk:shouldermobs），退出重进后肩上的还在；局域网/服务器上另一个玩家能看到肩上的臭鼬（SimpleChannel 同步）；日志里 8 条 armor_material 覆盖警告属正常

# 沙盒启动（0.3.2，sandbox-paths）

- [ ] 真机上手动：Jay 在只给实例目录写权限的沙盒里启动三个平台（共享 cacheRoot/mappingCache 只读）；能进世界，共享缓存不变，.amber-ul/cache-overlay 只在 warm 前缺东西时出现

# Forge 1.12.2（0.4.0，forge-1122）

- [ ] 真机上手动：Jay 用 amber-ul-0.4.0 包建一个 Forge 1.12.2 实例（Java 8），小整合包和一个大整合包（RLCraft 或超新星帝国 2）各启动两次：第一次冷启动能进世界，第二次热启动命中 .amber-ul/legacy-1.12.2 下的转换缓存；用 Java 21 启动时看到 legacy_java_unsupported 提示

# 快速进入和维护模式（0.5.0，quickplay / maintenance）

- [ ] 真机上手动：Jay 用 amber-ul-0.5.0 包，在 1.21.1、Forge 1.20.1、Forge 1.12.2 各点一次"直接进世界"（quickPlay singleplayer）：不经过标题画面进入所选存档，存档显示名不变；1.12.2 再试一次直接进服务器（quickPlay multiplayer）
- [ ] 真机上手动：对一个旧版本存档跑维护模式（1.20.1/1.21.1 先升级），进度条走完后游戏自己退出；再用半径 64 预生成，中途点取消，下次维护从中断处继续；全程没有 eula.txt、没有独立服务端
