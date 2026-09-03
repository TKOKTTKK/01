<template>
  <div class="page">
    <div class="section-title">
      模拟交易
      <span class="mock-badge">虚拟资金，不产生真实交易</span>
    </div>

    <template v-if="userStore.isLoggedIn()">
      <!-- 账户总览 -->
      <div class="card acct" v-if="sim.account">
        <div class="alabel">总资产</div>
        <div class="atotal">{{ fmtMoney(sim.account.totalAssets) }}</div>
        <div class="arow">
          <div>
            <div class="alabel">今日盈亏</div>
            <div :class="cls(sim.account.todayProfit)">{{ fmtSigned(sim.account.todayProfit) }}</div>
          </div>
          <div>
            <div class="alabel">总收益</div>
            <div :class="cls(sim.account.totalProfit)">{{ fmtSigned(sim.account.totalProfit) }}</div>
          </div>
          <div>
            <div class="alabel">收益率</div>
            <div :class="cls(sim.account.totalProfitRate)">{{ fmtSigned(sim.account.totalProfitRate) }}%</div>
          </div>
        </div>
        <div class="arow sub">
          <div><div class="alabel">可用资金</div><div>{{ fmtMoney(sim.account.availableCash) }}</div></div>
          <div><div class="alabel">持仓市值</div><div>{{ fmtMoney(sim.account.positionMarketValue) }}</div></div>
          <div><div class="alabel">冻结资金</div><div>{{ fmtMoney(sim.account.frozenCash) }}</div></div>
        </div>
      </div>
      <div v-else class="skeleton" style="height:150px;margin-bottom:12px"></div>

      <div class="section-title">
        我的持仓
        <span class="more" @click="$router.push('/trade/records')">交易记录 ›</span>
      </div>
      <div class="card" style="padding: 2px 14px;">
        <div v-for="p in sim.positions" :key="p.stockId" class="pos"
          @click="cancelOtherPrefetches(p.code); $router.push(`/stock/${p.code}`)">
          <div class="phead">
            <div>
              <span class="pname">{{ p.name }}</span>
              <span class="pcode">{{ p.code }}</span>
            </div>
            <span class="pill" :class="cls(p.profit)">{{ fmtSigned(p.profitRate) }}%</span>
          </div>
          <div class="pgrid">
            <span>持仓 <b>{{ p.quantity }}</b></span>
            <span>可卖 <b>{{ p.availableQuantity }}</b></span>
            <span>成本 <b>{{ fmtPrice(p.avgCost) }}</b></span>
            <span>现价 <b>{{ fmtPrice(p.price) }}</b></span>
            <span>市值 <b>{{ fmtMoney(p.marketValue) }}</b></span>
            <span>盈亏 <b :class="cls(p.profit)">{{ fmtSigned(p.profit) }}</b></span>
          </div>
          <div class="pbtns">
            <button class="buy" @click.stop="$router.push(`/trade/order/${p.code}?side=buy`)">买入</button>
            <button class="sell" @click.stop="$router.push(`/trade/order/${p.code}?side=sell`)">卖出</button>
          </div>
        </div>
        <div v-if="sim.loaded && sim.positions.length === 0" class="empty">
          暂无持仓<br /><br />
          <span class="link" @click="$router.push('/market')">去行情</span>
        </div>
        <div v-else-if="!sim.loaded" class="skeleton" style="height:130px;margin:10px 0"></div>
      </div>
    </template>

    <div v-else class="card empty">
      <span class="link" @click="$router.push('/login')">登录</span> 后使用模拟交易
    </div>
  </div>
</template>

<script setup lang="ts">
import { onActivated, onDeactivated, onMounted } from 'vue'
import { useSimStore } from '@/stores/sim'
import { useUserStore } from '@/stores/user'
import { changeClass, fmtMoney, fmtPrice, fmtSigned } from '@/utils/format'
import { cancelOtherPrefetches } from '@/utils/detailPrefetch'

defineOptions({ name: 'TradeView' })

const sim = useSimStore()
const userStore = useUserStore()
let timer: number | undefined

const cls = (v: number | null | undefined) => changeClass(v)

function start() {
  if (!userStore.isLoggedIn()) return
  sim.refresh().catch(() => { /* 静默 */ })
  timer = window.setInterval(() => {
    if (document.visibilityState === 'visible') sim.refresh().catch(() => { /* 静默 */ })
  }, 10000)
}
function stop() { window.clearInterval(timer); timer = undefined }

onMounted(start)
onActivated(() => { if (timer === undefined) start() })
onDeactivated(stop)
</script>

<style scoped>
.acct { text-align: left; }
.alabel { font-size: 11px; color: var(--text-3); }
.atotal { font-size: 32px; font-weight: 700; margin: 4px 0 12px; font-variant-numeric: tabular-nums; }
.arow { display: flex; justify-content: space-between; margin-bottom: 10px; }
.arow > div { flex: 1; font-size: 14px; font-variant-numeric: tabular-nums; }
.arow.sub { border-top: 1px solid var(--border); padding-top: 10px; margin-bottom: 0; color: var(--text-2); font-size: 13px; }
.pos { padding: 13px 0; border-bottom: 1px solid var(--border); cursor: pointer; }
.pos:last-of-type { border-bottom: none; }
.phead { display: flex; justify-content: space-between; align-items: center; }
.pname { font-size: 15px; font-weight: 600; }
.pcode { font-size: 11px; color: var(--text-3); margin-left: 6px; }
.pill { padding: 4px 10px; border-radius: 8px; font-size: 12.5px; font-variant-numeric: tabular-nums; }
.pill.up { background: var(--up-bg); }
.pill.down { background: var(--down-bg); }
.pgrid {
  display: grid; grid-template-columns: repeat(3, 1fr);
  gap: 5px 8px; margin: 9px 0; font-size: 12px; color: var(--text-3);
}
.pgrid b { color: var(--text-2); font-weight: 500; margin-left: 2px; font-variant-numeric: tabular-nums; }
.pbtns { display: flex; gap: 8px; }
.pbtns button {
  flex: 1; border: none; border-radius: 9px; padding: 8px 0;
  font-size: 13px; font-weight: 600; cursor: pointer; color: #fff;
}
.pbtns .buy { background: var(--up); }
.pbtns .sell { background: var(--down); }
</style>
