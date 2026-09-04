<template>
  <div class="chat-container" ref="chatContainer">
    <MessageItem
      v-for="(msg, index) in messages"
      :key="index"
      :msg="msg"
      :is-last-ai="isLastAiMessage(index)"
      @regenerate="$emit('regenerate')"
    />
  </div>
</template>

<script setup>
import { ref, nextTick, watch, onMounted } from 'vue'
import MessageItem from './MessageItem.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] }
})

defineEmits(['regenerate'])

const chatContainer = ref(null)

/** 判断是否是最后一条 AI 消息（用于显示重新生成按钮） */
function isLastAiMessage(index) {
  for (let i = props.messages.length - 1; i >= 0; i--) {
    if (props.messages[i].role === 'ai') {
      return i === index
    }
  }
  return false
}

/** 自动滚动到底部（immediate=true 时立即滚动，无动画） */
function scrollToBottom(immediate = false) {
  nextTick(() => {
    if (chatContainer.value) {
      if (immediate) {
        // 临时关闭平滑滚动，确保立即到位
        const prevBehavior = chatContainer.value.style.scrollBehavior
        chatContainer.value.style.scrollBehavior = 'auto'
        chatContainer.value.scrollTop = chatContainer.value.scrollHeight
        // 恢复平滑滚动
        requestAnimationFrame(() => {
          chatContainer.value.style.scrollBehavior = prevBehavior
        })
      } else {
        chatContainer.value.scrollTop = chatContainer.value.scrollHeight
      }
    }
  })
}

// 消息数量变化时自动滚动（新消息用平滑动画）
watch(() => props.messages.length, (newLen, oldLen) => {
  // 初始加载（从0变多）时立即滚动，无动画
  scrollToBottom(oldLen === 0)
})

// 深度监听消息内容变化（流式输出时 content 不断更新，需要持续滚动）
watch(() => props.messages.map(m => m.content?.length || 0).join(','), () => {
  // 只有最后一条是 AI 消息且正在输出时才自动滚动
  const lastMsg = props.messages[props.messages.length - 1]
  if (lastMsg && lastMsg.role === 'ai' && !lastMsg.done) {
    scrollToBottom()
  }
})

onMounted(() => {
  // 延迟两帧，确保消息已从 localStorage 加载并渲染
  requestAnimationFrame(() => {
    requestAnimationFrame(() => scrollToBottom(true))
  })
})

defineExpose({ scrollToBottom })
</script>

<style scoped>
.chat-container {
  flex: 1;
  overflow-y: auto;
  padding: 0;
  scroll-behavior: smooth;
}
/* 滚动条 hover 才显示 */
.chat-container::-webkit-scrollbar {
  width: 6px;
}
.chat-container::-webkit-scrollbar-track {
  background: transparent;
}
.chat-container::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 3px;
  transition: background 0.2s;
}
.chat-container:hover::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.15);
}
</style>
