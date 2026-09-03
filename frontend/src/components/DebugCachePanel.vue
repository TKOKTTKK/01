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
    </div>

    <div class="debug-section">
      <div class="debug-h">
        🧪 Protobuf+Gzip vs JSON+Gzip 传输对比（用上面输入框里的股票代码）
      </div>
      <div class="debug-row">
        <button class="debug-btn" :class="{ ghost: benchProtocol !== 'json' }"
          @click="benchProtocol = 'json'">JSON+Gzip（现有）</button>
        <button class="debug-btn" :class="{ ghost: benchProtocol !== 'protobuf' }"
          @click="benchProtocol = 'protobuf'">Protobuf+Gzip（新）</button>
        <button class="debug-btn" :disabled="benchLoading" @click="runBench">
          {{ benchLoading ? '请求中…' : '发起一次请求' }}
        </button>
        <button class="debug-btn ghost" @click="resetBench">清空统计</button>
      </div>
      <div v-if="benchError" class="debug-miss">❌ {{ benchError }}</div>

      <table class="debug-table">
        <thead>
          <tr>
            <th>协议</th><th>次数</th><th>传输字节<br />(gzip后·均值)</th>
            <th>解压后字节<br />(均值)</th><th>压缩比</th><th>平均耗时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in (['json', 'protobuf'] as const)" :key="p">
            <td>{{ p === 'json' ? 'JSON+Gzip' : 'Protobuf+Gzip' }}</td>
            <td>
              {{ benchStats[p].count }}
              <span v-if="benchStats[p].count && benchStats[p].measuredCount < benchStats[p].count" class="debug-miss">
                （仅 {{ benchStats[p].measuredCount }} 次测到真实值）
              </span>
            </td>
            <td>{{ benchStats[p].count ? fmtBytes(avgEncodedBytes(benchStats[p])) : '-' }}</td>
            <td>{{ benchStats[p].count ? fmtBytes(Math.round(benchStats[p].totalDecodedBytes / benchStats[p].count)) : '-' }}</td>
            <td>{{ benchStats[p].count ? compressionRatio(benchStats[p]) : '-' }}</td>
            <td>{{ benchStats[p].count ? avgDuration(benchStats[p]) + ' ms' : '-' }}</td>
          </tr>
        </tbody>
      </table>

      <div v-if="benchStats.json.count && benchStats.protobuf.count" class="debug-hit">
        Protobuf 平均比 JSON 少传 {{ fmtBytes(avgEncodedBytes(benchStats.json) - avgEncodedBytes(benchStats.protobuf)) }}
        （{{ bytesSavedPercent() }}%），基于目前各自 {{ benchStats.json.count }} / {{ benchStats.protobuf.count }} 次样本，
        样本少的时候这个百分比会抖动，多测几次再看。
      </div>

      <div v-if="benchLog.length" class="debug-section">
        <div class="debug-h">最近请求明细（最多留 20 条，新的在最上面）</div>
        <div v-for="(l, i) in benchLog" :key="i" :class="l.ok ? 'debug-hit' : 'debug-miss'">
          {{ l.protocol === 'json' ? 'JSON' : 'PB ' }} · {{ l.code }} ·
          <template v-if="l.ok">
            传输 {{ fmtBytes(l.encodedBytes) }} / 解压后 {{ fmtBytes(l.decodedBytes) }} /
            {{ Math.round(l.durationMs) }}ms{{ l.measured ? '' : '（未测到真实传输字节，已回退成解压后大小，见下方说明）' }}
            · {{ l.detail }}
          </template>
          <template v-else>{{ l.detail }}</template>
        </div>
      </div>
    </div>

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
 * 全量后台同步是否命中/正常推进，不是正式功能，不应该留在生产代码里。
 *
 * 用完删除方法（共 2 处）：
 * 1. 删掉本文件：frontend/src/components/DebugCachePanel.vue
 * 2. 打开 frontend/src/views/SettingsView.vue，删掉里面标了
 *    "// 调试面板：验证完删掉本段和 DebugCachePanel.vue" 的那几处代码
 * 两处都删完，`npm run build` 应该照常通过（没有任何其他文件引用这个组件）。
 *
 * 实现上直接用原生 IndexedDB API 只读打开 chartDiskCache.ts 建的那个库
 * （stock_app_chart_cache），不 import、不改动 chartDiskCache.ts /
 * fullSync.ts 本身，删除这一个文件不会牵扯到任何正式代码。
 *
 * 【2026-09 新增】灰度对比区块额外 import 了两个 Protobuf+Gzip POC 相关的
 * 文件：api/protoQuoteIntraday.ts 和 utils/protocolBench.ts。这两个文件是
 * 独立的工具模块，不是"只为调试面板存在"，如果后面真的往正式页面接入
 * Protobuf 协议，它们会被正式代码复用、不能跟着这个文件一起删；如果是
 * 单纯验证完毕、决定不采用 Protobuf 方案，才需要连这两个文件一并删除。
 */
