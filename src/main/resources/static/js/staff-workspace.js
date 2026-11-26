window.t3lPage.register(({ signal }) => {
  const shell = document.querySelector('.staff-workspace-shell');
  const main = document.querySelector('main[data-navigation]');
  if (!shell || !main) return;

  const topbar = shell.querySelector('.staff-topbar');
  const sidebar = shell.querySelector('#staff-sidebar');
  const toggle = shell.querySelector('[data-staff-sidebar-toggle]');
  const close = sidebar.querySelector('[data-staff-sidebar-close]');
  const root = document.documentElement;
  const mobile = window.matchMedia('(max-width: 760px)');
  const tablet = window.matchMedia('(max-width: 1080px)');
  const savedKey = 't3l-staff-sidebar';
  const activeLink = sidebar.querySelector('[aria-current="page"] .staff-rail-label');
  if (activeLink) shell.querySelector('[data-staff-section]').textContent = activeLink.textContent;

  const isDrawerOpen = () => shell.hasAttribute('data-staff-drawer-open');
  const isCompact = () => root.dataset.staffSidebar === 'compact'
    || (tablet.matches && root.dataset.staffSidebar !== 'expanded');

  function syncControls() {
    const open = mobile.matches && isDrawerOpen();
    const expanded = mobile.matches ? open : !isCompact();
    const label = mobile.matches ? 'Mở thanh điều hướng'
      : expanded ? 'Thu gọn thanh điều hướng' : 'Mở rộng thanh điều hướng';
    toggle.title = label;
    toggle.setAttribute('aria-label', label);
    toggle.setAttribute('aria-expanded', String(expanded));
    toggle.hidden = false;
    close.hidden = !open;
    sidebar.inert = mobile.matches && !open;
    topbar.inert = main.inert = open;
    if (open) {
      sidebar.setAttribute('role', 'dialog');
      sidebar.setAttribute('aria-modal', 'true');
    } else {
      sidebar.removeAttribute('role');
      sidebar.removeAttribute('aria-modal');
    }
  }

  function dismissDrawer(restoreFocus = true) {
    const wasOpen = isDrawerOpen();
    shell.removeAttribute('data-staff-drawer-open');
    syncControls();
    if (wasOpen && restoreFocus) toggle.focus();
  }

  toggle.addEventListener('click', () => {
    if (mobile.matches) {
      shell.setAttribute('data-staff-drawer-open', '');
      syncControls();
      close.focus();
      return;
    }
    const preference = isCompact() ? 'expanded' : 'compact';
    root.dataset.staffSidebar = preference;
    try { localStorage.setItem(savedKey, preference); } catch {}
    syncControls();
  }, { signal });

  close.addEventListener('click', () => dismissDrawer(), { signal });
  shell.querySelector('[data-staff-sidebar-backdrop]').addEventListener('click', () => dismissDrawer(), { signal });
  sidebar.addEventListener('click', event => {
    if (isDrawerOpen() && event.button === 0 && !event.ctrlKey && !event.metaKey
        && !event.shiftKey && !event.altKey && event.target.closest('a[href]')) dismissDrawer();
  }, { signal });
  shell.addEventListener('keydown', event => {
    if (!isDrawerOpen()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      dismissDrawer();
    }
    if (event.key !== 'Tab') return;
    const focusable = [...sidebar.querySelectorAll('a[href], button:not([hidden])')];
    const boundary = event.shiftKey ? focusable[0] : focusable.at(-1);
    if (document.activeElement !== boundary) return;
    event.preventDefault();
    (event.shiftKey ? focusable.at(-1) : focusable[0]).focus();
  }, { signal });

  mobile.addEventListener('change', () => dismissDrawer(), { signal });
  tablet.addEventListener('change', syncControls, { signal });
  signal.addEventListener('abort', () => dismissDrawer(false), { once: true });
  syncControls();
});
