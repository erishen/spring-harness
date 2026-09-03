<template>
  <div class="chat-container" ref="chatContainer">
    <div v-for="(msg, index) in messages" :key="index" class="msg" :class="msg.role">
      <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
      <div class="bubble-wrap">
        <!-- PSE 多 Agent 协作步骤展示（PSE 模式）- 放在最终答案前面 -->
        <div v-if="msg.pseSteps && msg.pseSteps.length" class="pse-steps">
          <div class="pse-steps-title">
            <span class="dot"></span>PSE 多 Agent 协作过程 · 共 {{ msg.pseSteps.length }} 步
            <button class="pse-copy-btn" @click="copyPseSteps(msg)" title="复制完整执行过程">
              📋 复制
            </button>
          </div>
          <div v-for="(step, si) in msg.pseSteps" :key="si" class="pse-step" :class="'role-' + step.role">
            <div class="pse-step-header">
              <span class="pse-role-badge" :class="'badge-' + step.role">
                {{ roleDisplayName(step.role) }}
              </span>
              <span class="pse-step-title">{{ step.title }}</span>
              <span class="pse-step-seq">#{{ step.sequence }}</span>
            </div>
            <div v-if="step.content" class="pse-step-content" v-html="renderMarkdown(step.content)"></div>
            <!-- 工具调用卡片（Specialist 执行时） -->
            <div v-if="step.toolCalls && step.toolCalls.length" class="tool-calls">
              <div v-for="(tc, i) in step.toolCalls" :key="i" class="tool-call-card">
                <div class="tool-call-header">
                  <span class="tool-name">{{ toolDisplayName(tc.name) }}</span>
                  <span class="tool-duration">{{ tc.durationMs }}ms</span>
                </div>
                <div class="tool-call-section">
                  <span class="section-label">入参</span>
                  <pre class="section-content">{{ formatInput(tc.input) }}</pre>
                </div>
                <div class="tool-call-section">
                  <span class="section-label">结果</span>
                  <pre class="section-content">{{ formatOutput(tc.output) }}</pre>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- ReAct 流式实时状态展示（正在思考/调用工具时） -->
        <div v-if="(msg.currentThought || (msg.currentToolCalls && msg.currentToolCalls.length)) && msg.role === 'ai'" class="react-live">
          <div class="react-live-title">
            <span class="live-dot"></span>
            <span>第 {{ msg.iterations || 1 }} 轮 · 进行中</span>
          </div>
          <!-- 当前思考内容 -->
          <div v-if="msg.currentThought" class="react-thought live">
            <span class="thought-label">💭 思考中...</span>
            <div class="thought-content" v-html="renderMarkdown(msg.currentThought)"></div>
          </div>
          <!-- 当前工具调用 -->
          <div v-if="msg.currentToolCalls && msg.currentToolCalls.length" class="tool-calls">
            <div v-for="(tc, i) in msg.currentToolCalls" :key="i" class="tool-call-card" :class="{ running: tc.status === 'running' }">
              <div class="tool-call-header">
                <span class="tool-name">
                  <span v-if="tc.status === 'running'" class="spinner"></span>
                  {{ toolDisplayName(tc.name) }}
                </span>
                <span class="tool-status" :class="tc.status">
                  {{ tc.status === 'running' ? '执行中...' : '完成' }}
                </span>
              </div>
              <div class="tool-call-section">
                <span class="section-label">入参</span>
                <pre class="section-content">{{ formatInput(tc.input) }}</pre>
              </div>
              <div v-if="tc.output" class="tool-call-section">
                <span class="section-label">结果</span>
                <pre class="section-content">{{ formatOutput(tc.output) }}</pre>
              </div>
            </div>
          </div>
        </div>

        <!-- ReAct 多轮步骤展示（Agent 模式）- 放在最终答案前面 -->
        <div v-if="msg.reactSteps && msg.reactSteps.length" class="react-steps">
          <div class="react-steps-title">
            <span class="dot"></span>ReAct 推理过程 · 共 {{ msg.iterations }} 轮
          </div>
          <div v-for="(step, si) in msg.reactSteps" :key="si" class="react-step">
            <div class="react-step-header">
              <span class="step-badge">第 {{ step.iteration }} 轮</span>
              <span class="step-type">{{ step.type }}</span>
            </div>
            <!-- 思考内容 -->
            <div v-if="step.thought && step.type !== '最终回答'" class="react-thought">
              <span class="thought-label">💭 思考</span>
              <div class="thought-content" v-html="renderMarkdown(step.thought)"></div>
            </div>
            <!-- 工具调用卡片 -->
            <div v-if="step.toolCalls && step.toolCalls.length" class="tool-calls">
              <div v-for="(tc, i) in step.toolCalls" :key="i" class="tool-call-card">
                <div class="tool-call-header">
                  <span class="tool-name">{{ toolDisplayName(tc.name) }}</span>
                  <span class="tool-duration">{{ tc.durationMs }}ms</span>
                </div>
                <div class="tool-call-section">
                  <span class="section-label">入参</span>
                  <pre class="section-content">{{ formatInput(tc.input) }}</pre>
                </div>
                <div class="tool-call-section">
                  <span class="section-label">结果</span>
                  <pre class="section-content">{{ formatOutput(tc.output) }}</pre>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 最终答案/消息内容 -->
        <div class="bubble" :class="{ error: msg.error, 'md-content': msg.role === 'ai' }">
          <span v-if="msg.content && msg.role === 'ai'" v-html="renderMarkdown(msg.content)"></span>
          <span v-else-if="msg.content">{{ msg.content }}</span>
          <span v-else-if="msg.role === 'ai' && !msg.error" class="typing">
            <span></span><span></span><span></span>
          </span>
        </div>

        <!-- 消息操作栏：复制 + 重新生成 -->
        <div v-if="msg.content" class="msg-actions" :class="{ visible: copiedIndex === index }">
          <button class="msg-action-btn" :class="{ copied: copiedIndex === index }" @click="copyMessage(msg, index)" :title="msg.error ? '复制完整错误报告（含PSE步骤）' : '复制消息内容'">
            {{ copiedIndex === index ? '已复制 ✓' : (msg.error && msg.pseSteps ? '复制错误报告' : '复制') }}
          </button>
          <button 
            v-if="msg.role === 'ai' && isLastAiMessage(index)" 
            class="msg-action-btn regenerate-btn" 
            @click="$emit('regenerate')"
            title="重新生成回答"
          >
            🔄 重新生成
          </button>
        </div>

        <!-- Token 用量统计 -->
        <div v-if="msg.tokenUsage && msg.role === 'ai'" class="token-usage">
          <span class="token-item">
            <span class="token-icon">🔢</span>
            <span class="token-label">总 Token</span>
            <span class="token-value">{{ msg.tokenUsage.totalTokens }}</span>
          </span>
          <span class="token-item">
            <span class="token-label">输入</span>
            <span class="token-value">{{ msg.tokenUsage.promptTokens }}</span>
          </span>
          <span class="token-item">
            <span class="token-label">输出</span>
            <span class="token-value">{{ msg.tokenUsage.completionTokens }}</span>
          </span>
          <span v-if="msg.llmCallCount" class="token-item">
            <span class="token-label">LLM 调用</span>
            <span class="token-value">{{ msg.llmCallCount }} 次</span>
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, watch, onMounted, onBeforeUnmount } from 'vue'
import { renderMarkdown } from '../utils/markdown.js'

