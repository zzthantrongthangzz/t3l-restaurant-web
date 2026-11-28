const assert = require('node:assert/strict');
const { setTimeout: wait } = require('node:timers/promises');

module.exports = async ({browser, states, context, open, step, screenshot, db, sql, fixture, base, displayName, phone, email, record}) => {
  const notRun = (role, id, route, reason) => record({role, id, route, kind:'interaction', status:'BLOCKED', reason});
  const expectSql = async (query, values, predicate) => {
    for (let i = 0; i < 18; i++) {
      const result = (await sql(db, query, values))[0];
      if (result && predicate(result)) return result;
      await wait(330);
    }
    assert.fail('Expected QA record or state was not observed');
  };
  const reservation = (predicate) => expectSql('SELECT id,code,status,deposit_status FROM reservations WHERE guest_phone=? ORDER BY id DESC LIMIT 1', [phone], predicate);
  const clickAction = (page, suffix, selector = 'button[type=submit],button:not([type])') =>
    page.locator(`form[action$="${suffix}"]:visible`).first().locator(selector).first().click({timeout:6500});
  const pageFor = async role => {
    const ctx = await context(browser, undefined, states[role]);
    const page = await ctx.newPage();
    page.setDefaultTimeout(7000);
    page.on('dialog', dialog => dialog.accept());
    return [ctx, page];
  };

  const [guest, gp] = await pageFor('guest');
  await step('guest', 'menu-search-empty', '/menu', gp, async () => {
    await open(gp, '/menu');
    await gp.locator('#menu-search').fill('qa-unmatched-restaurant-audit');
    await gp.locator('#menu-no-results').waitFor();
    assert.match(await gp.locator('#menu-no-results').innerText(), /Chưa tìm thấy/);
    return 'Unmatched search rendered its empty result.';
  });
  await step('guest', 'menu-details-modal', '/menu', gp, async () => {
    await open(gp, '/menu');
    await gp.locator('button[data-menu-detail], button[data-dish-detail], button.menu-detail').filter({hasNotText:'Đóng'}).first().click();
    await gp.locator('#dish-dialog[open]').waitFor();
    await screenshot(gp, 'guest', 'flow-guest-menu-dialog-open');
    await gp.keyboard.press('Escape');
    await gp.locator('#dish-dialog:not([open])').waitFor({state:'attached'});
    return 'Opened a dish dialog and dismissed it with Escape.';
  });
  if (process.env.QA_RESUME !== '1') await step('guest', 'contact-submit', '/contact', gp, async () => {
    await open(gp, '/contact');
    await gp.locator('#contact-name').fill(displayName);
    await gp.locator('#contact-email').fill(email);
    await gp.locator('#contact-phone').fill(phone);
    await gp.locator('#contact-subject').fill('QA audit contact');
    await gp.locator('#contact-message').fill('Synthetic contact for isolated website audit.');
    await gp.locator('#contact-form button[type=submit]').click();
    await gp.locator('.contact-success').waitFor();
    return 'Contact submitted only to the isolated database.';
  });
  await guest.close();

  let booked = !!fixture.buyerBookingId;
  if (!booked && states.buyer) {
    const [buyer, bp] = await pageFor('buyer');
    booked = await step('buyer', 'booking-preorder-confirmation', '/reservations/new', bp, async () => {
      await open(bp, '/reservations/new');
      const baseDate = await bp.locator('input[name=date]').getAttribute('min');
      const tomorrow = new Date(Date.parse(baseDate + 'T12:00:00Z') + 86400000).toISOString().slice(0,10);
      await open(bp, `/reservations/new?date=${tomorrow}&time=18:00&guests=2`);
      await bp.locator('[data-booking-next="2"]:visible').click();
      const plus = bp.locator('[data-booking-panel="2"] [data-preorder-step="1"]:visible').first();
      await plus.click();
      if (await bp.locator('#preorderNote').isVisible()) await bp.locator('#preorderNote').fill('QA kitchen note');
      await screenshot(bp, 'buyer', 'flow-buyer-booking-step-2');
      await bp.locator('[data-booking-next="3"]:visible').click();
      await bp.locator('input[name=consentAccepted]').check();
      await screenshot(bp, 'buyer', 'flow-buyer-booking-step-3');
      await bp.locator('[data-booking-confirm]').click();
      await bp.waitForURL('**/reservations/confirmation?**');
      const booking = await reservation(row => !!row.code);
      fixture.buyerCode = booking.code;
      fixture.buyerBookingId = booking.id;
      const order = await sql(db, 'SELECT id FROM dining_orders WHERE reservation_id=?', [booking.id]);
      assert.ok(order.length, 'Reservation created without a persisted preorder');
      await screenshot(bp, 'buyer', 'flow-buyer-booking-receipt');
      return 'Reservation, confirmation and one preordered dish persisted.';
    });
    await buyer.close();
  } else if (!booked) notRun('buyer','booking-preorder-confirmation','/reservations/new','Buyer login missing');

  if (booked && states.staff) {
    const [staff, sp] = await pageFor('staff');
    const [orderContext, op] = await pageFor('staff');
    const id = fixture.buyerBookingId;
    const bookingRoute = `/staff/reservations/${id}`;
    const orderRoute = `/staff/orders/${id}`;
    const alreadySeated = process.env.QA_RESUME === '1' && (await reservation(() => true)).status === 'SEATED';
    let deposited = false;
    if (!alreadySeated && states.buyer) {
      const [buyer, bp] = await pageFor('buyer');
      deposited = await step('buyer','deposit-demo','/reservations/lookup?code={buyerCode}',bp,async () => {
        await open(bp, '/reservations/confirmation?code=' + encodeURIComponent(fixture.buyerCode));
        const detail = bp.getByRole('link', {name:'Xem chi tiết đặt bàn'});
        if (await detail.count()) await detail.click();
        await bp.waitForURL(/\/reservations\/(lookup|confirmation)\?/);
        if (await bp.locator('details.booking-payment-demo > summary').count()) await bp.locator('details.booking-payment-demo > summary').click();
        await screenshot(bp, 'buyer', 'flow-buyer-deposit-before-demo');
        await clickAction(bp, '/payment-demo');
        const current = await reservation(row => !['WAITING_PAYMENT','TRANSFER_SUBMITTED','PENDING'].includes(row.deposit_status));
        return `Local payment simulation accepted. Reservation: ${current.status}.`;
      });
      await buyer.close();
    } else if (!alreadySeated) notRun('buyer','deposit-demo','/reservations/confirmation','Buyer session missing');
    let approved = false;
    if (!alreadySeated && deposited) approved = await step('staff', 'confirm-reservation', '/staff/reservations/{id}', sp, async () => {
      await open(sp,bookingRoute);
      await sp.locator('button[name=status][value=CONFIRMED]:visible').click();
      const current = await reservation(row => row.status==='CONFIRMED');
      return `Reservation status: ${current.status}`;
    });
    else if (!alreadySeated) notRun('staff','confirm-reservation','/staff/reservations/{id}','Deposit must be verified before staff confirmation in QR mode');
    const shifted = await step('staff','open-shift','/staff/shift',sp,async () => {
      await open(sp,'/staff/shift');
      if (await sp.locator('input[name=openingCash]').isVisible()) {
        await sp.locator('input[name=openingCash]').fill('100000');
        await clickAction(sp,'/shift/open');
      }
      await sp.locator('[data-status=OPEN]').first().waitFor();
      return 'Synthetic opening cash recorded.';
    });
    let received = alreadySeated, finalized = false;
    if (!alreadySeated && approved && shifted) received = await step('staff','receive-table','/staff/reservations/{id}',sp,async () => {
      // Advance the isolated fixture into the check-in window instead of waiting a calendar day.
      await sql(db,'UPDATE reservations SET reservation_date=CURRENT_DATE(), reservation_time=CURRENT_TIME(), ends_at=DATE_ADD(NOW(), INTERVAL 120 MINUTE) WHERE id=?',[id]);
      await open(sp,bookingRoute);
      await sp.locator('button[name=status][value=RECEIVE]:visible').click();
      await reservation(row => row.status==='SEATED');
      return 'Synthetic schedule advanced into the check-in window. UI changed reservation to SEATED.';
    }); else if (!alreadySeated) notRun('staff','receive-table','/staff/reservations/{id}','Deposit and/or opening shift did not complete');
    if (received && shifted) finalized = await step('staff','add-dish-and-finalize','/staff/orders/{id}',op,async () => {
      await open(op,orderRoute);
      const openOrder = op.locator('form[action$="/open"]:visible');
      if (await openOrder.count()) await openOrder.locator('button').click();
      const dish = op.locator('select[name=dishId]:visible');
      const before = await op.locator('.staff-table tbody tr').count();
      await dish.selectOption({index:1});
      await op.locator('.staff-order-add input[name=quantity]').fill('2');
      await clickAction(op,'/items');
      await op.waitForFunction(count => document.querySelectorAll('.staff-table tbody tr').length > count, before);
      await screenshot(op,'staff','flow-staff-order-draft-with-dishes');
      await clickAction(op,'/finalize');
      await expectSql('SELECT status FROM dining_orders WHERE reservation_id=?',[id], row=>row.status==='FINALIZED');
      await op.getByRole('heading',{name:'Xác nhận thu tiền'}).waitFor();
      return 'Added a dish and finalized the order via the staff UI.';
    }); else notRun('staff','add-dish-and-finalize','/staff/orders/{id}','Table reception or opening shift did not complete');
    if (finalized) await step('staff','cash-payment','/staff/orders/{id}',op,async () => {
      const form = op.locator('form[action$="/pay"]').filter({has:op.locator('input[name=method][value=CASH]')});
      await form.getByRole('checkbox').check();
      await form.getByRole('button',{name:'Thanh toán tiền mặt'}).click();
      await expectSql('SELECT d.id,d.payment_method,d.amount FROM dining_payments d JOIN dining_orders o ON o.id=d.order_id WHERE o.reservation_id=?',[id],row=>row.payment_method==='CASH' && Number(row.amount)>0);
      await op.getByRole('heading',{name:'Thông tin thanh toán'}).waitFor();
      return 'QA cash ledger entry created. No transfer or external payment.';
    }); else notRun('staff','cash-payment','/staff/orders/{id}','Order finalization did not complete');
    await orderContext.close();
    if (shifted) await step('staff','reconcile-shift','/staff/shift',sp,async () => {
      await open(sp,'/staff/shift');
      const close = sp.locator('form[data-shift-close]');
      const expected = await close.getAttribute('data-expected-cash');
      await close.locator('input[name=countedCash]').fill(String(Math.round(Number(expected))));
      await close.locator('button[type=submit]').click();
      const modal = sp.locator('dialog[data-shift-confirm]');
      await modal.waitFor({state:'visible'});
      await screenshot(sp,'staff','flow-staff-close-shift-modal');
      await modal.getByRole('button',{name:/Xác nhận|Kết ca/}).last().click();
      await sp.locator('[data-status=CLOSED]').first().waitFor();
      return `Counted expected cash (${expected} VND) and confirmed reconciliation.`;
    });
    await step('staff','booking-list-pagination','/staff/reservations',sp,async () => {
      await open(sp,'/staff/reservations');
      const next = sp.getByRole('link',{name:'Trang sau'});
      await next.click();
      await sp.waitForURL(/page=1/);
      await sp.getByRole('link',{name:'Trang trước'}).waitFor();
      await screenshot(sp,'staff','flow-staff-booking-list-page-2');
      await sp.getByRole('link',{name:'Trang trước'}).click();
      await sp.waitForURL(/page=0|\/reservations$/);
      return 'Navigated to page 2 and back to page 1.';
    });
    await staff.close();
  } else {
    for (const [role,id,route] of [['staff','confirm-reservation','/staff/reservations/{id}'],['buyer','deposit-demo','/reservations/confirmation'],['staff','receive-table','/staff/reservations/{id}'],['staff','add-dish-and-finalize','/staff/orders/{id}'],['staff','cash-payment','/staff/orders/{id}'],['staff','reconcile-shift','/staff/shift']]) {
      notRun(role,id,route,'Buyer booking or Staff login did not complete');
    }
  }

  if (states.admin) {
    const [admin, ap] = await pageFor('admin');
    await step('admin','accounts-filter','/admin/accounts',ap,async () => {
      await open(ap,'/admin/accounts');
      const filter = ap.locator('main form[method=get]').first();
      await filter.locator('input[name=q],input[type=search]').first().fill('qa-no-such-account');
      await filter.getByRole('button',{name:'Tìm kiếm'}).click();
      await ap.waitForURL(/qa-no-such-account/);
      assert.match(await ap.locator('main').innerText(), /Không tìm thấy|Không có tài khoản|Chưa có tài khoản|Không có kết quả/);
      return 'Accounts filters submitted and empty result was displayed.';
    });
    await step('admin','export-csv','/admin/revenue.csv',ap,async () => {
      await open(ap,'/admin/revenue');
      const csv = ap.locator('main a[href*=".csv"]').first();
      const [download] = await Promise.all([ap.waitForEvent('download'),csv.click()]);
      assert.match(download.suggestedFilename(),/\.csv$/i);
      await download.delete();
      return 'CSV downloaded from the UI and discarded from the evidence bundle.';
    });
    await step('admin','activity-filter','/admin/activity',ap,async () => {
      await open(ap,'/admin/activity');
      const form = ap.locator('main form:has(input[name=action])');
      await form.locator('input[name=action]').fill('qa-none');
      await form.getByRole('button',{name:'Lọc',exact:true}).click();
      await ap.waitForURL(/action=qa-none/);
      await ap.locator('main .staff-empty').waitFor();
      return 'Action filter initialized and returned an empty result.';
    });
    await admin.close();
  }
};
