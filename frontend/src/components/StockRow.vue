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
import { prefetchStockDetail, cancelOtherPrefetches, claimStockPrefetch } from '@/utils/detailPrefetch'
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
  // 真实点击发生：立即认领这只股票的预取 entry，保证它不会被后续任何
  // "离开视口"信号撤回（哪怕是 Tab 页 keep-alive 停用时浏览器误判的
  // "假离开"，见 detailPrefetch.ts 的 claimStockPrefetch 注释）——这一步
  // 必须放在 router.push 之前，抢在路由异步解析/组件懒加载完成之前生效。
  claimStockPrefetch(props.stock.code)
  // 取消除了这只股票之外、还在飞行中的预取请求，让出带宽（仅弱网触发）
  cancelOtherPrefetches(props.stock.code)
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

// 进入即取：一进视口就加入 LRU 追踪队列，同一屏内打包成一次批量请求（细节见 viewportPrefetch）
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
