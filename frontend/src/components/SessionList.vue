<template>
  <div class="session-list">
    <!-- 新建会话按钮 -->
    <button class="new-session-btn" @click="$emit('create')">
      <span class="plus-icon">+</span>
      新建对话
    </button>

    <!-- 会话列表 -->
    <div class="sessions">
      <div
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ active: session.id === currentSessionId }"
        @click="$emit('switch', session.id)"
      >
        <div class="session-icon">💬</div>
        <div class="session-info">
          <div class="session-title" :title="session.title">{{ session.title }}</div>
          <div class="session-time">{{ formatTime(session.updatedAt) }}</div>
        </div>
        <button
          class="delete-btn"
          @click.stop="$emit('delete', session.id)"
          title="删除会话"
        >
          ×
        </button>
      </div>
    </div>

    <!-- 会话数量统计 -->
    <div class="session-count">
      共 {{ sessions.length }} 个会话
    </div>
  </div>
</template>

<script setup>
defineProps({
  sessions: {
    type: Array,
    default: () => []
  },
  currentSessionId: {
    type: String,
    default: ''
  }
})

defineEmits(['create', 'switch', 'delete'])

/** 格式化时间 */
function formatTime(timestamp) {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  const now = new Date()
  const diff = now - date

  // 今天
  if (diff < 86400000 && date.getDate() === now.getDate()) {
    return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  // 昨天
  if (diff < 172800000) {
    return '昨天 ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  // 7天内
  if (diff < 604800000) {
    const days = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    return days[date.getDay()] + ' ' + date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  // 更早
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<style scoped>
.session-list {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f8fafc;
  border-right: 1px solid #e2e8f0;
}

.new-session-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 12px;
  padding: 10px;
  background: #4a6cf7;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.new-session-btn:hover {
  background: #3b5de7;
}

.plus-icon {
  font-size: 16px;
  font-weight: bold;
}

.sessions {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px;
  margin-bottom: 4px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  position: relative;
}

.session-item:hover {
  background: #eef2f7;
}

.session-item.active {
  background: #dbeafe;
}

.session-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.session-info {
  flex: 1;
  min-width: 0;
}

.session-title {
  font-size: 13px;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
}

.session-item.active .session-title {
  color: #1d4ed8;
}

.session-time {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 2px;
}

.delete-btn {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: #94a3b8;
  font-size: 16px;
  cursor: pointer;
  opacity: 0;
  transition: all 0.15s;
  flex-shrink: 0;
}

.session-item:hover .delete-btn {
  opacity: 1;
}

.delete-btn:hover {
  background: #fee2e2;
  color: #dc2626;
}

.session-count {
  padding: 12px;
  font-size: 11px;
  color: #94a3b8;
  text-align: center;
  border-top: 1px solid #e2e8f0;
}
</style>
