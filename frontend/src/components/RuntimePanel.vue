<template>
  <aside class="runtime-panel">
    <div class="panel-header">
      <span class="panel-icon">⚙️</span>
      <span class="panel-title">Runtime</span>
      <span class="panel-subtitle">{{ tools.length + skills.length }} 项能力</span>
    </div>

    <!-- 工具列表 -->
    <div class="panel-card">
      <div class="card-header" @click="toolsExpanded = !toolsExpanded">
        <div class="card-title-group">
          <span class="card-arrow" :class="{ expanded: toolsExpanded }">▶</span>
          <span class="card-icon">🧰</span>
          <div class="card-titles">
            <span class="card-title">Tools</span>
            <span class="card-sub">本地 {{ localToolCount }}</span>
          </div>
        </div>
      </div>

      <div class="card-body" v-show="toolsExpanded">
        <!-- 本地工具 -->
        <div class="tool-group" v-if="localTools.length">
          <div class="group-label">
            <span class="group-dot local"></span>
            <span>本地工具</span>
            <span class="group-count">{{ localTools.length }}</span>
          </div>
          <div class="tool-grid">
            <div class="tool-chip" v-for="tool in localTools" :key="tool.name" :title="tool.description">
              <span class="tool-chip-name">{{ tool.name }}</span>
            </div>
          </div>
        </div>

        <div class="empty-hint" v-if="!localTools.length">加载中...</div>
      </div>
    </div>

    <!-- MCP 外部工具（独立区域） -->
    <div class="panel-card" v-if="mcpTools.length">
      <div class="card-header" @click="mcpExpanded = !mcpExpanded">
        <div class="card-title-group">
          <span class="card-arrow" :class="{ expanded: mcpExpanded }">▶</span>
          <span class="card-icon">🔌</span>
          <div class="card-titles">
            <span class="card-title">MCP</span>
            <span class="card-sub">外部工具 · {{ mcpServers.length }} 服务</span>
          </div>
        </div>
        <span class="count-badge">{{ mcpTools.length }}</span>
      </div>

      <div class="card-body" v-show="mcpExpanded">
        <div v-for="server in mcpServers" :key="server.name" class="mcp-server-block">
          <div class="mcp-server-tag">
            <span class="server-name">{{ server.name }}</span>
            <span class="server-type">{{ server.type }}</span>
          </div>
          <div class="tool-grid">
            <div class="tool-chip mcp" v-for="tool in server.tools" :key="tool.name" :title="tool.description">
              <span class="tool-chip-name">{{ tool.name }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 最近知识库检索（独立区域，不依赖 Tools 展开） -->
    <div class="panel-card" v-if="hasRagTool">
      <div class="card-header" @click="ragExpanded = !ragExpanded">
        <div class="card-title-group">
          <span class="card-arrow" :class="{ expanded: ragExpanded }">▶</span>
          <span class="card-icon">🧠</span>
          <div class="card-titles">
            <span class="card-title">Knowledge</span>
            <span class="card-sub">RAG 检索链路</span>
          </div>
        </div>
        <span class="count-badge" v-if="trace && trace.exists">{{ trace.trace.durationMs }}ms</span>
      </div>

      <div class="card-body" v-show="ragExpanded">
        <div v-if="trace && trace.exists" class="rag-trace-body">
          <div class="rag-trace-query">🔍 {{ trace.trace.query }}</div>
          <div class="rag-trace-flow">
            <span class="flow-node">候选 {{ trace.trace.candidateCount }}</span>
            <span class="flow-arrow">→</span>
            <span class="flow-node" :class="{ warn: trace.trace.rankMethod !== 'rerank' }">
              {{ trace.trace.rankMethod === 'rerank' ? 'Rerank 精排' : '回退向量' }}
            </span>
            <span class="flow-arrow">→</span>
            <span class="flow-node">返回 {{ trace.trace.returnedCount }}</span>
          </div>
          <div v-if="trace.trace.fragments && trace.trace.fragments.length" class="rag-trace-frags">
            <div v-for="(f, fi) in trace.trace.fragments.slice(0, 3)" :key="fi" class="rag-trace-frag">
              <div class="rag-trace-frag-head">
                <span class="rag-frag-source">{{ f.source || '未知来源' }}</span>
                <span v-if="f.score != null" class="rag-frag-score">{{ Number(f.score).toFixed(3) }}</span>
              </div>
              <div class="rag-frag-snippet">{{ f.snippet }}</div>
            </div>
          </div>
        </div>
        <div v-else class="rag-trace-empty">尚未执行知识库检索</div>
      </div>
    </div>

    <!-- Skills 技能列表 -->
    <div class="panel-card" v-if="skillsEnabled">
      <div class="card-header" @click="skillsExpanded = !skillsExpanded">
        <div class="card-title-group">
          <span class="card-arrow" :class="{ expanded: skillsExpanded }">▶</span>
          <span class="card-icon">⚡</span>
          <div class="card-titles">
            <span class="card-title">Skills</span>
            <span class="card-sub">技能库</span>
          </div>
        </div>
        <span class="count-badge">{{ skills.length }}</span>
      </div>

      <div class="card-body" v-show="skillsExpanded">
        <div class="skill-list">
          <div class="skill-item" v-for="skill in skills" :key="skill.name" :title="skill.description">
            <div class="skill-icon">🎯</div>
            <div class="skill-info">
              <div class="skill-name">{{ skill.name }}</div>
              <div class="skill-desc">{{ skill.description }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 长期记忆 -->
    <div class="panel-card">
      <div class="card-header" @click="memoryExpanded = !memoryExpanded">
        <div class="card-title-group">
          <span class="card-arrow" :class="{ expanded: memoryExpanded }">▶</span>
          <span class="card-icon">💾</span>
          <div class="card-titles">
            <span class="card-title">Memory</span>
            <span class="card-sub">{{ memory.enabled ? '长期记忆 · ' + memory.count + ' 条' : '已关闭' }}</span>
          </div>
        </div>
        <label class="mem-toggle" @click.stop :title="memory.enabled ? '关闭记忆' : '开启记忆'">
          <input type="checkbox" :checked="memory.enabled" @change="onToggleMemory($event.target.checked)" />
          <span class="toggle-slider" :class="{ on: memory.enabled }"></span>
        </label>
      </div>

      <div class="card-body" v-show="memoryExpanded">
        <div v-if="memory.enabled && memory.items && memory.items.length" class="memory-list">
          <div class="memory-item" v-for="item in memory.items.slice(0, 8)" :key="item.id">
            <div class="memory-item-head">
              <span class="memory-cat" :class="item.category || 'fact'">{{ item.categoryLabel || (item.category === 'preference' ? '偏好' : item.category === 'goal' ? '目标' : '事实') }}</span>
              <button class="memory-del" @click="onDeleteMemory(item.id)" title="删除这条记忆">✕</button>
            </div>
            <div class="memory-content">{{ item.content }}</div>
          </div>
        </div>
        <div v-else-if="memory.enabled" class="memory-empty">还没有记忆。在对话中说出偏好/事实（如「我喜欢简洁回答」）即可自动记录。</div>
        <div v-else class="memory-empty">记忆已关闭。</div>
        <div v-if="memory.count > 8" class="memory-more">仅显示前 8 条，共 {{ memory.count }} 条</div>
        <div class="memory-actions" v-if="memory.enabled && memory.count">
          <button class="mem-clear-btn" @click="onClearMemory">清空记忆</button>
        </div>
      </div>
    </div>

    <!-- 服务状态 -->
    <div class="panel-card">
      <div class="card-header" @click="statusExpanded = !statusExpanded">
        <div class="card-title-group">
          <span class="card-arrow" :class="{ expanded: statusExpanded }">▶</span>
          <span class="card-icon">🛡️</span>
          <div class="card-titles">
            <span class="card-title">Service</span>
            <span class="card-sub">MCP · 代码沙箱</span>
          </div>
        </div>
      </div>

      <div class="card-body" v-show="statusExpanded">
        <!-- MCP 状态 -->
        <div class="status-row">
          <div class="status-info">
            <span class="status-icon">🔌</span>
            <span class="status-name">MCP</span>
          </div>
          <div class="status-right">
            <span class="status-badge" :class="mcpStatus.enabled ? 'on' : 'off'">
              {{ mcpStatus.enabled ? '已启用' : '未启用' }}
            </span>
            <span class="status-count" v-if="mcpStatus.enabled">{{ mcpStatus.toolCount }} 工具</span>
          </div>
        </div>
        <div class="server-list" v-if="mcpStatus.enabled && mcpStatus.servers?.length">
          <div class="server-item" v-for="s in mcpStatus.servers" :key="s.name">
            <span class="server-dot"></span>
            <span class="server-item-name">{{ s.name }}</span>
            <span class="server-item-type">{{ s.type }}</span>
          </div>
        </div>

        <!-- 代码沙箱状态 -->
        <div class="status-row">
          <div class="status-info">
            <span class="status-icon">🐳</span>
            <span class="status-name">代码沙箱</span>
          </div>
          <div class="status-right">
            <span class="status-badge" :class="sandboxStatus.enabled ? 'on' : 'off'">
              {{ sandboxStatus.enabled ? '可用' : '不可用' }}
            </span>
          </div>
        </div>
        <div class="sandbox-detail" v-if="sandboxStatus.enabled">
          <div class="detail-block">
            <span class="detail-label">支持语言</span>
            <div class="lang-chips">
              <span class="lang-chip" v-for="lang in sandboxStatus.supportedLanguages" :key="lang">{{ lang }}</span>
            </div>
          </div>
          <div class="detail-row" v-if="sandboxStatus.config">
            <span class="detail-label">资源限制</span>
            <span class="detail-value">{{ sandboxStatus.config.memoryMb }}MB · {{ sandboxStatus.config.cpus }}核 · {{ sandboxStatus.config.timeoutSeconds }}s</span>
          </div>
          <div class="detail-row" v-if="sandboxStatus.config">
            <span class="detail-label">输出上限</span>
            <span class="detail-value">{{ sandboxStatus.config.maxOutputKb }}KB</span>
          </div>
        </div>
        <div class="hint-text" v-if="!sandboxStatus.enabled">
          启动 Docker（OrbStack）后自动可用
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

// Runtime 面板
const tools = ref([])
const mcpStatus = ref({ enabled: false, toolCount: 0, servers: [] })
const sandboxStatus = ref({ enabled: false, supportedLanguages: [], config: null })
const skills = ref([])
const skillsEnabled = ref(false)

// 最近一次知识库检索链路
const trace = ref(null)
let traceTimer = null

// 折叠状态
const toolsExpanded = ref(false)
const mcpExpanded = ref(false)
const skillsExpanded = ref(false)
const ragExpanded = ref(true)
const memoryExpanded = ref(false)
const statusExpanded = ref(true)

// 长期记忆
const memory = ref({ enabled: true, count: 0, items: [] })

const localToolCount = computed(() => tools.value.filter(t => !t.fromMcp).length)
const localTools = computed(() => tools.value.filter(t => !t.fromMcp))
const mcpTools = computed(() => tools.value.filter(t => t.fromMcp))
const hasRagTool = computed(() => tools.value.some(t => t.name === 'search_knowledge'))

// 按服务器分组 MCP 工具
const mcpServers = computed(() => {
  const serverMap = {}
  mcpTools.value.forEach(tool => {
    const serverName = tool.mcpServer || 'unknown'
    if (!serverMap[serverName]) {
      const serverInfo = (mcpStatus.value.servers || []).find(s => s.name === serverName)
      serverMap[serverName] = {
        name: serverName,
        type: serverInfo?.type || 'stdio',
        description: serverInfo?.description || '',
        tools: []
      }
    }
    serverMap[serverName].tools.push(tool)
  })
  return Object.values(serverMap)
})

async function loadTools() {
  try {
    const res = await fetch('/api/tools')
    if (res.ok) {
      const data = await res.json()
      tools.value = data.tools || []
    }
  } catch (e) {
    console.error('加载工具列表失败', e)
  }
}

async function loadMcpStatus() {
  try {
    const res = await fetch('/api/mcp/status')
    if (res.ok) {
      mcpStatus.value = await res.json()
    }
  } catch (e) {
    console.error('加载 MCP 状态失败', e)
  }
}

async function loadSandboxStatus() {
  try {
    const res = await fetch('/api/sandbox/status')
    if (res.ok) {
      sandboxStatus.value = await res.json()
    }
  } catch (e) {
    console.error('加载沙箱状态失败', e)
  }
}

async function loadSkills() {
  try {
    const res = await fetch('/api/skills')
    if (res.ok) {
      const data = await res.json()
      skills.value = data.skills || []
      skillsEnabled.value = data.enabled || false
    }
  } catch (e) {
    console.error('加载技能列表失败', e)
  }
}

// 轮询最近一次知识库检索链路
async function loadTrace() {
  try {
    const res = await fetch('/rag/search-trace')
    if (res.ok) {
      const data = await res.json()
      if (data.exists) trace.value = data
    }
  } catch (e) {
    // 忽略瞬时失败，等待下轮轮询
  }
}

// ==================== 长期记忆 ====================

// 加载记忆列表与状态
async function loadMemory() {
  try {
    const res = await fetch('/api/memory')
    if (res.ok) {
      const data = await res.json()
      memory.value = {
        enabled: !!data.enabled,
        count: data.count || 0,
        items: data.items || []
      }
    }
  } catch (e) {
    console.error('加载记忆失败', e)
  }
}

// 开关记忆
async function onToggleMemory(enabled) {
  try {
    await fetch('/api/memory/toggle', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled })
    })
    memory.value.enabled = enabled
    if (!enabled) {
      memory.value.items = []
      memory.value.count = 0
    } else {
      loadMemory()
    }
  } catch (e) {
    console.error('切换记忆失败', e)
  }
}

