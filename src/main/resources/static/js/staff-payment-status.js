window.t3lPage.register(({ signal }) => {
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
