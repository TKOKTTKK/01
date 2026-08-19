<template>
  <div class="page">
    <!-- 搜索框 -->
    <div class="search-fake" @click="$router.push('/search')">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
      <span>搜索股票代码 / 名称</span>
    </div>

    <!-- 市场指数 -->
    <div class="section-title">
      市场指数
      <span v-if="mock" class="mock-badge">模拟行情</span>
    </div>
    <div class="idx-list" v-if="indexes.length">
      <IndexCard v-for="i in indexes" :key="i.code" :index="i" />
    </div>
    <div v-else class="idx-list">
      <div class="skeleton" style="flex:1;height:78px" v-for="n in 3" :key="n"></div>
    </div>

    <!-- 我的自选 -->
    <div class="section-title">
      我的自选
      <span class="more" @click="$router.push('/watchlist')">全部 ›</span>
    </div>
    <div class="card" style="padding: 2px 14px;">
      <template v-if="userStore.isLoggedIn()">
        <StockRow v-for="s in watchlist" :key="s.id" :stock="s" />
        <div v-if="watchlist.length === 0" class="empty">暂无自选，去搜索添加吧</div>
      </template>
      <div v-else class="empty">
        <span style="color: var(--accent)" @click="$router.push('/login')">登录</span>
        后查看自选股
      </div>
    </div>

    <!-- 热门股票 -->
    <div class="section-title">热门股票</div>
    <div class="card" style="padding: 2px 14px;">
      <StockRow v-for="s in hot" :key="s.id" :stock="s" />
      <div v-if="hot.length === 0" class="empty">加载中…</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getHotStocks, getMarketIndex, getWatchlist } from '@/api'
import type { MarketIndex, StockItem } from '@/api/types'
import { useUserStore } from '@/stores/user'
import IndexCard from '@/components/IndexCard.vue'
import StockRow from '@/components/StockRow.vue'

const userStore = useUserStore()
const indexes = ref<MarketIndex[]>([])
const hot = ref<StockItem[]>([])
const watchlist = ref<StockItem[]>([])
const mock = ref(true)
let timer: number | undefined

async function load() {
  try {
    const [idx, hotList] = await Promise.all([getMarketIndex(), getHotStocks(6)])
    indexes.value = idx
    hot.value = hotList
    if (userStore.isLoggedIn()) {
      watchlist.value = (await getWatchlist()).slice(0, 5)
    }
  } catch { /* 首页静默失败，保持已有数据 */ }
}

onMounted(() => {
  load()
  timer = window.setInterval(load, 10000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<style scoped>
.search-fake {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--bg-soft);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 12px 14px;
  color: var(--text-3);
  font-size: 14px;
  cursor: pointer;
}
.idx-list { display: flex; gap: 8px; }
</style>
