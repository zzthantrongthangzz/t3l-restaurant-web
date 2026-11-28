const http = require('node:http');

// The application only sees an empty synthetic transaction feed.
http.createServer((request, response) => {
  if (request.method !== 'GET' || !request.url.startsWith('/transactions?') ||
      request.headers.authorization !== 'Bearer qa-local-sink-only') {
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, { 'Content-Type':'application/json' }).end('{"data":[]}');
}).listen(12526,'127.0.0.1',()=>process.stdout.write('QA SePay feed ready on loopback\n'));