const props = defineProps({
  messages: { type: Array, default: () => [] }
})

const emit = defineEmits(['regenerate'])

const chatContainer = ref(null)
const copiedIndex = ref(-1)

// ==================== 代码块复制按钮 ====================

/** 给 AI 正文（最终回答）里的代码块添加语言标签 + 复制按钮。
 *  注意：只处理 .bubble.md-content 内的 pre，避免误伤工具卡片的入参/结果
 *  （.section-content pre）、ReAct 思考区、PSE 步骤内容等过程信息。 */
function addCopyButtons() {
  nextTick(() => {
    const root = chatContainer.value
    if (!root) return
    // 清理历史误加：还原不在 AI 正文里的代码块包装（如工具卡片入参/结果、思考区等）
    root.querySelectorAll('.code-block-wrapper').forEach(w => {
      if (!w.closest('.bubble.md-content')) {
        const pre = w.querySelector('pre')
        if (pre) w.replaceWith(pre)
      }
    })
    const pres = root.querySelectorAll('.bubble.md-content pre')
    if (!pres) return
    pres.forEach((pre, idx) => {
      // 避免重复添加
      if (pre.parentElement?.classList.contains('code-block-wrapper')) return

      // 从 code 的 class 中提取语言名
      const code = pre.querySelector('code')
      const langMatch = code?.className?.match(/language-([\w+-]+)/)
      const lang = langMatch ? langMatch[1] : ''

      const wrapper = document.createElement('div')
      wrapper.className = 'code-block-wrapper'

      // 头部栏：语言标签 + 复制按钮
      const header = document.createElement('div')
      header.className = 'code-block-header'

      const langLabel = document.createElement('span')
      langLabel.className = 'code-lang-label'
      langLabel.textContent = lang || 'code'

      const copyBtn = document.createElement('button')
      copyBtn.className = 'code-copy-btn'
      copyBtn.dataset.idx = idx
      // 用 SVG 图标 + 文字
      copyBtn.innerHTML = `
        <svg class="copy-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
        </svg>
        <span class="copy-text">复制</span>
      `

      copyBtn.addEventListener('click', async () => {
        const codeEl = pre.querySelector('code')
        const text = codeEl ? codeEl.innerText : pre.innerText
        const copyText = copyBtn.querySelector('.copy-text')
        const copyIcon = copyBtn.querySelector('.copy-icon')
        try {
          await navigator.clipboard.writeText(text)
          copyText.textContent = '已复制'
          copyBtn.classList.add('copied')
          copyIcon.innerHTML = `<path d="M20 6L9 17l-5-5"></path>`
          setTimeout(() => {
            copyText.textContent = '复制'
            copyBtn.classList.remove('copied')
            copyIcon.innerHTML = `
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
            `
          }, 2000)
        } catch (e) {
          // 降级方案
          const textarea = document.createElement('textarea')
          textarea.value = text
          document.body.appendChild(textarea)
          textarea.select()
          document.execCommand('copy')
          document.body.removeChild(textarea)
          copyText.textContent = '已复制'
          copyBtn.classList.add('copied')
          copyIcon.innerHTML = `<path d="M20 6L9 17l-5-5"></path>`
          setTimeout(() => {
            copyText.textContent = '复制'
            copyBtn.classList.remove('copied')
            copyIcon.innerHTML = `
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
            `
          }, 2000)
        }
      })

      header.appendChild(langLabel)
      header.appendChild(copyBtn)
      wrapper.appendChild(header)

      // 把 pre 移到 wrapper 中
      pre.parentNode.insertBefore(wrapper, pre)
      wrapper.appendChild(pre)
    })
  })
}

