<template>
  <div class="msg" :class="msg.role">
    <div class="avatar">{{ msg.role === 'user' ? '我' : 'AI' }}</div>
    <div class="bubble-wrap">
      <!-- PSE 多 Agent 协作步骤 -->
      <div v-if="msg.pseSteps && msg.pseSteps.length" class="pse-steps">
        <div class="pse-steps-title">
          <span class="dot"></span>PSE 多 Agent 协作过程 · 共 {{ msg.pseSteps.length }} 步
          <button class="pse-copy-btn" @click="copyPseSteps" title="复制完整执行过程">
            📋 复制
          </button>
        </div>
        <div v-for="(step, si) in msg.pseSteps" :key="si" class="pse-step" :class="'role-' + step.role">
          <div class="pse-step-header">
            <span class="pse-role-badge" :class="'badge-' + step.role">{{ roleDisplayName(step.role) }}</span>
            <span class="pse-step-title">{{ step.title }}</span>
            <span v-if="step.sequence" class="pse-step-seq">#{{ step.sequence }}</span>
          </div>
          <div v-if="step.content" class="pse-step-content" v-html="renderMarkdown(step.content)"></div>
          <div v-if="step.toolCalls && step.toolCalls.length" class="tool-calls">
            <ToolCallCard v-for="(tc, i) in step.toolCalls" :key="i" :tool-call="tc" />
          </div>
        </div>
      </div>

      <!-- ReAct 流式实时状态 -->
      <div v-if="(msg.currentThought || (msg.currentToolCalls && msg.currentToolCalls.length)) && msg.role === 'ai'" class="react-live">
        <div class="react-live-title">
          <span class="live-dot"></span>
          <span>第 {{ msg.iterations || 1 }} 轮 · 进行中</span>
        </div>
        <div v-if="msg.currentThought" class="react-thought live">
          <span class="thought-label">💭 思考中...</span>
          <div class="thought-content" v-html="renderMarkdown(msg.currentThought)"></div>
        </div>
        <div v-if="msg.currentToolCalls && msg.currentToolCalls.length" class="tool-calls">
          <ToolCallCard v-for="(tc, i) in msg.currentToolCalls" :key="i" :tool-call="tc" />
        </div>
      </div>

      <!-- ReAct 多轮步骤 -->
      <div v-if="msg.reactSteps && msg.reactSteps.length" class="react-steps">
        <div class="react-steps-title">
          <span class="dot"></span>ReAct 推理过程 · 共 {{ msg.iterations }} 轮
        </div>
        <div v-for="(step, si) in msg.reactSteps" :key="si" class="react-step">
          <div class="react-step-header">
            <span class="step-badge">第 {{ step.iteration }} 轮</span>
            <span class="step-type">{{ step.type }}</span>
          </div>
          <div v-if="step.thought && step.type !== '最终回答'" class="react-thought">
            <span class="thought-label">💭 思考</span>
            <div class="thought-content" v-html="renderMarkdown(step.thought)"></div>
          </div>
          <div v-if="step.toolCalls && step.toolCalls.length" class="tool-calls">
            <ToolCallCard v-for="(tc, i) in step.toolCalls" :key="i" :tool-call="tc" />
          </div>
        </div>
      </div>

      <!-- 最终答案 -->
      <div ref="bubbleRef" class="bubble" :class="{ error: msg.error, 'md-content': msg.role === 'ai' }">
        <span v-if="msg.content && msg.role === 'ai'" v-html="renderMarkdown(msg.content)"></span>
        <span v-else-if="msg.content">{{ msg.content }}</span>
        <span v-else-if="msg.role === 'ai' && !msg.error" class="typing">
          <span></span><span></span><span></span>
        </span>
      </div>

      <!-- 操作栏 -->
      <div v-if="msg.content" class="msg-actions" :class="{ visible: copied }">
        <button class="msg-action-btn" :class="{ copied }" @click="copyMessage" :title="msg.error ? '复制完整错误报告（含PSE步骤）' : '复制消息内容'">
          {{ copied ? '已复制 ✓' : (msg.error && msg.pseSteps ? '复制错误报告' : '复制') }}
        </button>
        <button
          v-if="msg.role === 'ai' && isLastAi"
          class="msg-action-btn regenerate-btn"
          @click="$emit('regenerate')"
          title="重新生成回答"
        >
          🔄 重新生成
        </button>
      </div>

      <!-- Token 用量 -->
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
</template>

<script setup>
import { ref, nextTick, watch, onMounted, onBeforeUnmount } from 'vue'
import { renderMarkdown } from '../utils/markdown.js'
import { roleDisplayName, buildErrorReport, copyToClipboard } from '../utils/format.js'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps({
  msg: { type: Object, required: true },
  isLastAi: { type: Boolean, default: false }
})

