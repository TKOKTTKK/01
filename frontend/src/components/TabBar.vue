<template>
  <nav class="tabbar">
    <router-link v-for="t in tabs" :key="t.path" :to="t.path" class="tab"
      :class="{ active: isActive(t.path) }">
      <span class="icon" v-html="t.icon"></span>
      <span class="label">{{ t.label }}</span>
    </router-link>
  </nav>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
const isActive = (p: string) => route.path === p

const tabs = [
  { path: '/', label: '首页', icon: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/></svg>' },
  { path: '/market', label: '行情', icon: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 20V10M10 20V4M16 20v-8M22 20H2"/></svg>' },
  { path: '/watchlist', label: '自选', icon: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="m12 3 2.9 5.9 6.1.9-4.5 4.4 1 6.3-5.5-3-5.5 3 1-6.3L3 9.8l6.1-.9z"/></svg>' },
  { path: '/mine', label: '我的', icon: '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="8" r="4"/><path d="M4 21c0-4 3.6-6.5 8-6.5s8 2.5 8 6.5"/></svg>' }
]
</script>

<style scoped>
.tabbar {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  height: calc(var(--tabbar-h) + var(--safe-bottom));
  padding-bottom: var(--safe-bottom);
  background: rgba(19, 25, 34, .92);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-top: 1px solid var(--border);
  display: flex;
  z-index: 100;
}
.tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--text-3);
  text-decoration: none;
  font-size: 10.5px;
  transition: color .15s;
}
.tab.active { color: var(--accent); }
.icon { display: flex; }
</style>
