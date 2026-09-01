import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import './styles.css'

const app = createApp(App)
app.config.errorHandler = (err, instance, info) => {
  // eslint-disable-next-line no-console
  console.error('[VUE-HANDLER]', info, err && err.message, err && err.stack)
}
app.use(router).mount('#app')