defineEmits(['regenerate'])

const bubbleRef = ref(null)
const copied = ref(false)

// ===== 代码块复制按钮（仅作用于当前消息的 .bubble.md-content）=====
let codeObserver = null
let codeRafId = null

function addCopyButtons() {
  nextTick(() => {
    const bubble = bubbleRef.value
    if (!bubble) return
    // 清理历史误加：还原不在 AI 正文里的代码块包装
    bubble.querySelectorAll('.code-block-wrapper').forEach(w => {
      if (!w.closest('.bubble.md-content')) {
        const pre = w.querySelector('pre')
        if (pre) w.replaceWith(pre)
      }
    })
    const pres = bubble.querySelectorAll('.bubble.md-content pre')
    if (!pres) return
    pres.forEach((pre, idx) => {
      if (pre.parentElement?.classList.contains('code-block-wrapper')) return
      const code = pre.querySelector('code')
      const langMatch = code?.className?.match(/language-([\w+-]+)/)
      const lang = langMatch ? langMatch[1] : ''

      const wrapper = document.createElement('div')
      wrapper.className = 'code-block-wrapper'
      const header = document.createElement('div')
      header.className = 'code-block-header'
      const langLabel = document.createElement('span')
      langLabel.className = 'code-lang-label'
      langLabel.textContent = lang || 'code'
      const copyBtn = document.createElement('button')
      copyBtn.className = 'code-copy-btn'
      copyBtn.innerHTML = `
        <svg class="copy-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
        </svg>
        <span class="copy-text">复制</span>`
      copyBtn.addEventListener('click', async () => {
        const codeEl = pre.querySelector('code')
        const text = codeEl ? codeEl.innerText : pre.innerText
        const ok = await copyToClipboard(text)
        if (ok) {
          copyBtn.querySelector('.copy-text').textContent = '已复制'
          copyBtn.classList.add('copied')
          copyBtn.querySelector('.copy-icon').innerHTML = '<path d="M20 6L9 17l-5-5"></path>'
          setTimeout(() => {
            copyBtn.querySelector('.copy-text').textContent = '复制'
            copyBtn.classList.remove('copied')
            copyBtn.querySelector('.copy-icon').innerHTML = '<rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>'
          }, 2000)
        }
      })
      header.appendChild(langLabel)
      header.appendChild(copyBtn)
      wrapper.appendChild(header)
      pre.parentNode.insertBefore(wrapper, pre)
      wrapper.appendChild(pre)
    })
  })
}

function scheduleAddCopyButtons() {
  if (codeRafId) return
  codeRafId = requestAnimationFrame(() => {
    codeRafId = null
    addCopyButtons()
  })
}

onMounted(() => {
  addCopyButtons()
  if (bubbleRef.value && typeof MutationObserver !== 'undefined') {
    codeObserver = new MutationObserver(scheduleAddCopyButtons)
    codeObserver.observe(bubbleRef.value, { childList: true, subtree: true })
  }
})

onBeforeUnmount(() => {
  if (codeRafId) cancelAnimationFrame(codeRafId)
  if (codeObserver) { codeObserver.disconnect(); codeObserver = null }
})

// 消息内容变化时重新添加代码块按钮
watch(() => props.msg.content, () => addCopyButtons())

