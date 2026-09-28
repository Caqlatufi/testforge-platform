import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': `http://127.0.0.1:${process.env.TESTFORGE_SERVER_PORT || '8081'}`,
      '/actuator': `http://127.0.0.1:${process.env.TESTFORGE_SERVER_PORT || '8081'}`,
    },
  },
})
