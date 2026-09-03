<template>
  <div class="debug-panel">
    <div class="debug-title">🔧 缓存调试面板（临时，验证完请删除，见组件顶部注释）</div>
    <div class="debug-row">
      <input v-model="code" placeholder="股票代码，如 600519" class="debug-input"
        @keyup.enter="query" />
      <button class="debug-btn" @click="query">查询</button>
      <button class="debug-btn ghost" @click="listAll">看全部已缓存代码</button>
    </div>
    <div class="debug-row">
      <button class="debug-btn ghost" @click="checkSyncStatus">查看全量同步状态</button>
      <button class="debug-btn ghost" @click="checkTraffic">查看流量统计</button>
    </div>

    <template v-if="traffic">
      <div class="debug-section">
        <div class="debug-h">流量统计（从这次打开 App 开始累计，刷新页面归零）</div>
        <div class="debug-hit">预取流量：{{ formatBytes(traffic.prefetchBytes) }}（{{ traffic.prefetchCount }} 个请求）</div>
        <div class="debug-hit">
          总流量：{{ formatBytes(traffic.totalBytes) }}（{{ traffic.totalCount }} 个请求）
          {{ traffic.totalBytes > 0 ? `· 预取占比 ${(traffic.prefetchBytes / traffic.totalBytes * 100).toFixed(1)}%` : '' }}
        </div>
        <div class="debug-miss" v-if="traffic.totalCount === 0">
          还没有任何请求被记录到——如果这时候明明已经有网络活动，
          可能是浏览器不支持 Resource Timing，或者请求发生在 App 打开之前
          （理论上不应该，见 trafficStats.ts 头注释）
        </div>
      </div>
    </template>

    <div v-if="loading" class="debug-hint">查询中…</div>

    <template v-if="syncStatus">
      <div class="debug-section">
        <div class="debug-h">全量后台同步（fullSync.ts）</div>
        <div class="debug-hit">
          今天日期：{{ syncStatus.today }}；记录里的日期：{{ syncStatus.dateStr ?? '（还没跑过）' }}
        </div>
        <div v-if="syncStatus.dateStr === syncStatus.today" class="debug-hit">
          今天进度：{{ syncStatus.nextIndex }} / {{ syncStatus.total }}
          {{ syncStatus.nextIndex >= syncStatus.total ? '（已完成）' : '（进行中/等待下次空闲触发）' }}
        </div>
        <div v-else class="debug-miss">今天还没开始同步（等待启动延迟/空闲触发，或还没到今天）</div>
        <div class="debug-hit">
          kline 表里 period=day 的记录数：{{ syncStatus.syncedCount }}
          （全量同步 + 用户点开过的日K都算在内，两者共用同一张表；
          全量同步跑完理论上应该接近甚至等于股票总数 {{ syncStatus.total || '?' }}）
        </div>
        <div class="debug-hit">
          当前（Asia/Shanghai）：{{ syncStatus.nowStr }} · {{ syncStatus.inTradingHours ? '✅ 交易时段内，价格轮询应该在跑' : '⏸ 非交易时段，价格轮询原地待命' }}
        </div>
      </div>
    </template>

    <template v-if="result">
      <div class="debug-section">
        <div class="debug-h">quote（实时行情快照，可见即取就该有）</div>
        <div v-if="result.quote" class="debug-hit">✅ 已缓存 · {{ formatAge(result.quote.ts) }}</div>
        <div v-else class="debug-miss">❌ 未命中</div>
      </div>

      <div class="debug-section">
        <div class="debug-h">intraday（分时图，可见即取就该有）</div>
        <div v-if="result.intraday" class="debug-hit">✅ 已缓存 · {{ formatAge(result.intraday.ts) }}</div>
        <div v-else class="debug-miss">❌ 未命中</div>
      </div>

      <div class="debug-section">
        <div class="debug-h">kline（K线，只有手指按住 touchstart 深度预取才会有）</div>
        <template v-if="result.kline.length">
          <div v-for="k in result.kline" :key="k.period" class="debug-hit">
            ✅ {{ k.period }} · {{ k.count }} 根 · {{ formatAge(k.ts) }}
          </div>
        </template>
        <div v-else class="debug-miss">❌ 未命中（如果只是划过没按住，这是正常的，不算 bug）</div>
      </div>
    </template>

    <template v-if="allCodes">
      <div class="debug-section">
        <div class="debug-h">quote 表里已缓存过的全部代码（共 {{ allCodes.length }} 个）</div>
        <div class="debug-chips">
          <button v-for="c in allCodes" :key="c" class="debug-chip" @click="code = c; query()">{{ c }}</button>
          <span v-if="!allCodes.length" class="debug-miss">（还没有任何缓存）</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * ⚠️ 临时调试面板 —— 仅用于在真机上肉眼验证 IndexedDB 预取缓存 /
 * 全量后台同步是否命中/正常推进、预取流量是否符合预期，不是正式功能，
 * 不应该留在生产代码里。
 *
 * 用完删除方法（共 4 处，比其它几处多了流量统计相关的两处）：
 * 1. 删掉本文件：frontend/src/components/DebugCachePanel.vue
 * 2. 打开 frontend/src/views/SettingsView.vue，删掉里面标了
 *    "// 调试面板：验证完删掉本段和 DebugCachePanel.vue" 的那几处代码
 * 3. 删掉 frontend/src/utils/trafficStats.ts
 * 4. 打开 frontend/src/api/http.ts，删掉 fetchLowPriority 里给 URL 加
 *    `_pf=1` 标记参数的那一行（有注释标注，搜 "_pf=1"）；打开 main.ts，
 *    删掉最上面 `import './utils/trafficStats'` 那一行（连同上面那段
 *    "必须是第一个 import" 的注释）
 * 前两处不牵扯任何正式功能（原理见下）；第 3、4 处例外——流量统计要
 * 精确区分"预取流量"和"真实交互流量"，必须在 http.ts 里给预取请求的 URL
 * 打一个标记，这是唯一碰了生产代码的地方，删除时不能漏掉，否则会留下
 * 一个不会被任何东西读取、但一直在悄悄改 URL 的死代码。
 *
 * 查缓存/同步状态部分：直接用原生 IndexedDB API 只读打开 chartDiskCache.ts
 * 建的那个库（stock_app_chart_cache），不 import、不改动 chartDiskCache.ts /
 * fullSync.ts 本身。
 * 查流量部分：读 utils/trafficStats.ts 暴露的 getTrafficStats()——这个
 * 文件本身是自包含的（自己内部用 PerformanceObserver 统计，不需要本面板
 * 也能独立工作），本面板只是读一下它的结果展示出来。
 */
