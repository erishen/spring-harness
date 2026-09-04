<template>
  <div class="tool-call-card" :class="{ running: toolCall.status === 'running', error: isError }">
    <!-- 头部 -->
    <div class="tool-call-header" @click="expanded = !expanded">
      <span class="tool-name">
        <span v-if="toolCall.status === 'running'" class="spinner"></span>
        <span v-else-if="isError" class="status-icon error">✗</span>
        <span v-else class="status-icon success">✓</span>
        {{ toolDisplayName(toolCall.name) }}
      </span>
      <span v-if="toolCall.durationMs != null" class="tool-duration">{{ formatDuration(toolCall.durationMs) }}</span>
      <span v-if="toolCall.status" class="tool-status" :class="toolCall.status">
        {{ toolCall.status === 'running' ? '执行中...' : (isError ? '失败' : '完成') }}
      </span>
      <span class="expand-icon">{{ expanded ? '▼' : '▶' }}</span>
    </div>

    <!-- 展开内容 -->
    <div v-show="expanded" class="tool-call-body">
      <!-- 入参 -->
      <div v-if="hasInput" class="tool-section">
        <div class="section-header" @click="showInput = !showInput">
          <span class="section-label">📥 入参</span>
          <span class="section-toggle">{{ showInput ? '收起' : '展开' }}</span>
        </div>
        <pre v-show="showInput" class="section-content input">{{ formatInput(toolCall.input) }}</pre>
      </div>

      <!-- 代码执行结果（结构化展示） -->
      <template v-if="isCodeExecution && parsedResult">
        <!-- 代码 -->
        <div v-if="codeInput" class="tool-section">
          <div class="section-header">
            <span class="section-label">📝 代码</span>
            <span class="section-lang">{{ codeLanguage || 'unknown' }}</span>
          </div>
          <pre class="section-content code"><code>{{ codeInput }}</code></pre>
        </div>

        <!-- stdout -->
        <div v-if="parsedResult.stdout" class="tool-section">
          <div class="section-header">
            <span class="section-label stdout">📤 stdout</span>
          </div>
          <pre class="section-content stdout">{{ parsedResult.stdout }}</pre>
        </div>

        <!-- stderr -->
        <div v-if="parsedResult.stderr" class="tool-section">
          <div class="section-header">
            <span class="section-label stderr">⚠️ stderr</span>
          </div>
          <pre class="section-content stderr">{{ parsedResult.stderr }}</pre>
        </div>

        <!-- 执行信息 -->
        <div class="tool-exec-info">
          <span class="exec-item" :class="{ ok: parsedResult.exitCode === 0 }">
            exitCode: {{ parsedResult.exitCode }}
          </span>
          <span v-if="parsedResult.durationMs != null" class="exec-item">
            耗时: {{ formatDuration(parsedResult.durationMs) }}
          </span>
          <span v-if="parsedResult.timedOut" class="exec-item timeout">超时</span>
        </div>
      </template>

      <!-- 通用结果 -->
      <div v-else-if="hasOutput" class="tool-section">
        <div class="section-header">
          <span class="section-label">📤 结果</span>
        </div>
        <pre class="section-content output">{{ formatOutput(toolCall.output) }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { toolDisplayName, formatInput, formatOutput } from '../utils/format.js'

const props = defineProps({
  toolCall: {
    type: Object,
    required: true
  }
})

const expanded = ref(true)
const showInput = ref(false)

/** 是否为代码执行工具 */
const isCodeExecution = computed(() => props.toolCall.name === 'execute_code')

/** 解析结果为 JSON（用于代码执行等结构化结果） */
const parsedResult = computed(() => {
  if (!props.toolCall.output) return null
  if (typeof props.toolCall.output !== 'string') return props.toolCall.output
  try {
    return JSON.parse(props.toolCall.output)
  } catch {
    return null
  }
})

/** 代码入参（execute_code 的 code 字段） */
const codeInput = computed(() => {
  if (!isCodeExecution.value || !props.toolCall.input) return ''
  if (typeof props.toolCall.input === 'string') {
    try {
      const obj = JSON.parse(props.toolCall.input)
      return obj.code || ''
    } catch {
      return props.toolCall.input
    }
  }
  return props.toolCall.input.code || ''
})

/** 代码语言 */
const codeLanguage = computed(() => {
  if (!isCodeExecution.value || !props.toolCall.input) return ''
  if (typeof props.toolCall.input === 'string') {
    try {
      const obj = JSON.parse(props.toolCall.input)
      return obj.language || ''
    } catch {
      return ''
    }
  }
  return props.toolCall.input.language || ''
})

/** 是否有入参 */
const hasInput = computed(() => {
  if (!props.toolCall.input) return false
  if (typeof props.toolCall.input === 'string') return props.toolCall.input.trim() !== ''
  return Object.keys(props.toolCall.input).length > 0
})

/** 是否有结果 */
const hasOutput = computed(() => {
  return props.toolCall.output && props.toolCall.output !== '(无)'
})

/** 是否为错误结果 */
const isError = computed(() => {
  if (parsedResult.value && parsedResult.value.exitCode != null && parsedResult.value.exitCode !== 0) return true
  if (props.toolCall.output && /错误|失败|Exception|Error|timeout|超时/i.test(String(props.toolCall.output))) return true
  return false
})

/** 格式化耗时 */
function formatDuration(ms) {
  if (ms == null) return ''
  if (ms < 1000) return ms + 'ms'
  return (ms / 1000).toFixed(1) + 's'
}
</script>

<style scoped>
.tool-call-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  margin: 8px 0;
  overflow: hidden;
  transition: all 0.15s;
}
.tool-call-card.running {
  border-color: rgba(59, 130, 246, 0.4);
  background: rgba(59, 130, 246, 0.02);
}
.tool-call-card.error {
  border-color: rgba(239, 68, 68, 0.3);
}

