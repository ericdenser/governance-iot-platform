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


api.interceptors.response.use(
  (response) => {
    // Desempacota ApiResponse<T> automaticamente: { success, data, ... } → data
    if (response.data && typeof response.data === 'object' && 'success' in response.data) {
      response.data = response.data.data
    }
    return response
  },
  (error) => {
    // Garante que o servidor respondeu algo antes de checar o status
    if (error.response) {
      
      // 401: Sessão expirou ou não existe no Redis
      if (error.response.status === 401) {
        const authStore = useAuthStore()
        authStore.clearAuth()
        
        // Chuta pro login com Hard Reset (Limpa cache do Pinia de todas as abas)
        window.location.href = '/' 
      } 
      
      // 403: Está logado, mas não tem permissão — deixa o componente tratar

    }

    //SEMPRE rejeita a promessa no final para quebrar o fluxo lá no componente Vue
    return Promise.reject(error)
  }
)

export default api
