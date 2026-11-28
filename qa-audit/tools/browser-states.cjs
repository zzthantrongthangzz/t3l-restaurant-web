const assert = require('node:assert/strict');
const { chromium, mysql, context, open, step, base, loginPassword, addRedactions, screenshot } = require('./audit.cjs');

async function main() {
  const db = await mysql.createConnection({host:'127.0.0.1',port:23307,user:'t3l_audit',password:'t3l-audit-disposable-db',database:'t3l_restaurant'});
  const browser = await chromium.launch({headless:true,args:['--disable-gpu']});
  try {
    const [[booking]] = await db.execute("SELECT guest_phone FROM reservations WHERE guest_name='Audit Buyer' ORDER BY id DESC LIMIT 1");
    assert.ok(booking?.guest_phone);
    addRedactions([booking.guest_phone]);
    const guestContext = await browser.newContext({viewport:{width:390,height:844},colorScheme:'light'});
    const guest = await guestContext.newPage();
    guest.setDefaultTimeout(8000);
    guest.setDefaultNavigationTimeout(8000);
    const authPath = pathname => guest.waitForURL(url => url.pathname === pathname,{waitUntil:'domcontentloaded'});
    await step('guest','auth-navigation-and-theme','/login',guest,async()=>{
      await open(guest,'/login');
      await guest.getByRole('button',{name:/hiện mật khẩu/i}).click();
      assert.equal(await guest.locator('#login-password').getAttribute('type'),'text');
      await guest.getByRole('button',{name:/ẩn mật khẩu/i}).click();
      assert.equal(await guest.locator('#login-password').getAttribute('type'),'password');
      await guest.locator('[data-theme-toggle]').click();
      await guest.waitForFunction(()=>document.documentElement.dataset.theme==='dark');
      await guest.getByRole('link',{name:'Đăng ký ngay'}).click();
      await authPath('/register');
      assert.equal(await guest.locator('html').getAttribute('data-theme'),'dark');
      await guest.locator('main').getByRole('link',{name:'Đăng nhập',exact:true}).click();
      await authPath('/login');
      await guest.getByRole('link',{name:'Quên mật khẩu?'}).click();
      await authPath('/login/forgot-password');
      assert.equal(await guest.locator('html').getAttribute('data-theme'),'dark');
      await guest.getByRole('link',{name:'Quay lại đăng nhập'}).click();
      await authPath('/login');
      return 'Password visibility, theme toggle, and customer register/reset/login links were exercised on mobile.';
    });
    await guestContext.close();

    const buyerContext = await context(browser,{width:768,height:1024,theme:'dark'});
    const page = await buyerContext.newPage();
    page.setDefaultTimeout(8000);
    page.setDefaultNavigationTimeout(8000);
    await step('buyer','booking-loading','/reservations/new',page,async()=>{
      await open(page,'/login');
      await page.locator('#login-phone').fill(booking.guest_phone);
      await page.locator('#login-password').fill(loginPassword+'R');
      await page.locator('form[action$="/login/password"] button[type=submit]').click();
      await page.waitForURL(base+'/');
      await open(page,'/reservations/new');
      const baseDate = await page.locator('input[name=date]').getAttribute('min');
      const future = new Date(Date.parse(baseDate+'T12:00:00Z')+172800000).toISOString().slice(0,10);
      await open(page,`/reservations/new?date=${future}&time=18:00&guests=2`);
      await page.locator('[data-booking-next="2"]:visible').click();
      await page.locator('[data-booking-next="3"]:visible').click();
      await page.locator('input[name=consentAccepted]').check();
      await page.evaluate(() => {
        document.addEventListener('submit', event => {
          if (event.target.id === 'booking-form') event.preventDefault();
        }, {once:true});
      });
      const confirm = page.locator('[data-booking-confirm]');
      await confirm.click();
      assert.equal(await page.locator('#booking-form').getAttribute('aria-busy'),'true');
      assert.equal(await confirm.getAttribute('aria-disabled'),'true');
      assert.match(await confirm.innerText(),/Đang xử lý/);
      await screenshot(page,'buyer','flow-buyer-booking-in-flight');
      return 'Observed the loading label and disabled confirmation after normal submit handlers ran. The browser then withheld this extra POST to preserve the captured state.';
    });
    await buyerContext.close();
  } finally { await browser.close(); await db.end(); }
}
main().catch(error=>{console.error(error.name+': browser state checks did not complete.');process.exitCode=1;});
