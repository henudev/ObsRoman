import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from './views/DashboardView.vue'
import SearchView from './views/SearchView.vue'
import TraceView from './views/TraceView.vue'
import ApiDocsView from './views/ApiDocsView.vue'
import SdkDocsView from './views/SdkDocsView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/search', name: 'search', component: SearchView },
    { path: '/trace/:traceId', name: 'trace', component: TraceView, props: true },
    { path: '/docs', name: 'docs', component: ApiDocsView },
    { path: '/sdk', name: 'sdk', component: SdkDocsView }
  ]
})
