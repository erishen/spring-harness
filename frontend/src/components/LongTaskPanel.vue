<template>
  <div class="longtask-panel">
    <!-- 提交表单 -->
    <div class="task-form">
      <div class="task-form-row">
        <select v-model="taskType" class="task-type-select" title="任务类型">
          <option value="pse">PSE 协作</option>
          <option value="agent">ReAct Agent</option>
          <option value="chat">普通对话</option>
        </select>
        <input v-model="taskModel" class="task-model-input" placeholder="模型（默认 agnes-2.0-flash）" />
        <button class="task-submit-btn" :disabled="submitting" @click="submitTask">
          {{ submitting ? '提交中...' : '提交任务' }}
        </button>
      </div>
      <textarea
        v-model="taskMessage"
        class="task-message-input"
        placeholder="输入任务描述，如：帮我调研苹果公司并分析投资价值（会后台异步执行，不受超时限制）"
        rows="1"
        @input="e => autoGrow(e.target)"
      ></textarea>
    </div>

    <!-- 示例任务：快速验证各类能力 -->
    <div class="task-examples">
      <div class="task-examples-header">
        <span class="task-examples-title">✨ 示例任务</span>
        <span class="task-examples-hint">点击预填 · 覆盖本地工具 / Skills / MCP</span>
      </div>
      <div class="task-example-item" v-for="ex in examples" :key="ex.title" @click="fillExample(ex)">
        <span class="ex-type" :class="ex.type">{{ ex.typeLabel }}</span>
        <span class="ex-text">{{ ex.title }}</span>
        <span class="ex-tags">{{ ex.tags.join(' · ') }}</span>
      </div>
    </div>

    <!-- 任务列表 -->
    <div class="task-list">
      <div class="task-list-header">
        <span class="task-list-title">📋 长时任务（{{ tasks.length }}）</span>
        <span class="task-list-hint">每 3 秒自动刷新 · 后台异步执行</span>
      </div>

      <div v-if="tasks.length === 0" class="task-empty">
        暂无任务。提交一个 PSE / ReAct / 对话任务，它会在后台执行，不会阻塞页面。
      </div>

      <div v-for="task in tasks" :key="task.id" class="task-card" :class="task.status" @click="toggleDetail(task.id)">
        <div class="task-card-main">
          <div class="task-type-badge" :class="task.type">{{ typeLabel(task.type) }}</div>
          <div class="task-info">
            <div class="task-message" :title="task.message">{{ task.message }}</div>
            <div class="task-meta">
              <span class="task-id">#{{ task.id }}</span>
              <span class="task-time">{{ formatTime(task.createdAt) }}</span>
              <span class="task-duration" v-if="task.durationMs">{{ formatDuration(task.durationMs) }}</span>
              <span class="task-model" v-if="task.model">{{ task.model }}</span>
            </div>
            <div class="task-progress" v-if="task.progressText && !task.terminal">{{ task.progressText }}</div>
          </div>
          <div class="task-right">
            <span class="task-status-badge" :class="task.status">{{ statusLabel(task.status) }}</span>
            <span class="task-tokens" v-if="task.tokenUsage && task.tokenUsage.totalTokens">
              ⚡{{ task.tokenUsage.totalTokens }} tok · {{ task.tokenUsage.llmCallCount }} 次
            </span>
            <div class="task-actions" @click.stop>
              <button
                v-if="task.status === 'running' || task.status === 'pending'"
                class="task-cancel-btn"
                title="中断任务"
                @click="cancelTask(task.id)"
              >⏹</button>
              <button
                v-else-if="task.status === 'failed' || task.status === 'cancelled'"
                class="task-restart-btn"
                title="续跑（重新执行）"
                @click="restartTask(task.id)"
              >↻</button>
              <button
                v-else
                class="task-delete-btn"
                title="删除任务"
                @click="deleteTask(task.id)"
              >🗑</button>
            </div>
          </div>
        </div>

        <!-- 详情：步骤 + 结果 -->
        <div v-if="detailId === task.id" class="task-detail">
          <div class="task-detail-tools" @click.stop>
            <button class="copy-detail-btn" :class="{ copied: copyDone === task.id }" @click="copyTaskDetail(task)">
              {{ copyDone === task.id ? '✓ 已复制' : '📋 复制执行过程' }}
            </button>
          </div>
          <div v-if="task.error" class="task-error">
            <span class="detail-label">错误</span>
            <pre>{{ task.error }}</pre>
          </div>
          <div v-if="task.steps && task.steps.length" class="task-steps">
            <span class="detail-label">执行过程（{{ task.steps.length }} 条）</span>
            <div class="step-list">
              <div v-for="(step, si) in task.steps" :key="si" class="step-item">
                <span class="step-seq">{{ si + 1 }}</span>
                <span class="step-type">{{ step.type || step.step?.role || 'step' }}</span>
                <span class="step-title">{{ step.step?.title || step.step?.action || '' }}</span>
                <pre v-if="step.content" class="step-content">{{ step.content }}</pre>
                <pre v-if="step.step?.content && step.step.content !== step.content" class="step-content">{{ step.step.content }}</pre>
                <div v-if="step.toolCall" class="step-tool">
                  <span class="tool-name">{{ step.toolCall.name }}</span>
                  <pre class="tool-io">入参: {{ step.toolCall.input }}</pre>
                  <pre v-if="step.toolCall.output" class="tool-io">结果: {{ step.toolCall.output }}</pre>
                </div>
              </div>
            </div>
          </div>
          <div v-if="task.result" class="task-result">
            <span class="detail-label">结果</span>
            <div class="task-result-content" v-html="renderMarkdown(task.result)"></div>
          </div>
          <div v-if="task.logs && task.logs.length" class="task-log-bar" @click.stop>
            <button class="task-log-btn" @click="toggleLog(task.id)">
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
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { renderMarkdown, enhanceCodeBlocks } from '../utils/markdown'

