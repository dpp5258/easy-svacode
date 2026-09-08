// 睡岗(pose)规则高级参数定义。
// 键名与 C++ Analyzer(Phase A, Core/Control.h 解析区) 的 canonical 键一一对应：
//   roiEnabled/roiBudget/roiPad/roiMatchIoU/roiRecheckMs/tierHi/tierLo/roiMin/
//   boxFallbackMs/boxFallbackTierMin/thetaDesk/windowSec/ratioP/gapTolSec/gapSec/deskSec
// 后端 DeploymentController 对 behaviorRules 全量透传，故无需后端/DB 改动。

export const SLEEP_ADVANCED_PARAMS = {
  roiEnabled: {
    default: 1,
    integer: true,
    min: 0,
    max: 1,
    label: 'ROI 增强',
    hint: '1=开启(默认) 0=关闭本规则的 ROI 放大识别'
  },
  roiBudget: {
    default: 4,
    integer: true,
    min: 0,
    max: 32,
    step: 1,
    label: '每帧 ROI 预算',
    hint: '每帧最多尝试的 ROI 放大次数，0=默认 4'
  },
  roiPad: {
    default: 2.5,
    min: 1,
    max: 8,
    step: 0.1,
    precision: 1,
    label: '裁窗 pad',
    hint: 'ROI 裁剪边长 = pad × max(宽,高)，0=默认 2.5'
  },
  roiMatchIoU: {
    default: 0.15,
    min: 0.05,
    max: 0.95,
    step: 0.05,
    precision: 2,
    label: '位置匹配 IoU',
    hint: 'ROI 结果与整帧目标的 IoU 匹配阈值，0=默认 0.15'
  },
  roiRecheckMs: {
    default: 2500,
    integer: true,
    min: 0,
    max: 60000,
    step: 100,
    label: '失败重试间隔(ms)',
    hint: 'ROI 连续失败后的退避重试间隔，0=默认 2500'
  },
  tierHi: {
    default: 100,
    integer: true,
    min: 40,
    max: 640,
    step: 10,
    label: 'tier1 边界(px)',
    hint: '640 空间下 tier1 下限，框高≥此值不触发 ROI，0=默认 100'
  },
  tierLo: {
    default: 50,
    integer: true,
    min: 10,
    max: 640,
    step: 10,
    label: 'tier2 边界(px)',
    hint: '640 空间下 tier2 下限，0=默认 50'
  },
  roiMin: {
    default: 40,
    integer: true,
    min: 10,
    max: 640,
    step: 10,
    label: 'ROI 最小尺寸(px)',
    hint: '640 空间下小于此尺寸不再尝试 ROI，0=默认 40'
  },
  boxFallbackMs: {
    default: 8000,
    integer: true,
    min: 0,
    max: 3600000,
    step: 1000,
    label: '框级兜底时长(ms)',
    hint: '静止且姿态不可用时按框累计的兜底时长，0=关闭，默认 8000'
  },
  boxFallbackTierMin: {
    default: 2,
    integer: true,
    min: 0,
    max: 3,
    step: 1,
    label: '兜底最低档位',
    hint: '仅 tier≥该档的目标参与框级兜底，0=默认 2'
  },
  thetaDesk: {
    default: 0,
    min: 0,
    max: 1,
    step: 0.05,
    precision: 2,
    label: '趴桌判定 θ',
    hint: 'C3 趴桌的头部比例阈值，0=默认(维持现状)'
  },
  windowSec: {
    default: 4,
    min: 1,
    max: 60,
    step: 0.5,
    precision: 1,
    label: 'C2 窗口 W(s)',
    hint: '低头判定滑动窗口，0=默认 4'
  },
  ratioP: {
    default: 0.5,
    min: 0.1,
    max: 1,
    step: 0.05,
    precision: 2,
    label: 'C2 占比 p',
    hint: '窗口内姿态缺失/低头占比，0=默认 0.5'
  },
  gapTolSec: {
    default: 0.5,
    min: 0,
    max: 10,
    step: 0.1,
    precision: 1,
    label: 'C1 间隙容忍(s)',
    hint: '段内短暂断供仍续计，0=默认 0.5'
  },
  gapSec: {
    default: 1.5,
    min: 0,
    max: 60,
    step: 0.1,
    precision: 1,
    label: '断供 GAP(s)',
    hint: '断供超过该时长状态重置，0=默认 1.5'
  },
  deskSec: {
    default: 1.5,
    min: 0,
    max: 120,
    step: 0.5,
    precision: 1,
    label: '趴桌持续(s)',
    hint: 'C3 趴桌判定所需持续时长，0=默认 1.5'
  }
}

// roiEnabled 语义：-1/1 = 开，0 = 关；统一落库为 1/0
export function normalizeRoiEnabled(raw) {
  return Number(raw) === 0 ? 0 : 1
}

// 供父组件在 rule 缺键/复位时生成完整默认值集合
export function sleepAdvancedDefaults() {
  const result = {}
  Object.keys(SLEEP_ADVANCED_PARAMS).forEach(key => {
    result[key] = SLEEP_ADVANCED_PARAMS[key].default
  })
  return result
}
