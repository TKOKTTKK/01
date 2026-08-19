<template>
  <div class="row" @click="$router.push(`/stock/${stock.code}`)">
    <div class="left">
      <div class="name">{{ stock.name }}</div>
      <div class="code">{{ stock.market }} {{ stock.code }}</div>
    </div>
    <div class="mid" :class="cls">{{ fmtPrice(stock.price) }}</div>
    <div class="right">
      <span class="pill" :class="cls">{{ fmtPercent(stock.changePercent) }}</span>
    </div>
    <slot />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { StockItem } from '@/api/types'
import { changeClass, fmtPercent, fmtPrice } from '@/utils/format'

const props = defineProps<{ stock: StockItem }>()
const cls = computed(() => changeClass(props.stock.changePercent))
</script>

<style scoped>
.row {
  display: flex;
  align-items: center;
  padding: 13px 2px;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  min-height: 56px;
}
.row:last-child { border-bottom: none; }
.left { flex: 1; min-width: 0; }
.name { font-size: 15px; font-weight: 600; }
.code { font-size: 11px; color: var(--text-3); margin-top: 3px; }
.mid { width: 88px; text-align: right; font-size: 15px; font-variant-numeric: tabular-nums; }
.right { width: 86px; display: flex; justify-content: flex-end; }
.pill {
  min-width: 72px;
  text-align: center;
  padding: 5px 0;
  border-radius: 8px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.pill.up { background: rgba(240, 73, 62, .14); }
.pill.down { background: rgba(15, 191, 127, .14); }
.pill.flat { background: var(--bg-soft); }
</style>
