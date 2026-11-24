(() => {
  'use strict';

  const widget = document.querySelector('.t3l-chat');
  if (!widget) return;
  const launcher = widget.querySelector('.t3l-chat-launcher');
  const panel = widget.querySelector('.t3l-chat-panel');
  const log = widget.querySelector('.t3l-chat-stream');
  const form = widget.querySelector('.t3l-chat-form');
  const input = form.querySelector('textarea');
  const status = widget.querySelector('.t3l-chat-notice');
  const suggestions = widget.querySelector('.t3l-chat-suggestions');
  const welcome = log.firstElementChild.cloneNode(true);
  const linkLabels = new Map([
    ['/menu', 'Xem thực đơn'], ['/faq', 'Xem câu hỏi thường gặp'], ['/contact', 'Liên hệ nhà hàng'],
    ['/about', 'Về T3L'], ['/reservations/new', 'Đến trang đặt bàn'], ['/login', 'Đăng nhập'],
    ['/account/reservations', 'Xem lịch đặt bàn'],
  ]);
  const appPath = new URL(widget.dataset.chatSession, location.href).pathname.replace(/\/chat\/session$/, '');
  const money = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' });
  let session;
  let sessionRequest;
  let pending = false;

  function notice(text) {
    text ||= session && !session.aiConfigured ? 'Đang trả lời bằng dữ liệu nhà hàng.' : '';
    status.textContent = text;
    status.hidden = !text;
  }

  function focusLog() {
    log.scrollTop = log.scrollHeight;
  }

  function addMessage(text, user = false) {
    const bubble = document.createElement('p');
    bubble.className = 't3l-chat-message' + (user ? ' is-user' : '');
    bubble.textContent = text;
    log.append(bubble);
    focusLog();
    return bubble;
  }

  function render(reply) {
    addMessage(reply.text);
    if (reply.dishes?.length) {
      const list = document.createElement('ul');
      list.className = 't3l-chat-cards';
      for (const dish of reply.dishes) {
        const item = document.createElement('li');
        const name = document.createElement('strong');
        name.textContent = dish.name;
        const price = document.createElement('span');
        price.textContent = money.format(dish.price) + '/phần';
        item.append(name, price);
        if (dish.quantity > 1) {
          const total = document.createElement('small');
          total.textContent = dish.quantity + ' phần · ' + money.format(dish.quantity * dish.price);
          item.append(total);
        }
        list.append(item);
      }
      log.append(list);
    }
    const actions = document.createElement('div');
    actions.className = 't3l-chat-actions';
    for (const link of reply.links ?? []) {
      if (!linkLabels.has(link)) continue;
      const anchor = document.createElement('a');
      anchor.href = appPath + link;
      anchor.textContent = linkLabels.get(link);
      actions.append(anchor);
    }
    if (reply.draftToken) actions.append(draftButton(reply.draftToken));
    if (actions.childElementCount) log.append(actions);
    focusLog();
  }

  function draftButton(token) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 't3l-chat-draft';
    button.textContent = 'Mở biểu mẫu đã điền';
    button.addEventListener('click', () => openDraft(button, token));
    return button;
  }

  async function read(url, options) {
    const response = await fetch(url, { ...options, credentials: 'same-origin', cache: 'no-store',
      signal: AbortSignal.timeout(60000) }).catch(() => {
      throw new Error('Chưa kết nối được với trợ lý. Bạn vui lòng thử lại.');
    });
    const data = await response.json().catch(() => ({}));
    if (response.status === 403) {
      session = undefined;
      throw new Error('Phiên trò chuyện đã thay đổi. Vui lòng thử lại.');
    }
    if (!response.ok) throw new Error(data.error || 'Trợ lý đang gián đoạn. Bạn vui lòng thử lại.');
    return data;
  }

  async function loadSession() {
    if (session) return session;
    sessionRequest ??= read(widget.dataset.chatSession).then(snapshot => {
      if (!snapshot.conversationAvailable) throw new Error('Chưa kết nối được lịch sử. Bạn vui lòng thử lại.');
      log.replaceChildren(welcome.cloneNode(true));
      for (const entry of snapshot.history) {
        addMessage(entry.user, true);
        addMessage(entry.reply);
      }
      if (snapshot.draftAvailable) {
        const actions = document.createElement('div');
        actions.className = 't3l-chat-actions';
        actions.append(draftButton(snapshot.draftToken));
        log.append(actions);
        focusLog();
      }
      session = snapshot;
      if (!pending) notice('');
      return session;
    }).finally(() => { sessionRequest = undefined; });
    return sessionRequest;
  }

  async function write(url, method, body, extraHeaders) {
    const state = await loadSession();
    const headers = { ...extraHeaders, [state.csrfHeader]: state.csrfToken };
    if (body) headers['Content-Type'] = 'application/json';
    return read(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  }

  function show(open) {
    panel.hidden = !open;
    launcher.setAttribute('aria-expanded', String(open));
    if (open) {
      const target = matchMedia('(pointer: coarse)').matches ? log : input;
      target.focus({ preventScroll: true });
      loadSession().catch(error => notice(error.message));
    } else {
      launcher.focus({ preventScroll: true });
    }
  }

  async function send(message) {
    if (pending || !message.trim()) return;
    pending = true;
    const submit = form.querySelector('[type=submit]');
    submit.disabled = true;
    notice('Trợ lý đang trả lời…');
    let bubble;
    try {
      await loadSession();
      bubble = addMessage(message, true);
      const reply = await write(widget.dataset.chatMessages, 'POST', { text: message });
      log.querySelectorAll('.t3l-chat-draft').forEach(button => button.remove());
      input.value = '';
      render(reply);
      notice('');
    } catch (error) {
      bubble?.remove();
      input.value = message;
      notice(error.message);
    } finally {
      pending = false;
      submit.disabled = false;
    }
  }

  async function openDraft(button, token) {
    if (pending) return;
    pending = true;
    button.disabled = true;
    notice('Đang kiểm tra lại chỗ trống…');
    try {
      const reply = await write(widget.dataset.chatDraft, 'POST', undefined, { 'X-Chat-Draft': token });
      const url = new URL(reply.url, location.href);
      if (url.origin !== location.origin || url.pathname !== appPath + '/reservations/new') {
        throw new Error('Không mở được biểu mẫu đặt bàn.');
      }
      location.assign(url.href);
    } catch (error) {
      button.disabled = false;
      notice(error.message);
    } finally {
      pending = false;
    }
  }

  launcher.addEventListener('click', () => show(panel.hidden));
  widget.querySelector('.t3l-chat-close').addEventListener('click', () => show(false));
  widget.addEventListener('keydown', event => {
    if (event.key === 'Escape' && !panel.hidden) {
      event.stopPropagation();
      show(false);
    }
  });
  form.addEventListener('submit', event => {
    event.preventDefault();
    send(input.value.trim());
  });
  input.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      form.requestSubmit();
    }
  });
  suggestions.addEventListener('click', event => {
    const button = event.target.closest('[data-prompt]');
    if (button) send(button.dataset.prompt);
  });
  widget.querySelector('.t3l-chat-clear').addEventListener('click', async () => {
    if (pending || !window.confirm('Xóa lịch sử cuộc trò chuyện này?')) return;
    pending = true;
    try {
      await write(widget.dataset.chatSession, 'DELETE');
      log.replaceChildren(welcome.cloneNode(true));
      notice('');
    } catch (error) {
      notice(error.message);
    } finally {
      pending = false;
    }
  });
})();
