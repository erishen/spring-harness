<template>
  <div class="tool-call-card" :class="{ running: toolCall.status === 'running' }">
    <div class="tool-call-header">
      <span class="tool-name">
        <span v-if="toolCall.status === 'running'" class="spinner"></span>
        {{ toolDisplayName(toolCall.name) }}
      </span>
      <span v-if="toolCall.durationMs != null" class="tool-duration">{{ toolCall.durationMs }}ms</span>
      <span v-if="toolCall.status" class="tool-status" :class="toolCall.status">
        {{ toolCall.status === 'running' ? '执行中...' : '完成' }}
      </span>
    </div>
    <div class="tool-call-section">
      <span class="section-label">入参</span>
      <pre class="section-content">{{ formatInput(toolCall.input) }}</pre>
    </div>
    <div v-if="toolCall.output" class="tool-call-section">
      <span class="section-label">结果</span>
      <pre class="section-content">{{ formatOutput(toolCall.output) }}</pre>
    </div>
  </div>
</template>

<script setup>
import { toolDisplayName, formatInput, formatOutput } from '../utils/format.js'

defineProps({
  toolCall: {
    type: Object,
    required: true
  }
})
</script>

<style scoped>
.tool-call-card {
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  margin: 6px 0;
  overflow: hidden;
}
.tool-call-card.running {
  border-color: rgba(59, 130, 246, 0.4);
  background: rgba(59, 130, 246, 0.04);
}
.tool-call-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: rgba(0, 0, 0, 0.03);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 12px;
}
.tool-name {
  font-weight: 600;
  color: #374151;
  display: flex;
  align-items: center;
  gap: 4px;
}
.tool-duration {
  color: #9ca3af;
  font-size: 11px;
}
.tool-status {
  margin-left: auto;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}
.tool-status.running {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.1);
}
.spinner {
  display: inline-block;
  width: 10px;
  height: 10px;
  border: 2px solid rgba(59, 130, 246, 0.3);
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.tool-call-section {
  padding: 6px 10px;
}
.section-label {
  display: block;
  font-size: 11px;
  color: #6b7280;
  margin-bottom: 3px;
  font-weight: 500;
}
.section-content {
  margin: 0;
  padding: 6px 8px;
  background: rgba(0, 0, 0, 0.03);
  border-radius: 4px;
  font-size: 11.5px;
  font-family: 'SF Mono', 'Fira Code', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
  color: #374151;
}
</style>
