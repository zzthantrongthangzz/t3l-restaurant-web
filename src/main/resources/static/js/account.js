window.t3lPage.register(({ signal }) => {
  for (const photo of document.querySelectorAll('.account-identity .customer-avatar img')) {
    const useInitial = () => photo.remove();
    if (photo.complete && photo.naturalWidth === 0) useInitial();
    else photo.addEventListener('error', useInitial, { once: true, signal });
  }

  const sidebar = document.querySelector('.account-sidebar');
  const header = document.getElementById('home-header');
  if (sidebar && header) {
    const syncHeaderHeight = () => {
      sidebar.style.setProperty('--account-header-height', Math.ceil(header.getBoundingClientRect().height) + 'px');
    };
    syncHeaderHeight();
    const observer = new ResizeObserver(syncHeaderHeight);
    observer.observe(header, { box: 'border-box' });
    signal.addEventListener('abort', () => observer.disconnect(), { once: true });
  }

  const form = document.querySelector('[data-account-profile]');
  if (!form) return;

  const submit = form.querySelector('button[type="submit"]');
  const formValues = () => new URLSearchParams(new FormData(form)).toString();
  const initialValues = formValues();
  const updateSaveButton = () => {
    submit.disabled = formValues() === initialValues;
  };

  updateSaveButton();
  form.addEventListener('input', updateSaveButton, { signal });
  form.addEventListener('change', updateSaveButton, { signal });
  window.addEventListener('pageshow', updateSaveButton, { signal });
});
