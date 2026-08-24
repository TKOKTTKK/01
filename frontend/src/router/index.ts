import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'

/**
 * 视图 loader 表。
 *
 * 路由的 component 和「预加载」共用同一个函数引用，这样 Vite 生成的
 * 动态 import 只有一份、chunk 也只有一份；预加载过的组件在真正导航时
 * 直接命中模块缓存，不会二次发起网络请求。
 */
export const viewLoaders = {
  home: () => import('@/views/HomeView.vue'),
  market: () => import('@/views/MarketView.vue'),
  watchlist: () => import('@/views/WatchlistView.vue'),
  trade: () => import('@/views/TradeView.vue'),
  mine: () => import('@/views/MineView.vue'),
  order: () => import('@/views/OrderView.vue'),
  records: () => import('@/views/RecordsView.vue'),
  settings: () => import('@/views/SettingsView.vue'),
  search: () => import('@/views/SearchView.vue'),
  stock: () => import('@/views/StockDetailView.vue'),
  news: () => import('@/views/NewsDetailView.vue'),
  login: () => import('@/views/LoginView.vue'),
  register: () => import('@/views/RegisterView.vue')
} as const

export type ViewName = keyof typeof viewLoaders

/** 路径 -> 视图名，供 TabBar 悬停/触摸预加载使用 */
export const pathToView: Record<string, ViewName> = {
  '/': 'home',
  '/market': 'market',
  '/watchlist': 'watchlist',
  '/trade': 'trade',
  '/mine': 'mine'
}

const preloaded = new Set<ViewName>()

/** 触发某个视图的 chunk 下载（幂等，重复调用无副作用） */
export function preloadView(name: ViewName): void {
  if (preloaded.has(name)) return
  preloaded.add(name)
  viewLoaders[name]().catch(() => {
    // 预加载失败不影响正常导航，届时会重新走一次 import
    preloaded.delete(name)
  })
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: viewLoaders.home, meta: { tab: true } },
    { path: '/market', name: 'market', component: viewLoaders.market, meta: { tab: true } },
    { path: '/watchlist', name: 'watchlist', component: viewLoaders.watchlist, meta: { tab: true } },
    { path: '/trade', name: 'trade', component: viewLoaders.trade, meta: { tab: true } },
    { path: '/mine', name: 'mine', component: viewLoaders.mine, meta: { tab: true } },
    { path: '/trade/order/:code', name: 'order', component: viewLoaders.order },
    { path: '/trade/records', name: 'records', component: viewLoaders.records },
    { path: '/settings', name: 'settings', component: viewLoaders.settings },
    { path: '/search', name: 'search', component: viewLoaders.search },
    { path: '/stock/:code', name: 'stock', component: viewLoaders.stock },
    { path: '/news/:id', name: 'news', component: viewLoaders.news },
    { path: '/login', name: 'login', component: viewLoaders.login },
    { path: '/register', name: 'register', component: viewLoaders.register },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

// ---- Tab 页滚动位置保存与恢复（配合 KeepAlive）----
const savedScroll: Record<string, number> = {}
const keepScrollPaths = new Set(['/', '/market', '/watchlist', '/trade', '/mine'])

router.beforeEach((to, from) => {
  if (keepScrollPaths.has(from.path) || from.name === 'stock' || from.name === 'search') {
    savedScroll[from.fullPath] = window.scrollY
  }
})
router.afterEach((to) => {
  nextTick(() => {
    const y = (keepScrollPaths.has(to.path) || to.name === 'stock' || to.name === 'search')
      ? (savedScroll[to.fullPath] ?? 0) : 0
    window.scrollTo(0, y)
  })
})

export default router
