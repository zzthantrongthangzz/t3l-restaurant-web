window.t3lPage.register(({ signal, state }) => {
  document.querySelectorAll('[data-preorder-picker]').forEach((picker) => {
    const currency = new Intl.NumberFormat('vi-VN');
    const format = (amount) => currency.format(amount) + ' đ';
    const minimum = BigInt(picker.dataset.minimum);
    const paid = BigInt(picker.dataset.paid);
    const voucher = picker.querySelector('[data-voucher-url]');
    const voucherCode = voucher?.querySelector('[data-voucher-code]');
    const voucherMessage = voucher?.querySelector('[data-voucher-message]');
    let quoteRequest;
    let quoteTimer;
    const search = picker.querySelector('[data-preorder-search]');
    const category = picker.querySelector('[data-preorder-category]');
    const cart = picker.querySelector('[data-preorder-lines]');
    const fields = picker.querySelector('[data-preorder-fields]');
    const warning = picker.querySelector('[data-preorder-warning]');
    const submit = picker.closest('form').querySelector('[data-preorder-booking-submit]');
    const pageUrl = location.pathname + location.search;
    const saved = state.preorder?.pageUrl === pageUrl ? state.preorder : null;
    const normalize = (text) => text.toLocaleLowerCase('vi').normalize('NFD').replace(/\p{M}/gu, '').replaceAll('đ', 'd');
    const items = new Map([...picker.querySelectorAll('[data-preorder-product]')].map((element) => {
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

    for (const value of new Set([...items.values()].map((item) => item.category))) {
      category.add(new Option(value, value));
    }
    if (saved) {
      search.value = saved.search;
      if ([...category.options].some(option => option.value === saved.category)) category.value = saved.category;
    }
    document.addEventListener('t3l:page-save', event => {
      event.detail.preorder = {
        pageUrl, search: search.value, category: category.value,
        quantities: Object.fromEntries([...items].map(([id, item]) => [id, item.quantity]))
      };
    }, { signal });

    function filter() {
      const query = normalize(search.value.trim());
      let visible = 0;
      items.forEach((item) => {
        const matches = (!category.value || category.value === item.category)
          && item.searchName.includes(query);
        item.element.hidden = !matches;
        if (matches) visible += 1;
      });
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
      picker.querySelector('[data-preorder-total]').textContent = format(total);
      picker.querySelector('[data-preorder-deposit]').textContent = format(deposit);
      picker.querySelector('[data-preorder-balance]').textContent = format(balance);
      const extra = picker.querySelector('[data-preorder-topup]');
      if (extra) extra.textContent = format(topup);
      if (voucher) {
        voucher.querySelector('[data-voucher-discount]').textContent = format(discount);
        voucher.querySelector('[data-voucher-net]').textContent = format(net);
      }
    }

    async function preview(requestedByUser = false) {
      clearTimeout(quoteTimer);
      quoteRequest?.abort();
      const code = voucherCode?.value.trim();
      if (!code) {
        if (voucherMessage) voucherMessage.textContent = '';
        return;
      }
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
      try {
        const response = await fetch(voucher.dataset.voucherUrl,{method:'POST',body,signal:ctrl.signal,credentials:'same-origin'});
        const result = await response.json();
        if (ctrl.signal.aborted || !picker.isConnected) return;
        if (!response.ok) {
          voucherMessage.textContent = result.message || 'Chưa thể kiểm tra mã voucher.';
          return;
        }
        showTotals(...['gross','discount','net','deposit','topup','balance'].map(key=>BigInt(result[key])));
        voucherMessage.textContent = 'Mã hợp lệ với giỏ hiện tại. Lượt được giữ khi yêu cầu được lưu hoặc duyệt.';
        if (requestedByUser) document.dispatchEvent(new Event('t3l:voucher-applied'));
      } catch (error) {
        if (error.name !== 'AbortError') voucherMessage.textContent = 'Chưa thể kiểm tra mã voucher.';
      }
    }

    function render() {
      const lines = document.createDocumentFragment();
      const inputs = document.createDocumentFragment();
      let total = 0n;
      let count = 0;
      let unavailable = false;

      items.forEach((item) => {
        item.element.querySelector('[data-preorder-count]').textContent = item.quantity;
        item.element.querySelector('[data-preorder-step="-1"]').disabled = item.quantity === 0;
        item.element.querySelector('[data-preorder-step="1"]').disabled = !item.available || item.quantity >= 20;
        if (item.quantity === 0) return;
        count += 1;
        const price = amount(item);
        total += price;
        unavailable ||= !item.available;
        const row = document.createElement('li');
        const description = document.createElement('span');
        description.textContent = item.name + ' × ' + item.quantity + ' · ' + (item.available ? format(price) : 'Hết hoặc ngừng bán');
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.dataset.preorderRemove = item.id;
        remove.setAttribute('aria-label', 'Bỏ ' + item.name + ' khỏi giỏ');
        remove.textContent = 'Bỏ';
        row.append(description, remove);
        lines.append(row);
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'preorderQuantities[' + item.id + ']';
        input.value = item.quantity;
        inputs.append(input);
      });

      const portion = (total * 70n + 99n) / 100n;
      const deposit = portion > minimum ? portion : minimum;
      const credit = paid > deposit ? paid : deposit;
      showTotals(total,0n,total,deposit,deposit > paid ? deposit - paid : 0n,total > credit ? total - credit : 0n);
      picker.querySelector('[data-preorder-expected]').value = total.toString();
      picker.querySelector('[data-preorder-empty]').hidden = count > 0;
      cart.replaceChildren(lines);
      fields.replaceChildren(inputs);
      if (voucher) {
        clearTimeout(quoteTimer);
        quoteRequest?.abort();
        voucherMessage.textContent = '';
        if (voucherCode.value.trim()) quoteTimer = setTimeout(preview, 240);
      }
      warning.textContent = unavailable ? 'Bỏ món đã hết hoặc ngừng bán khỏi giỏ trước khi gửi.' : '';
      if (submit && picker.dataset.buyer === 'false') {
        submit.textContent = count > 0 ? 'Đăng nhập để đặt bàn và món' : submit.dataset.defaultLabel;
        if (count > 0) {
          submit.name = 'loginOnly';
          submit.value = 'true';
          submit.formNoValidate = true;
        } else {
          submit.removeAttribute('name');
          submit.formNoValidate = false;
        }
      }
    }

    voucherCode?.addEventListener('input',render);
    voucher?.querySelector('[data-voucher-select]')?.addEventListener('change',event => {
      if (!event.target.value) return;
      voucherCode.value = event.target.value;
      render();
      void preview(true);
    });
    voucher?.querySelector('[data-voucher-preview]').addEventListener('click',()=> void preview(true));
    signal.addEventListener('abort',()=> {clearTimeout(quoteTimer);quoteRequest?.abort();},{once:true});

    search.addEventListener('input', filter);
    category.addEventListener('change', filter);
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
        if (item.available && !item.element.hidden) item.element.querySelector('[data-preorder-step="1"]').focus();
        else search.focus();
        return;
      }
      if (!button.dataset.preorderStep) return;
      const item = items.get(button.closest('[data-preorder-product]').dataset.id);
      const step = Number(button.dataset.preorderStep);
      if (step > 0 && item.quantity === 0 && [...items.values()].filter((product) => product.quantity > 0).length >= 30) {
        warning.textContent = 'Giỏ chỉ nhận tối đa 30 loại món.';
        return;
      }
      const before = item.quantity;
      item.quantity += step;
      render();
      trackCart(item, before);
    });

    filter();
    render();
  });
});
