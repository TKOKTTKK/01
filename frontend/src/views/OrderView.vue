<template>
  <div class="page no-tab">
    <div class="nav">
      <button class="back" @click="$router.back()">‹</button>
      <div class="title">
        <div class="tname">{{ side === 'buy' ? '买入' : '卖出' }} {{ stock?.name || code }}</div>
        <div class="tcode">{{ code }} <span class="mock-badge">模拟交易</span></div>
      </div>
    </div>

    <div class="seg" style="margin-bottom: 14px;">
      <button :class="{ active: side === 'buy' }" @click="side = 'buy'">买入</button>
      <button :class="{ active: side === 'sell' }" @click="side = 'sell'">卖出</button>
    </div>

    <div class="card">
      <div class="line"><span>当前价格</span><b class="price">{{ fmtPrice(quote?.price) }}</b></div>
      <div class="line" v-if="side === 'buy'">
        <span>可用资金</span><b>{{ fmtMoney(sim.account?.availableCash) }}</b>
      </div>
      <template v-else>
        <div class="line"><span>持仓数量</span><b>{{ position?.quantity ?? 0 }}</b></div>
        <div class="line"><span>可卖数量</span><b>{{ position?.availableQuantity ?? 0 }}</b></div>
      </template>

      <div class="qty-label">{{ side === 'buy' ? '买入数量（100 股整数倍）' : '卖出数量' }}</div>
      <input v-model.number="qty" type="number" inputmode="numeric" class="input"
        :placeholder="side === 'buy' ? '如 100' : '不超过可卖数量'" min="0" :step="100" />
      <div class="quick">
        <button v-for="f in fractions" :key="f.label" @click="qty = f.calc()">{{ f.label }}</button>
      </div>

      <div class="line total">
        <span>预计金额</span>
        <b :class="side === 'buy' ? 'up' : 'down'">{{ fmtMoney(estAmount) }}</b>
      </div>
    </div>

    <button class="btn" :class="side" :disabled="submitting || !valid" @click="submit">
      {{ submitting ? '委托中…' : (side === 'buy' ? '确认买入' : '确认卖出') }}
    </button>
    <p class="hint" v-if="hint">{{ hint }}</p>
    <p class="disclaimer">模拟交易使用虚拟资金，按当前模拟行情价格立即成交，不产生真实交易。</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getQuote, getStock, placeSimOrder } from '@/api'
import type { Quote, SimPosition, StockItem } from '@/api/types'
import { useSimStore } from '@/stores/sim'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { fmtMoney, fmtPrice } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const sim = useSimStore()
const ui = useUiStore()
const userStore = useUserStore()

const code = String(route.params.code)
const side = ref<'buy' | 'sell'>(route.query.side === 'sell' ? 'sell' : 'buy')
const stock = ref<StockItem | null>(null)
const quote = ref<Quote | null>(null)
const qty = ref<number | null>(null)
const submitting = ref(false)
let timer: number | undefined

const position = computed<SimPosition | undefined>(
  () => sim.positions.find(p => p.code === code))


const estAmount = computed(() => {
  const p = quote.value?.price
  if (!p || !qty.value || qty.value <= 0) return null
  return p * qty.value
})

// 快捷比例：买入按可用资金测算最大手数；卖出按可卖数量
const fractions = computed(() => {
  const mk = (label: string, ratio: number) => ({
    label,
    calc: () => {
      if (side.value === 'buy') {
        const cash = sim.account?.availableCash ?? 0
        const p = quote.value?.price ?? 0
        if (p <= 0) return 0
        const maxLots = Math.floor(cash / (p * 100))
        return Math.max(0, Math.floor(maxLots * ratio)) * 100
      }
      const avail = position.value?.availableQuantity ?? 0
      if (ratio === 1) return avail
      return Math.floor((avail * ratio) / 100) * 100
    }
  })
  return [mk('1/4', 0.25), mk('1/2', 0.5), mk('3/4', 0.75),
    mk(side.value === 'buy' ? '全仓' : '全部', 1)]
})

const hint = computed(() => {
  if (!qty.value || qty.value <= 0) return ''
  if (side.value === 'buy') {
    if (qty.value % 100 !== 0) return '买入数量必须是 100 股的整数倍'
    if (estAmount.value !== null && (sim.account?.availableCash ?? 0) < estAmount.value) return '可用资金不足'
  } else {
    if (qty.value > (position.value?.availableQuantity ?? 0)) return '卖出数量超过可卖数量'
  }
  return ''
})
const valid = computed(() => !!qty.value && qty.value > 0 && !hint.value && !!quote.value)

async function loadQuote() {
  try { quote.value = await getQuote(code) } catch { /* 保持已有 */ }
}

async function submit() {
  if (!valid.value || !quote.value) return
  const ok = await ui.confirm({
    title: side.value === 'buy' ? '确认买入' : '确认卖出',
    lines: [
      `股票：${stock.value?.name ?? code}（${code}）`,
      `价格：${fmtPrice(quote.value.price)}`,
      `数量：${qty.value} 股`,
      `金额：${fmtMoney(estAmount.value)}`
    ],
    confirmText: side.value === 'buy' ? '确认买入' : '确认卖出',
    danger: side.value === 'sell'
  })
  if (!ok) return
  submitting.value = true
  try {
    const t = await placeSimOrder(code, side.value === 'buy' ? 'BUY' : 'SELL', qty.value!)
    ui.toast(`${side.value === 'buy' ? '买入' : '卖出'}成功 ${t.quantity}股 @${fmtPrice(t.price)}`, 'success')
    await sim.refresh()
    router.back()
  } catch (e) {
    ui.toast((e as Error).message, 'error')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!userStore.isLoggedIn()) {
    router.replace({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  try {
    stock.value = await getStock(code)
    await Promise.all([loadQuote(), sim.loaded ? Promise.resolve() : sim.refresh()])
  } catch (e) {
    ui.toast((e as Error).message, 'error')
    router.back()
    return
  }
  // 加 visibility 守卫：锁屏/切后台时不再空转请求
  timer = window.setInterval(() => {
    if (document.visibilityState === 'visible') loadQuote()
  }, 5000)
})
onUnmounted(() => window.clearInterval(timer))
</script>

<style scoped>
.nav { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.back { background: none; border: none; color: var(--text); font-size: 30px; line-height: 1; cursor: pointer; padding: 0 6px 4px 0; }
.tname { font-size: 17px; font-weight: 700; }
.tcode { font-size: 11px; color: var(--text-3); margin-top: 2px; }
.line { display: flex; justify-content: space-between; font-size: 14px; color: var(--text-2); padding: 6px 0; }
.line b { color: var(--text); font-variant-numeric: tabular-nums; font-weight: 600; }
.line .price { font-size: 17px; }
.line.total { border-top: 1px solid var(--border); margin-top: 12px; padding-top: 12px; }
.qty-label { font-size: 12px; color: var(--text-3); margin: 14px 0 8px; }
.quick { display: flex; gap: 8px; margin-top: 10px; }
.quick button {
  flex: 1; border: 1px solid var(--border); background: var(--bg-soft);
  color: var(--text-2); border-radius: 9px; padding: 8px 0; font-size: 13px; cursor: pointer;
}
.btn.buy { background: var(--up); }
.btn.sell { background: var(--down); }
.hint { color: var(--up); font-size: 13px; text-align: center; margin-top: 10px; }
.disclaimer { font-size: 11px; color: var(--text-3); line-height: 1.7; margin-top: 16px; text-align: center; }
</style>
