const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { chromium } = require('C:/Users/Administrator.DESKTOP-1KCKBJ1/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = 'E:/Project/DDPlayTV';
const directory = path.join(root, 'document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02');
const url = 'http://127.0.0.1:18106/document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/index.html';
const sha = p => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');

(async () => {
    const evidence = JSON.parse(fs.readFileSync(path.join(directory, 'verification.json'), 'utf8'));
    for (const item of evidence.images) {
        assert.equal(sha(path.join(directory, item.path)), item.sha256, item.path);
        assert.equal(sha(path.join(root, item.source)), item.sha256, item.source);
    }
    for (const item of evidence.logs) assert.equal(sha(path.join(directory, item.path)), item.sha256, item.path);
    for (const [p, hash] of Object.entries({ ...evidence.sourceSha256, ...evidence.apkSha256 })) assert.equal(sha(path.join(root, p)), hash, p);
    assert.equal(sha(path.join(root, 'document/prototypes/tv-ui/container-hierarchy-prototype.html')), evidence.prototypeSha256);
    assert.equal(evidence.nativeImageCount, 92);
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const errors = [];
    const decoded = new Set();
    const links = new Set();
    try {
        const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
        page.on('pageerror', error => errors.push(error.message));
        assert.equal((await page.goto(url)).status(), 200);
        assert.equal(await page.locator('figure').count(), 8);
        for (const font of ['1', '1.3']) {
            await page.locator('#font').selectOption(font);
            for (const [view, count] of [['compare', 8], ['after', 38], ['before', 8]]) {
                await page.locator('#view').selectOption(view);
                assert.equal(await page.locator('figure').count(), count);
                const dimensions = await page.locator('img').evaluateAll(async images => {
                    images.forEach(image => image.loading = 'eager');
                    await Promise.all(images.map(image => image.decode()));
                    return images.map(image => [image.src, image.naturalWidth, image.naturalHeight]);
                });
                dimensions.forEach(([src, w, h]) => {
                    decoded.add(src);
                    assert.deepEqual([w, h], [1440, 810], src);
                });
                for (const href of await page.locator('a').evaluateAll(nodes => nodes.map(a => a.href))) links.add(href);
            }
        }
        assert.equal(decoded.size, 92);
        await page.locator('#view').selectOption('after');
        await page.locator('#group').selectOption('C07');
        assert.equal(await page.locator('figure').count(), 5);
        await page.locator('#group').selectOption('C08');
        assert.equal(await page.locator('figure').count(), 4);
        await page.locator('#view').selectOption('compare');
        assert.equal(await page.locator('figure').count(), 1);
        await page.locator('#group').selectOption('all');
        await page.locator('#font').selectOption('1');
        const [popup] = await Promise.all([page.waitForEvent('popup'), page.locator('figure a').first().click()]);
        await popup.waitForLoadState();
        assert(popup.url().endsWith('/images/before-font1-CacheManagerActivity.png'));
        await popup.close();
        for (const link of links) assert.equal((await page.request.get(link)).status(), 200, link);
        await page.screenshot({ path: path.join(directory, 'gallery-browser.png') });
        await page.setViewportSize({ width: 760, height: 1000 });
        assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
        assert.equal(await page.locator('figure').count(), 8);
        await page.screenshot({ path: path.join(directory, 'gallery-mobile.png') });
        assert.deepEqual(errors, []);
        const result = {
            date: '2026-11-02', url, nativeImagesDecoded: decoded.size, comparisonGroups: 16,
            afterStatesPerFont: { '1.0': 38, '1.3': 38 }, fonts: ['1.0', '1.3'], linksChecked: links.size,
            fullSizePopup: true, responsive760: true, pageErrors: errors,
            sourceApkImageLogHashesMatch: true,
            indexSha256: sha(path.join(directory, 'index.html')),
            verificationSha256: sha(path.join(directory, 'verification.json'))
        };
        fs.copyFileSync(__filename, path.join(directory, 'evidence/verify-gallery.cjs'));
        fs.writeFileSync(path.join(directory, 'gallery-verification.json'), JSON.stringify(result, null, 2) + '\n');
        process.stdout.write(JSON.stringify(result) + '\n');
    } finally {
        await browser.close();
    }
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
