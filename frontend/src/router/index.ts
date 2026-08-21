import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { tab: true } },
    { path: '/market', name: 'market', component: () => import('@/views/MarketView.vue'), meta: { tab: true } },
    { path: '/watchlist', name: 'watchlist', component: () => import('@/views/WatchlistView.vue'), meta: { tab: true } },
    { path: '/trade', name: 'trade', component: () => import('@/views/TradeView.vue'), meta: { tab: true } },
    { path: '/mine', name: 'mine', component: () => import('@/views/MineView.vue'), meta: { tab: true } },
    { path: '/trade/order/:code', name: 'order', component: () => import('@/views/OrderView.vue') },
    { path: '/trade/records', name: 'records', component: () => import('@/views/RecordsView.vue') },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue') },
    { path: '/stock/:code', name: 'stock', component: () => import('@/views/StockDetailView.vue') },
    { path: '/news/:id', name: 'news', component: () => import('@/views/NewsDetailView.vue') },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue') },
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
