(() => {
  const script = document.currentScript;
  const endpoint = script.dataset.endpoint;
  const queue = [];
  const activityWindow = 30000;
  let choice = 'unset';
  let csrf;
  let preferences;
  let currentMain;
  let page;
  let observer;
  let lastInteraction = performance.now();
  let lastTick = lastInteraction;
  let seconds = 0;
  let inFlight = false;
  let nextSend = 0;
  let panel;
  let banner;

  function pageKey() {
    const prefix = new URL(endpoint, location.href).pathname.replace(/\/tracking\/$/, '');
    const path = location.pathname.slice(prefix.length).replace(/\/$/, '') || '/';
    if (!document.querySelector('[data-nav-shell="public"]')) return null;
    return ({
      '/': 'home', '/menu': 'menu', '/about': 'about', '/contact': 'contact', '/faq': 'faq',
      '/reservations/new': 'booking', '/reservations/lookup': 'lookup', '/reservations/confirmation': 'confirmation',
      '/login': 'login', '/login/sms': 'login', '/login/recover': 'login', '/register': 'login',
      '/account': 'account', '/account/reservations': 'history', '/account/orders': 'orders',
      '/account/vouchers': 'vouchers', '/account/addresses': 'addresses', '/account/invoices': 'invoices'
    })[path] || (/^\/account\/reservations\/[^/]+\/preorder$/.test(path) ? 'preorder'
      : /^\/account\/addresses\/(new|\d+)$/.test(path) ? 'addresses' : null);
  }

  function record(type, data = {}) {
    if (choice !== 'allowed' || !page) return;
    queue.push({ id: eventId(), type, page, ...data });
    if (queue.length > 72) queue.shift();
    if (queue.length >= 20) void flush();
  }

  function eventId() {
    if (crypto.randomUUID) return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    return hex.replace(/^(........)(....)(....)(....)(............)$/, '$1-$2-$3-$4-$5');
  }

  function post(path, body, keepalive = false) {
    return fetch(endpoint + path, {
      method: 'POST', credentials: 'same-origin', keepalive,
      headers: { 'Content-Type': 'application/json', [csrf.header]: csrf.token },
      body: JSON.stringify(body)
    });
  }

  async function flush(leaving = false) {
    if (choice !== 'allowed' || !csrf || !queue.length || performance.now() < nextSend || (inFlight && !leaving)) return;
    const events = queue.splice(0, 24);
    if (!leaving) inFlight = true;
    try {
      const response = await post('events', { events }, leaving);
      if (response.status === 403) {
        queue.length = 0;
        void readPreference(true);
      } else if (response.status === 429 && !leaving) {
        nextSend = performance.now() + 60000;
        queue.unshift(...events);
      } else if (response.status >= 500 && !leaving) {
        nextSend = performance.now() + 20000;
        queue.unshift(...events);
      }
    } catch {
      if (!leaving && choice === 'allowed') {
        nextSend = performance.now() + 20000;
        queue.unshift(...events);
      }
    } finally {
      if (!leaving) inFlight = false;
    }
  }

  function tick() {
    const now = performance.now();
    if (choice === 'allowed' && document.visibilityState === 'visible' && document.hasFocus()) {
      seconds += Math.max(0, Math.min(now, lastInteraction + activityWindow) - lastTick) / 1000;
    }
    lastTick = now;
  }

  function checkpoint() {
    const whole = Math.min(15, Math.floor(seconds));
    if (whole > 0) record('ACTIVE', { seconds: whole });
    seconds = 0;
  }

  function switchPage(force = false) {
    const next = pageKey();
    const main = document.querySelector('main');
    if (!force && main === currentMain && page === next) return;
    tick();
    checkpoint();
    observer?.disconnect();
    page = next;
    currentMain = main;
    lastInteraction = lastTick = performance.now();
    if (choice !== 'allowed' || !page) return;
    record('PAGE_VIEW');
    if (page === 'vouchers') record('VOUCHER_VIEW');
    if ('IntersectionObserver' in window) {
      observer = new IntersectionObserver(entries => {
        if (document.visibilityState !== 'visible') return;
        entries.filter(entry => entry.isIntersecting).forEach(entry => {
          observer.unobserve(entry.target);
          const dishId = Number(entry.target.dataset.id);
          if (dishId > 0) record('DISH_VIEW', { dishId });
        });
      }, { threshold: .55 });
      document.querySelectorAll('.menu-card[data-id], [data-preorder-product][data-id]').forEach(card => observer.observe(card));
    }
  }

  function renderChoice() {
    if (!pageKey() || choice === 'disabled') { banner?.remove(); banner = null; return; }
    if (!banner) {
      banner = document.createElement('aside');
      banner.className = 'tracking-choice';
      banner.innerHTML = `
        <button type="button" class="tracking-toggle" aria-controls="tracking-panel" aria-expanded="false">Quyền riêng tư</button>
        <section id="tracking-panel" class="tracking-panel" aria-labelledby="tracking-title" hidden>
          <strong id="tracking-title">Phân tích trải nghiệm</strong>
          <p>Nếu đồng ý, T3L đo lượt xem và thao tác để cải thiện website. Dữ liệu lưu tối đa 90 ngày. Tin nhắn và nội dung cá nhân không được ghi nhận.</p>
          <div class="tracking-actions">
            <button type="button" data-tracking-choice="allow">Đồng ý</button>
            <button type="button" data-tracking-choice="deny">Từ chối</button>
          </div>
          <p class="tracking-status" role="status"></p>
        </section>`;
      document.body.append(banner);
      panel = banner.querySelector('#tracking-panel');
      const toggle = banner.querySelector('.tracking-toggle');
      const expand = open => {
        panel.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
      };
      toggle.addEventListener('click', () => expand(panel.hidden));
      banner.addEventListener('click', async event => {
        const control = event.target.closest('[data-tracking-choice]');
        if (!control || !csrf) return;
        const allowed = control.dataset.trackingChoice === 'allow';
        const buttons = panel.querySelectorAll('button');
        buttons.forEach(button => { button.disabled = true; });
        const status = panel.querySelector('.tracking-status');
        try {
          const response = await post('preferences', { allowed });
          if (!response.ok) throw Error();
          choice = allowed ? 'allowed' : 'denied';
          queue.length = 0;
          nextSend = 0;
          switchPage(true);
          expand(false);
          toggle.focus({ preventScroll: true });
        } catch {
          status.textContent = 'Chưa lưu được lựa chọn. Bạn có thể thử lại.';
        } finally {
          buttons.forEach(button => { button.disabled = false; });
        }
      });
      expand(choice === 'unset');
    }
    panel.querySelector('.tracking-status').textContent = choice === 'allowed'
      ? 'Phân tích đang bật. Bạn có thể tắt bất cứ lúc nào.' : choice === 'denied' ? 'Phân tích đang tắt.' : '';
  }

  async function readPreference(refresh = false) {
    if (!pageKey() || (preferences && !refresh)) return preferences;
    preferences = fetch(endpoint + 'preferences', { credentials: 'same-origin', cache: 'no-store' })
      .then(response => { if (!response.ok) throw Error(); return response.json(); })
      .then(state => {
        const changed = state.choice !== choice;
        choice = state.choice;
        csrf = state.token ? state : null;
        renderChoice();
        switchPage(changed);
      }).catch(() => { preferences = null; });
    return preferences;
  }

  const normalize = text => text.toLocaleLowerCase('vi').normalize('NFD').replace(/\p{M}/gu, '').replace(/đ/g,'d').trim().replace(/ +/g,' ');
  let searchTimer;
  let lastSearch;
  document.addEventListener('input', event => {
    if (choice !== 'allowed' || !event.target.matches('#menu-search, [data-preorder-search]')) return;
    clearTimeout(searchTimer);
    const input = event.target;
    searchTimer = setTimeout(() => {
      if (!input.isConnected) return;
      const term = normalize(input.value);
      if (term.length < 2 || term.length > 40 || !/^[a-z ]+$/.test(term) || term === lastSearch) return;
      const catalog = input.closest('[data-preorder-picker]') || currentMain;
      if (![...catalog.querySelectorAll('[data-name]')].some(card => normalize(card.dataset.name).includes(term))) return;
      lastSearch = term;
      record('SEARCH', { term });
    }, 650);
  });
  document.addEventListener('click', event => {
    if (choice !== 'allowed') return;
    const button = event.target.closest('button');
    if (!button) return;
    if (button.matches('.t3l-chat-launcher[aria-expanded="true"]')) record('CHAT_OPEN');
    if (button.matches('.menu-categories [data-category]') && button.dataset.category) {
      record('CATEGORY', { term: button.textContent.trim() });
    }
    if (button.matches('[data-detail]')) {
      const dishId = Number(button.closest('[data-id]')?.dataset.id);
      if (dishId > 0) record('DISH_CLICK', { dishId });
    }
  });
  document.addEventListener('change', event => {
    if (event.target.matches('[data-preorder-category]') && event.target.value) record('CATEGORY', { term: event.target.value });
  });
  document.addEventListener('t3l:voucher-applied', () => record('VOUCHER_QUOTE'));
  document.addEventListener('t3l:cart', event => record(event.detail.type, {
    dishId: Number(event.detail.id), quantity: event.detail.quantity
  }));

  function onPage() {
    lastSearch = null;
    switchPage();
    if (csrf) renderChoice();
    else void readPreference();
  }

  function interaction() {
    if (choice !== 'allowed') return;
    tick();
    lastInteraction = performance.now();
  }

  for (const type of ['pointerdown', 'keydown', 'scroll']) {
    document.addEventListener(type, interaction, { passive: true, capture: true });
  }
  window.addEventListener('focus', () => { lastTick = lastInteraction = performance.now(); });
  window.addEventListener('blur', () => { checkpoint(); lastTick = performance.now(); });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') { checkpoint(); void flush(true); }
    lastTick = performance.now();
  });
  window.addEventListener('pagehide', () => { tick(); checkpoint(); void flush(true); });
  window.addEventListener('pageshow', event => { if (event.persisted) switchPage(true); });
  document.addEventListener('t3l:page', onPage);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', onPage, { once: true });
  else onPage();
  window.setInterval(() => { tick(); checkpoint(); void flush(); }, 15000);
})();
