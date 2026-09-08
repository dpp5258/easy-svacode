# ROI 框选放大 + 三档自适应降级 + 框级兜底 · C++ 迁移方案

> 状态：**草案 v0.1（供角色3/角色2 评审后实施）**
> 依据：`临时easySVA/算法规则.md` §8.5/§10 + Python 部署链 `algo/roi_zoom.py`、`algo/deploy_chain.py`（已 44 段等价回归固化基线）
> 目标仓源码：`/opt/SVA/SVA-server/Analyzer/Core/*`（现工作区为基线 716f175 之上的脏树，不 git 提交）
> 范围：只改 Analyzer（C++ 分析器）；后端/前端本阶段不动（参数入库为 Phase B）

---

## 0. 结论先行

Python 侧已验证的三件事，在 C++ 各对应一处**新增/小改**，不推翻现有架构：

| Python 能力 | C++ 迁移本质 | 主要落点 |
|---|---|---|
| ROI 放大（第二路小窗推理+位置匹配） | 给 OnnxRuntimeEngine 加一个"对目标框裁窗二次推理"方法，在整帧推理后按人调用 | `AlgorithmOnYolo.cpp/.h` + `Analyzer.cpp` |
| 三档自适应（tier1/2/3） | 按人框高(640 空间)打标，新增 `DetectObject` 字段 | `Analyzer.cpp` + `Algorithm.h` |
| 框级兜底（静止+时长，pose 不可用才走） | 扩展已有 pose 状态机（`SleepPoseState`）累计兜底时长 | `BehaviorEvaluator.cpp/.h` |
| §6/§10 参数可配 | 硬编码常量 → `BehaviorRuleConfig` 字段 + JSON 可选键（缺省=现状，无回归） | `Control.h` + `BehaviorEvaluator.cpp` |

**核心判断**：ROI 二次推理必须放在**整帧推理同一处**（`Analyzer::runAlgorithmTask`，image+engine+detects 齐备且已有 `mAlgorithmMtx` 串行），不能放到 Worker 判定层（那里拿不到原图与引擎）。由此带来一个语义差异：ROI 决策发生在**打 trackId 之前**，退避键用"归一化框位置"而非 trackId（详见 §5/§6）。

---

## 1. C++ 现状速览（锚点均为实测行号）

### 1.1 数据流
```
AvPullStream → Worker::handleDecodeVideo(每路控制)
  → Analyzer::handleVideoFrame        (Analyzer.cpp:263)     每帧
      → runAlgorithmTask              (Analyzer.cpp:168)     按 detectFps 节流
          → algorithm->objectDetect(image, detects)          (Analyzer.cpp:240, mAlgorithmMtx 内)
              → OnnxRuntimeEngine::runInference(image,...)   (整帧 letterbox-640 → ORT → decode)
                  → decodePoseOutput(...)                    (AlgorithmOnYolo.cpp:396; 填 keypoints/hd/poseOk)
          → applyRegionAndObjectMatch / happen               (Analyzer.cpp:259)
  → Scheduler::updateTemporalTracks(...)                     (Worker.cpp:738; 此时才打 trackId/speed/regionStates)
  → 逐 detect: BehaviorEvaluator::evaluateAtomicBehavior     (Worker.cpp:760 → BehaviorEvaluator.cpp:791)
      → sleep 规则 → isSleepHit(control, rule, detect, regionState)   (BehaviorEvaluator.cpp:527)
          pose 通道: 静止门槛 → feedSleepPoseState(C1/C2/C3) (BehaviorEvaluator.cpp:551-590, 常量 L285-292)
          未命中 → 旧平台判据: dwell + 宽高比≥1.2(横躺)      (BehaviorEvaluator.cpp:592-645)
```

