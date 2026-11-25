(() => {
  const panel = document.querySelector('.deposit-payment[data-payment-status-url]');
  if (!panel) return;

  const statusText = panel.querySelector('[data-payment-status-text]');
  const countdown = panel.querySelector('[data-payment-countdown]');
  const transfer = panel.querySelector('.deposit-transfer');
  const demo = panel.querySelector('.deposit-demo-form');
  let seconds = Number(countdown?.dataset.seconds || 0);

  const paintCountdown = () => {
    if (!countdown) return;
    if (seconds <= 0) {
      countdown.textContent = 'Thời hạn thanh toán đã kết thúc.';
      return;
    }
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    countdown.textContent = `Còn ${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')} để thanh toán.`;
    seconds -= 1;
  };

  panel.querySelector('[data-copy-payment-code]')?.addEventListener('click', async (event) => {
    const code = panel.querySelector('[data-payment-code]')?.textContent?.trim();
    if (!code) return;
    try {
      await navigator.clipboard.writeText(code);
      event.currentTarget.textContent = 'Đã sao chép';
    } catch {
      const input = document.createElement('textarea');
      input.value = code;
      input.style.position = 'fixed';
      input.style.opacity = '0';
      document.body.append(input);
      input.select();
      document.execCommand('copy');
      input.remove();
      event.currentTarget.textContent = 'Đã sao chép';
    }
  });

  paintCountdown();
  window.setInterval(paintCountdown, 1000);

  const refresh = async () => {
    try {
      const response = await fetch(panel.dataset.paymentStatusUrl, {
        headers: { Accept: 'application/json' },
        cache: 'no-store',
        credentials: 'same-origin',
      });
      if (!response.ok) return;
      const status = await response.json();
      panel.dataset.status = status.status;
      if (statusText) statusText.textContent = status.label;
      if (countdown && status.remainingSeconds >= 0 && status.status === 'WAITING') {
        seconds = status.remainingSeconds;
        countdown.hidden = false;
      } else if (countdown) {
        countdown.hidden = true;
      }
      if (transfer) transfer.hidden = status.status !== 'WAITING';
      if (demo) demo.hidden = status.status !== 'WAITING';
      if (['CONFIRMED', 'CANCELLED', 'NO_SHOW', 'APPLIED', 'REFUNDED', 'FORFEITED', 'CLOSED']
          .includes(status.status)) window.clearInterval(timer);
    } catch {
      // A temporary status request failure must not hide the payment instructions.
    }
  };

  const timer = window.setInterval(refresh, 5000);
  refresh();
})();
