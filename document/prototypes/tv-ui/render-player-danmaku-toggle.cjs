// Design-only preview. Run: node document/prototypes/tv-ui/render-player-danmaku-toggle.cjs
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {createRequire} = require('node:module');
const runtimeRoot = process.env.DDPLAY_RUNTIME_MODULES || path.join(process.env.USERPROFILE, '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
const runtimeRequire = createRequire(path.join(runtimeRoot, 'package.json'));
const {chromium} = runtimeRequire('playwright');
const sharp = runtimeRequire('sharp');
const output = path.join(__dirname, 'previews/player-danmaku-toggle-2026-11-03');
const url = pathToFileURL(path.join(__dirname, 'player-bottom-refinement-prototype.html')).href;
const states = [
  {name:'on-idle',danmaku:'on',focus:'play',label:'开启 · 未聚焦'},
  {name:'off-idle',danmaku:'off',focus:'play',label:'关闭 · 未聚焦'},
  {name:'on-focused',danmaku:'on',focus:'danmaku',label:'开启 · 聚焦'},
  {name:'off-focused',danmaku:'off',focus:'danmaku',label:'关闭 · 聚焦'}
];
const observations = [], errors = [];
function check(condition, message) {if (!condition) throw new Error(message);}
async function main() {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:810},deviceScaleFactor:1});
    page.on('pageerror', error=>errors.push(error.message));
    async function navigate(query) {
      await page.goto(url+'?'+new URLSearchParams({variant:'open',capture:'1',review:'danmaku',focus:'play',...query}));
      await page.evaluate(()=>document.fonts.ready);
      await page.waitForTimeout(200);
      await page.locator('.video').evaluate(async element=>{
        const image = new Image();
        image.src = getComputedStyle(element).backgroundImage.slice(5,-2);
        await image.decode();
      });
    }
    async function record(name) {
      const observation = await page.evaluate(()=>({
        state:{...state},controlsVisible,
        seek:document.querySelector('.seek')?.getBoundingClientRect().toJSON(),
        subtitle:document.querySelector('.video-subtitle').getBoundingClientRect().toJSON(),
        controls:[...document.querySelectorAll('.control')].map(button=>({
          key:button.dataset.key,label:button.textContent,pressed:button.getAttribute('aria-pressed'),
          disabled:button.disabled,focused:button.classList.contains('focused'),
          color:getComputedStyle(button).color,opacity:getComputedStyle(button).opacity,
          rect:button.getBoundingClientRect().toJSON()
        }))
      }));
      observations.push({name,...observation});
      return observation;
    }
    await navigate({review:''});
    const baseline = await record('confirmed-layout-baseline');
    for(const item of states) {
      await navigate({danmaku:item.danmaku,focus:item.focus});
      const result = await record(item.name);
      const button = result.controls.find(control=>control.key==='danmaku');
      check(result.controls.length===9,'An existing action was lost');
      check(button.pressed===String(item.danmaku==='on'),'Toggle accessibility state differs');
      check(button.label===(item.danmaku==='on'?'弹幕开':'弹幕关'),'State label differs');
      check(!button.disabled&&button.opacity==='1','Off state looks disabled');
      check(button.focused===(item.focus==='danmaku'),'Focus and enabled state are conflated');
      check(JSON.stringify(result.seek)===JSON.stringify(baseline.seek),'Timeline geometry changed');
      check(JSON.stringify(result.subtitle)===JSON.stringify(baseline.subtitle),'Subtitle geometry changed');
      check(result.controls.every(control=>control.rect.y>result.seek.bottom),'An action moved above the timeline');
      await page.screenshot({path:path.join(output,item.name+'-full.png')});
      await page.screenshot({path:path.join(output,item.name+'-detail.png'),clip:{x:1110,y:654,width:330,height:156}});
    }
    await page.keyboard.press('Enter');
    let result = await record('confirm-off-to-on');
    check(result.state.danmaku==='on'&&result.state.focus==='danmaku','Confirm did not enable while retaining focus');
    await page.keyboard.press('Enter');
    result = await record('confirm-on-to-off');
    check(result.state.danmaku==='off'&&result.state.focus==='danmaku','Confirm did not disable while retaining focus');
    await page.keyboard.press('ArrowLeft');
    result = await record('leave-off-button');
    check(result.state.focus==='audio'&&result.state.danmaku==='off','Focus movement changed toggle state');
    await page.keyboard.press('ArrowRight');
    result = await record('return-to-off-button');
    check(result.state.focus==='danmaku','Off button is unreachable');
    await page.keyboard.press('Escape');
    check(!(await record('hide-controls')).controlsVisible,'Controls did not hide');
    await page.keyboard.press('Enter');
    result = await record('restore-controls');
    check(result.state.focus==='danmaku'&&result.state.danmaku==='off','Restore unexpectedly toggled');
    await page.locator('.control.danmaku').click();
    check((await record('pointer-toggle')).state.danmaku==='on','Pointer toggle failed');
    for(const background of ['bright','dark']) {
      await navigate({danmaku:'off',focus:'danmaku',background});
      await record('off-focused-'+background);
      await page.screenshot({path:path.join(output,'off-focused-'+background+'.png')});
    }
    await page.setViewportSize({width:1504,height:1180});
    await navigate({capture:'0',danmaku:'on',focus:'danmaku'});
    await page.locator('#next-variant').click();
    result = await record('preview-switcher');
    check(result.state.variant==='open'&&result.state.danmaku==='off','Switcher changed the confirmed layout');
    await page.locator('#danmaku').selectOption('on');
    await record('state-selector');
    await page.screenshot({path:path.join(output,'review-page.png'),fullPage:true});
    check(errors.length===0,'Browser errors: '+errors.join('; '));
  } finally {await browser.close();}
  const board = Buffer.from(`<svg width="1120" height="800" xmlns="http://www.w3.org/2000/svg"><style>text{font-family:'Microsoft YaHei','Segoe UI',sans-serif}</style><rect width="1120" height="800" fill="#111217"/><text x="48" y="37" fill="#9a9caa" font-size="13" letter-spacing="2">DDPLAYTV / DANMAKU TOGGLE / DESIGN PREVIEW</text><text x="48" y="87" fill="#f5f5f7" font-size="32" font-weight="600">弹幕开关 · 四种状态</text><text x="48" y="123" fill="#b4b5c1" font-size="16">保留已确认的底栏布局；图标与文字表示开关，白色高亮表示焦点。</text>${states.map((item,i)=>`<text x="${48+i%2*528}" y="${174+Math.floor(i/2)*284}" fill="#f0f0f4" font-size="21" font-weight="500">${item.label}</text>`).join('')}<text x="48" y="757" fill="#d1d2dc" font-size="16">按确认键直接切换，焦点留在原处；详细配置仍在右侧设置栏。</text><text x="48" y="783" fill="#8e909f" font-size="12">已确认的按钮样式预览；背景静帧内原有弹幕不会随本预览开关消失。</text></svg>`);
  const details = await Promise.all(states.map(async(item,i)=>({input:await sharp(path.join(output,item.name+'-detail.png')).resize(495,234).toBuffer(),left:48+i%2*528,top:190+Math.floor(i/2)*284})));
  await sharp(board).composite(details).png().toFile(path.join(output,'four-states.png'));
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({date:'2026-11-03',kind:'HTML button-state design preview only; no native implementation',review:'danmaku',variant:'open',errors,observations},null,2)+'\n');
  console.log(JSON.stringify({output,runtimeErrors:errors.length,observations:observations.length},null,2));
}
main().catch(error=>{console.error(error);process.exitCode=1});