// ===== 复制消息 =====
async function copyMessage() {
  let text = props.msg.content || ''
  if (props.msg.error && props.msg.pseSteps && props.msg.pseSteps.length) {
    text = buildErrorReport(props.msg)
  }
  const ok = await copyToClipboard(text)
  if (ok) {
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

async function copyPseSteps() {
  await copyToClipboard(buildErrorReport(props.msg))
}
</script>

<style scoped>
/* 消息布局 */
.msg {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  animation: fadeIn 0.2s ease;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
.msg.user { flex-direction: row-reverse; }
.avatar {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.msg.user .avatar { background: #3b82f6; color: #fff; }
.msg.ai .avatar { background: #10b981; color: #fff; }
.bubble-wrap {
  max-width: 85%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.msg.user .bubble-wrap { align-items: flex-end; }

/* 气泡 */
.bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  overflow-wrap: break-word;
}
.msg.user .bubble {
  background: #3b82f6;
  color: #fff;
  border-bottom-right-radius: 4px;
}
.msg.ai .bubble {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-bottom-left-radius: 4px;
  color: #1f2937;
}
.bubble.error {
  background: #fef2f2;
  border-color: #fecaca;
  color: #991b1b;
}

/* ===== Markdown 内容样式（AI 回答） ===== */
:deep(.md-content) {
  font-size: 14px;
  line-height: 1.7;
  color: #1f2937;
}
:deep(.md-content > *:first-child) { margin-top: 0; }
:deep(.md-content > *:last-child) { margin-bottom: 0; }

/* 标题 */
:deep(.md-content h1),
:deep(.md-content h2),
:deep(.md-content h3),
:deep(.md-content h4),
:deep(.md-content h5),
:deep(.md-content h6) {
  margin: 16px 0 8px;
  font-weight: 600;
  color: #111827;
  line-height: 1.3;
}
:deep(.md-content h1) { font-size: 20px; border-bottom: 2px solid #e5e7eb; padding-bottom: 6px; }
:deep(.md-content h2) { font-size: 17px; border-bottom: 1px solid #f3f4f6; padding-bottom: 4px; }
:deep(.md-content h3) { font-size: 15.5px; }
:deep(.md-content h4) { font-size: 14.5px; }
:deep(.md-content h5), :deep(.md-content h6) { font-size: 13.5px; color: #4b5563; }

/* 段落 */
:deep(.md-content p) {
  margin: 8px 0;
}

/* 列表 */
:deep(.md-content ul),
:deep(.md-content ol) {
  margin: 8px 0;
  padding-left: 24px;
}
:deep(.md-content li) {
  margin: 4px 0;
  line-height: 1.6;
}
:deep(.md-content ul ul),
:deep(.md-content ul ol),
:deep(.md-content ol ul),
:deep(.md-content ol ol) {
  margin: 4px 0;
}
:deep(.md-content li > p) { margin: 2px 0; }

/* 任务列表 */
:deep(.md-content li input[type="checkbox"]) {
  margin-right: 6px;
  transform: translateY(1px);
}

/* 表格 */
:deep(.md-content table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
  font-size: 13px;
  display: block;
  overflow-x: auto;
}
:deep(.md-content th),
:deep(.md-content td) {
  border: 1px solid #e5e7eb;
  padding: 7px 12px;
  text-align: left;
  vertical-align: top;
}
:deep(.md-content th) {
  background: #f9fafb;
  font-weight: 600;
  color: #374151;
  white-space: nowrap;
}
:deep(.md-content tr:nth-child(even) td) {
  background: #fafafa;
}
:deep(.md-content tr:hover td) {
  background: #f0f7ff;
}

/* 引用块 */
:deep(.md-content blockquote) {
  margin: 10px 0;
  padding: 8px 14px;
  border-left: 4px solid #3b82f6;
  background: #f0f7ff;
  color: #4b5563;
  border-radius: 0 6px 6px 0;
}
:deep(.md-content blockquote p) {
  margin: 4px 0;
}
:deep(.md-content blockquote blockquote) {
  margin: 6px 0;
  border-left-color: #a78bfa;
  background: #f5f3ff;
}

/* 行内代码 */
:deep(.md-content code) {
  background: #f3f4f6;
  color: #db2777;
  padding: 1.5px 6px;
  border-radius: 4px;
  font-size: 12.5px;
  font-family: 'SF Mono', 'Fira Code', Monaco, Consolas, monospace;
  word-break: break-all;
}
:deep(.md-content pre code) {
  background: none;
  color: inherit;
  padding: 0;
  font-size: inherit;
}

/* 链接 */
:deep(.md-content a) {
  color: #2563eb;
  text-decoration: none;
  border-bottom: 1px solid transparent;
  transition: border-color 0.15s;
}
:deep(.md-content a:hover) {
  border-bottom-color: #2563eb;
}

/* 图片 */
:deep(.md-content img) {
  max-width: 100%;
  border-radius: 8px;
  margin: 8px 0;
}

/* 水平线 */
:deep(.md-content hr) {
  border: none;
  border-top: 1px solid #e5e7eb;
  margin: 16px 0;
}

/* 强调 */
:deep(.md-content strong) {
  color: #111827;
  font-weight: 600;
}
:deep(.md-content em) {
  color: #7c3aed;
  font-style: italic;
}
:deep(.md-content del) {
  color: #9ca3af;
  text-decoration: line-through;
}

/* 键盘按键 */
:deep(.md-content kbd) {
  background: #f3f4f6;
  border: 1px solid #d1d5db;
  border-bottom-width: 2px;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 11.5px;
  font-family: 'SF Mono', Monaco, monospace;
  color: #374151;
}

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
  animation: typing 1.2s infinite;
}
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-4px); opacity: 1; }
}

/* 操作栏 */
.msg-actions {
  display: flex;
  gap: 6px;
  opacity: 0;
  transition: opacity 0.15s;
}
.msg-actions.visible,
.msg:hover .msg-actions { opacity: 1; }
.msg-action-btn {
  font-size: 12px;
  padding: 3px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.msg-action-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.msg-action-btn.copied { border-color: #10b981; color: #10b981; }
.regenerate-btn:hover { border-color: #f59e0b; color: #f59e0b; }

/* Token 用量 */
.token-usage {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 6px 10px;
  background: rgba(59, 130, 246, 0.04);
  border-radius: 8px;
  font-size: 11.5px;
  color: #6b7280;
}
.token-item {
  display: flex;
  align-items: center;
  gap: 4px;
}
.token-icon { font-size: 12px; }
.token-label { color: #9ca3af; }
.token-value { font-weight: 600; color: #374151; }

/* PSE 步骤 */
.pse-steps {
  background: rgba(16, 185, 129, 0.04);
  border: 1px solid rgba(16, 185, 129, 0.15);
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 4px;
}
.pse-steps-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: #047857;
  margin-bottom: 8px;
}
.pse-steps-title .dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: #10b981;
}
.pse-copy-btn {
  margin-left: auto;
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid rgba(16, 185, 129, 0.3);
  border-radius: 4px;
  background: #fff;
  color: #047857;
  cursor: pointer;
}
.pse-copy-btn:hover { background: rgba(16, 185, 129, 0.08); }
.pse-step {
  padding: 6px 8px;
  margin-bottom: 4px;
  background: #fff;
  border-radius: 6px;
  border-left: 3px solid #d1d5db;
}
.pse-step.role-planner { border-left-color: #8b5cf6; }
.pse-step.role-specialist { border-left-color: #3b82f6; }
.pse-step.role-evaluator { border-left-color: #f59e0b; }
.pse-step.role-system { border-left-color: #6b7280; }
.pse-step-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  margin-bottom: 4px;
}
.pse-role-badge {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
  background: #f3f4f6;
  color: #6b7280;
}
.badge-planner { background: rgba(139, 92, 246, 0.1); color: #7c3aed; }
.badge-specialist { background: rgba(59, 130, 246, 0.1); color: #2563eb; }
.badge-evaluator { background: rgba(245, 158, 11, 0.1); color: #d97706; }
.badge-system { background: #f3f4f6; color: #6b7280; }
.pse-step-title { font-weight: 500; color: #374151; }
.pse-step-seq { margin-left: auto; color: #9ca3af; font-size: 11px; }
.pse-step-content {
  font-size: 12.5px;
  color: #4b5563;
  line-height: 1.5;
  padding: 4px 0;
}

/* ReAct 实时 */
.react-live {
  background: rgba(59, 130, 246, 0.04);
  border: 1px solid rgba(59, 130, 246, 0.15);
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 4px;
}
.react-live-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: #1d4ed8;
  margin-bottom: 8px;
}
.live-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: #3b82f6;
  animation: pulse 1.5s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}
.react-thought {
  background: rgba(139, 92, 246, 0.05);
  border-radius: 6px;
  padding: 6px 8px;
  margin-bottom: 6px;
}
.react-thought.live { border: 1px dashed rgba(139, 92, 246, 0.3); }
.thought-label {
  font-size: 11px;
  color: #7c3aed;
  font-weight: 600;
  display: block;
  margin-bottom: 2px;
}
.thought-content {
  font-size: 12.5px;
  color: #4b5563;
  line-height: 1.5;
}

/* ReAct 步骤 */
.react-steps {
  background: rgba(245, 158, 11, 0.04);
  border: 1px solid rgba(245, 158, 11, 0.15);
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 4px;
}
.react-steps-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: #b45309;
  margin-bottom: 8px;
}
.react-steps-title .dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: #f59e0b;
}
.react-step {
  padding: 6px 8px;
  margin-bottom: 4px;
  background: #fff;
  border-radius: 6px;
}
.react-step-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  margin-bottom: 4px;
}
.step-badge {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
  background: rgba(245, 158, 11, 0.1);
  color: #b45309;
}
.step-type { color: #6b7280; font-size: 11.5px; }

/* 工具调用容器 */
.tool-calls {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 代码块包装（由 JS 动态添加） */
:deep(.code-block-wrapper) {
  position: relative;
  margin: 8px 0;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e5e7eb;
}
:deep(.code-block-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
}
:deep(.code-lang-label) {
  font-size: 11px;
  color: #6b7280;
  font-family: monospace;
  text-transform: uppercase;
}
:deep(.code-copy-btn) {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
:deep(.code-copy-btn:hover) { border-color: #3b82f6; color: #3b82f6; }
:deep(.code-copy-btn.copied) { border-color: #10b981; color: #10b981; }
:deep(.copy-icon) { width: 12px; height: 12px; }
:deep(.code-block-wrapper pre) {
  margin: 0;
  padding: 10px;
  overflow-x: auto;
  font-size: 12.5px;
  line-height: 1.5;
  background: #f9fafb;
}
</style>
