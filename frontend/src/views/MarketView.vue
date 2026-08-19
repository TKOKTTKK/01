<template>
  <div class="page">
    <div class="section-title">
      市场指数
      <span class="mock-badge">模拟行情</span>
    </div>
    <div class="idx-list">
      <IndexCard v-for="i in indexes" :key="i.code" :index="i" />
    </div>

    <div class="section-title">全部股票</div>
    <div class="card" style="padding: 2px 14px;">
      <StockRow v-for="s in stocks" :key="s.id" :stock="s" />
      <div v-if="stocks.length === 0" class="empty">加载中…</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getMarketIndex, listStocks } from '@/api'
import type { MarketIndex, StockItem } from '@/api/types'
import IndexCard from '@/components/IndexCard.vue'
import StockRow from '@/components/StockRow.vue'

const indexes = ref<MarketIndex[]>([])
const stocks = ref<StockItem[]>([])
let timer: number | undefined

async function load() {
  try {
    const [idx, list] = await Promise.all([getMarketIndex(), listStocks()])
    indexes.value = idx
    stocks.value = list
  } catch { /* 保持已有数据 */ }
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 10000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<style scoped>
.idx-list { display: flex; gap: 8px; }
</style>
