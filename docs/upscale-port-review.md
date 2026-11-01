# 图像增强(upscale)移植审查 — feature/image-upscale

参照系:`HaoweiLi97/mihon_img_upscale`(v1.3.9,master)相对其 mihon 基线(v1.1.5 时代)的 upscale 相关改动。
被审对象:`komikku feature/image-upscale` 分支相对 `master` 的全部变更。
结论先行:**核心链路(NCNN/Vulkan 推理、模型管理、Coil 解码管线、阅读器集成、设置 UI、QNN/NPU)已完整移植并在 SM8750 真机可用;发现并修复 2 个移植缺陷;另有 7 处有意差异(全部有理由),无未解释的遗漏。**

> 后续同步:上游 1.3.11 / 1.3.12 的移植审查见 [§6](#6-1311--1312-同步审查--syncupscale-1-3-11);上游 1.3.15 的移植与审查收尾见 [§7](#7-1315-同步与审查收尾--featupscale-1-3-15--fixupscale-review-followup)。

---

## 1. 逐文件对账

### 1.1 新增文件(上游新增 → 本分支新增)

| 上游文件 | 状态 | 说明 |
|---|---|---|
| `app/src/main/cpp/waifu2x.{h,cpp}`(Vulkan 分块推理、fp16/fp32 融合 compute shader) | ✅ 原样 | 自 v1.3.9 master 拷贝 |
| `app/src/main/cpp/waifu2x_jni.cpp`、`shaders.h`、`waifu2x_fused_*.comp` | ✅ 原样 | JNI 导出面与 Kotlin `external fun` 一一对应 |
| `app/src/main/cpp/anime4k.{h,cpp}`(GLSL Anime4K) | ✅ 原样 | 休眠能力:JNI/Kotlin 已接,UI 无入口(上游亦然) |
| `app/src/main/cpp/qnn_backend.{h,cpp}` | ✅ 原样 + 配置差异 | 见 §3.3,以 `MIHON_ENABLE_QNN=1` 编译 |
| `app/src/main/cpp/CMakeLists.txt` | ✏️ 适配 | 剔除 spatial/depth 源文件;QNN 只查头文件不查 SDK lib(运行库来自 AAR) |
| `third_party/ncnn-20260113-android-vulkan/`(99MB) | ✅ 原样 | 与上游同版本同布局;`local.properties`/`-PncnnSdkDir`/环境变量均可覆盖 |
| `third_party/qnn-include/` + LICENSE + README | 🆕 替代方案 | 上游用本地 QAIRT SDK 头文件;本分支 vendor 自 `qualcomm/geniex-qairt-plugin`(BSD-3-Clause,QNN C API 2.27,运行时 2.49 向后兼容)。**头文件无法从 release APK 获得** |
| `util/waifu2x/Waifu2x.kt` | ✏️ 适配 | 模型生命周期、assets 解压、QNN 初始化;**运行时 GitHub/HF 按需下载回退已移除**,模型全部捆绑、缺失即快速失败(`BUNDLED_MODEL_CACHE_VERSION` 随捆绑资产变更递增) |
| `util/waifu2x/ImageEnhancer.kt` | ✅ 原样 | 优先级队列(可见页 3/副页 2/提升 1/预载 0 + 距离 + FIFO)、抢占、代际取消 |
| `util/waifu2x/ImageEnhancementCache.kt` | ✏️ 适配 | 磁盘缓存(3GB 上限)、configHash、skip 标记、透明图拒收;**新增 `saveToCacheSync`(保留所有权),移除无调用者的异步保存路径**(见 §3.6) |
| `util/qnn/QualcommHtp.kt` | ✅ 原样 | HTP 架构探测(JNI),spatial 注释残留无碍 |
| `util/image/ImageFilter.kt`(ink 滤镜) | ❌ 跳过 | 见 §2 |
| `assets/` 模型(realcugan SE/Pro、realesrgan、waifu2x、nose、upconv7、span、sudo、anime4k) | ✏️ 适配 | 模型 ~87MB(qnn-contexts 另计 437MB,共 526MB);新增 w2xex-esrgan 三模型与 animejanai x2,删除未引用的 `realesrgan-models/anime/x4`;来源/许可证/校验和见 `docs/third-party-upscale-assets.md` 与 `docs/upscale-assets-sha256sums.txt` |
| `assets/qnn-contexts/`(150 个,437MB,v69–v81) | ✅ 原样 | 从上游 release APK 提取,字节级一致;上游是构建期从 `qnn.context.dir` stage,本分支直接入库 |
| `app/src/main/jniLibs/arm64-v8a/libQnnModelDlc.so` | ✅ 原样 | 从上游 release APK 提取;已验证 `libQnnHtp.so` 不依赖它(仅 DLC 模型路径用),对齐上游打包 |
| i18n:37 个 `reader_*` 字符串 | ✏️ 适配 | **KMR / i18n-kmk base**(上游是 MR / i18n),符合本仓库 i18n 规范;Weblate 可译 |

### 1.2 修改的既有文件

| 文件 | 上游改动 | 状态 | 说明 |
|---|---|---|---|
| `app/build.gradle.kts` | cmake 接入、NCNN/QNN 路径解析、QNN staging 任务、AAR 依赖、ndkVersion | ✏️ 适配 | 无 QNN staging 任务(上下文直接入库);`ndkVersion` 不钉死(用 AGP 默认,本机 27 自动装);AAR 依赖同上游 |
| `data/coil/Utils.kt` | +5 个 Coil 参数(enhanced/mangaId/chapterId/pageIndex/pageVariant) | ✅ 原样 | KMK 块 |
| `data/coil/TachiyomiImageDecoder.kt` | 重写:缓存优先 → 预缩放 → 推理 → 纹理上限 → 回写缓存;BitmapFactory 兜底;`DecodeResult?` 软失败 | ✏️ 适配 + 保留 SY | 保留 komikku 的 SY 封面归档(CbzCrypto)两条路径;ink 滤镜调用点未移植。**P1 修复**:移除全局解码串行、推理移至专用调度器、直接返回增强结果(见 §3.5/§3.6) |
| `ui/reader/model/ReaderPage.kt` | +`enhancementStream`/`enhancementKeySuffix` | ✏️ 适配 | 放在**类体**而非构造参数(构造参数会破坏既有尾随 lambda 调用点,上游 mihon 无此调用模式) |
| `ui/reader/setting/ReaderPreferences.kt` | +15 个 `realCugan*` 偏好 | ✅ 原样 | 键名与上游完全一致(升级用户偏好可迁移);未移植其遗留 `waifu2xEnabled`/`inkFilter*`/`realCuganProEnabled`(无 UI 引用) |
| `presentation/reader/settings/ColorFilterPage.kt` | +增强设置段 | ✏️ 适配 | `SettingsChipRow` 传 `StringResource`(komikku 组件签名与上游不同);字符串 KMR |
| `ui/reader/ReaderActivity.kt` | setUiBusy 全套(菜单/触摸/按键/销毁)、底栏增强开关按钮、spatial 场景 | ✏️ 部分移植 | setUiBusy ✅(`UI_BUSY_COOLDOWN_MS=1s`);底栏按钮 ❌(§2);spatial ❌ |
| `ui/reader/ReaderViewModel.kt` | `toggleImageEnhancement()`、`ImageEnhancer.reset()` | ✅ 原样 | reset 挂在 `init()`(komikku 的初始化入口与上游不同) |
| `loader/HttpPageLoader.kt` | preloadSize 联动 + internalLoadPage 增强流替换 | ✏️ 适配 | **本分支修正**:增强关闭时回落 komikku 的 `preloadSize()`(SY 偏好)而非上游的常量 4,避免关闭功能时行为回归 |
| `loader/DownloadPageLoader.kt` | loadPage 时触发增强 | ✅ 原样 | |
| `viewer/ReaderPageImageView.kt` | 485→1396 行:状态角标、增强图轮询、交叉淡入交换、缓存自愈、onPageSelected 剪枝 | ✏️ 部分移植 + 2 处修正 | 保留 komikku 的 KMK 缩放特性(disableZoomIn/doubleTapZoom/landscapeZoomScaleType)与 webtoon 长条 Coil 解码路径(并在该路径也接入增强);`enhancementDisplayEnabled` 为本分支新增开关;**修复 SSIV recycle 竞态**(§3.1)、**偏好 collector 绑定 attach/detach 生命周期**(§3.4) |
| `viewer/pager/PagerViewer.kt` | offscreenPageLimit 联动、destroy 时 cancelAll、hasSplitPage | ✏️ 适配 | `else 1` 保留 komikku 默认(上游 mihon 为 2);新增 `hasSplitPage` |
| `viewer/pager/PagerConfig.kt` | 16 个偏好变更监听(cancelAll + 清缓存 + 刷 adapter) | ✅ 原样 | |
| `viewer/pager/PagerPageHolder.kt` | 增强上下文绑定、缓存优先显示、拆页变体、合并双页 | ✏️ 适配(差异最大) | 见 §2 拆页设计与 §2.2 合并双页 |
| `viewer/webtoon/WebtoonViewer.kt` | destroy cancelAll、onPageSelected reprioritize、currentGlobalPageIndex | ✅ 原样 | |
| `viewer/webtoon/WebtoonConfig.kt` | 同 PagerConfig 监听(以 appContext 清缓存) | ✅ 原样 | |
| `viewer/webtoon/WebtoonPageHolder.kt` | (上游无此文件改动;本分支主动接入) | 🆕 本分支扩展 | webtoon 侧缓存优先显示;上游 webtoon 无拆页概念故无需变体 |
| `presentation/reader/appbars/*` | 底栏增强开关按钮 | ❌ 跳过 | §2 |
| `App.kt` / `SettingsAdvancedScreen` / `GLUtil` | 上游含 GLUtil(DEVICE_TEXTURE_LIMIT 等) | ⭕ N/A | komikku 自带(KMK 特性),直接使用 |

## 2. 有意差异(7 项)

1. **拆页增强重新设计**:上游 auto-split 模式对每个"半页"独立增强(`wide_left/right` 变体 + 拆分增强流),与其自研 PageSpreadDetector 深度耦合。komikku 用 SY 的 `dualPageSplit`(显示期拆分 + InsertPage 独立 holder),照搬会有重复插入 InsertPage 的风险。本分支方案:**整页增强 + 显示期重切**(`processEnhanced` 不再触发 `onPageSplit`,以 `hasSplitPage` 守卫缓存过早命中),两半共享同一份整页缓存。代价:拆页父页首载必须先看一次原始整页(与上游守卫语义一致)。
2. **SY 合并双页(extraPage)在线合并**:两源直接重跑 `mergePages` 会带副作用(fullPage 标记/插入拆页)。本分支改为:主副页各自独立增强(cache key 用各自的 `enhancementKeySuffix`),`ReaderPageImageView` 通过 `secondaryPage` 感知副页并在两半缓存都就绪后调用无副作用的 `mergeEnhancedPages` 在线换入;任一半未就绪或不可增强时保持原始显示,不阻塞拆页布局。
3. **底栏快捷开关按钮未移植**:komikku 底栏是 SY 的用户可配置按钮系统(`ReaderBottomButton` 枚举 + 设置页),上游是固定图标行,直接搬会破坏按钮自定义体系。`toggleImageEnhancement()` 已就绪,后续若要加按钮走 `ReaderBottomButton` 枚举扩展。
4. **ink 滤镜未移植**:上游隐藏偏好控制(无设置 UI、默认全关),与 upscale 无关。
5. **spatial depth(Depth Anything)未移植**:独立特性,非 upscale;相关 cpp/Kotlin/assets/字符串均剔除。
6. **QNN 构建方式**:上游 = 本地 QAIRT SDK(头文件 + DLC + 上下文生成本地化);本分支 = vendored 头文件 + Maven AAR 运行库 + 上下文直接入库。**构建不再依赖高通 SDK**,换来的成本是仓库 +~530MB 二进制(见 §4 待决)。
7. **遗留偏好不搬**:`pref_waifu2x_enabled`(上游旧版残留,无 UI)、`realCuganProEnabled`(Pro 由模型枚举决定)。新装用户与升级用户偏好键均与上游对齐。

## 3. 审查发现的问题(均已修复)

### 3.1 [已修] SSIV recycle 竞态崩溃(真机复现)
上游的 `clearProcessedSwapView`/`completeProcessedSwapTransition` 对**仍挂在视图树上的** SSIV 先 `recycle()` 后 `removeView()`;tachiyomiorg SSIV 的 `onDraw→sendStateChanged` 会解引用已被置空的 `vTranslate` → NPE 闪退(真机 PID 28748 复现,栈:`SubsamplingScaleImageView.sendStateChanged ← onDraw`)。修复:先 `removeView` 再 `recycle`,并在 recycle 前摘除 `OnStateChangedListener`。**上游代码存在同样的潜伏竞态。**

### 3.2 [已修] webtoon 预载页数回归(移植笔误)
上游 `HttpPageLoader.preloadSize` 回落值是常量 4(纯 mihon 语义);直译到 komikku 会覆盖 SY 的 `preloadSize()` 偏好。已改为回落 `readerPreferences.preloadSize().get()`。

### 3.3 [已修] 暂存区混入无关文件
`qnncheck/`(检查 AAR 的临时解包)曾误入 index,已移除;`gradle.properties`(+tooling.parallel)是用户在会话前的既有改动,**不属于本分支,提交时必须排除**。

### 3.4 [已修] 偏好 collector 泄漏(与视图生命周期解耦)
三个偏好 collector 原在 `ReaderPageImageView.init` 注册且挂在永不取消的 scope 上;pager/webtoon 的 RecyclerView 每创建一个页面视图都会新增一批常驻 collector(并持续读写已 detach 视图的 `statusView`),随滚动累积。修复:collector 改在 `onAttachedToWindow` 启动、`onDetachedFromWindow` 取消;`viewScope` 仅在旧实例已取消时才重建,声明处实例不再成为孤儿。

### 3.5 [已修] Coil 解码被全局串行 + 推理占用解码线程池
- 伴生对象上的 `Semaphore(1)` 把经此解码器的所有解码(正文页、封面等)串成单队列,上游无此串行 — 已移除。
- 数秒级推理原先跑在共享 Coil 解码线程池(3 线程)上,会饿死其它解码 — 改为 `Dispatchers.IO.limitedParallelism(1)` 专用调度器;增强的串行性仍由 `ImageEnhancer` 单 worker 与 native 锁保证。

### 3.6 [已修] 解码结果契约错误(显示依赖轮询竞争)
`decode()` 原返回原始位图、增强结果经异步队列落盘,显示正确性依赖 500ms 轮询与保存队列的竞争,且保存被拒时推理结果被静默丢弃。修复:新增 `ImageEnhancementCache.saveToCacheSync`(同步写入、调用方保留位图所有权),`decode()` 直接返回增强结果;并删除已无调用者的异步 `enqueueSaveToCache` 及其队列/worker。

### 3.7 [已修] 运行时模型下载回退与死代码
- 移除 `Waifu2x` 的 GitHub/HF 运行时下载回退:模型已全部随包提供,缺失属打包错误,改为快速失败而非在解码线程发起同步网络请求。
- `extractModelsToCache` 加 `@Synchronized`,避免多入口并发解压竞争。
- 删除无调用者的 `Waifu2x.process()`、`processingId` 与对应 `nativeProcess` 声明(cpp 侧符号仍由 `nativeProcessRealCugan` 内部使用)。

### 3.8 [已评估/不采纳] abort 定向化
曾将 `nativeAbortProcessing`/`nativeClearAbortProcessing` 由进程级标志改为按请求 id 定向,但未运行 id 的目标会残留,使后续推理被误杀(当前单 worker + 全局 native 锁下,原「每次 prepare 无条件清除」语义自愈且正确)。评估后回退,保持上游语义。

## 4. 机械检查结果

- ✅ `i18n/`、`i18n-sy/` 非 base 无任何新增(diff 为空) — 符合 AGENTS.md i18n 硬规则
- ✅ 字符串仅新增于 `i18n-kmk/.../base/strings.xml`(37 个)
- ✅ `spotlessApply` → `spotlessCheck` 通过;`assembleDebug` / `assemblePreview`(R8)通过
- ✅ 无上游标识泄漏(未动 `AppUpdateChecker.GITHUB_REPO`、applicationId 保持 `app.komikku`)
- ✅ 偏好键与上游一致,上游用户迁移路径可用
- ✅ `.gitattributes`:`*.param` 与 `*.bin`/`*.so`/`*.dlc`/`*.a` 一致标记为 binary,防止 ncnn 模型描述被行尾归一化
- ✅ `docs/upscale-assets-sha256sums.txt` 覆盖全部二进制与 ncnn `.param`
- ⚠️ 未跟踪的 `app/src/main/assets/Anime4K_*.glsl`(根目录 7 个)是用户本地既有文件,与本次移植无关,保持未跟踪

## 5. 遗留/待决事项

1. **仓库体积**:third_party(ncnn 99MB + qnn-include 1.9MB)、assets(~527MB)、libQnnModelDlc(3.2MB)拟全部入库(上游同样入库),仓库将增 ~630MB。备选:Git LFS / 下载脚本 / 只保留 v79 上下文 — **待确认后提交**。
2. `reader_image_enhancement_toast` 字符串目前无引用(为底栏按钮预留)。
3. NPU 上下文为静态资产;若未来要为新架构重新生成,需上游 `tools/qnn` 的 Docker 管线(未移植)。

## 6. 1.3.11 / 1.3.12 同步审查 — sync/upscale-1-3-11

参照系:`HaoweiLi97/mihon_img_upscale` 053c33caf(Release 1.3.11,父 65e3f1900)与 3e5aee233(Release 1.3.12,父 053c33caf)中增强/阅读器相关的改动子集。
被审对象:`sync/upscale-1-3-11` 分支相对 `master` 的 3 个提交(38382d22f、dc94dce90、8318dea84;18 文件,+1202/−348)。
结论先行:**三个提交均与提交信息声明一致;7 个原生 C++ 文件 + 2 个宿主测试与上游字节级一致;Kotlin 层全部偏差可归因到 §1–§2 的既定 KMK 适配;无范围蔓延、无实现错误。**

### 6.1 逐提交对账

**38382d22f — 1.3.11 原生与解码修复**

| 上游改动 | 状态 | 说明 |
|---|---|---|
| `monochrome_guard.h`(新) | ✅ 原样 | 64×64 快速拒绝采样、NEON + 可移植小端块路径、0.05% 弱色度噪声预算;替换 JNI 层的输入 RGB→灰采样归一化,有色调扫描与小块彩色区域保留源像素,中性页的伪色仍由共享输出通道抑制 |
| `periodic_texture_guard.h` | ✅ 原样 | 正交轴 70% 方向覆盖要求(局部笔画簇不再缩整块)、per-core TilePlan/Analysis 缓存、AxisTransform 圆整缩放比(Vulkan/QNN 共享)、占用率提前退出 |
| 75% shrink-and-restore 守卫限制到中性 Real-CUGAN Pro 页 | ✅ 一致 | `nativeInitRealCugan` 增加 `isPro` 参数(JNI → `g_realcugan_pro` → 守卫条件 `g_realcugan_pro && is_monochrome_input`);denoise3 图名启发式移除 |
| `TachiyomiImageDecoder` 源分辨率解码 | ✅ 一致 | 增强开启时按源分辨率解码,推理前不再被 `inSampleSize` 采样丢失细节 |
| `ImageEnhancementCache` 修订号 | ✅ 一致 | 新 `_dv` 输入解码修订(=1);纹理守卫修订 `_tv`(=3)条件 `model == 1 \|\| (model == 0 && noise == 3)`(全部 Real-CUGAN Pro + SE denoise3);monochrome 修订 `_nv`(=3);bundle/QNN 上下文版本 16/18 → **17/19**(本 fork 序列 +1 语义,上游 14/17 → 15/18) |
| 宿主回归测试 `app/src/test/cpp/` | ✅ 原样 | monochrome / periodic_texture 两文件;未接 Gradle/CI,仅头文件注释中的手动 `c++` 命令(上游同) |

**dc94dce90 — 1.3.12 阅读器稳定性修复**

| 上游改动 | 状态 | 说明 |
|---|---|---|
| `openmp_runtime.h`(新) | ✅ 原样 | JNI_OnLoad 设 `KMP_TOPOLOGY_METHOD=flat`,规避 Android 运行时在 libomp 计核与拓扑发现之间下线 CPU 导致的断言(Real-CUGAN 初始化路径) |
| `ImageEnhancementCache` 验证快照 API | ✅ 逐字一致 | `readCachedImage`(锁内字节快照 + 1/32 采样预解码拒绝损坏/空条目)、`decodeCachedImage`、compare-and-delete `discardCachedImage`、锁内 `removeCachedImage`(拒绝以失败/零字节编码覆盖页面唯一有效缓存) |
| `TachiyomiImageDecoder` 解码回落 | ✅ 一致 | `source.peek()` 预检原生解码器,失败仍可回落 BitmapFactory;缓存增强图一律经 `decodeCachedImage` 验证快照解码 |
| `ReaderPageImageView` 陈旧缓存拒收与恢复 | ✅ 一致 | `imageRevision` / `currentCachedFile` 追踪;处理源文件名编码的增强配置已过期则忽略;增强加载失败经 `originalImageSourceFactory` 回落原始页并每绑定重试一次增强 |
| `PagerPageHolder` 全路径验证 | ✏️ 适配(既定约定) | 初载、两个 factory、合并双页半图全部经 `readCachedImage` 验证并回落原始显示流;`buildOriginalDisplaySource` 复用非变更显示变换(`processEnhanced`/`mergeEnhancedPages`),恢复不再重插拆页。上游的 `shouldUseSplitDisplaySource` 系列辅助在本 fork 拆页设计(§2.1)下不存在 |

**8318dea84 — QNN 资产剥离构建(本 fork 自有,无上游对应)**

`-Pexclude-qnn-assets` → `strip-qnn.gradle.kts` 经 `finalizedBy` 挂到每个 `package<Variant>` 任务:重写非 arm64 split APK 剔除 `assets/qnn-contexts/*`、zipalign 对齐、debug keystore 重签;CI release job 用 `SIGNING_KEY`(release keystore)再签;universal 与 arm64-v8a 保留模型;不带该属性的构建不变。x86 split 体积 ~412MB → ~115MB。

### 6.2 移植取舍

每条记录:决定 / 放弃的替代方案 / 代价。

1. **原生层与上游字节级一致**:7 个 cpp 文件 + 2 个宿主测试逐字拷贝,komikku 侧可做的精简/改写一律不做。代价:komikku 无法在不破坏"字节级一致"属性的前提下裁剪未用路径;收益:后续 1.3.x 同步保持零噪声 diff,对账成本恒定。
2. **monochrome 穷举中性检测替代采样归一化**:选 64×64 快速拒绝 + NEON/可移植块路径 + 0.05% 弱色度噪声预算的穷举检测;放弃 1.3.11 之前的输入 RGB→灰采样归一化(会毁掉有色调扫描与小块彩色区域的源像素)。代价:每块多一次分析遍;接受中性页 ≤0.05% 的色度泄漏(伪色仍由共享输出通道抑制)。
3. **增强开启时按源分辨率解码**:选全分辨率解码进推理;放弃按阅读器显示尺寸预采样(省内存但推理前丢细节)。代价:解码峰值内存更高、推理输入更大;仅在增强开启时生效。
4. **缓存版本 16/18 → 17/19 + `_dv`/`_tv`/`_nv` 升版**:选升版而非兼容读取;放弃保留旧条目。代价:存量用户增强缓存整体失效一次(下次阅读重新推理);收益:旧守卫/旧解码路径产生的条目永不显示。`_tv3` 收窄到 `model == 1 || (model == 0 && noise == 3)` 而非全局后缀升版,是为了把失效范围限制在受影响模型。
5. **增强加载失败 → 回落原始页 + 每绑定重试一次增强**:选有界单次重试(`enhancedRecoveryCount`);放弃两种极端 — 不重试(损坏条目 = 该页永久降级,直到手动清缓存)与无限重试(失败循环)。代价:损坏条目在首次访问多花一次增强推理,随后被 compare-and-delete 丢弃。
6. **所有增强文件访问经 `readCachedImage` 验证**:选每次访问都做 1/32 采样预解码;放弃"只在首载验证"。代价:每次访问少量 CPU/分配;收益:损坏条目在完整解码与 SSIV 显示之前被拒绝。
7. **陈旧配置条目忽略而非立即删除**:文件名编码增强配置,过期条目在访问时忽略;磁盘回收交给既有 3GB→90% trim。放弃"发现即删"。代价:短暂多占磁盘;收益:不在显示路径(该文件可能正被当前页流式使用)上做删除,避免竞态。
8. **QNN 剥离选包后重写而非构建期分流**:AGP 未提供按 split 剔除 assets 的构建期配置,故 `finalizedBy` 挂包后任务重写 split。代价:每变体多一次重写 + zipalign + 重签的构建时间;本地产物为 debug keystore 签名(安装信任链由 CI release job 用 release keystore 重签终结);变体列表硬编码与 `apkDir` 锚定 `projectDirectory` 的漂移风险已在脚本注释内自我声明。

### 6.3 有意不移植(上游 1.3.11/1.3.12 其余部分)

1. **host 侧 source-api/LocalSource 改动**(3e5aee233)— 上游该半区是 TachiyomiX 1.6 合并更新 API 的桥接重做(`Source.getMangaUpdate` 经 `LegacyMangaUpdateContext` 协程上下文分发、`CatalogueSource` 桥委托 super、`HttpSource` 收敛 Rx 兜底、`LocalSource` 直接覆写 suspend API + 两个源测试),与 upscale 正交。取舍:本同步只声明读器/增强范围,不动 `source-api` ABI(AGENTS.md:避免破坏扩展 ABI);代价:1.6 扩展的桥接修复要等专门的同步轮次。
2. **androidTest**:`ImageEnhancementCacheTest.kt`、`NativeModelStartupTest.kt`(3e5aee233)— 设备 instrumentation 测试;本 fork 无模拟器/设备 CI,无法执行。缓存验证逻辑的回归保障由 `app/src/test/cpp` 宿主测试与 Kotlin 层对账覆盖。
3. **扩展兼容性文件**(053c33caf):`SearchSource`、`ExceptionFormatter`、proguard、gradle 升级 — 与 upscale 无关。
4. **`ReaderPreferences` model-20 HFA2Real 退役机制**(053c33caf)— fork 无 `REMOVED_ENHANCEMENT_MODELS`/`FIRST_CUSTOM_MODEL_ID` 机制,从未存在过模型 20。
5. **`app/build.gradle.kts` QNN staging 回退**(053c33caf)— fork 静态入库 `assets/qnn-contexts/`,无 staging 块(§1.1)。
6. **`tools/qnn/export_models.py` checkpoint 解包**(053c33caf)— fork 无 `tools/qnn`(§5.3)。

### 6.4 已审/排除的疑点(备查,避免重复误报)

- **`discardCachedImage` 的 `current != expectedSource` 不是恒等比较**:okio-jvm 3.17.0 的 `Buffer.equals` 为逐段内容比较(反汇编确认:引用相等 → instanceof → size → 逐段内容),且 `Buffer.close()` 是空操作(bytecode 仅 `return`)— compare-and-delete 语义正确,与上游一致。
- **`app/build.gradle.kts` 死导入**:剥离提交曾引入 9 个未使用导入(使用它们的逻辑在 `strip-qnn.gradle.kts` 内自行导入);ktlint 不检查 `.kts` 未用导入(`spotlessCheck` 不报错),已在 8318dea84 手工清理。

### 6.5 机械检查

- ✅ `:app:spotlessCheck` 通过;i18n 模块零触碰,KMK/SY 岛完整
- ✅ `assembleDebug` 通过
- ✅ 18 个文件全部映射到提交信息声明,无范围蔓延

## 7. 1.3.15 同步与审查收尾 — feat/upscale-1-3-15 + fix/upscale-review-followup

参照系:`HaoweiLi97/mihon_img_upscale` 1ab1420(Release 1.3.15)/v1.3.15 标签。
被审对象:本地 `master` 领先 `origin/master` 的 8 个提交(2026-10-21 合并,fast-forward 至 15d9b0a49),以及其后的审查收尾分支 `fix/upscale-review-followup`。
结论先行:**8 个提交与上游 v1.3.15 语义对齐,引擎 cpp 7 个文件字节级一致(见 §7.4);审查发现 5 项问题,均已在收尾分支处置(4 项修复、1 项决定保留);新增 18 个 JVM 单测全部通过。**

### 7.1 逐提交对账(8 个,2026-10-21 并入 master)

| 提交 | 内容 | 对齐状态 |
|---|---|---|
| dcef7ec90 | 增强失败显式失败而非伪装模型输出:解码路径的 enhanced 结果所有权契约(ownsResult/finally 回收)、透明图拒收后保留原图 | ✅ 与上游 1.3.15 语义一致 |
| 5d5d64ba1 | 缓存写盘队列防护:单槽 Channel + `pendingSaveKeys` 簿记、取消/清空不泄漏位图、`clear()` 与写盘以读写锁互斥(写盘持读锁全程) | ✅ 一致 |
| e6d2b413a | 增强队列串行化与重试竞态:worker 在同一 monitor 下 poll、`requeueOnCancel` 簿记、`processRequest` 代际/身份校验、被取消尝试不得抑制替代请求的重排队 | ✅ 一致 |
| f366fe980 | 状态标签跨轮询/重绑定稳定:`EnhancementPollKey`(revision+manga/chapter/page/variant/config)去重重启、`statusUpdateVersion` 拒绝陈旧回帖 | ✅ 一致 |
| f2e7c7305 | **QNN 设备编译路线**:`assets/qnn-models` 15 个 FP16 DLC(33.6MB,取自 v1.3.15 release APK)取代 437MB 预编译 contexts;`QnnModelContexts` 按设备在 `noBackupFilesDir` 编译并持久化(缓存键含 CACHE_VERSION=20/QNN 2.49.0/DLC hash/HTP 架构/Build.FINGERPRINT/ABI,checksum 校验,新 context 就绪后惰性回收同模型旧 key 目录);删除 `strip-qnn.gradle.kts` 与 `-Pexclude-qnn-assets`;INT8 全退役,精度枚举收窄为 FP16/FP32/BF16(legacy 2 → FP16),QNN 缓存键固定 `_nf1`;`waifu2x_jni.cpp`/`spatial_qnn_runtime.cpp`/`qnn_context_bridge.cpp` 字节级恢复 | ✅ 一致(资产来源见 §7.4) |
| 1f72f570a | **MediaTek NPU 后端**:`MediaTekNpu`(LiteRT 2.3.0 + NeuroPilot dispatch,15 个 tflite 32.8 MiB 设备编译,`litert-models/manifest.json` 校验和)、Manifest 6 个 `libneuron*` 可选 native-library、设置页后端枚举 + 准备状态(Preparing/Ready/Failed)+ 重试、默认后端 MTK 优先(次选 Qualcomm,回落 Vulkan)、`NpuModelStatus` 共享状态流 | ✅ 一致 |
| 1715ba574 | zh-rCN 4 个字符串(联发科 NPU / NPU 准备状态×3) | ⚠️ 有意偏离 i18n 规则,见 §7.3-1 |
| 15d9b0a49 | 状态徽标把 MediaTek NPU 处理页标注为 LiteRT(与后端实际运行时一致) | ✅ 一致 |

上游自述 MediaTek 路径未在真机验证(其测试机为 SM8475,Qualcomm);komikku 同样未验证。

### 7.2 审查收尾(fix/upscale-review-followup)

审查发现的 5 项及处置:

1. **zh-rCN 非 base locale 直接编辑(1715ba574)— 决定保留**。与 AGENTS.md "非 base locale 由 Weblate 接管"的硬规则冲突(自查项明确禁止);译者决定接受偏离并在此记录,理由是上游措辞直接可复用、避免中文用户在 Weblate 同步前长期看到英文设置文案。已知代价:Weblate 下次同步该 4 键可能冲突,届时以 Weblate 为准。
2. **[已修] `AndroidManifest.xml` KMK 标记未闭合**:6 个 `libneuron*` 声明的 `<!-- KMK -->` 无闭合。XML 注释内容不允许 `--`,沿用仓库既有写法 `<!-- KMK -> -->` / `<!-- KMK <- -->`(同 `res/layout/edit_manga_dialog.xml`)。
3. **[已修] `MediaTekNpu.prepareForCpuValidation` 死代码删除**:上游由 androidTest `MediaTekNpuDeviceTest` 调用,该测试未移植(§7.3-3),钩子在 komikku 无调用者。连同 `prepareInternal` 的 `cpuValidationOnly` 参数与 `litert-cpu-validation` 目录分支一并清理;JNI 侧 `nativeInitialize` 的 `cpuTest`/`prepareOnly` 参数保留(引擎接口字节级一致的前提)。
4. **[已补] JVM 单测**:见 §7.5。
5. **[已修] `app/build.gradle.kts` 冗余嵌套 KMK 标记**:litert 依赖本就在外层 KMK 块内,删除多余的 `// KMK -->`/`// KMK <--` 对。

### 7.3 移植取舍与已决事项

1. **zh-rCN 偏离**:同 §7.2-1,已决:保留。
2. **QNN 资产路线**:选"FP16 DLC + 设备编译"替代"预编译 contexts 入库 + APK 剥离构建"。收益:仓库 −437MB、非 arm64 split 不再需要包后重写、DLC 与 runtime 版本解耦;代价:首次使用每模型一次秒级设备编译(失败显式化,UI 可重试),旧版本静态 context 用户升级后 `cacheDir/qnn-contexts-v*` 残留(无害,系统可回收)。
3. **androidTest 维持不移植**(延续 §6.3-2):上游 `ImageEnhancementCacheTest`/`ImageEnhancerQueueTest`/`MediaTekNpuDeviceTest`/`QualcommHtpDeviceTest`/`NativeModelStartupTest` 均为设备 instrumentation,本 fork 无设备 CI。其中可纯 JVM 化的断言改写入 `app/src/test`(§7.5);位图/真机依赖的用例保持上游-only。`prepareForCpuValidation` 因此删除(§7.2-3)。
4. **默认后端设备自适应**:`realCuganProcessingBackend` 默认值由常量 1(Qualcomm)改为 `Waifu2x.defaultProcessingBackend()`(MTK → MediaTek,Qualcomm HTP 可用 → Qualcomm,否则 Vulkan)。仅影响新装用户;升级用户保留既有存储值。
5. **增强失败不写 skip marker(与上游一致,已核对)**:模型全部随 APK 分发并在设备上编译,失败是可重试状态而非永久跳过,故 `TachiyomiImageDecoder` 在 NPU 初始化失败或模型无 NPU 资产时只保留原图、不落 `.skip`;`MediaTekNpu`/`QnnModelContexts` 自行记住失败,仅设置页显式重试(或新进程)才重新编译。为免轮询循环反复整页解码,komikku 在 `ReaderPageImageView` 的 poll heal 分支加了每次绑定最多 `MAX_HEAL_ATTEMPTS` 次重入队(上游无此上限)。
6. **补齐两处上游行为(审查发现,已修)**:①`ImageEnhancer.enhanceLazy` 的"已提升为高优先级"早退分支漏了 `preemptForVisibleRequest`,导致可见页无法抢占正在处理的其他页,已按上游补回;②`ImageEnhancementCache.removeSkipMarker` 在本 fork 无调用者(上游由 `PagerPageHolder.ensureVisibleExtraPageEnhancement` 调用),已改为在 `ReaderPageImageView.onPageSelected` 中对主/副页各解除一次,使因分辨率超限被跳过的页在用户翻回时重试,且只在选中时清除、不在重绑定时清除,避免失败页循环。

### 7.4 机械检查(审查时复验)

- ✅ 引擎 cpp 与上游 v1.3.15 字节级一致:`float_tile_processor.h`、`litert_backend.{h,cpp}`、`qnn_context_bridge.cpp`、`spatial_qnn_runtime.{h,cpp}`、`waifu2x_jni.cpp`(git blob 哈希对比);`CMakeLists.txt` 差异仅为构建集成(新增 3 源文件、litert 头文件路径、移除 HTP 架构硬编码)
- ✅ `qnn-models` 15 个 DLC 与 `litert-models` 15 个 tflite 的 SHA-256 均与各自 `manifest.json` 一致,键与文件一一对应
- ✅ `spotlessCheck` 通过;无 `exclude-qnn-assets`/`strip-qnn` 残留引用;`.gitattributes` 已补 `*.tflite binary`
- ✅ i18n:base 新增 4 键合规;zh-rCN 为已记录的有意偏离(§7.2-1)

### 7.5 单元测试(app/src/test,JVM)

蓝本为上游 androidTest,JVM 化后可入 CI(`:app:testReleaseUnitTest`),共 18 用例:

| 测试类 | 覆盖 |
|---|---|
| `ImageEnhancerPriorityTest` | 队列排序语义:可见页(3) > 副页(2) > 提升页(1) > 预载(0)、同级按目标距离、再按 FIFO;副页属于聚焦目标(对应上游 `queuedPagesFollowVisibleSpreadThenPromotionThenDistance` 的断言部分) |
| `ImageEnhancementCacheKeyTest` | `CacheEvent.affects` 键匹配矩阵(Cleared 全命中);configHash 按后端分离(`_qv*_nf1`/`_mtv*`)、legacy INT8 → FP16、纹理守卫修订只作用于 Pro 与 SE denoise3、固定 2x 模型忽略偏好 scale |
| `Waifu2xBackendResolutionTest` | NPU 模型支持矩阵(Pro 2x/3x,其余 2x,Vulkan-only 模型不可路由);`qnnModelName` 与捆绑资产名对齐(含 Pro noise 规范化);`resolveProcessingBackend` 保留显式 NPU 选择、拒绝越界;NPU 强制 FP16 |
| `ImageEnhancementCacheDiscardTest` | compare-and-delete 语义:陈旧快照不得删除并发重存的新条目、匹配快照删除、无快照无条件删除(对应上游 `rejectingAnOldSnapshotDoesNotDeleteItsNewReplacement`) |

实现说明:`discardCachedImage` 成功路径走 `android.util.Log`,JVM 测试内以 `mockkStatic(Log)` 局部放行(不改全局 `isReturnDefaultValues`);`ImageEnhancer`/`Waifu2x`/`QnnModelContexts`/`MediaTekNpu` 的 object 初始化在 JVM 安全(loadLibrary 均有兜底)。位图/Context 依赖的写盘与入队行为仍以真机验证为准。

## 8. 1.3.16 同步 — feat/upscale-1-3-16-sync

参照系:`HaoweiLi97/mihon_img_upscale` cf6eacd(Release 1.3.16)+ b8b5da7(修正提交),合计即 v1.3.16 标签;基线为已移植的 1ab1420(v1.3.15)。
结论先行:**引擎层(cpp、模型资产、导出脚本)字节级复制;宿主层 6 个文件语义重放;fork 特有的 spread 管线与 mpreg 解码器相关改动不适用(见 §8.3)。**

### 8.1 上游两个提交的内容

| 提交 | 内容 |
|---|---|
| cf6eacd | ① 重生成 Real-ESRGAN Photo NCNN 模型(x4.bin/x4.param,官方权重);② 读者队列/首帧:HttpPageLoader 先发布原始流并标 Ready,增强改异步入队;`ReaderPageImageView` 以 `hasDisplayedImage`+`pendingInitialEnhancement` 把增强推迟到首帧显示后两动画帧,`ImageEnhancer.waitingForVisibleImage`/`onVisibleImageDisplayed` 在首帧前挂起队列;③ 解码内存:`EnhancementImageDecoder.decodeToBitmap`(mpreg 解码器内采样)与 `ImageUtil.decodeBitmap` 直解,省去整帧中转;④ PagerPageHolder 释放时机(onDeselect recycle)、拆页裁剪画中裁(contentRect 替代 trimBitmapBorders)、初始选中恢复(hasReceivedSelection);PagerViewer `isSelectedHolder` + `currentGlobalPageIndex` 锚点;⑤ 缓存:saveQueue RENDEZVOUS、缓存键 `_rp1`;BUNDLED_MODEL_CACHE_VERSION 15→16 |
| b8b5da7 | ① x4.param 的 Interp 残差上采样参数 `1=4 2=4` → `1=4.0 2=4.0`(NCNN 文本参数按拼写定类型,整型拼写被读作整型比特导致零尺寸 resize → 输出全黑);② `waifu2x.cpp` staged 推理显式检查 `ex.input`/`ex.extract` 返回码并 return -1,tile 失败由 continue 改 return -1(拒绝失败推理,防止黑图入缓存);③ 缓存键 `_rp1`→`_rp2`;BUNDLED_MODEL_CACHE_VERSION 16→17;④ androidTest `RealEsrganPhotoTest` |

### 8.2 本次移植(分支 feat/upscale-1-3-16-sync)

**引擎层(字节级复制,git archive 自 b8b5da7,SHA-256 复验一致):**

| 文件 | 说明 |
|---|---|
| `app/src/main/cpp/waifu2x.cpp` | staged 推理失败显式化(b8b5da7;komikku 原文件与 v1.3.15 字节一致,复制后与上游 v1.3.16 一致) |
| `app/src/main/assets/realesrgan-models/v3-general/x4.{bin,param}` | 官方权重重生成图(bin 2,435,261→2,435,272 字节;param 含 `1=4.0 2=4.0` 修正) |
| `tools/ncnn/export_realesrgan_photo.py` | 新增导出脚本(权重尺寸/SHA256 校验、二进制写入警示、修正后的 resize 参数);komikku 首次引入 `tools/` 目录 |

**宿主层(语义重放,KMK 标记;`android.util.Log.i` 调试日志按本仓库规范并入既有 `logcat`/省略):**

| 文件 | 重放内容 |
|---|---|
| `HttpPageLoader.kt` | 加载器不再同步查缓存/初始化缓存:恒发布原始流 + Ready;增强经 `scope.launch` 异步入队(enhanceLazy 对已缓存/跳过页早退)。可见页的缓存换入由视图层 `processImageHelper`(缓存优先)承担 |
| `ReaderPageImageView.kt` | `hasDisplayedImage`/`pendingInitialEnhancement`;`onImageLoaded` 记录首帧并 `notifyVisibleImageDisplayed()`(两动画帧后执行挂起回调 + `ImageEnhancer.onVisibleImageDisplayed`);`onPageSelected` 增强块加首帧门控;三个内联触发点(BitmapDrawable/SSIV 流/webtoon Coil)统一改为绑定时登记回调;`setImage`×2 与 `recycle()` 重置标记 |
| `ImageEnhancer.kt` | `waitingForVisibleImage` 挂起 + `onVisibleImageDisplayed` 放行;worker poll 门控;`enhanceLazy` 在 runtime 初始化/缓存 I/O 之前捕获代数并去 `@Synchronized`,入队拆为 `@Synchronized enqueueRequest`(只保护内存队列状态,代数校验前置) |
| `ImageEnhancementCache.kt` | 缓存键新增 `_rp$REAL_ESRGAN_PHOTO_NCNN_REVISION`(=2,仅 Real-ESRGAN Photo + Vulkan) |
| `Waifu2x.kt` | `BUNDLED_MODEL_CACHE_VERSION` 17→18(komikku 版本线独立,取下一值),强制重取新模型资产 |
| `PagerViewer.kt` | `isSelectedHolder`;`onReaderPageSelected` 锚定 `ReaderPageImageView.currentGlobalPageIndex`(初始 spread 可能在 holder 挂载前被选中) |
| `PagerPageHolder.kt` | `hasReceivedSelection`;`onImageLoaded` 时若尚未收到选中且是当前选中 holder,则补发 `onPageSelected`(恢复 ImageEnhancer 目标页,避免首帧放行的 `isFocusedTarget` 校验失败导致队列永久挂起) |

**测试:** `ImageEnhancementCacheKeyTest` 新增 `_rp2` 作用域用例(Photo+Vulkan 独有;Anime 风格/QNN/Real-CUGAN 不含)。

### 8.3 不适用项(与 §6.3/§7.3 同类取舍)

1. **mpreg 解码器路径 N/A**:`EnhancementImageDecoder.decodeToBitmap` 与 `ImageUtil.decodeBitmap` 的"解码直出位图"内存优化依赖 fork 私有 `ca.mpreg.imagedecoder`;komikku 用 `tachiyomi.decoder`(mihon 上游),`ImageUtil` 为上游文件,不移植。komikku 的增强解码仍走 `tachiyomi.decoder` 路径。
2. **saveQueue RENDEZVOUS 不重放**:komikku 的写盘管线(capacity=1 Channel + `limitedParallelism(1)` 写盘 dispatcher,§5)与 RENDEZVOUS 同样把在飞增强位图上界压到两份(1 编码 + 1 缓冲/交接),fork 该项修复的意图已满足。
3. **PagerPageHolder spread 管线改动 N/A**:fork 的 onDeselect recycle、`contentRect` 画中裁(替代 `trimBitmapBorders`)作用于其私有 spread/拆页管线(BookPageImageSource、halfStatusView 等),komikku 未移植该管线,双页路径沿用 SY `dualPageSplit`/`mergePages`;`hasReceivedSelection` 恢复逻辑与 fork 管线无关,已按 komikku 结构重放(见 §8.2)。
4. **androidTest `RealEsrganPhotoTest` 维持不移植**(§7.3-3):设备 instrumentation;其可 JVM 化的断言(缓存键修订作用域)已入 `ImageEnhancementCacheKeyTest`。
5. **CHANGELOG/versionCode 不移植**:komikku 版本与变更日志独立管理。

### 8.4 机械检查(移植后执行,见提交前验证)

- 引擎 3 文件 + 导出脚本与 b8b5da7 SHA-256 一致;`cpp/waifu2x.cpp` 与 v1.3.15 基线仅含本修复差异
- `spotlessApply`/`spotlessCheck`、`:app:compileDebugKotlin`、`:app:testReleaseUnitTest`(waifu2x 包)通过
- i18n 无改动;无新增非 base locale 字符串

### 8.5 移植后修复 — 双页换入滞留(fix/upscale-double-page-swap)

真机回归(1.3.16 移植后):双页(拆分/合并)下增强图偶发滞留——状态角标持续"处理中",直到手动翻页才换入;拆分模式的 InsertPage holder(独立 holder、独立轮询)尤为明显。调整 heal 次数上限无效。

根因:`ReaderPageImageView` 轮询内的缓存检查是事件驱动单点(`shouldCheckCache` 仅由 `CacheEvent.Saved/Skipped` 置位);`CacheEvent` 流为 `replay=32/DROP_OLDEST`,事件可被后续事件挤出。同时 heal 分支的重新入队对"条目已在缓存"的页被 `ImageEnhancer.enhanceLazy` 的 isCached 早退吞掉(既不换入也不产生任何动作)。净效果:成品已在缓存而轮询不重查,唯一的其他缓存查询点是 `onPageSelected`(手动翻页)。拆分双页主页 holder 与 InsertPage holder 各自独立轮询同一缓存键,失效概率叠加;InsertPage holder 创建晚于 `onPageSplit`,其轮询启动晚于 worker 完成的窗口最大。

补充根因(复查时确认):轮询的事件过滤器 `filter { it.affects(主键) }` 只匹配主半键——**合并双页副半的 Saved 事件对主半轮询天然不可达**。主半先完成时,副半的完成无法唤醒轮询,这是"副页无法换入/延迟换入"最直接的结构性机制(早于事件丢失就存在)。同键设计的拆分双页(主页/InsertPage 共用 `(index, "")`)不受此影响。

修复(轮询兜底分支,requested=false 时):

- 先重查缓存再入队:命中即换入(合并双页需两半齐,经 `buildEnhancedDisplaySource`);条目损坏走 `healInvalidEnhancedCache`(丢弃 + 重入队)后落穿既有 heal 逻辑。换入不再依赖单一缓存事件。
- 事件过滤器追加副半键(`secondaryKey`):副半完成即时触发轮询的缓存检查,不再依赖 ≤500ms 的兜底轮询周期。
- `ImageEnhancer.processRequest`:`dataProvider` 返回 null 从静默丢弃改为 WARN 日志(数据源消失与队列滞留在日志上可区分)。
- `ImageEnhancer.onVisibleImageDisplayed`:首帧放行的 target 不匹配从静默改为 INFO 日志(`waitingForVisibleImage` 挂起从此可诊断)。

复查确认的安全性边界:

- 兜底重查块**不需要** `hasSplitPage` 守卫(对比 `currentEnhancedFile` 的首显守卫):轮询由 `processImageHelper` 启动(首帧后两帧),而 `onPageSplit` 的 InsertPage 插入在首帧前已 post 到主线程——轮询的生命周期总是晚于拆页插入,缓存命中时兄弟半页必然已存在。若未来把轮询启动点提前到首帧之前,必须补回该守卫。
- 重查块对"真实未缓存页"只在入队成功前执行 1–2 次(入队后 requested=true 不再进入该分支),不产生持续磁盘轮询。
- 任一半处理期间 `requested=true` 使轮询保持存活,两半齐后的下一兜底周期(或副半事件,见过滤器强化)即换入;合并双页"任一半先完成"的两种时序均闭环。
- "无法合并 → RAW 退出"镜像既有 `shouldCheckCache` 路径语义;动图/宽页在正常流程中已被 `mergePages`/`splitDoublePages` 重构 holder(副页置空、revision 递增取消旧轮询),不会到达该分支。
- `withUIContext` 被取消时 `transformedSource` 流依赖 GC 回收——与既有 `shouldCheckCache` 路径同模式,非本提交引入。

### 8.6 真机回归定位与最终修复(三态恢复,fix/upscale-double-page-swap)

真机日志(logcat 抓取 + 诊断日志两轮迭代)定位到仍滞留的完整机制,以 (5,6) 与 (13,14) spread 为例:

1. 用户快速翻页途中(offscreen 预载,`currentGlobalPageIndex` 落后于 holder 位置),spread 的 holder 提前创建,主半先完成增强;
2. 副半(或主半)的请求被剪枝窗口(以落后的 `currentGlobalPageIndex` 计算)剪掉;
3. 轮询收到已完成半的 Saved 事件 → 兜底重查:命中半存在、缺失半不存在 → 不换入 → heal 窗口判定(同样以落后的 `currentGlobalPageIndex` 计算)**失败 → 轮询 RAW 退出**;
4. 用户翻到该 spread → `onPageSelected` 重新入队缺失半 → 缺失半完成(Saved)——**但轮询已死,第二个 Saved 事件无人接收** → 两半永齐不了换入 → 角标"处理中"卡死。

根因本质:**heal 窗口只应限制"重新入队"的频率(防无限解码),却连带杀死了观察者本身**——轮询的设计意图是"attach 期间持续观察,`onDetachedFromWindow` 才停止",窗口失败退出违背了这一点;且窗口按主半 index 计算,对"预载方向上的合并双页"系统性误杀(剪枝与 heal 用同一个落后锚点)。

最终修复(轮询兜底分支重构为三态恢复):

- **主半缓存 ✓、副半缺失**:有界重推副半(`Re-queueing sibling page N`),继续等待;
- **主半缺失、副半缓存 ✓**(对称情形,第二轮复现发现):有界重推主半(`Re-queueing primary page N`),继续等待;
- **两半都缺失**:保留原有按窗口的有界重推;窗口失败时**降级为 RAW 但轮询不退出**(`Poll degraded to raw`,每次绑定只打一次 WARN),继续观察事件——翻页回来时 `onPageSelected` 的重新入队/直接换入路径与活着的轮询互补;
- 轮询的生命周期回归 `onDetachedFromWindow`;heal 重试次数上限(`MAX_HEAL_ATTEMPTS`)语义不变——它现在只限制解码重试,不再终止观察。

真机验证:快速翻页 + 停留场景下,(1,2)/(3,4)/(7,8)/(17,18)/(19,20) 等曾被剪枝/滞留的 spread 全部被自动恢复换入;修复前必现的"处理中"卡死不再出现。诊断日志保留集(排查用临时日志已剔除):`Re-queueing sibling/primary`(WARN)、`Poll degraded to raw`(WARN)、`Fallback cannot merge/invalid entry`(WARN)、`ImageEnhancer` 的数据源丢弃 WARN 与首帧 target 不匹配 INFO。

验证:`spotlessCheck`、`:app:compileDebugKotlin`、waifu2x 包 JVM 单测通过。

## 9. 1.3.17 增强部分同步 — b8b5da7 → cffd495 净差异

参照系:基线为已移植的 1.3.16(b8b5da7,cf6eacd+b8b5da7 合成),上游推进至 stable 1.3.17(cffd495)。上游 1.3.16 之后共 8 个提交,其中 fd047fb(beta 1 端上 W8A16 校准)被 b8f90ed(beta 2)整体撤销、784e483(独立包名/签名/图标)不适用、7f4a8de 的本地画廊大部分不适用。本次移植范围:**增强相关内容 = 7f4a8de 的 loader 可见页优先级意图 + 9fd1393(beta 4)+ 0ee1e20(测试对齐)+ 0f271bf(最终修正)的净语义**。beta 2(W8A8 模型选项)此前已单独移植(见暂存区,§9.4 顺带补缺)。

### 9.1 语义重放(KMK 标记;上游 1.3.17 changelog 对应)

| 文件 | 重放内容 |
|---|---|
| `ImageEnhancer.kt` | ① `ProcessingPhase`(PREPARING/ENHANCING/SAVING)+ `activePhase`,claim 时置 PREPARING;`processingPhase()`(活跃且未取消才返回,否则 null 供状态轮询回退)/`updateProcessingPhase()`;② worker 循环与 `processRequest` 的 OOM 捕获(后者 `saveSkippedToCache` 后保留原图,防止轮询无限重试);③ `ImageRequest.allowHardware(false)`(硬件位图喂不了原生推理/缓存编码);④ 抢占阈值放宽:`activePageIndex < targetPageIndex - 2`、`abs(activePageIndex - pageIndex) > 2`——普通翻页时邻近工作放行入缓存 |
| `ImageEnhancementCache.kt` | 写盘循环 OOM 捕获:标记 skipped 后继续排水,一次失败编码不再卡死写盘协程 |
| `Waifu2x.kt` | `resolvePrecision` NPU 分支补 `|| requestedPrecision == 4`:beta 1 遗留的端上 W8A16 设置值迁移到 INT8(仅 INT8-capable 模型),beta 2 移植缺项 |
| `TachiyomiImageDecoder.kt` | ① `markEnhancementSkipped()`:skip 决策点(尺寸限制/NPU 不支持)、initialized-but-null 推理失败(先 `ensureActive` 区分取消)、Exception/OOM 兜底各路径统一落 skipped 标记,状态轮询不再无限重试;② ENHANCING(推理前)/SAVING(缓存交接前)阶段上报;③ 顶层 OOM 捕获返回 null |
| `EnhancementStatusText.kt` | 新增 `Context.activeEnhancementLabel()`:PREPARING → "PREPARING MODEL..."、SAVING → "FINISHING..."、ENHANCING → 百分比/ENHANCING;null 回退 |
| `ReaderPageImageView.kt` | ① `setProcessedSource` 加 `forceRefresh` + `currentLoadedUri` 防重(同 URI 直接关流返回,不再重置正在绘制的活跃图);② 首查(processImageHelper)缓存命中不再同步解码/变换——统一 `startEnhancementPolling`,由观察者异步换入;③ 轮询:`pageVariant` 取自 key(与去重键一致)、缓存命中分支 alreadyDisplayed 早退(完成通知丢失时仅恢复标签,跳过解码)、全部 `setProcessedSource` 调用加 revision 守卫、`activeEnhancementLabel` 阶段标签接入、OOM 捕获回退 RAW、`cacheEvents` 提升到 try 外 + `finally cancel()`(SharedFlow 永不完成,生产者不得滞留);④ 轮询 `pid==pIdx` 分支保留为副半进度回退 |
| `PagerViewer.kt` | `offscreenPageLimit` 增强开启时钳 2(请求持有流工厂、holder 持有解码位图,更大增强队列不得等比分配全尺寸视图);关闭保持 1 |
| `PageLoader.kt` | 新钩子 `onPageSelected(page)`:加载器获知可见页 |
| `HttpPageLoader.kt` | ① `onPageSelected`:可见页 Error→Queue + 以 retry 同级(2)入队,离屏 holder 不得改优先级;② `internalLoadPage` 取消 → Queue(非 Error),回到页面可重新加载,不再闪错误角标;③ worker collect 原子认领(mihon 遗产竞态):同页双条目(onPageSelected 2 + holder 1 常态共存)在 `isImageInCache` 磁盘窗口内可双通过 filter 并发双下载——认领即在页监视器下离 Queue,第二个条目被 filter/认领双重拦截,`LoadPage` 与 `Queue` 在 holder 渲染相同故无 UI 闪变(上游对应机制:launchLoadOnce 原子占槽) |
| `ReaderViewModel.kt` | ① `onPageSelected` 插入 `page.chapter.pageLoader?.onPageSelected(page)`(InsertPage 早退之后);② `ImageEnhancer.reset(page ?: chapter.last_page_read)`:首帧门控锚定实际打开页,替代锚 0 |
| i18n-kmk base | 恢复 `reader_status_processed/finishing/enhancing`(285e7af7b 删除的阶段定义,zh-rCN/zh-rTW 孤儿翻译由此激活;processed 暂无代码引用)+ 上游新增 `reader_status_preparing`;`reader_status_enhancing_progress` 维持 096823017 的纯百分比 `%1$d%%`(主动设计,不回退) |
| `ReaderPageImageView.kt`(回退 d68236e31) | 收尾中恢复为独立阶段:轮询 `requested && wasEnhancing` 分支从 backend 名改回 `reader_status_finishing`(推理结束、请求仍挂起的缓存写盘窗口);1.3.17 的 SAVING 阶段标签保留,两者覆盖不同窗口(解码侧/活跃请求清除后) |

### 9.2 不适用项

1. **HttpPageLoader 调度架构重写 N/A**:上游以 `downloadJobs`+`scheduleLoads`+`preloadWindow`(PAGES_AHEAD=8/BEHIND=2/CONCURRENT_PRELOADS=2)替换 rolling chunk;komikku 是 mihon/SY 优先级队列 + N reader 线程 + EXH 扩展(aggressivePageLoading/readerThreads/dataSaver/instantRetry),整体替换会丢弃这些行为。语义目标(可见页优先、翻页保留邻近下载、取消不丢页、同页认领串行)由 9.1 的等价点达成。
2. **上游 onPageSelected 大简化 N/A**:上游把选中块收敛为单次 `startEnhancementPolling`(其轮询承担入队);komikku 轮询保持 §8.5/8.6 三态恢复,双页半页处理(合并换入/副半跳过标记清理)比上游完整,保留本地结构。
3. **Webtoon strip 分支 N/A**:上游 `setStripImage` 依赖其 strip 渲染器;komikku webtoon 走 enhancedOverlay,输出已在解码器受 `DEVICE_TEXTURE_LIMIT` 约束(等价目标:防止超纹理位图进 Canvas)。
4. **PagerPageHolder halfStatusView/smoothRefreshCombinedDisplay N/A**:fork 私有 spread 管线(§8.3-3),komikku 无对应路径;`forceRefresh=true` 的调用方不存在,参数保留为 API 对齐与去重逃生门。
5. **轮询 `checkedCache` 单次守卫与 `shouldCheckCache || !hasRequest` 不重放**:本地 else 分支已做丢失完成事件的缓存重查(fallback 路径),且按 §8.6 有界 heal 约束重入频率;上游的无界重查与其 RENDEZVOUS 队列配套,本地语义已覆盖且边界更强。
6. **ChapterLoader/ReaderActivity/DirectoryPageLoader 改动不移植**:requestedPage 重排/initialPageIndex/page_index intent 属上游本地画廊(beta 3);komikku 已有 SY 的 `loadChapter(chapter, page)` 与 SavedState 恢复路径。LocalBook 过滤属画廊。
7. **androidTest 三个不移植**(§7.3-3 惯例):`ReaderEnhancementCompletionTest`、`ImageEnhancerQueueTest`、`HttpPageLoaderTest`;其 JVM 可断言部分(processingPhase 空闲契约)入 `ImageEnhancerPriorityTest`。
8. **CHANGELOG/versionCode 不移植**(惯例)。
9. **原图下载进度显示回退(维护者决定)**:上游的 `PageDownloadProgress.downloadProgressFlow`(Ready 钳 100、双页均值)与 loader 的 `progress=0/100` 锚点、holder 的合并指示器属于纯展示通道——增强/缓存/下载控制流均不读 `Page.progress`,移植后经确认无功能影响,按维护者决定回退,沿用本地原有的逐半页进度显示;`reader_status_preparing/finishing/enhancing` 字符串保留(阶段标签仍使用 preparing/finishing)。

### 9.3 验证

`spotlessApply`/`spotlessCheck`、`:app:compileDebugKotlin`、`:app:testReleaseUnitTest`(QnnPrecisionPolicyTest 5 项含新迁移用例、ImageEnhancerPriorityTest 7 项含 2 项新 phase 用例、Waifu2xBackendResolutionTest、ImageEnhancementCacheKeyTest、ImageEnhancementCacheDiscardTest)全部通过。后续本地修正(d68236e31 回退、worker 原子认领、zh 字符串补齐)后复跑 spotless 与 `:app:compileDebugKotlin` 通过。

### 9.4 提交结构

beta 2(W8A8)与 1.3.17 增强部分经维护者决定**合并为一次提交**(上游提交边界见 §9 开头对照,本地两批改动无相互依赖)。另含两项本地修正与一次回退:FINISHING 阶段恢复(撤销 d68236e31,见 9.1)、worker 原子认领修复双下载竞态(见 9.1 ③)、下载进度显示回退(见 9.2-9)。
