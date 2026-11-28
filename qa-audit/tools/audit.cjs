const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { setTimeout: wait } = require('node:timers/promises');
const screens = require('./screens.cjs');

const root = path.resolve(__dirname, '..');
const runtime = path.resolve(root, '../target/t3l-qa-audit-runtime');
process.env.PLAYWRIGHT_BROWSERS_PATH ||= path.join(runtime, 'browsers');
const { chromium } = require(path.join(runtime, 'node_modules/playwright'));
const mysql = require(path.join(runtime, 'node_modules/mysql2/promise'));
const base = 'http://127.0.0.1:18085';
const loginPassword = fs.readFileSync(path.join(runtime, 'qa-password.txt'), 'utf8').trim();
const resetPassword = loginPassword + 'R';
const otpPath = path.join(runtime, 'qa-otp.txt');
let phone = '093' + Date.now().toString().slice(-7);
let email = `audit-${phone}@qa.invalid`;
const resume = process.env.QA_RESUME === '1';
const onlyScreens = process.env.QA_ONLY_SCREENS?.split(',');
const displayName = 'Audit Buyer';
const reportPath = path.join(root, 'playwright-report', 'results.jsonl');
const profiles = [
  [1920, 1080], [1366, 768], [768, 1024], [390, 844]
].flatMap(([width, height]) => ['light', 'dark'].map(theme => ({ width, height, theme })));
const fixture = {};
let personal = [displayName, email, phone, loginPassword, resetPassword];

function safe(value) {
  let text = String(value ?? '');
  for (const item of personal) if (item && item.length > 3) text = text.split(item).join('[redacted]');
  return text.replace(/[\w.+-]+@[\w.-]+\.[\w.-]+/g, '[email]')
    .replace(/(?:\+?84|0)[\d .()-]{8,14}\d/g, '[phone]')
    .replace(/T3L-[A-Z0-9]{8,}|(?:TCOC|THD)\d{5,}/g, '[code]');
}

function record(item) {
  fs.appendFileSync(reportPath, JSON.stringify(item) + '\n');
  process.stdout.write(`${item.status} ${item.role} ${item.id}${item.profile ? ` ${item.profile}` : ''}\n`);
}

async function sql(db, query, values = []) {
  return (await db.execute(query, values))[0];
}

async function context(browser, profile = profiles[0], state) {
  const context = await browser.newContext({ viewport: { width: profile.width, height: profile.height }, storageState: state });
  await context.addInitScript(theme => {
    if (location.origin === 'http://127.0.0.1:18085') localStorage.setItem('t3l-theme', theme);
  }, profile.theme);
  await context.route('https://vietqr.app/**', route => route.fulfill({
    contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#eee"/><text x="28" y="135" font-size="18">QA QR PLACEHOLDER</text></svg>'
  }));
  return context;
}

function diagnostics(page) {
  const errors = [];
  page.on('pageerror', error => errors.push({ type: 'javascript', text: safe(error.message) }));
  page.on('console', line => {
    if (line.type() === 'error' && !line.text().startsWith('Failed to load resource')) {
      errors.push({ type: 'console', text: safe(line.text()) });
    }
  });
  page.on('response', response => {
    const url = new URL(response.url());
    if (response.status() >= 400) {
      errors.push({ type: 'http', status: response.status(), route: safe(url.pathname), resource: response.request().resourceType(), host: url.hostname });
    }
  });
  page.on('requestfailed', request => {
    const failure = request.failure()?.errorText;
    if (failure === 'net::ERR_ABORTED') return;
    const url = new URL(request.url());
    errors.push({ type: 'network', route: safe(url.pathname), resource: request.resourceType(), host: url.hostname, text: safe(failure) });
  });
  return errors;
}

async function open(page, route) {
  return page.goto(base + route, { waitUntil: 'domcontentloaded', timeout: 14000 });
}

