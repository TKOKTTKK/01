import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getSimAccount, getSimPositions } from '@/api'
import type { SimAccount, SimPosition } from '@/api/types'

export const useSimStore = defineStore('sim', () => {
  const account = ref<SimAccount | null>(null)
  const positions = ref<SimPosition[]>([])
  const loaded = ref(false)

  async function refresh() {
    const [acc, pos] = await Promise.all([getSimAccount(), getSimPositions()])
    account.value = acc
    positions.value = pos
    loaded.value = true
  }

  function reset() { account.value = null; positions.value = []; loaded.value = false }

  return { account, positions, loaded, refresh, reset }
})
