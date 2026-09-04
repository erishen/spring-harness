<template>
  <div class="panel-card">
    <div class="card-header" @click="expanded = !expanded">
      <div class="card-title-group">
        <span class="card-arrow" :class="{ expanded }">▶</span>
        <span v-if="icon" class="card-icon">{{ icon }}</span>
        <div class="card-titles">
          <span class="card-title">{{ title }}</span>
          <span v-if="subtitle" class="card-sub">{{ subtitle }}</span>
        </div>
      </div>
      <slot name="header" />
    </div>
    <div class="card-body" v-show="expanded">
      <slot />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  icon: { type: String, default: '' },
  defaultExpanded: { type: Boolean, default: false }
})

const expanded = ref(props.defaultExpanded)
</script>

<style scoped>
.panel-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  margin-bottom: 8px;
  overflow: hidden;
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
.card-header:hover {
  background: #f9fafb;
}
.card-title-group {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.card-arrow {
  font-size: 10px;
  color: #9ca3af;
  transition: transform 0.2s;
  display: inline-block;
}
.card-arrow.expanded {
  transform: rotate(90deg);
}
.card-icon {
  font-size: 14px;
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
  color: #1f2937;
}
.card-sub {
  font-size: 11px;
  color: #9ca3af;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card-body {
  padding: 10px 12px;
  border-top: 1px solid #f3f4f6;
}
</style>
