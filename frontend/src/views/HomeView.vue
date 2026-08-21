<template>
  <div class="page">
    <div class="search-fake" @click="$router.push('/search')">
      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
      <span>搜索股票代码 / 名称</span>
    </div>

    <div class="section-title">
      市场指数
      <span class="mock-badge">模拟行情</span>
    </div>
    <div class="idx-list" v-if="market.indexes.length">
      <IndexCard v-for="i in market.indexes" :key="i.code" :index="i" />
    </div>
    <div v-else-if="market.error" class="card error-block">
      <p>行情加载失败</p>
      <button @click="market.refresh()">重新加载</button>
    </div>
    <div v-else class="idx-list">
      <div class="skeleton" style="flex:1;height:78px" v-for="n in 3" :key="n"></div>
    </div>

    <div class="section-title">
      我的自选
      <span class="more" @click="$router.push('/watchlist')">全部 ›</span>
    </div>
    <div class="card" style="padding: 2px 14px;">
      <template v-if="userStore.isLoggedIn()">
        <StockRow v-for="s in watchlist.list.slice(0, 5)" :key="s.id" :stock="s" />
        <div v-if="watchlist.loaded && watchlist.list.length === 0" class="empty">
          还没有自选股票，<span class="link" @click="$router.push('/search')">去添加</span>
        </div>
        <div v-else-if="!watchlist.loaded" class="skeleton" style="height:56px;margin:10px 0"></div>
      </template>
      <div v-else class="empty">
        <span class="link" @click="$router.push('/login')">登录</span> 后查看自选股
      </div>
    </div>

    <div class="section-title">热门股票</div>
    <div class="card" style="padding: 2px 14px;">
      <StockRow v-for="s in market.hot" :key="s.id" :stock="s" />
      <div v-if="!market.hot.length" class="skeleton" style="height:120px;margin:10px 0"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onActivated, onMounted } from 'vue'
import { useMarketStore } from '@/stores/market'
import { useWatchlistStore } from '@/stores/watchlist'
import { useUserStore } from '@/stores/user'
import IndexCard from '@/components/IndexCard.vue'
import StockRow from '@/components/StockRow.vue'

defineOptions({ name: 'HomeView' })

const market = useMarketStore()
const watchlist = useWatchlistStore()
const userStore = useUserStore()

function ensure() {
  market.ensure()
  if (userStore.isLoggedIn() && !watchlist.loaded) {
    watchlist.refresh().catch(() => { /* 静默，等下轮刷新 */ })
  }
}
onMounted(ensure)
onActivated(ensure)
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
