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
});
