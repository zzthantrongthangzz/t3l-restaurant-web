const net = require('node:net');
const fs = require('node:fs');

// This fixture accepts only synthetic QA mail and never relays it.
const output = process.env.QA_OTP_FILE;
if (!output) throw new Error('QA_OTP_FILE is required');

net.createServer((socket) => {
  let buffer = '';
  let data = null;
  let recipient = '';
  socket.write('220 qa.invalid SMTP\r\n');
  socket.on('error', () => socket.destroy());
  socket.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    for (let end; (end = buffer.indexOf('\r\n')) >= 0;) {
      const line = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      if (data !== null) {
        if (line !== '.') { data += line + '\n'; continue; }
        let body = data.split('\n\n').slice(1).join('\n\n');
        if (/Content-Transfer-Encoding: base64/i.test(data)) body = Buffer.from(body.trim(), 'base64').toString('utf8');
        const code = body.match(/(?:^|\D)(\d{6})(?!\d)/)?.[1];
        if (code && recipient.endsWith('@qa.invalid')) fs.writeFileSync(output, code);
        data = null;
        socket.write('250 queued locally\r\n');
      } else if (/^EHLO|^HELO/i.test(line)) {
        socket.write('250 qa.invalid\r\n');
      } else if (/^MAIL FROM:/i.test(line)) {
        socket.write('250 OK\r\n');
      } else if (/^RCPT TO:/i.test(line)) {
        recipient = line.match(/<([^>]+)>/)?.[1]?.toLowerCase() ?? '';
        socket.write(recipient.endsWith('@qa.invalid') ? '250 OK\r\n' : '550 QA fixture accepts only qa.invalid\r\n');
      } else if (line === 'DATA') {
        data = '';
        socket.write('354 Start input\r\n');
      } else if (line === 'QUIT') {
        socket.end('221 Bye\r\n');
      } else {
        socket.write('250 OK\r\n');
      }
    }
  });
}).listen(12525, '127.0.0.1', () => process.stdout.write('QA SMTP sink ready on loopback\n'));
