import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: () => import('@/views/HomeView.vue'), meta: { tab: true } },
    { path: '/market', name: 'market', component: () => import('@/views/MarketView.vue'), meta: { tab: true } },
    { path: '/watchlist', name: 'watchlist', component: () => import('@/views/WatchlistView.vue'), meta: { tab: true } },
    { path: '/mine', name: 'mine', component: () => import('@/views/MineView.vue'), meta: { tab: true } },
    { path: '/search', name: 'search', component: () => import('@/views/SearchView.vue') },
    { path: '/stock/:code', name: 'stock', component: () => import('@/views/StockDetailView.vue') },
    { path: '/news/:id', name: 'news', component: () => import('@/views/NewsDetailView.vue') },
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ],
  scrollBehavior: () => ({ top: 0 })
})

export default router
