window.t3lPage.register(({ signal }) => {
  const reservationList = document.querySelector('[data-reservation-list-url]');
  if (reservationList) {
    const rows = [...reservationList.querySelectorAll('[data-reservation-row]')];
    if (!rows.length) return;
    const url = new URL(reservationList.dataset.reservationListUrl, location.href);
    url.searchParams.set('ids', rows.map(row => row.dataset.reservationRow).join(','));
    const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 });
    const money = amount => number.format(amount) + ' đ';

    const updateAmount = (row, selector, text, visible) => {
      const element = row.querySelector(selector);
      element.hidden = !visible;
      if (element.textContent !== text) element.textContent = text;
    };
    const updateBooking = (row, state) => {
      const booking = row.querySelector('[data-booking-state]');
      const deposit = row.querySelector('[data-list-deposit-state]');
      booking.dataset.status = state.status;
      deposit.dataset.status = state.depositStatus;
      if (booking.textContent !== state.statusLabel) booking.textContent = state.statusLabel;
      if (deposit.textContent !== state.depositLabel) deposit.textContent = state.depositLabel;
      row.querySelector('[data-booking-late]').hidden = !state.late;
      row.querySelector('[data-list-order]').hidden = state.status !== 'SEATED';
      updateAmount(row, '[data-deposit-amount]', money(state.depositAmount), state.depositAmount > 0);
      updateAmount(row, '[data-deposit-paid]', 'Đã thu ' + money(state.depositPaid),
        state.depositPaid > 0 && state.depositPaid !== state.depositAmount);
      updateAmount(row, '[data-deposit-remaining]', 'Còn ' + money(state.depositRemaining),
        state.depositPaid > 0 && state.depositRemaining > 0);
      for (const name of ['confirm', 'receive']) {
        const form = row.querySelector('[data-list-action="' + name + '"]');
        const button = form.querySelector('button');
        const note = form.querySelector('.staff-action-reason');
        const action = state[name];
        form.hidden = !action.visible;
        button.disabled = !action.enabled;
        note.hidden = action.enabled || !action.reason;
        if (note.textContent !== action.reason) note.textContent = action.reason;
        if (name === 'receive' && action.enabled && action.reason) button.dataset.confirm = action.reason;
        else delete button.dataset.confirm;
      }
    };

    let loading = false;
    const pollList = async () => {
      if (loading || document.hidden) return;
      loading = true;
      try {
        const result = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store', signal });
        if (!result.ok || result.redirected) return;
        const updates = await result.json();
        rows.forEach(row => {
          const state = updates[row.dataset.reservationRow];
          if (state) updateBooking(row, state);
        });
      } catch {
        // The last server-rendered or verified state stays visible if a refresh fails.
      } finally {
        loading = false;
      }
    };
    pollList();
    const timer = window.setInterval(pollList, 5000);
    signal.addEventListener('abort', () => window.clearInterval(timer), { once: true });
    document.addEventListener('visibilitychange', pollList, { signal });
    return;
  }

  const containers = [...document.querySelectorAll('[data-payment-status-url]')];
  if (!containers.length) return;

  const updateAction = (container, value, enabled, reason) => {
    const button = [...container.querySelectorAll('button[name="status"]')]
      .find((candidate) => candidate.value === value);
    if (!button) return;
    const form = button.form;
    button.disabled = !enabled;
    button.title = enabled ? '' : reason || '';
    if (value === 'RECEIVE') {
      if (enabled && reason) button.dataset.confirm = reason;
      else delete button.dataset.confirm;
    }
    const note = form.querySelector('.staff-action-reason, small');
    if (note) {
      note.textContent = reason || '';
      note.hidden = !reason;
    }
  };

  const refresh = async (node) => {
    try {
      const response = await fetch(node.dataset.paymentStatusUrl, {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'same-origin',
        signal,
      });
      if (!response.ok) return;
      const state = await response.json();
      if (node.matches('[data-invoice-payment]')) {
        const label = node.querySelector('[data-invoice-payment-state]');
        const confirm = node.querySelector('[data-invoice-confirm]');
        const details = node.querySelector('.staff-transfer-details');
        const simulationForm = node.querySelector('form[action*="payment-demo"]');
        if (label) label.textContent = state.label;
        if (confirm) confirm.disabled = !state.confirmEnabled;
        if (details) details.hidden = state.status !== 'WAITING';
        if (simulationForm) simulationForm.hidden = state.status !== 'WAITING';
        return;
      }
      const container = node.matches('.plan-table-customer') ? node.closest('.plan-table-card') : node;
      const deposit = container.querySelector('.staff-deposit-state');
      if (deposit && state.status !== 'NO_DEPOSIT') {
        deposit.textContent = state.label;
        deposit.dataset.status = state.status;
      }
      updateAction(container, 'CONFIRMED', state.confirmEnabled, state.confirmEnabled ? '' : 'Chưa nhận đủ tiền cọc hoặc yêu cầu đã hết hiệu lực.');
      updateAction(container, 'RECEIVE', state.receiveEnabled, state.receiveReason);
    } catch {
      // Keep the current actions if a background status request is temporarily unavailable.
    }
  };

  const poll = () => containers.forEach(refresh);
  poll();
  const timer = window.setInterval(poll, 5000);
  signal.addEventListener('abort', () => window.clearInterval(timer), { once: true });
});
