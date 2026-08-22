<template>
  <div class="page no-tab">
    <div class="nav">
      <button class="back" @click="$router.back()">‹</button>
      <div style="font-size:15px;font-weight:600">交易记录</div>
    </div>

    <div class="seg" style="margin-bottom: 12px;">
      <button :class="{ active: tab === 'trades' }" @click="tab = 'trades'">成交记录</button>
      <button :class="{ active: tab === 'flows' }" @click="tab = 'flows'">资金明细</button>
    </div>

    <div class="card" style="padding: 4px 14px;" v-if="tab === 'trades'">
      <div v-for="t in trades" :key="t.id" class="row">
        <div class="left">
          <div class="name">
            {{ t.name }}
            <span class="side" :class="t.side === 'BUY' ? 'up' : 'down'">
              {{ t.side === 'BUY' ? '买入' : '卖出' }}
            </span>
          </div>
          <div class="meta">{{ fmtTime(t.createdAt) }}</div>
        </div>
        <div class="right">
          <div class="amt">{{ t.quantity }}股 @{{ fmtPrice(t.price) }}</div>
          <div class="meta">{{ fmtMoney(t.amount) }}</div>
        </div>
      </div>
      <div v-if="loaded && trades.length === 0" class="empty">暂无交易记录</div>
      <div v-else-if="!loaded" class="skeleton" style="height:130px;margin:10px 0"></div>
    </div>

    <div class="card" style="padding: 4px 14px;" v-else>
      <div v-for="f in flows" :key="f.id" class="row">
        <div class="left">
          <div class="name">{{ f.description }}</div>
          <div class="meta">{{ fmtTime(f.createdAt) }} · 余额 {{ fmtMoney(f.balance) }}</div>
        </div>
        <div class="right">
          <div class="amt" :class="f.amount >= 0 ? 'up' : 'down'">
            {{ f.amount >= 0 ? '+' : '' }}{{ fmtMoney(f.amount) }}
          </div>
        </div>
      </div>
      <div v-if="loaded && flows.length === 0" class="empty">暂无资金明细</div>
      <div v-else-if="!loaded" class="skeleton" style="height:130px;margin:10px 0"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getSimCashFlows, getSimTrades } from '@/api'
import type { SimCashFlow, SimTrade } from '@/api/types'
import { fmtMoney, fmtPrice, fmtTime } from '@/utils/format'

const tab = ref<'trades' | 'flows'>('trades')
const trades = ref<SimTrade[]>([])
const flows = ref<SimCashFlow[]>([])
const loaded = ref(false)


onMounted(async () => {
  try {
    const [t, f] = await Promise.all([getSimTrades(50), getSimCashFlows(50)])
    trades.value = t
    flows.value = f
  } catch { /* 空状态即可 */ }
  loaded.value = true
})
</script>

<style scoped>
.nav { display: flex; align-items: center; gap: 6px; margin-bottom: 14px; }
.back { background: none; border: none; color: var(--text); font-size: 30px; line-height: 1; cursor: pointer; padding: 0 6px 4px 0; }
.row { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--border); }
.row:last-of-type { border-bottom: none; }
.name { font-size: 14px; font-weight: 600; }
.side { font-size: 11px; font-weight: 500; margin-left: 5px; }
.meta { font-size: 11px; color: var(--text-3); margin-top: 4px; }
.right { text-align: right; }
.amt { font-size: 14px; font-variant-numeric: tabular-nums; }
</style>
