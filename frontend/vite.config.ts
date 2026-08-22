import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5173,
    proxy: {
      // 开发环境代理到本地后端
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: {
    // 拆分后单个 chunk 应远小于 1MB，超过说明又有大依赖被打进业务代码
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        /**
         * 手动分包：
         * - echarts 单独成 chunk：体积最大且变动极少，独立后可长期命中浏览器缓存，
         *   业务代码更新时用户无需重新下载图表库；
         * - vue 全家桶 + axios 合成 vendor：同样是低频变动的第三方依赖。
         * 二者都不会进入首屏入口 chunk 之外的业务页面，避免重复打包。
         */
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
          if (
            id.includes('/vue/') || id.includes('@vue/') ||
            id.includes('vue-router') || id.includes('pinia') ||
            id.includes('axios')
          ) return 'vendor'
        }
      }
    }
  }
})