### 1.2 关键对象与约束
- 引擎单例方法：`AlgorithmOnYolo::objectDetect` → `OnnxRuntimeEngine::runInference`（AlgorithmOnYolo.h:41/78）。Pose 模型输入 640×640，预处理 letterbox + RGB（AlgorithmOnYolo.cpp:575-615，`swapRB=(mDecoder!=Direct)`）。
- `DetectObject`（Algorithm.h:43-83）已有 pose 专属字段：`keypoints/keypointConf/hd/poseOk`；再加 3 个部署字段即可。
- 规则配置：`BehaviorRuleConfig`（Control.h:86-124），JSON 解析在 Control.h:660-800（`hdThreshold` 已有三个别名 L788-793）；sleep 默认参数归一化 L1056-1060。
- pose 状态机：`gSleepPoseStates` 以 `{control,rule,trackId}` 为键（BehaviorEvaluator.cpp:317-332），`feedSleepPoseState` 每次 feed 前有**断供 >GAP 重置**（对齐 Python 断供保护），常量硬编码在 L285-292。

---

## 2. Python → C++ 迁移映射

| Python（roi_zoom.py / deploy_chain.py） | C++ 迁移到 | 说明 |
|---|---|---|
| `full_scale()` = 640/max(H,W) | `Analyzer` 工具函数（有 image dims 处） | 算 boxH640 用 |
| `tier_of()`：≥100/≥50/<50 | `Algorithm.h` 内联或 Analyzer 内小函数 | 3 个边界值将来走规则参数 |
| `crop_window()`：pad*max(w,h) 正方形夹取 | OnnxRuntimeEngine 新私有函数 `buildRoiWindow` | 与 Python 完全同式 |
| `roi_infer()`：裁窗 letterbox-640→ORT→decode→坐标回原图 | OnnxRuntimeEngine 新方法 `runPoseRoi(...)` | 复用现有 runInference 的预处理/decode 骨架，source 换成裁窗 |
| `pick_target()`：IoU 匹配原目标（禁 maxconf） | runPoseRoi 内或 Analyzer 选择函数 | 匹配阈值默认 0.15 |
| `person_feature()/hd_ok()`（hd 重算） | 从 decodePoseOutput L474-523 抽出 `static fillPoseFeatures(DetectObject&)` 复用 | 全帧/ROI 共用同一特征函数，保证同源可比 |
| `upgrade()` | AlgorithmOnYolo 新方法 `bool upgradePoseByRoi(image, detect, roiParams)` | 成功 → 改写 detect.hd/poseOk/poseFromRoi |
| deploy_chain 的"整帧一次+定档" | `Analyzer::runAlgorithmTask`：objectDetect 后遍历打 `tier/boxH640` | 纯打标，零推理 |
| deploy_chain 的"ROI 升级（平台门槛后+退避+预算）" | runAlgorithmTask 内：对 `tier≥2 && !poseOk && boxH640≥roiMin` 目标尝试；退避键=量化框位置；每帧预算 cap | 追踪前无 trackId，见 §6 差异 |
| deploy_chain 的"框级兜底 fb（pose 持续不可用+静止+时长）" | `SleepPoseState` 加 fb 字段；`feedSleepPoseState` 在 ok=false 且 tier≥2 时累计 fbMs，≥boxMs 返回触发 | 放在状态机里，天然按 track 断供/GAP 语义 |
| deploy_chain 事件 chan（pose_C1/fb_t2） | 不做 DB 区分（v1）；日志/状态计数区分 | 待决 Q3 |

---

## 3. 逐文件改动清单

### 3.1 `Algorithm.h` — DetectObject 新增 3 字段
```cpp
float boxH640   = 0.0f;  // 640 空间人框高(整帧 letterbox scale 换算), 打标用
int   poseTier  = 1;     // 1/2/3 (§8.5 三档)
bool  poseFromRoi = false; // 本帧 hd/poseOk 是否来自 ROI 放大(调试/审计)
```
影响：DetectObject 被 `std::move` 拷贝（Analyzer.cpp:300-304），普通值类型安全。

### 3.2 `AlgorithmOnYolo.h/.cpp` — ROI 二次推理入口
1. **抽出特征函数**：把 decodePoseOutput 里计算 hd/poseOk 的段落（约 L474-523）提为
   `static void fillPoseFeatures(DetectObject &d);`（kpts 在 d 上，box 在 d 上，hd 比值为尺度无关）。
   全帧 decode 与 ROI 结果共用，保证两路 hd 同源可比。
