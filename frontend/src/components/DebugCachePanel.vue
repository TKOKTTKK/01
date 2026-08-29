<template>
  <div class="debug-panel">
    <div class="debug-title">🔧 缓存调试面板（临时，验证完请删除，见组件顶部注释）</div>
    <div class="debug-row">
      <input v-model="code" placeholder="股票代码，如 600519" class="debug-input"
        @keyup.enter="query" />
      <button class="debug-btn" @click="query">查询</button>
      <button class="debug-btn ghost" @click="listAll">看全部已缓存代码</button>
    </div>

    <div v-if="loading" class="debug-hint">查询中…</div>

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
 * ⚠️ 临时调试面板 —— 仅用于在真机上肉眼验证 IndexedDB 预取缓存是否命中，
 * 不是正式功能，不应该留在生产代码里。
 *
 * 用完删除方法（共 2 处）：
 * 1. 删掉本文件：frontend/src/components/DebugCachePanel.vue
 * 2. 打开 frontend/src/views/SettingsView.vue，删掉里面标了
 *    "// 调试面板：验证完删掉本段和 DebugCachePanel.vue" 的那几处代码
 * 两处都删完，`npm run build` 应该照常通过（没有任何其他文件引用这个组件）。
 *
 * 实现上直接用原生 IndexedDB API 只读打开 chartDiskCache.ts 建的那个库
 * （stock_app_chart_cache），不 import、不改动 chartDiskCache.ts 本身，
 * 删除这一个文件不会牵扯到任何正式代码。
 */
import { ref } from 'vue'

const DB_NAME = 'stock_app_chart_cache'

interface DebugResult {
  quote: { ts: number } | null
  intraday: { ts: number } | null
  kline: { period: string; ts: number; count: number }[]
}

const code = ref('')
const loading = ref(false)
const result = ref<DebugResult | null>(null)
const allCodes = ref<string[] | null>(null)

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
