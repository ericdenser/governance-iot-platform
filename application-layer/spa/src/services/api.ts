import axios from 'axios'
import { useAuthStore } from '@/stores/auth'

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  headers: {
    'X-Requested-With': 'XMLHttpRequest'
  }
})

const NEXT_STORAGE_KEY = 'post_login_next'

let sessionExpiredHandled = false

const isSafeInternalPath = (path: string): boolean =>
  path.startsWith('/') && !path.startsWith('//') && !path.includes('..')

api.interceptors.response.use(
  (response) => {
    if (response.data && typeof response.data === 'object' && 'success' in response.data) {
      response.data = response.data.data
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401 && !sessionExpiredHandled) {
      const authStore = useAuthStore()
      authStore.clearAuth()

      // Já na tela de login: só limpa o estado. Redirecionar de novo recarrega
      // a página, zera este guard e cria loop infinito enquanto o BFF responder 401.
      if (window.location.pathname !== '/') {
        sessionExpiredHandled = true

        const currentPath = window.location.pathname + window.location.search
        if (isSafeInternalPath(currentPath)) {
          sessionStorage.setItem(NEXT_STORAGE_KEY, currentPath)
        }

        window.location.href = '/?expired=1'
      }
    }

    return Promise.reject(error)
  }
)

export default api
