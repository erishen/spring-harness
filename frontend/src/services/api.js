/**
 * API 服务层
 * 封装所有后端 API 调用，统一错误处理和参数构建
 */

// ==================== 工具函数 ====================

/** 构建查询参数 */
function buildQuery(params = {}) {
  const searchParams = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      searchParams.append(key, value)
    }
  })
  const query = searchParams.toString()
  return query ? '?' + query : ''
}

/** 统一 fetch 封装 */
async function request(url, options = {}) {
  const response = await fetch(url, options)
  if (!response.ok) {
    let errorMsg = `HTTP ${response.status}`
    try {
      const errText = await response.text()
      if (errText) errorMsg += ' - ' + errText.substring(0, 200)
    } catch (e) { /* ignore */ }
    throw new Error(errorMsg)
  }
  return response
}

// ==================== 模型相关 API ====================

/** 获取模型列表 */
export async function getModels() {
  const res = await request('/models')
  return res.json()
}

// ==================== 聊天相关 API ====================

/**
 * 非流式对话
 * @param {string} message - 用户消息
 * @param {string} [model] - 模型名称
 * @param {Array} [messages] - 历史消息 [{role, content}]
 */
export async function chat(message, model, messages) {
  const params = { message }
  if (model) params.model = model
  if (messages && messages.length) params.messages = JSON.stringify(messages)
  const res = await request('/chat' + buildQuery(params))
  return res.text()
}

/**
 * 流式对话（SSE）
 * @param {string} message - 用户消息
 * @param {string} [model] - 模型名称
 * @param {Array} [messages] - 历史消息 [{role, content}]
 * @param {AbortSignal} [signal] - 中止信号
 */
export async function chatStream(message, model, messages, signal) {
  const params = { message }
  if (model) params.model = model
  if (messages && messages.length) params.messages = JSON.stringify(messages)
  return request('/chat/stream' + buildQuery(params), {
    method: 'GET',
    headers: { Accept: 'text/event-stream' },
    signal
  })
}

/**
 * ReAct Agent 流式对话
 * @param {string} message - 用户消息
 * @param {string} [model] - 模型名称
 * @param {AbortSignal} [signal] - 中止信号
 */
export async function agentReactStream(message, model, signal) {
  const params = { message }
  if (model) params.model = model
  return request('/chat/agent/react/stream' + buildQuery(params), {
    method: 'GET',
    headers: { Accept: 'text/event-stream' },
    signal
  })
}

/**
 * PSE 多 Agent 协作流式对话
 * @param {string} message - 用户消息
 * @param {string} [model] - 模型名称
 * @param {AbortSignal} [signal] - 中止信号
 */
export async function pseStream(message, model, signal) {
  const params = { message }
  if (model) params.model = model
  return request('/chat/pse/stream' + buildQuery(params), {
    method: 'GET',
    headers: { Accept: 'text/event-stream' },
    signal
  })
}

/**
 * RAG 对话
 * @param {string} message - 用户消息
 * @param {string} [model] - 模型名称
 * @param {AbortSignal} [signal] - 中止信号
 */
export async function ragChat(message, model, signal) {
  const params = { message }
  if (model) params.model = model
  const res = await request('/chat/rag' + buildQuery(params), { method: 'GET', signal })
  return res.json()
}

// ==================== RAG 文档管理 API ====================

/** 获取文档列表 */
export async function getDocuments() {
  const res = await request('/rag/documents')
  return res.json()
}

/**
 * 上传文档
 * @param {File} file - 文件对象
 */
export async function uploadDocument(file) {
  const formData = new FormData()
  formData.append('file', file)
  const res = await request('/rag/documents', {
    method: 'POST',
    body: formData
  })
  return res.json()
}

/**
 * 删除文档
 * @param {string} docId - 文档 ID
 */
export async function deleteDocument(docId) {
  const res = await request('/rag/documents/' + docId, { method: 'DELETE' })
  return res.json()
}

// ==================== 运行时信息 API ====================

/** 获取工具列表 */
export async function getTools() {
  const res = await request('/api/tools')
  return res.json()
}

/** 获取 MCP 状态 */
export async function getMcpStatus() {
  const res = await request('/api/mcp/status')
  return res.json()
}

/** 获取沙箱状态 */
export async function getSandboxStatus() {
  const res = await request('/api/sandbox/status')
  return res.json()
}

/** 获取技能列表 */
export async function getSkills() {
  const res = await request('/api/skills')
  return res.json()
}

// ==================== SSE 解析工具 ====================

/**
 * 解析 SSE 流，回调每个事件
 * @param {Response} response - fetch 响应
 * @param {Function} onEvent - 事件回调 (data) => void
 * @param {Function} [onError] - 错误回调
 */
export async function parseSSE(response, onEvent, onError) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

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
        onEvent(data)
      } catch (e) {
        if (onError) onError(e, dataLines.join('\n'))
        else console.error('解析 SSE 事件失败', e, dataLines.join('\n'))
      }
    }
  }
}

export default {
  getModels,
  chat,
  chatStream,
  agentReactStream,
  pseStream,
  ragChat,
  getDocuments,
  uploadDocument,
  deleteDocument,
  getTools,
  getMcpStatus,
  getSandboxStatus,
  getSkills,
  parseSSE
}
