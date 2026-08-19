<template>
  <div class="page no-tab">
    <!-- 顶部导航 -->
    <div class="nav">
      <button class="back" @click="$router.back()">‹</button>
      <div class="title">
        <div class="tname">{{ quote?.name || code }}</div>
        <div class="tcode">{{ code }} <span v-if="quote?.mock" class="mock-badge">模拟行情</span></div>
      </div>
      <button class="fav" :class="{ on: faved }" @click="toggleFav">
        {{ faved ? '✓ 已自选' : '+ 自选' }}
      </button>
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  addWatch, getIndicators, getIntraday, getKline,
  getQuote, getStock, getStockNews, inWatchlist, removeWatch
} from '@/api'
import type { Indicators, Intraday, KlineItem, NewsItem, Period, Quote, StockItem } from '@/api/types'
import { changeClass, fmtAmount, fmtChange, fmtPercent, fmtPrice, fmtTime, fmtVolume } from '@/utils/format'
import { useUserStore } from '@/stores/user'
import IntradayChart from '@/components/charts/IntradayChart.vue'
import KlineChart from '@/components/charts/KlineChart.vue'

type Tab = 'intraday' | Period

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const code = String(route.params.code)

const quote = ref<Quote | null>(null)
const stock = ref<StockItem | null>(null)
const intraday = ref<Intraday | null>(null)
const kline = ref<KlineItem[]>([])
const indicators = ref<Indicators | null>(null)
const news = ref<NewsItem[]>([])
const faved = ref(false)

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
let timer: number | undefined
const klineCache = new Map<Period, { k: KlineItem[]; i: Indicators }>()

async function loadQuote() {
  try { quote.value = await getQuote(code) } catch { /* 保持已有 */ }
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
    alert((e as Error).message)
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
      await removeWatch(stock.value.id)
      faved.value = false
    } else {
      await addWatch(stock.value.id)
      faved.value = true
    }
  } catch (e) {
    alert((e as Error).message)
  }
}

onMounted(async () => {
  try {
    const [s] = await Promise.all([getStock(code), loadQuote()])
    stock.value = s
    intraday.value = await getIntraday(code)
    news.value = await getStockNews(code, 10)
    if (userStore.isLoggedIn()) {
      faved.value = await inWatchlist(s.id)
    }
  } catch (e) {
    alert((e as Error).message)
    router.back()
    return
  }
  timer = window.setInterval(loadQuote, 10000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<style scoped>
.nav { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.back {
  background: none; border: none; color: var(--text);
  font-size: 30px; line-height: 1; cursor: pointer; padding: 0 6px 4px 0;
}
.title { flex: 1; }
.tname { font-size: 17px; font-weight: 700; }
.tcode { font-size: 11px; color: var(--text-3); margin-top: 2px; }
.fav {
  border: 1px solid var(--accent); background: transparent; color: var(--accent);
  border-radius: 9px; font-size: 13px; padding: 7px 12px; cursor: pointer;
}
.fav.on { background: var(--accent); color: #fff; }

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

.news { padding: 12px 0; border-bottom: 1px solid var(--border); cursor: pointer; }
.news:last-child { border-bottom: none; }
.ntitle { font-size: 14px; line-height: 1.45; }
.nmeta { font-size: 11px; color: var(--text-3); margin-top: 5px; }
</style>
