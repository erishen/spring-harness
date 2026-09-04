<template>
  <aside class="runtime-panel">
    <!-- 面板头部 -->
    <div class="panel-header">
      <span class="panel-icon">⚙️</span>
      <span class="panel-title">运行时面板</span>
      <span class="panel-subtitle">Tools · MCP · Skills</span>
    </div>

    <!-- Tools -->
    <CollapsibleCard title="Tools" :subtitle="localToolCount + ' 个本地工具'" icon="🔧">
      <div class="tool-group" v-if="localTools.length">
        <div class="group-label">
          <span class="group-dot local"></span>本地工具
          <span class="group-count">{{ localTools.length }}</span>
        </div>
        <div class="tool-grid">
          <div class="tool-chip" v-for="tool in localTools" :key="tool.name" :title="tool.description">
            <span class="tool-chip-name">{{ tool.name }}</span>
          </div>
        </div>
      </div>
      <div class="empty-hint" v-if="!localTools.length">加载中...</div>
    </CollapsibleCard>

    <!-- MCP -->
    <CollapsibleCard v-if="mcpTools.length" title="MCP" :subtitle="mcpStatus.toolCount + ' 个工具'" icon="🔌">
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
    </CollapsibleCard>

    <!-- Knowledge (最近知识库检索) -->
    <CollapsibleCard v-if="hasRagTool" title="Knowledge" subtitle="最近知识库检索" icon="📚" :default-expanded="true">
      <div v-if="trace && trace.exists" class="rag-trace-body">
        <div class="rag-trace-query">🔍 {{ trace.trace.query }}</div>
        <div class="rag-trace-flow">
          <span class="flow-node">Query</span>
          <span class="flow-arrow">→</span>
          <span class="flow-node">Embedding</span>
          <span class="flow-arrow">→</span>
          <span class="flow-node" :class="{ warn: trace.trace.reranked }">Rerank</span>
          <span class="flow-arrow">→</span>
          <span class="flow-node">{{ trace.trace.fragmentCount }} 片段</span>
        </div>
        <div v-if="trace.trace.fragments && trace.trace.fragments.length" class="rag-trace-frags">
          <div v-for="(f, fi) in trace.trace.fragments.slice(0, 3)" :key="fi" class="rag-trace-frag">
            <div class="rag-trace-frag-head">
              <span class="rag-frag-source">{{ f.source }}</span>
              <span class="rag-frag-score">{{ f.score ? f.score.toFixed(3) : '—' }}</span>
            </div>
            <div class="rag-frag-snippet">{{ f.snippet }}</div>
          </div>
        </div>
      </div>
      <div v-else class="rag-trace-empty">尚未执行知识库检索</div>
    </CollapsibleCard>

    <!-- Skills -->
    <CollapsibleCard v-if="skillsEnabled" title="Skills" :subtitle="skills.length + ' 个技能'" icon="📦">
      <div class="skill-list">
        <div class="skill-item" v-for="skill in skills" :key="skill.name">
          <div class="skill-head">
            <span class="skill-name">{{ skill.name }}</span>
            <span class="skill-trigger" v-if="skill.trigger">{{ skill.trigger }}</span>
          </div>
          <div class="skill-desc" v-if="skill.description">{{ skill.description }}</div>
        </div>
      </div>
    </CollapsibleCard>

    <!-- Memory -->
    <CollapsibleCard title="Memory" :subtitle="memory.enabled ? '长期记忆 · ' + memory.count + ' 条' : '已关闭'" icon="💾">
      <template #header>
        <label class="mem-toggle" @click.stop :title="memory.enabled ? '关闭记忆' : '开启记忆'">
          <input type="checkbox" :checked="memory.enabled" @change="onToggleMemory($event.target.checked)" />
          <span class="toggle-slider" :class="{ on: memory.enabled }"></span>
        </label>
      </template>

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
    </CollapsibleCard>

    <!-- Service (MCP + 沙箱状态) -->
    <CollapsibleCard title="Service" subtitle="MCP · 代码沙箱" icon="🛡️" :default-expanded="true">
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
    </CollapsibleCard>
  </aside>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import CollapsibleCard from './CollapsibleCard.vue'

