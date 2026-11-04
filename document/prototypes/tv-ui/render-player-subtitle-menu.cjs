// Design observations only. Run: node document/prototypes/tv-ui/render-player-subtitle-menu.cjs
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {createRequire} = require('node:module');
const runtimeRoot = process.env.DDPLAY_RUNTIME_MODULES || path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const runtimeRequire = createRequire(path.join(runtimeRoot, 'package.json'));
const {chromium} = runtimeRequire('playwright');
const sharp = runtimeRequire('sharp');
const output = path.join(__dirname, 'previews/player-subtitle-menu-marquee-2026-11-03');
const route = process.env.DDPLAY_PREVIEW_URL || pathToFileURL(path.join(__dirname, 'player-bottom-refinement-prototype.html')).href;
const observations = [], errors = [];
const check = (condition, message) => {if (!condition) throw new Error(message);};
async function main() {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:810},deviceScaleFactor:1});
    page.on('pageerror', error => errors.push(error.message));
    async function navigate(query = {}) {
      await page.goto(route+'?'+new URLSearchParams({variant:'open',review:'subtitle',layout:'vertical',capture:'1',scene:'middle',subtitle:'zh',panel:'quick',...query}));
      await page.evaluate(() => document.fonts.ready);
      await page.locator('.video').evaluate(async element => {
        const image = new Image();
        image.src = getComputedStyle(element).backgroundImage.slice(5,-2);
        await image.decode();
      });
    }
    async function record(name, capture = true) {
      const result = await page.evaluate(() => {
        const rect = selector => document.querySelector(selector)?.getBoundingClientRect().toJSON();
        const scroller = document.querySelector('.subtitle-options');
        return {
          state:{...window.subtitleMenuPreview?.state},bottomFocus:state.focus,controlsVisible,
          seek:rect('.seek'),videoSubtitle:rect('.video-subtitle'),panel:rect('.subtitle-menu'),
          actions:[...document.querySelectorAll('.control')].map(button => ({key:button.dataset.key,rect:button.getBoundingClientRect().toJSON()})),
          options:[...document.querySelectorAll('[data-subtitle]')].map(button => ({
            id:button.dataset.subtitle,name:button.textContent,selected:button.getAttribute('aria-checked'),focused:button.classList.contains('focused'),
            rect:button.getBoundingClientRect().toJSON(),nameRect:button.querySelector('.subtitle-name').getBoundingClientRect().toJSON(),
            checkRect:button.querySelector('.subtitle-check').getBoundingClientRect().toJSON(),
            direction:getComputedStyle(button.querySelector('.subtitle-name')).direction,
            marquee:button.querySelector('.subtitle-name').dataset.marquee,
            textTransform:getComputedStyle(button.querySelector('.subtitle-name-text')).transform
          })),
          subtitleVisible:getComputedStyle(document.querySelector('.video-subtitle')).visibility === 'visible',
          scroll:scroller ? {left:scroller.scrollLeft,width:scroller.clientWidth,total:scroller.scrollWidth,top:scroller.scrollTop,height:scroller.clientHeight,totalHeight:scroller.scrollHeight,rect:scroller.getBoundingClientRect().toJSON()} : null,
          longName:document.querySelector('.subtitle-full-name')?.textContent
        };
      });
      observations.push({name,...result});
      if (capture) await page.screenshot({path:path.join(output,name+'.png')});
      return result;
    }
    await navigate({review:'',focus:'subtitle'});
    const baseline = await record('confirmed-bottom-baseline', false);
    const scenes = [
      ['on-selected',{}],
      ['focus-away',{option:'en'}],
      ['off-selected',{subtitle:'off'}],
      ['single-track',{tracks:'single'}],
      ['many-tracks',{tracks:'many',option:'file',subtitle:'file'}],
      ['german',{tracks:'many',subtitle:'de',option:'de'}],
      ['arabic',{tracks:'many',subtitle:'ar',option:'ar'}],
      ['empty',{tracks:'empty'}],
      ['loading',{tracks:'loading'}],
      ['error',{tracks:'error'}],
      ['details',{panel:'details'}],
      ['closed-on',{panel:'closed'}],
      ['closed-off',{panel:'closed',subtitle:'off'}],
      ['font130',{font:'1.3',option:'dual'}],
      ['long-name-font130',{tracks:'many',font:'1.3',option:'file',subtitle:'file'}],
      ['german-font130',{tracks:'many',font:'1.3',option:'de',subtitle:'de'}],
      ['arabic-font130',{tracks:'many',font:'1.3',option:'ar',subtitle:'ar'}],
      ['bright',{background:'bright',option:'en'}],
      ['dark',{background:'dark',option:'en'}]
    ];
    for (const [name, query] of scenes) {
      await navigate(query);
      const result = await record(name);
      check(result.actions.length === 9, name+': an existing bottom action was lost');
      check(result.seek.y === baseline.seek.y && result.seek.x === baseline.seek.x, name+': confirmed timeline moved');
      check(result.videoSubtitle.y === baseline.videoSubtitle.y && result.videoSubtitle.x === baseline.videoSubtitle.x, name+': subtitle placement moved');
      if (result.panel) {
        check(result.panel.bottom < result.seek.y-20, name+': quick panel overlaps the timeline');
        check(result.panel.x >= 60 && result.panel.right <= 1380 && result.panel.y > 150, name+': panel left the safe area');
        check(result.options.filter(option => option.selected === 'true').length === 1, name+': selection is not exclusive');
        check(result.options.filter(option => option.focused).length === 1, name+': missing or duplicate focus');
        const first = result.options[0];
        for (const option of result.options) {
          check(Math.abs(option.rect.width-first.rect.width) < 1 && Math.abs(option.rect.x-first.rect.x) < 1, name+': rows are not equally wide or aligned');
          check(Math.abs(option.rect.height-first.rect.height) < 1, name+': rows are not equally high');
          check(Math.abs(option.checkRect.x-first.checkRect.x) < 1, name+': checkmark columns are not aligned');
          check(option.nameRect.right < option.checkRect.x && option.nameRect.x >= option.rect.x && option.nameRect.right <= option.rect.right, name+': text overlaps checkmark column or row boundary');
        }
        const focused = result.options.find(option => option.focused);
        check(focused.rect.top >= result.scroll.rect.top-1 && focused.rect.bottom <= result.scroll.rect.bottom+1, name+': focused option is clipped by scrolling');
        check(result.scroll.total <= result.scroll.width, name+': horizontal overflow remains');
      }
      if (name === 'focus-away') check(result.state.selected === 'zh' && result.state.focused === 'en', 'Focus movement changed selected track');
      if (name === 'off-selected') check(!result.subtitleVisible, 'Off state did not hide the preview subtitle');
      if (name === 'many-tracks') {
        check(result.scroll.top > 0, 'Long-track focus did not scroll vertically');
        check(result.options.find(option => option.id === 'file').nameRect.height < 40, 'Long filename wraps instead of remaining single-line');
      }
      if (name.startsWith('arabic')) check(result.options.find(option => option.id === 'ar').direction === 'rtl', 'Arabic text lost its reading direction');
    }
    for (const [name,option,sign] of [['file','file',-1],['rtl','ar',1]]) {
      await navigate({tracks:'many',option,subtitle:option});
      const before = await record('marquee-'+name+'-waiting');
      check(before.options.find(item => item.id === option).marquee === 'waiting', 'Long-name marquee did not wait for dwell');
      await page.waitForFunction(() => document.querySelector('.subtitle-name[data-marquee="running"]'));
      await page.waitForTimeout(450);
      const moving = await record('marquee-'+name+'-moving');
      const transform = moving.options.find(item => item.id === option).textTransform;
      check(transform !== 'none' && Math.sign(Number(transform.split(',')[4])) === sign, 'Marquee moves in the wrong reading direction');
      check(moving.options.filter(item => item.marquee === 'running').length === 1, 'More than one name is scrolling');
      check(moving.options.find(item => item.id === option).checkRect.x === before.options.find(item => item.id === option).checkRect.x, 'Checkmark moved with the text');
      await page.keyboard.press('ArrowUp');
      check(await page.locator('.subtitle-name[data-marquee="running"]').count() === 0, 'Losing focus did not reset marquee');
    }
    await navigate();
    await page.waitForTimeout(1300);
    check(await page.locator('.subtitle-name[data-marquee]').count() === 0, 'Short names must not scroll');
    await navigate();
    await page.keyboard.press('ArrowDown');
    let result = await record('browse-without-applying', false);
    check(result.state.selected === 'zh' && result.state.focused === 'en', 'Browsing selected a subtitle');
    await page.keyboard.press('Enter');
    result = await record('apply-english', false);
    check(result.state.selected === 'en' && result.state.panel === 'closed' && result.bottomFocus === 'subtitle', 'Confirm did not apply and restore focus');
    await page.keyboard.press('Enter');
    result = await record('reopen-selected', false);
    check(result.state.focused === 'en' && result.state.panel === 'quick', 'Reopening did not focus the effective option');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Enter');
    result = await record('turn-off', false);
    check(result.state.selected === 'off' && !result.subtitleVisible, 'Selecting Off failed');
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Escape');
    result = await record('cancel-browsing', false);
    check(result.state.selected === 'off' && result.state.panel === 'closed', 'BACK applied an unconfirmed option');
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    result = await record('enable-by-selecting-track', false);
    check(result.state.selected === 'zh' && result.subtitleVisible, 'Choosing a track did not enable subtitles');
    await page.locator('[data-subtitle-settings]').click();
    check((await record('open-details-by-pointer', false)).state.panel === 'details', 'Details entry failed');
    await page.keyboard.press('Escape');
    check((await record('return-from-details', false)).bottomFocus === 'subtitle', 'Details BACK lost bottom focus');
    await navigate({tracks:'error'});
    await page.keyboard.press('ArrowUp');
    check((await record('focus-retry', false)).state.focused === 'retry', 'Retry is not reachable by remote');
    await page.keyboard.press('ArrowDown');
    check((await record('return-from-retry', false)).state.focused === 'off', 'Retry traps the remote focus');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Enter');
    check((await record('retry-by-remote', false)).state.tracks === 'multiple', 'Retry is not reachable by remote');
    await navigate();
    await page.locator('[data-subtitle="dual"]').click();
    check((await record('pointer-track-selection', false)).state.selected === 'dual', 'Pointer selection failed');
    await navigate({tracks:'many',option:'file'});
    for (let i=0;i<9;i++) await page.keyboard.press('ArrowUp');
    result = await record('remote-scroll-to-start', false);
    check(result.state.focused === 'off' && result.scroll.top === 0, 'Remote could not return to the start of the list');
    for (let i=0;i<9;i++) await page.keyboard.press('ArrowDown');
    result = await record('remote-scroll-to-end', false);
    check(result.state.focused === 'file' && result.scroll.top > 0, 'Remote could not reach the last long-named track');
    await page.keyboard.press('Enter');
    check((await record('apply-long-name', false)).state.selected === 'file', 'Long-named track could not be applied');
    await navigate({layout:'horizontal',tracks:'many',option:'file',subtitle:'file'});
    await record('previous-horizontal-many');
    await page.setViewportSize({width:1504,height:1180});
    await navigate({capture:'0',option:'en'});
    await page.screenshot({path:path.join(output,'review-page.png'),fullPage:true});
    check(errors.length === 0, 'Browser runtime errors: '+errors.join('; '));
  } finally {await browser.close();}
  const labels = ['正常选择 · 单行等高，勾选独立','长文件名 · 聚焦后文字滚动','德文长名称 · 默认省略，聚焦滚动','阿拉伯文 · 按自身方向滚动'];
  const names = ['on-selected','marquee-file-moving','german','marquee-rtl-moving'];
  const board = Buffer.from(`<svg width="1440" height="1020" xmlns="http://www.w3.org/2000/svg"><style>text{font-family:'Microsoft YaHei','Segoe UI',sans-serif}</style><rect width="1440" height="1020" fill="#111217"/><text x="32" y="46" font-size="30" fill="#f5f5f7">字幕快捷面板 · 单行滚动方案</text><text x="32" y="83" font-size="17" fill="#b4b5c1">同宽等高，聚焦停留约 1 秒后滚动；勾选保持固定，离焦复位。</text>${labels.map((label,i) => `<text x="${32+i%2*704}" y="${133+Math.floor(i/2)*426}" font-size="20" fill="#ededf2">${label}</text>`).join('')}<text x="32" y="990" font-size="16" fill="#a3a3b2">网页演示数据；原生实施与验证另记。上下浏览、确认应用、返回收起。</text></svg>`);
  const composites = await Promise.all(names.map(async (name,i) => ({
    input:await sharp(path.join(output,name+'.png')).resize(672,378).toBuffer(),
    left:32+i%2*704,top:151+Math.floor(i/2)*426
  })));
  await sharp(board).composite(composites).png().toFile(path.join(output,'states.png'));
  const comparisonBoard = Buffer.from(`<svg width="1568" height="820" xmlns="http://www.w3.org/2000/svg"><style>text{font-family:'Microsoft YaHei','Segoe UI',sans-serif}</style><rect width="1568" height="820" fill="#111217"/><text x="24" y="44" font-size="28" fill="#f5f5f7">字幕长名称 · 同数据对比</text><text x="24" y="84" font-size="18" fill="#c3c3cf">上一稿 · 横排标签</text><text x="808" y="84" font-size="18" fill="#c3c3cf">用户方案 · 单行等高，聚焦后自动滚动</text><text x="24" y="786" font-size="16" fill="#a3a3b2">两稿均聚焦并选择同一长文件名；局部等比例裁切。动态效果请查看网页。</text></svg>`);
  const comparisonCrops = await Promise.all(['previous-horizontal-many','many-tracks'].map(async (name,i) => ({
    input:await sharp(path.join(output,name+'.png')).extract({left:660,top:170,width:750,height:580}).png().toBuffer(),
    left:24+i*784,top:123
  })));
  await sharp(comparisonBoard).composite(comparisonCrops).png().toFile(path.join(output,'comparison.png'));
  fs.writeFileSync(path.join(output,'verification.json'), JSON.stringify({date:'2026-11-03',kind:'HTML single-line marquee design; native implementation and verification tracked separately',route:'player-bottom-refinement-prototype.html?variant=open&review=subtitle&layout=vertical',errors,observations},null,2)+'\n');
  console.log(JSON.stringify({output,runtimeErrors:errors.length,observations:observations.length},null,2));
}
main().catch(error => {console.error(error);process.exitCode = 1;});
