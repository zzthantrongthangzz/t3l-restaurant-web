(() => {
  const key = 't3l-theme';
  const root = document.documentElement;
  document.addEventListener('DOMContentLoaded', () => {
    const buttons = [...document.querySelectorAll('[data-theme-toggle]')];
    const themeColor = document.createElement('meta');
    themeColor.name = 'theme-color';
    document.head.append(themeColor);
    const update = () => {
      themeColor.content = getComputedStyle(root).getPropertyValue('--surface').trim();
      for (const button of buttons) {
        button.setAttribute('aria-pressed', String(root.dataset.theme === 'dark'));
        button.hidden = false;
      }
    };
    update();
    for (const button of buttons) {
      button.addEventListener('click', () => {
        const theme = root.dataset.theme === 'dark' ? 'light' : 'dark';
        root.dataset.theme = theme;
        update();
        try { localStorage.setItem(key, theme); } catch {}
      });
    }
  }, { once: true });
})();
