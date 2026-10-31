// Renders eyes-animated.html to docs/images/orbit-header.gif (README) and a still
// orbit-header.png (GitHub social preview, which only ever shows a still image).
// Needs Playwright (with Chromium) and ffmpeg:  node docs/images/source/render-gif.js
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const W = 960, H = 480, FPS = 20, LOOP = 6;   // the README shows it about this size anyway
const here = __dirname;
const frames = path.join(here, 'frames');

(async () => {
  fs.rmSync(frames, { recursive: true, force: true });
  fs.mkdirSync(frames);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  await page.goto(`file://${path.join(here, 'eyes-animated.html')}?w=${W}&h=${H}`);
  await page.waitForFunction(() => window.ready);
  for (let i = 0; i < FPS * LOOP; i++) {
    await page.evaluate(t => window.draw(t), i / FPS);
    await page.screenshot({ path: path.join(frames, `f${String(i).padStart(4, '0')}.png`) });
  }
  // The still: full size, eyes open and centred
  await page.setViewportSize({ width: 1280, height: 640 });
  await page.goto(`file://${path.join(here, 'eyes-animated.html')}?w=1280&h=640`);
  await page.waitForFunction(() => window.ready);
  await page.evaluate(() => window.draw(0.4));
  await page.screenshot({ path: path.join(here, '..', 'orbit-header.png') });
  await browser.close();

  // One shared palette for all frames keeps the glow smooth and the file small
  const palette = path.join(frames, 'palette.png');
  const input = ['-framerate', String(FPS), '-i', path.join(frames, 'f%04d.png')];
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...input, '-vf', 'palettegen=max_colors=192:stats_mode=full', palette]);
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', ...input, '-i', palette, '-lavfi',
    'paletteuse=dither=sierra2_4a:diff_mode=rectangle', '-loop', '0', path.join(here, '..', 'orbit-header.gif')]);
  fs.rmSync(frames, { recursive: true, force: true });
  console.log('Wrote docs/images/orbit-header.gif and orbit-header.png');
})();
