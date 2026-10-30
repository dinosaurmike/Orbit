const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const b = await chromium.launch();
  const out = process.argv[2];
  for (const [mode, w, h, file] of [['header',1280,640,'orbit-header.png'],['feature',1024,500,'play-feature-graphic.png']]) {
    const p = await b.newPage({ viewport: { width: w, height: h } });
    await p.goto('file://' + path.resolve('eyes.html') + `?w=${w}&h=${h}&mode=${mode}`);
    await p.waitForTimeout(300);
    await p.screenshot({ path: path.join(out, file) });
  }
  await b.close();
})();
