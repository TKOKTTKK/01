import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getSimPortfolio } from '@/api'
import type { SimAccount, SimPosition } from '@/api/types'

export const useSimStore = defineStore('sim', () => {
  const account = ref<SimAccount | null>(null)
  const positions = ref<SimPosition[]>([])
  const loaded = ref(false)

  /**
   * 用合并接口一次取回账户 + 持仓。
   * 之前是两个独立请求，恰好跨过后端行情缓存过期边界时，
   * 「总资产」和「持仓市值之和」会对不上几分钱。
   */
  async function refresh() {
    const data = await getSimPortfolio()
    account.value = data.account
    positions.value = data.positions
    loaded.value = true
  }

  function reset() { account.value = null; positions.value = []; loaded.value = false }

  return { account, positions, loaded, refresh, reset }
})
