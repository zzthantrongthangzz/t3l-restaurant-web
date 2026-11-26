window.t3lPage.register(({ signal, state }) => {
  document.querySelectorAll('[data-preorder-picker]').forEach((picker) => {
    const booking = picker.hasAttribute('data-booking-preorder');
    const currency = new Intl.NumberFormat('vi-VN');
    const format = (amount) => currency.format(amount) + ' đ';
    const minimum = BigInt(picker.dataset.minimum ?? 0);
    const paid = BigInt(picker.dataset.paid ?? 0);
    const currentDeposit = BigInt(picker.dataset.currentDeposit ?? 0);
    const editForm = booking ? null : picker.closest('form');
    const kitchenNote = editForm?.elements.preorderNote;
    const voucher = booking ? null : picker.querySelector('[data-voucher-url]');
    const voucherCode = voucher?.querySelector('[data-voucher-code]');
    const voucherMessage = voucher?.querySelector('[data-voucher-message]');
    let quoteRequest;
    let quoteTimer;
    const search = picker.querySelector('[data-preorder-search]');
    const category = picker.querySelector('[data-preorder-category]');
    const categories = picker.querySelector('[data-preorder-categories]');
    const cart = picker.querySelector('[data-preorder-lines]');
    const fields = picker.querySelector('[data-preorder-fields]');
    const warning = picker.querySelector('[data-preorder-warning]');
    const pageUrl = location.pathname + location.search;
    const saved = state.preorder?.pageUrl === pageUrl ? state.preorder : null;
    const normalize = (text) => text.toLocaleLowerCase('vi').normalize('NFD').replace(/\p{M}/gu, '').replaceAll('đ', 'd');
    const products = [...picker.querySelectorAll('[data-preorder-product]')];
    if (categories) {
      const drink = (product) => /\b(do uong|thuc uong|nuoc uong|ruou|bia|cocktail|mocktail)\b/.test(normalize(product.dataset.category ?? ''));
      products.sort((a, b) => Number(drink(a)) - Number(drink(b)));
      picker.querySelector('.preorder-products').append(...products);
    }
    const items = new Map(products.map((element) => {
      const {id, name, category, price, quantity, available} = element.dataset;
      const tiers = [...element.querySelectorAll('[data-preorder-tier]')].map((tier) => ({
        price: BigInt(tier.dataset.price), quantity: Number(tier.dataset.quantity)
      }));
      const item = {
        id, name, category, price: BigInt(price), searchName: normalize(name),
        quantity: saved?.quantities[id] ?? Number(quantity), available: available === 'true', tiers, element
      };
      element.querySelector('img').addEventListener('error', (event) => {
        const image = event.currentTarget;
        if (image.src !== new URL(picker.dataset.placeholder, location.href).href) image.src = picker.dataset.placeholder;
      });
      return [id, item];
    }));
    const initialQuantities = [...items.values()].map(item => item.quantity);
    const initialNote = kitchenNote?.value;
    const initialVoucher = voucherCode?.value.trim();
    const beforeLeave = event => {
      event.preventDefault();
      event.returnValue = '';
    };

    function trackUnsavedChanges() {
      if (!editForm) return;
      const dirty = editForm.dataset.unsaved === 'true' || kitchenNote.value !== initialNote
        || voucherCode.value.trim() !== initialVoucher
        || [...items.values()].some((item, i) => item.quantity !== initialQuantities[i]);
      if (dirty) window.addEventListener('beforeunload', beforeLeave);
      else window.removeEventListener('beforeunload', beforeLeave);
    }

    kitchenNote?.addEventListener('input', trackUnsavedChanges);
    editForm?.addEventListener('submit', () => window.removeEventListener('beforeunload', beforeLeave));
    signal.addEventListener('abort', () => window.removeEventListener('beforeunload', beforeLeave), { once: true });

    const categoryValues = [...new Set([...items.values()].map((item) => item.category).filter(Boolean))];
    let selectedCategory = categoryValues.includes(saved?.category) ? saved.category : '';
    if (category) {
      for (const value of categoryValues) category.add(new Option(value, value));
      category.value = selectedCategory;
    }
    if (categories && categoryValues.length) {
      for (const value of ['', ...categoryValues]) {
        const button = document.createElement('button');
        button.type = 'button';
        button.dataset.preorderFilter = value;
        button.textContent = value || 'Tất cả';
        categories.append(button);
      }
      categories.hidden = false;
      categories.addEventListener('click', (event) => {
        const button = event.target.closest('[data-preorder-filter]');
        if (!button) return;
        selectedCategory = button.dataset.preorderFilter;
        filter();
      });
    }
    if (saved) search.value = saved.search;
    document.addEventListener('t3l:page-save', event => {
      event.detail.preorder = {
        pageUrl, search: search.value, category: selectedCategory,
        quantities: Object.fromEntries([...items].map(([id, item]) => [id, item.quantity]))
      };
    }, { signal });

    function filter() {
      const query = normalize(search.value.trim());
      let visible = 0;
      items.forEach((item) => {
        const matches = (!selectedCategory || selectedCategory === item.category)
          && item.searchName.includes(query);
        item.element.hidden = !matches;
        if (matches) visible += 1;
      });
      for (const button of categories?.children ?? []) {
        button.setAttribute('aria-pressed', String(button.dataset.preorderFilter === selectedCategory));
      }
      picker.querySelector('[data-preorder-no-results]').hidden = visible > 0;
    }

    function amount(item) {
      let remaining = item.quantity;
      let total = 0n;
      for (const tier of item.tiers) {
        const kept = Math.min(remaining, tier.quantity);
        total += tier.price * BigInt(kept);
        remaining -= kept;
      }
      return total + item.price * BigInt(remaining);
    }

    function showTotals(total, discount, net, deposit, topup, balance) {
      const quoted = deposit != null;
      picker.querySelector('[data-preorder-total]').textContent = format(total);
      picker.querySelector('[data-preorder-deposit]').textContent = quoted ? format(deposit) : '—';
      picker.querySelector('[data-preorder-balance]').textContent = quoted ? format(balance) : '—';
      picker.querySelector('[data-preorder-topup]').textContent = quoted ? format(topup) : '—';
      picker.querySelector('[data-voucher-discount]').textContent = quoted ? (discount > 0n ? '−' : '') + format(discount) : '—';
      picker.querySelector('[data-voucher-net]').textContent = quoted ? format(net) : '—';
      const difference = quoted ? deposit - currentDeposit : 0n;
      const change = picker.querySelector('[data-preorder-deposit-change-row]');
      change.hidden = difference === 0n;
      const value = picker.querySelector('[data-preorder-deposit-change]');
      value.textContent = difference < 0n ? '−' + format(-difference) : '+' + format(difference);
    }

    async function preview(requestedByUser = false) {
      clearTimeout(quoteTimer);
      quoteRequest?.abort();
      const code = voucherCode?.value.trim();
      const ctrl = new AbortController();
      quoteRequest = ctrl;
      const form = picker.closest('form');
      const body = new URLSearchParams();
      for (const input of fields.querySelectorAll('input')) body.set(input.name, input.value);
      body.set('voucherCode',code);
      const csrf = form.querySelector('input[name="_csrf"]');
      if (csrf) body.set(csrf.name,csrf.value);
      const table = form.querySelector('input[name="tableId"]');
      if (table) body.set('tableId',table.value);
      if (voucher.dataset.voucherBooking) body.set('bookingCode',voucher.dataset.voucherBooking);
      voucherMessage.textContent = code ? 'Đang kiểm tra voucher…' : '';
      voucherMessage.dataset.state = '';
      try {
        const response = await fetch(voucher.dataset.voucherUrl,{method:'POST',body,signal:ctrl.signal,credentials:'same-origin'});
        const result = await response.json();
        if (ctrl.signal.aborted || !picker.isConnected) return;
        if (!response.ok) {
          voucherMessage.textContent = result.message || 'Chưa thể kiểm tra giỏ món.';
          voucherMessage.dataset.state = 'error';
          return;
        }
        showTotals(...['gross','discount','net','deposit','topup','balance'].map(key=>BigInt(result[key])));
        voucherMessage.textContent = code ? 'Voucher áp dụng được cho giỏ hiện tại.' : '';
        voucherMessage.dataset.state = code ? 'success' : '';
        if (code && requestedByUser) document.dispatchEvent(new Event('t3l:voucher-applied'));
      } catch (error) {
        if (error.name !== 'AbortError') {
          voucherMessage.textContent = 'Chưa thể kiểm tra giỏ món. Bạn có thể thử lại.';
          voucherMessage.dataset.state = 'error';
        }
      }
    }

    function render() {
      const lines = document.createDocumentFragment();
      const inputs = document.createDocumentFragment();
      const review = document.createDocumentFragment();
      let total = 0n;
      let count = 0;
      let portions = 0;
      let unavailable = false;

      items.forEach((item) => {
        item.element.querySelector('[data-preorder-count]').textContent = item.quantity;
        item.element.querySelector('[data-preorder-step="-1"]').disabled = item.quantity === 0;
        item.element.querySelector('[data-preorder-step="1"]').disabled = !item.available || item.quantity >= 20;
        if (item.quantity === 0) return;
        count += 1;
        portions += item.quantity;
        const price = amount(item);
        total += price;
        unavailable ||= !item.available;
        const row = document.createElement('li');
        const description = document.createElement('span');
        description.textContent = item.name + ' × ' + item.quantity;
        const linePrice = document.createElement('strong');
        linePrice.textContent = item.available ? format(price) : 'Hết hoặc ngừng bán';
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.dataset.preorderRemove = item.id;
        remove.setAttribute('aria-label', 'Bỏ ' + item.name + ' khỏi giỏ');
        remove.textContent = 'Bỏ';
        if (booking) {
          row.dataset.preorderCartId = item.id;
          const summary = document.createElement('li');
          summary.append(description.cloneNode(true), linePrice.cloneNode(true));
          review.append(summary);
          const controls = item.element.querySelector('.preorder-stepper').cloneNode(true);
          controls.append(remove);
          row.append(description, linePrice, controls);
        } else row.append(description, linePrice, remove);
        lines.append(row);
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'preorderQuantities[' + item.id + ']';
        input.value = item.quantity;
        inputs.append(input);
      });

      picker.querySelector('[data-preorder-expected]').value = total.toString();
      picker.querySelector('[data-preorder-empty]').hidden = count > 0;
      cart.replaceChildren(lines);
      fields.replaceChildren(inputs);
      warning.textContent = unavailable ? 'Bỏ món đã hết hoặc ngừng bán khỏi giỏ trước khi gửi.' : '';
      if (booking) {
        picker.querySelector('[data-booking-cart-content]').hidden = count === 0;
        const cartCount = picker.querySelector('[data-booking-cart-count]');
        cartCount.hidden = count === 0;
        cartCount.textContent = portions + ' phần';
        const note = picker.querySelector('[data-booking-cart-note]');
        note.hidden = count === 0;
        picker.querySelector('[data-booking-cart-total]').textContent = format(total);
        picker.querySelector('[data-booking-review-total]').textContent = format(total);
        picker.querySelector('[data-booking-review-lines]').replaceChildren(review);
        picker.dispatchEvent(new Event('booking:cart-change'));
        return;
      }

      const portion = (total * 70n + 99n) / 100n;
      const deposit = portion > minimum ? portion : minimum;
      const credit = paid > deposit ? paid : deposit;
      picker.querySelectorAll('[data-preorder-count-summary]').forEach(label => { label.textContent = portions + ' phần'; });
      if (voucherCode.value.trim() || initialVoucher) showTotals(total);
      else showTotals(total, 0n, total, deposit, deposit > paid ? deposit - paid : 0n, total > credit ? total - credit : 0n);
      clearTimeout(quoteTimer);
      quoteRequest?.abort();
      voucherMessage.textContent = '';
      if (!unavailable) quoteTimer = setTimeout(preview, 240);
      trackUnsavedChanges();
    }

    voucherCode?.addEventListener('input',render);
    voucher?.querySelector('[data-voucher-select]')?.addEventListener('change',event => {
      if (!event.target.value) return;
      voucherCode.value = event.target.value;
      render();
      void preview(true);
    });
    voucherCode?.addEventListener('keydown', event => {
      if (event.key !== 'Enter' || event.isComposing) return;
      event.preventDefault();
      void preview(true);
    });
    voucher?.querySelector('[data-voucher-preview]').addEventListener('click',()=> void preview(true));
    signal.addEventListener('abort',()=> {clearTimeout(quoteTimer);quoteRequest?.abort();},{once:true});
    if (editForm && 'ResizeObserver' in window) {
      const cartPanel = cart.closest('aside');
      const header = document.querySelector('.home-header');
      const observer = new ResizeObserver(() => {
        const headerHeight = header?.getBoundingClientRect().height ?? 0;
        cartPanel.style.setProperty('--preorder-sticky-top', Math.ceil(headerHeight + 14) + 'px');
        cartPanel.style.setProperty('--preorder-cart-height', Math.ceil(cartPanel.getBoundingClientRect().height) + 'px');
      });
      observer.observe(cartPanel);
      if (header) observer.observe(header);
      signal.addEventListener('abort', () => observer.disconnect(), { once: true });
    }

    search.addEventListener('input', filter);
    category?.addEventListener('change', () => {
      selectedCategory = category.value;
      filter();
    });
    function trackCart(item, before) {
      const type = item.quantity === 0 ? 'CART_REMOVE' : before === 0 ? 'CART_ADD' : 'CART_QUANTITY';
      document.dispatchEvent(new CustomEvent('t3l:cart', { detail: { type, id: item.id, quantity: item.quantity } }));
    }

    picker.addEventListener('click', (event) => {
      const button = event.target.closest('button');
      if (!button) return;
      if (button.dataset.preorderRemove) {
        const item = items.get(button.dataset.preorderRemove);
        const before = item.quantity;
        item.quantity = 0;
        render();
        trackCart(item, before);
        if (booking) (cart.querySelector('button:enabled') ?? cart.parentElement.querySelector('h3')).focus();
        else if (item.available && !item.element.hidden) item.element.querySelector('[data-preorder-step="1"]').focus();
        else search.focus();
        return;
      }
      if (!button.dataset.preorderStep) return;
      const cartRow = button.closest('[data-preorder-cart-id]');
      const id = cartRow?.dataset.preorderCartId ?? button.closest('[data-preorder-product]').dataset.id;
      const item = items.get(id);
      const step = Number(button.dataset.preorderStep);
      if (step > 0 && item.quantity === 0 && [...items.values()].filter((product) => product.quantity > 0).length >= 30) {
        warning.textContent = 'Giỏ chỉ nhận tối đa 30 loại món.';
        return;
      }
      const before = item.quantity;
      item.quantity += step;
      render();
      trackCart(item, before);
      if (cartRow) {
        const row = [...cart.children].find(element => element.dataset.preorderCartId === id);
        const replacement = row?.querySelector('[data-preorder-step="' + step + '"]:enabled, [data-preorder-remove]');
        (replacement ?? cart.querySelector('button:enabled') ?? cart.parentElement.querySelector('h3')).focus();
      }
    });

    filter();
    render();
  });
});
