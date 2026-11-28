const fs = require('node:fs');
const path = require('node:path');
const screens = require('./screens.cjs');

const root = path.resolve(__dirname, '..');
const project = path.resolve(root, '..');
const runtime = path.join(project, 'target/t3l-qa-audit-runtime');
const input = path.join(root, 'playwright-report/results.jsonl');
const attempts = fs.existsSync(input) ? fs.readFileSync(input, 'utf8').split('\n').filter(Boolean).flatMap(line => {
  try { return [JSON.parse(line)]; } catch { return []; }
}) : [];
const rows = [...new Map(attempts.map(row => [[row.role,row.id,row.profile ?? row.kind,row.kind==='permission' ? row.route : ''].join('::'),row])).values()];
const labels = ['PASS', 'FAIL', 'BLOCKED', 'NOT TESTED'];
const plain = value => String(value ?? '').replace(/\x1b\[[0-9;]*m/g, '');
const md = value => plain(value ?? '—').replaceAll('|', '\\|').replace(/\s*\n\s*/g, ' ');
const esc = value => plain(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const write = (name, text) => fs.writeFileSync(path.join(root, name), text + '\n');
const screenshot = row => row?.screenshot ? `[Ảnh](${row.screenshot})` : '—';

function files(directory, suffix) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const name = path.join(directory, entry.name);
    return entry.isDirectory() ? files(name, suffix) : entry.name.endsWith(suffix) ? [name] : [];
  });
}

const routes = [];
const controllers = files(path.join(project, 'src/main/java'), 'Controller.java');
for (const source of controllers) {
  const code = fs.readFileSync(source, 'utf8');
  const classHeader = code.match(/\bclass\s+\w+/)?.index ?? 0;
  const parseMappings = part => [...part.matchAll(/@(Get|Post|Delete|Put|Patch|Request)Mapping\b(?:\s*\(([^)]*)\))?/g)];
  const paths = annotation => [...String(annotation ?? '').matchAll(/"(\/[^"\r\n]*|)"/g)].map(match => match[1]);
  const prefixes = parseMappings(code.slice(0, classHeader)).flatMap(match => paths(match[2]));
  if (!prefixes.length) prefixes.push('');
  for (const match of parseMappings(code.slice(classHeader))) {
    const endpoints = paths(match[2]);
    if (!endpoints.length) endpoints.push('');
    const line = code.slice(0, classHeader + match.index).split('\n').length;
    for (const prefix of prefixes) for (const endpoint of endpoints) {
      routes.push({ method: match[1].toUpperCase(), route: prefix + endpoint || '/', source: path.basename(source), line });
    }
  }
}
routes.sort((a, b) => a.route.localeCompare(b.route) || a.method.localeCompare(b.method));
const templates = files(path.join(project, 'src/main/resources/templates'), '.html').map(source => {
  const value = fs.readFileSync(source, 'utf8');
  return { file: path.relative(path.join(project, 'src/main/resources/templates'), source).replaceAll('\\', '/'),
    forms: (value.match(/<form\b/g) ?? []).length, dialogs: (value.match(/<dialog\b/g) ?? []).length,
    links: (value.match(/<a\s/g) ?? []).length };
});

const planned = Object.values(screens).flat().length * 8;
const layout = rows.filter(row => row.profile);
const actions = rows.filter(row => !row.profile);
const count = (value, status) => value.filter(row => row.status === status).length;
const screenshots = files(path.join(root, 'screenshots'), '.jpg').length;
const statusSummary = values => labels.map(label => `${label} ${count(values,label)}`).join(' · ');

