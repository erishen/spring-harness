<template>
  <div class="app">
    <!-- 左侧会话列表 -->
    <aside class="sidebar">
      <SessionList
        :sessions="sessions"
        :currentSessionId="currentSessionId"
        @create="createSession"
        @switch="switchSession"
        @delete="deleteSession"
      />
    </aside>

    <!-- 主内容区 -->
    <div class="main-content-wrapper">
    <header>
      <div class="logo">AI</div>
      <h1>Spring AI Alibaba 聊天演示</h1>
      <span class="model">DashScope · Vue 3</span>
      <div class="header-actions">
        <button class="header-btn" @click="exportChat" title="导出聊天记录为 Markdown">导出</button>
        <button class="header-btn danger" @click="clearChat" title="清空当前会话">清空</button>
      </div>
    </header>

    <!-- 模式切换 -->
    <div class="mode-switch">
      <div class="mode-row">
        <div class="mode-buttons">
          <button :class="{ active: mode === 'chat' }" @click="mode = 'chat'">普通对话</button>
          <button :class="{ active: mode === 'rag' }" @click="mode = 'rag'">RAG 知识库</button>
          <button :class="{ active: mode === 'agent' }" @click="mode = 'agent'">ReAct Agent</button>
          <button :class="{ active: mode === 'pse' }" @click="mode = 'pse'">PSE 协作</button>
          <button :class="{ active: mode === 'task' }" @click="mode = 'task'">长时任务</button>
        </div>
        <select v-model="selectedModel" class="model-select" title="选择模型">
          <option value="">默认 (glm-5.2)</option>
          <optgroup label="✅ 可直接使用">
            <option v-for="m in workingModels" :key="m.id" :value="m.id" :title="m.description">
              {{ m.id }}（{{ m.vendor }}）
            </option>
          </optgroup>
          <optgroup label="⚠️ 暂有URL兼容问题">
            <option v-for="m in pendingModels" :key="m.id" :value="m.id" :title="m.description">
              {{ m.id }}（{{ m.vendor }}）
            </option>
          </optgroup>
        </select>
      </div>
      <div v-if="modeHint" class="mode-hint">{{ modeHint }}</div>
    </div>

    <!-- RAG 文档管理面板（仅 RAG 模式显示） -->
    <RagPanel v-if="mode === 'rag'" />

    <!-- 模型参数设置（仅普通对话模式显示） -->
    <ModelSettings v-if="mode === 'chat'" v-model="modelSettings" />

    <div class="content-wrapper">
    <!-- 长时任务模式：后台异步任务管理面板 -->
    <LongTaskPanel v-if="mode === 'task'" />

    <div v-else class="main-content">
    <MessageList :messages="messages" ref="messageListRef" @regenerate="regenerate" />

    <InputArea
      v-model="input"
      :placeholder="placeholder"
      :loading="loading"
      :hint="hintText"
      :mode="mode"
      @send="sendMessage"
      @stop="stopGeneration"
      ref="inputAreaRef"
    />
    </div>

    <!-- 右侧 Runtime 面板：工具列表 + MCP 状态 -->
    <RuntimePanel />
    </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import RuntimePanel from './components/RuntimePanel.vue'
import RagPanel from './components/RagPanel.vue'
import InputArea from './components/InputArea.vue'
import MessageList from './components/MessageList.vue'
import ModelSettings from './components/ModelSettings.vue'
import SessionList from './components/SessionList.vue'
import LongTaskPanel from './components/LongTaskPanel.vue'
import { useSessions } from './composables/useSessions.js'

// 多会话管理
const {
  sessions,
  currentSessionId,
  currentMessages,
  createSession,
  switchSession,
  deleteSession,
  clearCurrentSession
} = useSessions()

// 为了兼容现有代码，用 messages 引用 currentMessages
const messages = currentMessages

// localStorage 键名
const STORAGE_MODEL_KEY = 'spring-harness-selected-model'
const STORAGE_MODE_KEY = 'spring-harness-mode'

const mode = ref(localStorage.getItem(STORAGE_MODE_KEY) || 'chat')
const input = ref('')
const loading = ref(false)
const messageListRef = ref(null)
const inputAreaRef = ref(null)

// 停止生成：AbortController
let abortController = null
// 最后一条用户消息（用于重新生成）
const lastUserMessage = ref('')

