import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'login', component: LoginView },
    { path: '/home',     name: 'dashboard',  meta: { title: 'Dashboard' },    component: () => import('../views/DashboardView.vue') },
    { path: '/devices',  name: 'devices',    meta: { title: 'Dispositivos' }, component: () => import('../views/DevicesView.vue') },
    { path: '/devices/:id', name: 'device-detail', meta: { title: 'Dispositivo' }, component: () => import('../views/DeviceDetailView.vue') },
    { path: '/firmware', name: 'firmware',   meta: { title: 'Firmware' },     component: () => import('../views/FirmwareView.vue') },
    { path: '/firmware/:firmwareId', name: 'firmware-detail', meta: { title: 'Firmware' }, component: () => import('../views/FirmwareDetailView.vue') },
    { path: '/sensors',  name: 'sensors',    meta: { title: 'Sensores' },     component: () => import('../views/SensorsView.vue') },
    { path: '/commands', name: 'commands',   meta: { title: 'Comandos' },     component: () => import('../views/CommandsView.vue') },
    { path: '/events',   name: 'events',     meta: { title: 'Eventos' },      component: () => import('../views/EventsView.vue') },
    { path: '/errors',   name: 'errors',     meta: { title: 'Erros' },        component: () => import('../views/ErrorsView.vue') },
    { path: '/audit',    name: 'audit',      meta: { title: 'Auditoria', requiresAdmin: true }, component: () => import('../views/AuditView.vue') },
    { path: '/groups',   name: 'groups',     meta: { title: 'Grupos' },                         component: () => import('../views/GroupsView.vue') },
    { path: '/profile',  name: 'profile',    meta: { title: 'Perfil' },       component: () => import('../views/ProfileView.vue') },
  ],
})

router.beforeEach(async (to, _, next) => {
  const authStore = useAuthStore()
  await authStore.checkAuth()

  if (to.name !== 'login' && !authStore.isAuthenticated) return next({ name: 'login' })
  if (to.name === 'login' && authStore.isAuthenticated) return next({ name: 'dashboard' })
  if (to.meta.requiresAdmin && !authStore.isAdmin) return next({ name: 'dashboard' })

  next()
})

export default router