const tasks = ref([])
const taskType = ref('pse')
const taskMessage = ref('')
const taskModel = ref('')
const submitting = ref(false)
const detailId = ref('')
const logOpenId = ref('')
const copyDone = ref('')
let timer = null

// 示例任务：覆盖本地工具 / Skills / MCP 三类能力
const examples = [
  {
    type: 'pse',
    typeLabel: 'PSE',
    title: '查询 AAPL、TSLA、MSFT 三只股票实时行情，算买100股各需多少人民币并比较今日涨幅',
    tags: ['query_stock', 'query_exchange_rate', 'calculator'],
  },
  {
    type: 'agent',
    typeLabel: 'ReAct',
    title: '现在几点？算 123×456，查美元兑人民币汇率，再用代码沙箱计算斐波那契第20项',
    tags: ['get_datetime', 'calculator', 'query_exchange_rate', 'execute_code'],
  },
  {
    type: 'pse',
    typeLabel: 'PSE',
    title: '生成一份本周投资组合周报（调用投资周报技能）',
    tags: ['skill_run → weekly-investment'],
  },
  {
    type: 'agent',
    typeLabel: 'ReAct',
    title: '用代码审查技能审查 ReActAgentService.java，输出结构化审查报告',
    tags: ['skill_run → code-review'],
  },
  {
    type: 'agent',
    typeLabel: 'ReAct',
    title: '列出项目目录结构，读取 README.md 并总结项目要点',
    tags: ['MCP list_directory', 'MCP read_file'],
  },
  {
    type: 'agent',
    typeLabel: 'ReAct',
    title: '用 Go 并发筛出 1~1000000 的全部素数，统计个数与耗时（计算密集型，需数秒~数十秒）',
    tags: ['execute_code → go'],
  },
  {
    type: 'agent',
    typeLabel: 'ReAct',
    title: '用 Java 生成 10 万条随机整数并排序，输出最大的 10 个数与耗时',
    tags: ['execute_code → java'],
  },
]

async function fillExample(ex) {
  taskType.value = ex.type
  taskMessage.value = ex.title
  // 聚焦到输入框（高度由 watch 自动适配）
  await nextTick()
  const ta = document.querySelector('.task-message-input')
  if (ta) ta.focus()
}

// 输入框高度自适应：内容多时自动增高，达到上限后内部滚动（滚动条已隐藏）
function autoGrow(e) {
  if (!e || !e.style) return
  e.style.height = 'auto'
  e.style.height = Math.min(e.scrollHeight, 120) + 'px'
}

