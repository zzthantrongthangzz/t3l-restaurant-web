window.t3lPage.register(({ signal }) => {
  document.querySelectorAll('[data-payment-form]').forEach(form => {
    const method = form.querySelector('[data-payment-method]');
    const field = form.querySelector('[data-payment-reference]');
    const input = field.querySelector('input');
    const update = () => {
      const transfer = method.value === 'TRANSFER';
      field.hidden = !transfer;
      input.disabled = !transfer;
      input.required = transfer;
    };
    method.addEventListener('change', update);
    update();
  });

  document.addEventListener('submit', event => {
    const message = event.submitter?.dataset.confirm;
    if (message && !window.confirm(message)) event.preventDefault();
  }, { capture: true, signal });

  document.querySelectorAll('[data-reason-open]').forEach(button => {
    const dialog = document.getElementById(button.getAttribute('aria-controls'));
    if (!dialog) return;
    button.addEventListener('click', () => dialog.showModal(), { signal });
    dialog.querySelector('[data-reason-cancel]').addEventListener('click', () => dialog.close(), { signal });
    dialog.addEventListener('close', () => { dialog.querySelector('[name="reason"]').value = ''; }, { signal });
  });

  const moveDialog = document.querySelector('dialog#move-table');
  if (moveDialog) {
    document.querySelector('[data-open-move-table]')?.addEventListener('click', () => moveDialog.showModal(), { signal });
    if (moveDialog.dataset.open === 'true' && !moveDialog.open) moveDialog.showModal();
  }

  const serviceTime = document.querySelector('[data-service-seconds]');
  if (serviceTime) {
    const started = performance.now();
    const running = serviceTime.dataset.serviceRunning === 'true';
    const update = () => {
      const elapsed = Number(serviceTime.dataset.serviceSeconds) + (running ? Math.floor((performance.now() - started) / 1000) : 0);
      const hours = Math.floor(elapsed / 3600);
      const minutes = Math.floor(elapsed / 60) % 60;
      serviceTime.textContent = [hours, minutes, elapsed % 60].map(value => String(value).padStart(2, '0')).join(':');
      serviceTime.dateTime = 'PT' + elapsed + 'S';
    };
    update();
    if (running) {
      const timer = window.setInterval(update, 1000);
      signal.addEventListener('abort', () => window.clearInterval(timer), { once: true });
    }
  }

  const shiftTime = document.querySelector('[data-shift-seconds]');
  if (shiftTime?.dataset.shiftRunning === 'true') {
    const firstRender = performance.now();
    const initialSeconds = Number(shiftTime.dataset.shiftSeconds);
    const update = () => {
      const seconds = initialSeconds + Math.floor((performance.now() - firstRender) / 1000);
      const days = Math.floor(seconds / 86400);
      const clock = [Math.floor(seconds / 3600) % 24, Math.floor(seconds / 60) % 60, seconds % 60]
        .map(value => String(value).padStart(2, '0')).join(':');
      shiftTime.textContent = days > 0 ? days + ' ngày · ' + clock : clock;
      shiftTime.dateTime = 'PT' + seconds + 'S';
    };
    update();
    const timer = window.setInterval(update, 1000);
    signal.addEventListener('abort', () => window.clearInterval(timer), { once: true });
  }

  const shiftClose = document.querySelector('[data-shift-close]');
  if (shiftClose) {
    const counted = shiftClose.elements.countedCash;
    const output = shiftClose.querySelector('[data-shift-difference]');
    const confirmation = document.querySelector('[data-shift-confirm]');
    const expected = BigInt(shiftClose.dataset.expectedCash);
    const money = new Intl.NumberFormat('vi-VN');
    const format = amount => money.format(amount) + ' đ';
    const showDifference = (element, amount) => {
      const difference = amount - expected;
      element.dataset.cashBalance = difference === 0n ? 'equal' : (difference > 0n ? 'over' : 'under');
      element.textContent = difference === 0n ? 'Khớp sổ' : (difference > 0n
        ? 'Thừa ' + format(difference) : 'Thiếu ' + format(-difference));
    };
    const update = () => {
      if (/^[0-9]{1,18}$/.test(counted.value)) {
        showDifference(output, BigInt(counted.value));
      } else {
        delete output.dataset.cashBalance;
        output.textContent = '—';
      }
    };
    counted.addEventListener('input', update, { signal });
    update();
    if (typeof confirmation?.showModal === 'function') {
      delete shiftClose.querySelector('[data-confirm]').dataset.confirm;
      let confirmed = false;
      shiftClose.addEventListener('submit', event => {
        if (confirmed) return;
        event.preventDefault();
        const amount = BigInt(counted.value);
        confirmation.querySelector('[data-confirm-counted]').textContent = format(amount);
        showDifference(confirmation.querySelector('[data-confirm-difference]'), amount);
        confirmation.returnValue = '';
        confirmation.showModal();
      }, { signal });
      confirmation.addEventListener('close', () => {
        if (confirmation.returnValue !== 'close-shift') return;
        confirmed = true;
        shiftClose.requestSubmit();
        confirmed = false;
      }, { signal });
    }
  }

  const tableType = document.querySelector('[data-inventory-kind]');
  if (tableType) {
    const capacity = tableType.form.elements.capacity;
    const update = () => {
      capacity.value = tableType.selectedOptions[0].dataset.capacity;
    };
    tableType.addEventListener('change', update);
    update();
  }
});
