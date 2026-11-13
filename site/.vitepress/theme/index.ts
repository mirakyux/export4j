// SPDX-License-Identifier: Apache-2.0
import DefaultTheme from 'vitepress/theme-without-fonts'
import { h } from 'vue'
import type { Theme } from 'vitepress'
import Home from './Home.vue'
import LanguageSwitch from './LanguageSwitch.vue'
import './custom.css'
import './docs.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'nav-bar-content-after': () => h(LanguageSwitch, { class: 'language-switch-desktop' }),
    'nav-screen-content-after': () => h(LanguageSwitch, { class: 'language-switch-mobile' })
  }),
  enhanceApp({ app }) {
    app.component('Export4jHome', Home)
  }
} satisfies Theme
