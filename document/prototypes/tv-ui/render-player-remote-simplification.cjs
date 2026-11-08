// Reproducible HTML review captures; no Android/device verification is implied.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {createRequire} = require('node:module');
const runtimeRoot = process.env.DDPLAY_RUNTIME_MODULES || path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const runtimeRequire = createRequire(path.join(runtimeRoot, 'package.json'));
const {chromium} = runtimeRequire('playwright');
const sharp = runtimeRequire('sharp');
const output = path.join(__dirname, 'previews/player-remote-simplification-2026-11-08');
const url = pathToFileURL(path.join(__dirname, 'player-bottom-refinement-prototype.html')).href;
const observations = [], errors = [];
function check(condition, message) { if (!condition) throw new Error(message); }
async function main() {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:810},deviceScaleFactor:1});
    page.on('pageerror', error => errors.push(error.message));
    async function navigate(query = {}) {
      await page.goto(url + '?' + new URLSearchParams({review:'remote',variant:'open',layout:'compact',focus:'play',capture:'1',...query}));
      await page.evaluate(() => document.fonts.ready);
      await page.waitForTimeout(180);
      check(await page.evaluate(async () => { const img = new Image(); img.src = getComputedStyle(document.querySelector('.video')).backgroundImage.slice(5,-2); await img.decode(); return img.naturalWidth > 0; }), 'Missing source frame');
    }
    async function record(name, capture = true) {
      const result = await page.evaluate(() => ({...remoteReview.snapshot(),
        subtitleBounds:document.querySelector('.video-subtitle').getBoundingClientRect().toJSON(),
        timeline:document.querySelector('.seek')?.getBoundingClientRect().toJSON(),
        controls:[...document.querySelectorAll('.control')].map(node => ({key:node.dataset.key,disabled:node.disabled,rect:node.getBoundingClientRect().toJSON()})),
        panelBounds:document.querySelector('.remote-panel')?.getBoundingClientRect().toJSON(),
        playAppearance:(() => { const node=document.querySelector('.control.play'); if(!node)return null; const style=getComputedStyle(node); return {background:style.backgroundColor,image:style.backgroundImage,color:style.color,width:style.width,shadow:style.boxShadow,label:node.getAttribute('aria-label'),pressed:node.classList.contains('pressed'),selected:node.getAttribute('aria-pressed')}; })()
      }));
      observations.push({name,...result});
      check(result.controls.every(node => node.rect.x >= 0 && node.rect.right <= 1440 && node.rect.top > result.timeline.bottom && node.rect.bottom < 810), name + ': controls escaped bounds');
      if (result.layout === 'retain') check(result.controls.filter(node => ['previous','next'].includes(node.key)).every(node => node.rect.width === 58 && node.rect.height === 58), name + ': episode focus must be a 58px circle');
      if (result.controlsVisible && !['loading','error'].includes(result.scene) && !result.empty && result.subtitleBounds.width) {
        check(result.subtitleBounds.x === 488 && result.subtitleBounds.y === 720, name + ': subtitle moved');
        check(result.controls.every(node => node.rect.right <= result.subtitleBounds.left || node.rect.left >= result.subtitleBounds.right), name + ': control crosses subtitle');
      }
      if (result.panelBounds) check(result.panelBounds.x >= 0 && result.panelBounds.top >= 0 && result.panelBounds.right <= 1440 && result.panelBounds.bottom < result.timeline.top, name + ': panel crosses timeline');
      if (capture) await page.screenshot({path:path.join(output,name + '.png')});
      return result;
    }
    for (const layout of ['baseline','retain','compact']) {
      await navigate({layout});
      const result = await record(layout + '-full');
      check(result.controls.length === {baseline:9,retain:7,compact:5}[layout], 'Wrong action count');
      await page.screenshot({path:path.join(output,layout + '-bottom.png'),clip:{x:0,y:595,width:1440,height:215}});
    }
    for (const scene of ['playing','first','last','single','unseekable','loading','error']) {
      await navigate({scene});
      const result = await record('compact-' + scene);
      check(result.scene === scene, 'Scene query was not restored: ' + scene);
      check(result.controls.length === (scene === 'single' ? 4 : 5), 'Scene action count differs');
      if (['loading','error'].includes(scene)) check(await page.locator('.remote-status').isVisible(), 'Missing status feedback: ' + scene);
    }
    for (const scene of ['first','last','single','unseekable']) {
      await navigate({layout:'retain',scene});
      const result = await record('retain-' + scene, false);
      if (scene === 'first') check(!result.keys.includes('previous'), 'First episode retains previous focus');
      if (scene === 'last') check(!result.keys.includes('next'), 'Last episode retains next focus');
    }
    for (const background of ['bright','dark']) { await navigate({background}); await record('compact-' + background); }
    await navigate({font:'1.3',focus:'danmaku',danmaku:'off'});
    await record('compact-font130');
    await navigate();
    await page.keyboard.press('ArrowRight');
    check((await record('dpad-picker',false)).focus === 'picker', 'Compact focus order incorrect');
    await page.keyboard.press('Enter');
    check((await record('episodes')).panelIndex === 49, 'Picker must focus current episode');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    let result = await record('episode-confirm',false);
    check(result.episode === 51 && result.focus === 'picker' && result.position === 0, 'Episode selection did not restore caller');
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await record('subtitle');
    await page.keyboard.press('ArrowDown');
    await record('subtitle-browse',false);
    check(await page.evaluate(() => remoteReview.model.subtitle === '简体中文'), 'Browsing applied subtitle');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Enter');
    check((await record('subtitle-off',false)).subtitle === '关', 'Off selection not applied');
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    check((await page.evaluate(() => remoteReview.snapshot())).subtitle === '简体中文', 'Track selection did not enable subtitles');
    await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');
    check((await record('subtitle-back',false)).focus === 'subtitle', 'Subtitle return focus lost');
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    await record('audio');
    await page.keyboard.press('Escape');
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Enter');
    result = await record('danmaku-off');
    check(result.focus === 'danmaku' && await page.locator('[data-key="danmaku"]').getAttribute('aria-pressed') === 'false', 'Toggle state/focus lost');
    await page.keyboard.press('Escape');
    const before = await page.evaluate(() => remoteReview.snapshot());
    await page.keyboard.press('ArrowRight');
    result = await record('hidden-seek');
    check(result.position === before.position + 10 && !result.controlsVisible, 'Hidden DPAD seek must be ten seconds');
    await page.keyboard.press('Enter');
    result = await record('hidden-play-toggle',false);
    check(result.playing !== before.playing && !result.controlsVisible, 'Hidden center must toggle, not reveal controls');
    await page.keyboard.press('ArrowDown');
    check((await record('restore-focus',false)).focus === 'danmaku', 'Bottom focus not restored');
    for (const focus of ['subtitle','audio']) {
      await navigate({tracks:'empty',focus});
      await page.keyboard.press('Enter');
      await record('empty-' + focus);
      await page.keyboard.press('Escape');
      check((await page.evaluate(() => remoteReview.snapshot())).focus === focus, 'Empty panel traps focus');
    }
    await navigate({font:'1.3',focus:'subtitle'});
    await page.keyboard.press('Enter');
    await record('subtitle-font130');
    await navigate({scene:'unseekable'});
    await page.keyboard.press('Escape');
    await page.keyboard.press('ArrowRight');
    check((await record('unseekable-shortcut',false)).position === 582, 'Unseekable position changed');
    for (const playstyle of ['original','focus']) {
      for (const scene of ['middle','playing']) {
        for (const focus of ['subtitle','play']) {
          await navigate({layout:'retain',playstyle,scene,focus});
          const name = `play-${playstyle}-${scene}-${focus}`;
          const result = await record(name);
          check(result.controls.length === 7, 'Selected B must retain seven actions');
          check(result.playAppearance.label === (scene === 'playing' ? '暂停' : '播放'), 'Play icon/action mismatch');
          check(result.playAppearance.selected === null, 'Play action must not expose a persistent selected state');
          if (playstyle === 'focus') {
            check(result.playAppearance.width === '58px', 'Play should match track-button size');
            check(result.playAppearance.color === (focus === 'play' ? 'rgb(23, 24, 29)' : 'rgb(245, 245, 247)'), 'Play foreground must follow focus');
            check((result.playAppearance.background === 'rgb(245, 245, 247)') === (focus === 'play'), 'Play fill must follow focus');
          }
          await page.screenshot({path:path.join(output,name+'-bottom.png'),clip:{x:0,y:595,width:1440,height:215}});
          await page.screenshot({path:path.join(output,name+'-detail.png'),clip:{x:40,y:685,width:430,height:120}});
        }
      }
    }
    await navigate({layout:'retain',playstyle:'focus'});
    await page.keyboard.down('Enter');
    let press = await record('play-pressed');
    check(press.playing && press.playAppearance.pressed, 'Press must toggle and show transient feedback');
    await page.keyboard.down('Enter');
    check((await page.evaluate(() => remoteReview.snapshot())).playing, 'Holding center must not repeat toggle');
    await page.keyboard.up('Enter');
    check(!(await record('play-released',false)).playAppearance.pressed, 'Release must clear pressed appearance');
    for (const key of ['previous','next','picker','subtitle','audio','danmaku']) {
      await page.keyboard.press('ArrowRight');
      const result = await record('b-focus-'+key,false);
      check(result.focus === key, 'B focus order changed');
      check(result.playAppearance.background !== 'rgb(245, 245, 247)', 'Unfocused play still resembles focus');
    }
    await navigate({layout:'retain',playstyle:'focus',focus:'subtitle'});
    await page.keyboard.press('Enter');
    check((await record('play-panel-open',false)).playAppearance.background !== 'rgb(245, 245, 247)', 'Panel opens with a false play focus');
    for (const background of ['bright','dark']) {
      await navigate({layout:'retain',playstyle:'focus',focus:'subtitle',background});
      await record('b-play-'+background);
    }
    await navigate({layout:'retain',playstyle:'focus',focus:'play',font:'1.3'});
    await record('b-play-font130');
    for (const focus of ['previous','next']) {
      for (const font of ['1','1.3']) {
        await navigate({layout:'retain',playstyle:'focus',focus,font});
        const name = `episode-${focus}-round-font${font}`;
        const result = await record(name);
        check(result.focus === focus, 'Episode focus not restored');
        await page.screenshot({path:path.join(output,name+'-detail.png'),clip:{x:40,y:685,width:430,height:120}});
        const left = result.controls.filter(node => ['play','previous','next','picker'].includes(node.key));
        check(left.every((node,index) => index === 0 || node.rect.left - left[index - 1].rect.right >= 10), 'Episode controls overlap or lose spacing');
      }
    }
    await page.setViewportSize({width:1504,height:1200});
    await navigate({capture:'0',layout:'retain',playstyle:'focus',focus:'subtitle'});
    await page.screenshot({path:path.join(output,'review-page.png'),fullPage:true});
    await page.locator('#remote-playstyle').selectOption('original');
    await page.reload();
    check(await page.evaluate(() => remoteReview.model.playstyle === 'original'), 'Play-style URL restore failed');
    await page.locator('#remote-layout').selectOption('retain');
    check(await page.evaluate(() => remoteReview.model.layout === 'retain'), 'Toolbar layout switch failed');
    await page.locator('#next-variant').click();
    check(await page.evaluate(() => remoteReview.model.layout === 'baseline'), 'Floating layout switch failed');
    await page.reload();
    check(await page.evaluate(() => remoteReview.model.layout === 'baseline'), 'URL layout restore failed');
    check(errors.length === 0, 'Runtime errors: ' + errors.join('; '));
  } finally { await browser.close(); }
  const names = [['baseline','当前 · 九项'],['retain','B · 七项，保留直接换集（已选布局）'],['compact','A · 五项备选（未采用）']];
  const svg = Buffer.from(`<svg width="1504" height="925" xmlns="http://www.w3.org/2000/svg"><rect width="1504" height="925" fill="#101013"/><g fill="#f5f5f7" font-family="Microsoft YaHei, sans-serif"><text x="32" y="45" font-size="26">播放底栏简化 · 同画面对照</text>${names.map(([,label],i) => `<text x="32" y="${96+i*280}" font-size="20">${label}</text>`).join('')}<text x="32" y="907" font-size="13" fill="#a9a9b5">网页设计预览 · 保留上方时间轴与原字幕位置 · 等待用户确认后实施原生界面</text></g></svg>`);
  await sharp(svg).composite(names.map(([name],i) => ({input:path.join(output,name+'-bottom.png'),left:32,top:112+i*280}))).png().toFile(path.join(output,'comparison.png'));
  const states = ['compact-single','compact-loading','compact-error','compact-dark','empty-subtitle','empty-audio','subtitle-font130','hidden-seek'];
  const sheet = Buffer.from(`<svg width="1440" height="1740" xmlns="http://www.w3.org/2000/svg"><rect width="1440" height="1740" fill="#101013"/>${states.map((name,i) => `<text x="${16+i%2*720}" y="${22+Math.floor(i/2)*435}" fill="#ddd" font-size="15" font-family="Segoe UI">${name}</text>`).join('')}</svg>`);
  await sharp(sheet).composite(await Promise.all(states.map(async (name,i) => ({input:await sharp(path.join(output,name+'.png')).resize(720,405).toBuffer(),left:i%2*720,top:30+Math.floor(i/2)*435})))).png().toFile(path.join(output,'states.png'));
  const playComparison = Buffer.from('<svg width="1504" height="654" xmlns="http://www.w3.org/2000/svg"><rect width="1504" height="654" fill="#101013"/><g font-family="Microsoft YaHei, sans-serif" fill="#eeeef4"><text x="32" y="42" font-size="26">B 稿 · 焦点同在「字幕」，播放键不再争夺高亮</text><text x="32" y="90" font-size="20">原样式：播放键常亮，容易看成两个焦点</text><text x="32" y="372" font-size="20">新样式：播放键回到半透明常态，仅当前焦点提亮</text><text x="32" y="636" font-size="13" fill="#a9a9b5">设计预览 · 七项布局已选定 · 播放键新样式待确认后与底栏一起实施</text></g></svg>');
  await sharp(playComparison).composite([{input:path.join(output,'play-original-middle-subtitle-bottom.png'),left:32,top:108},{input:path.join(output,'play-focus-middle-subtitle-bottom.png'),left:32,top:388}]).png().toFile(path.join(output,'play-focus-comparison.png'));
  const playStates = [['middle','subtitle','已暂停 · 未聚焦'],['middle','play','已暂停 · 已聚焦'],['playing','subtitle','播放中 · 未聚焦'],['playing','play','播放中 · 已聚焦']];
  const playSheet = Buffer.from(`<svg width="940" height="410" xmlns="http://www.w3.org/2000/svg"><rect width="940" height="410" fill="#101013"/>${playStates.map(([, ,label],i) => `<text x="${20+i%2*470}" y="${38+Math.floor(i/2)*200}" fill="#eeeef4" font-size="20" font-family="Microsoft YaHei">${label}</text>`).join('')}</svg>`);
  await sharp(playSheet).composite(playStates.map(([scene,focus],i) => ({input:path.join(output,`play-focus-${scene}-${focus}-detail.png`),left:20+i%2*470,top:60+Math.floor(i/2)*200}))).png().toFile(path.join(output,'play-focus-states.png'));
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({date:'2026-11-08',kind:'HTML design preview only',errors,observations},null,2)+'\n');
  console.log(JSON.stringify({output,observations:observations.length,runtimeErrors:errors.length},null,2));
}
main().catch(error => {console.error(error);process.exitCode=1;});
