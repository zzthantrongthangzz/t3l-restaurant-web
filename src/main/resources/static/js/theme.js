(() => {
  const key = 't3l-theme';
  const root = document.documentElement;
  document.addEventListener('DOMContentLoaded', () => {
    const buttons = [...document.querySelectorAll('[data-theme-toggle]')];
    const update = () => buttons.forEach(button => {
      button.setAttribute('aria-pressed', String(root.dataset.theme === 'dark'));
      button.hidden = false;
    });
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