// 监听消息变化，添加代码块复制按钮
watch(() => props.messages, () => {
  addCopyButtons()
}, { deep: true })

// 流式输出时 v-html 每帧重写会覆盖刚添加的复制按钮，
// 而 watch 不监听每一帧变化，导致代码块按钮偶发不出现。
// 用 MutationObserver 监听 chat-container 内 DOM 变化，节流补增强，覆盖流式/异步渲染/会话恢复等所有场景。
let codeObserver = null
let codeRafId = null

function scheduleAddCopyButtons() {
  if (codeRafId) return
  codeRafId = requestAnimationFrame(() => {
    codeRafId = null
    addCopyButtons()
  })
}

onMounted(() => {
  // 首次挂载时已有历史消息，主动增强一次
  addCopyButtons()
  if (!chatContainer.value || typeof MutationObserver === 'undefined') return
  codeObserver = new MutationObserver(scheduleAddCopyButtons)
  codeObserver.observe(chatContainer.value, { childList: true, subtree: true })
})

onBeforeUnmount(() => {
  if (codeRafId) cancelAnimationFrame(codeRafId)
  if (codeObserver) {
    codeObserver.disconnect()
    codeObserver = null
  }
})

/** 判断是否是最后一条 AI 消息（用于显示重新生成按钮） */
function isLastAiMessage(index) {
  for (let i = props.messages.length - 1; i >= 0; i--) {
    if (props.messages[i].role === 'ai') {
      return i === index
    }
  }
  return false
}