async function screenshot(page, folder, id) {
  const relative = `screenshots/${folder}/${id}.jpg`;
  const privateFields = 'input:not([type="hidden"]):is([type="tel"],[type="password"],[type="email"],[name="username"],[name="fullName"],[name="guestName"],[name="code"],[name="accountNumber"],[autocomplete="one-time-code"])';
  await page.evaluate(({names,fields}) => {
    const patterns = [/[\w.+-]+@[\w.-]+\.[\w.-]+/g, /(?:\+?84|0)[\d .()-]{8,14}\d/g, /T3L-[A-Z0-9]{8,}|(?:TCOC|THD)\d{5,}/g];
    const text = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    window.__auditRedactions = [];
    for (let node = text.nextNode(); node; node = text.nextNode()) {
      if (!node.parentElement?.closest('script, style')) {
        let next = node.data;
        for (const name of names) if (name && name.length > 3) next = next.split(name).join('[ẩn]');
        for (const pattern of patterns) next = next.replace(pattern, '[ẩn]');
        if (next !== node.data) { window.__auditRedactions.push([node, node.data]); node.data = next; }
      }
    }
    for (const field of document.querySelectorAll(fields)) {
      if (field.value) field.setAttribute('data-audit-mask','');
    }
  }, {names:personal,fields:privateFields});
  try {
    await page.screenshot({ path: path.join(root, relative), fullPage: true, type: 'jpeg', quality: 64,
      animations: 'disabled', timeout: 11000, maskColor: '#4e4e4e',
      mask: [page.locator('[data-audit-mask], [data-payment-code], [data-transfer-reference], img[src*="vietqr"], [data-avatar]')] });
    return relative;
  } finally {
    await page.evaluate(() => {
      for (const [node, original] of window.__auditRedactions ?? []) node.data = original;
      delete window.__auditRedactions;
      for (const field of document.querySelectorAll('[data-audit-mask]')) field.removeAttribute('data-audit-mask');
    }).catch(() => {});
  }
}

async function sample(page) {
  return page.evaluate(() => {
    const width = document.documentElement.clientWidth;
    const computed = getComputedStyle(document.body);
    const overflowing = Array.from(document.querySelectorAll('main *')).filter(element => {
      const r = element.getBoundingClientRect();
      const css = getComputedStyle(element);
      return r.width > 0 && r.right > width + 3 && css.position !== 'fixed' && css.visibility !== 'hidden' &&
        !element.closest('svg, [hidden], [aria-hidden="true"], dialog:not([open])');
    }).slice(0, 8).map(el => `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}${el.classList.length ? '.' + [...el.classList].slice(0, 2).join('.') : ''}`);
    const forms = Array.from(document.querySelectorAll('main form')).map(form => ({
      method: form.getAttribute('method') || 'get', route: new URL(form.getAttribute('action') || location.href, location.href).pathname
    }));
    return { width, scrollWidth: document.documentElement.scrollWidth, height: document.documentElement.scrollHeight,
      theme: document.documentElement.dataset.theme, bodyFont: computed.fontFamily, bodyColor: computed.color, overflowing,
      headings: document.querySelectorAll('main h1').length, forms, buttons: document.querySelectorAll('main button').length,
      links: document.querySelectorAll('main a[href]').length, dialogs: document.querySelectorAll('main dialog').length };
  });
}

