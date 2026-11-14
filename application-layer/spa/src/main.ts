import { createApp } from 'vue'
import { createPinia } from 'pinia'

import '@fontsource/ibm-plex-sans/latin-400.css'
import '@fontsource/ibm-plex-sans/latin-500.css'
import '@fontsource/ibm-plex-sans/latin-600.css'
import '@fontsource/ibm-plex-mono/latin-400.css'
import '@fontsource/ibm-plex-mono/latin-500.css'
import '@/assets/styles/design-system.css'

import App from './App.vue'
import router from './router'
import { vModalDialog } from './directives/modalDialog'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.directive('modal-dialog', vModalDialog)

app.mount('#app')
