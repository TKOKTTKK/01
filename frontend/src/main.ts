import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/base.css'
import { startIdlePreload } from './utils/preload'
import { scheduleFullSync } from './utils/fullSync'
import { scheduleQuoteIntradaySync } from './utils/quoteIntradaySync'
import { startVisiblePricePolling } from './utils/visiblePricePolling'
import { useUiStore } from './stores/ui'

const app = createApp(App)
app.use(createPinia()).use(router)

/**
 * 全局错误兜底：任何未被组件捕获的异常都不应导致静默白屏。
 * 统一转成 toast 提示，并把详情打到控制台便于排查。
 */
function reportError(scope: string, err: unknown) {
  console.error(`[${scope}]`, err)
  try {
    useUiStore().toast('操作出错了，请重试', 'error')
  } catch {
    // Pinia 尚未就绪时忽略
  }
}

app.config.errorHandler = (err) => reportError('vue', err)
window.addEventListener('unhandledrejection', (e) => {
  reportError('promise', e.reason)
  e.preventDefault()
})

app.mount('#app')

// 挂载完成后，在浏览器空闲时后台预加载各 Tab 页与详情页的 chunk
startIdlePreload()

// 全量后台静默同步：把整个股票池的日K+指标提前拉到本地 IndexedDB，
// 详情见 utils/fullSync.ts。内部自己处理节流/断点续传/前台判断，这里
// 只管触发一次。
scheduleFullSync()

// 全量后台静默同步：分时图 + 实时行情快照版本，调度骨架跟上面日K同步
// 一样（网络空闲才推进），但交易时段内每 30 分钟重新扫一轮、非交易时段
// 只扫一轮，详情见 utils/quoteIntradaySync.ts。
scheduleQuoteIntradaySync()

// 可视区高频价格轮询：只在交易时段、只更新屏幕上看得见的股票，
// 详情见 utils/visiblePricePolling.ts。
startVisiblePricePolling()
