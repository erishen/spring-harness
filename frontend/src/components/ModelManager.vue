<template>
  <div class="model-manager" v-if="visible">
    <div class="manager-overlay" @click="$emit('close')"></div>
    <div class="manager-panel">
      <div class="manager-header">
        <span class="manager-title">🤖 模型管理</span>
        <div class="manager-actions">
          <button class="manager-reset" @click="resetAll" title="重置所有模型启用状态为默认">↺ 重置默认</button>
          <button class="manager-close" @click="$emit('close')">✕</button>
        </div>
      </div>
      <div class="manager-stats">
        <span class="stat-item">共 {{ allModels.length }} 个模型</span>
        <span class="stat-item enabled">已启用 {{ enabledCount }}</span>
        <span class="stat-item disabled">已禁用 {{ allModels.length - enabledCount }}</span>
        <span class="stat-item native">需原生端点 {{ nativeCount }}</span>
      </div>
      <div class="manager-body">
        <div v-for="(models, vendor) in groupedModels" :key="vendor" class="vendor-group">
          <div class="vendor-header">
            <span class="vendor-name">{{ vendor }}</span>
            <span class="vendor-count">{{ models.length }} 个</span>
          </div>
          <div class="model-list">
            <div
              v-for="m in models"
              :key="m.id"
              class="model-row"
              :class="{ disabled: !m.enabled, native: m.nativeEndpoint }"
            >
              <div class="model-info">
                <span class="model-id">{{ m.id }}</span>
                <span class="model-desc">{{ m.description }}</span>
                <span v-if="m.nativeEndpoint" class="native-badge" title="需要原生API端点，当前 Spring AI Alibaba 版本暂不支持">需原生端点</span>
              </div>
              <label class="model-toggle" :title="m.enabled ? '点击禁用' : '点击启用'">
                <input type="checkbox" :checked="m.enabled" @change="toggleModel(m.id)" />
                <span class="toggle-slider" :class="{ on: m.enabled }"></span>
              </label>
            </div>
          </div>
        </div>
      </div>
      <div class="manager-footer">
        <span class="footer-hint">启用状态为内存级别，重启服务后恢复默认。需原生端点的模型即使启用也可能调用失败。</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'changed'])

const allModels = ref([])

const enabledCount = computed(() => allModels.value.filter(m => m.enabled).length)
const nativeCount = computed(() => allModels.value.filter(m => m.nativeEndpoint).length)

const groupedModels = computed(() => {
  const groups = {}
  allModels.value.forEach(m => {
    if (!groups[m.vendor]) groups[m.vendor] = []
    groups[m.vendor].push(m)
  })
  return groups
})

async function loadAllModels() {
  try {
    const res = await fetch('/models/all')
    if (res.ok) allModels.value = await res.json()
  } catch (e) {
    console.error('加载全部模型失败', e)
  }
}

async function toggleModel(id) {
  try {
    const res = await fetch(`/models/${encodeURIComponent(id)}/toggle`, { method: 'PUT' })
    if (res.ok) {
      const updated = await res.json()
      const idx = allModels.value.findIndex(m => m.id === id)
      if (idx >= 0) allModels.value[idx] = updated
      emit('changed')
    }
  } catch (e) {
    console.error('切换模型启用状态失败', e)
  }
}

async function resetAll() {
  try {
    await fetch('/models/reset', { method: 'POST' })
    await loadAllModels()
    emit('changed')
  } catch (e) {
    console.error('重置模型失败', e)
  }
}

onMounted(() => {
  if (props.visible) loadAllModels()
})

// 面板打开时重新加载
import { watch } from 'vue'
watch(() => props.visible, (val) => {
  if (val) loadAllModels()
})
</script>

<style scoped>
.model-manager {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.manager-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  backdrop-filter: blur(2px);
}
.manager-panel {
  position: relative;
  width: 560px;
  max-width: 90vw;
  max-height: 80vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.manager-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
}
.manager-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
}
.manager-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.manager-reset {
  font-size: 11px;
  padding: 3px 10px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
}
.manager-reset:hover { border-color: #3b82f6; color: #3b82f6; }
.manager-close {
  font-size: 14px;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #9ca3af;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.manager-close:hover { background: #f3f4f6; color: #374151; }

.manager-stats {
  display: flex;
  gap: 12px;
  padding: 8px 18px;
  border-bottom: 1px solid #f3f4f6;
  font-size: 11px;
}
.stat-item { color: #6b7280; }
.stat-item.enabled { color: #059669; font-weight: 600; }
.stat-item.disabled { color: #9ca3af; }
.stat-item.native { color: #d97706; margin-left: auto; }

.manager-body {
  flex: 1;
  overflow-y: auto;
  padding: 10px 18px;
}
.manager-body::-webkit-scrollbar { width: 6px; }
.manager-body::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.12); border-radius: 3px; }

.vendor-group {
  margin-bottom: 12px;
}
.vendor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  margin-bottom: 4px;
}
.vendor-name {
  font-size: 12px;
  font-weight: 600;
  color: #374151;
}
.vendor-count {
  font-size: 10px;
  color: #9ca3af;
}
.model-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.model-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  border-radius: 6px;
  transition: background 0.15s;
}
.model-row:hover { background: #f9fafb; }
.model-row.disabled { opacity: 0.5; }
.model-row.native { background: rgba(217, 119, 6, 0.04); }
.model-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}
.model-id {
  font-size: 12px;
  font-weight: 600;
  color: #1f2937;
  font-family: 'SF Mono', Monaco, monospace;
  flex-shrink: 0;
}
.model-desc {
  font-size: 11px;
  color: #6b7280;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.native-badge {
  flex-shrink: 0;
  font-size: 9px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 3px;
  background: #fef3c7;
  color: #b45309;
}
.model-toggle {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  flex-shrink: 0;
}
.model-toggle input { display: none; }
.toggle-slider {
  width: 32px;
  height: 18px;
  border-radius: 10px;
  background: #d1d5db;
  position: relative;
  transition: background 0.2s;
}
.toggle-slider::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  transition: left 0.2s;
  box-shadow: 0 1px 2px rgba(0,0,0,0.2);
}
.toggle-slider.on { background: #10b981; }
.toggle-slider.on::after { left: 16px; }

.manager-footer {
  padding: 8px 18px;
  border-top: 1px solid #f3f4f6;
  background: #f9fafb;
}
.footer-hint {
  font-size: 10px;
  color: #9ca3af;
  line-height: 1.4;
}
</style>
