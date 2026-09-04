<template>
  <div class="rag-panel">
    <div class="rag-upload">
      <label class="upload-btn" :class="{ disabled: uploading }">
        <input type="file" @change="handleFileSelect" accept=".txt,.md,.pdf,.json,.xml,.java,.py,.js,.ts" hidden />
        {{ uploading ? '上传中...' : '📎 上传文档' }}
      </label>
      <span class="rag-stats">
        {{ documents.length }} 个文档 · {{ totalChunks }} 个向量块
      </span>
    </div>
    <div v-if="documents.length" class="rag-doc-list">
      <div v-for="doc in documents" :key="doc.docId" class="rag-doc-item">
        <span class="doc-name">{{ doc.fileName }}</span>
        <span class="doc-meta">{{ doc.chunkCount }} 块 · {{ formatSize(doc.size) }} · {{ doc.uploadTime }}</span>
        <div class="doc-actions">
          <button class="doc-preview" @click="previewDocument(doc)">预览</button>
          <button class="doc-delete" @click="deleteDocument(doc.docId)">删除</button>
        </div>
      </div>
    </div>
    <div v-else class="rag-empty">暂无文档，上传后即可基于知识库问答</div>

    <!-- 文档预览浮层 -->
    <div v-if="previewDoc" class="preview-overlay" @click.self="closePreview">
      <div class="preview-panel">
        <div class="preview-header">
          <span class="preview-title">📄 {{ previewDoc.fileName }}</span>
          <span class="preview-meta">{{ previewDoc.chunkCount }} 个向量块 · {{ formatSize(previewDoc.size) }}</span>
          <button class="preview-close" @click="closePreview">✕</button>
        </div>
        <div v-if="previewLoading" class="preview-loading">加载中...</div>
        <div v-else-if="previewError" class="preview-error">{{ previewError }}</div>
        <div v-else class="preview-content" v-html="renderedContent"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { renderMarkdown } from '../utils/markdown'

// RAG 文档管理
const documents = ref([])
const uploading = ref(false)

// 文档预览
const previewDoc = ref(null)
const previewContent = ref('')
const previewLoading = ref(false)
const previewError = ref('')

const totalChunks = computed(() => documents.value.reduce((sum, d) => sum + d.chunkCount, 0))

const renderedContent = computed(() => {
  if (!previewContent.value) return ''
  try {
    return renderMarkdown(previewContent.value)
  } catch {
    return `<pre>${previewContent.value}</pre>`
  }
})

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

async function loadDocuments() {
  try {
    const res = await fetch('/rag/documents')
    if (res.ok) {
      documents.value = await res.json()
    }
  } catch (e) {
    console.error('加载文档列表失败', e)
  }
}

async function handleFileSelect(event) {
  const file = event.target.files[0]
  if (!file) return
  await uploadDocument(file)
  event.target.value = ''
}

async function uploadDocument(file) {
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await fetch('/rag/documents', {
      method: 'POST',
      body: formData
    })
    if (res.ok) {
      await loadDocuments()
    } else {
      alert('上传失败：' + res.status)
    }
  } catch (e) {
    alert('上传失败：' + e.message)
  } finally {
    uploading.value = false
  }
}

async function deleteDocument(docId) {
  if (!confirm('确定删除该文档？')) return
  try {
    const res = await fetch('/rag/documents/' + docId, { method: 'DELETE' })
    if (res.ok) {
      await loadDocuments()
    }
  } catch (e) {
    console.error('删除失败', e)
  }
}

async function previewDocument(doc) {
  previewDoc.value = doc
  previewContent.value = ''
  previewLoading.value = true
  previewError.value = ''
  try {
    const res = await fetch('/rag/documents/' + doc.docId + '/content')
    if (res.ok) {
      const data = await res.json()
      previewContent.value = data.content || '（文档内容为空）'
    } else if (res.status === 404) {
      previewError.value = '该文档上传于预览功能上线前，暂无内容缓存。请重新上传文档以启用预览。'
    } else {
      previewError.value = '加载失败：' + res.status
    }
  } catch (e) {
    previewError.value = '加载失败：' + e.message
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  previewDoc.value = null
  previewContent.value = ''
  previewError.value = ''
}

onMounted(() => {
  loadDocuments()
})
</script>

<style scoped>
.rag-panel {
  padding: 12px 24px;
  background: #f8f9fb;
  border-bottom: 1px solid #e8eaed;
}
.rag-upload {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.upload-btn {
  display: inline-block;
  padding: 6px 14px;
  background: #4a6cf7;
  color: #fff;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}
.upload-btn:hover {
  background: #3b5de7;
}
.upload-btn.disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.rag-stats {
  font-size: 12px;
  color: #6b7280;
}
.rag-doc-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.rag-doc-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 10px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #eef0f3;
}
.doc-name {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-meta {
  font-size: 11px;
  color: #9ca3af;
  flex-shrink: 0;
}
.doc-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.doc-preview {
  padding: 3px 10px;
  font-size: 11px;
  color: #3b82f6;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
}
.doc-preview:hover {
  background: #dbeafe;
}
.doc-delete {
  padding: 3px 10px;
  font-size: 11px;
  color: #ef4444;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
}
.doc-delete:hover {
  background: #fee2e2;
}
.rag-empty {
  font-size: 12px;
  color: #9ca3af;
  text-align: center;
  padding: 4px;
}

/* 文档预览浮层 */
.preview-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(2px);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
}
.preview-panel {
  width: 720px;
  max-width: 90vw;
  max-height: 80vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.preview-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid #e5e7eb;
  background: #f9fafb;
}
.preview-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-meta {
  font-size: 11px;
  color: #9ca3af;
  flex-shrink: 0;
}
.preview-close {
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #9ca3af;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.preview-close:hover {
  background: #f3f4f6;
  color: #374151;
}
.preview-loading,
.preview-error {
  padding: 40px 20px;
  text-align: center;
  font-size: 13px;
  color: #6b7280;
}
.preview-error {
  color: #ef4444;
}
.preview-content {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px;
  font-size: 13px;
  line-height: 1.7;
  color: #374151;
}
.preview-content :deep(h1),
.preview-content :deep(h2),
.preview-content :deep(h3) {
  margin: 16px 0 8px;
  color: #1f2937;
}
.preview-content :deep(p) {
  margin: 8px 0;
}
.preview-content :deep(code) {
  background: #f3f4f6;
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
  font-family: 'SF Mono', Monaco, monospace;
}
.preview-content :deep(pre) {
  background: #1f2937;
  color: #e5e7eb;
  padding: 12px 14px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 10px 0;
}
.preview-content :deep(pre code) {
  background: none;
  padding: 0;
  color: inherit;
}
.preview-content :deep(blockquote) {
  border-left: 3px solid #d1d5db;
  padding-left: 12px;
  color: #6b7280;
  margin: 10px 0;
}
.preview-content :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 10px 0;
}
.preview-content :deep(th),
.preview-content :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 6px 10px;
  text-align: left;
  font-size: 12px;
}
.preview-content :deep(th) {
  background: #f9fafb;
  font-weight: 600;
}
.preview-content :deep(hr) {
  border: none;
  border-top: 1px solid #e5e7eb;
  margin: 16px 0;
}
</style>
