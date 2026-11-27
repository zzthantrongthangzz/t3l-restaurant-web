window.t3lPage.register(({ signal, state }) => {
  const board = document.querySelector('[data-table-board]');
  if (!board) return;
  const grid = board.querySelector('[data-board-grid]');
  const filters = board.querySelector('[data-board-filters]');
  const messages = board.querySelector('[data-board-messages]');
  const statusFilters = [...board.querySelectorAll('[data-board-state]')];
  const dialog = board.querySelector('[data-board-dialog]');
  const title = dialog.querySelector('[data-table-title]');
  const back = dialog.querySelector('[data-board-back]');
  const content = dialog.querySelector('[data-table-content]');
  const searchTable = board.querySelector('[data-filter-table]');
  const searchGuest = board.querySelector('[data-filter-guest]');
  const kindFilter = board.querySelector('[data-filter-kind]');
  const adminBoard = board.classList.contains('admin-table-board');
  const total = board.querySelector('[data-board-total]');
  const context = board.querySelector('[data-board-context]');
  let cards = [...grid.querySelectorAll('.table-board-card')];
  let selectedButton;
  const requestedState = adminBoard ? '' : new URL(location.href).searchParams.get('state');
  let statusFilter = state.board?.status
    ?? statusFilters.find(button => button.dataset.boardState === requestedState)?.dataset.boardState ?? '';
  let pendingRead;
  let writing = false;
  let queuedHistory;
  let dialogLoad;
  const scheduleFields = ['date', 'time', 'guests'];
  const scheduleValues = () => scheduleFields.map(name => filters.elements[name].value).join('|');
  let appliedSchedule = scheduleValues();
  searchTable.value = state.board?.table ?? searchTable.value;
  searchGuest.value = state.board?.guest ?? searchGuest.value;
  kindFilter.value = state.board?.kind ?? kindFilter.value;

  function normalize(value) {
    return value.toLocaleLowerCase('vi').normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd').trim();
  }

  function matchesGuest(card, query) {
    if (!query) return true;
    const phoneSearch = /^[+\d .()-]+$/.test(query) ? query.replace(/\D/g, '').replace(/^84(?=\d)/, '0') : '';
    return [...card.querySelector('template').content.querySelectorAll('[data-table-guest]')].some(guest => {
      const phone = (guest.dataset.phone || '').replace(/\D/g, '').replace(/^84(?=\d)/, '0');
      return normalize(guest.textContent).includes(query) || (phoneSearch && phone.includes(phoneSearch));
    });
  }

  function applyFilters() {
    const counts = Object.fromEntries(statusFilters.map(button => [button.dataset.boardState, 0]));
    const tableQuery = normalize(searchTable.value);
    const guestQuery = normalize(searchGuest.value);
    let visibleCount = 0;
    for (const card of cards) {
      const state = card.dataset.summaryState || card.dataset.state;
      const hasWaiting = card.dataset.hasWaiting === 'true';
      const matches = (!kindFilter.value || card.dataset.kind === kindFilter.value)
        && normalize(card.dataset.search).includes(tableQuery) && matchesGuest(card, guestQuery);
      if (matches && state !== 'WAITING' && Object.hasOwn(counts, state)) counts[state] += 1;
      if (matches && hasWaiting) counts.WAITING += 1;
      if (matches) counts[''] += 1;
      card.hidden = !matches || Boolean(statusFilter && (statusFilter === 'WAITING' ? !hasWaiting : statusFilter !== state));
      if (!card.hidden) visibleCount += 1;
    }
    statusFilters.forEach(button => {
      const status = button.dataset.boardState;
      const selected = status === statusFilter;
      button.setAttribute('aria-pressed', String(selected));
      button.querySelector('[data-count]').textContent = counts[status];
      if (!adminBoard && ['INACTIVE', 'TOO_SMALL'].includes(status)) {
        button.hidden = !selected && counts[status] === 0;
      }
    });
    const label = visibleCount + ' bàn';
    if (total.textContent !== label) total.textContent = label;
    for (const group of grid.querySelectorAll('[data-board-group]')) {
      const visible = group.querySelectorAll('.table-board-card:not([hidden])').length;
      group.hidden = visible === 0;
      group.querySelector('[data-board-group-count]').textContent = visible + ' bàn';
    }
    board.querySelector('[data-board-empty]').hidden = visibleCount > 0;
    state.board = { table: searchTable.value, guest: searchGuest.value, kind: kindFilter.value, status: statusFilter };
  }

  function showTableActions() {
    const card = selectedButton.closest('.table-board-card');
    title.textContent = card.dataset.label;
    back.hidden = true;
    content.replaceChildren(card.querySelector('template').content.cloneNode(true));
  }

  function showBookingFlow(flow) {
    content.replaceChildren(flow);
    const error = flow.querySelector('[role="alert"]');
    if (error) error.tabIndex = -1;
    (error || flow.querySelector('[data-booking-focus]'))?.focus();
  }

  async function refreshArrivalAction() {
    const button = content.querySelector('[data-arrival-url]');
    if (!button || !dialog.open || document.hidden) return;
    try {
      const response = await fetch(button.dataset.arrivalUrl, { cache: 'no-store' });
      if (!response.ok || response.redirected) return;
      const status = await response.json();
      if (!button.isConnected || !dialog.open) return;
      button.disabled = !status.receiveEnabled;
      button.title = status.receiveEnabled ? '' : status.receiveReason;
      const reason = content.querySelector('[data-board-arrival-reason]');
      if (reason) {
        reason.textContent = status.receiveEnabled ? '' : status.receiveReason;
        reason.hidden = status.receiveEnabled || !status.receiveReason;
      }
      if (status.receiveEnabled && status.receiveReason) button.dataset.confirm = status.receiveReason;
      else delete button.dataset.confirm;
    } catch {
      // Keep the current action until another refresh succeeds.
    }
  }

  grid.addEventListener('click', event => {
    const button = event.target.closest('[data-select-table]');
    if (!button || writing) return;
    selectedButton = button;
    button.setAttribute('aria-pressed', 'true');
    showTableActions();
    dialog.showModal();
    refreshArrivalAction();
  });
  dialog.addEventListener('close', () => {
    dialogLoad?.abort();
    selectedButton?.setAttribute('aria-pressed', 'false');
    selectedButton = null;
  });
  dialog.addEventListener('click', async event => {
    if (event.target.closest('[data-board-back], [data-table-dialog-back]')) {
      if (writing) return;
      dialogLoad?.abort();
      showTableActions();
      refreshArrivalAction();
      content.querySelector('[data-table-action]')?.focus();
      return;
    }
    const link = event.target.closest('[data-table-action="walk-in"], [data-table-action="reserve"], [data-booking-reset], [data-move-url]');
    if (!link || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (writing) return;
    dialogLoad?.abort();
    const request = new AbortController();
    dialogLoad = request;
    const moving = Boolean(link.dataset.moveUrl);
    const url = new URL(link.dataset.moveUrl || link.href, location.href);
    if (!moving) url.searchParams.set('popup', 'true');
    try {
      const response = await fetch(url, { signal: request.signal });
      if (response.redirected) {
        location.assign(link.href);
        return;
      }
      if (!response.ok) throw new Error();
      const page = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (request.signal.aborted || !dialog.open) return;
      const form = page.querySelector(moving ? '[data-move-table-panel]' : '[data-booking-flow]');
      if (!form) throw new Error();
      if (moving) {
        title.textContent = 'Chuyển bàn';
        content.replaceChildren(form);
        (form.querySelector('input[type="radio"]') || form.querySelector('[data-table-dialog-back]')).focus();
      } else {
        title.textContent = url.searchParams.get('walkIn') === 'true' ? 'Nhận khách đến ngay' : 'Tạo đặt bàn';
        back.hidden = false;
        showBookingFlow(form);
      }
    } catch {
      if (!request.signal.aborted) showDialogError('Không thể mở thao tác. Vui lòng thử lại.');
    }
  });

  function showDialogError(message) {
    content.querySelector('[data-dialog-error]')?.remove();
    const error = document.createElement('p');
    error.className = 'staff-notice is-error';
    error.dataset.dialogError = '';
    error.setAttribute('role', 'alert');
    error.textContent = message;
    content.prepend(error);
  }
  for (const button of statusFilters) {
    button.addEventListener('click', () => {
      statusFilter = statusFilter === button.dataset.boardState ? '' : button.dataset.boardState;
      applyFilters();
    });
  }
  searchTable.addEventListener('input', applyFilters);
  searchGuest.addEventListener('input', applyFilters);
  kindFilter.addEventListener('change', applyFilters);

  async function refreshBoard(url, { body, bookingStep = false, fromHistory = false, quiet = false, resetFilters = false } = {}) {
    if (writing) {
      if (fromHistory) queuedHistory = url;
      return;
    }
    pendingRead?.abort();
    const request = new AbortController();
    pendingRead = body ? null : request;
    if (body) writing = true;
    grid.setAttribute('aria-busy', 'true');

    try {
      const response = await fetch(url, { method: body ? 'POST' : 'GET', body, signal: request.signal });
      if (request.signal.aborted) return;
      if (new URL(response.url).pathname !== new URL(filters.action).pathname && response.redirected) {
        location.assign(response.url);
        return;
      }
      const page = new DOMParser().parseFromString(await response.text(), 'text/html');
      if (request.signal.aborted) return;
      if (bookingStep && (response.ok || response.status === 422)) {
        const form = page.querySelector('[data-booking-flow]');
        if (form) {
          if (dialog.open) showBookingFlow(form);
          return;
        }
      }
      if (!response.ok) throw new Error();
      const nextGrid = page.querySelector('[data-board-grid]');
      const nextFilters = page.querySelector('[data-board-filters]');
      const nextMessages = page.querySelector('[data-board-messages]');
      if (!nextGrid || !nextFilters || !nextMessages) throw new Error();
      if (quiet && dialog.open) return;

      const focusedCard = (selectedButton || document.activeElement).closest('.table-board-card');
      if (dialog.open) dialog.close();
      grid.replaceChildren(...nextGrid.children);
      cards = [...grid.querySelectorAll('.table-board-card')];
      context.textContent = page.querySelector('[data-board-context]').textContent;
      filters.elements.time.options[0].textContent = nextFilters.elements.time.options[0].textContent;
      for (const name of scheduleFields) {
        filters.elements[name].value = nextFilters.elements[name].value;
      }
      if (resetFilters) {
        searchTable.value = '';
        searchGuest.value = '';
        kindFilter.value = '';
        statusFilter = '';
      }
      appliedSchedule = scheduleValues();
      applyFilters();
      if (!quiet) messages.replaceChildren(...nextMessages.childNodes);
      if (focusedCard) {
        cards.find(card => card.dataset.label === focusedCard.dataset.label && !card.hidden)
          ?.querySelector('[data-select-table]').focus({ preventScroll: true });
      }
      if (!body && !fromHistory && !quiet && url.href !== location.href) window.t3lPage.recordInline(url);
    } catch {
      if (request.signal.aborted || quiet) return;
      if (fromHistory) {
        location.reload();
        return;
      }
      const message = body
        ? 'Không nhận được kết quả. Hãy làm mới sơ đồ để kiểm tra trạng thái trước khi thử lại.'
        : 'Không thể cập nhật sơ đồ bàn. Vui lòng thử lại.';
      if (bookingStep && dialog.open) {
        showDialogError(message);
        return;
      }
      if (dialog.open) dialog.close();
      const error = document.createElement('p');
      error.className = 'staff-notice is-error';
      error.textContent = message;
      messages.replaceChildren(error);
    } finally {
      if (body || pendingRead === request) {
        writing = false;
        pendingRead = null;
        grid.removeAttribute('aria-busy');
        if (queuedHistory) {
          const next = queuedHistory;
          queuedHistory = null;
          refreshBoard(next, { fromHistory: true });
        }
      }
    }
  }

  filters.addEventListener('submit', event => {
    event.preventDefault();
    const url = new URL(filters.action);
    url.search = new URLSearchParams(new FormData(filters));
    refreshBoard(url);
  });
  filters.querySelector('[data-board-today]').addEventListener('click', event => {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    const url = new URL(event.currentTarget.href);
    const guests = filters.elements.guests.value;
    if (guests !== '1') url.searchParams.set('guests', guests);
    refreshBoard(url);
  });
  filters.querySelector('[data-board-reset]').addEventListener('click', event => {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    refreshBoard(new URL(event.currentTarget.href), { resetFilters: true });
  });
  board.addEventListener('submit', event => {
    if (event.defaultPrevented || !event.target.matches('[data-board-write], [data-booking-step]')) return;
    event.preventDefault();
    const form = event.target;
    const body = new URLSearchParams(new FormData(form, event.submitter));
    if (form.matches('[data-booking-save]') || body.has('fromBoard')) {
      for (const name of ['Date', 'Time', 'Guests']) {
        body.set('return' + name, filters.elements[name.toLowerCase()].value);
      }
    }
    refreshBoard(new URL(form.action), { body, bookingStep: form.matches('[data-booking-step]') });
  }, { signal });
  window.addEventListener('t3l:inline-pop', () => refreshBoard(new URL(location.href), { fromHistory: true }), { signal });
  const arrivalTimer = window.setInterval(refreshArrivalAction, 15000);
  const boardTimer = window.setInterval(() => {
    if (!document.hidden && !dialog.open && !writing && !pendingRead && !filters.contains(document.activeElement)
        && appliedSchedule === scheduleValues()) {
      refreshBoard(new URL(location.href), { quiet: true });
    }
  }, 20000);
  signal.addEventListener('abort', () => {
    window.clearInterval(arrivalTimer);
    window.clearInterval(boardTimer);
    pendingRead?.abort();
    dialogLoad?.abort();
  }, { once: true });
  applyFilters();
});
