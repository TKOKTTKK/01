<template>
  <div class="page no-tab detail-page">
    <!-- 顶部导航 -->
    <div class="nav">
      <button class="back" @click="$router.back()">‹</button>
      <div class="title">
        <div class="tname">{{ quote?.name || code }}</div>
        <div class="tcode">{{ code }} <span v-if="quote?.mock" class="mock-badge">模拟行情</span></div>
      </div>
    </div>

    <!-- 价格区 -->
    <div class="price-block" v-if="quote">
      <div class="big" :class="cls">{{ fmtPrice(quote.price) }}</div>
      <div class="chg" :class="cls">
        {{ fmtChange(quote.changeAmount) }}&nbsp;&nbsp;{{ fmtPercent(quote.changePercent) }}
      </div>
      <div class="stats">
        <span>今开 <b>{{ fmtPrice(quote.openPrice) }}</b></span>
        <span>昨收 <b>{{ fmtPrice(quote.preClose) }}</b></span>
        <span>最高 <b class="up">{{ fmtPrice(quote.highPrice) }}</b></span>
        <span>最低 <b class="down">{{ fmtPrice(quote.lowPrice) }}</b></span>
        <span>成交量 <b>{{ fmtVolume(quote.volume) }}</b></span>
        <span>成交额 <b>{{ fmtAmount(quote.amount) }}</b></span>
      </div>
      <div class="stats" v-if="statsExpanded">
        <span>涨跌额 <b :class="cls">{{ fmtChange(quote.changeAmount) }}</b></span>
        <span>振幅 <b>{{ amplitude }}</b></span>
        <span>均价 <b>{{ avgDealPrice }}</b></span>
      </div>
      <div class="expand" @click="statsExpanded = !statsExpanded">
        {{ statsExpanded ? '收起 ▲' : '更多数据 ▼' }}
      </div>
    </div>
    <div v-else class="skeleton" style="height:120px;margin-bottom:12px"></div>

    <!-- 周期切换 -->
    <div class="seg" style="margin-bottom: 10px;">
      <button v-for="t in tabs" :key="t.key" :class="{ active: tab === t.key }"
        @click="switchTab(t.key)">{{ t.label }}</button>
    </div>

    <!-- 图表 -->
    <div class="card" style="padding: 8px 4px;">
      <IntradayChart v-if="tab === 'intraday'" :data="intraday" />
      <template v-else>
        <KlineChart :kline="kline" :indicators="indicators" :sub="sub" />
        <div class="seg" style="margin: 8px 8px 4px;">
          <button v-for="s in subs" :key="s" :class="{ active: sub === s }" @click="sub = s">{{ s }}</button>
        </div>
      </template>
    </div>

    <!-- 新闻 -->
    <div class="section-title">相关新闻</div>
    <div class="card" style="padding: 4px 14px;">
      <div class="news" v-for="n in news" :key="n.id" @click="$router.push(`/news/${n.id}`)">
        <div class="ntitle">{{ n.title }}</div>
        <div class="nmeta">{{ n.source }} · {{ fmtTime(n.publishTime) }}</div>
      </div>
      <div v-if="news.length === 0" class="empty">暂无相关新闻</div>
    </div>

    <!-- 底部操作栏：自选 / 卖出 / 买入（固定，适配安全区域） -->
    <div class="action-bar">
      <button class="fav" :class="{ on: faved }" @click="toggleFav">
        <span class="fav-icon">{{ faved ? '★' : '☆' }}</span>
        <span>{{ faved ? '已自选' : '自选' }}</span>
      </button>
      <button class="sell" @click="goTrade('sell')">卖出</button>
      <button class="buy" @click="goTrade('buy')">买入</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getIndicators, getIntraday, getKline, getQuote, getStockNews, inWatchlist
} from '@/api'
import { getPrefetchedOrFetch } from '@/utils/detailPrefetch'
import type { Indicators, Intraday, KlineItem, NewsItem, Period, Quote, StockItem } from '@/api/types'
import { changeClass, fmtAmount, fmtChange, fmtPercent, fmtPrice, fmtTime, fmtVolume } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import { useWatchlistStore } from '@/stores/watchlist'
import { useUiStore } from '@/stores/ui'
import IntradayChart from '@/components/charts/IntradayChart.vue'
import KlineChart from '@/components/charts/KlineChart.vue'

defineOptions({ name: 'StockDetailView' })

type Tab = 'intraday' | Period

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const watchlistStore = useWatchlistStore()
const ui = useUiStore()
const code = String(route.params.code)

const quote = ref<Quote | null>(null)
const stock = ref<StockItem | null>(null)
const intraday = ref<Intraday | null>(null)
const kline = ref<KlineItem[]>([])
const indicators = ref<Indicators | null>(null)
const news = ref<NewsItem[]>([])
const faved = ref(false)
const statsExpanded = ref(false)

// KeepAlive 缓存后，周期/副图指标/K线缩放状态都会保留
const tab = ref<Tab>('intraday')
const tabs: { key: Tab; label: string }[] = [
  { key: 'intraday', label: '分时' },
  { key: 'day', label: '日K' },
  { key: 'week', label: '周K' },
  { key: 'month', label: '月K' }
]
const subs = ['MACD', 'KDJ', 'RSI'] as const
const sub = ref<'MACD' | 'KDJ' | 'RSI'>('MACD')