2. **引擎新方法**（建议签名，实现者可调）：
```cpp
// OnnxRuntimeEngine
// 对 full 中 target 框做 pad 放大裁窗→letterbox 640→推理→decode→映射回 full 坐标→
// 按 IoU(matchIoU) 匹配目标框, 成功则 out = 该候选(坐标已在原图空间)
bool runPoseRoi(const cv::Mat &full, const DetectObject &target,
                float pad, float matchIoU, DetectObject &out);
```
   实现要点：
   - 裁窗：`side = pad*max(w,h)`，夹取回画面内（与 `crop_window` 一致）；
   - 预处理复用 runInference 的 pose 分支写法（letterbox 640 → blob RGB，AlgorithmOnYolo.cpp:575-615）；
   - decode 复用 `decodePoseOutput`（source 尺寸=裁窗尺寸，scale=640/side, padX=padY≈0），
     再整体平移 (xa,ya) 回原图坐标；
   - 候选 ≥conf 0.25 后按"与目标框 IoU 最大且 >matchIoU"选（**不取 maxconf**，§8.5.1b 纪律）；
   - 命中后 `fillPoseFeatures(out)`。
3. **AlgorithmOnYolo 门面方法**：`bool upgradePoseByRoi(cv::Mat &image, DetectObject &det, float pad, float matchIoU)`
   → 内部调用引擎 runPoseRoi；成功则把 out 的 `hd/poseOk`（及可选 keypoints）写回 det，置 `det.poseFromRoi=true`。

### 3.3 `Analyzer.cpp` — 打标 + ROI 调度（主改动）
在 `runAlgorithmTask` 的 `objectDetect` 之后、`applyRegionAndObjectMatch` 之前插入：
```
const float scaleFull = 640.0f / max(image.rows, image.cols);   // 全帧 letterbox 比例
for (detect in taskDetects) if (detect.source_algorithm 是 pose 族):
    detect.boxH640 = (detect.y2 - detect.y1) * scaleFull;
    detect.poseTier = tier_of(boxH640);            // ≥100→1, ≥50→2, else 3
// 仅当本控制启用了 ROI(默认 pose 规则启用) 且规则 ROI 预算>0:
roiBudget = 规则或默认(每帧≤4);
for (detect in taskDetects):
    if (detect.poseTier<2 || detect.poseOk) continue;            // tier1/已可算不 ROI
    if (detect.boxH640 < roiMin(40)) continue;                    // 太小救不回
    if (overheated(detect)) continue;                             // 退避: 见 §6
    if (roiBudget-- <= 0) break;
    if (mScheduler 的 pose 引擎算法可用) {                         // 即本 task 的 algorithm
        bool ok = algorithmOnYolo->upgradePoseByRoi(image, detect, pad=2.5, matchIoU=0.15);
        recordBackoff(detect, ok);                                 // 失败计数/下次重试时间
        ++counters.roiAttempt; if(ok) ++counters.roiOk;
    }
```
> 说明：`image` 在该函数内有效且 `algorithm` 已解析（Analyzer.cpp:225-241），
> 调用在 `mAlgorithmMtx` 临界区内 → ROI 二次推理与整帧推理天然互斥，无需新锁。
> taskDetects 合并进 happenDetects 后，tier/boxH640/poseFromRoi 随 detect 带到 Worker/BehaviorEvaluator。

退避表（Analyzer 成员，per-control）：
```
struct RoiBackoff { int fail; int64_t lastTryMs; };
unordered_map<BoxKey, RoiBackoff> roiBackoff;   // BoxKey = 量化中心(x/16,y/16)+高(取整/16)
规则: fail<3 → 每帧可试; fail≥3 → 距 lastTry ≥ roiRecheckMs(默认2500) 才重试; 成功 fail=0。
30s 无更新的键清理(复用 gSleepPoseStates 的清理节奏思想)。
```

### 3.4 `BehaviorEvaluator.h/.cpp` — 参数化 + 框级兜底
1. 定义 `struct PoseRuleParams`（或直接扩展 BehaviorRuleConfig，见 3.5），替换 L285-292 硬编码：
```cpp
thetaDesk=0.0, W=4.0, p=0.5, gapTol=0.5, GAP=1.5, TDesk=1.5,
boxFallbackMs=8000, boxFallbackTierMin=2     // 0=关兜底
```
   `feedSleepPoseState(..., const PoseRuleParams &pr)` 内部全部用参数（缺省=旧常量，行为不变）。
