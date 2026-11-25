window.t3lPage.register(({ signal }) => {
  const profile = document.getElementById('booking-profile');
  if (profile && typeof profile.showModal === 'function') {
    profile.removeAttribute('open');
    profile.showModal();
  }
  const form = document.getElementById('booking-form');
  if (!form) return;
  const date = form.querySelector('#date');
  const time = form.querySelector('#time');
  const note = form.querySelector('#note');
  const submit = form.querySelector('.booking-submit');
  const draftLogin = document.getElementById('booking-login-draft');
  if (draftLogin) {
    document.querySelector('[data-header-login]')?.addEventListener('click', (event) => {
      if (event.defaultPrevented || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      form.requestSubmit(draftLogin);
    }, { signal });
  }
  const submitContent = [...submit.childNodes].map(node => node.cloneNode(true));
  const timeHint = document.getElementById('time-hint');
  const defaultTimeHint = timeHint.textContent;
  const localTime = new Intl.DateTimeFormat('en-GB', {
    timeZone: form.dataset.zone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
  });
  const localDate = new Intl.DateTimeFormat('en-CA', {
    timeZone: form.dataset.zone, year: 'numeric', month: '2-digit', day: '2-digit'
  });
  const updateTimeOptions = () => {
    const now = new Date();
    const parts = Object.fromEntries(localDate.formatToParts(now).map(part => [part.type, part.value]));
    const today = `${parts.year}-${parts.month}-${parts.day}`;
    date.min = today;
    const currentTime = localTime.format(now);
    [...time.options].forEach(option => {
      option.disabled = !!option.value && date.value === today && option.value <= currentTime;
    });
    if (time.selectedOptions[0]?.disabled) time.value = '';
    timeHint.textContent = [...time.options].some(option => option.value && !option.disabled)
      ? defaultTimeHint : 'Hôm nay đã hết giờ nhận khách. Vui lòng chọn ngày khác.';
  };
  updateTimeOptions();
  date.addEventListener('change', updateTimeOptions);
  time.addEventListener('focus', updateTimeOptions);
  const updateCount = () => document.getElementById('note-count').textContent = `${note.value.length}/500 ký tự`;
  updateCount();
  note.addEventListener('input', updateCount);
  form.addEventListener('submit', () => {
    submit.disabled = true;
    submit.textContent = 'Đang xử lý…';
    form.setAttribute('aria-busy', 'true');
  });
  window.addEventListener('pageshow', () => {
    submit.disabled = false;
    submit.replaceChildren(...submitContent.map(node => node.cloneNode(true)));
    form.removeAttribute('aria-busy');
    updateTimeOptions();
  }, { signal });
  const errors = document.getElementById('booking-errors');
  if (errors) errors.focus();
});
