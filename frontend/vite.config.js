import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 开发时可用 AIFANYI_API 指到别的后端端口（如并行跑第二个实例调试）
        target: process.env.AIFANYI_API || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
