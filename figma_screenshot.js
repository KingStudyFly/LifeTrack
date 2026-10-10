// 截取 LifeTrack 毛玻璃预览的所有页面为高清 PNG
const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');

const OUT = 'd:\\Coding\\AISummary\\LifeTrack\\figma_export';

const delay = ms => new Promise(r => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(OUT, { recursive: true });

  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 390 * 2, height: 844 * 2, deviceScaleFactor: 2 });

  const url = 'file:///d:/Coding/AISummary/LifeTrack/frosted_glass_preview.html';
  await page.goto(url, { waitUntil: 'load' });
  await delay(500);

  const pages = ['record', 'track', 'diary', 'insight', 'profile'];

  for (const mode of ['light', 'dark']) {
    if (mode === 'dark') {
      await page.evaluate(() => {
        document.body.classList.add('dark');
        document.getElementById('phoneFrame').classList.add('dark');
      });
      await delay(500);
    }

    for (const name of pages) {
      await page.evaluate((p) => {
        document.querySelectorAll('.nav-item').forEach(b => b.classList.remove('active'));
        document.querySelectorAll('.page').forEach(pp => pp.classList.remove('active'));
        document.querySelector(`.nav-item[data-page="${p}"]`).classList.add('active');
        document.getElementById(`page-${p}`).classList.add('active');
      }, name);
      await delay(400);

      await page.evaluate(() => {
        document.querySelectorAll('.controls, .annotation, .mode-indicator').forEach(el => {
          el.style.display = 'none';
        });
      });

      const phone = await page.$('.phone-frame');
      await phone.screenshot({
        path: path.join(OUT, `${mode}_${name}.png`),
        type: 'png'
      });
      console.log(`  ${mode}_${name}.png`);
    }

    if (mode === 'dark') {
      await page.evaluate(() => {
        document.body.classList.remove('dark');
        document.getElementById('phoneFrame').classList.remove('dark');
      });
      await delay(300);
    }
  }

  await browser.close();
  console.log('Done! All 10 PNG files saved to:', OUT);
}

main().catch(console.error);
