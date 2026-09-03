import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Vite 开发服务器配置
// 前端固定运行在 http://localhost:5174（strictPort 防止端口被占用时自动切换）
// /chat 开头的请求代理到后端 Spring Boot http://localhost:8080
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        // 第三方库分包：依赖不变则 chunk 缓存复用，主包更小
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          if (id.includes('highlight.js')) return 'vendor-hljs'
          if (id.includes('marked')) return 'vendor-marked'
          if (id.includes('vue')) return 'vendor-vue'
        }
      }
    }
  },
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/chat': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true
      },
      '/rag': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/models': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
