// node shots.mjs <w> <h> <outDir> <name=query>... — one browser, one PNG per query (scene.html), then a contact sheet
import { chromium } from 'playwright-core'; import { execFileSync } from 'node:child_process'; import path from 'node:path';
const [w, h, outDir, ...items] = process.argv.slice(2);
const browser = await chromium.launch({ headless: true, args: ['--use-angle=metal', '--enable-gpu', '--ignore-gpu-blocklist'] });
const page = await (await browser.newContext({ viewport: { width: +w, height: +h } })).newPage();
const errors = []; page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') errors.push(m.text()); }); page.on('pageerror', e => errors.push(e.message));
const files = [];
for (const it of items) {
  const k = it.indexOf('='), name = it.slice(0, k), query = it.slice(k + 1);
  await page.goto('http://127.0.0.1:8377/__probe/scene.html?' + query);
  await page.waitForFunction('window.__first > 0 && window.sv && window.sv._resourceLoads.size === 0', null, { timeout: 90000 }); await page.waitForTimeout(+(process.env.WAIT || 2500));
  const f = path.join(outDir, name + '.png'); await page.screenshot({ path: f }); files.push(f);
}
await browser.close();
if (errors.length) console.log('ERRORS', errors.slice(0, 8));
const cols = files.length > 1 ? 2 : 1, rows = Math.ceil(files.length / cols), cw = +(process.env.CELL || 1000) / cols | 0;
const args = ['-loglevel', 'error', '-y']; for (const f of files) args.push('-i', f);
const sheet = path.join(outDir, 'sheet.png');
if (files.length === 1) args.push('-vf', 'scale=' + cw + ':-1', sheet);
else args.push('-filter_complex', files.map((_, i) => '[' + i + ':v]scale=' + cw + ':-1[s' + i + ']').join(';') + ';' + files.map((_, i) => '[s' + i + ']').join('') + 'xstack=inputs=' + files.length + ':layout=' + files.map((_, i) => (i % cols ? 'w0' : '0') + '_' + (i < cols ? '0' : Array.from({ length: Math.floor(i / cols) }, (_, k) => 'h' + k * cols).join('+'))).join('|'), sheet);
execFileSync('ffmpeg', args); console.log(sheet);