2. `SleepPoseState` 增加 `double fbMs=0;`；`feedSleepPoseState` 逻辑（对齐 deploy_chain）：
   - ok=false（pose 不可用）且 tier≥boxFallbackTierMin 且 state=NORMAL：fbMs += dt*1000；fbMs≥boxFallbackMs → 置 ALARM 并返回 true（标记 fb 命中）；
   - ok=true / 平台门槛不过(未调 feed) → fbMs=0（对齐"pose 可用或动起来即重置"）。
   > 现状 `isSleepHit` 在 moving/超速时提前 return、不调 feed → 等价于 Python 的"非 ok_platform 时兜底清零"；断供>GAP 本就会 reset（feedSleepPoseState 内部）。
3. `isSleepHit` 末尾（L589"未命中继续走旧判据"前）无需额外分支——fb 命中已由 feed 返回 true。
   旧"横躺宽高比"兜底保留不动（L592-645），作为 pose/fb 之外的既有通道。

### 3.5 `Control.h` — 新规则字段 + JSON 可选键（缺省=现行为）
`BehaviorRuleConfig` 增（全部有默认 0/缺省，保证老 JSON 无回归）：
```
double thetaDesk=0.0;      double windowSec=0.0;   // 0→用 kSleep* 现值
double ratioP=0.0;         double gapTolSec=0.0;
double gapSec=0.0;         double deskSec=0.0;
double roiEnabled=1.0;     // 0=关(该规则不做 ROI); 默认开(仅 pose 规则生效)
double tierHi=0.0,tierLo=0.0,roiMinPx=0.0;         // 0→默认 100/50/40
double roiPad=0.0, roiMatchIoU=0.0;
int    roiBudget=0;        double roiRecheckMs=0.0;
double boxFallbackMs=0.0;  int boxFallbackTierMin=2; // 0→默认 8000(仅 pose 规则)
```
解析处（Control.h L785-800 hdThreshold 附近）加同名 JSON 键 + 别名（`thetaHd/theta_hd` 已有；新增 `W/p/gapTol/GAP/T_desk/boxMs/tierHi...` 尽量兼容 Python 参数名）。
归一化 L1056-1060 sleep 段：**不要动 thresholdMs 语义**（它同时是 pose T_suspect 与旧 dwell 阈值，现状 demo=8000）；`boxFallbackMs` 默认仅在 pose 且 tier≥2 生效，与 thresholdMs 解耦。

### 3.6 不动清单（明确不改，防回归）
`TemporalContext.*`、`Worker.*`（除已存在调用链）、`Server.*`、`Scheduler.*`（引擎注册/建控不变）、
`AvPushStream/AvPullStream/GenerateAlarmVideo`、后端 Java、前端。**Analyzer 启动/布控协议不变。**

---

## 4. 新增/可配参数表（JSON 键，缺省=现状）

| 键 | 默认 | 说明 | Python 对应 |
|---|---|---|---|
| `thetaDesk` | 0.0 | C3 趴桌阈值 | `theta_desk` |
| `windowSec` | 4.0 | C2 窗口 | `W` |
| `ratioP` | 0.5 | C2 占比 | `p` |
| `gapTolSec` | 0.5 | 点头间隙 | `gap_tol` |
| `gapSec` | 1.5 | GAP | `GAP` |
| `deskSec` | 1.5 | C3 时长 | `T_desk` |
| `roiEnabled` | 1 (pose) | ROI 开关 | --roi-budget>0 |
| `tierHi/tierLo` | 100/50 | 档位边界(640 空间) | `TIER_HI/LO` |
| `roiMinPx` | 40 | ROI 下限 | `ROI_MIN` |
| `roiPad` | 2.5 | 裁窗 pad | `--roi-pad` |
| `roiMatchIoU` | 0.15 | 位置匹配阈值 | `--roi-match` |
| `roiBudget` | 4 | 每帧 ROI 次数 | `--roi-budget` |
| `roiRecheckMs` | 2500 | 失败退避间隔 | `--roi-recheck` |
| `boxFallbackMs` | 8000 | 框级兜底时长(0=关) | `--box-ms` |
| `boxFallbackTierMin` | 2 | 兜底最低档位 | tier≥2 |
| `fbTier1` | false | tier1 也兜底 | `--fb-tier1` |