import { ref, reactive } from 'vue'
import { useUserStore } from '@/stores/user'
import { measureRequest } from '@/utils/protocolBench'
import { quoteIntradayProtoUrl, decodeQuoteIntradayResponse } from '@/api/protoQuoteIntraday'

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

/**
 * Protobuf+Gzip vs JSON+Gzip 灰度对比：
 * - JSON 侧：并行发起 /quote + /intraday 两个请求，跟现在真实调用现状对齐
 *   （见 api/index.ts 的 getQuote/getIntraday，页面上这两个接口本来就是分开调的）。
 * - Protobuf 侧：单次请求 /quote-intraday.pb。
 * 每次点"发起一次请求"都会往 benchStats 里累计一条样本，方便切换协议后
 * 多测几次、比较两边平均传输字节数和耗时的差异，不是只看单次结果。
 */
type BenchProtocol = 'json' | 'protobuf'

interface BenchStat {
  count: number
  totalEncodedBytes: number
  totalDecodedBytes: number
  totalDurationMs: number
  /** 有多少次样本是从 Resource Timing 拿到的真实传输字节数，
   *  而不是拿不到时回退的"解压后大小"估算值——回退值会让压缩比显示成
   *  失真的 1.0 附近，这个计数是给自己提个醒，不是所有样本都靠谱。 */
  measuredCount: number
}

function emptyBenchStat(): BenchStat {
  return { count: 0, totalEncodedBytes: 0, totalDecodedBytes: 0, totalDurationMs: 0, measuredCount: 0 }
}

interface BenchLogEntry {
  protocol: BenchProtocol
  code: string
  encodedBytes: number
  decodedBytes: number
  durationMs: number
  measured: boolean
  ok: boolean
  detail: string
}

const benchProtocol = ref<BenchProtocol>('json')
const benchLoading = ref(false)
const benchError = ref('')
const benchStats = reactive<Record<BenchProtocol, BenchStat>>({
  json: emptyBenchStat(),
  protobuf: emptyBenchStat()
})
const benchLog = ref<BenchLogEntry[]>([])

