// Throwaway preview server. Serves only this prototype, bound to loopback.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const file = path.join(__dirname, 'index.html');
http.createServer((request, response) => {
  if (request.url.split('?')[0] !== '/' && request.url.split('?')[0] !== '/index.html') {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(fs.readFileSync(file));
}).listen(4173, '127.0.0.1', () => console.log('DDPlayTV prototype: http://127.0.0.1:4173'));
