window.t3lPage.register(({ signal, state }) => {
  const form = document.querySelector('[data-booking-flow]');
  if (!form) return;

  const panels = [...form.querySelectorAll('[data-booking-panel]')];
  const steps = [...form.querySelectorAll('[data-booking-step]')];
  const progress = form.querySelector('.checkout-progress');
  const confirm = form.querySelector('[data-booking-confirm]');
  const reviewLogin = form.querySelector('[data-booking-login-review]');
  const quantities = form.querySelector('[data-preorder-fields]');
  const expectedTotal = form.querySelector('[data-preorder-expected]');
  const kitchenNote = form.elements.preorderNote;
  const consent = form.elements.consentAccepted;
  const voucher = form.querySelector('[data-booking-voucher]');
  const code = form.querySelector('[data-voucher-code]');
  const offers = form.querySelector('[data-voucher-select]');
  const voucherMessage = form.querySelector('[data-voucher-message]');
  const quotePanels = panels.slice(1).map(panel => ({
    status: panel.querySelector('[data-booking-quote-status]'),
    amounts: [...panel.querySelectorAll('[data-booking-amount]')]
  }));
  const menuBar = panels[1].querySelector('.checkout-menu-actions');
  const cart = panels[1].querySelector('.checkout-cart');
  const reviewTotal = form.querySelector('[data-booking-review-total]');
  const contactInputs = [...panels[0].querySelectorAll('input, textarea')];
  const pageUrl = location.pathname + location.search;
  const saved = state.booking?.pageUrl === pageUrl ? state.booking : null;
  const currency = new Intl.NumberFormat('vi-VN');
  let current = 1;
  let skipCart = saved?.skipCart ?? false;
  let submitting = false;
  let quoteRequest;
  let quoteTimer;

  if (saved) {
    for (const input of [...contactInputs, kitchenNote, code].filter(Boolean)) {
      input.value = saved.values[input.name] ?? input.value;
    }
    consent.checked = saved.consent;
  }
  form.noValidate = true;
  form.dataset.bookingReady = '';

  function excludeCart(exclude) {
    for (const input of quantities.children) input.disabled = exclude;
    expectedTotal.disabled = exclude;
    kitchenNote.disabled = exclude;
    if (code) code.disabled = exclude;
  }

  function updateReview() {
    const hasDishes = !skipCart && quantities.childElementCount > 0;
    excludeCart(skipCart);
    form.querySelector('[data-booking-review-empty]').hidden = hasDishes;
    form.querySelector('[data-booking-review-skipped]').hidden = !skipCart;
    form.querySelector('[data-booking-review-lines]').hidden = !hasDishes;
    form.querySelector('[data-booking-review-subtotal]').hidden = !hasDishes;
    form.querySelector('[data-booking-kitchen-note]').hidden = !hasDishes || !kitchenNote.value.trim();
    if (voucher) voucher.hidden = skipCart;
    if (reviewLogin) {
      reviewLogin.hidden = !hasDishes;
      confirm.hidden = hasDishes;
    }
    for (const output of form.querySelectorAll('[data-booking-copy]')) {
      const value = form.elements[output.dataset.bookingCopy].value.trim();
      output.textContent = value;
      const optional = output.closest('[data-booking-optional]');
      if (optional) optional.hidden = !value;
    }
  }

  function stopQuote() {
    clearTimeout(quoteTimer);
    quoteRequest?.abort();
  }

  async function requestQuote(url, voucherCode, request) {
    const body = new URLSearchParams();
    for (const input of quantities.children) {
      if (!input.disabled) body.set(input.name, input.value);
    }
    body.set('tableId', form.elements.tableId.value);
    if (voucherCode) body.set('voucherCode', voucherCode);
    const csrf = form.elements._csrf;
    if (csrf) body.set(csrf.name, csrf.value);
    const response = await fetch(url, {
      method: 'POST', body, signal: request.signal, credentials: 'same-origin', headers: { Accept: 'application/json' }
    });
    if (response.redirected || !response.headers.get('content-type')?.includes('application/json')) {
      throw new Error('Quote unavailable');
    }
    return { ok: response.ok, result: await response.json() };
  }

  function showQuote(result, amounts) {
    for (const output of amounts) {
      const key = output.dataset.bookingAmount;
      const value = BigInt(result[key]);
      output.textContent = key === 'deposit' && value === 0n
        ? 'Không yêu cầu cọc'
        : (key === 'discount' && value > 0n ? '−' : '') + currency.format(value) + ' đ';
    }
  }

  function voucherStatus(message, invalid = false) {
    if (!voucherMessage) return;
    voucherMessage.textContent = message;
    voucherMessage.toggleAttribute('data-invalid', invalid);
    code.setAttribute('aria-invalid', String(invalid));
  }

  function queueQuote(delay = 0, explicit = false) {
    stopQuote();
    const reviewing = current === 3;
    if (!reviewing && (current !== 2 || quantities.childElementCount === 0)) return;
    const { status, amounts } = quotePanels[current - 2];
    const voucherCode = reviewing && skipCart ? '' : code?.value.trim();
    if (reviewing) {
      voucherStatus(voucherCode ? 'Đang kiểm tra mã…' : '');
      confirm.disabled = true;
    }
    status.textContent = reviewing ? 'Đang cập nhật tổng tiền…' : '';
    for (const output of amounts) {
      if (reviewing || output.dataset.bookingAmount !== 'gross') output.textContent = '—';
    }

    quoteTimer = setTimeout(async () => {
      const request = new AbortController();
      quoteRequest = request;
      try {
        let voucherIssue = '';
        let response = await requestQuote(voucherCode ? voucher.querySelector('[data-voucher-url]').dataset.voucherUrl
          : form.dataset.quoteUrl, voucherCode, request);
        if (request.signal.aborted) return;
        if (voucherCode && !response.ok) {
          const message = response.result.message || 'Chưa thể kiểm tra mã voucher.';
          if (reviewing) voucherStatus(message, true);
          else voucherIssue = message;
          response = await requestQuote(form.dataset.quoteUrl, '', request);
        } else if (voucherCode && reviewing) {
          voucherStatus('Mã hợp lệ với giỏ hiện tại. T3L giữ lượt dùng khi yêu cầu được lưu.');
          if (explicit) document.dispatchEvent(new Event('t3l:voucher-applied'));
        }
        if (request.signal.aborted) return;
        if (response.ok) {
          showQuote(response.result, amounts);
          if (reviewing) reviewTotal.textContent = currency.format(BigInt(response.result.gross)) + ' đ';
          status.textContent = voucherIssue;
        } else status.textContent = response.result.message || 'Chưa thể cập nhật tổng tiền.';
      } catch (error) {
        if (request.signal.aborted) return;
        status.textContent = 'Chưa thể cập nhật tổng tiền. Vui lòng thử lại.';
        if (voucherCode && reviewing) voucherStatus('Chưa thể kiểm tra mã voucher.');
      } finally {
        if (reviewing && !request.signal.aborted && !submitting) confirm.disabled = false;
      }
    }, delay);
  }

  function showStep(step, focus = true) {
    current = step;
    panels.forEach((panel, index) => { panel.hidden = index + 1 !== step; });
    steps.forEach((button, index) => {
      button.disabled = index + 1 >= step;
      button.toggleAttribute('data-complete', index + 1 < step);
      if (index + 1 === step) button.setAttribute('aria-current', 'step');
      else button.removeAttribute('aria-current');
    });
    if (step === 3) updateReview();
    else {
      excludeCart(false);
      confirm.disabled = false;
    }
    queueQuote();
    if (focus) {
      panels[step - 1].querySelector('h2').focus({ preventScroll: true });
      progress.scrollIntoView({ behavior: 'instant', block: 'start' });
    }
  }

  function validateContact() {
    const invalid = contactInputs.find(input => !input.validity.valid);
    if (!invalid) return true;
    if (current !== 1) showStep(1);
    invalid.reportValidity();
    return false;
  }

  function advance(step, skip = false) {
    if (submitting || (step > current && !validateContact())) return;
    if (step === 3) skipCart = skip && quantities.childElementCount > 0;
    showStep(step);
  }

  function syncOffers() {
    if (!offers) return;
    offers.value = [...offers.options].find(option => option.value.toUpperCase() === code.value.trim().toUpperCase())?.value ?? '';
  }

  function checkVoucher() {
    if (code.value.trim()) queueQuote(0, true);
    else {
      voucherStatus('Nhập mã voucher hoặc chọn một mã khả dụng.');
      code.focus();
    }
  }

  form.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button || submitting) return;
    if (button.hasAttribute('data-booking-step')) advance(Number(button.dataset.bookingStep));
    if (button.hasAttribute('data-booking-next')) advance(Number(button.dataset.bookingNext));
    if (button.hasAttribute('data-booking-skip')) advance(3, true);
    if (button.hasAttribute('data-voucher-preview')) checkVoucher();
  });
  form.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.isComposing || !event.target.matches('input:not([type=checkbox])')) return;
    event.preventDefault();
    if (event.target === code) checkVoucher();
    else if (current === 1) advance(2);
  });
  code?.addEventListener('input', () => {
    syncOffers();
    queueQuote(280);
  });
  offers?.addEventListener('change', () => {
    code.value = offers.value;
    queueQuote(0, !!offers.value);
  });
  form.addEventListener('booking:cart-change', () => {
    if (current === 3) updateReview();
    queueQuote(current === 2 ? 180 : 0);
  });
  document.querySelector('[data-header-login]')?.addEventListener('click', event => {
    if (!reviewLogin || event.defaultPrevented || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (submitting) return;
    excludeCart(false);
    form.requestSubmit(reviewLogin);
  }, { signal });

  form.addEventListener('submit', event => {
    const button = event.submitter ?? confirm;
    if (submitting) {
      event.preventDefault();
      return;
    }
    if (button === confirm) {
      if (current !== 3 || !validateContact() || !consent.reportValidity()) {
        event.preventDefault();
        return;
      }
      if (reviewLogin && !reviewLogin.hidden) {
        event.preventDefault();
        form.requestSubmit(reviewLogin);
        return;
      }
    } else if (button !== reviewLogin) excludeCart(false);

    submitting = true;
    stopQuote();
    form.setAttribute('aria-busy', 'true');
    button.setAttribute('aria-disabled', 'true');
    if (!button.name) button.disabled = true;
    button.dataset.label = button.textContent;
    button.textContent = 'Đang xử lý…';
  });

  window.addEventListener('pageshow', event => {
    if (!event.persisted || !submitting) return;
    submitting = false;
    form.removeAttribute('aria-busy');
    const button = form.querySelector('button[data-label]');
    button.removeAttribute('aria-disabled');
    button.disabled = false;
    button.textContent = button.dataset.label;
    delete button.dataset.label;
    showStep(current, false);
  }, { signal });
  document.addEventListener('t3l:page-save', event => {
    event.detail.booking = {
      pageUrl, step: current, skipCart, consent: consent.checked,
      values: Object.fromEntries([...contactInputs, kitchenNote, code].filter(Boolean).map(input => [input.name, input.value]))
    };
  }, { signal });
  const root = document.documentElement;
  const resizeMenu = new ResizeObserver(() => {
    if (current !== 2) return;
    root.style.setProperty('--checkout-menu-bar-size', menuBar.offsetHeight + 'px');
    cart.style.setProperty('--checkout-cart-height', cart.offsetHeight + 'px');
  });
  resizeMenu.observe(menuBar);
  resizeMenu.observe(cart);
  signal.addEventListener('abort', () => {
    stopQuote();
    resizeMenu.disconnect();
    root.style.removeProperty('--checkout-menu-bar-size');
  }, { once: true });
  syncOffers();
  showStep(saved?.step ?? Number(form.dataset.startStep), false);
  form.querySelector('[data-booking-errors]')?.focus();
});
