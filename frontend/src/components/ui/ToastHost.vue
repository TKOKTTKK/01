<template>
  <div class="toast-host">
    <transition-group name="fade">
      <div v-for="t in ui.toasts" :key="t.id" class="toast" :class="t.type">{{ t.text }}</div>
    </transition-group>
  </div>
</template>

<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
const ui = useUiStore()
</script>
