<template>
  <div class="input-wrapper">
    <!-- 示例问题（按模式 + 当前环境能力动态生成，可刷新） -->
    <div class="examples-bar" v-if="examples.length">
      <span class="examples-label">试试：</span>
      <div class="examples-list">
        <button
          v-for="(ex, i) in examples"
          :key="i"
          class="example-chip"
          @click="applyExample(ex.title)"
          :title="ex.tags && ex.tags.length ? `${ex.title}\n依赖: ${ex.tags.join(', ')}` : ex.title"
        >
          {{ ex.title }}
        </button>
      </div>
      <button class="refresh-btn" @click="refresh" :disabled="loadingExamples" title="调用大模型基于当前环境重新生成示例">
        <span :class="{ spinning: loadingExamples }">↻</span> AI 生成
      </button>
    </div>

    <div class="input-area">
      <textarea
        :value="modelValue"
        rows="1"
        :placeholder="placeholder"
        @keydown="handleKeydown"
        @input="onInput"
        ref="inputRef"
      ></textarea>
      <button 
        @click="loading ? $emit('stop') : $emit('send')" 
        :class="{ 'stop-btn': loading }"
      >
        <span v-if="loading" class="btn-loading"></span>
        {{ loading ? '停止' : '发送' }}
      </button>
    </div>
    <div class="hint" v-if="hint">{{ hint }}</div>
  </div>
</template>

<script setup>
import { ref, nextTick, watch } from 'vue'
import { getExamples } from '../services/api.js'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  hint: { type: String, default: '' },
  mode: { type: String, default: 'chat' }
})

const emit = defineEmits(['update:modelValue', 'send', 'stop'])

const inputRef = ref(null)
const examples = ref([])
const loadingExamples = ref(false)

// 模式 → 后端示例接口参数
const MODE_PARAM = { chat: 'chat', agent: 'agent', pse: 'pse', rag: 'rag', task: 'longtask' }

async function load(withLlm = false) {
  loadingExamples.value = true
  try {
    const data = await getExamples(MODE_PARAM[props.mode] || 'chat', withLlm)
    examples.value = data.examples || []
  } catch (e) {
    examples.value = []
    console.error('加载示例问题失败:', e)
  } finally {
    loadingExamples.value = false
  }
}

// 切模式：快速模板（不调用大模型）；点刷新：LLM 重新生成（内容会变化）
watch(() => props.mode, () => load(false), { immediate: true })
const refresh = () => load(true)

function applyExample(text) {
  emit('update:modelValue', text)
  nextTick(() => {
    autoResize()
    inputRef.value?.focus()
  })
}

function onInput(e) {
  emit('update:modelValue', e.target.value)
  autoResize()
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    emit('send')
  }
}

function autoResize() {
  nextTick(() => {
    const el = inputRef.value
    if (el) {
      el.style.height = 'auto'
      el.style.height = Math.min(el.scrollHeight, 150) + 'px'
    }
  })
}

defineExpose({
  focus: () => inputRef.value?.focus(),
  autoResize
})
</script>

<style scoped>
.input-wrapper {
  padding: 12px 24px 16px;
  border-top: 1px solid #e8eaed;
  background: #fff;
}

/* 示例问题栏 */
.examples-bar {
  max-width: 1200px;
  margin: 0 auto 10px;
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.examples-label {
  font-size: 11px;
  color: #9ca3af;
  flex-shrink: 0;
  font-weight: 500;
  padding-top: 5px;
}
.examples-list {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  flex: 1;
  min-width: 0;
}
.example-chip {
  flex-shrink: 0;
  padding: 4px 12px;
  font-size: 12px;
  color: #6b7280;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
  font-family: inherit;
}
.example-chip:hover {
  background: #eef2ff;
  border-color: #c7d2fe;
  color: #4a6cf7;
}
.example-chip:active {
  transform: scale(0.97);
}
.refresh-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  font-size: 12px;
  color: #4a6cf7;
  background: transparent;
  border: 1px dashed #c7d2fe;
  border-radius: 16px;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
  white-space: nowrap;
}
.refresh-btn:hover:not(:disabled) {
  background: #eef2ff;
  border-color: #4a6cf7;
}
.refresh-btn:disabled {
  color: #9ca3af;
  border-color: #e5e7eb;
  cursor: not-allowed;
}
.refresh-btn .spinning {
  display: inline-block;
  animation: spin 0.8s linear infinite;
}

/* 输入区域 */
.input-area {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  max-width: 1200px;
  margin: 0 auto;
}
.input-area textarea {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #d1d5db;
  border-radius: 12px;
  font-size: 14px;
  font-family: inherit;
  resize: none;
  max-height: 150px;
  line-height: 1.5;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
  background: #fafafa;
  overflow: hidden;
}
.input-area textarea:focus {
  border-color: #4a6cf7;
  box-shadow: 0 0 0 3px rgba(74, 108, 247, 0.1);
  background: #fff;
}
.input-area textarea::placeholder {
  color: #9ca3af;
}
.input-area button {
  padding: 10px 22px;
  background: #4a6cf7;
  color: #fff;
  border: none;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, transform 0.1s;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}
.input-area button:hover:not(:disabled) {
  background: #3b5de7;
}
.input-area button:active:not(:disabled) {
  transform: scale(0.97);
}
.input-area button:disabled {
  background: #c7cdd9;
  cursor: not-allowed;
}
.input-area button.stop-btn {
  background: #ef4444;
}
.input-area button.stop-btn:hover {
  background: #dc2626;
}
.btn-loading {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.hint {
  max-width: 1200px;
  margin: 8px auto 0;
  font-size: 11px;
  color: #9ca3af;
  text-align: center;
}
</style>
