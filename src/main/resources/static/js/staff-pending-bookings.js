window.t3lPage.register(({ signal }) => {
  const bubble = document.querySelector('[data-pending-bookings]');
  if (!bubble) return;

  const endpoint = bubble.dataset.endpoint;
  const countLabel = bubble.querySelector('[data-pending-count]');
  let request;

  const refresh = async () => {
    request?.abort();
    request = new AbortController();
    try {
      const response = await fetch(endpoint, {
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
        signal: request.signal
      });
      if (!response.ok) return;
      const result = await response.json();
      const count = Math.max(0, Number(result.count) || 0);
      countLabel.textContent = String(count);
      bubble.hidden = count === 0;
      bubble.setAttribute('aria-label', `Hiện có ${count} yêu cầu đặt bàn đang chờ. Mở danh sách để xử lý.`);
    } catch (error) {
      if (error.name !== 'AbortError') console.warn('Không thể cập nhật số yêu cầu đặt bàn đang chờ.', error);
    }
  };

  refresh();
  const timer = window.setInterval(refresh, 5000);
  signal.addEventListener('abort', () => {
    window.clearInterval(timer);
    request?.abort();
  }, { once: true });
});