const inventory = [
  '# Danh mục màn hình và route T3L Restaurant', '',
  'Commit `aad2b75541019df4750b69ffa29cbc275d84bdbc` trên `main`. Tham số `{…}` là fixture tổng hợp trong cơ sở dữ liệu QA.', '',
  '## Phân quyền đọc từ Spring Security', '',
  '| Khu vực | Role | Cách xử lý |', '|---|---|---|',
  '| Trang chủ, thực đơn, giới thiệu, không gian, liên hệ, FAQ, đặt bàn, tra cứu, đăng nhập và khôi phục | Công khai | Hóa đơn/xác nhận đặt bàn còn kiểm soát theo quyền sở hữu hoặc phiên được cấp quyền |',
  '| `/account` và `/account/**` | BUYER | Guest chuyển tới `/login`. Staff và Admin nhận 403. |',
  '| `/staff/**` | STAFF, ADMIN | Guest chuyển tới `/staff/login`. Buyer nhận 403. |',
  '| `/admin/**` | ADMIN | Guest chuyển tới `/staff/login`. Buyer và Staff nhận 403. |',
  '| `/api/menu`, `/api/auth/login` | Công khai theo phương thức cấu hình | Các route API được phục vụ qua chuỗi stateless, độc lập với cookie phiên web. |',
  '| `/api/staff/**`, `/api/admin/**` | JWT theo role | Kiểm thử ma trận tách xác thực cookie phiên web khỏi JWT. |',
  '| `/chat/**`, `/tracking/**` | Tùy nhóm | Chat cho Guest, Buyer, Admin. Tracking cho Guest và Buyer. |',
  '| POST web | CSRF | Chuỗi web giữ kiểm tra CSRF. Chuỗi API stateless dùng Bearer JWT. |', '',
  '## Màn hình theo role', '',
  'Kết quả trong bảng chỉ áp dụng cho mở màn hình, HTTP, lỗi runtime đã lắng nghe, theme và tràn ngang cấp trang. Hành vi từng nút được ghi riêng. Mỗi màn hình có tối đa tám tổ hợp.', ''
];
for (const [role, items] of Object.entries(screens)) {
  inventory.push(`### ${role.toUpperCase()}`, '', '| Màn hình | Route | Các lần chạy |', '|---|---|---|');
  for (const [id,route] of items) {
    const matches = layout.filter(row => row.role===role && row.id===id);
    const summary = matches.length ? labels.map(label=>`${label} ${label==='NOT TESTED' ? Math.max(0,8-matches.length) : count(matches,label)}`).join(' · ') : 'NOT TESTED';
    inventory.push(`| ${id} | \`${md(route)}\` | ${summary} |`);
  }
  inventory.push('');
}
inventory.push('## Trạng thái và màn hình ngoài ma trận', '',
  '- Luồng đặt bàn 3 bước, OTP, đơn/voucher, modal và đối soát xuất hiện theo dữ liệu. Kiểm tra đã thực hiện nằm trong bảng thao tác của báo cáo chính.',
  '- `error/403`, `error/404` và `error/500` là template lỗi Spring Boot. 500 dựa trên lỗi gặp trong audit, không cưỡng bức lỗi hệ thống. `/admin/invalid` phục vụ dữ liệu đầu vào sai.',
  '- Trang chi tiết sửa địa chỉ, hóa đơn, lượt đặt, món, bàn, voucher, FAQ, liên hệ và tài khoản dùng ID theo fixture. Không coi biến thể cho mọi bản ghi là đã kiểm thử.',
  '- Các endpoint của Spring Security như `/logout` và Google OAuth, endpoint trả JSON, CSV, poll và fragment được kê riêng khỏi màn hình.', '',
  `## Route controller (${routes.length} mục từ ${controllers.length} lớp)`, '', '| Method | Route | Nguồn |', '|---|---|---|');
for (const route of routes) inventory.push(`| ${route.method} | \`${md(route.route)}\` | ${route.source}:${route.line} |`);
inventory.push('', `## Template (${templates.length})`, '', '| Template | Form | Dialog | Link |', '|---|---:|---:|---:|');
for (const template of templates) inventory.push(`| \`${template.file}\` | ${template.forms} | ${template.dialogs} | ${template.links} |`);
write('PAGE_INVENTORY.md', inventory.join('\n'));

