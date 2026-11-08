/* DESIGN PROTOTYPE ONLY, 2026-11-08.
 * Question: homepage-style full artwork + shelf, or artwork + episode list?
 * Same route via review=cinema; variant=hero|split|current. No native changes or persistence.
 * Artwork is from the official site; episodes, sources and progress are review fixtures.
 */
(() => {
  const query = new URLSearchParams(location.search);
  if (query.get('review') !== 'cinema') return;
  const variants = {hero:'A · 全景剧场',split:'B · 画面与列表',current:'现状结构'};
  const states = {normal:'常规续播',fresh:'尚未观看',seasons:'多季 / 特别篇',long:'超长标题',poster:'只有竖海报',noart:'没有图片',nostill:'缺少分集剧照',empty:'没有可播放文件',initial:'首次加载',missing:'续播文件失效',error:'播放准备失败',unknown:'时长未知',movie:'电影 / 多版本'};
  let variant = variants[query.get('variant')] ? query.get('variant') : 'hero';
  let state = states[query.get('state')] ? query.get('state') : 'normal';
  let font = query.get('font') === '1.3' ? 1.3 : 1;
  let season = 1, selectedEpisode = 3, modal = null, opener = 'resume', playError = false;
  const assets = 'assets/series-cinema/';
  const oldAssets = 'previews/series-detail/';
  const title = '乱世千金倪亚·利斯顿转生成为娇弱千金的弑神武人华丽无双录';
  const overview = '拥有最强前世的转生千金，第二人生也要大闹天下。在病弱的千金倪亚·利斯顿体内苏醒后，她决定以自己的方式，重新踏上这片未知的世界。';
  const playIcon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3.5v17L20 12z"/></svg>';
  const images = [oldAssets+'episode-1.png',oldAssets+'episode-2.png',assets+'scene-3.jpg',assets+'scene-1.jpg'];
  const names = ['自黑暗中浮现','王都的工作','舞台落幕之后'];
  const css = document.createElement('link');
  css.rel = 'stylesheet'; css.href = 'series-cinema-prototype.css'; document.head.append(css);
  document.title = 'DDPlayTV · 剧集详情大画面设计';
  document.body.className = 'sc-review' + (query.get('capture') === '1' ? ' sc-capture' : '');
  document.body.innerHTML = `<header class="sc-toolbar"><div><strong>剧集详情 / 视觉提案</strong><span class="sc-intro">延续首页的大画面 · 等待确认</span></div><div class="sc-tools"><label>场景 <select id="sc-state" aria-label="场景">${Object.entries(states).map(([key,label])=>`<option value="${key}">${label}</option>`).join('')}</select></label><label>字号 <select id="sc-font" aria-label="字号"><option value="1">100%</option><option value="1.3">130%</option></select></label><button id="sc-fullscreen">全屏预览</button></div></header><main class="sc-workspace" id="sc-workspace"><div class="sc-viewport" id="sc-viewport"><div class="sc-canvas" id="sc-canvas"></div></div></main><footer class="sc-footer"><div class="sc-note">方向键移动 · Enter 确认 · Esc 返回<br>素材：<a href="https://kyoranreijo-pr.com/" target="_blank" rel="noreferrer">动画官网</a> / 既有截图；分集配图、数量和进度为示例。</div><nav class="sc-switcher" id="sc-switcher" aria-label="设计方案"><button class="sc-cycle" data-cycle="-1" aria-label="上一个方案">‹</button>${Object.entries(variants).map(([key,label])=>`<button data-variant="${key}">${label}</button>`).join('')}<button class="sc-cycle" data-cycle="1" aria-label="下一个方案">›</button></nav><div class="sc-status" id="sc-status"></div></footer>`;
  const canvas = document.getElementById('sc-canvas');
  const viewport = document.getElementById('sc-viewport');
  const count = () => ['empty','initial'].includes(state) ? 0 : state === 'movie' ? 1 : season === 2 ? 6 : season === 0 ? 2 : 12;
  const recent = () => !['fresh','empty','initial'].includes(state);
  const labelSeason = n => n === 0 ? '特别篇' : `第 ${n} 季`;
  const episodeName = n => state === 'movie' ? '深海来信' : (season === 1 && names[n-1]) || (season === 0 ? `特别篇 ${n}` : `第 ${n} 集`);
  const workTitle = () => state === 'initial' ? '正在读取作品资料…' : state === 'movie' ? '深海来信' : state === 'long' ? title+'：第二人生与未知世界的冒险篇章' : title;
  const escape = value => String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
  function button(key,label,classes='',disabled=false) {
    return `<button class="${classes}" data-key="${key}" ${disabled?'disabled':''}>${label}</button>`;
  }
  function header() {
    return `<header class="sc-top"><div class="sc-top-left"><div class="sc-brand"><span class="sc-brand-mark">${playIcon}</span>DDPlayTV</div><span>作品详情</span></div><span>${state==='initial'?'':'115 · 媒体库'}</span></header>`;
  }
  function hero() {
    const disabled = ['initial','empty','missing'].includes(state);
    const action = state==='missing'?'上次文件不可用':state==='empty'?'暂无可播放文件':state==='initial'?'正在读取':state==='fresh'?'开始观看':state==='movie'?'继续播放':'继续播放 · 第 3 集';
    const progress = recent() && !['missing','unknown'].includes(state) ? '<span class="sc-mini-track"><i></i></span>' : '';
    const note = state==='initial'?'':state==='empty'?'文件入库后，可在这里开始观看':state==='missing'?'第 1 季 · 第 3 集 · 进度 42% 已保留':state==='fresh'?'从第 1 季第 1 集开始':state==='movie'?'本文件 42% · 上次观看':`第 1 季 · 第 3 集 · ${state==='unknown'?'已观看 10:08':'本集 42%'}`;
    return `<section class="sc-hero">${!['initial','noart'].includes(state)?`<img class="sc-poster" src="${oldAssets}poster.png" alt="作品竖海报">`:''}<div class="sc-copy"><div class="sc-eyebrow">${state==='initial'?'你的私人放映室':state==='fresh'?'从这里，开启新的故事':'回到故事之中'}</div><h1 class="sc-title">${escape(workTitle())}</h1>${state==='initial'?'':`<div class="sc-meta"><span>2026</span><i></i><span>${state==='movie'?'剧情':'动画 · 奇幻'}</span><span class="sc-type">${state==='movie'?'电影':'剧集'}</span></div><p class="sc-overview">${state==='movie'?'有些故事，值得慢慢看。从上次停下的地方，再次出发。':overview}</p>`}<div class="sc-actions">${button('resume',playIcon+action,'sc-play',disabled)}${state==='initial'?'':button('synopsis','完整简介','sc-secondary')+(state==='empty'?'':button('manage','管理资料','sc-manage'))}</div><div class="sc-progress-note">${progress}${note}</div></div></section>`;
  }
  function seasonBar() {
    if (['initial','movie'].includes(state)) return '';
    if (state !== 'seasons') return '<span class="sc-season-label">第 1 季</span>';
    return [1,2,0].map(n=>`<button class="sc-season" data-key="season-${n}" aria-pressed="${season===n}">${labelSeason(n)}</button>`).join('');
  }
  function episodeCard(n) {
    const isRecent = recent() && (state==='movie' || (season===1 && n===3));
    const unavailable = state==='missing' && isRecent;
    const source = state==='movie' ? assets+'scene-3.jpg' : state==='nostill'||state==='noart'||season!==1 ? null : images[n-1];
    const image = source ? `<img src="${source}" alt="">` : `<span class="sc-placeholder">${String(n).padStart(2,'0')}</span>`;
    const badge = isRecent ? '<span class="sc-badge">上次观看</span>' : '';
    const progress = isRecent && state!=='unknown' ? '<span class="sc-card-progress"><i style="width:42%"></i></span>' : '';
    const sub = unavailable?'原文件暂不可用 · 2 个版本':isRecent?(state==='unknown'?'已观看 10:08':state==='movie'?'本文件 42%':'本集 42%')+' · <b>2 个版本</b>':'115';
    return `<button class="sc-episode" data-key="episode-${n}" data-episode="${n}"><div class="sc-still">${image}${badge}${progress}</div><span class="sc-card-name">${state==='movie'?'':String(n).padStart(2,'0')+' · '}${escape(episodeName(n))}</span><span class="sc-card-sub">${sub}</span></button>`;
  }
  function shelf() {
    let content;
    if (state==='initial') content=`<div class="sc-episodes" aria-label="正在读取">${[1,2,3,4].map(()=>'<div style="flex:0 0 300px"><div class="sc-skeleton"></div><div class="sc-skeleton-line"></div></div>').join('')}</div>`;
    else if (state==='empty') content=`<div class="sc-empty"><strong>还没有发现这部作品的文件</strong><p>作品资料会保留，先到媒体库查看来源。</p>${button('browse','浏览媒体库','sc-secondary')}</div>`;
    else content=`<div class="sc-episodes" id="sc-episodes">${Array.from({length:count()},(_,i)=>episodeCard(i+1)).join('')}</div>`;
    return `<section class="sc-shelf"><div class="sc-shelf-heading"><h2>${state==='movie'?'影片文件':'剧集'}</h2><span class="sc-count">${state==='initial'?'读取中':state==='movie'?'2 个版本':`${count()} 集已入库`}</span><div class="sc-seasons">${seasonBar()}</div></div>${state==='error'||playError?'<p class="sc-alert" role="status">暂时无法连接来源，播放进度已保留。可重试播放，或选择其他文件版本。</p>':''}${content}</section>`;
  }
  function sync() {
    const url=new URL(location.href);
    Object.entries({review:'cinema',variant,state,font}).forEach(([key,value])=>url.searchParams.set(key,value));
    history.replaceState(null,'',url);
    document.getElementById('sc-state').value=state;
    document.getElementById('sc-font').value=font;
    document.querySelectorAll('[data-variant]').forEach(node=>node.setAttribute('aria-pressed',String(node.dataset.variant===variant)));
    document.getElementById('sc-status').textContent=`${variants[variant]} · ${states[state]} · ${font===1?'100%':'130%'}\n网页交互预览 · 原生尚未实施`;
  }
  function focusKey(key) {
    const node=canvas.querySelector(`[data-key="${key}"]:not(:disabled)`);
    if(!node)return false;
    node.focus({preventScroll:true});
    if(node.dataset.episode){selectedEpisode=Number(node.dataset.episode);node.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});}
    else if(!modal)node.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});
    return true;
  }
  function defaultFocus() { if(state!=='initial') focusKey(state==='empty'?'browse':state==='missing'?'episode-1':'resume'); }
  function render(key) {
    modal=null; canvas.className=`sc-canvas sc-layout-${variant}`;
    canvas.style.setProperty('--fs',font);
    canvas.innerHTML=`<div class="sc-screen ${state==='poster'?'sc-poster-only':''} ${['noart','initial'].includes(state)?'sc-no-art':''}" id="sc-screen"><div class="sc-art">${['noart','initial'].includes(state)?'':`<img src="${assets}key-visual.webp" alt="">`}</div>${header()}<main class="sc-content">${hero()}${shelf()}</main></div>`;
    canvas.querySelectorAll('img').forEach(img=>img.addEventListener('error',()=>{img.style.display='none';if(img.parentElement.classList.contains('sc-art'))document.getElementById('sc-screen').classList.add('sc-no-art');}));
    sync(); if(!key||!focusKey(key))defaultFocus();
  }
  function openDialog(kind,content) {
    if(!modal)opener=document.activeElement?.dataset.key||'resume';
    canvas.querySelector('.sc-modal')?.remove();modal=kind;
    canvas.insertAdjacentHTML('beforeend',`<div class="sc-modal"><section class="sc-dialog" role="dialog" aria-modal="true" aria-labelledby="sc-dialog-title">${content}</section></div>`);
    const target=canvas.querySelector('.sc-dialog button:not(:disabled)');
    if(target)target.focus();else{const dialog=canvas.querySelector('.sc-dialog');dialog.tabIndex=-1;dialog.focus();}
  }
  function closeDialog(){canvas.querySelector('.sc-modal')?.remove();modal=null;if(!focusKey(opener))defaultFocus();}
  function versions(n,manage=false) {
    selectedEpisode=n;
    openDialog(manage?'manage':'versions',`<h2 id="sc-dialog-title">${manage?'管理作品资料':'选择播放版本'}</h2><p>${state==='movie'?'影片文件':`${labelSeason(season)} · 第 ${n} 集`}</p>${button('version-original',`115 <small>${title} · S${String(season).padStart(2,'0')}E${String(n).padStart(2,'0')}.mp4</small><small>${state==='missing'?'原文件暂不可用 · 进度 42% 已保留':'上次观看 · 本文件 42%'}</small>`,'',state==='missing')}${button('version-nas',`家庭 NAS <small>${title} · S${String(season).padStart(2,'0')}E${String(n).padStart(2,'0')}.mkv</small><small>尚未观看 · 使用本版本自己的进度</small>`)}${button('cancel','取消','sc-dialog-cancel')}`);
  }
  function play(n,source='115',resume=false,playSeason=season) {
    if(state==='error'){playError=true;render(opener);return;}
    openDialog('play',`<h2 id="sc-dialog-title">${resume?'继续播放':'开始播放'} · ${state==='movie'?'深海来信':`${labelSeason(playSeason)}第 ${n} 集`}</h2><p>${source} · ${resume?(state==='unknown'?'从 10:08 继续':'从本文件 42% 继续'):'从本文件起点开始'}</p><p>这是网页播放预览，不连接真实媒体。</p>`);
  }
  function activate(key) {
    if(key.startsWith('season-')){season=Number(key.slice(7));selectedEpisode=1;render(key);return;}
    if(key.startsWith('episode-')){const n=Number(key.slice(8));selectedEpisode=n;opener=key;if(state==='movie'||(season===1&&n===3&&recent()))versions(n);else play(n);return;}
    if(key==='resume'){opener=key;play(state==='fresh'?1:3,'115',state!=='fresh',1);}
    if(key==='synopsis')openDialog('synopsis',`<h2 id="sc-dialog-title">${escape(workTitle())}</h2><p>${overview}</p><p>从上一段人生留下的记忆出发，她将用自己的方式面对新的生活。</p>`);
    if(key==='manage')openDialog('manage',`<h2 id="sc-dialog-title">管理作品资料</h2><p>选择实际文件，进入现有的资料管理流程。</p>${button('manage-file',`115 <small>${title} · S01E03.mp4</small>`)}${button('cancel','取消','sc-dialog-cancel')}`);
    if(key==='manage-file')openDialog('management-preview','<h2 id="sc-dialog-title">实际文件的资料管理</h2><p>确认方案后，这里衔接现有管理页面。本次提案不调整该页面。</p>');
    if(key==='browse')openDialog('library','<h2 id="sc-dialog-title">浏览媒体库</h2><p>这里衔接现有媒体库。按 Esc 返回详情页。</p>');
    if(key==='version-original')play(selectedEpisode,'115',true);
    if(key==='version-nas')play(selectedEpisode,'家庭 NAS',false);
    if(key==='cancel')closeDialog();
  }
  function move(direction) {
    const key=document.activeElement?.dataset.key||'';
    if(modal){const buttons=[...canvas.querySelectorAll('.sc-dialog button:not(:disabled)')];const index=buttons.indexOf(document.activeElement);const delta=['ArrowUp','ArrowLeft'].includes(direction)?-1:1;buttons[Math.max(0,Math.min(buttons.length-1,index+delta))]?.focus();return;}
    const episode=key.startsWith('episode-'),onSeason=key.startsWith('season-');
    const actions=['resume','synopsis','manage'].filter(k=>canvas.querySelector(`[data-key="${k}"]:not(:disabled)`));
    if(episode){
      const n=Number(key.slice(8));
      if(variant==='split'){
        if(direction==='ArrowLeft'){focusKey('resume')||focusKey('synopsis');return;}
        if(direction==='ArrowUp'&&n===1){focusKey('season-'+season)||focusKey('resume');return;}
        if(['ArrowUp','ArrowDown'].includes(direction)){focusKey('episode-'+Math.max(1,Math.min(count(),n+(direction==='ArrowUp'?-1:1))));return;}
      }else{
        if(direction==='ArrowUp'){focusKey('season-'+season)||focusKey('resume')||focusKey('synopsis');return;}
        if(['ArrowLeft','ArrowRight'].includes(direction)){focusKey('episode-'+Math.max(1,Math.min(count(),n+(direction==='ArrowLeft'?-1:1))));return;}
      }
      return;
    }
    if(onSeason){
      const seasons=[1,2,0];
      if(direction==='ArrowDown'){focusKey('episode-'+selectedEpisode);return;}
      if(direction==='ArrowUp'){focusKey('resume')||focusKey('synopsis');return;}
      focusKey('season-'+seasons[Math.max(0,Math.min(2,seasons.indexOf(Number(key.slice(7)))+(direction==='ArrowLeft'?-1:1)))]);return;
    }
    if(direction==='ArrowDown'){focusKey('season-'+season)||focusKey('episode-'+(recent()&&season===1?3:1))||focusKey('browse');return;}
    if(key==='browse'&&direction==='ArrowUp'){focusKey('synopsis');return;}
    if(['ArrowLeft','ArrowRight'].includes(direction)){
      const index=actions.indexOf(key)+(direction==='ArrowLeft'?-1:1);
      if(variant==='split'&&index>=actions.length){focusKey('episode-'+selectedEpisode);return;}
      focusKey(actions[Math.max(0,Math.min(actions.length-1,index))]);
    }
  }
  function switchVariant(next,keepSwitcher=false){variant=next;render();if(keepSwitcher)document.querySelector(`[data-variant="${next}"]`).focus();}
  function cycle(delta){const keys=Object.keys(variants);switchVariant(keys[(keys.indexOf(variant)+delta+keys.length)%keys.length],true);}
  canvas.addEventListener('click',event=>{const target=event.target.closest('button[data-key]');if(target&&!target.disabled)activate(target.dataset.key);});
  canvas.addEventListener('focusin',event=>{if(event.target.dataset.episode)selectedEpisode=Number(event.target.dataset.episode);});
  document.getElementById('sc-switcher').addEventListener('click',event=>{const target=event.target.closest('button');if(target?.dataset.variant)switchVariant(target.dataset.variant);else if(target?.dataset.cycle)cycle(Number(target.dataset.cycle));});
  document.addEventListener('keydown',event=>{
    if(event.target.matches('select,input,textarea,[contenteditable]'))return;
    if(event.target.closest('.sc-switcher')&&['ArrowLeft','ArrowRight'].includes(event.key)){event.preventDefault();cycle(event.key==='ArrowLeft'?-1:1);return;}
    if(!canvas.contains(document.activeElement))return;
    if(event.key==='Escape'||event.key==='Backspace'){
      event.preventDefault();if(modal)closeDialog();else{canvas.querySelector('.sc-exit-note')?.remove();canvas.insertAdjacentHTML('beforeend','<div class="sc-exit-note" role="status">实际应用将返回原入口，并恢复作品卡焦点</div>');setTimeout(()=>canvas.querySelector('.sc-exit-note')?.remove(),2400);}return;
    }
    if(modal&&event.key==='Tab'){event.preventDefault();const nodes=[...canvas.querySelectorAll('.sc-dialog button:not(:disabled)')];if(nodes.length){const index=nodes.indexOf(document.activeElement);nodes[(index+(event.shiftKey?-1:1)+nodes.length)%nodes.length].focus();}return;}
    if(event.key.startsWith('Arrow')){event.preventDefault();move(event.key);}
  });
  document.getElementById('sc-state').onchange=event=>{state=event.target.value;season=1;selectedEpisode=3;playError=false;render();};
  document.getElementById('sc-font').onchange=event=>{font=Number(event.target.value);render();};
  function resize(){const box=document.getElementById('sc-workspace');const full=!!document.fullscreenElement;const scale=Math.min((full?innerWidth:box.clientWidth-(document.body.classList.contains('sc-capture')?0:48))/1440,(full?innerHeight:box.clientHeight)/810);if(!full){viewport.style.width=1440*scale+'px';viewport.style.height=810*scale+'px';}canvas.style.transform=`scale(${scale})`;}
  document.getElementById('sc-fullscreen').onclick=async()=>{if(document.fullscreenElement)await document.exitFullscreen();else await viewport.requestFullscreen();resize();defaultFocus();};
  new ResizeObserver(resize).observe(document.getElementById('sc-workspace'));document.addEventListener('fullscreenchange',resize);
  window.seriesCinemaReview={snapshot:()=>({variant,state,font,season,selectedEpisode,modal,focus:document.activeElement?.dataset.key||null,scroll:document.getElementById('sc-screen').scrollTop}),focus:focusKey};
  render();resize();
})();
