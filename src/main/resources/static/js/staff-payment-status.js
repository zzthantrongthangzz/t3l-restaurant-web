(() => {
  const containers = [...document.querySelectorAll('[data-payment-status-url]')];
  if (!containers.length) return;

  const updateAction = (container, value, enabled, reason) => {
    const button = [...container.querySelectorAll('button[name="status"]')]
      .find((candidate) => candidate.value === value);
    if (!button) return;
    const form = button.form;
    button.disabled = !enabled;
    button.title = reason || '';
    const note = form.querySelector('.staff-action-reason, small');
    if (note) {
      note.textContent = reason || '';
      note.hidden = enabled || !reason;
    }
  };

  const refresh = async (node) => {
    try {
      const response = await fetch(node.dataset.paymentStatusUrl, {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'same-origin',
      });
      if (!response.ok) return;
      const state = await response.json();
      if (node.matches('[data-invoice-payment]')) {
        const label = node.querySelector('[data-invoice-payment-state]');
        const confirm = node.querySelector('[data-invoice-confirm]');
        const details = node.querySelector('.staff-transfer-details');
        const demo = node.querySelector('form[action*="payment-demo"]');
        if (label) label.textContent = state.label;
        if (state.demo && !node.querySelector('[data-payment-demo-label]')) {
          const badge = document.createElement('span');
          badge.className = 'staff-badge is-warning';
          badge.dataset.paymentDemoLabel = '';
          badge.textContent = 'DEMO · không tính doanh thu thực tế';
          label?.after(badge);
        }
        if (confirm) confirm.disabled = !state.confirmEnabled;
        if (details) details.hidden = state.status !== 'WAITING';
        if (demo) demo.hidden = state.status !== 'WAITING';
        return;
      }
      const container = node.matches('.plan-table-customer') ? node.closest('.plan-table-card') : node;
      const deposit = container.querySelector('.staff-deposit-state');
      if (deposit && state.status !== 'NO_DEPOSIT') {
        deposit.textContent = state.label;
        deposit.dataset.status = state.status;
      }
      if (state.demo && deposit) {
        deposit.dataset.demo = 'true';
        if (!container.querySelector('[data-payment-demo-label]')) {
          const badge = document.createElement('span');
          badge.className = 'staff-badge is-warning';
          badge.dataset.paymentDemoLabel = '';
          badge.textContent = 'DEMO · không tính doanh thu thực tế';
          deposit.after(badge);
        }
      }
      updateAction(container, 'CONFIRMED', state.confirmEnabled, state.confirmEnabled ? '' : 'Chưa nhận đủ tiền cọc hoặc yêu cầu đã hết hiệu lực.');
      updateAction(container, 'SEATED', state.receiveEnabled, state.receiveReason);
    } catch {
      // Keep the current actions if a background status request is temporarily unavailable.
    }
  };

  containers.forEach((node) => {
    refresh(node);
    window.setInterval(() => refresh(node), 5000);
  });
})();
