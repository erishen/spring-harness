<template>
  <div class="longtask-panel">
    <!-- 任务提交表单 -->
    <TaskForm
      v-model:task-type="taskType"
      v-model:task-model="taskModel"
      v-model:task-message="taskMessage"
      :submitting="submitting"
      @submit="submitTask"
    />

    <!-- 示例任务 -->
    <TaskExamples
      :examples="examples"
      :loading="loadingExamples"
      @select="fillExample"
      @refresh="() => loadExamples(true)"
    />

    <!-- 任务列表 -->
    <div class="task-list">
      <div class="task-list-header">
        <span class="task-list-title">📋 长时任务（{{ tasks.length }}）</span>
        <span class="task-list-hint">每 3 秒自动刷新</span>
      </div>

      <div v-if="tasks.length === 0" class="task-empty">
        暂无任务，提交一个长时任务开始吧
      </div>

      <div
        v-for="task in tasks"
        :key="task.id"
        class="task-card"
        :class="task.status"
        @click="toggleDetail(task.id)"
      >
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
              🔢 {{ task.tokenUsage.totalTokens }}
            </span>
            <div class="task-actions" @click.stop>
              <button
                v-if="task.status === 'running' || task.status === 'pending'"
                class="task-cancel-btn"
                @click="cancelTask(task.id)"
                title="取消任务"
              >取消</button>
              <button
                v-if="task.terminal"
                class="task-restart-btn"
                @click="restartTask(task.id)"
                title="重新执行"
              >重跑</button>
              <button
                v-if="task.terminal"
                class="task-delete-btn"
                @click="deleteTask(task.id)"
                title="删除任务"
              >删除</button>
            </div>
          </div>
        </div>

        <!-- 任务详情（子组件） -->
        <TaskDetail
          v-if="detailId === task.id"
          :task="task"
          :copy-done="copyDone"
          :log-open-id="logOpenId"
          @copy-detail="copyTaskDetail"
          @toggle-log="toggleLog"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getExamples } from '../services/api.js'
import TaskForm from './TaskForm.vue'
import TaskExamples from './TaskExamples.vue'
import TaskDetail from './TaskDetail.vue'

const tasks = ref([])
const taskType = ref('pse')
const taskMessage = ref('')
const taskModel = ref('')
const submitting = ref(false)
const detailId = ref('')
const logOpenId = ref('')
const copyDone = ref('')
let timer = null

// 示例任务
const examples = ref([])
const loadingExamples = ref(false)

async function loadExamples(withLlm = false) {
  loadingExamples.value = true
  try {
    const data = await getExamples('longtask', withLlm)
    examples.value = (data.examples || []).map(e => ({
      type: e.type,
      typeLabel: e.typeLabel || (e.type === 'pse' ? 'PSE' : 'ReAct'),
      title: e.title,
      tags: e.tags || []
    }))
  } catch (err) {
    console.error('加载示例任务失败:', err)
    examples.value = []
  } finally {
    loadingExamples.value = false
  }
}
onMounted(loadExamples)

function fillExample(ex) {
  taskType.value = ex.type
  taskMessage.value = ex.title
}

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
    if (res.ok) tasks.value = await res.json()
  } catch (e) { /* 忽略轮询错误 */ }
}

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
  if (res.ok) await fetchTasks()
}

async function deleteTask(id) {
  await fetch(`/api/tasks/${id}`, { method: 'DELETE' })
  if (detailId.value === id) detailId.value = ''
  await fetchTasks()
}

function toggleDetail(id) {
  detailId.value = detailId.value === id ? '' : id
  if (detailId.value !== id) logOpenId.value = ''
}

function toggleLog(id) {
  logOpenId.value = logOpenId.value === id ? '' : id
}

// 截断过长字段
function truncate(s, max) {
  if (!s) return ''
  const str = typeof s === 'string' ? s : JSON.stringify(s)
  return str.length > max ? str.slice(0, max) + '…（截断）' : str
}

// 格式化任务执行过程（正确步骤简写，错误/工具调用详细）
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
    const stepText = [type, step.step?.status, title, step.content, step.step?.content].filter(Boolean).join(' ')
    const isError = step.type === 'error' || step.step?.status === 'FAIL' || step.step?.failed || /FAIL|失败|未通过|❌|异常/.test(stepText)
    lines.push(`${isError ? '✗' : '✓'} 步骤 ${i + 1}: [${type}] ${title}`)
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

async function copyTaskDetail(task) {
  const text = formatTaskDetail(task)
  try {
    await navigator.clipboard.writeText(text)
  } catch {
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
  setTimeout(() => { if (copyDone.value === task.id) copyDone.value = '' }, 1500)
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
  height: 100%;
  padding: 12px;
  overflow-y: auto;
  background: #f5f6f8;
}
.longtask-panel::-webkit-scrollbar { width: 6px; }
.longtask-panel::-webkit-scrollbar-thumb { background: transparent; border-radius: 3px; }
.longtask-panel:hover::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.15); }

/* 任务列表 */
.task-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.task-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 2px 8px;
}
.task-list-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}
.task-list-hint {
  font-size: 10px;
  color: #9ca3af;
}
.task-empty {
  text-align: center;
  padding: 40px 20px;
  color: #9ca3af;
  font-size: 13px;
  background: #fff;
  border: 1px dashed #e5e7eb;
  border-radius: 10px;
}

/* 任务卡片 */
.task-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.15s;
}
.task-card:hover { border-color: #bfdbfe; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
.task-card.running { border-left: 3px solid #3b82f6; }
.task-card.completed { border-left: 3px solid #10b981; }
.task-card.failed { border-left: 3px solid #ef4444; }
.task-card.cancelled { border-left: 3px solid #9ca3af; }
.task-card.pending { border-left: 3px solid #f59e0b; }

.task-card-main {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
}
.task-type-badge {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  margin-top: 1px;
}
.task-type-badge.pse { background: #ecfdf5; color: #059669; }
.task-type-badge.agent { background: #eff6ff; color: #2563eb; }
.task-type-badge.chat { background: #f3f4f6; color: #6b7280; }

.task-info {
  flex: 1;
  min-width: 0;
}
.task-message {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.task-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
  font-size: 10.5px;
  color: #9ca3af;
}
.task-progress {
  margin-top: 4px;
  font-size: 11px;
  color: #3b82f6;
  font-style: italic;
}

.task-right {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}
.task-status-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
}
.task-status-badge.pending { background: #fef3c7; color: #b45309; }
.task-status-badge.running { background: #dbeafe; color: #1d4ed8; }
.task-status-badge.completed { background: #d1fae5; color: #047857; }
.task-status-badge.failed { background: #fee2e2; color: #b91c1c; }
.task-status-badge.cancelled { background: #f3f4f6; color: #6b7280; }
.task-tokens {
  font-size: 10px;
  color: #6b7280;
}
.task-actions {
  display: flex;
  gap: 4px;
}
.task-cancel-btn,
.task-restart-btn,
.task-delete-btn {
  font-size: 10px;
  padding: 2px 6px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.task-cancel-btn:hover { border-color: #ef4444; color: #ef4444; }
.task-restart-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.task-delete-btn:hover { border-color: #ef4444; color: #ef4444; }
</style>
