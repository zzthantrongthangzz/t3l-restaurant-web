window.t3lPage.register(({ signal }) => {
  const dashboard = document.querySelector('.staff-dashboard');
  if (!dashboard) return;

  const dialog = dashboard.querySelector('[data-dashboard-dialog]');
  const actions = [...dashboard.querySelectorAll('form[data-dashboard-action]')];
  const readLocalTime = value => Date.parse(value + 'Z');
  const timeAtRender = readLocalTime(dashboard.dataset.dashboardNow);
  const renderedAt = performance.now();
  const now = () => timeAtRender + performance.now() - renderedAt;
  const opensAt = readLocalTime(dashboard.dataset.opensAt);
  const closesAt = readLocalTime(dashboard.dataset.closesAt);
  const earlyWindow = Number(dashboard.dataset.checkInMinutes) * 60000;
  const lateWindow = Number(dashboard.dataset.noShowMinutes) * 60000;
  const dateTime = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short', timeZone: 'UTC' });
  let source;
  let approved;
  let sending;

  function showArrivalDialog(button, start, current) {
    const difference = current - start;
    const early = difference < 0;
    const minutes = Math.ceil(Math.abs(difference) / 60000);
    const duration = minutes >= 60
      ? Math.floor(minutes / 60) + ' giờ ' + minutes % 60 + ' phút'
      : minutes + ' phút';
    const info = button.form.dataset;
    const values = {
      message: early ? 'Khách đến sớm so với giờ đặt.' : 'Khách đến muộn so với giờ đặt.',
      guest: info.guest,
      code: info.code,
      guests: info.guests + ' khách',
      table: info.table,
      scheduled: dateTime.format(start),
      current: dateTime.format(current),
      difference: (early ? 'Sớm ' : 'Muộn ') + duration
    };
    dialog.querySelectorAll('[data-dialog-value]').forEach(item => {
      item.textContent = values[item.dataset.dialogValue];
    });
    source = button;
    dialog.returnValue = '';
    dialog.showModal();
  }

  function finishSending() {
    if (!sending) return;
    sending.removeAttribute('aria-disabled');
    sending.textContent = sending.value === 'RECEIVE' ? 'Nhận khách & bàn' : 'Xác nhận đặt bàn';
    sending = null;
  }

  dashboard.addEventListener('submit', event => {
    const button = event.submitter;
    const form = event.target;
    if (!form.matches('form[data-dashboard-action]') || !button || event.defaultPrevented) return;
    if (sending || source || button.disabled) {
      event.preventDefault();
      return;
    }
    if (button.value === 'RECEIVE' && approved !== button) {
      const start = readLocalTime(form.dataset.arrivalStart);
      const current = now();
      if (current < Math.max(opensAt, start - earlyWindow) || current >= Math.min(closesAt, start + lateWindow)) {
        event.preventDefault();
        showArrivalDialog(button, start, current);
        return;
      }
    }
    sending = button;
    sending.setAttribute('aria-disabled', 'true');
    sending.textContent = 'Đang xử lý…';
  }, { capture: true, signal });

  dialog.addEventListener('close', () => {
    const button = source;
    source = null;
    if (!button?.isConnected) return;
    if (!button.disabled && dialog.returnValue === 'receive') {
      approved = button;
      button.form.requestSubmit(button);
      approved = null;
    } else if (!button.disabled && !button.form.hidden) {
      button.focus({ preventScroll: true });
    } else {
      button.closest('li').querySelector('.dashboard-task-detail').focus({ preventScroll: true });
    }
  }, { signal });

  const busy = new MutationObserver(() => {
    if (!dashboard.hasAttribute('aria-busy')) finishSending();
  });
  busy.observe(dashboard, { attributeFilter: ['aria-busy'] });
  window.addEventListener('pageshow', finishSending, { signal });

  const url = new URL(dashboard.dataset.rowUrl, location.href);
  url.searchParams.set('ids', [...new Set(actions.map(form => form.dataset.dashboardBooking))].join(','));
  let polling = false;
  async function refreshActions() {
    if (!actions.length || polling || sending || document.hidden) return;
    polling = true;
    try {
      const response = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store', signal });
      if (!response.ok || response.redirected) return;
      const rows = await response.json();
      if (sending || signal.aborted) return;
      for (const form of actions) {
        const state = rows[form.dataset.dashboardBooking]?.[form.dataset.dashboardAction];
        if (!state) continue;
        form.hidden = !state.visible;
        form.querySelector('button').disabled = !state.enabled;
        const note = form.querySelector('[data-dashboard-action-reason]');
        note.textContent = state.reason ?? '';
        note.hidden = state.enabled || !state.reason;
      }
    } catch {
      // Server-rendered actions remain available if the status request fails.
    } finally {
      polling = false;
    }
  }

  const poll = window.setInterval(refreshActions, 15000);
  document.addEventListener('visibilitychange', refreshActions, { signal });
  signal.addEventListener('abort', () => {
    window.clearInterval(poll);
    busy.disconnect();
    if (dialog.open) dialog.close();
  }, { once: true });
});