// 消息变化（点击示例/手动输入）后同步输入框高度
watch(taskMessage, () => {
  const ta = document.querySelector('.task-message-input')
  if (ta) autoGrow(ta)
}, { flush: 'post' })

function typeLabel(t) {
  return { pse: 'PSE', agent: 'ReAct', chat: '对话' }[t] || t
}
function statusLabel(s) {
  return { pending: '排队中', running: '执行中', completed: '已完成', failed: '失败', cancelled: '已取消' }[s] || s
}
function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}
function formatDuration(ms) {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60000)}m${Math.floor((ms % 60000) / 1000)}s`
}

async function fetchTasks() {
  try {
    const res = await fetch('/api/tasks')
    if (res.ok) {
      tasks.value = await res.json()
      // 详情打开时，结果 Markdown 更新后重新增强代码块
      if (detailId.value) enhanceDetail()
    }
  } catch (e) {
    /* 忽略轮询错误 */
  }
}

// 打开详情后给「结果」Markdown 渲染的代码块加语言标签 + 复制按钮
// 注意：只增强结果区，避免执行过程里的纯文本 pre 被误加 "text 复制" 头部
function enhanceDetail() {
  nextTick(() => {
    const rc = document.querySelector('.task-result-content')
    if (rc) enhanceCodeBlocks(rc)
  })
}

// 详情展开/收起时增强代码块
watch(detailId, (id) => {
  if (id) enhanceDetail()
})

async function submitTask() {
  const msg = taskMessage.value.trim()
  if (!msg) return
  submitting.value = true
  try {
    const params = new URLSearchParams({ type: taskType.value, message: msg })
    const model = taskModel.value.trim() || 'agnes-2.0-flash'
    params.set('model', model)
    const res = await fetch(`/api/tasks?${params}`, { method: 'POST' })
    if (res.ok) {
      taskMessage.value = ''
      await fetchTasks()
    }
  } finally {
    submitting.value = false
  }
}

async function cancelTask(id) {
  await fetch(`/api/tasks/${id}?cancel=true`, { method: 'DELETE' })
  await fetchTasks()
}

async function restartTask(id) {
  const res = await fetch(`/api/tasks/${id}/restart`, { method: 'POST' })
  if (res.ok) {
    await fetchTasks()
  }
}

async function deleteTask(id) {
  await fetch(`/api/tasks/${id}`, { method: 'DELETE' })
  if (detailId.value === id) detailId.value = ''
  await fetchTasks()
}

function toggleDetail(id) {
  detailId.value = detailId.value === id ? '' : id
  // 收起详情时同时收起日志
  if (detailId.value !== id) logOpenId.value = ''
}

function toggleLog(id) {
  logOpenId.value = logOpenId.value === id ? '' : id
}

// 截断过长的字段，避免复制内容爆炸
function truncate(s, max) {
  if (!s) return ''
  const str = typeof s === 'string' ? s : JSON.stringify(s)
  return str.length > max ? str.slice(0, max) + '…（截断）' : str
}

// 格式化任务执行过程（正确步骤简写，错误/工具调用步骤详细）
function formatTaskDetail(task) {
  const lines = []
  lines.push('=== 任务执行过程 ===')
  lines.push(`类型: ${task.type} · 模型: ${task.model || ''} · 状态: ${task.status}${task.durationMs ? ' · 耗时: ' + formatDuration(task.durationMs) : ''}`)
  if (task.error) {
    lines.push('')
    lines.push('【错误信息】')
    lines.push(task.error)
  }
  lines.push('')
  lines.push(`【执行步骤】共 ${task.steps?.length || 0} 条`)
  ;(task.steps || []).forEach((step, i) => {
    const type = step.type || step.step?.role || 'step'
    const title = step.step?.title || step.step?.action || ''
    // 识别错误步骤：类型为 error / 状态 FAIL / 内容含失败标志
    const stepText = [type, step.step?.status, title, step.content, step.step?.content]
      .filter(Boolean).join(' ')
    const isError = step.type === 'error'
      || step.step?.status === 'FAIL'
      || step.step?.failed
      || /FAIL|失败|未通过|❌|异常/.test(stepText)
    lines.push(`${isError ? '✗' : '✓'} 步骤 ${i + 1}: [${type}] ${title}`)
    // 错误步骤显示详细内容，正常步骤只显示摘要
    if (isError) {
      if (step.content) lines.push(`  内容: ${truncate(step.content, 600)}`)
      if (step.step?.content && step.step.content !== step.content) lines.push(`  内容: ${truncate(step.step.content, 600)}`)
    }
    if (step.toolCall) {
      lines.push(`  工具调用: ${step.toolCall.name}`)
      lines.push(`    入参: ${truncate(step.toolCall.input, 300)}`)
      if (step.toolCall.output) lines.push(`    结果: ${truncate(step.toolCall.output, 300)}`)
    }
  })
  if (task.result) {
    lines.push('')
    lines.push('【结果】')
    lines.push(task.result)
  }
  return lines.join('\n')
}

// 复制执行过程到剪贴板
async function copyTaskDetail(task) {
  const text = formatTaskDetail(task)
  try {
    await navigator.clipboard.writeText(text)
  } catch (e) {
    // 降级方案
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  copyDone.value = task.id
  setTimeout(() => {
    if (copyDone.value === task.id) copyDone.value = ''
  }, 1500)
}

onMounted(() => {
  fetchTasks()
  timer = setInterval(fetchTasks, 3000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.longtask-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  max-width: min(1200px, 100%);
  width: 100%;
  margin: 0 auto;
  overflow-y: auto;
  height: 100%;
}

/* ===== 提交表单 ===== */
.task-form {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 14px 16px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
}
.task-form-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.task-type-select,
.task-model-input {
  padding: 7px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  background: #f8fafc;
  color: #334155;
  outline: none;
}
.task-type-select:focus,
.task-model-input:focus {
  border-color: #4a6cf7;
  background: #fff;
}
.task-type-select { flex-shrink: 0; }
.task-model-input { flex: 1; min-width: 120px; }
.task-submit-btn {
  flex-shrink: 0;
  padding: 7px 16px;
  background: linear-gradient(135deg, #6b8cff, #4a6cf7);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s;
}
.task-submit-btn:hover { opacity: 0.9; }
.task-submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.task-message-input {
  width: 100%;
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 13px;
  color: #334155;
  outline: none;
  resize: none;
  min-height: 42px;
  max-height: 120px;
  overflow-y: auto;
  font-family: inherit;
  line-height: 1.5;
}
.task-message-input:focus { border-color: #4a6cf7; }

/* ===== 示例任务 ===== */
.task-examples {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  padding: 12px 14px;
}
.task-examples-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.task-examples-title { font-size: 13px; font-weight: 600; color: #334155; }
.task-examples-hint { font-size: 11px; color: #94a3b8; }
.task-example-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  margin-bottom: 5px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}
.task-example-item:hover {
  border-color: #4a6cf7;
  background: #eef2ff;
  transform: translateX(2px);
}
.ex-type {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 5px;
}
.ex-type.pse { background: #ede9fe; color: #6d28d9; }
.ex-type.agent { background: #dbeafe; color: #1d4ed8; }
.ex-type.chat { background: #d1fae5; color: #047857; }
.ex-text {
  flex: 1;
  font-size: 12px;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ex-tags {
  flex-shrink: 0;
  font-size: 10px;
  color: #64748b;
  background: #f1f5f9;
  border-radius: 5px;
  padding: 2px 7px;
  max-width: 260px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ===== 任务列表 ===== */
.task-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.task-list-title { font-size: 14px; font-weight: 600; color: #334155; }
.task-list-hint { font-size: 11px; color: #94a3b8; }
.task-empty {
  padding: 28px;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
  background: #fff;
  border: 1px dashed #e2e8f0;
  border-radius: 10px;
}

/* 任务卡片 */
.task-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.task-card:hover {
  border-color: #c7d2fe;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}
.task-card.running { border-left: 3px solid #3b82f6; }
.task-card.completed { border-left: 3px solid #10b981; }
.task-card.failed { border-left: 3px solid #ef4444; }
.task-card.cancelled { border-left: 3px solid #9ca3af; opacity: 0.7; }
.task-card.pending { border-left: 3px solid #f59e0b; }

.task-card-main {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
}
.task-type-badge {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 6px;
}
.task-type-badge.pse { background: #ede9fe; color: #6d28d9; }
.task-type-badge.agent { background: #dbeafe; color: #1d4ed8; }
.task-type-badge.chat { background: #d1fae5; color: #047857; }

.task-info { flex: 1; min-width: 0; }
.task-message {
  font-size: 13px;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-meta {
  display: flex;
  gap: 10px;
  margin-top: 4px;
  font-size: 11px;
  color: #94a3b8;
  flex-wrap: wrap;
}
.task-progress {
  margin-top: 4px;
  font-size: 12px;
  color: #3b82f6;
}
.task-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.task-tokens {
  font-size: 11px;
  color: #a16207;
  background: #fef3c7;
  border-radius: 8px;
  padding: 2px 8px;
  white-space: nowrap;
}
.task-status-badge {
  font-size: 11px;
  font-weight: 500;
  padding: 3px 9px;
  border-radius: 10px;
}
.task-status-badge.pending { background: #fef3c7; color: #b45309; }
.task-status-badge.running { background: #dbeafe; color: #1d4ed8; }
.task-status-badge.running::before {
  content: '';
  display: inline-block;
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #3b82f6;
  margin-right: 5px;
  animation: blink 1s infinite;
}
@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }
.task-status-badge.completed { background: #d1fae5; color: #047857; }
.task-status-badge.failed { background: #fee2e2; color: #b91c1c; }
.task-status-badge.cancelled { background: #f3f4f6; color: #6b7280; }

.task-actions { display: flex; gap: 4px; }
.task-cancel-btn, .task-delete-btn, .task-restart-btn {
  width: 24px; height: 24px;
  display: flex; align-items: center; justify-content: center;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
  color: #94a3b8;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.task-cancel-btn:hover { background: #fee2e2; color: #dc2626; border-color: #fecaca; }
.task-restart-btn:hover { background: #dbeafe; color: #1d4ed8; border-color: #bfdbfe; }
.task-delete-btn:hover { background: #f3f4f6; color: #6b7280; }

/* ===== 详情 ===== */
.task-detail {
  border-top: 1px solid #f1f5f9;
  padding: 12px 14px;
  background: #fafbfc;
  border-radius: 0 0 10px 10px;
}

/* 详情顶部工具条 */
.task-detail-tools {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 10px;
}
.copy-detail-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 5px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
  color: #475569;
  cursor: pointer;
  transition: all 0.15s;
}
.copy-detail-btn:hover {
  border-color: #4a6cf7;
  color: #1d4ed8;
  background: #eef2ff;
}
.copy-detail-btn.copied {
  border-color: #10b981;
  color: #047857;
  background: #ecfdf5;
}
.detail-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 5px;
}
.task-error pre {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  margin-bottom: 10px;
}
.task-error pre { color: #b91c1c; background: #fef2f2; border-color: #fecaca; }

/* ===== 结果 Markdown 内容（全铺开，无单独滚动条） ===== */
.task-result-content {
  font-size: 13px;
  line-height: 1.7;
  color: #334155;
  word-break: break-word;
}
.task-result-content :deep(p) { margin: 6px 0; }
.task-result-content :deep(h1),
.task-result-content :deep(h2),
.task-result-content :deep(h3),
.task-result-content :deep(h4) {
  margin: 12px 0 6px;
  font-weight: 600;
  color: #1e293b;
  line-height: 1.4;
}
.task-result-content :deep(h1) { font-size: 18px; }
.task-result-content :deep(h2) { font-size: 16px; }
.task-result-content :deep(h3) { font-size: 14px; }
.task-result-content :deep(ul),
.task-result-content :deep(ol) {
  margin: 6px 0;
  padding-left: 22px;
}
.task-result-content :deep(li) { margin: 3px 0; }
.task-result-content :deep(code) {
  background: #f1f5f9;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12px;
  font-family: 'SF Mono', 'Menlo', monospace;
  color: #be185d;
}
.task-result-content :deep(pre) {
  margin: 0;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.task-result-content :deep(pre code) {
  background: transparent;
  padding: 0;
  color: #1e293b;
}
.task-result-content :deep(blockquote) {
  border-left: 3px solid #4a6cf7;
  padding-left: 12px;
  margin: 8px 0;
  color: #6b7280;
}
.task-result-content :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
  width: 100%;
}
.task-result-content :deep(th),
.task-result-content :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 6px 10px;
  text-align: left;
}
.task-result-content :deep(th) {
  background: #f8fafc;
  font-weight: 600;
}
.task-result-content :deep(hr) {
  border: none;
  border-top: 1px solid #e2e8f0;
  margin: 12px 0;
}
/* 代码块包装（语言标签 + 复制按钮） */
.task-result-content :deep(.code-block-wrapper) {
  position: relative;
  margin: 10px 0;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.task-result-content :deep(.code-block-wrapper pre) {
  margin: 0;
  border-radius: 0;
  padding-top: 12px;
}
.task-result-content :deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #16233c;
  padding: 4px 10px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.task-result-content :deep(.code-block-lang) {
  font-size: 10px;
  font-weight: 500;
  color: #8b9cb8;
  font-family: 'SF Mono', 'Menlo', monospace;
  letter-spacing: 0.3px;
}
.task-result-content :deep(.code-copy-btn) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 6px;
  background: transparent;
  color: #7d8fa8;
  border: 1px solid transparent;
  border-radius: 4px;
  font-size: 10px;
  line-height: 1.4;
  cursor: pointer;
  transition: all 0.2s ease;
  opacity: 0.75;
}
.task-result-content :deep(.code-block-header:hover .code-copy-btn) {
  opacity: 1;
  background: rgba(255,255,255,0.08);
  color: #cbd5e1;
  border-color: rgba(255,255,255,0.12);
}
.task-result-content :deep(.code-copy-btn.copied) {
  background: rgba(16,185,129,0.15);
  color: #34d399;
  border-color: rgba(16,185,129,0.25);
  opacity: 1;
}
.task-steps { margin-top: 4px; }
/* 执行过程：全铺开展示，不做高度限制与内部滚动 */
.step-list { }
.step-item {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 6px;
  padding: 6px 8px;
  margin-bottom: 4px;
  background: #fff;
  border: 1px solid #f1f5f9;
  border-radius: 6px;
  font-size: 12px;
}
.step-seq {
  flex-shrink: 0;
  width: 18px; height: 18px;
  display: flex; align-items: center; justify-content: center;
  background: #eef2ff; color: #4a6cf7;
  border-radius: 50%;
  font-size: 10px;
  font-weight: 600;
}
.step-type {
  flex-shrink: 0;
  font-weight: 600;
  color: #4a6cf7;
}
.step-title { color: #334155; }
.step-content {
  width: 100%;
  margin-top: 4px;
  font-size: 11px;
  color: #64748b;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f8fafc;
  border-radius: 4px;
  padding: 4px 6px;
}
.step-tool {
  width: 100%;
  margin-top: 4px;
  background: #f0f9ff;
  border-radius: 4px;
  padding: 4px 6px;
}
.tool-name { font-weight: 600; color: #0369a1; font-size: 11px; }
.tool-io {
  font-size: 11px;
  color: #475569;
  white-space: pre-wrap;
  word-break: break-word;
  margin-top: 2px;
}

/* ===== 执行日志 ===== */
.task-log-bar {
  margin-top: 10px;
}
.task-log-btn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
  font-size: 12px;
  color: #475569;
  cursor: pointer;
  transition: all 0.15s;
}
.task-log-btn:hover {
  border-color: #4a6cf7;
  background: #eef2ff;
  color: #1d4ed8;
}
.log-toggle { font-size: 11px; color: #64748b; }
.task-log-btn:hover .log-toggle { color: #1d4ed8; }
.task-logs { margin-top: 8px; }
/* 执行日志：全铺开展示，随主内容一起滚动 */
.log-list {
  background: #0f172a;
  border-radius: 6px;
  padding: 8px 10px;
}
.log-line {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 11px;
  color: #94a3b8;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.log-line::before {
  content: '› ';
  color: #4a6cf7;
}
</style>