const findings = new Map();
function addFinding(row, type, level, expected, actual, remediation) {
  const key = `${type}:${row.role}:${row.id}:${actual}`;
  if (!findings.has(key)) findings.set(key, { row, type, level, expected, actual, remediation, profiles: new Set() });
  if (row.profile) findings.get(key).profiles.add(row.profile);
}
for (const row of layout.filter(result => result.status==='FAIL')) {
  if (row.http >= 500 && row.http !== row.expected) {
    addFinding(row,'HTTP','High',`Trang render HTTP ${row.expected}.`,`Trả HTTP ${row.http}.`,'Đối chiếu log máy chủ và binding/model của controller, rồi thêm kiểm thử render với fixture hiện tại.');
  } else if (row.http && row.http !== row.expected) {
    addFinding(row,'HTTP','Medium',`HTTP ${row.expected} theo trường hợp cụ thể.`,`Trả HTTP ${row.http}.`,'Xác minh điều kiện fixture và quyền chi tiết của route trước khi sửa controller hoặc điều chỉnh đầu vào của test.');
  }
  if (row.metrics?.scrollWidth > row.metrics?.width + 2) {
    addFinding(row,'Tràn ngang','Medium','Trang không tạo thanh cuộn ngang cấp document.','Chiều rộng nội dung vượt vùng hiển thị.',
      `Kiểm tra min-width và cách ngắt nội dung. Phần tử nghi vấn: ${row.metrics.overflowing.join(', ') || 'xem ảnh và metrics'}.`);
  }
  const exception = row.events?.find(item => item.type==='javascript' || item.type==='console');
  if (exception) addFinding(row,'JavaScript','Medium','Không phát sinh lỗi JavaScript/console.',exception.text,'Sửa lỗi tại script hoặc dữ liệu đầu vào gây lỗi và kiểm tra lại khi điều hướng từng phần.');
  const local = row.events?.find(item => item.type==='http' && item.host==='127.0.0.1' && item.resource!=='document');
  if (local) addFinding(row,'HTTP nền','Medium','Tài nguyên hoặc API cùng origin trả thành công.',`${local.route} HTTP ${local.status}.`,'Kiểm tra URL, dữ liệu và điều kiện render theo role cho request nền.');
}
for (const row of actions.filter(item=>item.kind==='permission' && item.status==='FAIL')) {
  const permissive = [401,403,302].includes(row.expected) && row.http===200;
  addFinding(row,'Phân quyền',permissive?'High':'Medium',`HTTP ${row.expected}${row.expectedRedirect ? ` → ${row.expectedRedirect}`:''}.`,
    `HTTP ${row.http}${row.finalPath ? ` → ${row.finalPath}`:''}.`,'Xác nhận thứ tự matcher và chính sách Spring Security bằng session QA.');
}
for (const row of actions.filter(item=>item.kind==='ux' && item.status==='FAIL')) {
  addFinding(row,'UI/UX',row.severity,row.expectedResult,row.detail,row.fix);
}

