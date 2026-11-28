const assert = require('node:assert/strict');
const { chromium, mysql, context, open, step, addRedactions, loginPassword } = require('./audit.cjs');

async function main() {
  const db = await mysql.createConnection({host:'127.0.0.1',port:23307,user:'t3l_audit',password:'t3l-audit-disposable-db',database:'t3l_restaurant'});
  const browser = await chromium.launch({headless:true,args:['--disable-gpu']});
  try {
    const [[booking]] = await db.execute("SELECT id,code,guest_name,guest_phone FROM reservations WHERE guest_name='Audit Buyer' ORDER BY id DESC LIMIT 1");
    assert.ok(booking);
    addRedactions([...Object.values(booking).filter(value => typeof value === 'string'), 'audit-staff']);
    const ctx = await context(browser);
    const page = await ctx.newPage();
    page.setDefaultTimeout(6500);
    await open(page, '/staff/login');
    await page.locator('input[name=username]').fill('audit-staff');
    await page.locator('input[name=password]').fill(loginPassword);
    await page.locator('form button[type=submit]').click();
    await page.waitForURL('**/staff/dashboard');
    let payment;
    await step('staff','cash-payment','/staff/orders/{id}',page,async()=>{
      await open(page, '/staff/orders/' + booking.id);
      await page.getByRole('heading',{name:'Thông tin thanh toán'}).waitFor();
      await page.locator('#checkout').getByText('Tiền mặt',{exact:true}).waitFor();
      [[payment]] = await db.execute(`SELECT p.payment_method,p.amount,p.deposit_applied,p.shift_id,o.status
        FROM dining_payments p JOIN dining_orders o ON o.id=p.order_id WHERE o.reservation_id=?`,[booking.id]);
      assert.equal(payment?.status,'PAID');
      assert.equal(payment.payment_method,'CASH');
      assert.ok(Number(payment.amount)>0 && Number(payment.deposit_applied)>0);
      return `The submitted QA cash payment was persisted and visible as PAID. Cash ${payment.amount} VND, simulated deposit applied ${payment.deposit_applied} VND.`;
    });
    await step('staff','reconcile-shift','/staff/shift',page,async()=>{
      await open(page,'/staff/shift');
      await page.locator('[data-status=CLOSED]').first().waitFor();
      assert.ok(payment?.shift_id);
      const [[shift]] = await db.execute('SELECT status,opening_cash,cash_sales,counted_cash,cash_difference FROM staff_shifts WHERE id=?',[payment.shift_id]);
      assert.equal(shift?.status,'CLOSED');
      assert.equal(Number(shift.cash_sales),Number(payment.amount));
      assert.equal(Number(shift.counted_cash),Number(shift.opening_cash)+Number(shift.cash_sales));
      assert.equal(Number(shift.cash_difference),0);
      return `Verified the closed shift linked to the cash payment. Counted ${shift.counted_cash} VND and zero difference.`;
    });
    await ctx.close();
  } finally { await browser.close(); await db.end(); }
}
main().catch(error=>{console.error(error.name+': settlement verification did not complete.');process.exitCode=1;});