// 复制消息内容到剪贴板
async function copyMessage(msg, index) {
  let text = msg.content || ''
  // 错误消息时，复制完整的错误报告（含 PSE 步骤）
  if (msg.error && msg.pseSteps && msg.pseSteps.length) {
    text = buildErrorReport(msg)
  }
  try {
    await navigator.clipboard.writeText(text)
    copiedIndex.value = index
    setTimeout(() => { copiedIndex.value = -1 }, 2000)
  } catch (e) {
    // 降级方案：用 textarea
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    copiedIndex.value = index
    setTimeout(() => { copiedIndex.value = -1 }, 2000)
  }
}

// 构建完整的错误报告（正确步骤简写，出错步骤详细）
function buildErrorReport(msg) {
  const lines = []
  lines.push('=== PSE 执行错误报告 ===')
  lines.push('')
  lines.push('【错误信息】')
  lines.push(msg.content || '未知错误')
  lines.push('')
  if (msg.pseSteps && msg.pseSteps.length) {
    lines.push(`【PSE 执行步骤】共 ${msg.pseSteps.length} 步（正确步骤简写，出错步骤详细）`)
    lines.push('')
    const lastIdx = msg.pseSteps.length - 1
    msg.pseSteps.forEach((step, i) => {
      const isLast = i === lastIdx
      const hasError = step.content && /错误|失败|Exception|Error|timeout|超时/i.test(step.content)
      // 正确步骤简写，最后一步或含错误关键词的步骤详细
      if (!isLast && !hasError) {
        lines.push(`✓ 步骤 ${i + 1}: [${step.role}] ${step.title}`)
      } else {
        lines.push(`✗ 步骤 ${i + 1}: [${step.role}] ${step.title} ${isLast ? '← 最后一步' : ''}`)
        if (step.content) {
          lines.push('  内容:')
          step.content.split('\n').forEach(l => lines.push('  ' + l))
        }
        if (step.toolCalls && step.toolCalls.length) {
          step.toolCalls.forEach(tc => {
            lines.push(`  [工具调用] ${tc.name} (${tc.durationMs || '?'}ms)`)
            if (tc.input) lines.push(`  入参: ${JSON.stringify(tc.input)}`)
            if (tc.output) lines.push(`  结果: ${tc.output}`)
          })
        }
      }
      lines.push('')
    })
  }
  if (msg.tokenUsage) {
    lines.push('【Token 用量】')
    lines.push(`总: ${msg.tokenUsage.totalTokens}, 输入: ${msg.tokenUsage.promptTokens}, 输出: ${msg.tokenUsage.completionTokens}`)
    if (msg.llmCallCount) lines.push(`LLM 调用次数: ${msg.llmCallCount}`)
  }
  return lines.join('\n')
}