const mavenPath = path.join(runtime,'maven-suite.log');
const maven = fs.existsSync(mavenPath) ? fs.readFileSync(mavenPath,'utf8') : '';
const mavenOutput = maven.split(/\r?\n/).filter(line=>/Tests run:|^\[ERROR\]   \w+Test\./.test(line) && line.length<370);
write('playwright-report/maven-summary.txt', ['Maven suite (lược bỏ response dump, dữ liệu fixture và token)', ...mavenOutput].join('\n'));
const latestMaven = mavenOutput.filter(line=>/^\[(ERROR|INFO)\] Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+\s*$/.test(line)).at(-1);
const mavenTotals = latestMaven?.match(/Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)/)?.slice(1).map(Number);
const levels = ['Critical', 'High', 'Medium', 'Low'];
const report = [
  '# Full Website Audit · T3L Restaurant', '',
  '**Nhánh** `main` · **Commit** `aad2b75541019df4750b69ffa29cbc275d84bdbc` · **Ngày** 28/11/2026 (Việt Nam)', '',
  '## Kết quả và giới hạn', '',
  `- Playwright dùng Chromium để kiểm tra trực tiếp. Ma trận màn hình có **${layout.length}/${planned}** lần ghi nhận. ${statusSummary(layout)}. Chưa ghi nhận **${Math.max(0,planned-layout.length)}** lần.`,
  `- Thao tác và kiểm tra phân quyền có **${actions.length}** mục đã ghi nhận. ${statusSummary(actions)}.`,
  `- Tệp minh chứng full-page **${screenshots}** ảnh JPEG. Hồ sơ phiên trình duyệt, mật khẩu, OTP và log SMTP gốc nằm ngoài gói báo cáo.`,
  `- Maven trên schema QA riêng **${mavenTotals ? `${mavenTotals[0]} ca · PASS ${mavenTotals[0]-mavenTotals[1]-mavenTotals[2]-mavenTotals[3]} · FAIL ${mavenTotals[1]+mavenTotals[2]} · NOT TESTED ${mavenTotals[3]} (bộ test bỏ qua)` : 'NOT TESTED'}**. [Tóm tắt Maven đã lọc](playwright-report/maven-summary.txt).`,
  '- `PASS` xác nhận đúng các bước và điều kiện đã nêu. `FAIL` là lần chạy có sai khác. `BLOCKED` thiếu trạng thái hoặc fixture tiên quyết. `NOT TESTED` là hạng mục chưa chạy.', '',
  '## Môi trường', '',
  '- `git ls-remote origin refs/heads/main` xác nhận cùng commit với checkout. Trình duyệt kiểm tra HTTP công khai của dịch vụ có sẵn tại `localhost:8085`.',
  '- Toàn bộ thao tác ghi và ảnh nghiệp vụ dùng bản QA từ cùng checkout ở `127.0.0.1:18085`. MySQL mới ở `23307`, MongoDB mới ở `27028`. Maven dùng schema `t3l_audit_maven` độc lập.',
  '- SMTP chạy loopback và chỉ nhận `qa.invalid`, không relay. SePay dùng DEMO với feed giao dịch rỗng trên loopback. Playwright thay yêu cầu ảnh VietQR bằng ảnh QA. Thanh toán tiền mặt trong báo cáo là ghi thử vào sổ QA.',
  '- Sau khi Staff xác nhận, công cụ chỉnh ngày giờ của đúng lượt đặt tổng hợp trong DB QA sang khung nhận bàn. Các thao tác nhận bàn, thêm món, chốt đơn, xác nhận tiền mặt và đóng ca được thực hiện qua giao diện.',
  '- CSS/HTML và giao diện được đo tại 1920×1080, 1366×768, 768×1024, 390×844 cho cả Light và Dark. Thao tác nghiệp vụ theo ma trận riêng, không lặp giao dịch ở mọi viewport.',
  '- Báo cáo dùng kết quả cuối của mỗi ca. JSONL và thư mục ảnh lỗi có cả các lần chạy trước khi hiệu chỉnh fixture hoặc công cụ kiểm thử.',
  '- [Danh mục màn hình](PAGE_INVENTORY.md) · [Báo cáo Playwright](playwright-report/index.html) · [Kết quả thô đã lọc](playwright-report/results.jsonl).', '',
  '## Vấn đề quan sát được', '',
  `Mức độ lỗi sản phẩm đã có chứng cứ **${levels.map(level => `${level} ${[...findings.values()].filter(finding => finding.level===level).length}`).join(' · ')}**.`, '',
  '| ID | Mức | Route / role | Bước tái hiện | Mong đợi | Thực tế | Minh chứng | Đề xuất |',
  '|---|---|---|---|---|---|---|---|'
];
let sequence = 0;
for (const {row,type,level,expected,actual,remediation,profiles: variants} of findings.values()) {
  const steps = row.steps ?? `${row.role==='guest'?'Dùng phiên chưa đăng nhập':'Đăng nhập role tương ứng'}, mở route${variants.size ? ` ở ${[...variants].join(', ')}` : ''}. Kiểm tra ${type}.`;
  report.push(`| QA-${String(++sequence).padStart(2,'0')} | ${level} | \`${md(row.route)}\` / ${row.role} | ${md(steps)} | ${md(expected)} | ${md(actual)} | ${screenshot(row)} | ${md(remediation)} |`);
}
if (!sequence) report.push('| — | — | — | — | — | Chưa có vấn đề đủ chứng cứ từ các lần đã hoàn tất. | — | — |');
report.push('', '## Thao tác và các ca cần xác minh tiếp', '',
  '| Role | Ca | Route | Kết quả | Quan sát | Minh chứng |', '|---|---|---|---|---|---|');
