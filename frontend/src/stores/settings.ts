import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export type ThemeMode = 'system' | 'light' | 'dark'
export type ThemeColor = 'blue' | 'purple' | 'green' | 'orange'
export type PriceColorMode = 'red-up' | 'green-up'

const KEY = 'app_settings_v1'

interface Persisted {
  themeMode: ThemeMode
  themeColor: ThemeColor
  priceColorMode: PriceColorMode
}

function load(): Persisted {
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) return { themeMode: 'system', themeColor: 'blue', priceColorMode: 'red-up', ...JSON.parse(raw) }
  } catch { /* 忽略损坏的存储 */ }
  return { themeMode: 'system', themeColor: 'blue', priceColorMode: 'red-up' }
}

export const useSettingsStore = defineStore('settings', () => {
  const init = load()
  const themeMode = ref<ThemeMode>(init.themeMode)
  const themeColor = ref<ThemeColor>(init.themeColor)
  const priceColorMode = ref<PriceColorMode>(init.priceColorMode)

  const media = window.matchMedia('(prefers-color-scheme: dark)')

  /** 把设置落到 <html> 的 data 属性上，CSS 变量随之切换 */
  function apply() {
    const dark = themeMode.value === 'dark'
      || (themeMode.value === 'system' && media.matches)
    const el = document.documentElement
    el.dataset.theme = dark ? 'dark' : 'light'
    el.dataset.accent = themeColor.value
    el.dataset.price = priceColorMode.value
  }

  function persist() {
    localStorage.setItem(KEY, JSON.stringify({
      themeMode: themeMode.value,
      themeColor: themeColor.value,
      priceColorMode: priceColorMode.value
    }))
  }

  // 跟随系统：监听系统主题变化，实时切换
  media.addEventListener('change', () => {
    if (themeMode.value === 'system') apply()
  })

  watch([themeMode, themeColor, priceColorMode], () => { apply(); persist() })

  /** 清除缓存：只清 UI/行情/搜索缓存，不动登录信息、设置、自选与交易数据 */
  function clearCache() {
    localStorage.removeItem('market_cache_v1')
    localStorage.removeItem('recent_searches_v1')
  }

  return { themeMode, themeColor, priceColorMode, apply, clearCache }
})
