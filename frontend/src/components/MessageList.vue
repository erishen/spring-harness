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

/** 自动滚动到底部 */
function scrollToBottom() {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}

// 消息数量变化时自动滚动
watch(() => props.messages.length, () => scrollToBottom())

onMounted(() => scrollToBottom())

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
