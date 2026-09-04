<!--
  PrivacyPanel.vue
  隐私数据管理面板（GDPR 合规）
  - 个人数据统计展示
  - 导出所有数据（JSON）
  - 删除所有后端数据（不可恢复）
  - 清理本地聊天记录（localStorage）
-->
<template>
  <div class="privacy-panel">
    <div class="panel-header">
      <h3>隐私与数据管理</h3>
      <p class="subtitle">符合 GDPR 合规要求，您可以随时导出或删除个人数据</p>
    </div>

    <!-- 数据统计 -->
    <div class="stats-section" v-if="summary">
      <h4>数据统计</h4>
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-label">长时任务</div>
          <div class="stat-value">{{ summary.longTasks.total }}</div>
          <div class="stat-detail">
            完成 {{ summary.longTasks.completed }} · 失败 {{ summary.longTasks.failed }} · 运行中 {{ summary.longTasks.running }}
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-label">长期记忆</div>
          <div class="stat-value">{{ summary.memory.total }}</div>
          <div class="stat-detail">用户偏好与事实</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">RAG 文档</div>
          <div class="stat-value">{{ summary.rag.documents }}</div>
          <div class="stat-detail">{{ summary.rag.totalChunks }} 个向量块</div>
        </div>
      </div>
    </div>

    <!-- 操作按钮 -->
    <div class="actions-section">
      <h4>数据操作</h4>

      <div class="action-item">
        <div class="action-info">
          <div class="action-title">导出所有个人数据</div>
          <div class="action-desc">下载 JSON 格式的完整数据副本（长时任务、Memory、RAG 文档元信息）</div>
        </div>
        <button class="btn btn-primary" @click="exportData" :disabled="exporting">
          {{ exporting ? '导出中...' : '导出数据' }}
        </button>
      </div>

      <div class="action-item">
        <div class="action-info">
          <div class="action-title">清理本地聊天记录</div>
          <div class="action-desc">删除浏览器 localStorage 中的所有会话和聊天记录（仅影响本机）</div>
        </div>
        <button class="btn btn-warning" @click="clearLocalChat">
          清理本地记录
        </button>
      </div>

      <div class="action-item danger">
        <div class="action-info">
          <div class="action-title">删除所有后端数据</div>
          <div class="action-desc">
            <strong>不可恢复！</strong>删除所有长时任务、Memory 记忆、RAG 文档及向量数据。
            聊天记录需单独使用上方按钮清理。
          </div>
        </div>
        <button class="btn btn-danger" @click="deleteAllData" :disabled="deleting">
          {{ deleting ? '删除中...' : '删除所有数据' }}
        </button>
      </div>
    </div>

    <!-- 数据保留说明 -->
    <div class="notice-section">
      <h4>数据保留说明</h4>
      <ul>
        <li>聊天记录：存储在浏览器 localStorage，超过 30 天未更新的会话自动清理</li>
        <li>长时任务：已完成任务保留 30 天后自动清理（可配置）</li>
        <li>Memory 记忆：可启用 AES-256 加密存储（默认关闭）</li>
        <li>RAG 文档：原始文件不保留在服务器，仅保留文本内容和向量数据</li>
        <li>错误信息：自动脱敏，不泄露 API Key、文件路径、IP 等敏感信息</li>
      </ul>
    </div>

    <!-- 操作结果提示 -->
    <div v-if="message" class="result-message" :class="messageType">
      {{ message }}
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const summary = ref(null)
const exporting = ref(false)
const deleting = ref(false)
const message = ref('')
const messageType = ref('info')

/** 加载数据统计 */
async function loadSummary() {
  try {
    const res = await fetch('/api/privacy/summary')
    if (res.ok) {
      summary.value = await res.json()
    }
  } catch (e) {
    console.error('加载隐私数据统计失败', e)
  }
}

/** 导出所有数据 */
async function exportData() {
  exporting.value = true
  message.value = ''
  try {
    const res = await fetch('/api/privacy/export')
    if (res.ok) {
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = res.headers.get('Content-Disposition')?.match(/filename="(.+)"/)?.[1] || 'privacy-export.json'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      showMessage('数据导出成功', 'success')
    } else {
      showMessage('导出失败：' + res.statusText, 'error')
    }
  } catch (e) {
    showMessage('导出失败：' + e.message, 'error')
  } finally {
    exporting.value = false
  }
}

