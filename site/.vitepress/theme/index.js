import DefaultTheme from 'vitepress/theme';
import HorusHome from './HorusHome.vue';
import HorusDocsHome from './HorusDocsHome.vue';
import './contrast.css';
import './horus.css';

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    // Custom page layouts, chosen by the `layout` front matter of site/content pages.
    app.component('HorusHome', HorusHome);
    app.component('HorusDocsHome', HorusDocsHome);
  }
};
