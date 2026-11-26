window.t3lPage.register(({ signal }) => {
  const payment = document.querySelector('[data-booking-refresh-url]');
  if (!payment) return;

  const statusRegion = document.querySelector('[data-booking-payment-live]');
  let panel = payment.querySelector('#booking-payment');
  let deadline = 0;
  let refreshing = false;
  let awaitingCheckout = false;

  function togglePaymentActions(visible) {
    panel.querySelectorAll('[data-deposit-action]').forEach(action => { action.hidden = !visible; });
  }

  function paintCountdown() {
    const countdown = panel.querySelector('[data-payment-countdown]');
    if (!countdown) return;
    const seconds = Math.ceil((deadline - Date.now()) / 1000);
    if (seconds <= 0) {
      countdown.textContent = 'Đang kiểm tra thời hạn thanh toán…';
      togglePaymentActions(false);
      return;
    }
    const time = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
      .map(value => String(value).padStart(2, '0')).join(':');
    countdown.textContent = 'Thời gian còn lại · ' + time;
  }

  function setDeadline(seconds) {
    deadline = Date.now() + Number(seconds) * 1000;
    togglePaymentActions(!awaitingCheckout && panel.dataset.payable === 'true');
    paintCountdown();
  }

  function refreshDisclosures(page) {
    const requests = document.querySelector('[data-booking-requests]');
    const nextRequests = page.querySelector('[data-booking-requests]');
    if (requests && nextRequests && !requests.hasAttribute('data-editing') && !requests.contains(document.activeElement)
        && !requests.querySelector('[role="alert"]')) {
      nextRequests.open = requests.open;
      requests.replaceWith(nextRequests);
    }

    const history = document.querySelector('[data-booking-history]');
    const nextHistory = page.querySelector('[data-booking-history]');
    const previous = history?.querySelector('details');
    const replacement = nextHistory?.querySelector('details');
    if (previous && replacement) replacement.open = previous.open;
    if (history && nextHistory && !history.isEqualNode(nextHistory) && !history.contains(document.activeElement)) {
      history.replaceWith(nextHistory);
    }
  }

  async function updateCheckout(status) {
    awaitingCheckout = true;
    togglePaymentActions(false);
    const response = await fetch(payment.dataset.bookingRefreshUrl, {
      headers: { Accept: 'text/html', 'X-T3L-Booking-Refresh': 'checkout' },
      cache: 'no-store', credentials: 'same-origin', signal
    });
    if (!response.ok) return;
    const page = new DOMParser().parseFromString(await response.text(), 'text/html');
    const next = page.querySelector('#booking-payment');
    if (signal.aborted || next?.dataset.paymentStatusUrl !== panel.dataset.paymentStatusUrl) return;

    const orderOpen = document.querySelector('.booking-item-details')?.open;
    for (const selector of ['.booking-detail-status', '#booking-order']) {
      const current = document.querySelector(selector);
      const replacement = page.querySelector(selector);
      if (current && replacement && !current.isEqualNode(replacement)) current.replaceWith(replacement);
    }
    const orderDetails = document.querySelector('.booking-item-details');
    if (orderDetails && orderOpen != null) orderDetails.open = orderOpen;
    refreshDisclosures(page);
    const refundOpen = panel.querySelector('.deposit-refund-terms')?.open;
    const nextRefund = next.querySelector('.deposit-refund-terms');
    if (nextRefund && refundOpen != null) nextRefund.open = refundOpen;
    const focusWasInPayment = panel.contains(document.activeElement);
    panel.replaceWith(next);
    panel = next;
    awaitingCheckout = false;
    setDeadline(panel.querySelector('[data-payment-countdown]')?.dataset.seconds ?? 0);
    statusRegion.textContent = panel.dataset.status !== status.status
      ? panel.querySelector('[data-payment-status-text]').textContent
      : status.payable ? 'Thông tin thanh toán cọc đã cập nhật.' : status.label;
    if (focusWasInPayment) {
      panel.setAttribute('tabindex', '-1');
      panel.focus({ preventScroll: true });
    }
  }

  async function refresh() {
    if (refreshing || document.hidden) return;
    refreshing = true;
    try {
      const response = await fetch(panel.dataset.paymentStatusUrl, {
        headers: { Accept: 'application/json' }, cache: 'no-store', credentials: 'same-origin', signal
      });
      if (response.status === 401 || response.status === 403 || response.status === 404) {
        awaitingCheckout = true;
        togglePaymentActions(false);
        return;
      }
      if (!response.ok) return;
      const status = await response.json();
      if (signal.aborted) return;
      const changed = panel.dataset.reservationStatus !== status.reservationStatus
        || panel.dataset.status !== status.status || panel.dataset.checkoutVersion !== status.checkoutVersion
        || (panel.dataset.payable === 'true') !== status.payable;
      if (changed) {
        await updateCheckout(status);
      } else {
        awaitingCheckout = false;
        setDeadline(status.remainingSeconds);
      }
    } catch {
      // Keep a superseded QR hidden while retrying the checkout refresh.
    } finally {
      refreshing = false;
    }
  }

  document.addEventListener('input', event => {
    event.target.closest('[data-booking-requests]')?.setAttribute('data-editing', '');
  }, { signal });

  const isOpen = () => ['PENDING_CONFIRMATION', 'CONFIRMED'].includes(panel.dataset.reservationStatus);
  setDeadline(panel.querySelector('[data-payment-countdown]')?.dataset.seconds ?? 0);
  if (!isOpen()) return;
  const clock = window.setInterval(paintCountdown, 1000);
  const timer = window.setInterval(() => {
    if (isOpen()) void refresh();
    else {
      window.clearInterval(timer);
      window.clearInterval(clock);
    }
  }, 5000);
  document.addEventListener('visibilitychange', () => { if (isOpen()) void refresh(); }, { signal });
  window.addEventListener('pageshow', event => {
    if (!event.persisted || !isOpen()) return;
    awaitingCheckout = true;
    togglePaymentActions(false);
    void refresh();
  }, { signal });
  signal.addEventListener('abort', () => {
    window.clearInterval(clock);
    window.clearInterval(timer);
  }, { once: true });
  void refresh();
});