/** 清理本地聊天记录 */
function clearLocalChat() {
  if (!confirm('确定要清理本地所有聊天记录吗？\n\n此操作将删除浏览器中存储的所有会话和消息，不可恢复。')) {
    return
  }
  try {
    localStorage.removeItem('spring-harness-chat-history')
    localStorage.removeItem('spring-harness-sessions')
    localStorage.removeItem('spring-harness-current-session')
    showMessage('本地聊天记录已清理，刷新页面后生效', 'success')
  } catch (e) {
    showMessage('清理失败：' + e.message, 'error')
  }
}

/** 删除所有后端数据 */
async function deleteAllData() {
  if (!confirm('⚠️ 确定要删除所有后端数据吗？\n\n此操作不可恢复！将删除：\n• 所有长时任务记录\n• 所有 Memory 长期记忆\n• 所有 RAG 文档及向量数据\n\n聊天记录需单独清理。')) {
    return
  }
  if (!confirm('再次确认：真的要删除所有数据吗？此操作无法撤销！')) {
    return
  }

  deleting.value = true
  message.value = ''
  try {
    const res = await fetch('/api/privacy/delete', { method: 'DELETE' })
    if (res.ok) {
      const result = await res.json()
      showMessage(`数据删除完成：任务 ${result.longTasksDeleted} 个，Memory ${result.memoryDeleted} 条，RAG ${result.ragDocumentsDeleted} 个文档`, 'success')
      loadSummary()
    } else {
      showMessage('删除失败：' + res.statusText, 'error')
    }
  } catch (e) {
    showMessage('删除失败：' + e.message, 'error')
  } finally {
    deleting.value = false
  }
}

function showMessage(msg, type) {
  message.value = msg
  messageType.value = type
  setTimeout(() => { message.value = '' }, 5000)
}

onMounted(() => {
  loadSummary()
})
</script>

<style scoped>
.privacy-panel {
  padding: 16px;
}

.panel-header h3 {
  margin: 0 0 4px 0;
  font-size: 16px;
  color: #1a1b1c;
}

.subtitle {
  margin: 0 0 16px 0;
  font-size: 12px;
  color: #6b7280;
}

h4 {
  margin: 16px 0 8px 0;
  font-size: 13px;
  color: #374151;
  border-bottom: 1px solid #e5e7eb;
  padding-bottom: 4px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.stat-card {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px;
  text-align: center;
}

.stat-label {
  font-size: 11px;
  color: #6b7280;
  margin-bottom: 4px;
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
  color: #1a1b1c;
}

.stat-detail {
  font-size: 10px;
  color: #9ca3af;
  margin-top: 4px;
}

.actions-section {
  margin-top: 8px;
}

.action-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid #f3f4f6;
  gap: 12px;
}

.action-item.danger {
  background: #fef2f2;
  padding: 10px;
  border-radius: 8px;
  border: 1px solid #fecaca;
}

.action-info {
  flex: 1;
  min-width: 0;
}

.action-title {
  font-size: 13px;
  font-weight: 500;
  color: #1a1b1c;
}

.action-desc {
  font-size: 11px;
  color: #6b7280;
  margin-top: 2px;
  line-height: 1.4;
}

.btn {
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  white-space: nowrap;
  transition: opacity 0.2s;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-primary {
  background: #3b82f6;
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: #2563eb;
}

.btn-warning {
  background: #f59e0b;
  color: white;
}

.btn-warning:hover:not(:disabled) {
  background: #d97706;
}

.btn-danger {
  background: #ef4444;
  color: white;
}

.btn-danger:hover:not(:disabled) {
  background: #dc2626;
}

.notice-section ul {
  margin: 8px 0;
  padding-left: 16px;
  font-size: 11px;
  color: #6b7280;
  line-height: 1.6;
}

.result-message {
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
}

.result-message.success {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}

.result-message.error {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
}

.result-message.info {
  background: #eff6ff;
  color: #2563eb;
  border: 1px solid #bfdbfe;
}
</style>
