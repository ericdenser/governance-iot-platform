import api from './api'
export const usersApi = {
    list: () => api.get('/users'),
}