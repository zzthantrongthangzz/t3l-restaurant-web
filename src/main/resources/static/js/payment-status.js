window.t3lPage.register(({ signal }) => {
  const panel = document.querySelector('[data-payment-status-url]');
  if (!panel) return;

  const statusText = panel.querySelector('[data-payment-status-text]');
  const countdown = panel.querySelector('[data-payment-countdown]');
  const actions = panel.querySelectorAll('[data-deposit-action]');
  const copy = panel.querySelector('[data-copy-payment-code]');
  let deadline = Date.now() + Number(countdown?.dataset.seconds ?? panel.dataset.paymentSeconds ?? 0) * 1000;
  let refreshing = false;

  const paintCountdown = () => {
    const seconds = Math.ceil((deadline - Date.now()) / 1000);
    if (seconds <= 0) {
      if (countdown) countdown.textContent = 'Thời hạn thanh toán đã kết thúc.';
      actions.forEach(action => { action.hidden = true; });
      return;
    }
    if (!countdown) return;
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    countdown.textContent = `Còn ${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')} để thanh toán.`;
  };

  copy?.addEventListener('click', async () => {
    const code = panel.querySelector('[data-payment-code]')?.textContent?.trim();
    if (!code) return;
    try {
      await navigator.clipboard.writeText(code);
    } catch {
      const input = document.createElement('textarea');
      input.value = code;
      input.style.position = 'fixed';
      input.style.opacity = '0';
      document.body.append(input);
      input.select();
      document.execCommand('copy');
      input.remove();
    }
    copy.textContent = 'Đã sao chép';
  }, { signal });

  const refresh = async () => {
    if (refreshing) return;
    refreshing = true;
    try {
      const response = await fetch(panel.dataset.paymentStatusUrl, {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'same-origin',
        signal,
      });
      if (!response.ok) return;
      const status = await response.json();
      if (signal.aborted) return;
      if (!status.payable) actions.forEach(action => { action.hidden = true; });
      if (panel.dataset.reservationStatus !== status.reservationStatus || panel.dataset.status !== status.status
          || (panel.dataset.payable === 'true') !== status.payable) {
        window.location.reload();
        return;
      }
      if (statusText) statusText.textContent = status.label;
      if (status.payable) {
        deadline = Date.now() + status.remainingSeconds * 1000;
        actions.forEach(action => { action.hidden = false; });
        paintCountdown();
      }
    } catch {
      // The local countdown still hides expired payment controls if polling fails.
    } finally {
      refreshing = false;
    }
  };

  paintCountdown();
  const clock = countdown || actions.length ? window.setInterval(paintCountdown, 1000) : null;
  const open = ['PENDING_CONFIRMATION', 'CONFIRMED'].includes(panel.dataset.reservationStatus);
  const timer = open ? window.setInterval(refresh, 5000) : null;
  if (open) refresh();
  signal.addEventListener('abort', () => {
    window.clearInterval(clock);
    window.clearInterval(timer);
  }, { once: true });
});
