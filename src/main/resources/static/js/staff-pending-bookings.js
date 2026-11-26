window.t3lPage.register(({ signal }) => {
  const bubble = document.querySelector('[data-pending-bookings]');
  if (!bubble) return;

  const endpoint = bubble.dataset.endpoint;
  const countLabel = bubble.querySelector('[data-pending-count]');
  const topbar = bubble.hasAttribute('data-staff-notification');
  const liveRegion = topbar ? document.querySelector('[data-staff-notification-status]') : null;
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
      if (topbar) {
        const label = count > 0 ? count + ' yêu cầu đặt bàn đang chờ' : 'Không có yêu cầu đặt bàn đang chờ';
        countLabel.dataset.count = count > 99 ? '99+' : String(count);
        countLabel.hidden = count === 0;
        bubble.setAttribute('aria-label', 'Thông báo đặt bàn. ' + label);
        bubble.title = label;
        if (liveRegion && liveRegion.textContent !== label) liveRegion.textContent = label;
        return;
      }
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
