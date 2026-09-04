#include "BehaviorEvaluator.h"

#include "Algorithm.h"
#include "Control.h"
#include "Utils/GeometryUtils.h"

#include <algorithm>
#include <cmath>
#include <functional>
#include <limits>
#include <map>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace SVAAnalyzer
{
    namespace
    {
        bool hasLineCrossingBehavior(const Control &control)
        {
            for (size_t i = 0; i < control.lines.size(); ++i)
            {
                if (control.lines[i].points.size() >= 2)
                {
                    return true;
                }
            }
            return false;
        }

        bool hasEnabledBehaviorRules(const Control &control)
        {
            for (size_t i = 0; i < control.behaviorRules.size(); ++i)
            {
                if (control.behaviorRules[i].enabled)
                {
                    return true;
                }
            }
            return false;
        }

        /**
         * @brief Normalize an angle to [0, 360).
         */
        double normalizeAngleDeg(double angleDeg)
        {
            while (angleDeg < 0.0)
            {
                angleDeg += 360.0;
            }
            while (angleDeg >= 360.0)
            {
                angleDeg -= 360.0;
            }
            return angleDeg;
        }

        /**
         * @brief Compute the minimum angular distance between two angles.
         */
        double computeAngleDeltaDeg(double lhs, double rhs)
        {
            const double delta = std::fabs(normalizeAngleDeg(lhs) - normalizeAngleDeg(rhs));
            return std::min(delta, 360.0 - delta);
        }

        /**
         * @brief Try to resolve the movement direction angle from the detect object.
         * Teaching note: Uses trail points (center-of-box history) to compute movement vector.
         */
        bool tryResolveMovementAngleDeg(const DetectObject &detect, double &angleDeg)
        {
            angleDeg = 0.0;
            if (detect.trail.size() >= 2)
            {
                const TrackTrailPoint &prev = detect.trail[detect.trail.size() - 2];
                const TrackTrailPoint &curr = detect.trail[detect.trail.size() - 1];
                const double dx = static_cast<double>(curr.x - prev.x);
                const double dy = static_cast<double>(curr.y - prev.y);
                if (std::fabs(dx) > 1e-3 || std::fabs(dy) > 1e-3)
                {
                    angleDeg = normalizeAngleDeg(std::atan2(dy, dx) * 180.0 / M_PI);
                    return true;
                }
            }

            if (detect.speedPxPerSec > 0.1f)
            {
                angleDeg = normalizeAngleDeg(static_cast<double>(detect.directionAngleDeg));
                return true;
            }
            return false;
        }

        /**
         * @brief Resolve region info (id, name) for a behavior rule.
         * Falls back to the primary region if no specific geometryId is given.
         */
        bool resolveBehaviorRegionInfo(const Control &control,
                                       const std::string &requestedRegionId,
                                       std::string &regionId,
                                       std::string &regionName)
        {
            const RegionConfig *region = requestedRegionId.empty()
                                            ? control.findPrimaryRegion()
                                            : control.findRegionById(requestedRegionId);
            if (!region)
            {
                return false;
            }
            regionId = region->id;
            regionName = region->name.empty() ? region->id : region->name;
            return true;
        }

        /**
         * @brief Find the temporal region state for a specific region from the detect's state map.
         */
        const RegionTemporalState *findRegionState(const DetectObject &detect, const std::string &regionId)
        {
            if (regionId.empty())
            {
                return nullptr;
            }
            auto it = detect.regionStates.find(regionId);
            if (it == detect.regionStates.end())
            {
                return nullptr;
            }
            return &it->second;
        }

        /**
         * @brief Check dwell hit: tracked object has been in region >= thresholdMs.
         */
        bool isDwellHit(int64_t thresholdMs, const DetectObject &detect, const RegionTemporalState *regionState)
        {
            if (detect.trackId < 0 || !regionState)
            {
                return false;
            }
            return regionState->inRegionDurationMs >= std::max<int64_t>(1, thresholdMs);
        }

        /**
         * @brief Check low_speed hit: tracked object moving slower than maxSpeedPxPerSec for thresholdMs.
         */
        bool isLowSpeedHit(double maxSpeedPxPerSec,
                           int64_t thresholdMs,
                           const DetectObject &detect,
                           const RegionTemporalState *regionState)
        {
            if (detect.trackId < 0 || !regionState || !regionState->inRegion)
            {
                return false;
            }
            if (maxSpeedPxPerSec <= 0.0 || thresholdMs <= 0)
            {
                return false;
            }
            if (regionState->inRegionDurationMs < thresholdMs)
            {
                return false;
            }
            if (detect.trail.size() < 2 || detect.lastSeenTimestampMs <= 0)
            {
                return false;
            }

            const int64_t windowStartMs = detect.lastSeenTimestampMs - thresholdMs;
            size_t firstIndex = detect.trail.size();
            for (size_t i = 0; i < detect.trail.size(); ++i)
            {
                if (detect.trail[i].timestampMs >= windowStartMs)
                {
                    firstIndex = i;
                    break;
                }
            }
            if (firstIndex == detect.trail.size())
            {
                return false;
            }
            if (firstIndex > 0)
            {
                --firstIndex;
            }

            double travelledDistance = 0.0;
            const int64_t beginTs = std::max<int64_t>(windowStartMs, detect.trail[firstIndex].timestampMs);
            int64_t endTs = beginTs;
            for (size_t i = firstIndex + 1; i < detect.trail.size(); ++i)
            {
                const TrackTrailPoint &prev = detect.trail[i - 1];
                const TrackTrailPoint &curr = detect.trail[i];
                if (curr.timestampMs < windowStartMs)
                {
                    continue;
                }

                const double dx = static_cast<double>(curr.x - prev.x);
                const double dy = static_cast<double>(curr.y - prev.y);
                travelledDistance += std::sqrt(dx * dx + dy * dy);
                endTs = curr.timestampMs;
            }

            const int64_t elapsedMs = endTs - beginTs;
            if (elapsedMs < std::max<int64_t>(200, thresholdMs / 2))
            {
                return false;
            }

            const double averageSpeed = travelledDistance * 1000.0 / static_cast<double>(elapsedMs);
            return averageSpeed <= maxSpeedPxPerSec;
        }

        /**
         * @brief Check loitering hit: tracked object stays within maxDisplacementPx for thresholdMs.
         */
        bool isLoiteringHit(double maxDisplacementPx,
                            int64_t thresholdMs,
                            const DetectObject &detect,
                            const RegionTemporalState *regionState)
        {
            if (detect.trackId < 0 || !regionState || !regionState->inRegion)
            {
                return false;
            }
            if (maxDisplacementPx <= 0.0 || thresholdMs <= 0)
            {
                return false;
            }
            if (regionState->inRegionDurationMs < thresholdMs)
            {
                return false;
            }
            if (detect.trail.size() < 2 || detect.lastSeenTimestampMs <= 0)
            {
                return false;
            }

            const int64_t windowStartMs = detect.lastSeenTimestampMs - thresholdMs;
            size_t firstIndex = detect.trail.size();
            for (size_t i = 0; i < detect.trail.size(); ++i)
            {
                if (detect.trail[i].timestampMs >= windowStartMs)
                {
                    firstIndex = i;
                    break;
                }
            }
            if (firstIndex == detect.trail.size())
            {
                return false;
            }

            const TrackTrailPoint &anchor = detect.trail[firstIndex];
            double maxDistance = 0.0;
            int pointCount = 0;
            for (size_t i = firstIndex; i < detect.trail.size(); ++i)
            {
                const TrackTrailPoint &point = detect.trail[i];
                if (point.timestampMs < windowStartMs)
                {
                    continue;
                }
                const double dx = static_cast<double>(point.x - anchor.x);
                const double dy = static_cast<double>(point.y - anchor.y);
                maxDistance = std::max(maxDistance, std::sqrt(dx * dx + dy * dy));
                ++pointCount;
            }

            if (pointCount < 2)
            {
                return false;
            }
            return maxDistance <= maxDisplacementPx;
        }

        // ================== 睡岗 pose 时序状态机 (对齐 Python sleep_state_machine.py) ==================
        // 触发通道: C1 连续低头 down_run≥T_suspect(容忍<gap_tol 呼吸间隙); C2 滑动窗口W内低头累计占比;
        //           C3 趴桌 desk_run≥T_desk(hd≤θ_desk). 断供>GAP 清状态重新累计.
        const double kSleepThetaDesk = 0.0;   // C3 趴桌更严低头阈值
        const double kSleepWindowSec = 4.0;   // C2 滑动窗口 W
        const double kSleepRatioP = 0.5;      // C2 最低占比 p
        const double kSleepGapTolSec = 0.5;   // C1 段内间隙容忍 gap_tol (呼吸式续计)
        const double kSleepGapSec = 1.5;      // 安静/目标丢失 GAP → reset
        const double kSleepTDeskSec = 1.5;    // C3 趴桌持续 T_desk

        struct SleepPoseState
        {
            int state = 0; // 0=NORMAL 1=SUSPECT 2=ALARM
            int64_t lastFeedMs = 0; // R2: 最近一次 feed 时刻, 供定期清理
            bool hasLast = false;
            double lastT = 0.0;
            double downRun = 0.0;
            bool hasSegStart = false;
            double segStartSec = 0.0;
            double lastDownSec = 0.0;
            bool hasLastDown = false;
            double lastAnySec = 0.0;
            bool hasLastAny = false;
            double deskRun = 0.0;
            struct WinItem
            {
                double t = 0.0;
                bool down = false;
                double dur = 0.0;
            };
            std::vector<WinItem> win;
        };

        struct SleepPoseKey
        {
            std::string control;
            std::string rule;     // R1: 区分同一布控下的多条 sleep 规则, 避免状态互相串扰
            int trackId = -1;
            bool operator==(const SleepPoseKey &o) const { return control == o.control && rule == o.rule && trackId == o.trackId; }
        };
        struct SleepPoseKeyHash
        {
            size_t operator()(const SleepPoseKey &k) const
            {
                std::hash<std::string> hs;
                return hs(k.control) ^ (hs(k.rule) * 0x9e3779b1u) ^ (static_cast<size_t>(k.trackId) * 2654435761u);
            }
        };
        std::unordered_map<SleepPoseKey, SleepPoseState, SleepPoseKeyHash> gSleepPoseStates;
        std::mutex gSleepPoseMtx;
        int64_t gSleepLastPruneMs = 0; // R2: 定期清理超时状态

        void resetSleepPoseState(SleepPoseState &s)
        {
            s = SleepPoseState();
        }

        /**
         * @brief 逐帧驱动单个目标的姿态状态机 (每 control+track 一份).
         * @return true = 本帧处于 ALARM (达标当帧即 true, 持续报警持续 true)
         */
        bool feedSleepPoseState(SleepPoseState &s, double tSec, float hd, bool ok,
                                double thetaHd, double tSuspectSec)
        {
            if (!s.hasLast)
            {
                s.lastT = tSec;
                s.hasLast = true;
                return false;
            }
            double dt = tSec - s.lastT;
            if (dt <= 0.0)
            {
                dt = 0.001;
            }
            // 断供保护: 距上帧超过 GAP(judge 过滤/目标消失) → 清状态, 本帧重新累计
            if (dt > std::max(1.0, kSleepGapSec))
            {
                resetSleepPoseState(s);
                s.lastT = tSec;
                s.hasLast = true;
                return false;
            }
            s.lastT = tSec;

            const bool down = ok && (static_cast<double>(hd) <= thetaHd);
            const bool desk = ok && (static_cast<double>(hd) <= kSleepThetaDesk);

            // C2 滑动窗口: 剔除过期
            const double windowStart = tSec - kSleepWindowSec;
            while (!s.win.empty() && s.win.front().t < windowStart)
            {
                s.win.erase(s.win.begin());
            }

            SleepPoseState::WinItem item;
            item.t = tSec;
            item.down = down;
            item.dur = dt;
            bool trig = false;
            if (s.state == 0) // NORMAL
            {
                if (down || desk)
                {
                    s.state = 1; // SUSPECT
                    s.deskRun = desk ? dt : 0.0;
                    if (down)
                    {
                        s.segStartSec = tSec;
                        s.hasSegStart = true;
                        s.downRun = 0.0; // 段起点即本帧, 累计在下一帧体现
                        s.lastDownSec = tSec;
                        s.hasLastDown = true;
                    }
                    else
                    {
                        s.downRun = 0.0;
                        s.hasSegStart = false;
                    }
                    s.lastAnySec = tSec;
                    s.hasLastAny = true;
                    s.win.push_back(item);
                }
                else
                {
                    item.down = false;
                    s.win.push_back(item);
                }
            }
            else if (s.state == 1) // SUSPECT
            {
                if (down)
                {
                    const double gap = s.hasLastDown ? (tSec - s.lastDownSec) : 999.0;
                    if (gap > kSleepGapTolSec)
                    {
                        s.segStartSec = tSec;
                        s.hasSegStart = true;
                    }
                    if (s.hasSegStart)
                    {
                        s.downRun = tSec - s.segStartSec; // 段内时长(容忍<gap_tol间隙, 计入间隙)
                    }
                    else
                    {
                        s.downRun = dt;
                    }
                    s.lastDownSec = tSec;
                    s.hasLastDown = true;
                    s.lastAnySec = tSec;
                    s.hasLastAny = true;
                    s.win.push_back(item);
                }
                else
                {
                    if (s.hasLastDown && (tSec - s.lastDownSec) > kSleepGapTolSec)
                    {
                        s.hasSegStart = false;
                        s.downRun = 0.0;
                    }
                    item.down = false;
                    s.win.push_back(item);
                }
                s.deskRun = desk ? (s.deskRun + dt) : 0.0;
                if (down || desk)
                {
                    s.lastAnySec = tSec;
                    s.hasLastAny = true;
                }
                // C1: 段连续低头
                if (s.downRun >= tSuspectSec)
                {
                    trig = true;
                }
                else
                {
                    // C2: 窗口内低头累计与占比
                    double winDown = 0.0;
                    for (size_t wi = 0; wi < s.win.size(); ++wi)
                    {
                        if (s.win[wi].down)
                        {
                            winDown += s.win[wi].dur;
                        }
                    }
                    const double span = s.win.empty() ? 0.0 : (tSec - s.win.front().t);
                    if (span >= 0.5 && winDown >= tSuspectSec && (winDown / span) >= kSleepRatioP)
                    {
                        trig = true;
                    }
                }
                // C3: 趴桌
                if (!trig && s.deskRun >= kSleepTDeskSec)
                {
                    trig = true;
                }
                if (trig)
                {
                    s.state = 2; // ALARM
                    return true;
                }
                if (s.hasLastAny && (tSec - s.lastAnySec) >= kSleepGapSec)
                {
                    resetSleepPoseState(s);
                }
            }
            else // state == 2 ALARM
            {
                if (down)
                {
                    s.lastDownSec = tSec;
                    s.hasLastDown = true;
                    s.lastAnySec = tSec;
                    s.hasLastAny = true;
                    s.win.push_back(item);
                }
                else if (desk)
                {
                    s.lastAnySec = tSec;
                    s.hasLastAny = true;
                    item.down = false;
                    s.win.push_back(item);
                }
                else
                {
                    item.down = false;
                    s.win.push_back(item);
                }
                if (s.hasLastAny && (tSec - s.lastAnySec) >= kSleepGapSec)
                {
                    resetSleepPoseState(s);
                    return false;
                }
                return true;
            }
            return false;
        }

        /**
         * @brief Check sleep hit.
         * pose 引擎目标: 状态机判 "静止+持续低头" (C1/C2/C3, 认坐姿低头/伏案趴桌);
         * 旧平台判据(横躺宽高比)在 pose 未命中或非 pose 算法时兜底, 保证躺姿仍可报.
         */
        bool isSleepHit(const std::string &controlCode,
                        const BehaviorRuleConfig &rule,
                        const DetectObject &detect,
                        const RegionTemporalState *regionState)
        {
            if (detect.trackId < 0)
            {
                return false;
            }
            if (regionState && !regionState->inRegion)
            {
                return false;
            }

            const double maxSpeedPxPerSec = rule.maxSpeedPxPerSec > 0.0 ? rule.maxSpeedPxPerSec : 6.0;
            if (detect.speedPxPerSec > maxSpeedPxPerSec)
            {
                return false;
            }
            if (detect.motionState == "moving")
            {
                return false;
            }

            // ---- pose 判据(状态机): 静止 + poseOk 且 hd≤θ_hd 持续 ≥T_suspect ----
            const bool poseCapable = !detect.keypoints.empty()
                || detect.source_algorithm.find("pose") != std::string::npos;
            if (poseCapable)
            {
                const double thetaHd = rule.hdThreshold > 0.0 ? rule.hdThreshold : 0.12;
                const double tSuspectSec = rule.thresholdMs > 0
                    ? static_cast<double>(rule.thresholdMs) / 1000.0
                    : 15.0;
                const double tSec = static_cast<double>(detect.lastSeenTimestampMs) / 1000.0;
                SleepPoseKey key;
                key.control = controlCode;
                key.rule = rule.id;
                key.trackId = detect.trackId;
                std::lock_guard<std::mutex> guard(gSleepPoseMtx);
                // R2: 每 30s 清理一次超时(>30s 未更新)的状态, 防止 map 长期增长
                const int64_t nowMs = detect.lastSeenTimestampMs;
                if (gSleepLastPruneMs == 0 || (nowMs - gSleepLastPruneMs) >= 30000)
                {
                    for (auto it = gSleepPoseStates.begin(); it != gSleepPoseStates.end();)
                    {
                        if (it->second.lastFeedMs != 0 && (nowMs - it->second.lastFeedMs) >= 30000)
                        {
                            it = gSleepPoseStates.erase(it);
                        }
                        else
                        {
                            ++it;
                        }
                    }
                    gSleepLastPruneMs = nowMs;
                }
                SleepPoseState &st = gSleepPoseStates[key];
                st.lastFeedMs = nowMs;
                if (feedSleepPoseState(st, tSec, detect.hd, detect.poseOk, thetaHd, tSuspectSec))
                {
                    return true;
                }
                // 未命中继续走旧判据兜底(如横躺时关键点不可见)
            }

            // ---- 旧平台判据: 区域内停留时长 + 横躺宽高比 + 位移 ----
            const int64_t thresholdMs = std::max<int64_t>(1000, rule.thresholdMs > 0 ? rule.thresholdMs : 15000);
            const int64_t activeDurationMs = regionState ? regionState->inRegionDurationMs : detect.dwellMs;
            if (activeDurationMs < thresholdMs)
            {
                return false;
            }

            // Wide aspect ratio check: a lying person is wider than tall
            const double width = std::max(1, detect.x2 - detect.x1);
            const double height = std::max(1, detect.y2 - detect.y1);
            const double minAspectRatio = rule.distanceThresholdPx > 0.0 ? rule.distanceThresholdPx : 1.2;
            if ((width / height) < minAspectRatio)
            {
                return false;
            }

            if (rule.maxDisplacementPx > 0.0 && detect.trail.size() >= 2)
            {
                const int64_t windowStartMs = detect.lastSeenTimestampMs - thresholdMs;
                size_t firstIndex = detect.trail.size();
                for (size_t i = 0; i < detect.trail.size(); ++i)
                {
                    if (detect.trail[i].timestampMs >= windowStartMs)
                    {
                        firstIndex = i;
                        break;
                    }
                }
                if (firstIndex == detect.trail.size())
                {
                    return false;
                }

                const TrackTrailPoint &anchor = detect.trail[firstIndex];
                double maxDistance = 0.0;
                for (size_t i = firstIndex; i < detect.trail.size(); ++i)
                {
                    const TrackTrailPoint &point = detect.trail[i];
                    if (point.timestampMs < windowStartMs)
                    {
                        continue;
                    }
                    const double dx = static_cast<double>(point.x - anchor.x);
                    const double dy = static_cast<double>(point.y - anchor.y);
                    maxDistance = std::max(maxDistance, std::sqrt(dx * dx + dy * dy));
                }
                if (maxDistance > rule.maxDisplacementPx)
                {
                    return false;
                }
            }

            return true;
        }

        /**
         * @brief Check direction_move / direction_reverse hit.
         */
        bool isDirectionRuleHit(const BehaviorRuleConfig &rule,
                                const DetectObject &detect,
                                const RegionTemporalState *regionState,
                                double &matchedAngleDeg)
        {
            matchedAngleDeg = 0.0;
            if (detect.trackId < 0)
            {
                return false;
            }
            if (!rule.geometryId.empty())
            {
                if (!regionState || !regionState->inRegion)
                {
                    return false;
                }
                if (rule.thresholdMs > 0 && regionState->inRegionDurationMs < rule.thresholdMs)
                {
                    return false;
                }
            }
            else if (rule.thresholdMs > 0 && detect.dwellMs < rule.thresholdMs)
            {
                return false;
            }

            if (!tryResolveMovementAngleDeg(detect, matchedAngleDeg))
            {
                return false;
            }

            double expectedAngleDeg = normalizeAngleDeg(rule.directionAngleDeg);
            if (rule.behaviorType == "direction_reverse")
            {
                expectedAngleDeg = normalizeAngleDeg(expectedAngleDeg + 180.0);
            }
            return computeAngleDeltaDeg(matchedAngleDeg, expectedAngleDeg) <= std::max(1.0, rule.directionToleranceDeg);
        }

        /**
         * @brief Check cross_line hit using trail crossing logic.
         */
        bool tryResolveCrossLineBehavior(const Control &control,
                         const DetectObject &detect,
                         const std::string &requestedLineId,
                         const std::string &requestedDirection,
                         const std::string &ruleId,
                         const std::string &customEventName,
                         BehaviorDecision &decision)
        {
            if (detect.trackId < 0 || detect.trail.size() < 2)
            {
                return false;
            }

            // Use the most recent two trail points
            std::vector<TrackTrailPoint> latestTrail;
            latestTrail.reserve(2);
            latestTrail.push_back(detect.trail[detect.trail.size() - 2]);
            latestTrail.push_back(detect.trail[detect.trail.size() - 1]);

            auto computeLineSide = [](const GeometryLineSegment &line,
                                      const TrackTrailPoint &point) -> double
            {
                return (line.end.x - line.start.x) * (static_cast<double>(point.y) - line.start.y) -
                       (line.end.y - line.start.y) * (static_cast<double>(point.x) - line.start.x);
            };

            for (size_t i = 0; i < control.lines.size(); ++i)
            {
                const LineConfig &line = control.lines[i];
                if (line.points.size() < 2)
                {
                    continue;
                }
                if (!requestedLineId.empty() && line.id != requestedLineId)
                {
                    continue;
                }

                GeometryLineSegment geometryLine;
                geometryLine.start.x = static_cast<double>(line.points[0].x);
                geometryLine.start.y = static_cast<double>(line.points[0].y);
                geometryLine.end.x = static_cast<double>(line.points[1].x);
                geometryLine.end.y = static_cast<double>(line.points[1].y);
                if (!didTrailCrossLine(latestTrail, geometryLine))
                {
                    continue;
                }

                decision.matched = true;
                decision.ruleId = ruleId;
                decision.customEventName = customEventName;
                decision.behaviorType = "cross_line";
                decision.lineId = line.id;
                decision.lineName = line.name.empty() ? line.id : line.name;

                const double previousSide = computeLineSide(geometryLine, latestTrail[0]);
                const double currentSide = computeLineSide(geometryLine, latestTrail[1]);
                if (previousSide > 0.0 && currentSide < 0.0)
                {
                    decision.crossingDirection = "left_to_right";
                }
                else if (previousSide < 0.0 && currentSide > 0.0)
                {
                    decision.crossingDirection = "right_to_left";
                }
                else
                {
                    decision.crossingDirection = "unknown";
                }
                const std::string effectiveDirection = requestedDirection.empty() ? line.direction : requestedDirection;
                if (effectiveDirection == "left_to_right" || effectiveDirection == "right_to_left")
                {
                    if (decision.crossingDirection != effectiveDirection)
                    {
                        decision = BehaviorDecision{};
                        continue;
                    }
                }
                return true;
            }
            return false;
        }
    }

    bool controlUsesBehaviorOnlyMode(const Control &control)
    {
        return hasEnabledBehaviorRules(control) || control.dwellEnabled || hasLineCrossingBehavior(control);
    }

    /**
     * @brief Main behavior evaluation entry point.
     *
     * Teaching note: For each detect object from the current frame, this function iterates
     * through all enabled behavior rules in the control and returns the first match.
     *
     * The Scheduler (or Analyzer) calls this after temporal tracking has enriched the
     * DetectObject with trail, region states, speed, direction, etc.
     */
    BehaviorDecision evaluateAtomicBehavior(const Control &control, const DetectObject &detect)
    {
        BehaviorDecision decision;

        // Phase 1: Evaluate explicit behavior rules (from behaviorRules array)
        if (hasEnabledBehaviorRules(control))
        {
            for (size_t i = 0; i < control.behaviorRules.size(); ++i)
            {
                const BehaviorRuleConfig &rule = control.behaviorRules[i];
                if (!rule.enabled)
                {
                    continue;
                }

                // Filter by object class if rule specifies one
                if (!rule.ruleObjectCode.empty() && detect.class_name != rule.ruleObjectCode &&
                    rule.behaviorType != "region_motion")
                {
                    continue;
                }

                // --- cross_line ---
                if (rule.behaviorType == "cross_line")
                {
                    if (tryResolveCrossLineBehavior(control, detect, rule.geometryId, rule.direction, rule.id, rule.customEventName, decision))
                    {
                        return decision;
                    }
                    continue;
                }

                // Resolve region info for region-based rules
                std::string regionId;
                std::string regionName;
                if (!resolveBehaviorRegionInfo(control, rule.geometryId, regionId, regionName))
                {
                    continue;
                }
                const RegionTemporalState *regionState = findRegionState(detect, regionId);

                // --- enter_region ---
                if (rule.behaviorType == "enter_region" && regionState && regionState->enteredRegion)
                {
                    decision.matched = true;
                    decision.ruleId = rule.id;
                    decision.customEventName = rule.customEventName;
                    decision.behaviorType = "enter_region";
                    decision.regionId = regionId;
                    decision.regionName = regionName;
                    return decision;
                }

                // --- exit_region ---
                if (rule.behaviorType == "exit_region" && regionState && regionState->exitedRegion)
                {
                    decision.matched = true;
                    decision.ruleId = rule.id;
                    decision.customEventName = rule.customEventName;
                    decision.behaviorType = "exit_region";
                    decision.regionId = regionId;
                    decision.regionName = regionName;
                    return decision;
                }

                // --- dwell ---
                if (rule.behaviorType == "dwell" && isDwellHit(rule.thresholdMs, detect, regionState))
                {
                    decision.matched = true;
                    decision.ruleId = rule.id;
                    decision.customEventName = rule.customEventName;
                    decision.behaviorType = "dwell";
                    decision.regionId = regionId;
                    decision.regionName = regionName;
                    return decision;
                }

                // --- low_speed ---
                if (rule.behaviorType == "low_speed" && isLowSpeedHit(rule.maxSpeedPxPerSec, rule.thresholdMs, detect, regionState))
                {
                    decision.matched = true;
                    decision.ruleId = rule.id;
                    decision.customEventName = rule.customEventName;
                    decision.behaviorType = "low_speed";
                    decision.regionId = regionId;
                    decision.regionName = regionName;
                    return decision;
                }

                // --- loitering ---
                if (rule.behaviorType == "loitering" && isLoiteringHit(rule.maxDisplacementPx, rule.thresholdMs, detect, regionState))
                {
                    decision.matched = true;
                    decision.ruleId = rule.id;
                    decision.customEventName = rule.customEventName;
                    decision.behaviorType = "loitering";
                    decision.regionId = regionId;
                    decision.regionName = regionName;
                    return decision;
                }

                // --- sleep ---
                if (rule.behaviorType == "sleep" && isSleepHit(control.code, rule, detect, regionState))
                {
                    decision.matched = true;
                    decision.ruleId = rule.id;
                    decision.customEventName = rule.customEventName;
                    decision.behaviorType = "sleep";
                    decision.regionId = regionId;
                    decision.regionName = regionName;
                    return decision;
                }

                // --- direction_move / direction_reverse ---
                if ((rule.behaviorType == "direction_move" || rule.behaviorType == "direction_reverse"))
                {
                    double matchedAngleDeg = 0.0;
                    if (isDirectionRuleHit(rule, detect, regionState, matchedAngleDeg))
                    {
                        decision.matched = true;
                        decision.ruleId = rule.id;
                        decision.customEventName = rule.customEventName;
                        decision.behaviorType = rule.behaviorType;
                        decision.regionId = regionId;
                        decision.regionName = regionName;
                        decision.directionAngleDeg = matchedAngleDeg;
                        return decision;
                    }
                }
            }

            return decision;
        }

        // Phase 2: Legacy line crossing (no explicit behavior rules, but lines exist)
        if (hasLineCrossingBehavior(control) && tryResolveCrossLineBehavior(control, detect, "", "", "", "", decision))
        {
            return decision;
        }

        return decision;
    }
}