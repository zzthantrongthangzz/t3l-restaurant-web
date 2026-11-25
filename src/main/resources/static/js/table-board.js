window.t3lPage.register(({ signal, state }) => {
  const board = document.querySelector('[data-table-board]');
  if (!board) return;
  const cards = [...board.querySelectorAll('[data-select-table]')].map(button => button.closest('.table-board-card'));
  const commands = [...board.querySelectorAll('[data-board-command]')];
  const statusFilters = [...board.querySelectorAll('[data-board-state]')];
  const dialog = board.querySelector('[data-board-dialog]');
  const content = dialog.querySelector('[data-table-content]');
  const searchTable = board.querySelector('[data-filter-table]');
  const searchGuest = board.querySelector('[data-filter-guest]');
  const kindFilter = board.querySelector('[data-filter-kind]');
  let selectedCard;
  let statusFilter = state.board?.status ?? '';
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

  function category(card) {
    return ['PENDING', 'RESERVED'].includes(card.dataset.state) ? 'WAITING' : card.dataset.state;
  }

  function findAction(command) {
    return content.querySelector('[data-table-action="' + command + '"]');
  }

  function selectCard(card) {
    selectedCard?.querySelector('[data-select-table]').setAttribute('aria-pressed', 'false');
    selectedCard = card;
    content.replaceChildren();
    if (card) {
      card.querySelector('[data-select-table]').setAttribute('aria-pressed', 'true');
      dialog.querySelector('[data-table-title]').textContent = card.dataset.label;
      content.append(card.querySelector('template').content.cloneNode(true));
    }
    commands.forEach(button => {
      button.disabled = !card || (button.dataset.boardCommand !== 'details' && !findAction(button.dataset.boardCommand));
    });
  }

  function applyFilters() {
    const counts = { FREE: 0, WAITING: 0, OCCUPIED: 0 };
    const tableQuery = normalize(searchTable.value);
    const guestQuery = normalize(searchGuest.value);
    for (const card of cards) {
      const state = category(card);
      const matches = (!kindFilter.value || card.dataset.kind === kindFilter.value)
        && normalize(card.dataset.search).includes(tableQuery) && matchesGuest(card, guestQuery);
      if (matches && Object.hasOwn(counts, state)) counts[state] += 1;
      card.hidden = !matches || (statusFilter && statusFilter !== state);
    }
    if (selectedCard?.hidden) selectCard(undefined);
    statusFilters.forEach(button => {
      button.setAttribute('aria-pressed', String(button.dataset.boardState === statusFilter));
      button.querySelector('[data-count]').textContent = counts[button.dataset.boardState];
    });
    board.querySelector('[data-board-empty]').hidden = cards.some(card => !card.hidden);
  }

  for (const card of cards) {
    card.querySelector('[data-select-table]').addEventListener('click', () => {
      selectCard(card);
      dialog.showModal();
    });
  }
  for (const button of commands) {
    button.addEventListener('click', () => {
      const command = button.dataset.boardCommand;
      const action = findAction(command);
      if (action instanceof HTMLAnchorElement) action.click();
      else if (action instanceof HTMLButtonElement) action.form.requestSubmit(action);
      else if (command === 'details' && selectedCard) dialog.showModal();
    });
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
  board.querySelector('[data-board-date]').addEventListener('change', event => {
    if (event.target.validity.valid) event.target.form.requestSubmit();
  }, { signal });
  document.addEventListener('t3l:page-save', event => {
    event.detail.board = { status: statusFilter, table: searchTable.value, guest: searchGuest.value, kind: kindFilter.value };
  }, { signal });
  document.addEventListener('keydown', event => {
    if (dialog.open || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey
        || event.target.closest('input, textarea, select, [contenteditable]')) return;
    const command = commands.find(button => button.querySelector('kbd').textContent === event.key);
    if (!command || command.disabled) return;
    event.preventDefault();
    command.click();
  }, { signal });
  applyFilters();
});
