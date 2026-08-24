import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/base.css'
import { startIdlePreload } from './utils/preload'
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