function apiBase(): string {
  return (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
}

/** 生产环境前后端跨域（Cloudflare/Railway），带上跟正式请求一样的鉴权头，
 *  measureRequest 里用的裸 fetch 不会像 axios 实例那样自动附带它。
 *  这几个接口本身是 permitAll 的公开只读接口，不带这个头也能通，带上只是
 *  为了让测量条件跟真实请求尽量一致。 */
function authHeaders(): Record<string, string> {
  const store = useUserStore()
  return store.token ? { Authorization: `Bearer ${store.token}` } : {}
}

function recordBench(entry: Omit<BenchLogEntry, 'ok'> & { ok: boolean }) {
  benchLog.value.unshift(entry)
  if (benchLog.value.length > 20) benchLog.value.pop()
  if (!entry.ok) return
  const s = benchStats[entry.protocol]
  s.count++
  s.totalEncodedBytes += entry.encodedBytes
  s.totalDecodedBytes += entry.decodedBytes
  s.totalDurationMs += entry.durationMs
  if (entry.measured) s.measuredCount++
}

async function runBench() {
  const c = code.value.trim()
  if (!c) return
  benchError.value = ''
  benchLoading.value = true
  try {
    if (benchProtocol.value === 'json') {
      // 两个请求并行发，跟页面真实调用方式一致；耗时按"这一组请求整体
      // 完成花了多久"算，不是把两次耗时相加——并行请求相加会人为放大数字。
      const groupStart = performance.now()
      const [quoteM, intradayM] = await Promise.all([
        measureRequest(`${apiBase()}/api/stocks/${c}/quote`, { headers: authHeaders() }),
        measureRequest(`${apiBase()}/api/stocks/${c}/intraday`, { headers: authHeaders() })
      ])
      const groupDurationMs = performance.now() - groupStart

      const quoteBody = JSON.parse(new TextDecoder().decode(quoteM.buffer)) as { data?: { price?: number } }
      const intradayBody = JSON.parse(new TextDecoder().decode(intradayM.buffer)) as {
        data?: { points?: unknown[] }
      }
      const pointsCount = intradayBody.data?.points?.length ?? 0

      recordBench({
        protocol: 'json',
        code: c,
        encodedBytes: quoteM.encodedBytes + intradayM.encodedBytes,
        decodedBytes: quoteM.decodedBytes + intradayM.decodedBytes,
        durationMs: groupDurationMs,
        measured: quoteM.measured && intradayM.measured,
        ok: true,
        detail: `quote+intraday 两次并行请求 · 现价 ${quoteBody.data?.price ?? '?'} · 分时点 ${pointsCount} 个`
      })
    } else {
      const m = await measureRequest(`${apiBase()}${quoteIntradayProtoUrl(c)}`, {
        headers: { Accept: 'application/x-protobuf', ...authHeaders() }
      })
      const decoded = decodeQuoteIntradayResponse(m.buffer)
      if (decoded.code !== 0) throw new Error(decoded.message || '业务返回错误')
      const pointsCount = decoded.data?.points.length ?? 0

      recordBench({
        protocol: 'protobuf',
        code: c,
        encodedBytes: m.encodedBytes,
        decodedBytes: m.decodedBytes,
        durationMs: m.durationMs,
        measured: m.measured,
        ok: true,
        detail: `单次请求 · 现价定点值 ${String(decoded.data?.price ?? '?')} · 分时点 ${pointsCount} 个`
      })
    }
  } catch (err) {
    const message = err instanceof Error ? err.message : '请求失败'
    benchError.value = message
    recordBench({
      protocol: benchProtocol.value, code: c, encodedBytes: 0, decodedBytes: 0,
      durationMs: 0, measured: false, ok: false, detail: message
    })
  } finally {
    benchLoading.value = false
  }
}

function resetBench() {
  benchLog.value = []
  benchStats.json = emptyBenchStat()
  benchStats.protobuf = emptyBenchStat()
  benchError.value = ''
}

function fmtBytes(n: number): string {
  if (n < 1024) return `${Math.round(n)} B`
  return `${(n / 1024).toFixed(2)} KB`
}

function avgEncodedBytes(s: BenchStat): number {
  return s.count ? Math.round(s.totalEncodedBytes / s.count) : 0
}

function avgDuration(s: BenchStat): number {
  return s.count ? Math.round(s.totalDurationMs / s.count) : 0
}

function compressionRatio(s: BenchStat): string {
  if (!s.count || s.totalEncodedBytes === 0) return '-'
  return (s.totalDecodedBytes / s.totalEncodedBytes).toFixed(2)
}

function bytesSavedPercent(): string {
  const j = avgEncodedBytes(benchStats.json)
  const p = avgEncodedBytes(benchStats.protobuf)
  if (!j) return '0'
  return (((j - p) / j) * 100).toFixed(1)
}

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
.debug-table {
  width: 100%; border-collapse: collapse; margin: 6px 0 8px; font-size: 12px;
}
.debug-table th, .debug-table td {
  border: 1px solid var(--border); padding: 5px 6px; text-align: center;
}
.debug-table th { color: var(--text-3); font-weight: 500; background: var(--bg); }
.debug-btn[disabled] { opacity: .6; cursor: not-allowed; }
</style>
