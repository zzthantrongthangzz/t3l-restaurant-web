window.t3lPage.register(() => {
  const dialog = document.querySelector('#booking-profile, #booking-dialog');
  if (dialog?.showModal) {
    dialog.removeAttribute('open');
    dialog.showModal();
    const close = document.getElementById('plan-booking-close');
    if (close) dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      close.form.requestSubmit(close);
    });
  }
});