for (const row of actions) report.push(`| ${row.role} | ${row.id} | \`${md(row.route)}\` | ${row.status} | ${md(row.detail ?? row.reason ?? (row.http ? `HTTP ${row.http}` : null))} | ${screenshot(row)} |`);
report.push('', '## Kết quả Maven cần rà soát', '',
  '- Nhóm CustomerAccount có 8 ca FAIL. Nhiều assertion còn yêu cầu đăng nhập hoặc đăng ký SMS OTP. Bộ khôi phục mật khẩu bằng email, gồm ca hết hạn, đã PASS. [Danh sách assertion](playwright-report/maven-summary.txt).',
  '- Nhóm AdminWorkspace có 3 ca FAIL liên quan chỉnh món, khóa tài khoản nhân viên và đồng bộ thiết lập thực đơn/khu vực. Playwright đã kiểm tra tạo tài khoản và phân quyền theo role. Các assertion đối ứng của ba ca Maven cần kiểm chứng riêng. [Danh sách assertion](playwright-report/maven-summary.txt).',
  '- Hai ca FAIL ở Reservation và ReservationLookup kỳ vọng từ chối khu vực không tồn tại. Kiểm tra hợp đồng xử lý lựa chọn khu vực và đồng bộ lại validation hoặc kỳ vọng test. [Danh sách assertion](playwright-report/maven-summary.txt).',
  '- Ca dùng Atlas Vector Search được bỏ qua vì QA thiếu cấu hình Atlas. Trạng thái **NOT TESTED**.',
  '', '## Chưa bao phủ', '',
  '- **NOT TESTED** toàn bộ nút/form CRUD quản trị, tải ảnh Cloudinary, xác thực Google thật, SMS thật, thanh toán chuyển khoản thật, callback SePay thật, cuộc hội thoại Gemini thật và mọi tổ hợp lỗi mạng/tải chậm.',
  '- **NOT TESTED** tất cả nhánh của phân trang, lọc kết hợp, toàn bộ modal ở tám tổ hợp, thao tác bàn nâng cao (chuyển bàn/hủy/khách không đến), mọi tình huống voucher, công nợ, hoàn cọc và hàng loạt yêu cầu thay đổi đặt bàn.',
  '- **NOT TESTED** kiểm tra thủ công từng ảnh để xác nhận màu/contrast/font trong mọi trạng thái. Ma trận ghi theme, font cơ sở, tràn document, request và lỗi JavaScript. Các lần chạy chưa có ảnh minh chứng được ghi trong kết quả chi tiết.', '',
  '## Đề xuất UI/UX', '',
  '- Ưu tiên điểm gãy 390 px và 768 px tại các bảng quản trị/nhân viên. Dùng cuộn cục bộ có chỉ báo nếu cần giữ đủ cột, hoặc hiển thị dòng dạng thẻ.',
  '- Với luồng đặt bàn nhiều bước, hiển thị rõ bước đang thực hiện và giữ nút tiếp tục ở vị trí ổn định sau khi dữ liệu báo giá cập nhật.',
  '- Tách lỗi tải tài nguyên bên ngoài thành một chỉ báo riêng để người dùng vẫn phân biệt được nội dung nghiệp vụ và ảnh minh họa tải lỗi.', '',
  'Các kiểm thử Maven thất bại được liệt kê tại tệp tóm tắt riêng. Một ca test thất bại hoặc selector không khớp cần kiểm chứng thêm trước khi quy kết lỗi sản phẩm.');
write('AUDIT_REPORT.md', report.join('\n'));

const html = `<!doctype html><html lang="vi"><meta charset="utf-8"><title>T3L Playwright Audit</title><style>body{font:14px system-ui;padding:24px;background:#f7f4ed;color:#222}table{width:100%;border-collapse:collapse;background:white}th,td{padding:8px;border:1px solid #ded4c3;text-align:left;vertical-align:top}th{position:sticky;top:0;background:#2a231c;color:white}input,select{padding:8px;margin:8px}td{overflow-wrap:anywhere}.fail{background:#fff0e9}.blocked{background:#f7f2e4}</style><h1>T3L · Kết quả Playwright Chromium</h1><p>${esc(statusSummary(rows))}. ${screenshots} ảnh full-page.</p><a href="../AUDIT_REPORT.md">Báo cáo chính</a> · <a href="results.jsonl">JSON Lines</a><br><input id="search" placeholder="Lọc role, route, màn hình"><select id="status"><option value="">Mọi kết quả</option>${labels.map(x=>`<option>${esc(x)}</option>`).join('')}</select><table><thead><tr><th>Role</th><th>Ca · route</th><th>Viewport · theme</th><th>Kết quả</th><th>HTTP</th><th>Quan sát</th><th>Ảnh</th></tr></thead><tbody>${rows.map(row=>`<tr class="${row.status.toLowerCase()}" data-status="${esc(row.status)}"><td>${esc(row.role)}</td><td>${esc(row.id)}<br><code>${esc(row.route)}</code></td><td>${esc(row.profile)}</td><td>${esc(row.status)}</td><td>${esc(row.http)}</td><td>${esc(row.reason ?? row.detail ?? '')}${row.events?.length?'<br>'+esc(JSON.stringify(row.events.slice(0,8))):''}${row.metrics?.scrollWidth>row.metrics?.width+2?`<br>Tràn ngang ${row.metrics.scrollWidth-row.metrics.width}px`:''}${row.screenshotError?'<br>'+esc(row.screenshotError):''}</td><td>${row.screenshot?`<a href="../${esc(row.screenshot)}">Xem</a>`:''}</td></tr>`).join('')}</tbody></table><script>const s=document.querySelector('#search'),t=document.querySelector('#status');function filter(){for(const r of document.querySelectorAll('tbody tr'))r.hidden=!!((t.value&&r.dataset.status!==t.value)||!r.innerText.toLowerCase().includes(s.value.toLowerCase()))}s.oninput=t.onchange=filter;</script></html>`;
write('playwright-report/index.html', html);
