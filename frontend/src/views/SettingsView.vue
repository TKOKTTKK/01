<template>
  <div class="page no-tab">
    <div class="nav">
      <button class="back" @click="$router.back()">‹</button>
      <div style="font-size:15px;font-weight:600">设置</div>
    </div>

    <div class="group-title">外观</div>
    <div class="card list">
      <div class="item col">
        <span>主题模式</span>
        <div class="seg">
          <button v-for="m in modes" :key="m.v" :class="{ active: settings.themeMode === m.v }"
            @click="settings.themeMode = m.v">{{ m.label }}</button>
        </div>
      </div>
      <div class="item col">
        <span>主题颜色</span>
        <div class="colors">
          <button v-for="c in colors" :key="c.v" class="dot" :style="{ background: c.hex }"
            :class="{ on: settings.themeColor === c.v }" @click="settings.themeColor = c.v"></button>
        </div>
      </div>
      <div class="item col">
        <span>涨跌颜色</span>
        <div class="seg">
          <button :class="{ active: settings.priceColorMode === 'red-up' }"
            @click="settings.priceColorMode = 'red-up'">红涨绿跌</button>
          <button :class="{ active: settings.priceColorMode === 'green-up' }"
            @click="settings.priceColorMode = 'green-up'">绿涨红跌</button>
        </div>
      </div>
    </div>

    <div class="group-title">数据</div>
    <div class="card list">
      <div class="item"><span>行情刷新频率</span><span class="val">自动（10 秒）</span></div>
      <div class="item" @click="onClearCache" style="cursor:pointer">
        <span>清除缓存</span><span class="arrow">›</span>
      </div>
    </div>

    <div class="group-title">其他</div>
    <div class="card list">
      <div class="item"><span>行情数据源</span><span class="mock-badge">模拟行情（非真实数据）</span></div>
      <div class="item"><span>关于</span><span class="val">简牛行情</span></div>
      <div class="item"><span>版本</span><span class="val">v2.0.0</span></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useSettingsStore, type ThemeColor, type ThemeMode } from '@/stores/settings'
import { useUiStore } from '@/stores/ui'

const settings = useSettingsStore()
const ui = useUiStore()

const modes: { v: ThemeMode; label: string }[] = [
  { v: 'system', label: '跟随系统' },
  { v: 'light', label: '浅色' },
  { v: 'dark', label: '深色' }
]
const colors: { v: ThemeColor; hex: string }[] = [
  { v: 'blue', hex: '#3f8cff' },
  { v: 'purple', hex: '#8b6cf6' },
  { v: 'green', hex: '#16a37a' },
  { v: 'orange', hex: '#f2842c' }
]

async function onClearCache() {
  const ok = await ui.confirm({
    title: '确认清除缓存？',
    lines: ['仅清除：行情缓存、最近搜索', '不影响：登录、自选、模拟交易数据'],
    confirmText: '清除'
  })
  if (!ok) return
  settings.clearCache()
  ui.toast('缓存已清除', 'success')
}
</script>

<style scoped>
.nav { display: flex; align-items: center; gap: 6px; margin-bottom: 14px; }
.back { background: none; border: none; color: var(--text); font-size: 30px; line-height: 1; cursor: pointer; padding: 0 6px 4px 0; }
.group-title { font-size: 12px; color: var(--text-3); margin: 16px 4px 8px; }
.list { padding: 4px 14px; }
.item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 0; border-bottom: 1px solid var(--border); font-size: 14.5px;
}
.item:last-child { border-bottom: none; }
.item.col { flex-direction: column; align-items: stretch; gap: 10px; }
.val { color: var(--text-3); font-size: 13px; }
.arrow { color: var(--text-3); font-size: 18px; }
.colors { display: flex; gap: 14px; }
.dot {
  width: 30px; height: 30px; border-radius: 50%; border: 2px solid transparent;
  cursor: pointer; transition: transform .1s;
}
.dot.on { border-color: var(--text); transform: scale(1.12); }
</style>
