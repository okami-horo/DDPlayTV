// Render and inspect the design-only player bottom-bar comparison with bundled tools.
// Run: node document/prototypes/tv-ui/render-player-bottom-refinement.cjs
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {createRequire} = require('node:module');
const runtimeRoot = process.env.DDPLAY_RUNTIME_MODULES || path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const runtimeRequire = createRequire(path.join(runtimeRoot, 'package.json'));
const {chromium} = runtimeRequire('playwright');
const sharp = runtimeRequire('sharp');
const root = path.resolve(__dirname, '../../..');
const output = path.join(__dirname, 'previews/player-bottom-refinement-2026-11-03');
const url = pathToFileURL(path.join(__dirname, 'player-bottom-refinement-prototype.html')).href;
const results = [], errors = [];
function check(condition, message) {if (!condition) throw new Error(message);}
async function main() {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:810}, deviceScaleFactor:1});
    page.on('pageerror', error=>errors.push(error.message));
    async function navigate(query) {
      await page.goto(url+'?'+new URLSearchParams(query));
      await page.evaluate(()=>document.fonts.ready);
      await page.waitForFunction(()=>[...document.images].every(image=>image.complete));
      await page.waitForTimeout(200);
    }
    async function record(name) {
      const result = await page.evaluate(()=>({state:{...state},visible:controlsVisible,position,playing,geometry:[...document.querySelectorAll('.canvas')].map(canvas=>({variant:canvas.dataset.variant,tray:canvas.querySelector('.tray')?.getBoundingClientRect().toJSON(),quick:canvas.querySelector('.quick-actions')?.getBoundingClientRect().toJSON(),subtitle:canvas.querySelector('.video-subtitle').getBoundingClientRect().toJSON(),focused:[...canvas.querySelectorAll('.control.focused')].map(button=>button.dataset.key),disabled:[...canvas.querySelectorAll('.control:disabled')].map(button=>button.dataset.key)}))}));
      results.push({name,...result});return result;
    }
    for(const variant of ['current','new']){
      await navigate({variant,capture:'1',focus:'forward'});
      const view=await record(variant+'-full');
      check(view.geometry[0].tray.right<view.geometry[0].subtitle.left,'Tray crosses accepted subtitle position');
      const controls=await page.locator('.control').evaluateAll(buttons=>buttons.map(button=>({key:button.dataset.key,rect:button.getBoundingClientRect().toJSON()})));
      check(controls.length===9,'An action is missing');
      check(controls.every(button=>button.rect.x>=0&&button.rect.y>=600&&button.rect.right<=1440&&button.rect.bottom<=810),'A control is outside the visible bottom bar');
      await page.screenshot({path:path.join(output,variant+'-full.png')});
      await page.screenshot({path:path.join(output,variant+'-bottom.png'),clip:{x:0,y:600,width:1440,height:210}});
    }
    for(const scene of ['middle','playing','first','last','single','unseekable']){
      await navigate({variant:'compare',scene,focus:'forward',capture:'1'});
      const result=await record(scene);
      check(result.geometry[0].focused.join()===result.geometry[1].focused.join(),'Comparison focus differs');
      if(scene==='first')check(result.geometry.every(item=>item.disabled.includes('previous')),'First episode state missing');
      if(scene==='last')check(result.geometry.every(item=>item.disabled.includes('next')),'Last episode state missing');
      if(scene==='single')check(await page.locator('.episode-actions').count()===0,'Single-file episode actions visible');
      if(scene==='unseekable')check(result.geometry.every(item=>item.disabled.includes('rewind')&&item.disabled.includes('forward')),'Seek disabled state missing');
    }
    for(const focus of ['play','picker','subtitle','audio','danmaku']){
      await navigate({variant:'new',focus,capture:'1'});
      await record('focus-'+focus);
      if(['play','subtitle'].includes(focus))await page.screenshot({path:path.join(output,'new-focus-'+focus+'.png'),clip:{x:0,y:600,width:1440,height:210}});
    }
    for(const scene of ['first','last','single','unseekable']){
      await navigate({variant:'new',scene,focus:'forward',capture:'1'});
      await record('new-visual-'+scene);
      await page.screenshot({path:path.join(output,'new-'+scene+'.png'),clip:{x:0,y:600,width:1440,height:210}});
    }
    for(const background of ['bright','dark']){
      await navigate({variant:'new',background,capture:'1'});
      await record('background-'+background);
      await page.screenshot({path:path.join(output,'new-'+background+'.png')});
    }
    await navigate({variant:'new',focus:'play'});
    await page.keyboard.press('ArrowRight');check((await record('dpad-right')).state.focus==='forward','DPAD movement failed');
    await page.keyboard.press('Enter');check((await record('seek-forward')).position===597,'Seek simulation failed');
    await page.keyboard.press('Escape');check(!(await record('hide-controls')).visible,'Controls not hidden');
    await page.keyboard.press('Enter');check((await record('restore-controls')).state.focus==='forward','Focus not restored');
    await page.selectOption('#scene','unseekable');await page.keyboard.press('Tab');
    await page.evaluate(()=>document.activeElement.blur());
    await page.keyboard.press('ArrowRight');check((await record('disabled-skip')).state.focus==='previous','Disabled seek action was not skipped');
    await page.setViewportSize({width:1664,height:1200});await navigate({variant:'compare'});
    await page.screenshot({path:path.join(output,'review-page.png'),fullPage:true});
    check(errors.length===0,'Browser runtime errors: '+errors.join('; '));
    fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({date:'2026-11-03',kind:'HTML design preview; no Android changes or device interaction',errors,results},null,2)+'\n');
  } finally {await browser.close();}
  const svg = Buffer.from(`<svg width="1568" height="754" xmlns="http://www.w3.org/2000/svg"><style>text{font-family:'Microsoft YaHei','Segoe UI',sans-serif}</style><rect width="1568" height="754" fill="#101013"/><text x="64" y="55" fill="#a3a3af" font-size="14" letter-spacing="2">DDPLAYTV  /  PLAYBACK CONTROLS</text><text x="64" y="105" fill="#f5f5f7" font-size="32" font-weight="600">播放底栏 · 原 UI 与新方案</text><text x="64" y="139" fill="#9c9ca9" font-size="16">同一画面、同一进度、同一焦点；保留 A 单排结构与全部操作。</text><text x="64" y="190" fill="#f5f5f7" font-size="21" font-weight="600">原 UI</text><text x="155" y="190" fill="#9999a6" font-size="14">当前源码重建 · 已按原生图核对</text><text x="64" y="478" fill="#f5f5f7" font-size="21" font-weight="600">新方案</text><text x="155" y="478" fill="#b9b9c6" font-size="14">统一柔和托盘 · 选集与轨道入口同尺度 · 焦点局部提亮</text><text x="64" y="738" fill="#8b8b99" font-size="12">设计预览，尚未修改 Android；背景为既有截图裁切素材。</text></svg>`);
  await sharp(svg).composite([{input:path.join(output,'current-bottom.png'),left:64,top:212},{input:path.join(output,'new-bottom.png'),left:64,top:492}]).png().toFile(path.join(output,'comparison-bottom.png'));
  const fullHeader=Buffer.from(`<svg width="1440" height="1740" xmlns="http://www.w3.org/2000/svg"><style>text{font-family:'Microsoft YaHei',sans-serif}</style><rect width="1440" height="1740" fill="#101013"/><text x="40" y="38" fill="#dddde7" font-size="22">原 UI · 当前源码重建</text><text x="40" y="908" fill="#dddde7" font-size="22">新方案 · 待确认设计</text></svg>`);
  await sharp(fullHeader).composite([{input:path.join(output,'current-full.png'),left:0,top:60},{input:path.join(output,'new-full.png'),left:0,top:930}]).png().toFile(path.join(output,'comparison-full.png'));
  const stateNames=[['first','首集 · 上一集禁用'],['last','末集 · 下一集禁用'],['single','单个视频 · 收起剧集组'],['unseekable','不可跳转 · 快进 / 快退禁用']];
  const stateBase=Buffer.from(`<svg width="1504" height="350" xmlns="http://www.w3.org/2000/svg"><rect width="1504" height="350" fill="#101013"/>${stateNames.map(([,label],i)=>`<text x="${16+i%2*752}" y="${30+Math.floor(i/2)*170}" fill="#dddde7" font-family="Microsoft YaHei" font-size="18">${label}</text>`).join('')}</svg>`);
  const stateImages=await Promise.all(stateNames.map(async([name],i)=>({input:await sharp(path.join(output,'new-'+name+'.png')).resize(720,105).toBuffer(),left:16+i%2*752,top:48+Math.floor(i/2)*170})));
  await sharp(stateBase).composite(stateImages).png().toFile(path.join(output,'new-states.png'));
  console.log(JSON.stringify({output:path.relative(root,output),runtimeErrors:errors.length,observations:results.length},null,2));
}
main().catch(error=>{console.error(error);process.exitCode=1});
