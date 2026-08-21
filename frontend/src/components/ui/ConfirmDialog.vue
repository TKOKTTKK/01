<template>
  <transition name="fade">
    <div v-if="ui.confirmVisible" class="dialog-mask" @click.self="ui.answer(false)">
      <div class="dialog">
        <h3>{{ ui.confirmOpts.title }}</h3>
        <div v-for="(line, i) in ui.confirmOpts.lines || []" :key="i" class="dline">
          <span>{{ line.split('：')[0] }}</span>
          <b>{{ line.split('：')[1] || '' }}</b>
        </div>
        <div class="dbtns">
          <button class="cancel" @click="ui.answer(false)">取消</button>
          <button class="ok" :class="{ danger: ui.confirmOpts.danger }" @click="ui.answer(true)">
            {{ ui.confirmOpts.confirmText || '确认' }}
          </button>
        </div>
      </div>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { useUiStore } from '@/stores/ui'
const ui = useUiStore()
</script>
