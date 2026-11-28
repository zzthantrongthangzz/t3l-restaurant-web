const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { setTimeout: wait } = require('node:timers/promises');
const { chromium, mysql, context, open, step, addRedactions } = require('./audit.cjs');

const resetRoute = '/login/forgot-password';
const otpFile = path.resolve(__dirname, '../../target/t3l-qa-audit-runtime/qa-otp.txt');

async function requestCode(page, phone) {
  await open(page, resetRoute);
  await page.locator('#reset-phone').fill(phone);
  await page.getByRole('button',{name:'Gửi mã xác minh'}).click();
  await page.locator('#reset-code').waitFor();
}

async function main() {
  const db = await mysql.createConnection({host:'127.0.0.1',port:23307,user:'t3l_audit',password:'t3l-audit-disposable-db',database:'t3l_restaurant'});
  const browser = await chromium.launch({headless:true,args:['--disable-gpu']});
  try {
    const [[buyer]] = await db.execute("SELECT guest_phone FROM reservations WHERE guest_name='Audit Buyer' ORDER BY id DESC LIMIT 1");
    assert.ok(buyer?.guest_phone);
    const unknown = '091' + Date.now().toString().slice(-7);
    const [other] = await db.execute('SELECT id FROM app_users WHERE phone=?',[unknown]);
    assert.equal(other.length,0);
    addRedactions([buyer.guest_phone,unknown]);
    const knownContext = await context(browser,{width:390,height:844,theme:'dark'});
    const unknownContext = await context(browser,{width:390,height:844,theme:'dark'});
    const page = await knownContext.newPage();
    const control = await unknownContext.newPage();
    page.setDefaultTimeout(8000);
    control.setDefaultTimeout(8000);
    let correctOtp;
    const comparable = await step('guest','reset-generic-response',resetRoute,page,async()=>{
      fs.rmSync(otpFile,{force:true});
      await requestCode(page,buyer.guest_phone);
      for (let i=0;i<30&&!fs.existsSync(otpFile);i++) await wait(200);
      assert.ok(fs.existsSync(otpFile),'Missing OTP in local SMTP sink');
      correctOtp = fs.readFileSync(otpFile,'utf8').trim();
      assert.match(correctOtp,/^\d{6}$/);
      addRedactions([correctOtp]);
      await requestCode(control,unknown);
      assert.equal(await control.locator('main').innerText(),await page.locator('main').innerText());
      assert.equal(await page.locator('.password-reset-email').innerText(),'***@***');
      return 'Registered and unused phones presented the same OTP screen and the same generic email mask.';
    });
    if (comparable) {
      await step('guest','reset-resend-cooldown',resetRoute,page,async()=>{
        await page.getByRole('button',{name:'Gửi lại mã'}).click();
        await page.getByRole('alert').getByText(/quá thường xuyên/).waitFor();
        assert.equal(fs.readFileSync(otpFile,'utf8').trim(),correctOtp);
        return 'Immediate resend displayed a cooldown error and the local SMTP code remained unchanged.';
      });
      await step('guest','reset-attempt-limit',resetRoute,page,async()=>{
        const wrong = correctOtp === '000000' ? '111111' : '000000';
        addRedactions([wrong]);
        const verify = page.getByRole('button',{name:'Xác minh mã'});
        for (let attempts = 4; attempts > 0; attempts--) {
          await page.locator('#reset-code').fill(wrong);
          await verify.click();
          await page.getByRole('alert').getByText(new RegExp('còn '+attempts+' lần thử')).waitFor();
        }
        await page.locator('#reset-code').fill(wrong);
        await verify.click();
        await page.getByRole('alert').getByText(/quá nhiều lần/).waitFor();
        await page.locator('#reset-code').fill(correctOtp);
        await verify.click();
        await page.getByRole('alert').getByText(/quá nhiều lần/).waitFor();
        assert.equal(await page.locator('#reset-password').count(),0);
        return 'Five wrong OTPs blocked the challenge. The correct OTP was then refused on the same challenge.';
      });
    }
    await unknownContext.close();
    await knownContext.close();
  } finally { await browser.close(); await db.end(); }
}
main().catch(error=>{console.error(error.name+': recovery controls did not complete.');process.exitCode=1;});