> 注意：Analyzer 规则 JSON 由后端在"启动布控"时下发（Control.h 解析），后端目前不会下发这些新键 →
> Phase A 全部走默认值即可上线验证；要在某条布控调参需 Phase B（后端/库表透传），见 §9 待决。

---

## 5. 目标判定流（pose 布控一帧，改造后）

```
整帧 pose 推理 1 次 (mAlgorithmMtx 内)
  → 每 detect: 打 boxH640/tier                                  (零推理)
  → 每 detect: tier≥2 && !poseOk && boxH640≥40 && 未过热
        → ROI 裁窗二次推理 (同锁内, ≤roiBudget/帧)
            成功 → hd/poseOk 用 ROI 值 + poseFromRoi=true
            失败 → 记录退避 (fail≥3 后每 2.5s 才重试)
追踪 (trackId/speed/regionStates)                              (Worker, 不变)
evaluateAtomicBehavior → sleep 规则 isSleepHit:
  静止门槛不过 → false
  pose 通道: feedSleepPoseState(C1/C2/C3, ok=false 时按 tier 累计 fb)
     命中(pose 或 fb) → 告警 sleep
  未命中 → 旧横躺兜底(dwell+宽高比) (不变)
```

---

## 6. 线程 / CPU / 内存设计

- **并发**：ROI 在 `mAlgorithmMtx` 临界区内串行执行，与现有整帧推理共用同一引擎会话（同线程），无新锁、无跨线程数据竞争；代价是 ROI 推理耗时计入该帧任务 → 用 `roiBudget` 上限（默认 4 次/帧）+ 退避压住最坏情况。
- **CPU 实测参考**：Python 单次 ROI≈整帧推理≈90ms(640 输入)；C++ 同尺寸同模型应同级。近景布控全 tier1 → **0 次 ROI，零开销**；远机位小目标才触发。
- **退避必要性**：Python 实测"救不回的目标"每帧空转 120 次 ≈ CPU 翻倍 → C++ 同样必须退避（§6 BoxKey 方案），这是迁移中**最容易被漏掉**的部分。
- **节流联动**：ROI 加在推理侧，`checkFps` 自适应（Worker.cpp:810）会把"总推理率"统计进去，CPU 忙自动降频——行为与现状一致。
- **内存**：退避表 30s 清理；SleepPoseState 加 1 个 double；DetectObject 加 3 个标量。

### 语义差异（追踪前做 ROI，需知悉并评估）
Python 版 ROI 只对"平台门槛已过(静止/Tracked/区域内)"的目标尝试；C++ 在追踪前无法判静止 → 移动中的小目标也可能触发 ROI（浪费一次推理，但退避限制频率；移动目标不参与 pose 累计，无假报警风险）。若要求与 Python 完全一致 → 需把 ROI 挪到 Worker 判定层并给 Worker 传递 image/engine 引用（侵入更大），本方案 v1 不做，列待决 Q2。

---

## 7. 构建与测试计划

### 7.1 构建/部署（含回滚）
```
cp /opt/SVA/server/Analyzer /opt/SVA/server/Analyzer.bak.roi   # 备份
cd /opt/SVA/SVA-server/build && cmake .. -DCMAKE_BUILD_TYPE=Release  # 视原构建参数
make -j$(nproc) Analyzer
cp Analyzer /opt/SVA/server/Analyzer                            # 替换(与现流程一致)
# 重启三服务(backend 9114 / ZLM 9992 / Analyzer -f /opt/SVA/config.json), 布控 STOPPED 起步
```
回滚：停服务 → 还原 .bak.roi → 重启。

