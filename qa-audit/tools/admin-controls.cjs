const assert = require('node:assert/strict');
const { chromium, mysql, context, open, step, addRedactions, base, loginPassword, record } = require('./audit.cjs');

async function main() {
  const db = await mysql.createConnection({host:'127.0.0.1',port:23307,user:'t3l_audit',password:'t3l-audit-disposable-db',database:'t3l_restaurant'});
  const browser = await chromium.launch({headless:true,args:['--disable-gpu']});
  try {
    const [accounts] = await db.query('SELECT full_name,username,email,phone FROM app_users');
    addRedactions(accounts.flatMap(Object.values));
    const admin = await context(browser);
    const page = await admin.newPage();
    page.setDefaultTimeout(6500);
    page.on('dialog', dialog => dialog.accept());
    await open(page, '/staff/login');
    await page.locator('input[name=username]').fill('audit-admin');
    await page.locator('input[name=password]').fill(loginPassword);
    await page.locator('form button[type=submit]').click();
    await page.waitForURL('**/admin/dashboard');
    const suffix = Date.now().toString().slice(-6);
    const newEmployee = 'audit-control-' + suffix;
    addRedactions([newEmployee, 'Audit Employee']);
    await step('admin','create-employee','/admin/accounts/new',page,async()=>{
      await open(page,'/admin/accounts/new');
      await page.locator('#username').fill(newEmployee);
      await page.locator('#fullName').fill('Audit Employee');
      await page.locator('main input[type=password]').fill(loginPassword);
      const enabled = page.locator('input[name=enabled][type=checkbox]');
      if (await enabled.count()) await enabled.check();
      await page.getByRole('button',{name:'Lưu tài khoản'}).click();
      await page.waitForURL(/\/admin\/accounts(\/\d+)?(?:\?|$)/);
      const [[saved]] = await db.execute('SELECT role,enabled FROM app_users WHERE username=?',[newEmployee]);
      assert.equal(saved?.role,'STAFF');
      assert.equal(saved?.enabled,1);
      return 'Synthetic Staff account saved with its expected role.';
    });
    await step('admin','create-table','/admin/inventory/new',page,async()=>{
      await open(page,'/admin/inventory/new');
      const code = 'QA-' + suffix;
      await page.locator('#code').fill(code);
      await page.locator('#classification').selectOption('SMALL');
      assert.equal(await page.locator('#capacity').inputValue(),'4');
      await page.getByRole('button',{name:'Lưu bàn'}).click();
      await page.waitForURL(/\/admin\/inventory(?:\?|$)/);
      const [[saved]] = await db.execute('SELECT capacity FROM restaurant_tables WHERE code=?',[code]);
      assert.equal(saved?.capacity,4);
      return 'Table type synchronized to four seats and the synthetic table persisted.';
    });
    await step('admin','create-voucher','/admin/vouchers/new',page,async()=>{
      await open(page,'/admin/vouchers/new');
      const code='QA'+suffix;
      const date=(days)=>new Date(Date.now()+86400000*days).toISOString().slice(0,10)+'T12:00';
      for (const [key,value] of Object.entries({code,title:'QA voucher '+suffix,discountValue:'10',minimumTotal:'0',startsAt:date(1),endsAt:date(7),totalLimit:'2',customerLimit:'1'})) {
        await page.locator('#'+key).fill(value);
      }
      await page.locator('#discountType').selectOption('PERCENT');
      await page.getByRole('button',{name:'Lưu voucher'}).click();
      await page.waitForURL(/\/admin\/vouchers(\/\d+)?(?:\?|$)/);
      const [[saved]] = await db.execute('SELECT id FROM vouchers WHERE code=?',[code]);
      assert.ok(saved?.id);
      return 'Time-limited QA voucher saved.';
    });
    await step('admin','publish-faq','/admin/faqs/new',page,async()=>{
      await open(page,'/admin/faqs/new');
      const question='QA FAQ '+suffix;
      await page.locator('#question').fill(question);
      await page.locator('#answer').fill('Synthetic answer for QA coverage.');
      await page.locator('#displayOrder').fill('900');
      await page.getByRole('checkbox',{name:'Đã đăng'}).check();
      await page.getByRole('button',{name:'Lưu câu hỏi'}).click();
      await page.waitForURL(/\/admin\/faqs(?:\?|$)/);
      await open(page,'/faq');
      await page.getByText(question,{exact:true}).waitFor();
      const accordion = page.locator('details.faq-item').filter({hasText:question});
      await accordion.locator('summary').click();
      await accordion.getByText('Synthetic answer for QA coverage.',{exact:true}).waitFor();
      return 'Published a QA FAQ and observed it on the public screen.';
    });
    await step('admin','contact-status','/admin/contacts/{id}',page,async()=>{
      const [[fixture]] = await db.execute("SELECT id FROM contact_messages WHERE subject='QA audit contact' ORDER BY id DESC LIMIT 1");
      assert.ok(fixture?.id);
      await open(page,'/admin/contacts/'+fixture.id);
      await page.locator('select[name=status]').selectOption('RESOLVED');
      const notes=page.locator('main textarea');
      if (await notes.count()) await notes.fill('QA resolution only');
      const response=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/status'));
      await page.getByRole('button',{name:'Lưu cập nhật'}).click();
      await response;
      const [[saved]]=await db.execute('SELECT status FROM contact_messages WHERE id=?',[fixture.id]);
      assert.equal(saved.status,'RESOLVED');
      return 'Only the contact submitted by the QA flow was marked resolved.';
    });
    const staffToken = await admin.request.post(base+'/api/auth/login',{ data:{username:'audit-staff',password:loginPassword} });
    if (staffToken.status() === 200) {
      const body = await staffToken.json();
      const response = await admin.request.get(base+'/api/staff/reservations',{headers:{authorization:'Bearer '+body.accessToken}});
      record({role:'staff',kind:'permission',id:'staff-jwt-api',route:'/api/staff/reservations',status:response.status()===200?'PASS':'FAIL',expected:200,http:response.status()});
    } else {
      record({role:'staff',kind:'permission',id:'staff-jwt-api',route:'/api/auth/login',status:'FAIL',expected:200,http:staffToken.status()});
    }
    await admin.close();
  } finally { await browser.close(); await db.end(); }
}
main().catch(error=>{console.error(error.name+': additional control checks did not complete.');process.exitCode=1;});