// 模型选择
const models = ref([])
const selectedModel = ref(localStorage.getItem(STORAGE_MODEL_KEY) || 'agnes-2.0-flash')
const workingModels = computed(() => models.value.filter(m => !m.description.includes('暂需')))
const pendingModels = computed(() => models.value.filter(m => m.description.includes('暂需')))

// 模型参数设置（temperature、max_tokens、top_p、systemPrompt）
const modelSettings = ref({
  temperature: 0.7,
  maxTokens: 4096,
  topP: 1.0,
  systemPrompt: ''
})

// 监听模型和模式变化，保存到 localStorage
watch(selectedModel, (val) => {
  if (val) localStorage.setItem(STORAGE_MODEL_KEY, val)
})
watch(mode, (val) => {
  localStorage.setItem(STORAGE_MODE_KEY, val)
})

async function loadModels() {
  try {
    const res = await fetch('/models')
    if (res.ok) {
      models.value = await res.json()
    }
  } catch (e) {
    console.error('加载模型列表失败', e)
  }
}

const placeholder = computed(() => {
  if (mode.value === 'agent') return 'ReAct Agent：试试「现在几点？然后算123×456」「查苹果股价再算买100股多少钱」'
  if (mode.value === 'pse') return 'PSE 多 Agent：试试「帮我调研苹果公司并分析投资价值」「计算今天买100股特斯拉需要多少钱」'
  if (mode.value === 'rag') return 'RAG 模式：基于上传的文档提问，如「Spring AI 支持哪些向量库」'
  return '输入消息，Enter 发送，Shift+Enter 换行...'
})