import { ref } from 'vue'
import { getTrafficStats, type TrafficStats } from '@/utils/trafficStats'

const DB_NAME = 'stock_app_chart_cache'

interface DebugResult {
  quote: { ts: number } | null
  intraday: { ts: number } | null
  kline: { period: string; ts: number; count: number }[]
}

interface SyncStatus {
  today: string
  dateStr: string | null
  nextIndex: number
  total: number
  syncedCount: number
  nowStr: string
  inTradingHours: boolean
}

const code = ref('')
const loading = ref(false)
const result = ref<DebugResult | null>(null)
const allCodes = ref<string[] | null>(null)
const syncStatus = ref<SyncStatus | null>(null)
const traffic = ref<TrafficStats | null>(null)

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME)
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

function getAll<T>(db: IDBDatabase, store: string): Promise<T[]> {
  return new Promise((resolve, reject) => {
    if (!db.objectStoreNames.contains(store)) { resolve([]); return }
    const tx = db.transaction(store, 'readonly')
    const req = tx.objectStore(store).getAll()
    req.onsuccess = () => resolve(req.result as T[])
    req.onerror = () => reject(req.error)
  })
}

function formatAge(ts: number): string {
  const mins = Math.round((Date.now() - ts) / 60000)
  const time = new Date(ts).toLocaleString()
  if (mins < 1) return `刚刚（${time}）`
  if (mins < 60) return `${mins} 分钟前（${time}）`
  return `${Math.round(mins / 60)} 小时前（${time}）`
}

