<template>
  <div class="task-examples" v-if="examples.length">
    <div class="task-examples-header">
      <span class="task-examples-title">✨ 示例任务</span>
      <button class="task-examples-refresh" @click="$emit('refresh')" :disabled="loading" title="刷新示例（调用大模型生成）">
        {{ loading ? '生成中...' : '🔄 刷新' }}
      </button>
    </div>
    <div class="task-examples-list">
      <div
        v-for="ex in examples"
        :key="ex.title"
        class="task-example-item"
        @click="$emit('select', ex)"
      >
        <span class="task-example-type" :class="ex.type">{{ ex.typeLabel }}</span>
        <span class="task-example-title">{{ ex.title }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  examples: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

defineEmits(['select', 'refresh'])
</script>

<style scoped>
.task-examples {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 10px;
}
.task-examples-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.task-examples-title {
  font-size: 12px;
  font-weight: 600;
  color: #374151;
}
.task-examples-refresh {
  font-size: 11px;
  padding: 2px 8px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.task-examples-refresh:hover:not(:disabled) { border-color: #3b82f6; color: #3b82f6; }
.task-examples-refresh:disabled { opacity: 0.5; cursor: not-allowed; }
.task-examples-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.task-example-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  background: #f9fafb;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}
.task-example-item:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
}
.task-example-type {
  flex-shrink: 0;
  font-size: 9px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 3px;
}
.task-example-type.pse { background: #ecfdf5; color: #059669; }
.task-example-type.agent { background: #eff6ff; color: #2563eb; }
.task-example-title {
  font-size: 11.5px;
  color: #374151;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
