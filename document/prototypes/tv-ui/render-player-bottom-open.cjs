// Design-only captures. Run: node document/prototypes/tv-ui/render-player-bottom-open.cjs
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {createRequire} = require('node:module');
const runtimeRoot = process.env.DDPLAY_RUNTIME_MODULES || path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const runtimeRequire = createRequire(path.join(runtimeRoot, 'package.json'));
const {chromium} = runtimeRequire('playwright');
const sharp = runtimeRequire('sharp');
const output = path.join(__dirname, 'previews/player-bottom-open-top-2026-11-03');
const url = pathToFileURL(path.join(__dirname, 'player-bottom-refinement-prototype.html')).href;
const crop = {x:0,y:560,width:1440,height:250};
const observations = [], errors = [];
function check(condition, message) {if (!condition) throw new Error(message);}
async function main() {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:810},deviceScaleFactor:1});
    page.on('pageerror', error=>errors.push(error.message));
    async function navigate(query) {
      await page.goto(url+'?'+new URLSearchParams({capture:'1',focus:'play',...query}));
      await page.evaluate(()=>document.fonts.ready);
      await page.waitForTimeout(200);
      check(await page.locator('.video').first().evaluate(async element=>{
        const image = new Image();
        image.src = getComputedStyle(element).backgroundImage.slice(5,-2);
        await image.decode();
        return image.naturalWidth>0;
      }),'Background image did not load');
    }
    async function record(name) {
      const result = await page.evaluate(()=>({state:{...state},visible:controlsVisible,position,playing,canvases:[...document.querySelectorAll('.canvas')].map(canvas=>({
        variant:canvas.dataset.variant,
        subtitle:canvas.querySelector('.video-subtitle').getBoundingClientRect().toJSON(),
        seek:canvas.querySelector('.seek')?.getBoundingClientRect().toJSON(),
        focused:[...canvas.querySelectorAll('.control.focused')].map(button=>button.dataset.key),
        controls:[...canvas.querySelectorAll('.control')].map(button=>({key:button.dataset.key,disabled:button.disabled,rect:button.getBoundingClientRect().toJSON()})),
        trayCount:canvas.querySelectorAll('.tray').length
      }))}));
      observations.push({name,...result});
      return result;
    }
    for(const variant of ['current','new','open']) {
      await navigate({variant});
      const result = await record(variant+'-full');
      const canvas = result.canvases[0];
      check(canvas.controls.length===9,'An action is missing in '+variant);
      check(canvas.controls.every(button=>button.rect.x>=0&&button.rect.y>=580&&button.rect.right<=1440&&button.rect.bottom<=810),'Action outside the bottom area');
      check(canvas.subtitle.x===488&&canvas.subtitle.y===720,'Accepted subtitle position changed');
      if(variant==='open') {
        check(canvas.trayCount===0,'Open design still has a tray');
        check(canvas.controls.every(button=>button.rect.top>canvas.seek.bottom),'An open-layout control is above the timeline');
        check(canvas.seek.bottom<canvas.subtitle.top,'Timeline intersects subtitles');
        check(canvas.controls.every(button=>button.rect.right<=canvas.subtitle.left||button.rect.left>=canvas.subtitle.right),'A control intersects the accepted subtitle area');
      }
      await page.screenshot({path:path.join(output,variant+'-full.png')});
      await page.screenshot({path:path.join(output,variant+'-bottom.png'),clip:crop});
    }
    for(const scene of ['middle','playing','first','last','single','unseekable']) {
      await navigate({variant:'compare-open',scene,focus:'forward'});
      const result = await record('comparison-'+scene);
      check(result.canvases[0].focused.join()===result.canvases[1].focused.join(),'Comparison focus differs');
      for(const canvas of result.canvases) {
        const disabled = canvas.controls.filter(button=>button.disabled).map(button=>button.key);
        check(canvas.controls.length===(scene==='single'?6:9),'Unexpected number of actions');
        if(scene==='first')check(disabled.includes('previous'),'First-episode state missing');
        if(scene==='last')check(disabled.includes('next'),'Last-episode state missing');
        if(scene==='unseekable')check(disabled.includes('rewind')&&disabled.includes('forward'),'Unseekable state missing');
      }
      if(['first','last','single','unseekable'].includes(scene)) {
        await navigate({variant:'open',scene,focus:'forward'});
        await page.screenshot({path:path.join(output,'open-'+scene+'.png'),clip:crop});
      }
    }
    for(const focus of ['rewind','play','forward','previous','next','picker','subtitle','audio','danmaku']) {
      await navigate({variant:'open',focus});
      const result = await record('focus-'+focus);
      check(result.canvases[0].focused.join()===focus,'Focus missing: '+focus);
      const overlaps = await page.locator('.canvas').evaluate(canvas=>{
        const seek = canvas.querySelector('.seek').getBoundingClientRect();
        return [...canvas.querySelectorAll('.control > span')].filter(label=>getComputedStyle(label).visibility==='visible').some(label=>{
          const rect = label.getBoundingClientRect();
          return rect.top<=seek.bottom||rect.bottom>810;
        });
      });
      check(!overlaps,'An action label crosses the timeline or screen edge');
      if(['forward','subtitle'].includes(focus))await page.screenshot({path:path.join(output,'open-focus-'+focus+'.png'),clip:crop});
    }
    for(const background of ['bright','dark']) {
      await navigate({variant:'open',background,focus:'subtitle'});
      await record('background-'+background);
      await page.screenshot({path:path.join(output,'open-'+background+'.png')});
    }
    await navigate({variant:'open'});
    await page.keyboard.press('ArrowRight');
    check((await record('dpad-right')).state.focus==='forward','Right navigation failed');
    await page.keyboard.press('Enter');
    check((await record('seek-forward')).position===597,'15-second seek failed');
    await page.keyboard.press('Escape');
    check(!(await record('hide-controls')).visible,'Controls not hidden');
    await page.keyboard.press('Enter');
    check((await record('restore-controls')).state.focus==='forward','Focus not restored');
    await navigate({variant:'open',scene:'unseekable'});
    await page.keyboard.press('ArrowRight');
    check((await record('disabled-skip')).state.focus==='previous','Disabled action was not skipped');
    await page.setViewportSize({width:1664,height:1200});
    await navigate({variant:'compare-open',capture:'0'});
    await page.screenshot({path:path.join(output,'review-page.png'),fullPage:true});
    check(errors.length===0,'Browser errors: '+errors.join('; '));
  } finally {await browser.close();}
  const svg = Buffer.from(`<svg width="1568" height="850" xmlns="http://www.w3.org/2000/svg"><style>text{font-family:'Microsoft YaHei','Segoe UI',sans-serif}</style><rect width="1568" height="850" fill="#101013"/><text x="64" y="50" fill="#a3a3af" font-size="14" letter-spacing="2">DDPLAYTV / PLAYER CONTROLS</text><text x="64" y="98" fill="#f5f5f7" font-size="32" font-weight="600">开放式底栏 · 原 UI 与新设计</text><text x="64" y="134" fill="#a9a9b5" font-size="16">同一画面、进度、字幕位置与播放焦点；保留九个操作。</text><text x="64" y="183" fill="#f5f5f7" font-size="21" font-weight="600">原 UI</text><text x="155" y="183" fill="#9999a6" font-size="14">当前源码重建 · 已按原生图核对</text><text x="64" y="506" fill="#f5f5f7" font-size="21" font-weight="600">新设计</text><text x="166" y="506" fill="#b9b9c6" font-size="14">开放的长进度条 · 独立圆形按钮 · 小胶囊选集</text><text x="64" y="829" fill="#8b8b99" font-size="12">设计预览，等待视觉确认。原生 UI 与电视未改动。</text></svg>`);
  await sharp(svg).composite([{input:path.join(output,'current-bottom.png'),left:64,top:205},{input:path.join(output,'open-bottom.png'),left:64,top:528}]).png().toFile(path.join(output,'comparison-bottom.png'));
  const variants = [['current','原 UI · 源码重建'],['new','上一稿 · 双托盘'],['open','新设计 · 开放式底栏']];
  const variantsBase = Buffer.from(`<svg width="1504" height="990" xmlns="http://www.w3.org/2000/svg"><rect width="1504" height="990" fill="#101013"/>${variants.map(([,label],i)=>`<text x="32" y="${35+i*328}" fill="#eeeef2" font-family="Microsoft YaHei" font-size="21">${label}</text>`).join('')}</svg>`);
  await sharp(variantsBase).composite(variants.map(([name],i)=>({input:path.join(output,name+'-bottom.png'),left:32,top:52+i*328}))).png().toFile(path.join(output,'three-versions.png'));
  const scenes = [['first','首集 · 上一集禁用'],['last','末集 · 下一集禁用'],['single','单个视频 · 收起剧集组'],['unseekable','不可跳转 · 快进 / 快退禁用']];
  const stateBase = Buffer.from(`<svg width="1504" height="390" xmlns="http://www.w3.org/2000/svg"><rect width="1504" height="390" fill="#101013"/>${scenes.map(([,label],i)=>`<text x="${16+i%2*752}" y="${28+Math.floor(i/2)*190}" fill="#dddde7" font-family="Microsoft YaHei" font-size="18">${label}</text>`).join('')}</svg>`);
  const images = await Promise.all(scenes.map(async([name],i)=>({input:await sharp(path.join(output,'open-'+name+'.png')).resize(720,125).toBuffer(),left:16+i%2*752,top:48+Math.floor(i/2)*190})));
  await sharp(stateBase).composite(images).png().toFile(path.join(output,'open-states.png'));
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({date:'2026-11-03',kind:'HTML design preview only; no Android or physical-device changes',variant:'open',layout:'timeline-above-controls',errors,observations},null,2)+'\n');
  console.log(JSON.stringify({output,runtimeErrors:errors.length,observations:observations.length},null,2));
}
main().catch(error=>{console.error(error);process.exitCode=1});
