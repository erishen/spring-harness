/**
 * useChat composable
 * 封装聊天核心逻辑：消息管理、发送、停止、重新生成、持久化
 */
import { ref, watch, computed } from 'vue'
import * as api from '../services/api.js'

// localStorage 键名
const STORAGE_KEY = 'spring-harness-chat-history'

/** 默认欢迎消息 */
function defaultMessages() {
  return [{
    role: 'ai',
    content: '你好！我是基于 Spring AI Alibaba 的聊天助手。\n\n可切换模式：\n• 普通对话：流式输出 + 多轮上下文\n• Agent 模式：自动调用工具（计算器、时间、股票）\n• PSE 协作：Planner-Specialist-Evaluator 三角色协作\n• RAG 知识库：上传文档后基于内容问答'
  }]
}

/** 从 localStorage 加载聊天历史 */
function loadChatHistory() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const parsed = JSON.parse(saved)
      if (Array.isArray(parsed) && parsed.length > 0) {
        return parsed
      }
    }
  } catch (e) {
    console.error('加载聊天历史失败', e)
  }
  return defaultMessages()
}

/** 创建空的 AI 消息对象 */
export function createEmptyAiMessage() {
  return {
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
}

export function useChat() {
  // ==================== 状态 ====================
  const messages = ref(loadChatHistory())
  const loading = ref(false)
  const input = ref('')
  const lastUserMessage = ref('')

  // 停止生成：AbortController
  let abortController = null

  // ==================== 持久化 ====================

  /** 保存聊天历史到 localStorage */
  function saveChatHistory() {
    try {
      const simplified = messages.value.map(msg => ({
        role: msg.role,
        content: msg.content,
        error: msg.error || false,
        tokenUsage: msg.tokenUsage || null,
        llmCallCount: msg.llmCallCount || 0
      }))
      localStorage.setItem(STORAGE_KEY, JSON.stringify(simplified))
    } catch (e) {
      console.error('保存聊天历史失败', e)
      if (e.name === 'QuotaExceededError') {
        localStorage.removeItem(STORAGE_KEY)
      }
    }
  }

  // 监听 messages 变化，自动保存
  watch(messages, () => {
    saveChatHistory()
  }, { deep: true })

  // ==================== 历史消息构建 ====================

  /** 构建历史消息数组（用于多轮对话上下文） */
  const historyMessages = computed(() => {
    // 只取最近的 10 条消息（不含当前正在生成的），避免 token 过多
    const history = messages.value.slice(-11, -1)
    if (history.length === 0) return []
    return history.map(msg => ({
      role: msg.role === 'ai' ? 'assistant' : 'user',
      content: msg.content || ''
    })).filter(m => m.content)
  })

  // ==================== 核心操作 ====================

  /**
   * 发送消息
   * @param {Function} sendFn - 实际发送函数 (text, aiMsg, signal) => Promise
   * @param {Function} [onError] - 错误处理回调
   */
  async function sendMessage(sendFn, onError) {
    const text = input.value.trim()
    if (!text || loading.value) return

    // 创建新的 AbortController
    abortController = new AbortController()
    lastUserMessage.value = text

    loading.value = true
    messages.value.push({ role: 'user', content: text })
    input.value = ''

    let aiMsg = createEmptyAiMessage()
    messages.value.push(aiMsg)
    // 关键：push 到响应式数组后，重新获取 Proxy 引用
    aiMsg = messages.value[messages.value.length - 1]

    try {
      await sendFn(text, aiMsg, abortController.signal)
      if (!aiMsg.content && !aiMsg.error) {
        aiMsg.content = '(无返回内容)'
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        aiMsg.content = aiMsg.content || '(已停止生成)'
      } else {
        aiMsg.error = true
        if (onError) {
          onError(err, aiMsg)
        } else {
          if (err.message === 'Failed to fetch' || err.name === 'TypeError') {
            aiMsg.content = '无法连接到后端服务（端口 8080）。\n请确认后端已启动：运行 make dev 可同时启动前后端。'
          } else {
            aiMsg.content = '请求失败：' + err.message
          }
        }
      }
    } finally {
      loading.value = false
      abortController = null
    }
  }

  /** 停止当前生成 */
  function stopGeneration() {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    loading.value = false
  }

  /**
   * 重新生成上一条回答
   * @param {Function} sendFn - 实际发送函数
   */
  function regenerate(sendFn) {
    if (loading.value || !lastUserMessage.value) return
    // 移除最后一条 AI 消息
    if (messages.value.length > 0 && messages.value[messages.value.length - 1].role === 'ai') {
      messages.value.pop()
    }
    // 重新发送
    input.value = lastUserMessage.value
    sendMessage(sendFn)
  }

  /** 清空聊天记录 */
  function clearChat() {
    if (messages.value.length === 0) return
    if (!confirm('确定要清空所有聊天记录吗？此操作不可撤销。')) return
    messages.value = [{
      role: 'ai',
      content: '聊天记录已清空。有什么可以帮你的吗？'
    }]
    localStorage.removeItem(STORAGE_KEY)
  }

  /** 导出聊天记录为 Markdown */
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

  // ==================== 返回 ====================
  return {
    // 状态
    messages,
    loading,
    input,
    lastUserMessage,
    historyMessages,
    // 操作
    sendMessage,
    stopGeneration,
    regenerate,
    clearChat,
    exportChat,
    createEmptyAiMessage
  }
}

export default useChat
