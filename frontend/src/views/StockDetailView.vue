<template>
  <div class="page no-tab detail-page">
    <!-- 顶部导航 -->
    <div class="nav">
      <button class="back" @click="$router.back()">‹</button>
      <div class="title">
        <div class="tname">{{ view.name || code }}</div>
        <div class="tcode">{{ code }} <span v-if="quote?.mock" class="mock-badge">模拟行情</span></div>
      </div>
    </div>

    <!--
      价格区：数据接力（路由 state 里的列表快照）或正式行情，谁先有谁先渲染。
      seed 只有 价格/涨跌 两个核心字段，其余统计项在 quote 到达前显示 "--"，
      quote 到达后静默补全（同一套 DOM，无跳变）。
    -->
    <div class="price-block" v-if="quote || seed">
      <div class="big" :class="cls">{{ fmtPrice(view.price) }}</div>
      <div class="chg" :class="cls">
        {{ fmtChange(view.changeAmount) }}&nbsp;&nbsp;{{ fmtPercent(view.changePercent) }}
      </div>
      <div class="stats">
        <span>今开 <b>{{ fmtPrice(view.openPrice) }}</b></span>
        <span>昨收 <b>{{ fmtPrice(view.preClose) }}</b></span>
        <span>最高 <b class="up">{{ fmtPrice(view.highPrice) }}</b></span>
        <span>最低 <b class="down">{{ fmtPrice(view.lowPrice) }}</b></span>
        <span>成交量 <b>{{ fmtVolume(view.volume) }}</b></span>
        <span>成交额 <b>{{ fmtAmount(view.amount) }}</b></span>
      </div>
      <div class="stats" v-if="statsExpanded">
        <span>涨跌额 <b :class="cls">{{ fmtChange(view.changeAmount) }}</b></span>
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

    <!-- 图表：分时图容器始终立即挂载（数据未到时先画坐标轴骨架，见 IntradayChart） -->
    <div class="card" style="padding: 8px 4px;">
      <IntradayChart v-if="tab === 'intraday'" :data="intraday" />
      <template v-else>
        <KlineChart v-if="kline.length" :kline="kline" :indicators="indicators" :sub="sub" />
        <div v-else class="skeleton" style="height:300px;margin:0 8px"></div>
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
  getDetailBootstrap, getIndicators, getIntraday, getKline, getQuote, getStockNews, inWatchlist
} from '@/api'
import { getFreshEntry, getPrefetchedOrFetch } from '@/utils/detailPrefetch'
import { readIntradayDiskCache } from '@/utils/intradayDiskCache'
import { onIdle, shouldSkipPreload } from '@/utils/preload'
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

/**
 * 数据接力落地：同步读取路由 state 里列表带过来的行情快照。
 * 必须在 setup 里同步读（此时 history.state 就是本次导航写入的值）；
 * 校验 code 匹配 + 30 秒新鲜度（防止刷新后浏览器恢复的陈旧 state）。
 */
function readSeed(): StockItem | null {
  try {
    const st = window.history.state as { seed?: StockItem; seedTs?: number } | null
    if (st && st.seed && st.seed.code === code
      && typeof st.seedTs === 'number' && Date.now() - st.seedTs < 30_000) {
      return st.seed
    }
  } catch { /* state 不可读则忽略 */ }
  return null
}

const seed = ref<StockItem | null>(readSeed())
const quote = ref<Quote | null>(null)
const stock = ref<StockItem | null>(null)
/**
 * 分时图 0ms 打开：跟 seed 价格接力同一个思路，setup 阶段同步读一次
 * 磁盘缓存（见 intradayDiskCache.ts）。命中的话 IntradayChart 挂载那一刻
 * props.data 已经有值，直接画真实曲线，不用先走坐标轴骨架；
 * 网络请求（预取或本次现发）返回后照常静默覆盖成最新数据。
 */
const intraday = ref<Intraday | null>(readIntradayDiskCache(code))
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

/** 展示层合并：正式 quote 优先，未到达时用 seed 的核心字段，其余为 null（显示 --） */
const view = computed(() => {
  const q = quote.value
  const s = seed.value
  return {
    name: q?.name ?? s?.name ?? '',
    price: q?.price ?? s?.price ?? null,
    changeAmount: q?.changeAmount ?? s?.changeAmount ?? null,
    changePercent: q?.changePercent ?? s?.changePercent ?? null,
    openPrice: q?.openPrice ?? null,
    preClose: q?.preClose ?? null,
    highPrice: q?.highPrice ?? null,
    lowPrice: q?.lowPrice ?? null,
    volume: q?.volume ?? null,
    amount: q?.amount ?? null
  }
})

const cls = computed(() => changeClass(view.value.changePercent))
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
const klineInflight = new Map<Period, Promise<{ k: KlineItem[]; i: Indicators }>>()

async function loadQuote() {
  try { quote.value = await getQuote(code) } catch { /* 保持已有数据 */ }
}

/**
 * K线 + 指标加载：本地缓存 -> 在途请求 -> deep 预取（day）-> 现场请求。
 * 所有路径写入同一个 klineCache，切换周期二次进入零请求。
 */
