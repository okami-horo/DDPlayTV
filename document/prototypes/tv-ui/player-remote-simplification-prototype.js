// Design-only comparison on the existing player route: ?review=remote&layout=compact|retain|baseline.
// B (seven actions) is selected. Review playstyle=focus|original before combined native implementation.
(() => {
  if (params.get('review') !== 'remote') return;
  const layouts = {
    compact: {name:'A · 五项备选（未采用）', detail:'保留用于比较；用户已选择 B。'},
    retain: {name:'B · 七项（已选布局）', detail:'保留直接换集；播放键仅在聚焦时提亮。'},
    baseline: {name:'当前 · 九项操作', detail:'当前开放式底栏的网页重建，作为同画面对照。'}
  };
  const model = {
    layout: Object.hasOwn(layouts, params.get('layout')) ? params.get('layout') : 'retain',
    playstyle:params.get('playstyle') === 'original' ? 'original' : 'focus', pressed:false,
    panel:'', panelIndex:0, returnFocus:'play', episode:50,
    subtitle:'简体中文', audio:'日语 · 立体声', empty:params.get('tracks') === 'empty',
    font:params.get('font') === '1.3' ? 1.3 : 1, toast:''
  };
  const baseCanvas = canvas, baseRender = render, baseControl = control;
  const screens = document.getElementById('screens');
  let toastTimer;
  state.variant = 'open';
  state.focus = params.has('focus') ? state.focus : 'play';
  if (!['forward','rewind','previous','next','play','picker','subtitle','audio','danmaku'].includes(state.focus)) state.focus = 'play';
  lastFocus = state.focus;
  document.body.classList.add('remote-review');
  document.title = 'DDPlayTV · 遥控器优先的播放底栏';
  document.querySelector('.eyebrow').textContent = 'DDPLAYTV / PLAYER CONTROLS / 2026.11.08';
  document.querySelector('h1').textContent = 'B 稿 · 让高亮只表达焦点';
  document.querySelector('header p').textContent = '七项布局已选定。播放键取消常态白底，仅聚焦时提亮；上一集 / 下一集同步改为 58×58 的正圆焦点，与播放键尺寸一致。三项设计合并确认后一起实施。';
  document.querySelector('.scope').textContent = '设计预览 · 演示数据 · 等待确认';
  document.querySelector('.notes').innerHTML = '<p><strong>常态 / 保持克制</strong>播放键采用与右侧轨道键一致的半透明圆底、浅色图标与 58px 尺寸，取消常亮白底和独有的内描边。</p><p><strong>焦点 / 正圆与清楚的位置</strong>上一集、下一集与播放键统一为 58×58；上下集常态仍为简洁图标，聚焦时显示正圆浅底。首末集禁用规则不变。</p><p><strong>动作 / 图标承担含义</strong>暂停时显示 ▶，表示按下继续播放；播放中显示 Ⅱ，表示按下暂停。两种图标共享相同焦点样式，不以底色表达播放状态。</p>';
  document.querySelector('.footnote').innerHTML = '此页面模拟焦点、播放状态、跳转、选集及轨道选择，不连接真实播放器。背景静帧自带弹幕，因此开关仅演示按钮状态。字幕 / 选集 / 音轨面板为现有入口的简化示意，未提出侧栏重设计。按 Esc 模拟返回，M 模拟 MENU；点击画面后使用方向键。原生界面、构建与电视验证均未开展，须用户确认方案后实施。';
  document.querySelector('.toolbar').insertAdjacentHTML('afterbegin', '<label>方案 <select id="remote-layout"></select></label><label>播放键 <select id="remote-playstyle"><option value="focus">新稿 · 仅聚焦高亮</option><option value="original">原稿 · 常亮白底</option></select></label><label>轨道 <select id="remote-tracks"><option value="available">有轨道</option><option value="empty">暂无轨道</option></select></label><label>字号 <select id="remote-font"><option value="1">正常</option><option value="1.3">1.3 倍</option></select></label>');
  const layoutSelect = document.getElementById('remote-layout');
  layoutSelect.innerHTML = Object.entries(layouts).map(([key,value]) => `<option value="${key}">${value.name}</option>`).join('');
  document.getElementById('scene').insertAdjacentHTML('beforeend', '<option value="loading">视频缓冲中</option><option value="error">播放失败</option>');
  document.getElementById('danmaku-field').hidden = false;
  const style = document.createElement('style');
  style.textContent = `
    .remote-review [data-layout-switch]{display:none}
    .remote-review .screens.single .view{outline:none}
    .remote-review .caption{margin-top:4px}
    .remote-review .canvas[data-remote-layout="compact"] .open-primary{gap:24px}
    .remote-review .canvas[data-remote-layout="compact"] .control.picker{width:112px;height:54px;font-size:20px}
    .remote-review .canvas[data-remote-layout="retain"] .open-primary{gap:22px}
    .remote-review .canvas[data-remote-layout="retain"] .control.previous,.remote-review .canvas[data-remote-layout="retain"] .control.next{width:58px;height:58px;padding:14px}
    .remote-review .canvas[data-remote-layout="retain"] .control.previous svg,.remote-review .canvas[data-remote-layout="retain"] .control.next svg{width:28px;height:28px}
    .remote-review .canvas[data-play-style="focus"] .control.play{width:58px;height:58px;padding:14px;border:1px solid #ffffff35;background:linear-gradient(145deg,#cad0df32,#8a92a724);color:#f5f5f7;box-shadow:inset 0 1px 0 #ffffff14,0 3px 12px #0002}
    .remote-review .canvas[data-play-style="focus"] .control.play svg{width:28px;height:28px}
    .remote-review .canvas[data-play-style="focus"] .control.play.focused{background:#f5f5f7;color:#17181d;box-shadow:0 0 0 2px #ffffff65,0 4px 16px #0003;transform:translateY(-2px)}
    .remote-review .canvas[data-play-style="focus"] .control.play.focused.pressed{background:#dedee6;box-shadow:0 0 0 2px #ffffff65,0 2px 8px #0003;transform:translateY(-1px)}
    .remote-review .canvas .control:not(.picker)>span{font-size:calc(18px * var(--review-font))}
    .remote-review .canvas .control.picker{font-size:calc(20px * var(--review-font))}
    .remote-review .canvas[data-remote-font="1.3"] .control.picker{width:114px}
    .remote-review .canvas[data-remote-font="1.3"] .quick-actions{gap:38px}
    .remote-review .canvas .title.focused{outline:2px solid #f5f5f7;outline-offset:8px;border-radius:3px}
    .remote-review .canvas .settings.focused{color:#17181d;background:#f5f5f7;box-shadow:0 0 0 2px #ffffff65}
    .remote-panel{position:absolute;z-index:6;right:60px;bottom:203px;width:480px;padding:24px;border:1px solid #ffffff22;border-radius:20px;background:linear-gradient(145deg,#292c35,#20232b);box-shadow:0 16px 48px #0007;font-size:calc(22px * var(--review-font))}
    .remote-panel.episodes{right:auto;left:60px}
    .remote-panel h2{font-size:calc(24px * var(--review-font));font-weight:500;margin:0 0 16px}
    .remote-panel p{font-size:calc(18px * var(--review-font));line-height:1.5;color:#b6bac5;margin:12px 4px 0}
    .remote-panel-list{max-height:300px;overflow:auto;scrollbar-width:thin;scrollbar-color:#777a85 transparent}
    .remote-choice{display:flex;align-items:center;gap:16px;width:100%;height:calc(64px * var(--review-font));padding:0 16px;border:0;border-radius:10px;background:transparent;text-align:left;color:#f1f1f5;outline:none}
    .remote-choice.focused{background:#f4f4f7;color:#17181d}
    .remote-choice .name{display:block;flex:1;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
    .remote-choice .check{width:24px;flex:none;text-align:center}
    .remote-status{position:absolute;z-index:8;left:50%;top:44%;transform:translate(-50%,-50%);background:#20232bf0;border:1px solid #ffffff24;border-radius:16px;padding:20px 30px;font-size:24px;text-align:center;line-height:1.7;white-space:pre-line}
    .remote-state{margin-top:22px;padding:14px 18px;border-left:2px solid #777f91;background:#191b22;color:#bec4d2;font-size:13px;line-height:1.7}
    .remote-review .switcher output{min-width:220px}
    .remote-review.capture .remote-state{display:none}
  `;
  document.head.appendChild(style);
  screens.insertAdjacentHTML('afterend', '<div class="remote-state" id="remote-state" aria-live="polite"></div>');
  function keys() {
    return [ ...(model.layout === 'baseline' ? ['rewind'] : []), 'play', ...(model.layout === 'baseline' ? ['forward'] : []),
      ...(state.scene === 'single' ? [] : [...(model.layout === 'compact' ? [] : ['previous','next']), 'picker']), 'subtitle','audio','danmaku'
    ].filter(key => !disabled(key));
  }
  function resetScene() {
    model.panel = ''; model.toast = '';
    model.episode = state.scene === 'first' ? 1 : state.scene === 'last' ? 52 : 50;
    controlsVisible = true;
  }
  resetScene();
  control = function(key, variant) {
    let html = baseControl(key, variant);
    if (key === 'danmaku') {
      html = html.replace('aria-label="弹幕"', `aria-pressed="${state.danmaku === 'on'}" aria-label="${state.danmaku === 'on' ? '关闭' : '开启'}弹幕"`)
        .replace('<span>弹幕</span>', `<span>弹幕${state.danmaku === 'on' ? '开' : '关'}</span>`);
      if (state.danmaku === 'off') html = html.replace('</svg>', '<path d="M3 2L22 22" stroke-width="2.2"/></svg>');
    }
    return html;
  };
  function choices() {
    if (model.panel === 'picker') return Array.from({length:52}, (_,i) => `第 ${i + 1} 集`);
    if (model.panel === 'subtitle') return ['关', ...(model.empty ? [] : ['简体中文','English','简体中文 / English'])];
    if (model.panel === 'audio') return model.empty ? [] : ['日语 · 立体声','中文 · 立体声'];
    return ['播放器','字幕样式与位置','弹幕详细设置'];
  }
  function selectedIndex() {
    if (model.panel === 'picker') return model.episode - 1;
    return choices().indexOf(model[model.panel]);
  }
  function panelHtml() {
    if (!model.panel) return '';
    const items = choices();
    const title = {picker:'选集', subtitle:'字幕', audio:'音轨', settings:'设置'}[model.panel];
    return `<aside class="remote-panel ${model.panel === 'picker' ? 'episodes' : ''}" aria-label="${title}"><h2>${title}</h2><div class="remote-panel-list">${items.map((item,index) => `<button class="remote-choice ${index === model.panelIndex ? 'focused' : ''}" data-choice="${index}"><span class="name">${item}</span><span class="check">${index === selectedIndex() ? '✓' : ''}</span></button>`).join('')}</div>${model.empty && ['subtitle','audio'].includes(model.panel) ? `<p>${model.panel === 'subtitle' ? '暂无字幕，可从设置添加外挂字幕。' : '暂无可选音轨。'}</p>` : ''}</aside>`;
  }
  canvas = function() {
    const template = document.createElement('template');
    template.innerHTML = baseCanvas('open');
    const root = template.content;
    const stage = root.querySelector('.canvas');
    stage.dataset.remoteLayout = model.layout;
    stage.dataset.remoteFont = model.font;
    stage.style.setProperty('--review-font', model.font);
    root.querySelector('.view').tabIndex = 0;
    stage.dataset.playStyle = model.layout === 'retain' ? model.playstyle : 'original';
    root.querySelector('.control.play')?.classList.toggle('pressed', model.pressed);
    root.querySelector('.caption').innerHTML = `${layouts[model.layout].name}<small>${model.layout === 'retain' ? (model.playstyle === 'focus' ? '新稿：白底只表达焦点，图标表达动作。' : '原稿：播放键未聚焦时仍常亮白底。') : layouts[model.layout].detail}</small>`;
    if (model.layout !== 'baseline') root.querySelectorAll('[data-key="rewind"],[data-key="forward"]').forEach(node => node.remove());
    if (model.layout === 'compact') root.querySelectorAll('[data-key="previous"],[data-key="next"]').forEach(node => node.remove());
    if (!controlsVisible) root.querySelectorAll('.title,.clock,.settings,.top-shade').forEach(node => node.remove());
    const title = root.querySelector('.title');
    if (title) { title.textContent = state.scene === 'single' ? '本地视频.mkv' : `药屋少女的呢喃第三季 - S01E${String(model.episode).padStart(2,'0')}.mkv`; title.classList.toggle('focused', state.focus === 'title'); }
    root.querySelector('.settings')?.classList.toggle('focused', state.focus === 'settings');
    if (model.subtitle === '关' || model.empty || ['loading','error'].includes(state.scene)) root.querySelector('.video-subtitle').hidden = true;
    if (model.panel) {
      root.querySelectorAll('.control.focused,.title.focused,.settings.focused').forEach(node => node.classList.remove('focused'));
      stage.insertAdjacentHTML('beforeend', panelHtml());
    }
    const status = model.toast || (state.scene === 'loading' ? '正在缓冲…' : state.scene === 'error' ? '播放失败\n请返回后重新打开视频' : '');
    if (status) { const box = document.createElement('div'); box.className = 'remote-status'; box.textContent = status; stage.appendChild(box); }
    return template.innerHTML;
  };
  render = function() {
    if (!keys().includes(state.focus) && !['title','settings'].includes(state.focus)) state.focus = 'play';
    baseRender();
    params.set('layout', model.layout); params.set('font', model.font); params.set('tracks', model.empty ? 'empty' : 'available');
    params.set('playstyle', model.playstyle);
    history.replaceState(null, '', '?' + params);
    layoutSelect.value = model.layout;
    document.getElementById('remote-playstyle').value = model.playstyle;
    document.getElementById('remote-playstyle').disabled = model.layout !== 'retain';
    document.querySelectorAll('#focus option').forEach(option => { option.disabled = !keys().includes(option.value); });
    document.getElementById('remote-font').value = model.font;
    document.getElementById('remote-tracks').value = model.empty ? 'empty' : 'available';
    document.getElementById('variant-label').textContent = layouts[model.layout].name;
    document.getElementById('remote-state').textContent = `预览状态：${layouts[model.layout].name} · ${playing ? '播放中' : '已暂停'} · ${controlsVisible ? '底栏已展开' : '底栏已收起'} · ${model.panel ? '面板：' + model.panel : '焦点：' + ({play:'播放',picker:'选集',subtitle:'字幕',audio:'音轨',danmaku:'弹幕',previous:'上一集',next:'下一集',rewind:'快退',forward:'快进',title:'标题',settings:'设置'}[state.focus])} · 字幕：${model.subtitle} · 弹幕：${state.danmaku === 'on' ? '开' : '关'}`;
    const row = screens.querySelector('.remote-choice.focused');
    if (row) row.parentElement.scrollTop = Math.max(0, row.offsetTop - row.parentElement.offsetTop - (row.parentElement.clientHeight - row.offsetHeight) / 2);
  };
  function showToast(text) { clearTimeout(toastTimer); model.toast = text; toastTimer = setTimeout(() => { model.toast = ''; render(); }, 1400); }
  function openPanel(key) { model.returnFocus = state.focus; model.panel = key; model.panelIndex = Math.max(0, selectedIndex()); controlsVisible = true; }
  function closePanel() { model.panel = ''; state.focus = keys().includes(model.returnFocus) ? model.returnFocus : 'play'; lastFocus = state.focus; }
  function changeEpisode(number) { model.episode = Math.min(52,Math.max(1,number)); state.scene = model.episode === 1 ? 'first' : model.episode === 52 ? 'last' : 'middle'; position = 0; playing = true; }
  function choose() {
    if (model.panel === 'settings') { showToast('详细配置沿用现有设置面板'); return; }
    if (!choices().length) return;
    if (model.panel === 'picker') changeEpisode(model.panelIndex + 1);
    else model[model.panel] = choices()[model.panelIndex];
    closePanel();
  }
  function seek(offset) {
    if (['unseekable','loading','error'].includes(state.scene)) { showToast('当前视频暂不可跳转'); return; }
    position = Math.max(0, Math.min(1440,position + offset));
    showToast(`${offset > 0 ? '快进' : '快退'} ${Math.abs(offset)} 秒\n${String(Math.floor(position / 60)).padStart(2,'0')}:${String(position % 60).padStart(2,'0')} / 24:00`);
  }
  act = function(key) {
    if (key === 'play') { if (['loading','error'].includes(state.scene)) { showToast('等待视频恢复，或返回后重新打开'); return; } playing = !playing; }
    if (key === 'rewind' || key === 'forward') seek(key === 'rewind' ? -15 : 15);
    if (key === 'previous' || key === 'next') changeEpisode(model.episode + (key === 'previous' ? -1 : 1));
    if (['picker','subtitle','audio','settings'].includes(key)) openPanel(key);
    if (key === 'danmaku') state.danmaku = state.danmaku === 'on' ? 'off' : 'on';
  };
  cycleVariant = function(delta) { const names = Object.keys(layouts); model.layout = names[(names.indexOf(model.layout) + delta + names.length) % names.length]; model.panel = ''; render(); };
  layoutSelect.onchange = () => { model.layout = layoutSelect.value; model.panel = ''; render(); };
  document.getElementById('remote-playstyle').onchange = event => { model.playstyle = event.target.value; render(); };
  document.getElementById('remote-tracks').onchange = event => { model.empty = event.target.value === 'empty'; model.panelIndex = 0; render(); };
  document.getElementById('remote-font').onchange = event => { model.font = Number(event.target.value); render(); };
  document.getElementById('scene').addEventListener('change', () => { resetScene(); render(); });
  screens.addEventListener('click', event => {
    const choice = event.target.closest('[data-choice]'), control = event.target.closest('[data-key]');
    event.stopImmediatePropagation();
    if (choice) { model.panelIndex = Number(choice.dataset.choice); choose(); }
    else if (event.target.closest('.settings')) openPanel('settings');
    else if (control && !control.disabled && !model.panel) { state.focus = control.dataset.key; lastFocus = state.focus; act(state.focus); }
    else if (!controlsVisible) controlsVisible = true;
    render(); screens.querySelector('.view').focus({preventScroll:true});
  }, true);
  document.addEventListener('keydown', event => {
    if (event.target.closest('header,.switcher') || event.target.matches('input,select,textarea,[contenteditable="true"]')) return;
    if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter','Escape','m','M'].includes(event.key)) return;
    event.preventDefault(); event.stopImmediatePropagation();
    const key = event.key;
    if (key === 'Enter' && event.repeat) return;
    model.pressed = key === 'Enter' && controlsVisible && !model.panel && state.focus === 'play';
    if (key === 'Escape') { if (model.panel) closePanel(); else if (controlsVisible) { lastFocus = state.focus; controlsVisible = false; } else showToast('返回上一页面（预览结束）'); }
    else if (key.toLowerCase() === 'm') { if (!model.panel) openPanel('settings'); }
    else if (model.panel) {
      if (key === 'ArrowUp' || key === 'ArrowDown') model.panelIndex = Math.max(0,Math.min(choices().length - 1,model.panelIndex + (key === 'ArrowUp' ? -1 : 1)));
      if (key === 'Enter') choose();
    } else if (!controlsVisible) {
      if (key === 'ArrowLeft' || key === 'ArrowRight') seek(key === 'ArrowLeft' ? -10 : 10);
      if (key === 'Enter') { act('play'); showToast(playing ? '播放' : '暂停'); }
      if (key === 'ArrowDown') { controlsVisible = true; state.focus = keys().includes(lastFocus) ? lastFocus : 'play'; }
      if (key === 'ArrowUp') { if (state.scene !== 'single') openPanel('picker'); else { controlsVisible = true; state.focus = 'play'; } }
    } else if (key === 'ArrowUp') state.focus = 'title';
    else if (key === 'ArrowDown') state.focus = keys().includes(lastFocus) ? lastFocus : 'play';
    else if (key === 'ArrowLeft' || key === 'ArrowRight') {
      if (['title','settings'].includes(state.focus)) state.focus = state.focus === 'title' ? 'settings' : 'title';
      else { const list = keys(); state.focus = list[Math.max(0,Math.min(list.length - 1,list.indexOf(state.focus) + (key === 'ArrowLeft' ? -1 : 1)))]; lastFocus = state.focus; }
    } else if (key === 'Enter') act(state.focus);
    render();
  }, true);
  document.addEventListener('keyup', event => {
    if (event.key === 'Enter' && model.pressed) { model.pressed = false; render(); }
  }, true);
  window.remoteReview = {model, snapshot:() => ({...model, scene:state.scene, focus:state.focus, position, playing, controlsVisible, keys:keys()})};
  render();
})();