### 7.2 测试用例（逐条过，产出记录）
| # | 场景 | 方法 | 通过标准 |
|---|---|---|---|
| T1 近景回归 | 推 `/tmp/sleep_480x854_15.mp4`(480×854 近景, tier1) → 启动 测试1.1 | 睡岗告警在低头≥8s 后出现(与改前一致) | ROI 计数=0(tier1 不 ROI)，行为不变 |
| T2 远机位-ROI 成功 | 合成"小目标"流: 把该视频缩到 ~90px 贴 1280×720 画布推流(见 Python 造法) → pose 布控 | 日志 `roiAttempt>0, roiOk>0`, 低头段触发告警(经 ROI 或 pose) | 有告警且能确认 pose 数据来自 ROI |
| T3 远机位-兜底 | 同上但目标侧身/头不可见(ROI 救不回型, 可用 SCB 0009002 类画面重复帧) | 静止≥boxFallbackMs(8s) 触发, 日志记 fb | 触发且 attempt 数被退避压住(≈前几次+每2.5s) |
| T4 CPU | T2/T3 连续跑 ≥60s | Analyzer CPU 稳定(不退避时对比), 无每帧空转 | attempts 计数收敛 |
| T5 非 pose 布控回归 | Phase4/其它 yolo26 布控推流 | 行为与改前一致 | ROI/fb 不激活(pose 族判断) |
> 计数/观察：Analyzer 日志加 `LOGI("roiAttempt=%d roiOk=%d fbHit=%d ...")` 周期打印(或复用 status JSON，改 Server.cpp 亦可，v1 用日志即可)。

### 7.3 与 Python 基线的对应关系（诚实口径）
- C++ **不能**廉价重放 44 段 mp4（Analyzer 只吃流），因此"44 段等价回归"留在 Python 侧作为**语义 oracle**；
- C++ 侧用 T1（近景不劣化）承接"重构无回归"承诺，T2/T3 承接新功能行为；
- 若后续需要字节级对照：写离线 C++ harness 直接读 mp4 跑同一管线（需引入解码回放，工作量另计，列入未来项）。

---

## 8. 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| ROI 空转烧 CPU（漏退避） | 高 | §6 BoxKey 退避 + roiBudget，T3 用例专门验证 |
| 二次推理拖慢单帧 → checkFps 降 | 中 | 预算封顶；近景零 ROI；T4 实测 |
| 新字段污染非 pose 规则 | 低 | 仅 pose 族算法打标/ROI/fb；T5 回归 |
| 状态机行为微调引入回归 | 中 | 参数缺省=旧常量；T1+T5；Python 基线并存 |
| 误把 fb 当"姿态确认"导致误报上升 | 中 | fb 仅 tier≥2 且 pose 不可用；默认 8s 静态才触发；可规则关(0) |
| C++/Python 语义漂移(追踪前 ROI) | 中 | §6 差异显式记录；角色3 评估 Q2 |

---

## 9. 待角色3/小组拍板

- **Q1 默认开关**：ROI/兜底对 pose 睡岗规则**默认开**（本方案推荐，近景零副作用；远景是 ROI 的价值场景）还是默认关、仅显式规则开启？
- **Q2 ROI 时机**：接受"追踪前尝试（移动中也会试，靠退避限频）"，还是要求完全对齐 Python（把 image+engine 引到 Worker 判定层，改动更大）？
- **Q3 告警区分**：fb 兜底触发的告警是否需要在 DB/前端与 pose 触发区分（chan 信息）？v1 建议不加（同一 `sleep` 行为码），仅日志计数；要区分则需动 SVA-backend `HWaringController` 映射（属 Phase B）。
- **Q4 参数通道**：Phase B 是否把 §4 参数通过后端/库表下发到布控 JSON（前端可配）？范围涉及 SVA-backend 规则组装 + SVA-web 配置界面，建议独立排期。
- **Q5 兜底口径**：fb 只要求"静止+时长"（Python 语义，坐姿小目标也能报）；平台旧兜底要求"横躺宽高比≥1.2"。两者并存是否接受（fb 更宽、专为 pose 通道设计）？

---

## 10. 工作量与交付

```
Phase A(本方案): Algorithm.h/.cpp + AlgorithmOnYolo.* + Analyzer.cpp + BehaviorEvaluator.* + Control.h
  预计改动: ~5 文件、净增 ~400-600 行
  交付: 补丁 + 本文 + T1-T5 测试记录; 源码留在脏树不提交
Phase B(另案): 参数入库/前端可配 + 告警 chan 区分(后端) + 可选离线 C++ mp4 回放 harness
```