function loadKline(period: Period): Promise<{ k: KlineItem[]; i: Indicators }> {
  const cached = klineCache.get(period)
  if (cached) return Promise.resolve(cached)
  const inflight = klineInflight.get(period)
  if (inflight) return inflight

  const pre = period === 'day' ? getFreshEntry(code) : null
  const source: Promise<[KlineItem[], Indicators]> = pre?.klineDay && pre.indicatorsDay
    ? Promise.all([pre.klineDay, pre.indicatorsDay])
    : Promise.all([getKline(code, period), getIndicators(code, period)])

  const p = source
    .then(([k, i]) => {
      const v = { k, i }
      klineCache.set(period, v)
      return v
    })
    .finally(() => klineInflight.delete(period))
  klineInflight.set(period, p)
  return p
}

async function switchTab(t: Tab) {
  tab.value = t
  try {
    if (t === 'intraday') {
      if (!intraday.value) intraday.value = await getIntraday(code)
      return
    }
    const v = await loadKline(t)
    if (tab.value === t) { // 等待期间用户又切走了就不覆盖
      kline.value = v.k
      indicators.value = v.i
    }
  } catch (e) {
    ui.toast((e as Error).message, 'error')
  }
}

/**
 * 空闲时后台预取 K线 + 指标：详情页默认停在分时，但用户大概率会去点
 * 日K/周K/月K 中的某一个。
 *
 * 【v3.2 修复】之前只预热了 day，week/month 完全没有预取，导致用户
 * 首次点击周K/月K时是现场发起请求，1-2 秒才出图——这才是"周K月K慢"
 * 的真正原因，不是这两个周期的缓存没生效，而是它们从没被预热过。
 *
 * 现在三个周期都预热，但错开时间、串行触发（day 先、week/month 依次
 * 排在后面的空闲时间片），避免一次性并发三组"K线+指标"请求抢占带宽，
 * 也避免抢占分时图/新闻等首屏更重要请求的资源。
 * 省流量/2G 用户依然整体跳过。
 */
function warmKlineOnIdle() {
  if (shouldSkipPreload()) return
  const periods: Period[] = ['day', 'week', 'month']
  let delay = 1200
  for (const p of periods) {
    if (klineCache.has(p)) continue
    const period = p
    const wait = delay
    onIdle(() => { loadKline(period).catch(() => { /* 静默，点击时会重试 */ }) }, wait)
    delay += 800 // 依次错开，串行让出空闲时间片
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

onMounted(() => {
  // 命中预取（可见即取 / touchstart）就直接复用，否则此刻发起 detail-bootstrap。
  // 各数据源到达即渲染，不再用 Promise.all 等全部到齐：
  // 价格区（seed 已先画）-> quote 覆盖 -> 分时折线 -> 新闻，逐段点亮。
  const pre = getPrefetchedOrFetch(code)

  pre.quote.then(q => { quote.value = q }).catch(() => { /* 价格区保留 seed，轮询会重试 */ })
  pre.intraday.then(v => { intraday.value = v }).catch(() => { /* 图表保持骨架 */ })

  // stock 是页面基础（自选/交易都依赖 id），拿不到才视为致命错误退回
  pre.stock
    .then(async (s) => {
      stock.value = s
      if (userStore.isLoggedIn()) {
        faved.value = await inWatchlist(s.id).catch(() => false)
      }
    })
    .catch((e) => {
      ui.toast((e as Error).message, 'error')
      router.back()
    })

  getStockNews(code, 10)
    .then(list => { news.value = list })
    .catch(() => { /* 新闻缺失不影响主内容，显示"暂无" */ })

  // touchstart deep 预取过的日K直接装进本地缓存；没有则空闲时补一手
  const fresh = getFreshEntry(code)
  if (fresh?.klineDay && fresh.indicatorsDay) {
    Promise.all([fresh.klineDay, fresh.indicatorsDay])
      .then(([k, i]) => { if (!klineCache.has('day')) klineCache.set('day', { k, i }) })
      .catch(() => { /* 静默 */ })
  }
  warmKlineOnIdle()

  startPolling()
})

// KeepAlive：切走停止轮询省流量，切回来立即刷新并恢复轮询
// tab 不在保留状态之列：切走时重置为分时图，下次进来（不管是不是同一只
// 股票）第一眼看到的都是分时图，跟行业里大部分行情 App 的默认预期一致；
// K线数据缓存（klineCache）本身不清，只是视图归位，真去点日K/周K/月K
// 时依然是命中缓存的零等待。
onActivated(() => {
  if (stock.value) {
    // 一次 bootstrap 同时刷新 quote + 分时（替代原来的两次独立请求）；
    // 失败（如后端旧版本）降级为原来的两个独立接口
    // 上面已把 tab 重置为 intraday，这里必然要刷新分时
    getDetailBootstrap(code)
      .then(b => {
        quote.value = b.quote
        intraday.value = b.intraday
      })
      .catch(() => {
        loadQuote()
        getIntraday(code).then(v => { intraday.value = v }).catch(() => { /* 静默 */ })
      })
    startPolling()
  }
})
onDeactivated(() => {
  stopPolling()
  tab.value = 'intraday'
})
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
