// Renders the two pieces of lettering of the set with headless Chromium:
//   plate.png  512x128  white wordmark on black — replaces the plate artwork of the car
//                       (kept inside u 0.14..0.86: the rest of that texture is sampled as "no glow")
//   board.png  1152x128 one LED perimeter board, repeated along the walls
//   node wordmark.mjs <outDir>
import { chromium } from 'playwright-core';
import path from 'node:path';
const out = process.argv[2];
const b = await chromium.launch();
const font = "700 {S}px/1 'Helvetica Neue',Helvetica,Arial,sans-serif";
async function shot(name, w, h, html) {
  const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
  await p.setContent('<body style="margin:0;width:' + w + 'px;height:' + h + 'px;overflow:hidden">' + html + '</body>');
  await p.screenshot({ path: path.join(out, name) }); await p.close();
}
await shot('plate.png', 512, 128, '<div style="height:128px;background:#000;display:grid;place-items:center"><div style="font:' + font.replace('{S}', 46) + ';letter-spacing:7px;color:#fff;padding-left:7px">SCENEVIEW</div></div>');
await shot('board.png', 1152, 128, '<div style="height:128px;background:linear-gradient(90deg,#04102e,#0a2a6a 50%,#04102e);display:flex;align-items:center;justify-content:center;gap:54px">'
  + '<div style="width:150px;height:14px;background:linear-gradient(90deg,transparent,#35c8ff)"></div>'
  + '<div style="font:' + font.replace('{S}', 84) + ';letter-spacing:14px;color:#fff;padding-left:14px">SCENEVIEW</div>'
  + '<div style="width:150px;height:14px;background:linear-gradient(270deg,transparent,#35c8ff)"></div></div>');
await b.close();
