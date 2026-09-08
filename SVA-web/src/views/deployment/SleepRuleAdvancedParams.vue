<template>
  <div v-if="rule" class="sleep-advanced-panel">
    <div class="sleep-advanced-header">
      <span class="sleep-advanced-toggle" @click="expanded = !expanded">
        <i :class="expanded ? 'el-icon-arrow-down' : 'el-icon-arrow-right'" />
        <span class="sleep-advanced-title">睡岗高级参数（pose/ROI/兜底）</span>
      </span>
      <span v-if="!expanded" class="sleep-advanced-summary">{{ collapsedSummary }}</span>
      <span v-if="expanded" class="sleep-advanced-reset" @click="handleReset">恢复默认</span>
    </div>
    <div v-if="expanded" class="sleep-advanced-grid">
      <div class="srap-cell srap-cell--roi">
        <div class="srap-label">ROI 增强</div>
        <el-select
          size="mini"
          class="srap-input"
          :value="displayValue('roiEnabled')"
          @change="value => commit('roiEnabled', value)"
        >
          <el-option :value="1" label="开启" />
          <el-option :value="0" label="关闭" />
        </el-select>
      </div>
      <div v-for="f in numericFields" :key="f.key" class="srap-cell">
        <el-tooltip :content="f.hint" placement="top" :open-delay="300">
          <div class="srap-label">{{ f.label }}</div>
        </el-tooltip>
        <el-input-number
          size="mini"
          class="srap-input"
          :value="displayValue(f.key)"
          :min="f.min"
          :max="f.max"
          :step="f.step !== undefined ? f.step : 1"
          :precision="f.precision !== undefined ? f.precision : (f.integer ? 0 : undefined)"
          controls-position="right"
          @change="value => commit(f.key, value)"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { SLEEP_ADVANCED_PARAMS } from './sleepAdvancedParams'

export default {
  name: 'SleepRuleAdvancedParams',
  props: {
    rule: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      SLEEP_ADVANCED_PARAMS,
      expanded: false
    }
  },
  computed: {
    numericFields() {
      const result = []
      Object.keys(SLEEP_ADVANCED_PARAMS).forEach(key => {
        if (key === 'roiEnabled') {
          return
        }
        result.push({
          key,
          ...SLEEP_ADVANCED_PARAMS[key]
        })
      })
      return result
    },
    collapsedSummary() {
      const roiOn = this.displayValue('roiEnabled') === 1
      return `ROI ${roiOn ? '开' : '关'} · 预算 ${this.displayValue('roiBudget')} · 兜底 ${this.displayValue('boxFallbackMs')}ms`
    }
  },
  methods: {
    displayValue(key) {
      if (!this.rule) {
        return SLEEP_ADVANCED_PARAMS[key].default
      }
      const spec = SLEEP_ADVANCED_PARAMS[key]
      const raw = this.rule[key]
      if (key === 'roiEnabled') {
        // -1/1 = 开，0 = 关；界面统一展示 1/0
        return Number(raw) === 0 ? 0 : 1
      }
      if (raw === undefined || raw === null || raw === '') {
        return spec.default
      }
      return Number(raw)
    },
    commit(key, value) {
      const spec = SLEEP_ADVANCED_PARAMS[key]
      let nextValue
      if (key === 'roiEnabled') {
        nextValue = Number(value) === 0 ? 0 : 1
      } else if (value === undefined || value === null || value === '') {
        nextValue = spec.default
      } else {
        let numericValue = Number(value)
        if (!Number.isFinite(numericValue)) {
          numericValue = spec.default
        }
        if (spec.integer) {
          numericValue = Math.round(numericValue)
        } else if (typeof spec.precision === 'number') {
          numericValue = Number(numericValue.toFixed(spec.precision))
        }
        if (typeof spec.min === 'number') {
          numericValue = Math.max(spec.min, numericValue)
        }
        if (typeof spec.max === 'number') {
          numericValue = Math.min(spec.max, numericValue)
        }
        nextValue = numericValue
      }
      this.$emit('change', this.rule.id, key, nextValue)
    },
    handleReset() {
      this.$emit('reset', this.rule.id)
    }
  }
}
</script>

<style scoped>
.sleep-advanced-panel {
  margin-top: 4px;
  padding: 5px 10px;
  border: 1px dashed #dcdfe6;
  border-radius: 4px;
  background: #fafbfc;
  width: 100%;
  box-sizing: border-box;
}

.sleep-advanced-header {
  display: flex;
  align-items: center;
  min-height: 22px;
}

.sleep-advanced-toggle {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  user-select: none;
  min-width: 0;
}

.sleep-advanced-title {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin-left: 2px;
}

.sleep-advanced-toggle .el-icon-arrow-right,
.sleep-advanced-toggle .el-icon-arrow-down {
  font-size: 12px;
  color: #909399;
}

.sleep-advanced-summary {
  margin-left: 10px;
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sleep-advanced-reset {
  margin-left: auto;
  font-size: 12px;
  color: #409eff;
  cursor: pointer;
  user-select: none;
}

.sleep-advanced-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: 6px 10px;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #e4e7ed;
}

.srap-cell {
  min-width: 0;
}

.srap-label {
  font-size: 11px;
  color: #606266;
  margin-bottom: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: default;
}

.srap-input {
  width: 100%;
}

.srap-cell--roi .el-select {
  width: 100%;
}
</style>