// Runtime 面板数据
const tools = ref([])
const mcpStatus = ref({ enabled: false, toolCount: 0, servers: [] })
const sandboxStatus = ref({ enabled: false, supportedLanguages: [], config: null })
const skills = ref([])
const skillsEnabled = ref(false)

// 最近一次知识库检索链路
const trace = ref(null)
let traceTimer = null

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
  } catch (e) { console.error('加载工具列表失败', e) }
}

async function loadMcpStatus() {
  try {
    const res = await fetch('/api/mcp/status')
    if (res.ok) mcpStatus.value = await res.json()
  } catch (e) { console.error('加载 MCP 状态失败', e) }
}

async function loadSandboxStatus() {
  try {
    const res = await fetch('/api/sandbox/status')
    if (res.ok) sandboxStatus.value = await res.json()
  } catch (e) { console.error('加载沙箱状态失败', e) }
}

async function loadSkills() {
  try {
    const res = await fetch('/api/skills')
    if (res.ok) {
      const data = await res.json()
      skills.value = data.skills || []
      skillsEnabled.value = data.enabled || false
    }
  } catch (e) { console.error('加载技能列表失败', e) }
}

async function loadTrace() {
  try {
    const res = await fetch('/rag/search-trace')
    if (res.ok) {
      const data = await res.json()
      if (data.exists) trace.value = data
    }
  } catch (e) { /* 忽略瞬时失败 */ }
}

// ==================== 长期记忆 ====================
async function loadMemory() {
  try {
    const res = await fetch('/api/memory')
    if (res.ok) {
      const data = await res.json()
      memory.value = { enabled: !!data.enabled, count: data.count || 0, items: data.items || [] }
    }
  } catch (e) { console.error('加载记忆失败', e) }
}

async function onToggleMemory(enabled) {
  try {
    await fetch('/api/memory/toggle', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled })
    })
    memory.value.enabled = enabled
    if (!enabled) { memory.value.items = []; memory.value.count = 0 }
    else loadMemory()
  } catch (e) { console.error('切换记忆失败', e) }
}

async function onDeleteMemory(id) {
  try {
    await fetch('/api/memory/' + encodeURIComponent(id), { method: 'DELETE' })
    loadMemory()
  } catch (e) { console.error('删除记忆失败', e) }
}

async function onClearMemory() {
  try {
    await fetch('/api/memory/clear', { method: 'POST' })
    loadMemory()
  } catch (e) { console.error('清空记忆失败', e) }
}

onMounted(() => {
  loadTools(); loadMcpStatus(); loadSandboxStatus(); loadSkills(); loadTrace(); loadMemory()
  traceTimer = setInterval(loadTrace, 2500)
})

onBeforeUnmount(() => {
  if (traceTimer) clearInterval(traceTimer)
  traceTimer = null
})
</script>

<style scoped>
.runtime-panel {
  width: 250px;
  min-width: 250px;
  flex-shrink: 0;
  height: 100%;
  background: #fafbfc;
  border-left: 1px solid #e5e7eb;
  padding: 12px 10px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.runtime-panel::-webkit-scrollbar { width: 6px; }
.runtime-panel::-webkit-scrollbar-thumb { background: transparent; border-radius: 3px; }
.runtime-panel:hover::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.15); }