// 删除单条记忆
async function onDeleteMemory(id) {
  try {
    await fetch('/api/memory/' + encodeURIComponent(id), { method: 'DELETE' })
    loadMemory()
  } catch (e) {
    console.error('删除记忆失败', e)
  }
}

// 清空记忆
async function onClearMemory() {
  try {
    await fetch('/api/memory/clear', { method: 'POST' })
    loadMemory()
  } catch (e) {
    console.error('清空记忆失败', e)
  }
}

onMounted(() => {
  loadTools()
  loadMcpStatus()
  loadSandboxStatus()
  loadSkills()
  loadTrace()
  loadMemory()
  traceTimer = setInterval(loadTrace, 2500)
})

onBeforeUnmount(() => {
  if (traceTimer) clearInterval(traceTimer)
  traceTimer = null
})
</script>

<style scoped>
.runtime-panel {
  width: 240px;
  min-width: 240px;
  height: 100%;
  background: #fafbfc;
  border-left: 1px solid #e5e7eb;
  padding: 12px 10px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 面板头部 */
.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 12px;
  border-bottom: 1px solid #e5e7eb;
}
.panel-icon { font-size: 18px; }
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}
.panel-subtitle {
  margin-left: auto;
  font-size: 11px;
  color: #9ca3af;
}