/* 头部 */
.tool-call-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f9fafb;
  border-bottom: 1px solid #f3f4f6;
  font-size: 12.5px;
  cursor: pointer;
  user-select: none;
}
.tool-call-header:hover {
  background: #f3f4f6;
}
.tool-name {
  font-weight: 600;
  color: #374151;
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
}
.status-icon {
  font-size: 10px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.status-icon.success {
  background: #d1fae5;
  color: #059669;
}
.status-icon.error {
  background: #fee2e2;
  color: #dc2626;
}
.tool-duration {
  color: #9ca3af;
  font-size: 11px;
  font-family: 'SF Mono', Monaco, monospace;
  flex-shrink: 0;
}
.tool-status {
  font-size: 10.5px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
  flex-shrink: 0;
}
.tool-status.running {
  color: #2563eb;
  background: #dbeafe;
}
.tool-status:not(.running) {
  color: #059669;
  background: #d1fae5;
}
.tool-call-card.error .tool-status:not(.running) {
  color: #dc2626;
  background: #fee2e2;
}
.expand-icon {
  font-size: 9px;
  color: #9ca3af;
  flex-shrink: 0;
}

/* 展开内容 */
.tool-call-body {
  padding: 8px 12px;
}

/* 分区 */
.tool-section {
  margin-bottom: 8px;
}
.tool-section:last-child {
  margin-bottom: 0;
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  cursor: pointer;
}
.section-label {
  font-size: 11px;
  font-weight: 600;
  color: #6b7280;
}
.section-label.stdout { color: #059669; }
.section-label.stderr { color: #dc2626; }
.section-toggle {
  font-size: 10px;
  color: #9ca3af;
}
.section-lang {
  font-size: 10px;
  color: #9ca3af;
  background: #f3f4f6;
  padding: 1px 6px;
  border-radius: 3px;
  font-family: 'SF Mono', Monaco, monospace;
}

/* 内容区 */
.section-content {
  margin: 4px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 11.5px;
  font-family: 'SF Mono', 'Fira Code', Monaco, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
  line-height: 1.5;
}
.section-content::-webkit-scrollbar { width: 4px; }
.section-content::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.15); border-radius: 2px; }

.section-content.input {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  color: #475569;
}
.section-content.output {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
}
.section-content.code {
  background: #1e293b;
  border: 1px solid #334155;
  color: #e2e8f0;
}
.section-content.code code {
  background: none;
  padding: 0;
}
.section-content.stdout {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
}
.section-content.stderr {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #991b1b;
}

/* 执行信息 */
.tool-exec-info {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  font-size: 11px;
  font-family: 'SF Mono', Monaco, monospace;
}
.exec-item {
  padding: 2px 8px;
  border-radius: 4px;
  background: #f3f4f6;
  color: #6b7280;
}
.exec-item.ok {
  background: #d1fae5;
  color: #059669;
}
.exec-item.timeout {
  background: #fef3c7;
  color: #b45309;
}

/* 旋转动画 */
.spinner {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2px solid rgba(59, 130, 246, 0.3);
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
