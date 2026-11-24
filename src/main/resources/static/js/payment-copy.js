(() => {
  document.querySelectorAll('[data-copy-payment-code]').forEach((button) => {
    button.addEventListener('click', async () => {
      const panel = button.closest('[data-invoice-payment]');
      const code = panel?.querySelector('[data-payment-code]')?.textContent?.trim();
      if (!code) return;
      try {
        await navigator.clipboard.writeText(code);
      } catch {
        const input = document.createElement('textarea');
        input.value = code;
        input.style.position = 'fixed';
        input.style.opacity = '0';
        document.body.append(input);
        input.select();
        document.execCommand('copy');
        input.remove();
      }
      button.textContent = 'Đã sao chép';
    });
  });
})();
