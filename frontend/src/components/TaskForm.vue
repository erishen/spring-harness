<template>
  <div class="task-form">
    <div class="task-form-row">
      <select v-model="localType" class="task-type-select" title="任务类型">
        <option value="pse">PSE 协作</option>
        <option value="agent">ReAct Agent</option>
      </select>
      <input
        v-model="localModel"
        class="task-model-input"
        placeholder="模型（默认 agnes-2.0-flash）"
      />
      <button class="task-submit-btn" :disabled="submitting" @click="onSubmit">
        {{ submitting ? '提交中...' : '🚀 提交任务' }}
      </button>
    </div>
    <textarea
      ref="inputRef"
      v-model="localMessage"
      class="task-message-input"
      placeholder="描述长时任务，如：用 Go 并发筛出 1~1000000 的全部素数，统计个数与耗时"
      rows="2"
      @input="autoGrow"
      @keydown.enter.ctrl="onSubmit"
    ></textarea>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  taskType: { type: String, default: 'pse' },
  taskModel: { type: String, default: '' },
  taskMessage: { type: String, default: '' },
  submitting: { type: Boolean, default: false }
})

const emit = defineEmits(['update:taskType', 'update:taskModel', 'update:taskMessage', 'submit'])

const localType = ref(props.taskType)
const localModel = ref(props.taskModel)
const localMessage = ref(props.taskMessage)
const inputRef = ref(null)

watch(localType, v => emit('update:taskType', v))
watch(localModel, v => emit('update:taskModel', v))
watch(localMessage, v => emit('update:taskMessage', v))

// 外部填充示例时同步本地值
watch(() => props.taskMessage, v => {
  localMessage.value = v
  nextTick(() => autoGrow())
})

function autoGrow() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 120) + 'px'
}

function onSubmit() {
  if (!localMessage.value.trim() || props.submitting) return
  emit('submit')
}

defineExpose({ focus: () => inputRef.value?.focus() })
</script>

<style scoped>
.task-form {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 10px;
}
.task-form-row {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.task-type-select {
  flex-shrink: 0;
  padding: 5px 8px;
  font-size: 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  color: #374151;
  cursor: pointer;
}
.task-model-input {
  flex: 1;
  min-width: 0;
  padding: 5px 8px;
  font-size: 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  color: #374151;
}
.task-model-input::placeholder { color: #9ca3af; }
.task-submit-btn {
  flex-shrink: 0;
  padding: 5px 12px;
  font-size: 12px;
  font-weight: 600;
  border: none;
  border-radius: 6px;
  background: #3b82f6;
  color: #fff;
  cursor: pointer;
  transition: background 0.15s;
}
.task-submit-btn:hover:not(:disabled) { background: #2563eb; }
.task-submit-btn:disabled { background: #9ca3af; cursor: not-allowed; }
.task-message-input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  color: #1f2937;
  resize: none;
  overflow-y: auto;
  line-height: 1.5;
}
.task-message-input::placeholder { color: #9ca3af; }
.task-message-input:focus { outline: none; border-color: #3b82f6; box-shadow: 0 0 0 2px rgba(59,130,246,0.1); }
.task-message-input::-webkit-scrollbar { width: 4px; }
.task-message-input::-webkit-scrollbar-thumb { background: transparent; }
</style>