async function browseProfile(browser, role, profile, state) {
  const tag = `${profile.width}x${profile.height}-${profile.theme}`;
  if (!state && role !== 'guest') {
    for (const [id, route] of screens[role]) record({ role, profile: tag, id, route, status: 'BLOCKED', reason: 'QA login did not complete' });
    return;
  }
  const ctx = await context(browser, profile, state);
  const page = await ctx.newPage();
  const events = diagnostics(page);
  page.setDefaultTimeout(7000);
  try {
    for (const [id, pattern, expected = 200, redirect] of screens[role]) {
      if (onlyScreens && !onlyScreens.includes(id)) continue;
      let actual = pattern.replace(/\{(\w+)\}/g, (_, key) => fixture[key] ?? `{${key}}`);
      const entry = { role, profile: tag, id, route: pattern, expected, status: 'PASS' };
      if (actual.includes('{')) { record({ ...entry, status: 'BLOCKED', reason: 'QA fixture not available' }); continue; }
      events.length = 0;
      try {
        const response = await open(page, actual);
        entry.http = response?.status();
        if (actual.includes('#')) {
          await page.locator('#' + actual.split('#')[1]).waitFor({ state: 'visible' });
          entry.http ??= (await ctx.request.get(base + actual.split('#')[0])).status();
        }
        const finalPath = new URL(page.url()).pathname;
        entry.finalPath = safe(finalPath);
        await page.locator('main, h1').first().waitFor({ state: 'attached', timeout: 5500 });
        entry.metrics = await sample(page);
        entry.metrics.forms = entry.metrics.forms.map(form => ({...form, route: safe(form.route)}));
        await page.waitForLoadState('load', { timeout: 1700 }).catch(() => {});
        entry.events = events.filter(item => !(item.type === 'http' && item.resource === 'document' && item.status === expected));
        if (entry.http !== expected || finalPath !== (redirect ?? actual.split(/[?#]/)[0]) ||
            entry.metrics.theme !== profile.theme || entry.metrics.scrollWidth > entry.metrics.width + 2 ||
            entry.events.some(item => item.type === 'javascript' || item.type === 'console' || (item.type === 'http' && item.host === '127.0.0.1'))) entry.status = 'FAIL';
      } catch (error) {
        entry.status = 'FAIL';
        entry.reason = safe(error.message).split('\n').slice(0, 2).join(' ');
        entry.events = [...events];
      }
      try {
        const dir = entry.status === 'FAIL' ? 'errors' : profile.width < 800 ? 'mobile' : role;
        entry.screenshot = await screenshot(page, dir, `${role}-${id}-${tag}`);
      } catch (error) { entry.screenshotError = safe(error.message).split('\n')[0]; }
      record(entry);
    }
  } finally { await ctx.close(); }
}

async function step(role, id, route, page, operation) {
  const entry = { role, id, route, kind: 'interaction', status: 'PASS' };
  try { entry.detail = await operation(); }
  catch (error) { entry.status = 'FAIL'; entry.reason = safe(error.message).split('\n').slice(0, 3).join(' '); }
  try { if (page.url().startsWith(base + '/')) entry.screenshot = await screenshot(page, entry.status === 'FAIL' ? 'errors' : role, `flow-${role}-${id}`); }
  catch (error) { entry.screenshotError = safe(error.message).split('\n')[0]; }
  record(entry);
  return entry.status === 'PASS';
}

async function authenticateStaff(page, role) {
  await open(page, '/staff/login');
  await page.locator('input[name=username]').fill(`audit-${role}`);
  await page.locator('input[name=password]').fill(loginPassword);
  await page.locator('form button[type=submit]').click();
  await page.waitForURL(`**/${role}/dashboard`);
}

async function resetFlow(page, db) {
  const [buyer] = await sql(db, 'SELECT id FROM app_users WHERE phone=?', [phone]);
  assert.ok(buyer, 'Buyer must have registered through the browser');
  await sql(db, `INSERT INTO customer_profiles(user_id,google_subject,google_email)
    VALUES (?,?,?) ON DUPLICATE KEY UPDATE google_subject=VALUES(google_subject),google_email=VALUES(google_email)`,
    [buyer.id, `qa-audit-${phone}`, email]);
  fs.rmSync(otpPath, { force: true });
  await open(page, '/login/forgot-password');
  await page.locator('#reset-phone').fill(phone);
  await page.locator('button[type=submit]').filter({ hasText: 'Gửi mã xác minh' }).click();
  await page.locator('#reset-code').waitFor();
  assert.match(await page.locator('main').innerText(), /\*\*\*@\*\*\*/);
  await screenshot(page, 'guest', 'flow-guest-otp-requested');
  for (let attempt = 0; attempt < 30 && !fs.existsSync(otpPath); attempt++) await wait(250);
  assert.ok(fs.existsSync(otpPath), 'No OTP received by the local SMTP fixture');
  const code = fs.readFileSync(otpPath, 'utf8').trim();
  personal.push(code);
  await page.locator('#reset-code').fill(code);
  await page.locator('button[type=submit]').filter({ hasText: 'Xác minh mã' }).click();
  await page.locator('#reset-password').waitFor();
  assert.match(await page.locator('main').innerText(), /a\*\*\*@q\*\*\*\.invalid/);
  await screenshot(page, 'guest', 'flow-guest-password-reset-form');
  await page.locator('#reset-password').fill(resetPassword);
  await page.locator('#reset-confirmation').fill(resetPassword);
  await page.getByRole('button', { name: 'Lưu mật khẩu mới' }).click();
  await page.getByText(/Đã đổi mật khẩu thành công/).waitFor();
  return 'OTP captured in the local SMTP sink, masked recipient verified, success banner displayed.';
}

async function bootstrap(browser, db) {
  const states = {};
  let password = resume ? resetPassword : loginPassword;
  if (!resume) {
  const guest = await context(browser);
  const gp = await guest.newPage();
  await step('guest', 'live-8085-read-only', '/', gp, async () => {
    const result = await gp.goto('http://localhost:8085/', { waitUntil: 'domcontentloaded', timeout: 10000 });
    assert.equal(result.status(), 200);
    // Make sure the screenshot captured by step() remains on the isolated QA app.
    await open(gp, '/');
    return 'Existing 8085 responded HTTP 200. Further checks run on isolated 18085.';
  });
  const registered = await step('guest', 'register', '/register', gp, async () => {
    await open(gp, '/register');
    await gp.locator('#fullName').fill(displayName);
    await gp.locator('#phone').fill(phone);
    await gp.locator('#register-password').fill(loginPassword);
    await gp.locator('#register-confirm-password').fill(loginPassword);
    await gp.getByRole('button', { name: 'Tạo tài khoản' }).click();
    await gp.waitForURL(base + '/');
    const buyers = await sql(db, 'SELECT id FROM app_users WHERE phone=? AND role=?', [phone, 'BUYER']);
    assert.equal(buyers.length, 1);
    return 'Registered a synthetic account successfully.';
  });
  await guest.close();

  const recovery = await context(browser);
  if (registered && await step('guest', 'reset-password', '/login/forgot-password', await recovery.newPage(), () => resetFlow(recovery.pages()[0], db))) {
    password = resetPassword;
  }
  await recovery.close();
  }

  const buyer = await context(browser);
  const bp = await buyer.newPage();
  if (await step('buyer', 'login', '/login', bp, async () => {
    await open(bp, '/login');
    await bp.locator('#login-phone').fill(phone);
    await bp.locator('#login-password').fill(password);
    await bp.locator('form[action$="/login/password"] button[type=submit]').click();
    await bp.waitForURL(base + '/');
    assert.equal((await open(bp, '/account')).status(), 200);
    return 'Buyer role reached its account screen.';
  })) {
    states.buyer = await buyer.storageState();
    if (!resume) await step('buyer', 'address-form', '/account/addresses/new', bp, async () => {
      await open(bp, '/account/addresses/new');
      const form = bp.locator('main form[action$="/addresses/new"]');
      for (const key of ['recipientName', 'phone', 'streetAddress']) {
        const field = form.locator(`[name="${key}"]`);
        if (await field.count()) await field.fill({ recipientName: displayName, phone, streetAddress: 'QA Address Only' }[key]);
      }
      await form.locator('button[type=submit]').click();
      await bp.waitForURL('**/account/addresses');
      const edit = await bp.locator('main a[href]').evaluateAll(items => items.map(item => new URL(item.href).pathname).find(p => /\/addresses\/\d+$/.test(p)));
      assert.ok(edit);
      fixture.addressId = edit.split('/').pop();
      return 'Synthetic address created and edit route discovered.';
    });
  }
  await buyer.close();

  for (const role of ['staff', 'admin']) {
    const ctx = await context(browser);
    const page = await ctx.newPage();
    if (await step(role, 'login', '/staff/login', page, async () => {
      await authenticateStaff(page, role);
      return `${role} reached the expected dashboard.`;
    })) {
      states[role] = await ctx.storageState();
      if (role === 'admin') {
        await open(page, '/admin/faqs');
        const edit = await page.locator('main a[href]').evaluateAll(items => items.map(item => new URL(item.href).pathname).find(p => /\/faqs\/[^/]+\/edit$/.test(p)));
        if (edit) fixture.faqId = edit.split('/').at(-2);
      }
    }
    await ctx.close();
  }
  return states;
}

async function permissions(browser, states) {
  const matrix = {
    guest: [['/account', 302, '/login'], ['/staff/dashboard', 302, '/staff/login'], ['/admin/accounts', 302, '/staff/login'], ['/api/staff/reservations', 401]],
    buyer: [['/account', 200], ['/staff/dashboard', 403], ['/admin/accounts', 403], ['/api/staff/reservations', 401]],
    staff: [['/account', 403], ['/staff/dashboard', 200], ['/admin/accounts', 403], ['/api/staff/reservations', 401]],
    admin: [['/account', 403], ['/staff/dashboard', 200], ['/admin/accounts', 200], ['/api/staff/reservations', 401]]
  };
  for (const [role, routes] of Object.entries(matrix)) {
    if (role !== 'guest' && !states[role]) { record({role, id:'role-matrix', status:'BLOCKED', reason:'QA login missing'}); continue; }
    const ctx = await context(browser, profiles[0], states[role]);
    try {
      for (const [route, expected, redirect] of routes) {
        const r = await ctx.request.get(base + route, { maxRedirects: 0, timeout: 7500 });
        const location = r.headers().location;
        const got = location ? new URL(location, base).pathname : null;
        const ok = r.status() === expected && (!redirect || got === redirect);
        record({ role, id: 'role-matrix', kind: 'permission', route, status: ok ? 'PASS' : 'FAIL', expected, http: r.status(), expectedRedirect: redirect, finalPath: got });
      }
    } finally { await ctx.close(); }
  }
}

async function main() {
  const config = { host: '127.0.0.1', port: 23307, database: 't3l_restaurant', user: 't3l_audit', password: 't3l-audit-disposable-db' };
  const db = await mysql.createConnection(config);
  let browser;
  try {
    browser = await chromium.launch({ headless: true, args: ['--disable-gpu'] });
    if (resume) {
      const [booking] = await sql(db, 'SELECT id,code,guest_phone FROM reservations WHERE guest_name=? ORDER BY id DESC LIMIT 1', [displayName]);
      assert.ok(booking, 'Resume requires a reservation from the first run');
      phone = booking.guest_phone;
      email = `audit-${phone}@qa.invalid`;
      personal.push(phone, email);
      fixture.buyerCode = booking.code;
      fixture.buyerBookingId = booking.id;
    }
    for (const [key, query] of Object.entries({
      reservationId: 'SELECT id FROM reservations ORDER BY id LIMIT 1',
      orderReservationId: 'SELECT reservation_id AS id FROM dining_orders ORDER BY id LIMIT 1',
      accountId: "SELECT id FROM app_users WHERE role='STAFF' ORDER BY id LIMIT 1",
      dishId: 'SELECT id FROM dishes ORDER BY id LIMIT 1', tableId: 'SELECT id FROM restaurant_tables ORDER BY id LIMIT 1',
      voucherId: 'SELECT id FROM vouchers ORDER BY id LIMIT 1', contactId: 'SELECT id FROM contact_messages ORDER BY id LIMIT 1',
      invoiceId: "SELECT id FROM dining_orders WHERE status='PAID' ORDER BY id LIMIT 1"
    })) { try { fixture[key] = (await sql(db, query))[0]?.id; } catch {} }
    personal.push(...(await sql(db, 'SELECT full_name,username,email,phone FROM app_users')).flatMap(Object.values).filter(Boolean));
    personal.push(...(await sql(db, 'SELECT DISTINCT guest_name,guest_phone,guest_email FROM reservations')).flatMap(Object.values).filter(Boolean));
    personal = [...new Set(personal)];
    const states = await bootstrap(browser, db);
    await permissions(browser, states);
    if (process.env.QA_RUN_FLOWS !== '0') await require('./flows.cjs')({browser, states, context, open, step, screenshot, db, sql, fixture, base, displayName, phone, email, record});
    if (process.env.QA_MATRIX !== '0') {
      for (const role of Object.keys(screens)) {
        for (let offset = 0; offset < profiles.length; offset += 3) {
          await Promise.all(profiles.slice(offset, offset + 3).map(profile => browseProfile(browser, role, profile, states[role])));
        }
      }
    }
  } finally { await browser?.close(); await db.end(); }
}

if (require.main === module) main().catch(error => { console.error(safe(error.stack)); process.exitCode = 1; });
function addRedactions(values) { personal.push(...values.filter(Boolean)); }
module.exports = { chromium, mysql, context, open, record, step, screenshot, addRedactions, base, loginPassword };
