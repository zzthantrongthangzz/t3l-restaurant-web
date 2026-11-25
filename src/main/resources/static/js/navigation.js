(() => {
  const frame = 'main[data-navigation]';
  const shellSelector = '[data-nav-shell]';
  const region = {
    public: /^(?:\/|\/(?:menu|about|contact|faq|reservations\/(?:new|lookup)|account(?:\/.*)?))$/,
    staff: /^\/staff(?:\/(?!login(?:\/|$))[^.]+)?$/,
    admin: /^\/admin(?:\/[^.]+)?$/
  };
  const initializers = new Map();
  const pageScripts = new Map();
  const entries = new Map();
  const persistedControls = '[data-navigation-persist]';
  let page = new AbortController();
  let pending;
  let submitting = false;
  let shownUrl = new URL(location.href);
  let entry = history.state?.t3lPage ?? 0;
  let nextEntry = entry;
  let busyFrame;
  let failure;
  let activeScripts = [];

  function mount(scripts, state = {}) {
    activeScripts = scripts;
    for (const script of scripts) {
      initializers.get(script)?.({ signal: page.signal, state });
    }
    document.dispatchEvent(new Event('t3l:page'));
  }

  window.t3lPage = {
    register(initializer) {
      initializers.set(document.currentScript.src, initializer);
    },
    recordInline(url) {
      remember();
      entry = ++nextEntry;
      history.pushState({ ...history.state, t3lPage: entry }, '', url);
      shownUrl = url;
    }
  };

  const scriptsOf = (doc, url = location.href) => [...doc.querySelectorAll('script[data-page-script][src]')]
    .map(script => new URL(script.getAttribute('src'), url).href);

  document.addEventListener('DOMContentLoaded', () => {
    mount(scriptsOf(document));
    if (!document.querySelector(frame)) return;
    history.replaceState({ ...history.state, t3lPage: entry }, '');
    history.scrollRestoration = 'manual';
  }, { once: true });

  function canVisit(url) {
    const shell = document.querySelector(shellSelector);
    return !!(shell && document.querySelector(frame) && url.origin === location.origin
      && region[shell.dataset.navShell]?.test(url.pathname));
  }

  function samePage(a, b) {
    return a.pathname === b.pathname && a.search === b.search;
  }

  function remember() {
    const state = { x: scrollX, y: scrollY, values: {} };
    for (const control of document.querySelectorAll(persistedControls)) {
      if (control.id) state.values[control.id] = control.value;
    }
    document.dispatchEvent(new CustomEvent('t3l:page-save', { detail: state }));
    entries.set(entry, state);
    if (entries.size > 40) entries.delete(entries.keys().next().value);
  }

  function restore(state) {
    for (const control of document.querySelectorAll(persistedControls)) {
      if (Object.hasOwn(state.values ?? {}, control.id)) control.value = state.values[control.id];
    }
  }

  function prepareScript(url) {
    if (initializers.has(url)) return Promise.resolve();
    if (!pageScripts.has(url)) {
      const script = document.createElement('script');
      script.src = url;
      const loaded = new Promise((resolve, reject) => {
        script.onload = resolve;
        script.onerror = () => {
          pageScripts.delete(url);
          script.remove();
          reject(new Error('Page script unavailable'));
        };
      });
      pageScripts.set(url, loaded);
      document.head.append(script);
    }
    return pageScripts.get(url);
  }

  async function prepareStyles(doc, url, staged) {
    const old = [...document.head.querySelectorAll('link[rel=stylesheet]:not([data-navigation-staged])')];
    const sources = [...doc.head.querySelectorAll('link[rel=stylesheet]')];
    const urls = sources.map(source => new URL(source.getAttribute('href'), url).href);
    const existing = new Map(old.map(link => [link.href, link]));
    const retained = urls.filter(href => existing.has(href));
    const previousOrder = old.map(link => link.href).filter(href => retained.includes(href));
    // Keep loaded stylesheet nodes in place. Moving them can temporarily detach their styles.
    if (retained.some((href, index) => href !== previousOrder[index])) existing.clear();

    const loads = [];
    sources.forEach((source, index) => {
      const href = urls[index];
      if (existing.has(href)) return;
      const link = document.adoptNode(source.cloneNode());
      link.href = href;
      link.media = 'not all';
      link.dataset.navigationStaged = '';
      staged.push(link);
      loads.push(new Promise((resolve, reject) => {
        link.onload = resolve;
        link.onerror = reject;
      }));
      const following = urls.slice(index + 1).find(next => existing.has(next));
      document.head.insertBefore(link, existing.get(following) || null);
    });
    await Promise.all(loads);
    return () => {
      old.filter(link => !existing.has(link.href) || !urls.includes(link.href)).forEach(link => link.remove());
      for (const link of staged) {
        link.removeAttribute('media');
        delete link.dataset.navigationStaged;
      }
    };
  }

  function identity(shell) {
    return [...shell.querySelectorAll('a, summary, .sidebar-account-label')]
      .filter(node => !node.matches('.skip-link'))
      .map(node => node.matches('[data-header-login]') ? 'login' : node.textContent.trim()).join('|');
  }

  function updateShell(shell, incoming) {
    const links = [...shell.querySelectorAll('a')];
    [...incoming.querySelectorAll('a')].forEach((link, index) => {
      for (const attr of ['href', 'aria-current']) {
        if (link.hasAttribute(attr)) links[index].setAttribute(attr, link.getAttribute(attr));
        else links[index].removeAttribute(attr);
      }
    });
    const tokens = [...incoming.querySelectorAll('input[name="_csrf"]')];
    shell.querySelectorAll('input[name="_csrf"]').forEach((token, index) => {
      if (tokens[index]) token.value = tokens[index].value;
    });
    const labels = [...incoming.querySelectorAll('.sidebar-account-label')];
    shell.querySelectorAll('.sidebar-account-label').forEach((label, index) => {
      if (labels[index] && !label.isEqualNode(labels[index])) label.replaceWith(document.adoptNode(labels[index]));
    });
    const initial = shell.querySelector('.customer-avatar > span');
    const incomingInitial = incoming.querySelector('.customer-avatar > span');
    if (initial && incomingInitial && initial.textContent !== incomingInitial.textContent) {
      initial.textContent = incomingInitial.textContent;
    }
  }

  function replacePart(doc, selector, anchor) {
    const current = document.querySelector(selector);
    const next = doc.querySelector(selector);
    if (!next) current?.remove();
    else if (current && current.isEqualNode(next)) return;
    else if (current) current.replaceWith(document.adoptNode(next));
    else anchor.before(document.adoptNode(next));
  }

  function updateHead(doc, referrer) {
    document.title = doc.title;
    for (const name of ['description', 'robots', 'referrer', 'theme-color']) {
      const selector = 'meta[name="' + name + '"]';
      const existing = document.head.querySelector(selector);
      const incoming = doc.head.querySelector(selector);
      if (name === 'referrer' && referrer === 'no-referrer') {
        if (incoming) incoming.content = referrer;
        else {
          const meta = existing || document.createElement('meta');
          meta.name = name;
          meta.content = referrer;
          if (!existing) document.head.append(meta);
          continue;
        }
      }
      if (existing && incoming) existing.content = incoming.content;
      else if (existing) existing.remove();
      else if (incoming) document.head.append(document.adoptNode(incoming));
    }
  }

  function install(doc, shell, styles, referrer) {
    const header = doc.querySelector(shellSelector);
    updateShell(shell, header);
    updateHead(doc, referrer);
    styles();
    document.body.className = doc.body.className;
    replacePart(doc, 'body > .skip-link', shell);
    const anchor = document.querySelector('.t3l-chat') || document.body.querySelector('script');
    const footer = doc.querySelector('body > .home-footer');
    if (anchor) replacePart(doc, 'body > .home-footer', anchor);
    else if (footer && !document.querySelector('body > .home-footer')) document.body.append(document.adoptNode(footer));
    document.querySelectorAll('body > dialog').forEach(dialog => dialog.remove());
    for (const dialog of doc.querySelectorAll('body > dialog')) document.body.append(document.adoptNode(dialog));
  }

  function setBusy(value) {
    if (value) {
      busyFrame = document.querySelector(frame);
      busyFrame?.setAttribute('aria-busy', 'true');
      failure?.remove();
    } else {
      busyFrame?.removeAttribute('aria-busy');
      busyFrame = null;
    }
  }

  function showFailure() {
    failure = document.createElement('p');
    failure.className = 'navigation-error';
    failure.setAttribute('role', 'alert');
    failure.textContent = 'Không thể xác nhận kết quả. Hãy kiểm tra lại trạng thái trên trang trước khi gửi lại.';
    document.querySelector(frame)?.prepend(failure);
  }

  async function visit(url, { mode = 'push', keepScroll = false, body } = {}) {
    if (submitting) return;
    pending?.abort();
    const request = new AbortController();
    pending = request;
    submitting = !!body;
    setBusy(true);
    const staged = [];
    const targetEntry = history.state?.t3lPage;
    let response;
    try {
      const options = {
        headers: { Accept: 'text/html' }, credentials: 'same-origin', cache: 'no-store',
        referrerPolicy: 'no-referrer', signal: request.signal
      };
      if (!body && url.pathname === shownUrl.pathname && document.querySelector('[data-navigation-fragment]')) {
        options.headers['X-T3L-Fragment'] = 'main';
      }
      if (body) Object.assign(options, { method: 'POST', body: new URLSearchParams(body) });
      response = await fetch(url, options);
      const destination = new URL(response.url || url);
      if (!response.redirected) destination.hash = url.hash;
      if (!response.headers.get('content-type')?.includes('text/html') || !canVisit(destination)) throw new Error('Document navigation required');
      // A validation response rendered directly from POST keeps the current GET URL.
      if (body && !response.redirected) destination.href = shownUrl.href;
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      const shell = document.querySelector(shellSelector);
      const next = doc.querySelector(shellSelector);
      const incomingMain = doc.querySelector(frame);
      const fragment = response.headers.get('X-T3L-Fragment') === 'main';
      const profileEdit = body && /\/account$/.test(url.pathname) && response.ok && destination.pathname === url.pathname
        && shell.querySelector('.customer-menu') && next?.querySelector('.customer-menu');
      if (!incomingMain || (fragment ? destination.pathname !== shownUrl.pathname
          : !next || next.dataset.navShell !== shell.dataset.navShell
            || (!profileEdit && identity(next) !== identity(shell))
            || next.querySelectorAll('a').length !== shell.querySelectorAll('a').length
            || !!doc.querySelector('.t3l-chat') !== !!document.querySelector('.t3l-chat'))) {
        throw new Error('Document layout changed');
      }
      const scripts = fragment ? activeScripts : scriptsOf(doc, destination);
      let styles;
      if (!fragment) {
        [styles] = await Promise.all([prepareStyles(doc, destination, staged), Promise.all(scripts.map(prepareScript))]);
      }
      request.signal.throwIfAborted();

      remember();
      const leaving = entries.get(entry);
      const focused = document.activeElement;
      if (body && response.ok && response.redirected) entries.delete(entry);
      const state = mode === 'pop' ? entries.get(targetEntry) ?? {} : keepScroll
        ? body ? { board: leaving.board } : leaving : {};
      if (mode !== 'pop' && !samePage(destination, shownUrl)) {
        entry = ++nextEntry;
        history.pushState({ ...history.state, t3lPage: entry }, '', destination);
      } else if (mode === 'pop') {
        entry = targetEntry ?? ++nextEntry;
        nextEntry = Math.max(nextEntry, entry);
        history.replaceState({ ...history.state, t3lPage: entry }, '', destination);
      }
      shownUrl = destination;
      page.abort();
      page = new AbortController();
      if (!fragment) install(doc, shell, styles, response.headers.get('Referrer-Policy'));
      document.querySelector(frame).replaceWith(document.adoptNode(incomingMain));
      restore(state);
      mount(scripts, state);
      const main = document.querySelector(frame);
      if (!main.contains(document.activeElement) && !document.querySelector('dialog[open]')) {
        const control = keepScroll && focused.matches('input:not([type=hidden]), select, textarea')
          ? (focused.id ? main.querySelector('#' + CSS.escape(focused.id))
            : focused.name ? main.querySelector('[name="' + CSS.escape(focused.name) + '"]') : null)
          : null;
        if (control) control.focus({ preventScroll: true });
        else if (mode !== 'pop') {
          main.tabIndex = -1;
          main.focus({ preventScroll: true });
        }
      }
      const hash = destination.hash.length > 1 ? destination.hash.slice(1) : '';
      const anchor = hash && document.getElementById(hash);
      if (mode === 'pop') window.scrollTo({ left: state.x ?? 0, top: state.y ?? 0, behavior: 'instant' });
      else if (anchor) anchor.scrollIntoView({ behavior: 'instant' });
      else window.scrollTo({ left: 0, top: keepScroll ? leaving?.y ?? 0 : 0, behavior: 'instant' });
    } catch (error) {
      staged.forEach(link => link.remove());
      if (request.signal.aborted) return;
      if (!body || response?.redirected && new URL(response.url).origin === location.origin) {
        const target = response?.redirected ? response.url : url.href;
        if (mode === 'pop') location.replace(target);
        else location.assign(target);
      } else showFailure();
    } finally {
      if (pending === request) {
        pending = null;
        submitting = false;
        setBusy(false);
      }
    }
  }

  document.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest('a[href]');
    if (!link || link.target && link.target !== '_self' || link.hasAttribute('download') || link.closest('[data-native-navigation]')) return;
    const url = new URL(link.href);
    if (!canVisit(url)) return;
    if (samePage(url, new URL(location.href))) {
      if (url.href === location.href && link.hasAttribute('aria-current')) event.preventDefault();
      return;
    }
    event.preventDefault();
    visit(url, { keepScroll: url.pathname === shownUrl.pathname });
  });

  document.addEventListener('submit', event => {
    if (event.defaultPrevented) return;
    const form = event.target;
    const submitter = event.submitter;
    const method = (submitter?.getAttribute('formmethod') || form.method).toLowerCase();
    const encoding = submitter?.getAttribute('formenctype') || form.enctype;
    if (!['get', 'post'].includes(method)
        || method === 'post' && (!form.matches('[data-partial-submit]') || encoding !== 'application/x-www-form-urlencoded')) return;
    const target = submitter?.getAttribute('formtarget') || form.target;
    if (target && target !== '_self' || form.closest('[data-native-navigation]')) return;
    const action = new URL(submitter?.getAttribute('formaction') ?? form.action, location.href);
    if (!canVisit(action)) return;
    const fields = new FormData(form, submitter);
    const keepScroll = action.pathname === shownUrl.pathname || method === 'post';
    event.preventDefault();
    if (method === 'get') {
      action.search = new URLSearchParams(fields).toString();
      visit(action, { keepScroll });
    } else {
      visit(action, { body: fields, keepScroll });
    }
  });

  window.addEventListener('popstate', () => {
    const url = new URL(location.href);
    const targetEntry = history.state?.t3lPage;
    if (samePage(url, shownUrl) && (targetEntry == null || targetEntry === entry)) return;
    if (url.pathname === shownUrl.pathname && document.querySelector('main[data-table-board]')) {
      shownUrl = url;
      entry = targetEntry ?? entry;
      window.dispatchEvent(new Event('t3l:inline-pop'));
      return;
    }
    if (submitting || !canVisit(url)) {
      location.reload();
      return;
    }
    visit(url, { mode: 'pop' });
  });
})();