async function query() {
  const c = code.value.trim()
  if (!c) return
  loading.value = true
  try {
    const db = await openDb()
    const [quotes, intradays, klines] = await Promise.all([
      getAll<{ code: string; ts: number }>(db, 'quote'),
      getAll<{ code: string; ts: number }>(db, 'intraday'),
      getAll<{ code: string; period: string; ts: number; kline: unknown[] }>(db, 'kline')
    ])
    result.value = {
      quote: quotes.find(q => q.code === c) ?? null,
      intraday: intradays.find(i => i.code === c) ?? null,
      kline: klines.filter(k => k.code === c).map(k => ({ period: k.period, ts: k.ts, count: k.kline.length }))
    }
  } catch {
    result.value = { quote: null, intraday: null, kline: [] }
  } finally {
    loading.value = false
  }
}

async function listAll() {
  loading.value = true
  try {
    const db = await openDb()
    const quotes = await getAll<{ code: string }>(db, 'quote')
    allCodes.value = [...new Set(quotes.map(q => q.code))].sort()
  } catch {
    allCodes.value = []
  } finally {
    loading.value = false
  }
}

function todayStr(): string {
  const d = new Date()
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

/**
 * 跟 utils/visiblePricePolling.ts 里的判断逻辑刻意保持一致但独立实现
 * （不 import 它）——调试面板的设计原则是不牵扯正式代码，见文件顶部注释。
 */
function checkTradingHours(): { inTradingHours: boolean; nowStr: string } {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai', hour12: false,
    weekday: 'short', hour: '2-digit', minute: '2-digit'
  }).formatToParts(new Date())
  const get = (t: string) => parts.find(p => p.type === t)?.value ?? ''
  const weekday = get('weekday')
  const hour = Number(get('hour'))
  const minute = Number(get('minute'))
  const weekdayCn: Record<string, string> = { Mon: '一', Tue: '二', Wed: '三', Thu: '四', Fri: '五', Sat: '六', Sun: '日' }
  const nowStr = `周${weekdayCn[weekday] ?? weekday} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
  if (weekday === 'Sat' || weekday === 'Sun') return { inTradingHours: false, nowStr }
  const mins = hour * 60 + minute
  const inTradingHours = (mins >= 9 * 60 + 30 && mins <= 11 * 60 + 30) || (mins >= 13 * 60 && mins <= 15 * 60)
  return { inTradingHours, nowStr }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function checkTraffic() {
  // 纯读内存变量，同步操作，不需要 loading 态
  traffic.value = getTrafficStats()
}

async function checkSyncStatus() {
  loading.value = true
  try {
    const db = await openDb()
    const metaRows = await getAll<{ key: string; value: unknown }>(db, 'meta')
    const state = metaRows.find(r => r.key === 'fullSyncState')?.value as
      { codes: string[]; nextIndex: number; dateStr: string } | undefined
    const klines = await getAll<{ code: string; period: string }>(db, 'kline')
    const syncedCount = klines.filter(k => k.period === 'day').length
    const { inTradingHours, nowStr } = checkTradingHours()
    syncStatus.value = {
      today: todayStr(),
      dateStr: state?.dateStr ?? null,
      nextIndex: state?.nextIndex ?? 0,
      total: state?.codes.length ?? 0,
      syncedCount,
      nowStr,
      inTradingHours
    }
  } catch {
    syncStatus.value = null
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.debug-panel {
  margin: 16px 4px; padding: 12px; border-radius: 12px;
  background: var(--bg-soft); border: 1px dashed var(--text-3);
  font-size: 12.5px;
}
.debug-title { font-weight: 600; margin-bottom: 10px; color: var(--text-2); }
.debug-row { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.debug-input {
  flex: 1; min-width: 120px; border: 1px solid var(--border); border-radius: 8px;
  padding: 7px 10px; font-size: 13px; background: var(--bg); color: var(--text);
}
.debug-btn {
  border: none; border-radius: 8px; padding: 7px 12px; font-size: 12.5px;
  background: var(--accent); color: #fff; cursor: pointer;
}
.debug-btn.ghost { background: transparent; color: var(--text-2); border: 1px solid var(--border); }
.debug-hint { color: var(--text-3); padding: 4px 0; }
.debug-section { margin-top: 10px; }
.debug-h { color: var(--text-3); margin-bottom: 4px; }
.debug-hit { color: var(--up); padding: 2px 0; }
.debug-miss { color: var(--down); padding: 2px 0; }
.debug-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.debug-chip {
  border: 1px solid var(--border); background: var(--bg); color: var(--text-2);
  border-radius: 12px; padding: 4px 10px; font-size: 12px; cursor: pointer;
}
</style>