// 复制 PSE 步骤
async function copyPseSteps(msg) {
  const text = buildErrorReport(msg)
  try {
    await navigator.clipboard.writeText(text)
  } catch (e) {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
}

const TOOL_NAMES = {
  calculator: '🧮 计算器',
  get_datetime: '🕐 当前时间',
  query_stock: '📈 股票查询'
}

function toolDisplayName(name) {
  return TOOL_NAMES[name] || name
}

function roleDisplayName(role) {
  const names = {
    planner: 'Planner 规划者',
    specialist: 'Specialist 执行者',
    evaluator: 'Evaluator 评审官',
    system: 'System'
  }
  return names[role] || role
}

function formatInput(input) {
  if (!input) return '(无)'
  if (typeof input === 'string') return input
  try { return JSON.stringify(input, null, 2) } catch { return String(input) }
}

function formatOutput(output) {
  if (!output) return '(无)'
  const match = output.match(/Response\[(.*)\]/)
  if (match) return match[1].replace(/, /g, '\n')
  return output
}

// 自动滚动到底部
watch(() => props.messages.length, () => {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
})

defineExpose({
  scrollToBottom: () => {
    nextTick(() => {
      if (chatContainer.value) {
        chatContainer.value.scrollTop = chatContainer.value.scrollHeight
      }
    })
  }
})
</script>

<style scoped>
.chat-container {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
}
.msg {
  display: flex;
  gap: 10px;
  max-width: 85%;
}
.msg.user {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.msg.user .avatar { background: #4a6cf7; color: #fff; }
.msg.ai .avatar { background: #f0f2f5; color: #6b7280; }
.bubble-wrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}
.bubble {
  padding: 14px 18px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}
.msg.user .bubble {
  background: #4a6cf7;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.msg.ai .bubble {
  background: #f5f7fa;
  color: #1f2937;
  border-bottom-left-radius: 4px;
}
.bubble.error {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}
.bubble.md-content {
  overflow-x: auto;
  overflow-y: visible;
}
.bubble.md-content :deep(h1), .bubble.md-content :deep(h2), .bubble.md-content :deep(h3) {
  margin: 12px 0 8px;
  font-weight: 600;
}
.bubble.md-content :deep(h1) { font-size: 18px; }
.bubble.md-content :deep(h2) { font-size: 16px; }
.bubble.md-content :deep(h3) { font-size: 15px; }
.bubble.md-content :deep(p) { margin: 8px 0; }
.bubble.md-content :deep(ul), .bubble.md-content :deep(ol) {
  margin: 8px 0;
  padding-left: 20px;
}
.bubble.md-content :deep(li) { margin: 4px 0; }
.bubble.md-content :deep(code) {
  background: rgba(0,0,0,0.06);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
  font-family: 'SF Mono', 'Menlo', monospace;
}
.bubble.md-content :deep(pre) {
  background: #1e293b;
  color: #e2e8f0;
  padding: 12px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 10px 0;
}
.bubble.md-content :deep(pre code) {
  background: none;
  padding: 0;
  color: inherit;
  font-size: 13px;
}
/* 代码块容器 + 头部栏 */
.bubble.md-content :deep(.code-block-wrapper) {
  position: relative;
  margin: 10px 0;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}
.bubble.md-content :deep(.code-block-wrapper pre) {
  margin: 0;
  border-radius: 0;
  padding-top: 12px;
}
/* 代码块头部栏：语言标签 + 复制按钮 */
.bubble.md-content :deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #16233c;
  padding: 4px 10px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.bubble.md-content :deep(.code-lang-label) {
  font-size: 10px;
  font-weight: 500;
  color: #8b9cb8;
  font-family: 'SF Mono', 'Menlo', monospace;
  letter-spacing: 0.3px;
}
/* 复制按钮：小巧精致 */
.bubble.md-content :deep(.code-copy-btn) {
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
.bubble.md-content :deep(.code-copy-btn .copy-icon) {
  width: 11px;
  height: 11px;
}
.bubble.md-content :deep(.code-block-header:hover .code-copy-btn) {
  opacity: 1;
  background: rgba(255,255,255,0.08);
  color: #cbd5e1;
  border-color: rgba(255,255,255,0.12);
}
.bubble.md-content :deep(.code-copy-btn.copied) {
  background: rgba(16,185,129,0.15);
  color: #34d399;
  border-color: rgba(16,185,129,0.25);
  opacity: 1;
}
.bubble.md-content :deep(blockquote) {
  border-left: 3px solid #4a6cf7;
  padding-left: 12px;
  margin: 8px 0;
  color: #6b7280;
}
.bubble.md-content :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
  width: 100%;
}
.bubble.md-content :deep(th), .bubble.md-content :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 6px 10px;
  text-align: left;
}
.bubble.md-content :deep(th) {
  background: #f9fafb;
  font-weight: 600;
}
.bubble.md-content :deep(a) {
  color: #4a6cf7;
  text-decoration: none;
}
.bubble.md-content :deep(a:hover) {
  text-decoration: underline;
}

/* 消息操作栏：默认隐藏，hover 消息或点击复制后显示（用 display 硬控，避免 opacity 被覆盖） */
.msg-actions {
  display: none;
  gap: 8px;
  margin-top: 6px;
}
.msg:hover .msg-actions,
.msg-actions.visible {
  display: flex;
}
.msg-action-btn {
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.2s;
}
.msg-action-btn:hover {
  background: #f3f4f6;
  color: #374151;
  border-color: #d1d5db;
}
.msg-action-btn.copied {
  background: #10b981;
  color: #fff;
  border-color: #10b981;
}
.msg-action-btn.regenerate-btn {
  background: #f0f9ff;
  color: #0284c7;
  border-color: #bae6fd;
}
.msg-action-btn.regenerate-btn:hover {
  background: #e0f2fe;
  color: #0369a1;
}

/* Token 用量统计 */
.token-usage {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 10px;
  padding: 10px 14px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 11px;
  color: #64748b;
}
.token-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.token-icon {
  font-size: 12px;
}
.token-label {
  color: #94a3b8;
  font-weight: 500;
}
.token-value {
  color: #475569;
  font-weight: 600;
  font-family: 'SF Mono', 'Menlo', monospace;
}

/* 打字动画 */
.typing {
  display: inline-flex;
  gap: 4px;
  padding: 4px 0;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9ca3af;
  animation: bounce 1.4s infinite ease-in-out both;
}
.typing span:nth-child(1) { animation-delay: -0.32s; }
.typing span:nth-child(2) { animation-delay: -0.16s; }
@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-6px); opacity: 1; }
}

