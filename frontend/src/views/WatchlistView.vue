<template>
  <div class="page">
    <div class="section-title">我的自选 <span class="mock-badge">模拟行情</span></div>

    <template v-if="userStore.isLoggedIn()">
      <div class="card" style="padding: 2px 14px;">
        <div class="wrow" v-for="s in watchlist.list" :key="s.id">
          <StockRow :stock="s" style="flex:1" />
          <button class="del" @click.stop="onRemove(s)">删除</button>
        </div>
        <div v-if="watchlist.loaded && watchlist.list.length === 0" class="empty">
          还没有自选股票<br /><br />
          <span class="link" @click="$router.push('/search')">去添加股票</span>
        </div>
        <div v-else-if="!watchlist.loaded" class="skeleton" style="height:112px;margin:10px 0"></div>
      </div>
    </template>
    <div v-else class="card empty">
      <span class="link" @click="$router.push('/login')">登录</span> 后管理自选股
    </div>
  </div>
</template>

<script setup lang="ts">
import { onActivated, onMounted } from 'vue'
import type { StockItem } from '@/api/types'
import { useUserStore } from '@/stores/user'
import { useWatchlistStore } from '@/stores/watchlist'
import { useMarketStore } from '@/stores/market'
import { useUiStore } from '@/stores/ui'
import StockRow from '@/components/StockRow.vue'

defineOptions({ name: 'WatchlistView' })

const userStore = useUserStore()
const watchlist = useWatchlistStore()
const market = useMarketStore()
const ui = useUiStore()

function ensure() {
  market.ensure() // 自选行情随全局轮询刷新
  if (userStore.isLoggedIn() && !watchlist.loaded) {
    watchlist.refresh().catch(() => ui.toast('自选加载失败', 'error'))
  }
}
onMounted(ensure)
onActivated(ensure)

async function onRemove(s: StockItem) {
  const ok = await ui.confirm({ title: `删除自选「${s.name}」？`, danger: true, confirmText: '删除' })
  if (!ok) return
  try {
    await watchlist.remove(s.id)
    ui.toast('已删除', 'success')
  } catch (e) {
    ui.toast((e as Error).message, 'error')
  }
}
</script>

<style scoped>
.wrow { display: flex; align-items: center; gap: 8px; }
.del {
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-3);
  border-radius: 8px;
  font-size: 12px;
  padding: 6px 10px;
  cursor: pointer;
  flex-shrink: 0;
}
</style>
