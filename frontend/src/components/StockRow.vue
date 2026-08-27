<template>
  <div ref="rowEl" class="row" @click="open"
    @touchstart.passive="onTouch" @mouseenter="onTouch">
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
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { preloadView } from '@/router'
import { prefetchStockDetail } from '@/utils/detailPrefetch'
import { observeStockRow, unobserveStockRow } from '@/utils/viewportPrefetch'
import type { StockItem } from '@/api/types'
import { changeClass, fmtPercent, fmtPrice } from '@/utils/format'

const props = defineProps<{ stock: StockItem }>()
const router = useRouter()
const rowEl = ref<HTMLDivElement | null>(null)
const cls = computed(() => changeClass(props.stock.changePercent))

/**
 * 数据接力：跳转时把列表已持有的行情快照塞进路由 state，
 * 详情页 onMounted 前就能用它渲染价格区（零网络等待），
 * 随后正式请求返回时静默替换。
 * seedTs 用于详情页判定新鲜度（刷新恢复的 history.state 可能很旧）。
 */
function open() {
  router.push({
    path: `/stock/${props.stock.code}`,
    state: { seed: { ...props.stock }, seedTs: Date.now() }
  })
}

/** 即将点击：预载详情页 chunk + deep 预取（bootstrap + 日K + 指标） */
function onTouch() {
  preloadView('stock')
  prefetchStockDetail(props.stock.code, { deep: true })
}

// 停下来即取：滚动停下后对当前可见的行预取 detail-bootstrap（预算与节流见 viewportPrefetch）
onMounted(() => {
  if (rowEl.value) observeStockRow(rowEl.value, () => props.stock.code)
})
onBeforeUnmount(() => {
  if (rowEl.value) unobserveStockRow(rowEl.value)
})
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
.pill.up { background: var(--up-bg); }
.pill.down { background: var(--down-bg); }
.pill.flat { background: var(--bg-soft); }
</style>
