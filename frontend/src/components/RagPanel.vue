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
        <button class="doc-delete" @click="deleteDocument(doc.docId)">删除</button>
      </div>
    </div>
    <div v-else class="rag-empty">暂无文档，上传后即可基于知识库问答</div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'

// RAG 文档管理
const documents = ref([])
const uploading = ref(false)

const totalChunks = computed(() => documents.value.reduce((sum, d) => sum + d.chunkCount, 0))

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
.doc-delete {
  padding: 3px 10px;
  font-size: 11px;
  color: #ef4444;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
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
</style>
