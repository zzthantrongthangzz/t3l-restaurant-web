window.t3lPage.register(({ signal }) => {
  document.addEventListener('click', async event => {
    const button=event.target.closest('[data-copy-voucher]');
    if (!button) return;
    try {
      await navigator.clipboard.writeText(button.dataset.copyVoucher);
      button.textContent='Đã sao chép';
    } catch {
      button.textContent='Mã: '+button.dataset.copyVoucher;
    }
  },{signal});
});