// 导出聊天记录为 Markdown
function exportChat() {
  if (messages.value.length === 0) {
    alert('没有可导出的聊天记录')
    return
  }

  const now = new Date()
  const dateStr = now.toISOString().slice(0, 10)
  const timeStr = now.toTimeString().slice(0, 8).replace(/:/g, '-')

  let markdown = `# 聊天记录导出\n\n`
  markdown += `> 导出时间：${now.toLocaleString('zh-CN')}\n`
  markdown += `> 消息数量：${messages.value.length}\n\n`
  markdown += `---\n\n`

  for (const msg of messages.value) {
    const role = msg.role === 'user' ? '🧑 用户' : '🤖 AI'
    markdown += `## ${role}\n\n`
    if (msg.content) {
      markdown += `${msg.content}\n\n`
    }
    // Token 用量
    if (msg.tokenUsage) {
      markdown += `> Token: 输入 ${msg.tokenUsage.promptTokens} / 输出 ${msg.tokenUsage.completionTokens} / 总计 ${msg.tokenUsage.totalTokens}`
      if (msg.llmCallCount) {
        markdown += ` | LLM 调用 ${msg.llmCallCount} 次`
      }
      markdown += `\n\n`
    }
    markdown += `---\n\n`
  }

  // 下载文件
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `chat-export-${dateStr}-${timeStr}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// 清空聊天记录
function clearChat() {
  if (messages.value.length === 0) return
  if (!confirm('确定要清空当前会话的聊天记录吗？此操作不可撤销。')) return
  clearCurrentSession()
}

const hintText = computed(() => {
  if (mode.value === 'agent') return 'ReAct 模式 · 多轮推理+工具调用 · 思考→行动→观察→再思考'
  if (mode.value === 'pse') return 'PSE 模式 · 三角色协作 · Planner 规划→Specialist 执行→Evaluator 独立评审'
  if (mode.value === 'rag') return 'RAG 模式 · 检索增强生成 · 向量检索 + Rerank 重排序 + LLM 生成'
  if (mode.value === 'task') return '长时任务 · 后台异步执行 · 不受 HTTP/SSE 超时限制'
  return '流式输出 · Vite + Vue 3 · Spring AI ChatClient'
})

const modeHint = computed(() => {
  if (mode.value === 'agent') return 'ReAct 多轮推理：思考→调用工具→观察→再思考，支持复杂任务多步分解'
  if (mode.value === 'pse') return 'PSE 三角色协作：Planner 规划分解 → Specialist 执行 → Evaluator 独立评审'
  if (mode.value === 'rag') return '上传文档后，基于知识库内容回答问题'
  if (mode.value === 'task') return '提交 PSE / ReAct / 对话任务到后台执行，3 秒自动刷新状态，长任务不阻塞页面'
  return ''
})

onMounted(() => {
  inputAreaRef.value?.focus()
  loadModels()
})

// ==================== 停止生成 ====================

/** 停止当前生成 */
function stopGeneration() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  loading.value = false
}

// ==================== 重新生成 ====================

/** 重新生成上一条回答 */
function regenerate() {
  if (loading.value || !lastUserMessage.value) return
  // 移除最后一条 AI 消息
  if (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'ai') {
    messages.value.pop()
  }
  // 重新发送
  input.value = lastUserMessage.value
  sendMessage()
}

// ==================== 构建历史消息参数 ====================

/** 构建历史消息 JSON 字符串（用于多轮对话上下文） */
function historyParam() {
  // 只取最近的 10 条消息（不含当前正在生成的），避免 token 过多
  const history = messages.value.slice(-11, -1)
  if (history.length === 0) return ''
  const simplified = history.map(msg => ({
    role: msg.role === 'ai' ? 'assistant' : 'user',
    content: msg.content || ''
  })).filter(m => m.content)
  return '&messages=' + encodeURIComponent(JSON.stringify(simplified))
}

function scrollToBottom() {
  messageListRef.value?.scrollToBottom()
}

// ==================== 发送消息 ====================

// 构建 model 查询参数（选中模型时附加到 URL）
function modelParam() {
  let params = selectedModel.value ? '&model=' + encodeURIComponent(selectedModel.value) : ''
  // 添加模型参数（仅普通对话模式）
  if (mode.value === 'chat' && modelSettings.value) {
    params += '&temperature=' + modelSettings.value.temperature
    params += '&maxTokens=' + modelSettings.value.maxTokens
    params += '&topP=' + modelSettings.value.topP
    if (modelSettings.value.systemPrompt && modelSettings.value.systemPrompt.trim()) {
      params += '&systemPrompt=' + encodeURIComponent(modelSettings.value.systemPrompt)
    }
  }
  return params
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text || loading.value) return

  // 创建新的 AbortController
  abortController = new AbortController()
  lastUserMessage.value = text

  loading.value = true
  messages.value.push({ role: 'user', content: text })
  input.value = ''

  let aiMsg = {
    role: 'ai',
    content: '',
    error: false,
    toolCalls: [],
    // ReAct Agent 流式状态
    currentThought: '',
    currentToolCalls: [],
    iterations: 0,
    streaming: false,
    // PSE 协作状态
    pseSteps: [],
    pseTasks: [],
    pseStatus: '',
    // Token 统计
    tokenUsage: null,
    llmCallCount: 0
  }
  messages.value.push(aiMsg)
  // 关键：push 到响应式数组后，重新获取 Proxy 引用，否则修改原始对象不会触发视图更新
  aiMsg = messages.value[messages.value.length - 1]
  scrollToBottom()

  try {
    if (mode.value === 'agent') {
      await sendAgent(text, aiMsg)
    } else if (mode.value === 'pse') {
      await sendPse(text, aiMsg)
    } else if (mode.value === 'rag') {
      await sendRag(text, aiMsg)
    } else {
      await sendStream(text, aiMsg)
    }
    if (!aiMsg.content && !aiMsg.error) {
      aiMsg.content = '(无返回内容)'
    }
  } catch (err) {
    if (err.name === 'AbortError') {
      // 用户主动停止
      aiMsg.content = aiMsg.content || '(已停止生成)'
    } else {
      aiMsg.error = true
      if (err.message === 'Failed to fetch' || err.name === 'TypeError') {
        aiMsg.content = '无法连接到后端服务（端口 8080）。\n请确认后端已启动：运行 make dev 可同时启动前后端。'
      } else {
        aiMsg.content = '请求失败：' + err.message
      }
    }
  } finally {
    loading.value = false
    abortController = null
    inputAreaRef.value?.focus()
  }
}

// RAG 模式：非流式，基于知识库检索增强
async function sendRag(text, aiMsg) {
  const response = await fetch('/chat/rag?message=' + encodeURIComponent(text) + modelParam(), { 
    method: 'GET',
    signal: abortController?.signal
  })
  if (!response.ok) {
    const errText = await response.text()
    throw new Error('HTTP ' + response.status + ' - ' + errText.substring(0, 200))
  }
  const data = await response.json()
  aiMsg.content = data.answer || ''
  scrollToBottom()
}

// ReAct Agent 模式：SSE 流式输出，实时展示思考过程和工具调用
async function sendAgent(text, aiMsg) {
  aiMsg.reactSteps = []
  aiMsg.iterations = 0
  aiMsg.currentThought = ''
  aiMsg.currentToolCalls = []

  const response = await fetch('/chat/agent/react/stream?message=' + encodeURIComponent(text) + modelParam(), {
    method: 'GET',
    headers: { Accept: 'text/event-stream' },
    signal: abortController?.signal
  })
  if (!response.ok) {
    const errText = await response.text()
    throw new Error('HTTP ' + response.status + ' - ' + errText.substring(0, 200))
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finalAnswer = ''
  let allSteps = []

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''

    for (const event of events) {
      const lines = event.split('\n')
      const dataLines = []
      for (const line of lines) {
        if (line.startsWith('data:')) {
          let token = line.slice(5)
          if (token.startsWith(' ')) token = token.slice(1)
          dataLines.push(token)
        }
      }
      if (dataLines.length === 0) continue

      try {
        const data = JSON.parse(dataLines.join('\n'))
        const type = data.type

        if (type === 'thinking') {
          if (data.content) {
            aiMsg.currentThought = data.content
          }
          aiMsg.iterations = data.iteration
        } else if (type === 'tool_call') {
          if (data.toolCall && data.toolCall.name) {
            aiMsg.currentToolCalls.push({
              name: data.toolCall.name,
              input: data.toolCall.input,
              output: null,
              durationMs: 0,
              status: 'running'
            })
          }
        } else if (type === 'tool_result') {
          if (data.toolCall && data.toolCall.name) {
            const lastCall = aiMsg.currentToolCalls[aiMsg.currentToolCalls.length - 1]
            if (lastCall && lastCall.name === data.toolCall.name && lastCall.status === 'running') {
              lastCall.output = data.toolCall.output
              lastCall.durationMs = data.toolCall.durationMs
              lastCall.status = 'done'
            }
          }
        } else if (type === 'step') {
          if (data.step) {
            allSteps.push(data.step)
            aiMsg.reactSteps = [...allSteps]
          }
          aiMsg.currentThought = ''
          aiMsg.currentToolCalls = []
        } else if (type === 'answer') {
          finalAnswer = data.content || ''
          aiMsg.content = finalAnswer
          // 最终回答只作为内容展示，不放入步骤列表（避免重复）
        } else if (type === 'done') {
          aiMsg.iterations = allSteps.length
        } else if (type === 'error') {
          throw new Error(data.content || 'Agent 执行出错')
        }
      } catch (e) {
        console.error('解析 SSE 事件失败', e, dataLines.join('\n'))
      }
    }
    scrollToBottom()
  }

  if (!finalAnswer && allSteps.length > 0) {
    const lastStep = allSteps[allSteps.length - 1]
    finalAnswer = lastStep.thought || ''
    aiMsg.content = finalAnswer
  }
  aiMsg.reactSteps = allSteps
  aiMsg.iterations = allSteps.length
  scrollToBottom()
}

// PSE 多 Agent 协作模式：SSE 流式输出，实时展示 Planner规划/Specialist执行/Evaluator评审
async function sendPse(text, aiMsg) {
  aiMsg.pseSteps = []
  aiMsg.pseTasks = []
  aiMsg.pseStatus = 'running'

  const response = await fetch('/chat/pse/stream?message=' + encodeURIComponent(text) + modelParam(), {
    method: 'GET',
    headers: { Accept: 'text/event-stream' },
    signal: abortController?.signal
  })
  if (!response.ok) {
    const errText = await response.text()
    throw new Error('HTTP ' + response.status + ' - ' + errText.substring(0, 200))
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finalAnswer = ''
  let allSteps = []

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''

    for (const event of events) {
      const lines = event.split('\n')
      const dataLines = []
      for (const line of lines) {
        if (line.startsWith('data:')) {
          let token = line.slice(5)
          if (token.startsWith(' ')) token = token.slice(1)
          dataLines.push(token)
        }
      }
      if (dataLines.length === 0) continue

      try {
        const data = JSON.parse(dataLines.join('\n'))
        const type = data.type

        if (type === 'step' && data.step) {
          allSteps.push(data.step)
          aiMsg.pseSteps = [...allSteps]
        } else if (type === 'answer') {
          finalAnswer = data.content || ''
          aiMsg.content = finalAnswer
        } else if (type === 'done') {
          aiMsg.pseStatus = 'completed'
        } else if (type === 'error') {
          aiMsg.pseStatus = 'failed'
          aiMsg.error = true
          aiMsg.content = data.content || 'PSE 执行出错'
          throw new Error(data.content || 'PSE 执行出错')
        }
      } catch (e) {
        if (e.message && e.message.includes('PSE')) throw e
        console.error('解析 SSE 事件失败', e, dataLines.join('\n'))
      }
    }
    scrollToBottom()
  }

  if (!finalAnswer && allSteps.length > 0) {
    const lastStep = allSteps[allSteps.length - 1]
    finalAnswer = lastStep.content || lastStep.title || ''
    aiMsg.content = finalAnswer
  }
  aiMsg.pseSteps = allSteps
  aiMsg.pseStatus = aiMsg.pseStatus || 'completed'
  scrollToBottom()
}

// 普通对话模式：SSE 流式输出
async function sendStream(text, aiMsg) {
  const response = await fetch('/chat/stream?message=' + encodeURIComponent(text) + modelParam() + historyParam(), {
    method: 'GET',
    headers: { Accept: 'text/event-stream' },
    signal: abortController?.signal
  })
  if (!response.ok) {
    const errText = await response.text()
    throw new Error('HTTP ' + response.status + ' - ' + errText.substring(0, 200))
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // SSE 事件以 \n\n 分隔，每个事件内可能有多个 data: 行
    // 正确解析：按 \n\n 分割事件，收集每个事件的所有 data: 行
    const events = buffer.split('\n\n')
    buffer = events.pop() || '' // 最后一个事件可能不完整，保留到 buffer

    for (const event of events) {
      // SSE 规范：每个事件内可能有多个 data: 行，多个 data 行的值用 \n 连接
      const lines = event.split('\n')
      const dataLines = []
      for (const line of lines) {
        if (line.startsWith('data:')) {
          let token = line.slice(5)
          // 去掉行首的一个空格（SSE 规范：data: 后面有一个空格）
          if (token.startsWith(' ')) token = token.slice(1)
          dataLines.push(token)
        }
      }
      if (dataLines.length > 0) {
        let eventData = dataLines.join('\n')
        // 处理 JSON 字符串转义
        if (eventData.startsWith('"') && eventData.endsWith('"')) {
          eventData = eventData.slice(1, -1)
        }
        eventData = eventData.replace(/\\n/g, '\n').replace(/\\"/g, '"').replace(/\\\\/g, '\\')
        aiMsg.content += eventData
        scrollToBottom()
      }
    }
  }
}
</script>

<style scoped>
.app { display: flex; flex-direction: row; height: 100vh; overflow: hidden; }

/* 左侧会话列表 */
.sidebar {
  width: 260px;
  flex-shrink: 0;
  height: 100%;
  overflow: hidden;
}

/* 主内容区 */
.main-content-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

header {
  background: #fff; border-bottom: 1px solid #e4e7ed; padding: 16px 24px;
  display: flex; align-items: center; gap: 12px;
}
.logo {
  width: 36px; height: 36px; background: linear-gradient(135deg, #6b8cff, #4a6cf7);
  border-radius: 8px; display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700; font-size: 14px;
}
header h1 { font-size: 16px; color: #1f2937; }
.model { font-size: 12px; color: #9ca3af; }
.header-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.header-btn {
  font-size: 12px;
  padding: 5px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  color: #4b5563;
  cursor: pointer;
  transition: all 0.2s;
}
.header-btn:hover {
  background: #f3f4f6;
  border-color: #d1d5db;
  color: #1f2937;
}
.header-btn.danger {
  color: #ef4444;
  border-color: #fecaca;
}
.header-btn.danger:hover {
  background: #fef2f2;
  border-color: #fca5a5;
}

/* 模式切换 */
.mode-switch {
  background: #f8f9fb;
  border-bottom: 1px solid #e4e7ed;
  padding: 10px 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
}
.mode-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.mode-buttons {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.mode-switch button {
  background: #fff;
  color: #6b7280;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 6px 14px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}
.mode-switch button:hover { border-color: #4a6cf7; color: #4a6cf7; }
.mode-switch button.active { background: #4a6cf7; color: #fff; border-color: #4a6cf7; }
.mode-hint {
  font-size: 12px;
  color: #9ca3af;
  padding-left: 2px;
}
.model-select {
  margin-left: auto;
  padding: 6px 10px;
  font-size: 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  color: #1f2937;
  cursor: pointer;
  outline: none;
  max-width: 280px;
  flex-shrink: 0;
}
.model-select:focus { border-color: #4a6cf7; }
.model-select optgroup { font-weight: 600; color: #6b7280; }

/* ---- 内容区水平布局：主内容 + 右侧 Runtime 面板 ---- */
.content-wrapper {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
}

</style>

<!-- ============================================================
     滚动条策略 B（全局，非 scoped）：hover 才显示
     所有可滚动容器默认隐藏滚动条（轨道常驻透明、不占布局抖动），
     鼠标悬停到该容器时显示灰色细滚动条。
     主聊天区 / 长时面板 / 会话 / 右侧面板 / 步骤日志 / 代码块 统一。
     ============================================================ -->
<style>
.chat-container,
.sessions,
.runtime-panel,
.longtask-panel,
.longtask-panel .step-list,
.longtask-panel .log-list,
.longtask-panel .task-message-input,
.code-block-wrapper pre {
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  -ms-overflow-style: none;
}
.chat-container::-webkit-scrollbar,
.sessions::-webkit-scrollbar,
.runtime-panel::-webkit-scrollbar,
.longtask-panel::-webkit-scrollbar,
.longtask-panel .step-list::-webkit-scrollbar,
.longtask-panel .log-list::-webkit-scrollbar,
.longtask-panel .task-message-input::-webkit-scrollbar,
.code-block-wrapper pre::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.chat-container::-webkit-scrollbar-thumb,
.sessions::-webkit-scrollbar-thumb,
.runtime-panel::-webkit-scrollbar-thumb,
.longtask-panel::-webkit-scrollbar-thumb,
.longtask-panel .step-list::-webkit-scrollbar-thumb,
.longtask-panel .log-list::-webkit-scrollbar-thumb,
.longtask-panel .task-message-input::-webkit-scrollbar-thumb,
.code-block-wrapper pre::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 3px;
}
.chat-container:hover,
.chat-container:active,
.sessions:hover,
.sessions:active,
.runtime-panel:hover,
.runtime-panel:active,
.longtask-panel:hover,
.longtask-panel:active,
.longtask-panel .step-list:hover,
.longtask-panel .step-list:active,
.longtask-panel .log-list:hover,
.longtask-panel .log-list:active,
.longtask-panel .task-message-input:hover,
.longtask-panel .task-message-input:active,
.code-block-wrapper pre:hover,
.code-block-wrapper pre:active {
  scrollbar-color: #d1d5db transparent;
}
.chat-container:hover::-webkit-scrollbar-thumb,
.chat-container:active::-webkit-scrollbar-thumb,
.sessions:hover::-webkit-scrollbar-thumb,
.sessions:active::-webkit-scrollbar-thumb,
.runtime-panel:hover::-webkit-scrollbar-thumb,
.runtime-panel:active::-webkit-scrollbar-thumb,
.longtask-panel:hover::-webkit-scrollbar-thumb,
.longtask-panel:active::-webkit-scrollbar-thumb,
.longtask-panel .step-list:hover::-webkit-scrollbar-thumb,
.longtask-panel .step-list:active::-webkit-scrollbar-thumb,
.longtask-panel .log-list:hover::-webkit-scrollbar-thumb,
.longtask-panel .log-list:active::-webkit-scrollbar-thumb,
.longtask-panel .task-message-input:hover::-webkit-scrollbar-thumb,
.longtask-panel .task-message-input:active::-webkit-scrollbar-thumb,
.code-block-wrapper pre:hover::-webkit-scrollbar-thumb,
.code-block-wrapper pre:active::-webkit-scrollbar-thumb {
  background: #d1d5db;
}
</style>
