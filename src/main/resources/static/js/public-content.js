window.t3lPage.register(({ signal }) => {
  if ('IntersectionObserver' in window && !matchMedia('(prefers-reduced-motion: reduce)').matches) {
    const observer = new IntersectionObserver(entries => entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    }), { threshold: 0.08 });
    document.querySelectorAll('[data-reveal]').forEach(element => {
      if (element.getBoundingClientRect().top >= innerHeight) {
        element.classList.add('reveal-ready');
        observer.observe(element);
      }
    });
    signal.addEventListener('abort', () => observer.disconnect(), { once: true });
  }

  const form = document.getElementById('contact-form');
  if (!form) return;
  if (form.dataset.hasErrors === 'true') form.querySelector('[aria-invalid="true"], .contact-form-error')?.focus();
  const submit = form.querySelector('button[type="submit"]');
  const label = [...submit.childNodes].map(node => node.cloneNode(true));
  form.addEventListener('submit', () => {
    submit.disabled = true;
    submit.textContent = 'Đang gửi lời nhắn…';
  });
  window.addEventListener('pageshow', () => {
    submit.disabled = false;
    submit.replaceChildren(...label.map(node => node.cloneNode(true)));
  }, { signal });
});
