<template>
  <div class="task-detail" @click.stop>
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

            <!-- answer 步骤：LLM 最终回答（Markdown），与最终结果相同则简洁提示 -->
            <div v-if="step.content && isAnswerStep(step) && !isDupAnswer(step)" class="step-md task-result-content" v-html="renderMarkdown(step.content)"></div>
            <pre v-else-if="step.content && !(isAnswerStep(step) && isDupAnswer(step))" class="step-content">{{ step.content }}</pre>
            <div v-if="step.step?.content && step.step.content !== step.content && isAnswerStep(step) && !isDupAnswer(step)" class="step-md task-result-content" v-html="renderMarkdown(step.step.content)"></div>
            <pre v-else-if="step.step?.content && step.step.content !== step.content && !(isAnswerStep(step) && isDupAnswer(step))" class="step-content">{{ step.step.content }}</pre>
            <div v-if="isAnswerStep(step) && isDupAnswer(step)" class="step-done-hint">✓ 生成最终回答（完整内容见下方结果）</div>

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
import { renderMarkdown } from '../utils/markdown.js'

const props = defineProps({
  task: { type: Object, required: true },
  copyDone: { type: String, default: '' },
  logOpenId: { type: String, default: '' }
})

defineEmits(['copyDetail', 'toggleLog'])

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
.step-content {
  width: 100%;
  margin: 2px 0 0;
  font-size: 11px;
  color: #4b5563;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'SF Mono', Monaco, monospace;
  line-height: 1.4;
}
.step-md {
  width: 100%;
  margin: 2px 0 0;
  font-size: 12px;
  color: #374151;
  line-height: 1.5;
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
  word-break: break-word;
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