## 11. 关联文件速查
- Python 实现：`临时easySVA/algo/roi_zoom.py`、`deploy_chain.py`、`run_equivalence.py`
- Python 基线：`临时easySVA/algo/output/equivalence_baseline/`（44/44 A≡B）
- 文档：`临时easySVA/算法规则.md` §8.5、§10；`接入easySVA原始项目.md`（角色3 交接总档）

---

## 12. Phase A 测试记录（2026-09-05 实测）

> 决策记录：Q1=默认开（规则 JSON `roiEnabled=0`/`boxFallbackMs=0` 可关）；Q2=追踪前尝试+量化框退避。
> 部署：`/opt/SVA/server/Analyzer`（新构建，CPU，原版备份 `Analyzer.bak.roi`）；服务以 root 运行（历史环境如此，
> 否则 `/var/www/SVA-web/upload/alarm` 写权限不足）；测试布控 `controlL03AYjSpvdIR9m 测试1.1`（on_yolo11n_pose，thresholdMs=8000）。

### T1 近景回归 ✅ 通过
- 流：`sleep_480x854_15.mp4` 循环推 cam439081；告警照常落库（睡岗告警 w827/w828/w829…，间隔与旧版一致），无崩溃、无新增 error。
- ROI 计数：偶发少量 attempt（推流循环接缝"人重新入画"产生 tier2 残缺小框，符合设计门控），回退键收敛（backoff 稳定、次数不涨）。
- 结论：ROI 构建未改坏旧行为；近景 tier1 大头像永不 ROI。

### T2 远机位（ROI 机制）⚠️ 部分验证，卡在数据缺口
- 流：SCB 真实教室远视角静态帧组流（0009002/0009001）。
- ✅ ROI 调度在真实流工作：`[roi] control=… attempt=… ok=… fbHits=… backoff=…` 周期日志出现；attempt 按 2.5s 退避节奏增长。
- ✅ 远视角 pose 告警照常（小目标头部可见者可触发，w832-837）。
- ❌ ok>0 / fbHits>0 **未能在该场景复现**：插桩证实"hd 不可算的小目标"（tier2）在多人拥挤场景被 C++ 追踪器丢弃
  （重叠框 IoU 竞争），**未进入 isSleepHit/feedSleepPoseState**——ROI 在追踪前能看到它、追踪后丢了，故 fb 永不累计。
  单人合成小目标(yolo 不可检出)也不可用。
- 结论与下一步：C++ ROI/兜底的"成功路径"需**真实远机位/单人睡觉视频素材**（文档已知缺口）或离线 C++ 单测 harness 验证；
  代码路径本身（attempt/退避/日志）已证活。

### 环境收尾
- 测试后：布控 STOPPED、推流停、backend/ZLM/Analyzer 停止、业务端口释放；`/opt/SVA/server/Analyzer` 为 ROI 构建（无调试插桩），`.bak.roi` 为基线。
- 遗留待办：T2 成功路径验证素材；Q3-Q5（§9）未决策；Phase B 参数入库未开始。

### T2b ROI 成功路径 ✅（2026-09-05 二次实测, 素材=真实场景/74b5045f…mp4, 576x1280@24 HEVC→H264 循环推流）
- 观测：`[roi] … attempt=1 ok=1 fbHits=0 backoff=1`（循环内稳定复现）→ **C++ ROI 放大在真实流中成功救回"全帧 hd 不可算"的小目标**（ok=1）。
- 说明：该素材 ROI 命中窗口短暂（每循环约 1 帧命中），故 attempt 恒为 1；符合"ROI 只碰需要它的目标"的设计。
- 补充：远视角 pose 告警此前已在 SCB 静态组流验证（w832-837）；本素材无睡岗告警（人物非睡眠，正常）。
- 剩余缺口：**fb(框级兜底) 的端到端命中**仍需"远机位/小目标、关键点不可见、且保持静止≥boxMs"的素材（现网 真实场景 与 SCB 均无此类持续目标）；代码路径与 Python 语义一致，待素材后补测（或离线 harness）。