/* ========== ReAct 流式实时状态样式 ========== */
.react-live {
  margin-top: 12px;
  background: #f0f7ff;
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  padding: 10px 12px;
}
.react-live-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #2563eb;
  margin-bottom: 8px;
}
.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2563eb;
  animation: pulse 1.5s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
.react-thought.live {
  background: #fff;
  border: 1px solid #e2e8f0;
}
.tool-call-card.running {
  border-color: #3b82f6;
  background: #eff6ff;
}
.tool-status {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
}
.tool-status.running {
  color: #2563eb;
  background: #dbeafe;
}
.tool-status.done {
  color: #16a34a;
  background: #dcfce7;
}
.spinner {
  display: inline-block;
  width: 10px;
  height: 10px;
  border: 2px solid #bfdbfe;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-right: 4px;
  vertical-align: middle;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ========== ReAct 多轮步骤样式 ========== */
.react-steps {
  margin-top: 12px;
}
.react-steps-title {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.react-steps-title .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #4a6cf7;
}
.react-step {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
}
.react-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.step-badge {
  font-size: 10px;
  font-weight: 600;
  background: #4a6cf7;
  color: #fff;
  padding: 2px 8px;
  border-radius: 10px;
}
.step-type {
  font-size: 11px;
  color: #6b7280;
  font-weight: 500;
}
.react-thought {
  background: #fffbeb;
  border-left: 3px solid #f59e0b;
  padding: 8px 10px;
  border-radius: 0 6px 6px 0;
  margin-bottom: 8px;
}
.thought-label {
  font-size: 11px;
  font-weight: 600;
  color: #92400e;
  display: block;
  margin-bottom: 4px;
}
.thought-content {
  font-size: 13px;
  color: #78350f;
  line-height: 1.5;
}

/* ========== PSE 多 Agent 步骤样式 ========== */
.pse-steps {
  margin-top: 16px;
}
.pse-steps-title {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 0 2px;
}
.pse-steps-title .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #8b5cf6;
}
.pse-copy-btn {
  font-size: 11px;
  padding: 3px 8px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.2s;
}
.pse-copy-btn:hover {
  background: #f3f4f6;
  border-color: #9ca3af;
  color: #374151;
}
.pse-step {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 10px;
}
.pse-step.role-planner { border-left: 3px solid #3b82f6; }
.pse-step.role-specialist { border-left: 3px solid #10b981; }
.pse-step.role-evaluator { border-left: 3px solid #f59e0b; }
.pse-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.pse-role-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 10px;
  flex-shrink: 0;
}
.pse-role-badge.badge-planner { background: #dbeafe; color: #1d4ed8; }
.pse-role-badge.badge-specialist { background: #d1fae5; color: #047857; }
.pse-role-badge.badge-evaluator { background: #fef3c7; color: #92400e; }
.pse-role-badge.badge-system { background: #f3f4f6; color: #6b7280; }
.pse-step-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  flex: 1;
  min-width: 0;
}
.pse-step-seq {
  font-size: 10px;
  color: #9ca3af;
  font-weight: 600;
  flex-shrink: 0;
}
.pse-step-content {
  font-size: 13px;
  color: #4b5563;
  line-height: 1.7;
  padding-top: 2px;
}
/* PSE 步骤内容中的 Markdown 元素间距优化 */
.pse-step-content :deep(p) {
  margin: 0 0 10px 0;
}
.pse-step-content :deep(p:last-child) {
  margin-bottom: 0;
}
.pse-step-content :deep(ul),
.pse-step-content :deep(ol) {
  margin: 8px 0;
  padding-left: 20px;
}
.pse-step-content :deep(li) {
  margin-bottom: 4px;
}
.pse-step-content :deep(pre) {
  margin: 0;
  padding: 12px;
  border-radius: 0;
}
.pse-step-content :deep(.code-block-wrapper) {
  border-radius: 8px;
  overflow: hidden;
  margin: 10px 0;
  border: 1px solid #e2e8f0;
}
.pse-step-content :deep(.code-block-wrapper pre) {
  margin: 0;
}
.pse-step-content :deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f1f5f9;
  padding: 3px 10px;
  border-bottom: 1px solid #e2e8f0;
}
.pse-step-content :deep(.code-lang-label) {
  font-size: 10px;
  font-weight: 500;
  color: #64748b;
  font-family: 'SF Mono', 'Menlo', monospace;
}
.pse-step-content :deep(.code-copy-btn) {
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
  transition: all 0.2s;
  opacity: 0.75;
}
.pse-step-content :deep(.code-copy-btn .copy-icon) {
  width: 11px;
  height: 11px;
}
.pse-step-content :deep(.code-copy-btn:hover) {
  opacity: 1;
  background: #fff;
  color: #334155;
  border-color: #e2e8f0;
}
.pse-step-content :deep(.code-copy-btn.copied) {
  background: #ecfdf5;
  color: #059669;
  border-color: #a7f3d0;
  opacity: 1;
}
.pse-step-content :deep(code) {
  padding: 2px 6px;
  border-radius: 4px;
}
.pse-step-content :deep(table) {
  margin: 10px 0;
}
.pse-step-content :deep(h1),
.pse-step-content :deep(h2),
.pse-step-content :deep(h3) {
  margin: 12px 0 8px 0;
}

/* ========== 工具调用卡片样式 ========== */
.tool-calls {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.tool-call-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
}
.tool-call-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
}
.tool-name {
  font-size: 12px;
  font-weight: 600;
  color: #4a6cf7;
}
.tool-duration {
  font-size: 10px;
  color: #9ca3af;
}
.tool-call-section {
  padding: 6px 10px;
}
.section-label {
  font-size: 10px;
  font-weight: 600;
  color: #9ca3af;
  text-transform: uppercase;
  display: block;
  margin-bottom: 4px;
}
.section-content {
  font-size: 12px;
  font-family: 'SF Mono', 'Menlo', monospace;
  color: #374151;
  background: #f9fafb;
  padding: 6px 8px;
  border-radius: 4px;
  overflow-x: auto;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}
</style>
