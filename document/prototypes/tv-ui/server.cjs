// Throwaway preview server. Serves only this prototype, bound to loopback.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const pages = new Set(['index.html', 'files-prototype.html', 'player-controls-prototype.html', 'deep-pages.html', 'previews/player-controls-baseline.png']);
const port = Number(process.argv[2] || 4173);
http.createServer((request, response) => {
  const page = request.url.split('?')[0].slice(1) || 'index.html';
  if (!pages.has(page)) {
    response.writeHead(404);
    response.end();
    return;
  }
  response.writeHead(200, { 'Content-Type': page.endsWith('.png') ? 'image/png' : 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(fs.readFileSync(path.join(__dirname, page)));
}).listen(port, '127.0.0.1', () => console.log(`DDPlayTV prototype: http://127.0.0.1:${port}`));