const cls = computed(() => changeClass(quote.value?.changePercent))
const amplitude = computed(() => {
  const q = quote.value
  if (!q || !q.preClose) return '--'
  return ((q.highPrice - q.lowPrice) / q.preClose * 100).toFixed(2) + '%'
})
const avgDealPrice = computed(() => {
  const q = quote.value
  if (!q || !q.volume) return '--'
  return (q.amount / (q.volume * 100)).toFixed(2)
})

let timer: number | undefined
const klineCache = new Map<Period, { k: KlineItem[]; i: Indicators }>()

async function loadQuote() {
  try { quote.value = await getQuote(code) } catch { /* 保持已有数据 */ }
}

async function switchTab(t: Tab) {
  tab.value = t
  try {
    if (t === 'intraday') {
      if (!intraday.value) intraday.value = await getIntraday(code)
      return
    }
    const cached = klineCache.get(t)
    if (cached) {
      kline.value = cached.k
      indicators.value = cached.i
      return
    }
    const [k, i] = await Promise.all([getKline(code, t), getIndicators(code, t)])
    klineCache.set(t, { k, i })
    kline.value = k
    indicators.value = i
  } catch (e) {
    ui.toast((e as Error).message, 'error')
  }
}

async function toggleFav() {
  if (!userStore.isLoggedIn()) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (!stock.value) return
  try {
    if (faved.value) {
      await watchlistStore.remove(stock.value.id)
      faved.value = false
      ui.toast('已移出自选', 'info')
    } else {
      await watchlistStore.add(stock.value.id)
      faved.value = true
      ui.toast('已加入自选', 'success')
    }
  } catch (e) {
    ui.toast((e as Error).message, 'error')
  }
}

function goTrade(side: 'buy' | 'sell') {
  if (!userStore.isLoggedIn()) {
    router.push({ path: '/login', query: { redirect: `/trade/order/${code}?side=${side}` } })
    return
  }
  router.push(`/trade/order/${code}?side=${side}`)
}

function startPolling() {
  if (timer !== undefined) return
  timer = window.setInterval(() => {
    if (document.visibilityState === 'visible') loadQuote()
  }, 10000)
}
function stopPolling() {
  window.clearInterval(timer)
  timer = undefined
}

onMounted(async () => {
  try {
    // 命中 touchstart/mouseenter 时预取的数据就直接复用，不再重新发起请求
    const pre = getPrefetchedOrFetch(code)
    const [s, , intr, newsList, favResult] = await Promise.all([
      pre.stock,
      pre.quote.then((q) => { quote.value = q }),
      pre.intraday,
      getStockNews(code, 10),
      userStore.isLoggedIn() ? pre.stock.then((s) => inWatchlist(s.id)) : Promise.resolve(false)
    ])
    stock.value = s
    intraday.value = intr
    news.value = newsList
    faved.value = favResult
  } catch (e) {
    ui.toast((e as Error).message, 'error')
    router.back()
    return
  }
  startPolling()
})

// KeepAlive：切走停止轮询省流量，切回来立即刷新并恢复轮询（页面状态保留）
onActivated(() => {
  if (stock.value) {
    loadQuote()
    if (tab.value === 'intraday') getIntraday(code).then(v => { intraday.value = v }).catch(() => { /* 静默 */ })
    startPolling()
  }
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
.detail-page { padding-bottom: calc(64px + var(--safe-bottom) + 12px); }
.nav { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.back {
  background: none; border: none; color: var(--text);
  font-size: 30px; line-height: 1; cursor: pointer; padding: 0 6px 4px 0;
}
.title { flex: 1; }
.tname { font-size: 17px; font-weight: 700; }
.tcode { font-size: 11px; color: var(--text-3); margin-top: 2px; }

.price-block { margin: 4px 2px 14px; }
.big { font-size: 40px; font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1.1; }
.chg { font-size: 15px; margin-top: 4px; font-variant-numeric: tabular-nums; }
.stats {
  display: grid; grid-template-columns: repeat(3, 1fr);
  gap: 6px 10px; margin-top: 12px;
  font-size: 12px; color: var(--text-3);
}
.stats b { color: var(--text-2); font-weight: 500; margin-left: 3px; font-variant-numeric: tabular-nums; }
.stats b.up { color: var(--up); }
.stats b.down { color: var(--down); }
.expand { font-size: 11px; color: var(--text-3); margin-top: 10px; cursor: pointer; }

.news { padding: 12px 0; border-bottom: 1px solid var(--border); cursor: pointer; }
.news:last-child { border-bottom: none; }
.ntitle { font-size: 14px; line-height: 1.45; }
.nmeta { font-size: 11px; color: var(--text-3); margin-top: 5px; }

/* 底部操作栏 */
.action-bar {
  position: fixed; left: 0; right: 0; bottom: 0;
  display: flex; gap: 10px;
  padding: 10px 14px calc(10px + var(--safe-bottom));
  background: var(--tabbar-bg);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-top: 1px solid var(--border);
  z-index: 100;
  max-width: 640px; margin: 0 auto;
}
.action-bar button {
  border: none; border-radius: 12px; font-size: 15px; font-weight: 600;
  cursor: pointer; padding: 12px 0; transition: opacity .15s;
}
.action-bar button:active { opacity: .8; }
.action-bar .fav {
  flex: 0 0 76px; background: transparent; color: var(--text-2);
  display: flex; flex-direction: column; align-items: center; gap: 1px;
  font-size: 11px; padding: 6px 0;
}
.action-bar .fav-icon { font-size: 18px; line-height: 1.2; }
.action-bar .fav.on { color: #d9a441; }
.action-bar .sell { flex: 1; background: var(--down); color: #fff; }
.action-bar .buy { flex: 1; background: var(--up); color: #fff; }
</style>
