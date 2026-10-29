// node serve.mjs <siteRoot> <probeRoot> [port] — static server, gzip for text/wasm like GitHub Pages
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path'; import zlib from 'node:zlib';
const [site, probe, port = '8377'] = process.argv.slice(2);
const types = { html: 'text/html; charset=utf-8', js: 'text/javascript', mjs: 'text/javascript', css: 'text/css', json: 'application/json', wasm: 'application/wasm', glb: 'model/gltf-binary', ktx: 'image/ktx', png: 'image/png', webp: 'image/webp', jpg: 'image/jpeg', svg: 'image/svg+xml', woff2: 'font/woff2', map: 'application/json', filamat: 'application/octet-stream' };
const gz = new Set(['html', 'js', 'mjs', 'css', 'json', 'wasm', 'svg', 'map', 'glb']);
http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  let file = p.startsWith('/__probe/') ? path.join(probe, p.slice(9)) : path.join(site, p);
  if (p.endsWith('/')) file = path.join(file, 'index.html');
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404); res.end('404'); return; }
    const ext = path.extname(file).slice(1);
    const h = { 'content-type': types[ext] || 'application/octet-stream', 'cache-control': 'no-store' };
    if (gz.has(ext) && /gzip/.test(req.headers['accept-encoding'] || '')) { h['content-encoding'] = 'gzip'; buf = zlib.gzipSync(buf, { level: 6 }); }
    h['content-length'] = buf.length; res.writeHead(200, h); res.end(buf);
  });
}).listen(+port, '127.0.0.1', () => console.log('serving on ' + port));
