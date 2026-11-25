window.t3lPage.register(({ signal, state }) => {
  const groups = [...document.querySelectorAll('[data-group]')];
  const filters = [...document.querySelectorAll('[data-category]')];
  const search = document.getElementById('menu-search');
  const resultCount = document.getElementById('menu-result-count');
  const noResults = document.getElementById('menu-no-results');
  const dialog = document.getElementById('dish-dialog');
  const selection = document.getElementById('menu-selection');
  const submit = document.querySelector('#menu-preorder button[type="submit"]');
  const emptySelection = document.getElementById('menu-selection-empty');
  const total = document.getElementById('menu-total');
  const shortcut = document.getElementById('menu-selection-shortcut');
  const formatPrice = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });
  const picked = new Map();
  const cards = [...document.querySelectorAll('.menu-card')];
  const names = new Map(cards.map(card => [card, normalize(card.dataset.name)]));
  const catalog = groups.map(group => [group, [...group.querySelectorAll('.menu-card')]]);
  const savedMenu = state.menu;
  let category = filters.some(button => button.dataset.category === savedMenu?.category) ? savedMenu.category : '';

  function normalize(value) {
    return value.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd').trim();
  }

  function filterCards() {
    const query = normalize(search.value);
    let count = 0;
    catalog.forEach(([group, cards]) => {
      let groupCount = 0;
      cards.forEach(card => {
        const matches = (!category || group.dataset.group === category) && names.get(card).includes(query);
        card.hidden = !matches;
        if (matches) groupCount++;
      });
      group.hidden = groupCount === 0;
      count += groupCount;
    });
    resultCount.textContent = count;
    noResults.hidden = count > 0;
  }

  if (groups.length) {
    search.closest('.menu-search').hidden = false;
    document.querySelector('.menu-categories').hidden = false;
    if (savedMenu) search.value = savedMenu.search;
    filters.forEach(button => button.setAttribute('aria-pressed', String(button.dataset.category === category)));
    filterCards();
    search.addEventListener('input', filterCards);
    filters.forEach(button => button.addEventListener('click', () => {
      category = button.dataset.category;
      filters.forEach(filter => filter.setAttribute('aria-pressed', String(filter === button)));
      filterCards();
    }));
  }

  function trackCart(type, id, quantity) {
    document.dispatchEvent(new CustomEvent('t3l:cart', { detail: { type, id, quantity } }));
  }

  function validQuantity(value) {
    const quantity = Number(value);
    return Number.isInteger(quantity) && quantity >= 1 && quantity <= 100 ? quantity : null;
  }

  function updateTotal() {
    let subtotal = 0;
    let valid = picked.size > 0;
    for (const { card, quantity } of picked.values()) {
      const count = validQuantity(quantity);
      if (count === null) valid = false;
      else subtotal += Number(card.dataset.price) * count;
    }
    total.textContent = formatPrice.format(subtotal);
    submit.disabled = !valid;
  }

  function updateSelection() {
    selection.replaceChildren();
    for (const [id, item] of picked) {
      const { card } = item;
      const field = 'items[' + selection.childElementCount + ']';
      const row = document.createElement('li');
      const picture = card.querySelector('img');
      const thumbnail = document.createElement('img');
      thumbnail.alt = '';
      thumbnail.src = picture.currentSrc || picture.src;
      thumbnail.addEventListener('error', () => { thumbnail.src = picture.dataset.fallback; }, { once: true });

      const name = document.createElement('span');
      name.textContent = card.dataset.name;
      const dishId = document.createElement('input');
      dishId.type = 'hidden';
      dishId.name = field + '.dishId';
      dishId.value = id;

      const quantity = document.createElement('input');
      quantity.className = 'menu-item-quantity';
      quantity.type = 'number';
      quantity.name = field + '.quantity';
      quantity.min = '1';
      quantity.max = '100';
      quantity.step = '1';
      quantity.required = true;
      quantity.value = item.quantity;
      quantity.setAttribute('aria-label', 'Số lượng ' + card.dataset.name);

      const stepper = document.createElement('div');
      stepper.className = 'menu-item-stepper';
      const decrease = document.createElement('button');
      const increase = document.createElement('button');
      for (const [button, delta, label, symbol] of [
        [decrease, -1, 'Giảm số lượng ', '−'],
        [increase, 1, 'Tăng số lượng ', '+']
      ]) {
        button.type = 'button';
        button.textContent = symbol;
        button.setAttribute('aria-label', label + card.dataset.name);
        button.addEventListener('click', () => {
          quantity.value = Math.min(100, Math.max(1, (validQuantity(quantity.valueAsNumber) ?? 1) + delta));
          updateQuantity();
          trackCart("CART_QUANTITY", id, quantity.valueAsNumber);
        });
      }
      stepper.append(decrease, quantity, increase);

      const price = document.createElement('strong');
      price.className = 'menu-item-price';
      function syncQuantity() {
        item.quantity = quantity.value;
        const count = validQuantity(quantity.valueAsNumber);
        price.textContent = count === null ? '—' : formatPrice.format(Number(card.dataset.price) * count);
        decrease.disabled = count === 1;
        increase.disabled = count === 100;
      }
      syncQuantity();
      function updateQuantity() {
        syncQuantity();
        updateTotal();
      }
      quantity.addEventListener('input', updateQuantity);
      quantity.addEventListener('change', () => {
        const count = validQuantity(quantity.valueAsNumber);
        if (count !== null) trackCart('CART_QUANTITY', id, count);
      });

      const note = document.createElement('input');
      note.className = 'menu-item-note-input';
      note.type = 'text';
      note.name = field + '.note';
      note.maxLength = 120;
      note.value = item.note;
      note.placeholder = 'Nhập ghi chú cho món...';
      note.setAttribute('aria-label', 'Ghi chú ' + card.dataset.name);
      note.addEventListener('input', () => { item.note = note.value; });

      const remove = document.createElement('button');
      remove.className = 'menu-item-remove';
      remove.type = 'button';
      remove.title = 'Xóa món';
      remove.setAttribute('aria-label', 'Xóa món ' + card.dataset.name);
      const svgNamespace = 'http://www.w3.org/2000/svg';
      const trash = document.createElementNS(svgNamespace, 'svg');
      trash.setAttribute('viewBox', '0 0 24 24');
      trash.setAttribute('aria-hidden', 'true');
      const lines = document.createElementNS(svgNamespace, 'path');
      lines.setAttribute('d', 'M4 7h16M9 7V4h6v3m3 0-1 13H7L6 7m4 3v7m4-7v7');
      trash.append(lines);
      remove.append(trash);
      remove.addEventListener('click', () => {
        toggleDish(card);
        (selection.querySelector('.menu-item-quantity') || document.querySelector('#menu-preorder a')).focus();
      });
      row.append(thumbnail, name, dishId, price, stepper, remove, note);
      selection.append(row);
    }
    emptySelection.hidden = picked.size > 0;
    shortcut.hidden = picked.size === 0;
    document.getElementById('menu-selection-count').textContent = picked.size;
    updateTotal();
  }

  selection?.closest('form').addEventListener('submit', event => {
    if (submit.disabled) {
      event.preventDefault();
      return;
    }
    submit.disabled = true;
    selection.querySelectorAll('.menu-item-quantity').forEach(input => { input.value = input.valueAsNumber; });
  });
  if (selection) window.addEventListener('pageshow', event => {
    if (event.persisted) updateTotal();
  }, { signal });

  function toggleDish(card) {
    const id = card.dataset.id;
    const selected = !picked.has(id);
    if (selected) picked.set(id, { card, quantity: '1', note: '' });
    else picked.delete(id);
    setSelected(card, selected);
    updateSelection();
    trackCart(selected ? "CART_ADD" : "CART_REMOVE", id, selected ? 1 : 0);
  }

  function setSelected(card, selected) {
    const button = card.querySelector('[data-add]');
    button.setAttribute('aria-pressed', String(selected));
    button.setAttribute('aria-label', (selected ? 'Bỏ ' : 'Chọn ') + card.dataset.name);
    button.textContent = selected ? '✓' : '+';
  }

  cards.forEach(card => {
    const picture = card.querySelector('img');
    const fallback = () => { picture.src = picture.dataset.fallback; };
    picture.addEventListener('error', fallback, { once: true });
    if (picture.complete && !picture.naturalWidth) fallback();

    const add = card.querySelector('[data-add]');
    if (add && selection) {
      add.hidden = false;
      const item = savedMenu?.items?.find(item => item.id === card.dataset.id);
      if (item) {
        picked.set(item.id, { card, quantity: item.quantity, note: item.note });
        setSelected(card, true);
      }
      add.addEventListener('click', () => toggleDish(card));
    }

    const detail = card.querySelector('[data-detail]');
    if (typeof dialog?.showModal === 'function') {
      detail.hidden = false;
      detail.addEventListener('click', () => {
        const preview = document.getElementById('dish-detail-image');
        preview.onerror = () => {
          preview.onerror = null;
          preview.src = picture.dataset.fallback;
        };
        preview.src = picture.currentSrc || picture.src;
        document.getElementById('dish-detail-title').textContent = card.dataset.name;
        document.getElementById('dish-detail-description').textContent = card.querySelector('.menu-description').textContent;
        document.getElementById('dish-detail-price').textContent = formatPrice.format(Number(card.dataset.price));
        const dietary = document.getElementById('dish-detail-dietary');
        dietary.textContent = card.querySelector('.menu-dietary')?.textContent || '';
        dietary.hidden = !dietary.textContent;
        dialog.showModal();
      });
    }
  });
  if (picked.size) updateSelection();
  if (search) {
    document.addEventListener('t3l:page-save', event => {
      event.detail.menu = {
        search: search.value, category,
        items: [...picked].map(([id, { quantity, note }]) => ({ id, quantity, note }))
      };
    }, { signal });
  }
});
