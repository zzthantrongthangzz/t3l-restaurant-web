(() => {
  const groups = [...document.querySelectorAll('[data-group]')];
  const filters = [...document.querySelectorAll('[data-category]')];
  const search = document.getElementById('menu-search');
  const resultCount = document.getElementById('menu-result-count');
  const noResults = document.getElementById('menu-no-results');
  const dialog = document.getElementById('dish-dialog');
  const selection = document.getElementById('menu-selection');
  const emptySelection = document.getElementById('menu-selection-empty');
  const total = document.getElementById('menu-total');
  const shortcut = document.getElementById('menu-selection-shortcut');
  const formatPrice = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });
  const picked = new Map();
  let category = '';

  function normalize(value) {
    return value.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g, 'd').trim();
  }

  function filterCards() {
    const query = normalize(search.value);
    let count = 0;
    groups.forEach(group => {
      let groupCount = 0;
      group.querySelectorAll('.menu-card').forEach(card => {
        const matches = (!category || group.dataset.group === category) && normalize(card.dataset.name).includes(query);
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
    search.addEventListener('input', filterCards);
    filters.forEach(button => button.addEventListener('click', () => {
      category = button.dataset.category;
      filters.forEach(filter => filter.setAttribute('aria-pressed', String(filter === button)));
      filterCards();
    }));
  }

  function updateSelection() {
    selection.replaceChildren();
    let subtotal = 0;
    for (const [id, card] of picked) {
      subtotal += Number(card.dataset.price);
      const row = document.createElement('li');
      const picture = card.querySelector('img');
      const thumbnail = document.createElement('img');
      thumbnail.alt = '';
      thumbnail.src = picture.currentSrc || picture.src;
      thumbnail.addEventListener('error', () => { thumbnail.src = picture.dataset.fallback; }, { once: true });
      const name = document.createElement('span');
      name.textContent = card.dataset.name;
      const input = document.createElement('input');
      input.type = 'hidden';
      input.name = 'preorderDishIds';
      input.value = id;
      const price = document.createElement('strong');
      price.textContent = formatPrice.format(Number(card.dataset.price));
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.textContent = '×';
      remove.setAttribute('aria-label', 'Bỏ ' + card.dataset.name);
      remove.addEventListener('click', () => {
        toggleDish(card);
        (selection.querySelector('button') || document.querySelector('#menu-preorder button[type="submit"]')).focus();
      });
      row.append(thumbnail, name, input, price, remove);
      selection.append(row);
    }
    emptySelection.hidden = picked.size > 0;
    total.textContent = formatPrice.format(subtotal);
    shortcut.hidden = picked.size === 0;
    document.getElementById('menu-selection-count').textContent = picked.size;
  }

  function toggleDish(card) {
    const id = card.dataset.id;
    const selected = !picked.has(id);
    if (selected) picked.set(id, card);
    else picked.delete(id);
    const button = card.querySelector('[data-add]');
    button.setAttribute('aria-pressed', String(selected));
    button.setAttribute('aria-label', (selected ? 'Bỏ ' : 'Chọn ') + card.dataset.name);
    button.textContent = selected ? '✓' : '+';
    updateSelection();
  }

  document.querySelectorAll('.menu-card').forEach(card => {
    const picture = card.querySelector('img');
    const fallback = () => { picture.src = picture.dataset.fallback; };
    picture.addEventListener('error', fallback, { once: true });
    if (picture.complete && !picture.naturalWidth) fallback();

    const add = card.querySelector('[data-add]');
    if (add && selection) {
      add.hidden = false;
      add.addEventListener('click', () => toggleDish(card));
    }

    const detail = card.querySelector('[data-detail]');
    if (typeof dialog.showModal === 'function') {
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
})();
