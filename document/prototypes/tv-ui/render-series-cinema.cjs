// HTML visual/interaction observations only. No native/device acceptance is implied.
const fs=require('node:fs');
const path=require('node:path');
const {createRequire}=require('node:module');
const {pathToFileURL}=require('node:url');
const runtime=createRequire(path.join(process.env.USERPROFILE,'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/package.json'));
const {chromium}=runtime('playwright');
const sharp=runtime('sharp');
const output=path.join(__dirname,'previews/series-cinema-2026-11-08');
const base=pathToFileURL(path.join(__dirname,'series-detail-prototype.html')).href;
const observations=[],errors=[];
function check(value,message){if(!value)throw new Error(message);}
(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:1440,height:810},deviceScaleFactor:1});
  page.on('pageerror',e=>errors.push(e.message));
  async function navigate(params={}){
   await page.goto(base+'?'+new URLSearchParams({review:'cinema',variant:'hero',capture:'1',...params}));
   await page.waitForFunction(()=>window.seriesCinemaReview&&getComputedStyle(document.querySelector('.sc-canvas')).width==='1440px');
   await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(img=>img.decode().catch(()=>{})));});
   await page.waitForTimeout(220);
  }
  async function record(name,capture=true){
   await page.waitForTimeout(160);
   const result=await page.evaluate(()=>({
    ...seriesCinemaReview.snapshot(),title:document.querySelector('.sc-title').getBoundingClientRect().toJSON(),
    shelf:document.querySelector('.sc-shelf').getBoundingClientRect().toJSON(),
    hero:document.querySelector('.sc-hero').getBoundingClientRect().toJSON(),
    focusBounds:document.activeElement.getBoundingClientRect().toJSON(),
    cards:[...document.querySelectorAll('.sc-episode')].map(n=>n.getBoundingClientRect().toJSON()),
    images:[...document.images].map(n=>({src:n.getAttribute('src'),loaded:n.naturalWidth>0})),
    header:document.querySelector('.sc-top').getBoundingClientRect().toJSON(),
    dialog:document.querySelector('.sc-dialog')?.getBoundingClientRect().toJSON(),
    alert:document.querySelector('.sc-alert')?.getBoundingClientRect().toJSON(),
    bodyOverflow:document.documentElement.scrollWidth>innerWidth
   }));
   observations.push({name,...result});
   check(result.images.every(img=>img.loaded),name+': missing image');
   check(!result.bodyOverflow,name+': outer page overflows');
   if(result.focus){const r=result.focusBounds;check(r.top>=0&&r.bottom<=810.5&&r.left>=0&&r.right<=1440.5,name+': focus clipped');}
   if(result.dialog)check(result.dialog.top>=0&&result.dialog.bottom<=810,name+': dialog clipped');
   if(result.alert)check(result.alert.left>=50&&result.alert.right<=1390,name+': alert escaped content gutter');
   if(result.variant!=='split')check(result.hero.bottom<=result.shelf.top+.5,name+': hero/shelf overlap');
   if(capture)await page.screenshot({path:path.join(output,name+'.png')});
   return result;
  }
  for(const variant of ['hero','split','current']){
   await navigate({variant});
   const result=await record(variant);
   if(variant==='hero')check(result.cards.filter(r=>r.left>=0&&r.right<=1440&&r.bottom<=810).length===4,'A must show four complete cards');
   for(const state of ['fresh','seasons','long','poster','noart','nostill','empty','initial','missing','error','unknown','movie']){
    await navigate({variant,state});await record(variant+'-'+state,variant==='hero');
   }
   await navigate({variant,font:'1.3',state:'long'});await record(variant+'-font130');
   if(variant==='split')await page.keyboard.press('ArrowRight');
   await page.keyboard.press('ArrowDown');await record(variant+'-font130-episodes');
  }
  await navigate();
  await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');
  check((await record('synopsis')).modal==='synopsis','Synopsis not opened');
  await page.keyboard.press('Escape');check((await record('synopsis-return',false)).focus==='synopsis','Synopsis caller lost');
  await page.keyboard.press('ArrowDown');
  check((await record('episode-focus')).focus==='episode-3','Down should reach last-watched episode');
  await page.keyboard.press('Enter');check((await record('versions')).modal==='versions','Version dialog missing');
  await page.keyboard.press('ArrowDown');await page.keyboard.press('Enter');
  check(await page.locator('.sc-dialog').innerText().then(t=>t.includes('家庭 NAS')&&t.includes('起点')),'Alternate version reused original progress');
  await page.keyboard.press('Escape');check((await record('version-return',false)).focus==='episode-3','Version caller lost');
  for(let n=0;n<8;n++)await page.keyboard.press('ArrowRight');await record('episode-scrolled');
  await page.keyboard.press('ArrowUp');check((await record('episode-up',false)).focus==='resume','Episode up did not reach play');
  await page.keyboard.press('ArrowRight');await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');
  await record('manage');await page.keyboard.press('Escape');
  await navigate({state:'seasons'});await page.keyboard.press('ArrowDown');await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');
  check((await record('season-two')).season===2,'Season switch failed');
  await page.keyboard.press('ArrowUp');await page.keyboard.press('Enter');
  check(await page.locator('.sc-dialog').innerText().then(t=>t.includes('第 1 季第 3 集')),'Changing season rewrote resume target');
  await page.keyboard.press('Escape');await page.keyboard.press('ArrowDown');await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');
  check((await record('specials')).season===0,'Specials not reachable');
  await navigate({state:'missing'});check((await record('missing-focus',false)).focus==='episode-1','Invalid resume must have usable focus');
  await page.keyboard.press('ArrowRight');await page.keyboard.press('ArrowRight');await page.keyboard.press('Enter');
  check((await record('missing-versions')).focus==='version-nas','Unavailable original gained focus');
  await page.keyboard.press('Escape');
  await navigate({state:'empty'});await page.keyboard.press('Enter');await record('empty-browse');await page.keyboard.press('Escape');
  check((await record('empty-return',false)).focus==='browse','Empty caller lost');
  await navigate({state:'error'});await page.keyboard.press('Enter');check((await record('error-retry')).modal===null,'Error incorrectly showed success');
  await navigate({variant:'split'});await page.keyboard.press('ArrowDown');await page.keyboard.press('ArrowDown');await page.keyboard.press('ArrowDown');await record('split-episodes');
  await page.keyboard.press('ArrowLeft');check((await record('split-return',false)).focus==='resume','Split return focus lost');
  await navigate({capture:'0'});await record('review-page');
  await page.locator('[data-variant="hero"]').focus();await page.keyboard.press('ArrowRight');
  check((await record('switcher',false)).variant==='split','Switcher keyboard failed');
  await page.reload();check((await record('url-restored',false)).variant==='split','Variant URL not restored');
  await page.setViewportSize({width:1000,height:760});await navigate({capture:'0'});await record('review-compact');
  await page.setViewportSize({width:1440,height:810});
  await page.goto(base+'?variant=B');await page.waitForTimeout(250);
  check(await page.locator('.layout-b').count()===1,'Original B route changed');
  check(errors.length===0,errors.join('\n'));
  for(const name of ['hero','split','current'])await sharp(path.join(output,name+'.png')).resize(720,405).toFile(path.join(output,name+'-small.png'));
  await sharp({create:{width:1440,height:810,channels:3,background:'#07080b'}}).composite([
   {input:path.join(output,'current-small.png'),left:0,top:0},{input:path.join(output,'hero-small.png'),left:720,top:0},
   {input:path.join(output,'split-small.png'),left:0,top:405},{input:await sharp(path.join(output,'hero-poster.png')).resize(720,405).toBuffer(),left:720,top:405}
  ]).png().toFile(path.join(output,'comparison.png'));
  fs.writeFileSync(path.join(output,'verification.json'),JSON.stringify({date:'2026-11-08',scope:'HTML only; no native/device verification',observations,errors},null,2));
  console.log(JSON.stringify({observations:observations.length,errors,output}));
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