/* 面板头部 */
.panel-header {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 8px 12px; border-bottom: 1px solid #e5e7eb;
  flex-wrap: nowrap;
}
.panel-icon { font-size: 18px; flex-shrink: 0; }
.panel-title { font-size: 15px; font-weight: 600; color: #111827; flex-shrink: 0; white-space: nowrap; }
.panel-subtitle { margin-left: auto; font-size: 11px; color: #9ca3af; white-space: nowrap; flex-shrink: 0; }

.empty-hint { font-size: 11px; color: #9ca3af; padding: 4px 0; }

/* 工具分组 */
.tool-group { margin-bottom: 12px; }
.tool-group:last-child { margin-bottom: 0; }
.group-label {
  display: flex; align-items: center; gap: 6px;
  font-size: 11px; font-weight: 600; color: #6b7280;
  margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.3px;
}
.group-dot { width: 6px; height: 6px; border-radius: 50%; }
.group-dot.local { background: #3b82f6; }
.group-dot.mcp { background: #10b981; }
.group-count { margin-left: auto; font-size: 10px; color: #9ca3af; font-weight: 500; }

/* 工具网格 */
.tool-grid { display: flex; flex-wrap: wrap; gap: 4px; }
.tool-chip {
  display: inline-flex; align-items: center; padding: 3px 8px;
  background: #f3f4f6; border-radius: 4px; cursor: help;
  transition: all 0.15s; border: 1px solid transparent;
  max-width: 100%;
}
.tool-chip:hover { background: #eff6ff; border-color: #bfdbfe; }
.tool-chip.mcp { background: #ecfdf5; }
.tool-chip.mcp:hover { background: #d1fae5; border-color: #6ee7b7; }
.tool-chip-name {
  font-size: 10px; font-family: 'SF Mono', Monaco, monospace;
  color: #374151; font-weight: 500;
  word-break: break-all;
  line-height: 1.3;
}

/* MCP 服务器块 */
.mcp-server-block { margin-bottom: 8px; }
.mcp-server-block:last-child { margin-bottom: 0; }
.mcp-server-tag { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.server-name { font-size: 10px; font-weight: 600; color: #059669; font-family: 'SF Mono', Monaco, monospace; }
.server-type { font-size: 9px; color: #9ca3af; background: #f3f4f6; padding: 1px 4px; border-radius: 3px; }

/* 知识库检索链路 */
.rag-trace-body { display: flex; flex-direction: column; gap: 8px; }
.rag-trace-query { font-size: 11px; font-weight: 600; color: #4b5563; word-break: break-all; line-height: 1.4; }
.rag-trace-flow { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; font-size: 10px; }
.flow-node { background: #f5f3ff; color: #6d28d9; padding: 2px 7px; border-radius: 4px; font-weight: 600; font-family: 'SF Mono', Monaco, monospace; }
.flow-node.warn { background: #fef3c7; color: #b45309; }
.flow-arrow { color: #c4b5fd; font-size: 11px; }
.rag-trace-frags { display: flex; flex-direction: column; gap: 6px; }
.rag-trace-frag { background: #fafafb; border: 1px solid #eef0f3; border-radius: 6px; padding: 6px 8px; }
.rag-trace-frag-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-bottom: 3px; }
.rag-frag-source { font-size: 10px; font-weight: 600; color: #8b5cf6; font-family: 'SF Mono', Monaco, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rag-frag-score { font-size: 10px; font-weight: 600; color: #059669; font-family: 'SF Mono', Monaco, monospace; flex-shrink: 0; }
.rag-frag-snippet { font-size: 10px; color: #6b7280; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.rag-trace-empty { font-size: 10px; color: #9ca3af; font-style: italic; padding: 2px 0; }

/* Skills */
.skill-list { display: flex; flex-direction: column; gap: 6px; }
.skill-item { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px 8px; }
.skill-head { display: flex; align-items: center; justify-content: space-between; gap: 4px; }
.skill-name { font-size: 11px; font-weight: 600; color: #1e40af; font-family: 'SF Mono', Monaco, monospace; }
.skill-trigger { font-size: 9px; color: #9ca3af; background: #f3f4f6; padding: 1px 4px; border-radius: 3px; }
.skill-desc { font-size: 10px; color: #6b7280; margin-top: 2px; line-height: 1.4; }

/* Memory */
.mem-toggle { display: inline-flex; align-items: center; cursor: pointer; }
.mem-toggle input { display: none; }
.toggle-slider {
  width: 30px; height: 16px; border-radius: 10px; background: #d1d5db;
  position: relative; transition: background 0.2s; flex-shrink: 0;
}
.toggle-slider::after {
  content: ''; position: absolute; top: 2px; left: 2px;
  width: 12px; height: 12px; border-radius: 50%; background: #fff;
  transition: left 0.2s; box-shadow: 0 1px 2px rgba(0,0,0,0.2);
}
.toggle-slider.on { background: #3b82f6; }
.toggle-slider.on::after { left: 16px; }
.memory-list { display: flex; flex-direction: column; gap: 6px; }
.memory-item { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px 8px; }
.memory-item-head { display: flex; align-items: center; justify-content: space-between; gap: 4px; }
.memory-cat { font-size: 9px; font-weight: 600; padding: 1px 5px; border-radius: 3px; background: #eff6ff; color: #2563eb; }
.memory-cat.preference { background: #fdf4ff; color: #a21caf; }
.memory-cat.goal { background: #f0fdf4; color: #15803d; }
.memory-del { font-size: 10px; color: #9ca3af; background: none; border: none; cursor: pointer; padding: 0 2px; }
.memory-del:hover { color: #ef4444; }
.memory-content { font-size: 10.5px; color: #374151; margin-top: 2px; line-height: 1.4; word-break: break-all; }
.memory-empty { font-size: 10px; color: #9ca3af; padding: 4px 0; line-height: 1.5; }
.memory-more { font-size: 9px; color: #9ca3af; text-align: center; padding: 4px 0; }
.memory-actions { margin-top: 6px; text-align: center; }
.mem-clear-btn { font-size: 10px; padding: 3px 10px; border: 1px solid #fecaca; border-radius: 4px; background: #fff; color: #ef4444; cursor: pointer; }
.mem-clear-btn:hover { background: #fef2f2; }

/* Service 状态 */
.status-row { display: flex; align-items: center; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid #f3f4f6; }
.status-row:last-child { border-bottom: none; }
.status-info { display: flex; align-items: center; gap: 6px; }
.status-icon { font-size: 13px; }
.status-name { font-size: 12px; font-weight: 500; color: #374151; }
.status-right { display: flex; align-items: center; gap: 6px; }
.status-badge { font-size: 10px; padding: 2px 8px; border-radius: 10px; font-weight: 600; }
.status-badge.on { background: #d1fae5; color: #059669; }
.status-badge.off { background: #f3f4f6; color: #9ca3af; }
.status-count { font-size: 10px; color: #6b7280; }
.server-list { display: flex; flex-direction: column; gap: 2px; padding: 4px 0 8px; }
.server-item { display: flex; align-items: center; gap: 6px; padding: 2px 0; }
.server-dot { width: 5px; height: 5px; border-radius: 50%; background: #10b981; }
.server-item-name { font-size: 10px; color: #374151; font-family: 'SF Mono', Monaco, monospace; }
.server-item-type { font-size: 9px; color: #9ca3af; margin-left: auto; }

/* 沙箱详情 */
.sandbox-detail { padding: 8px 0 4px; }
.detail-block { margin-bottom: 8px; }
.detail-label { display: block; font-size: 10px; color: #6b7280; font-weight: 600; margin-bottom: 4px; }
.lang-chips { display: flex; flex-wrap: wrap; gap: 4px; }
.lang-chip { font-size: 10px; padding: 2px 7px; background: #eff6ff; color: #2563eb; border-radius: 4px; font-family: 'SF Mono', Monaco, monospace; font-weight: 500; }
.detail-row { display: flex; align-items: center; justify-content: space-between; padding: 3px 0; }
.detail-value { font-size: 10.5px; color: #374151; font-family: 'SF Mono', Monaco, monospace; }
.hint-text { font-size: 10px; color: #9ca3af; font-style: italic; padding: 6px 0 2px; }
</style>
