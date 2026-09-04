<template>
  <div class="input-wrapper">
    <!-- 示例问题 -->
    <div class="examples-bar" v-if="examples.length">
      <span class="examples-label">试试：</span>
      <div class="examples-list">
        <button
          v-for="(ex, i) in examples"
          :key="i"
          class="example-chip"
          @click="applyExample(ex)"
          :title="ex"
        >
          {{ ex }}
        </button>
      </div>
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
import { ref, nextTick, computed } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  hint: { type: String, default: '' },
  mode: { type: String, default: 'chat' }
})

const emit = defineEmits(['update:modelValue', 'send', 'stop'])

const inputRef = ref(null)

// 各模式示例问题（组合工具展示）
const EXAMPLES = {
  chat: [
    '用 Markdown 写 Spring AI 简介',
    '解释 RAG 和微调的区别',
    '写 Python 快速排序代码'
  ],
  agent: [
    '用Python算斐波那契第20项',
    '用Java写冒泡排序并输出',
    '用Go写快速排序并输出',
    '用Rust算斐波那契第25项',
    '用C列出1到100的素数',
    '用C++反转排序一个数组',
    '查 AAPL 股价算买100股',
    '现在几点？算 123×456'
  ],
  pse: [
    '查 AAPL/MSFT/TSLA 股价，算哪个涨幅最大',
    '买100股 MSFT 要多少钱',
    '查时间规划今天学习计划'
  ],
  rag: [
    'Spring AI 支持哪些向量库',
    '如何配置 DashScope 模型',
    '文档提到哪些功能特性'
  ]
}

const examples = computed(() => EXAMPLES[props.mode] || [])

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
  max-width: 860px;
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

/* 输入区域 */
.input-area {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  max-width: 860px;
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
  max-width: 860px;
  margin: 8px auto 0;
  font-size: 11px;
  color: #9ca3af;
  text-align: center;
}
</style>
