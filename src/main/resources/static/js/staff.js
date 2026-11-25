window.t3lPage.register(({ signal }) => {
  document.querySelectorAll('[data-booking-form]').forEach(form => {
    const mode = form.querySelector('[data-booking-mode]');
    const phone = form.querySelector('[data-booking-phone]');
    const update = () => {
      const walkIn = mode.value === 'true';
      phone.required = !walkIn;
      form.querySelectorAll('[data-booking-schedule]').forEach(field => {
        field.hidden = walkIn;
        const input = field.querySelector('input, select');
        input.disabled = walkIn;
        input.required = !walkIn;
      });
    };
    mode.addEventListener('change', update);
    update();
  });

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

  const tableType = document.querySelector('[data-inventory-kind]');
  if (tableType) {
    const capacity = tableType.form.elements.capacity;
    const update = () => {
      const fixed = Number(tableType.selectedOptions[0].dataset.capacity);
      capacity.readOnly = fixed > 0;
      if (fixed > 0) capacity.value = fixed;
    };
    tableType.addEventListener('change', update);
    update();
  }
});
