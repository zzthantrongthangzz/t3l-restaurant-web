(() => {
  const key = 't3l-theme';
  const root = document.documentElement;
  let theme = 'light';
  try {
    if (localStorage.getItem(key) === 'dark') theme = 'dark';
  } catch {
    // The theme toggle also works for the current page when storage is disabled.
  }

  function apply(value) {
    root.dataset.theme = value;
    document.querySelectorAll('[data-theme-toggle]').forEach(button => {
      button.setAttribute('aria-pressed', String(value === 'dark'));
    });
  }

  apply(theme);
  document.addEventListener('DOMContentLoaded', () => {
    apply(theme);
    document.querySelectorAll('[data-theme-toggle]').forEach(button => {
      button.hidden = false;
      button.addEventListener('click', () => {
        theme = theme === 'dark' ? 'light' : 'dark';
        apply(theme);
        try {
          localStorage.setItem(key, theme);
        } catch {
          // The current page already shows the selected theme.
        }
      });
    });
  });
})();
