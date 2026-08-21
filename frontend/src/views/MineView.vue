<template>
  <div class="page">
    <div class="section-title">我的</div>

    <div class="card profile" v-if="userStore.isLoggedIn()">
      <div class="avatar">{{ initial }}</div>
      <div>
        <div class="uname">{{ userStore.user?.username }}</div>
        <div class="udesc">注册于 {{ fmtTime(userStore.user?.createdAt) || '--' }}</div>
      </div>
    </div>
    <div class="card profile" v-else @click="$router.push('/login')" style="cursor:pointer">
      <div class="avatar">?</div>
      <div>
        <div class="uname">未登录</div>
        <div class="udesc">点击登录 / 注册</div>
      </div>
    </div>

    <!-- 模拟交易账户卡片 -->
    <div class="card sim-card" v-if="userStore.isLoggedIn() && sim.account"
      @click="$router.push('/trade')">
      <div>
        <div class="slabel">模拟交易账户 <span class="mock-badge">虚拟资金</span></div>
        <div class="sassets">{{ fmtMoney(sim.account.totalAssets) }}</div>
      </div>
      <div class="srate" :class="changeClass(sim.account.totalProfitRate)">
        {{ sim.account.totalProfitRate > 0 ? '+' : '' }}{{ sim.account.totalProfitRate.toFixed(2) }}%
        <span class="arrow">›</span>
      </div>
    </div>

    <div class="card list">
      <div class="item" @click="$router.push('/watchlist')">
        <span>我的自选</span><span class="arrow">›</span>
      </div>
      <div class="item" @click="$router.push('/trade')">
        <span>模拟交易账户</span><span class="arrow">›</span>
      </div>
      <div class="item" @click="$router.push('/market')">
        <span>市场行情</span><span class="arrow">›</span>
      </div>
      <div class="item">
        <span>行情数据源</span>
        <span class="mock-badge">模拟行情（非真实数据）</span>
      </div>
      <div class="item" @click="$router.push('/settings')">
        <span>设置</span><span class="arrow">›</span>
      </div>
      <div class="item">
        <span>关于</span><span class="arrow" style="font-size:13px">v2.0.0</span>
      </div>
    </div>

    <button v-if="userStore.isLoggedIn()" class="btn ghost" @click="onLogout">退出登录</button>

    <p class="disclaimer">
      本应用当前展示的所有行情、指数与新闻均为系统生成的模拟数据，
      模拟交易使用虚拟资金，仅用于产品演示，不构成任何投资建议。
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useSimStore } from '@/stores/sim'
import { useWatchlistStore } from '@/stores/watchlist'
import { changeClass, fmtTime } from '@/utils/format'

defineOptions({ name: 'MineView' })

const router = useRouter()
const userStore = useUserStore()
const sim = useSimStore()
const watchlist = useWatchlistStore()
const initial = computed(() => (userStore.user?.username || '?').slice(0, 1).toUpperCase())

const fmtMoney = (v: number) =>
  v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function ensure() {
  if (userStore.isLoggedIn() && !sim.loaded) sim.refresh().catch(() => { /* 静默 */ })
}
onMounted(ensure)
onActivated(ensure)

function onLogout() {
  userStore.logout()
  sim.reset()
  watchlist.reset()
  router.push('/')
}
</script>

<style scoped>
.profile { display: flex; align-items: center; gap: 14px; }
.avatar {
  width: 52px; height: 52px; border-radius: 50%;
  background: linear-gradient(135deg, var(--accent), #6a5cff);
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; font-weight: 700; color: #fff;
}
.uname { font-size: 17px; font-weight: 700; }
.udesc { font-size: 12px; color: var(--text-3); margin-top: 4px; }
.sim-card { display: flex; align-items: center; justify-content: space-between; cursor: pointer; }
.slabel { font-size: 12px; color: var(--text-3); }
.sassets { font-size: 22px; font-weight: 700; margin-top: 6px; font-variant-numeric: tabular-nums; }
.srate { font-size: 15px; font-variant-numeric: tabular-nums; display: flex; align-items: center; gap: 4px; }
.list { padding: 4px 14px; }
.item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 15px 0; border-bottom: 1px solid var(--border);
  font-size: 14.5px; cursor: pointer;
}
.item:last-child { border-bottom: none; }
.arrow { color: var(--text-3); font-size: 18px; }
.disclaimer { font-size: 11px; color: var(--text-3); line-height: 1.7; margin-top: 18px; padding: 0 4px; }
</style>
