<template>
  <div class="model-settings">
    <button class="settings-toggle" @click="expanded = !expanded">
      <span class="toggle-icon">{{ expanded ? '▼' : '▶' }}</span>
      高级设置
      <span v-if="hasCustomSettings" class="custom-badge">已自定义</span>
    </button>
    <div v-if="expanded" class="settings-panel">
      <!-- Temperature -->
      <div class="setting-item">
        <div class="setting-label">
          <span>Temperature</span>
          <span class="setting-value">{{ temperature.toFixed(1) }}</span>
        </div>
        <input
          type="range"
          min="0"
          max="2"
          step="0.1"
          :value="temperature"
          @input="updateTemperature($event.target.value)"
        />
        <div class="setting-hint">0=确定性，2=创造性（默认 0.7）</div>
      </div>

      <!-- Max Tokens -->
      <div class="setting-item">
        <div class="setting-label">
          <span>Max Tokens</span>
          <span class="setting-value">{{ maxTokens }}</span>
        </div>
        <input
          type="number"
          min="128"
          max="8192"
          step="128"
          :value="maxTokens"
          @input="updateMaxTokens($event.target.value)"
        />
        <div class="setting-hint">最大生成 token 数（默认 4096）</div>
      </div>

      <!-- Top P -->
      <div class="setting-item">
        <div class="setting-label">
          <span>Top P</span>
          <span class="setting-value">{{ topP.toFixed(1) }}</span>
        </div>
        <input
          type="range"
          min="0"
          max="1"
          step="0.1"
          :value="topP"
          @input="updateTopP($event.target.value)"
        />
        <div class="setting-hint">核采样阈值（默认 1.0）</div>
      </div>

      <!-- 系统提示词 -->
      <div class="setting-item">
        <div class="setting-label">
          <span>系统提示词</span>
          <span v-if="systemPrompt" class="setting-value">已自定义</span>
        </div>
        <textarea
          :value="systemPrompt"
          @input="updateSystemPrompt($event.target.value)"
          placeholder="自定义 AI 角色和行为，如：你是一个专业的 Java 架构师..."
          rows="3"
          class="system-prompt-input"
        ></textarea>
        <div class="setting-hint">留空使用默认行为（可选）</div>
      </div>

      <!-- 重置按钮 -->
      <button class="reset-btn" @click="resetSettings">
        恢复默认
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: Object,
    default: () => ({
      temperature: 0.7,
      maxTokens: 4096,
      topP: 1.0
    })
  }
})

const emit = defineEmits(['update:modelValue'])

const expanded = ref(false)
const temperature = ref(props.modelValue.temperature ?? 0.7)
const maxTokens = ref(props.modelValue.maxTokens ?? 4096)
const topP = ref(props.modelValue.topP ?? 1.0)
const systemPrompt = ref(props.modelValue.systemPrompt ?? '')

const hasCustomSettings = computed(() => {
  return temperature.value !== 0.7 || maxTokens.value !== 4096 || topP.value !== 1.0 || (systemPrompt.value && systemPrompt.value.trim())
})

function updateTemperature(val) {
  temperature.value = parseFloat(val)
  emitChange()
}

function updateMaxTokens(val) {
  const v = parseInt(val)
  if (!isNaN(v) && v >= 128 && v <= 8192) {
    maxTokens.value = v
    emitChange()
  }
}

function updateTopP(val) {
  topP.value = parseFloat(val)
  emitChange()
}

function updateSystemPrompt(val) {
  systemPrompt.value = val
  emitChange()
}

function emitChange() {
  emit('update:modelValue', {
    temperature: temperature.value,
    maxTokens: maxTokens.value,
    topP: topP.value,
    systemPrompt: systemPrompt.value
  })
}

function resetSettings() {
  temperature.value = 0.7
  maxTokens.value = 4096
  topP.value = 1.0
  systemPrompt.value = ''
  emitChange()
}

// 监听外部变化
watch(() => props.modelValue, (val) => {
  if (val) {
    temperature.value = val.temperature ?? 0.7
    maxTokens.value = val.maxTokens ?? 4096
    topP.value = val.topP ?? 1.0
    systemPrompt.value = val.systemPrompt ?? ''
  }
}, { deep: true })
</script>

<style scoped>
.model-settings {
  margin: 8px 0;
}

.settings-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  font-size: 12px;
  color: #475569;
  cursor: pointer;
  transition: all 0.2s;
  width: 100%;
}

.settings-toggle:hover {
  background: #f1f5f9;
  border-color: #cbd5e1;
}

.toggle-icon {
  font-size: 10px;
  color: #94a3b8;
}

.custom-badge {
  margin-left: auto;
  padding: 1px 6px;
  background: #dbeafe;
  color: #2563eb;
  border-radius: 4px;
  font-size: 10px;
}

.settings-panel {
  margin-top: 8px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.setting-item {
  margin-bottom: 12px;
}

.setting-item:last-of-type {
  margin-bottom: 8px;
}

.setting-label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 12px;
  color: #334155;
  font-weight: 500;
}

.setting-value {
  color: #2563eb;
  font-weight: 600;
}

.setting-item input[type="range"] {
  width: 100%;
  height: 4px;
  -webkit-appearance: none;
  background: #e2e8f0;
  border-radius: 2px;
  outline: none;
}

.setting-item input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 14px;
  height: 14px;
  background: #4a6cf7;
  border-radius: 50%;
  cursor: pointer;
  transition: transform 0.1s;
}

.setting-item input[type="range"]::-webkit-slider-thumb:hover {
  transform: scale(1.2);
}

.setting-item input[type="number"] {
  width: 100%;
  padding: 4px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  font-size: 12px;
  background: #fff;
}

.setting-hint {
  margin-top: 2px;
  font-size: 10px;
  color: #94a3b8;
}

.system-prompt-input {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 12px;
  background: #fff;
  resize: vertical;
  min-height: 60px;
  font-family: inherit;
  line-height: 1.5;
}

.system-prompt-input:focus {
  outline: none;
  border-color: #4a6cf7;
  box-shadow: 0 0 0 2px rgba(74, 108, 247, 0.1);
}

.reset-btn {
  width: 100%;
  padding: 6px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 11px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s;
}

.reset-btn:hover {
  background: #f1f5f9;
  color: #334155;
  border-color: #cbd5e1;
}
</style>
