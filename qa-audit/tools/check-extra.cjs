const assert = require('node:assert/strict');
const { chromium, mysql, context, open, record, base, loginPassword } = require('./audit.cjs');

async function main() {
  const db = await mysql.createConnection({host:'127.0.0.1',port:23307,user:'t3l_audit',password:'t3l-audit-disposable-db',database:'t3l_restaurant'});
  const browser = await chromium.launch({headless:true,args:['--disable-gpu']});
  try {
    const [[buyer]] = await db.execute("SELECT guest_phone,code FROM reservations WHERE guest_name='Audit Buyer' ORDER BY id DESC LIMIT 1");
    const [[other]] = await db.execute("SELECT code FROM reservations WHERE guest_name<>'Audit Buyer' ORDER BY id LIMIT 1");
    assert.ok(buyer && other);
    const guest = await context(browser, {width:390,height:844,theme:'light'});
    const page = await guest.newPage();
    await open(page,'/reservations/new');
    await page.locator('.tracking-panel').waitFor({timeout:5000});
    const overlap = await page.evaluate(() => {
      const panel = document.querySelector('.tracking-panel').getBoundingClientRect();
      return [...document.querySelectorAll('#booking-form input:not([type=hidden]), #booking-form select')].flatMap(field => {
        const rect = field.getBoundingClientRect();
        const intersects = Math.min(rect.right,panel.right)>Math.max(rect.left,panel.left) &&
          Math.min(rect.bottom,panel.bottom)>Math.max(rect.top,panel.top);
        if (!intersects) return [];
        const element = document.elementFromPoint(Math.max(rect.left,panel.left)+2,Math.max(rect.top,panel.top)+2);
        return element?.closest('.tracking-panel') ? [field.id || field.name] : [];
      });
    });
    record({role:'guest',kind:'ux',id:'privacy-over-booking-form',route:'/reservations/new',status:overlap.length?'FAIL':'PASS',
      severity:'Medium',steps:'Mở phiên Guest mới, viewport 390×844 Light, giữ nguyên hộp Phân tích trải nghiệm và thử chạm ô liên hệ.',
      expectedResult:'Hộp quyền riêng tư chừa chỗ hoặc có thể thu gọn mà không che trường cần nhập.',
      detail:overlap.length?`Panel nhận hit-test thay cho các trường: ${overlap.join(', ')}.`:'Không đo được trường bị panel che.',
      fix:'Hiển thị banner trong luồng bố cục ở màn hình hẹp hoặc tách nó khỏi vùng biểu mẫu với khoảng chừa và nút thu gọn.',
      screenshot:'screenshots/mobile/guest-booking-390x844-light.jpg'});

    const buyerReceipt = await guest.request.get(base+'/reservations/confirmation?code='+encodeURIComponent(buyer.code),{maxRedirects:0});
    record({role:'guest',kind:'permission',id:'private-receipt-without-session',route:'/reservations/confirmation?code={buyerCode}',status:buyerReceipt.status()===404?'PASS':'FAIL',expected:404,http:buyerReceipt.status()});
    await guest.close();

    const loggedIn = await context(browser);
    const bp = await loggedIn.newPage();
    await open(bp,'/login');
    await bp.locator('#login-phone').fill(buyer.guest_phone);
    await bp.locator('#login-password').fill(loginPassword+'R');
    await bp.locator('form[action$="/login/password"] button[type=submit]').click();
    await bp.waitForURL(base+'/');
    const strangerOrder = await loggedIn.request.get(base+'/account/reservations/'+encodeURIComponent(other.code)+'/preorder',{maxRedirects:0});
    record({role:'buyer',kind:'permission',id:'other-buyers-preorder',route:'/account/reservations/{otherCode}/preorder',status:strangerOrder.status()===404?'PASS':'FAIL',expected:404,http:strangerOrder.status()});
    await loggedIn.close();
  } finally { await browser.close(); await db.end(); }
}
main().catch(error=>{console.error(error.name + ': extra QA checks did not complete.');process.exitCode=1;});
