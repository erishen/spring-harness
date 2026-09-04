/**
 * useSessions composable
 * 多会话管理：创建、切换、删除、重命名会话，localStorage 持久化
 */
import { ref, computed, watch } from 'vue'

const STORAGE_KEY = 'spring-harness-sessions'
const CURRENT_KEY = 'spring-harness-current-session'

/** 生成唯一会话 ID */
function generateId() {
  return 'session-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9)
}

/** 默认欢迎消息 */
function defaultMessages() {
  return [{
    role: 'ai',
    content: '你好！我是 **Spring Harness** 的 AI 助手。\n\n可切换模式：\n• 普通对话：流式输出 + 多轮上下文\n• RAG 知识库：上传文档后基于内容问答\n• ReAct Agent：自动识别意图调用工具\n• PSE 协作：Planner-Specialist-Evaluator 三角色协作\n• 长时任务：后台执行、可追踪 token 消耗'
  }]
}

/** 从 localStorage 加载会话 */
function loadSessions() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved) {
      const sessions = JSON.parse(saved)
      if (Array.isArray(sessions) && sessions.length > 0) {
        return sessions
      }
    }
  } catch (e) {
    console.error('加载会话失败', e)
  }
  // 默认创建一个会话
  const defaultSession = {
    id: generateId(),
    title: '新对话',
    messages: defaultMessages(),
    createdAt: Date.now(),
    updatedAt: Date.now()
  }
  return [defaultSession]
}

/** 从 localStorage 加载当前会话 ID */
function loadCurrentSessionId(sessions) {
  try {
    const saved = localStorage.getItem(CURRENT_KEY)
    if (saved && sessions.find(s => s.id === saved)) {
      return saved
    }
  } catch (e) { /* ignore */ }
  return sessions[0]?.id
}

export function useSessions() {
  // ==================== 状态 ====================
  const sessions = ref(loadSessions())
  const currentSessionId = ref(loadCurrentSessionId(sessions.value))

  // ==================== 计算属性 ====================
  const currentSession = computed(() => {
    return sessions.value.find(s => s.id === currentSessionId.value)
  })

  const currentMessages = computed({
    get() {
      return currentSession.value?.messages || []
    },
    set(messages) {
      if (currentSession.value) {
        currentSession.value.messages = messages
        currentSession.value.updatedAt = Date.now()
        // 自动更新会话标题（取第一条用户消息）
        const firstUserMsg = messages.find(m => m.role === 'user')
        if (firstUserMsg && currentSession.value.title === '新对话') {
          currentSession.value.title = firstUserMsg.content.substring(0, 20) + (firstUserMsg.content.length > 20 ? '...' : '')
        }
      }
    }
  })

  // ==================== 操作 ====================

  /** 创建新会话 */
  function createSession() {
    const newSession = {
      id: generateId(),
      title: '新对话',
      messages: defaultMessages(),
      createdAt: Date.now(),
      updatedAt: Date.now()
    }
    sessions.value.unshift(newSession)
    currentSessionId.value = newSession.id
    return newSession
  }

  /** 切换会话 */
  function switchSession(id) {
    if (sessions.value.find(s => s.id === id)) {
      currentSessionId.value = id
    }
  }

  /** 删除会话 */
  function deleteSession(id) {
    const index = sessions.value.findIndex(s => s.id === id)
    if (index === -1) return

    sessions.value.splice(index, 1)

    // 如果删除的是当前会话，切换到第一个会话
    if (currentSessionId.value === id) {
      if (sessions.value.length > 0) {
        currentSessionId.value = sessions.value[0].id
      } else {
        // 所有会话都被删除，创建一个新的
        createSession()
      }
    }
  }

  /** 重命名会话 */
  function renameSession(id, title) {
    const session = sessions.value.find(s => s.id === id)
    if (session) {
      session.title = title || '新对话'
      session.updatedAt = Date.now()
    }
  }

  /** 清空当前会话消息 */
  function clearCurrentSession() {
    if (currentSession.value) {
      currentSession.value.messages = defaultMessages()
      currentSession.value.title = '新对话'
      currentSession.value.updatedAt = Date.now()
    }
  }

  // ==================== 持久化 ====================

  /** 保存到 localStorage */
  function saveSessions() {
    try {
      // 简化消息，只保存必要字段
      const simplified = sessions.value.map(s => ({
        ...s,
        messages: s.messages.map(m => ({
          role: m.role,
          content: m.content,
          error: m.error || false,
          tokenUsage: m.tokenUsage || null,
          llmCallCount: m.llmCallCount || 0
        }))
      }))
      localStorage.setItem(STORAGE_KEY, JSON.stringify(simplified))
      localStorage.setItem(CURRENT_KEY, currentSessionId.value)
    } catch (e) {
      console.error('保存会话失败', e)
      if (e.name === 'QuotaExceededError') {
        // 存储溢出，删除最旧的会话
        if (sessions.value.length > 1) {
          sessions.value.pop()
          saveSessions()
        } else {
          localStorage.removeItem(STORAGE_KEY)
        }
      }
    }
  }

  // 监听会话变化，自动保存
  watch(sessions, () => {
    saveSessions()
  }, { deep: true })

  watch(currentSessionId, () => {
    saveSessions()
  })

  // ==================== 返回 ====================
  return {
    // 状态
    sessions,
    currentSessionId,
    currentSession,
    currentMessages,
    // 操作
    createSession,
    switchSession,
    deleteSession,
    renameSession,
    clearCurrentSession
  }
}

export default useSessions