/* 卡片：禁止被 flex 压缩，内容超出时面板自然滚动 */
.panel-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  flex-shrink: 0;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}
.card-header:hover { background: #f9fafb; }
.card-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.card-titles {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.card-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}
.card-sub {
  font-size: 10px;
  color: #9ca3af;
  white-space: nowrap;
}
.card-arrow {
  font-size: 9px;
  color: #9ca3af;
  transition: transform 0.2s;
  display: inline-block;
}
.card-arrow.expanded { transform: rotate(90deg); }
.card-icon { font-size: 14px; }
.card-stats {
  display: flex;
  gap: 4px;
}
.stat-badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 500;
}
.stat-badge.local { background: #eff6ff; color: #2563eb; }
.stat-badge.mcp { background: #ecfdf5; color: #059669; }
.count-badge {
  font-size: 11px;
  font-weight: 600;
  color: #6b7280;
  background: #f3f4f6;
  padding: 2px 8px;
  border-radius: 10px;
}

.card-body {
  padding: 8px 12px 12px;
  border-top: 1px solid #f3f4f6;
}

/* 工具分组 */
.tool-group { margin-bottom: 12px; }
.tool-group:last-child { margin-bottom: 0; }
.group-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #6b7280;
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}
.group-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.group-dot.local { background: #3b82f6; }
.group-dot.mcp { background: #10b981; }
.group-dot.rag { background: #8b5cf6; }
.group-count {
  margin-left: auto;
  font-size: 10px;
  color: #9ca3af;
  font-weight: 500;
}

/* 最近知识库检索链路 */
.rag-trace-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rag-trace-query {
  font-size: 11px;
  font-weight: 600;
  color: #4b5563;
  word-break: break-all;
  line-height: 1.4;
}
.rag-trace-flow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 10px;
}
.flow-node {
  background: #f5f3ff;
  color: #6d28d9;
  padding: 2px 7px;
  border-radius: 4px;
  font-weight: 600;
  font-family: 'SF Mono', Monaco, monospace;
}
.flow-node.warn {
  background: #fef3c7;
  color: #b45309;
}
.flow-arrow {
  color: #c4b5fd;
  font-size: 11px;
}
.rag-trace-frags {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rag-trace-frag {
  background: #fafafb;
  border: 1px solid #eef0f3;
  border-radius: 6px;
  padding: 6px 8px;
}
.rag-trace-frag-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 3px;
}
.rag-frag-source {
  font-size: 10px;
  font-weight: 600;
  color: #8b5cf6;
  font-family: 'SF Mono', Monaco, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rag-frag-score {
  font-size: 10px;
  font-weight: 600;
  color: #059669;
  font-family: 'SF Mono', Monaco, monospace;
  flex-shrink: 0;
}
.rag-frag-snippet {
  font-size: 10px;
  color: #6b7280;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.rag-trace-empty {
  font-size: 10px;
  color: #9ca3af;
  font-style: italic;
  padding: 2px 0;
}

/* 工具网格 - chip 样式 */
.tool-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.tool-chip {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  background: #f3f4f6;
  border-radius: 4px;
  cursor: help;
  transition: all 0.15s;
  border: 1px solid transparent;
}
.tool-chip:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
}
.tool-chip.mcp { background: #ecfdf5; }
.tool-chip.mcp:hover {
  background: #d1fae5;
  border-color: #6ee7b7;
}
.tool-chip-name {
  font-size: 10px;
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
  color: #374151;
  font-weight: 500;
}

/* MCP 服务器块 */
.mcp-server-block {
  margin-bottom: 8px;
}
.mcp-server-block:last-child { margin-bottom: 0; }
.mcp-server-tag {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}
.server-name {
  font-size: 10px;
  font-weight: 600;
  color: #059669;
  font-family: 'SF Mono', Monaco, monospace;
}
.server-type {
  font-size: 9px;
  color: #9ca3af;
  background: #f3f4f6;
  padding: 1px 4px;
  border-radius: 3px;
}

/* 长期记忆 */
.mem-toggle {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  margin-left: auto;
}
.mem-toggle input {
  display: none;
}
.toggle-slider {
  width: 30px;
  height: 16px;
  border-radius: 10px;
  background: #d1d5db;
  position: relative;
  transition: background 0.2s;
  flex-shrink: 0;
}
.toggle-slider::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #fff;
  transition: left 0.2s;
  box-shadow: 0 1px 2px rgba(0,0,0,0.2);
}
.toggle-slider.on {
  background: #3b82f6;
}
.toggle-slider.on::after {
  left: 16px;
}
.memory-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.memory-item {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 5px 7px;
}
.memory-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}
.memory-cat {
  font-size: 9px;
  font-weight: 600;
  color: #1d4ed8;
  background: #dbeafe;
  border-radius: 3px;
  padding: 1px 5px;
}
.memory-cat.fact { color: #0f766e; background: #ccfbf1; }
.memory-cat.goal { color: #7c3aed; background: #ede9fe; }
.memory-del {
  border: none;
  background: transparent;
  color: #9ca3af;
  font-size: 10px;
  cursor: pointer;
  padding: 0 2px;
  line-height: 1;
}
.memory-del:hover { color: #ef4444; }
.memory-content {
  font-size: 11px;
  color: #334155;
  margin-top: 2px;
  line-height: 1.5;
}
.memory-empty {
  font-size: 11px;
  color: #9ca3af;
  padding: 4px 2px;
  line-height: 1.6;
}
.memory-more {
  font-size: 10px;
  color: #9ca3af;
  margin-top: 4px;
}
.memory-actions {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
}
.mem-clear-btn {
  border: 1px solid #fecaca;
  background: #fef2f2;
  color: #dc2626;
  font-size: 10px;
  border-radius: 4px;
  padding: 3px 10px;
  cursor: pointer;
}
.mem-clear-btn:hover { background: #fee2e2; }

/* Skills 列表 */
.skill-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.skill-item {
  display: flex;
  gap: 8px;
  padding: 8px;
  background: #faf5ff;
  border-radius: 6px;
  border: 1px solid #ede9fe;
  cursor: help;
  transition: all 0.15s;
}
.skill-item:hover {
  background: #f3e8ff;
  border-color: #c4b5fd;
}
.skill-icon { font-size: 14px; flex-shrink: 0; }
.skill-info { flex: 1; min-width: 0; }
.skill-name {
  font-size: 12px;
  font-weight: 600;
  color: #6d28d9;
  font-family: 'SF Mono', Monaco, monospace;
  margin-bottom: 2px;
}
.skill-desc {
  font-size: 10px;
  color: #7c3aed;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 服务状态 */
.status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f3f4f6;
}
.status-row:last-child { border-bottom: none; }
.status-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.status-icon { font-size: 14px; }
.status-name {
  font-size: 12px;
  font-weight: 500;
  color: #374151;
}
.status-right {
  display: flex;
  align-items: center;
  gap: 6px;
}
.status-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
}
.status-badge.on { background: #dcfce7; color: #16a34a; }
.status-badge.off { background: #fee2e2; color: #dc2626; }
.status-count {
  font-size: 10px;
  color: #9ca3af;
}

/* 服务器列表 */
.server-list {
  padding: 6px 0 6px 22px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.server-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
}
.server-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #10b981;
}
.server-item-name {
  color: #374151;
  font-family: 'SF Mono', Monaco, monospace;
  font-weight: 500;
}
.server-item-type {
  color: #9ca3af;
  margin-left: auto;
}

/* 沙箱详情 */
.sandbox-detail {
  padding: 6px 0 6px 22px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.detail-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.lang-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.lang-chip {
  font-size: 10px;
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
  font-weight: 500;
  color: #374151;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  padding: 2px 7px;
  border-radius: 4px;
}
.lang-chip:nth-child(5n + 1) { background: #eff6ff; border-color: #bfdbfe; color: #2563eb; }
.lang-chip:nth-child(5n + 2) { background: #ecfdf5; border-color: #a7f3d0; color: #059669; }
.lang-chip:nth-child(5n + 3) { background: #fefce8; border-color: #fde68a; color: #a16207; }
.lang-chip:nth-child(5n + 4) { background: #fdf2f8; border-color: #fbcfe8; color: #be185d; }
.lang-chip:nth-child(5n + 5) { background: #f5f3ff; border-color: #ddd6fe; color: #6d28d9; }
.detail-row {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
}
.detail-label { color: #9ca3af; }
.detail-value {
  color: #374151;
  font-weight: 500;
  font-family: 'SF Mono', Monaco, monospace;
}

.hint-text {
  font-size: 10px;
  color: #9ca3af;
  padding: 6px 0 6px 22px;
  font-style: italic;
}
.empty-hint {
  font-size: 11px;
  color: #9ca3af;
  font-style: italic;
  text-align: center;
  padding: 12px 0;
}
</style>
