<template>
  <div class="task-detail" ref="rootEl" @click.stop>
    <!-- 复制执行过程 -->
    <div class="task-detail-tools">
      <button class="copy-detail-btn" :class="{ copied: copyDone === task.id }" @click="$emit('copyDetail', task)">
        {{ copyDone === task.id ? '✓ 已复制' : '📋 复制执行过程' }}
      </button>
    </div>

    <!-- 错误 -->
    <div v-if="task.error" class="task-error">
      <span class="detail-label">错误</span>
      <pre>{{ task.error }}</pre>
    </div>

    <!-- 执行过程 -->
    <div v-if="task.steps && task.steps.length" class="task-steps">
      <span class="detail-label">执行过程（{{ task.steps.length }} 条）</span>
      <div class="step-list">
        <template v-for="(step, si) in task.steps" :key="si">
          <div v-if="!(step.type === 'thinking' && !(step.content || step.step?.content || '').trim())" class="step-item">
            <span class="step-seq">{{ si + 1 }}</span>
            <span class="step-type">{{ step.type || step.step?.role || 'step' }}</span>
            <span class="step-title">{{ step.step?.title || step.step?.action || '' }}</span>

            <!-- 步骤内容：所有步骤都用 Markdown 渲染（除工具调用的入参/结果外） -->
            <!-- answer 步骤与最终结果重复时显示简洁提示 -->
            <div v-if="isAnswerStep(step) && isDupAnswer(step)" class="step-done-hint">✓ 生成最终回答（完整内容见下方结果）</div>
            <div v-else-if="step.content" class="step-md" v-html="renderMarkdown(step.content)"></div>
            <div v-if="step.step?.content && step.step.content !== step.content && !(isAnswerStep(step) && isDupAnswer(step))" class="step-md" v-html="renderMarkdown(step.step.content)"></div>

            <!-- tool_call -->
            <div v-if="step.toolCall && step.type === 'tool_call'" class="step-tool">
              <span class="tool-name">{{ step.toolCall.name }}</span>
              <template v-if="step.toolCall.name === 'execute_code'">
                <div class="tool-io-label">代码</div>
                <pre class="tool-io code">{{ codeOf(step.toolCall.input) }}</pre>
              </template>
              <template v-else>
                <div class="tool-io-label">入参</div>
                <pre class="tool-io">{{ prettyJson(step.toolCall.input) }}</pre>
              </template>
            </div>
            <!-- tool_result -->
            <div v-else-if="step.toolCall && step.type === 'tool_result'" class="step-tool">
              <span class="tool-name">{{ step.toolCall.name }}</span>
              <template v-if="step.toolCall.name === 'execute_code'">
                <div class="tool-io-label">结果</div>
                <pre class="tool-io code">{{ stdoutOf(step.toolCall.output) }}</pre>
                <pre v-if="stderrOf(step.toolCall.output)" class="tool-io code err">{{ stderrOf(step.toolCall.output) }}</pre>
              </template>
              <template v-else>
                <div class="tool-io-label">结果</div>
                <pre class="tool-io">{{ prettyJson(step.toolCall.output) }}</pre>
              </template>
            </div>
            <!-- 其他步骤内的工具信息 -->
            <div v-else-if="step.toolCall" class="step-tool">
              <span class="tool-name">{{ step.toolCall.name }}</span>
              <pre v-if="step.toolCall.input" class="tool-io">入参: {{ prettyJson(step.toolCall.input) }}</pre>
              <pre v-if="step.toolCall.output" class="tool-io">结果: {{ prettyJson(step.toolCall.output) }}</pre>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 最终结果 -->
    <div v-if="task.result" class="task-result">
      <span class="detail-label">结果</span>
      <div class="task-result-content" v-html="renderMarkdown(task.result)"></div>
    </div>

    <!-- 执行日志 -->
    <div v-if="task.logs && task.logs.length" class="task-log-bar" @click.stop>
      <button class="task-log-btn" @click="$emit('toggleLog', task.id)">
        <span>📋 执行日志（{{ task.logs.length }} 条）</span>
        <span class="log-toggle">{{ logOpenId === task.id ? '收起 ▲' : '查看 ▼' }}</span>
      </button>
      <div v-if="logOpenId === task.id" class="task-logs">
        <div class="log-list">
          <div v-for="(line, li) in task.logs" :key="li" class="log-line">{{ line }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from 'vue'
import { renderMarkdown, enhanceCodeBlocks } from '../utils/markdown.js'

const props = defineProps({
  task: { type: Object, required: true },
  copyDone: { type: String, default: '' },
  logOpenId: { type: String, default: '' }
})

defineEmits(['copyDetail', 'toggleLog'])

const rootEl = ref(null)

// 渲染后增强代码块（添加复制按钮）
function applyCodeEnhance() {
  nextTick(() => {
    if (rootEl.value) {
      enhanceCodeBlocks(rootEl.value)
    }
  })
}

onMounted(applyCodeEnhance)
watch(() => props.task.result, applyCodeEnhance)
watch(() => props.task.steps, applyCodeEnhance, { deep: true })

function isAnswerStep(step) {
  return step?.type === 'answer' || step?.step?.role === 'answer'
}

function isDupAnswer(step) {
  if (!isAnswerStep(step)) return false
  const a = (step.content || step.step?.content || '').trim()
  const r = (props.task.result || '').trim()
  return a !== '' && a === r
}

function prettyJson(str) {
  if (!str) return ''
  try { return JSON.stringify(JSON.parse(str), null, 2) } catch { return str }
}

function codeOf(input) {
  try { return JSON.parse(input)?.code ?? input } catch { return input }
}

function stdoutOf(output) {
  try { const o = JSON.parse(output); return o.stdout ?? output } catch { return output }
}

function stderrOf(output) {
  try { return JSON.parse(output)?.stderr || '' } catch { return '' }
}
</script>

<style scoped>
.task-detail {
  padding: 10px 12px;
  border-top: 1px solid #f3f4f6;
  background: #fafbfc;
}
.task-detail-tools {
  margin-bottom: 8px;
}
.copy-detail-btn {
  font-size: 11px;
  padding: 3px 10px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.copy-detail-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.copy-detail-btn.copied { border-color: #10b981; color: #10b981; }

.detail-label {
  display: block;
  font-size: 10px;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  margin-bottom: 4px;
}

/* 错误 */
.task-error { margin-bottom: 8px; }
.task-error pre {
  margin: 0;
  padding: 6px 8px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 4px;
  font-size: 11px;
  color: #991b1b;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'SF Mono', Monaco, monospace;
}

/* 执行步骤 */
.task-steps { margin-bottom: 8px; }
.step-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.step-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 6px;
  padding: 4px 6px;
  background: #fff;
  border: 1px solid #eef0f3;
  border-radius: 4px;
}
.step-seq {
  font-size: 9px;
  font-weight: 700;
  color: #9ca3af;
  min-width: 14px;
}
.step-type {
  font-size: 9px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 3px;
  background: #f3f4f6;
  color: #6b7280;
  text-transform: uppercase;
}
.step-title {
  font-size: 10.5px;
  color: #374151;
  font-weight: 500;
}
.step-md {
  width: 100%;
  margin: 4px 0 2px;
  padding: 8px 10px;
  background: #f9fafb;
  border: 1px solid #eef0f3;
  border-radius: 4px;
  font-size: 12px;
  color: #374151;
  line-height: 1.6;
}
.step-done-hint {
  width: 100%;
  font-size: 10px;
  color: #10b981;
  font-style: italic;
  margin-top: 2px;
}

/* 工具调用 */
.step-tool {
  width: 100%;
  margin-top: 4px;
}
.step-tool .tool-name {
  font-size: 10px;
  font-weight: 600;
  color: #2563eb;
  font-family: 'SF Mono', Monaco, monospace;
}
.tool-io-label {
  font-size: 9px;
  color: #9ca3af;
  margin-top: 3px;
}
.tool-io {
  margin: 2px 0 0;
  padding: 4px 6px;
  background: #f9fafb;
  border-radius: 3px;
  font-size: 10.5px;
  font-family: 'SF Mono', Monaco, monospace;
  white-space: pre-wrap;
  word-break: break-all;
  color: #374151;
  line-height: 1.4;
}
.tool-io.code { background: #1e293b; color: #e2e8f0; }
.tool-io.code.err { background: #7f1d1d; color: #fecaca; margin-top: 2px; }

/* 最终结果 */
.task-result { margin-bottom: 8px; }
.task-result-content {
  font-size: 12.5px;
  color: #1f2937;
  line-height: 1.6;
}
/* v-html 动态渲染的 Markdown 内容需要 :deep() 穿透 scoped */
.task-result-content :deep(h1),
.task-result-content :deep(h2),
.task-result-content :deep(h3),
.task-result-content :deep(h4) {
  margin: 12px 0 6px;
  font-weight: 600;
  color: #111827;
  line-height: 1.4;
}
.task-result-content :deep(h1) { font-size: 16px; }
.task-result-content :deep(h2) { font-size: 15px; }
.task-result-content :deep(h3) { font-size: 14px; }
.task-result-content :deep(h4) { font-size: 13px; }
.task-result-content :deep(p) { margin: 6px 0; }
.task-result-content :deep(ul),
.task-result-content :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}
.task-result-content :deep(li) { margin: 2px 0; }
.task-result-content :deep(strong) { color: #111827; font-weight: 600; }
.task-result-content :deep(em) { color: #4b5563; }
.task-result-content :deep(blockquote) {
  margin: 8px 0;
  padding: 6px 12px;
  border-left: 3px solid #d1d5db;
  background: #f9fafb;
  color: #6b7280;
}
/* 表格样式：关键修复 */
.task-result-content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 10px 0;
  font-size: 12px;
  display: block;
  overflow-x: auto;
}
.task-result-content :deep(thead) { background: #f3f4f6; }
.task-result-content :deep(th),
.task-result-content :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 6px 10px;
  text-align: left;
  white-space: nowrap;
}
.task-result-content :deep(th) {
  font-weight: 600;
  color: #374151;
}
.task-result-content :deep(tbody tr:nth-child(even)) { background: #fafbfc; }
.task-result-content :deep(tbody tr:hover) { background: #f0f7ff; }
/* 代码块 */
.task-result-content :deep(pre) {
  margin: 8px 0;
  padding: 10px 12px;
  background: #1e293b;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 11.5px;
  line-height: 1.5;
}
.task-result-content :deep(pre code) {
  background: none;
  padding: 0;
  color: #e2e8f0;
  font-family: 'SF Mono', Monaco, monospace;
}
.task-result-content :deep(code) {
  background: #f3f4f6;
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 11.5px;
  font-family: 'SF Mono', Monaco, monospace;
  color: #be185d;
}
.task-result-content :deep(hr) {
  border: none;
  border-top: 1px solid #e5e7eb;
  margin: 12px 0;
}
.task-result-content :deep(a) {
  color: #2563eb;
  text-decoration: none;
}
.task-result-content :deep(a:hover) { text-decoration: underline; }

/* step-md（answer 步骤中的 Markdown）同样需要 :deep() */
.step-md :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 11.5px;
  display: block;
  overflow-x: auto;
}
.step-md :deep(th),
.step-md :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 5px 8px;
  text-align: left;
  white-space: nowrap;
}
.step-md :deep(thead) { background: #f3f4f6; }
.step-md :deep(pre) {
  margin: 6px 0;
  padding: 8px 10px;
  background: #1e293b;
  border-radius: 5px;
  overflow-x: auto;
  font-size: 11px;
}
.step-md :deep(pre code) { background: none; color: #e2e8f0; }
.step-md :deep(code) {
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 11px;
  color: #be185d;
}

/* 执行日志 */
.task-log-bar { margin-top: 6px; }
.task-log-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 8px;
  font-size: 11px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.task-log-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.log-toggle { font-size: 10px; }
.task-logs {
  margin-top: 4px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  overflow: hidden;
}
.log-list {
  max-height: 200px;
  overflow-y: auto;
  background: #1e293b;
  padding: 6px 8px;
}
.log-line {
  font-size: 10px;
  font-family: 'SF Mono', Monaco, monospace;
  color: #94a3b8;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
