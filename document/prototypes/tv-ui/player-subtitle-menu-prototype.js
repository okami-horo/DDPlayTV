// Throwaway review of the confirmed single-line menu: focus alone starts long-name marquee.
// Mounted on the confirmed player route at ?variant=open&review=subtitle. In-memory fixtures only.
(() => {
  if (params.get('review') !== 'subtitle') return;
  const examples = [
    {id:'zh', name:'简体中文'}, {id:'en', name:'English'}, {id:'dual', name:'简体中文 / English'},
    {id:'traditional', name:'繁體中文（台灣）'}, {id:'ja', name:'日本語'},
    {id:'de', name:'Deutsch (Deutschland) – Untertitel für Hörgeschädigte'},
    {id:'fr', name:'Français (Canada) — sous-titres'},
    {id:'ar', name:'العربية (السعودية) — ترجمة وصفية للصم وضعاف السمع'},
    {id:'file', name:'[字幕组] 药屋少女的呢喃 第50集 [简体中文&English][WEB-DL 1080p][修订版].ass'}
  ];
  const scenarios = ['multiple', 'single', 'many', 'empty', 'loading', 'error'];
  const model = {
    tracks:scenarios.includes(params.get('tracks')) ? params.get('tracks') : 'multiple',
    selected:params.get('subtitle') || 'zh',
    focused:params.get('option') || params.get('subtitle') || 'zh',
    panel:params.get('panel') || 'quick',
    detailFocus:0
  };
  const available = () => examples.slice(0, {multiple:3,single:1,many:9}[model.tracks] || 0);
  const choices = () => [{id:'off', name:'关'}, ...available()];
  function normalize() {
    if (!choices().some(item => item.id === model.selected)) model.selected = 'off';
    if (!choices().some(item => item.id === model.focused) && !(model.tracks === 'error' && model.focused === 'retry')) model.focused = model.selected;
  }
  normalize();
  state.variant = 'open';
  state.focus = 'subtitle';
  lastFocus = 'subtitle';
  document.body.classList.add('subtitle-review');
  document.title = 'DDPlayTV · 字幕快捷面板设计';
  document.querySelector('header h1').textContent = '字幕 · 一个面板完成关闭与切换';
  document.querySelector('header p').textContent = '按用户方案：选项统一宽度、单行等高；只有聚焦的长名称停留约 1 秒后自动滚动，右侧勾选不动。选择“关”关闭，选择字幕即开启。';
  document.querySelector('.scope').textContent = '已确认方案 · 原生已实施';
  document.querySelector('.notes').innerHTML = '<p><strong>01 / 单行等高，聚焦后滚动</strong>短名称保持静止，长名称默认省略；聚焦停留约 1 秒后滚动，末尾停顿后循环，离焦复位。</p><p><strong>02 / 勾选与焦点分开</strong>✓ 标明正在生效的选项，浅色填充标明遥控器焦点；上下浏览不会切换字幕，确认后才应用。勾选不随文字滚动。</p><p><strong>03 / 多语言与详细设置</strong>覆盖中文、英文、德文、法文、阿拉伯文与长文件名，按文字方向滚动。右侧保留样式、同步和添加字幕入口。</p>';
  document.querySelector('.footnote').textContent = '已确认的单行滚动方案预览，轨道为演示数据。上下移动，Enter 选择，Esc / 返回收起；右上角设置仅展示入口结构。长名称只在聚焦停留后滚动，其他项静止；横排旧稿保留作比较。网页状态只在内存中，背景静帧含原有弹幕。';
  const toolbar = document.querySelector('.toolbar');
  toolbar.insertAdjacentHTML('afterbegin', '<label>预览 <select id="subtitle-panel"><option value="quick">字幕快捷面板</option><option value="closed">底部入口</option><option value="details">右侧详细设置</option></select></label><label>字幕 <select id="subtitle-tracks"><option value="multiple">三条字幕</option><option value="single">一条字幕</option><option value="many">多轨 / 长名称</option><option value="empty">暂无字幕</option><option value="loading">读取中</option><option value="error">读取失败</option></select></label><label>生效项 <select id="subtitle-selected"></select></label><label>字号 <select id="subtitle-font"><option value="1">1.0</option><option value="1.3">1.3</option></select></label>');
  toolbar.insertAdjacentHTML('afterbegin', '<label>方案 <select id="subtitle-layout"><option value="vertical">单行滚动稿</option><option value="horizontal">横排旧稿</option></select></label>');
  const style = document.createElement('style');
  style.textContent = `
    .subtitle-review [data-layout-switch],.subtitle-review #focus,.subtitle-review #scene{display:none}
    .subtitle-review label:has(#focus),.subtitle-review label:has(#scene){display:none}
    .subtitle-menu{position:absolute;z-index:5;right:60px;bottom:203px;width:690px;padding:22px 24px;border:1px solid #ffffff23;border-radius:24px;background:linear-gradient(155deg,#292931,#1d1d24);box-shadow:0 12px 42px #0006;color:#f5f5f7}
    .subtitle-menu.compact{width:410px}
    .subtitle-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:17px}
    .subtitle-heading h2{font-size:calc(24px * var(--subtitle-font,1));font-weight:500;line-height:1.25;margin:0}
    .subtitle-current{font-size:calc(17px * var(--subtitle-font,1));color:#b3b3c1}
    .subtitle-options{display:flex;gap:8px;overflow-x:auto;scrollbar-width:none;border-radius:14px}
    .subtitle-options::-webkit-scrollbar{display:none}
    .subtitle-list{position:relative}
    .subtitle-scrollbar{display:none}
    .subtitle-option{display:flex;align-items:center;justify-content:space-between;gap:12px;flex:0 0 calc(166px * var(--subtitle-font,1));min-width:0;height:68px;padding:0 17px;border:1.5px solid transparent;border-radius:14px;background:transparent;color:#ededf2;font:calc(22px * var(--subtitle-font,1)) Arial,"Microsoft YaHei",sans-serif;outline:0}
    .subtitle-option[data-subtitle="off"]{flex-basis:calc(90px * var(--subtitle-font,1))}
    .subtitle-option.selected{background:#ffffff0b}
    .subtitle-option.focused{background:#f5f5f7;color:#191a20;box-shadow:inset 0 0 0 1px #fff}
    .subtitle-option:focus-visible,.subtitle-detail-row:focus-visible{outline:0}
    .subtitle-name{overflow:hidden;white-space:nowrap;text-overflow:ellipsis;min-width:0}
    .subtitle-check{width:21px;flex:none;visibility:hidden}
    .subtitle-option.selected .subtitle-check{visibility:visible}
    .subtitle-check svg{display:block;width:21px;height:21px;stroke:currentColor;fill:none;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round}
    .subtitle-more{position:absolute;right:8px;bottom:44px;font-size:23px;color:#d5d5df;text-shadow:0 1px 6px #000;pointer-events:none}
    .subtitle-full-name{margin:16px 0 0;font-size:calc(18px * var(--subtitle-font,1));line-height:1.45;color:#c3c3cf;overflow-wrap:anywhere}
    .subtitle-message{font-size:21px;line-height:31px;color:#b8b8c5;margin:0 0 16px}
    .subtitle-message small{display:block;font-size:17px;line-height:26px;color:#9292a1}
    .subtitle-retry{font-size:18px;border:1px solid #ffffff40;border-radius:10px;background:#ffffff10;padding:7px 16px;margin-top:12px}
    .subtitle-retry.focused{background:#f5f5f7;color:#191a20;outline:0}
    .subtitle-details-scrim{position:absolute;z-index:4;inset:0;background:linear-gradient(90deg,transparent 36%,#08080d65 78%,#08080d8c)}
    .subtitle-details{position:absolute;z-index:5;top:23px;right:23px;bottom:23px;width:396px;padding:27px;border:1.5px solid #ffffff19;border-radius:33px;background:linear-gradient(#27272f,#1c1c23);box-shadow:0 8px 28px #0005}
    .subtitle-details h2{font-size:30px;font-weight:400;margin:7px 0 24px;padding-bottom:15px;border-bottom:1px solid #ffffff14}
    .subtitle-detail-row{display:flex;width:100%;align-items:center;justify-content:space-between;text-align:left;gap:12px;border:2px solid transparent;border-radius:16px;background:#ffffff09;padding:18px 17px;margin-bottom:13px;color:#f0f0f4;font:22px Arial,"Microsoft YaHei",sans-serif}
    .subtitle-detail-row.focused{background:#383842;border-color:#e3e3eb}
    .subtitle-detail-row small{display:block;font-size:17px;color:#acacbc;margin-top:7px}
    .subtitle-detail-row b{font-weight:400;color:#b9b9c5}
    .subtitle-review .settings{border:0;color:#f5f5f7;padding:0}
    .subtitle-review .settings.focused{box-shadow:inset 0 0 0 2px #f5f5f7;background:#ffffff25}
    .canvas[data-subtitle-layout="vertical"] .subtitle-menu{width:480px;padding:16px;border-radius:22px}
    .canvas[data-subtitle-layout="vertical"] .subtitle-heading{margin:0 16px 12px}
    .canvas[data-subtitle-layout="vertical"] .subtitle-current{display:none}
    .canvas[data-subtitle-layout="vertical"] .subtitle-options{flex-direction:column;gap:4px;max-height:300px;padding-right:10px;overflow-x:hidden;overflow-y:auto;scrollbar-width:none;scroll-padding:30px 0}
    .canvas[data-subtitle-layout="vertical"] .subtitle-scrollbar{display:block;position:absolute;right:0;top:4px;bottom:4px;width:3px;border-radius:3px;background:#ffffff0e;pointer-events:none}
    .canvas[data-subtitle-layout="vertical"] .subtitle-scrollbar[hidden]{display:none}
    .subtitle-scrollbar i{position:absolute;left:0;width:3px;border-radius:3px;background:#ffffff60}
    .canvas[data-subtitle-layout="vertical"] .subtitle-options[data-overflow="top"]{mask-image:linear-gradient(to bottom,transparent,#000 30px)}
    .canvas[data-subtitle-layout="vertical"] .subtitle-options[data-overflow="bottom"]{mask-image:linear-gradient(to top,transparent,#000 30px)}
    .canvas[data-subtitle-layout="vertical"] .subtitle-options[data-overflow="both"]{mask-image:linear-gradient(to bottom,transparent,#000 30px,#000 calc(100% - 30px),transparent)}
    .canvas[data-subtitle-layout="vertical"] .subtitle-option{display:grid;grid-template-columns:minmax(0,1fr) 24px;gap:16px;flex:0 0 auto;width:100%;height:calc(72px * var(--subtitle-font,1));padding:0 18px;text-align:start;border-radius:12px}
    .canvas[data-subtitle-layout="vertical"] .subtitle-name{display:block;overflow:hidden;white-space:nowrap;line-height:1.4;text-overflow:ellipsis}
    .subtitle-name-text{display:inline-block;max-width:100%;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;vertical-align:top}
    .subtitle-name.marquee .subtitle-name-text{max-width:none;overflow:visible;text-overflow:clip}
    .canvas[data-subtitle-layout="vertical"] .subtitle-option.selected{background:transparent}
    .canvas[data-subtitle-layout="vertical"] .subtitle-option.focused{background:#f5f5f7}
    .canvas[data-subtitle-layout="vertical"] .subtitle-check{width:24px;justify-self:end}
    .canvas[data-subtitle-layout="vertical"] .subtitle-full-name,.canvas[data-subtitle-layout="vertical"] .subtitle-more{display:none}
    .canvas[data-subtitle-layout="vertical"] .subtitle-message{margin:0 16px 12px}
  `;
  document.head.append(style);
  const originalRender = render;
  const originalAct = act;
  const tick = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4.5 12L9.5 17L19.5 7"/></svg>';
  const escapeText = text => text.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');
  let marqueeTimer, marqueeAnimation;
  function startFocusedMarquee(canvas) {
    if (canvas.dataset.subtitleLayout !== 'vertical') return;
    const name = canvas.querySelector('.subtitle-option.focused .subtitle-name');
    if (!name) return;
    const text = name.firstElementChild;
    const distance = text.scrollWidth-name.clientWidth;
    if (distance <= 1) return;
    name.dataset.marquee = 'waiting';
    marqueeTimer = setTimeout(() => {
      name.classList.add('marquee');
      name.dataset.marquee = 'running';
      const target = getComputedStyle(name).direction === 'rtl' ? distance : -distance;
      const travel = distance/45*1000, duration = travel+1200;
      marqueeAnimation = text.animate([
        {transform:'translateX(0)',offset:0},
        {transform:`translateX(${target}px)`,offset:travel/duration},
        {transform:`translateX(${target}px)`,offset:1}
      ], {duration,iterations:Infinity,easing:'linear'});
    },1000);
  }
  function quickPanel() {
    const items = available();
    const message = {
      empty:'暂无可用字幕<small>可在右侧设置中添加外挂字幕</small>',
      loading:'正在读取字幕…',
      error:`字幕读取失败<small>可重试，或在右侧添加字幕</small><button class="subtitle-retry ${model.focused === 'retry' ? 'focused' : ''}" data-retry>重试</button>`
    }[model.tracks];
    const focused = choices().find(item => item.id === model.focused);
    const full = focused?.id === 'file' ? `<p class="subtitle-full-name">${escapeText(focused.name)}</p>` : '';
    return `<section class="subtitle-menu ${items.length <= 1 ? 'compact' : ''}" role="dialog" aria-modal="true" aria-label="字幕快捷选择"><div class="subtitle-heading"><h2>字幕</h2><span class="subtitle-current">${items.length} 条可用</span></div>${message ? `<p class="subtitle-message">${message}</p>` : ''}<div class="subtitle-list"><div class="subtitle-options" role="radiogroup" aria-label="字幕"><!-- Selecting a track also turns subtitles on. -->${choices().map(item => `<button class="subtitle-option ${item.id === model.selected ? 'selected' : ''} ${item.id === model.focused ? 'focused' : ''}" role="radio" aria-checked="${item.id === model.selected}" aria-label="${escapeText(item.name)}" tabindex="${item.id === model.focused ? '0' : '-1'}" data-subtitle="${item.id}"><bdi dir="auto" class="subtitle-name"><span class="subtitle-name-text">${escapeText(item.name)}</span></bdi><span class="subtitle-check">${tick}</span></button>`).join('')}</div><span class="subtitle-scrollbar" hidden aria-hidden="true"><i></i></span></div><span class="subtitle-more" hidden aria-hidden="true">›</span>${full}</section>`;
  }
  function detailsPanel() {
    const rows = [['样式与位置','字号、垂直位置等'],['时间同步','调整字幕提前或延后'],['添加外挂字幕','选择字幕文件']];
    return `<div class="subtitle-details-scrim"></div><section class="subtitle-details" aria-label="字幕详细设置"><h2>字幕设置</h2>${rows.map((item,i) => `<button class="subtitle-detail-row ${model.detailFocus === i ? 'focused' : ''}" data-detail="${i}"><span>${item[0]}<small>${item[1]}</small></span><b>›</b></button>`).join('')}</section>`;
  }
  render = function() {
    clearTimeout(marqueeTimer);
    marqueeAnimation?.cancel();
    originalRender();
    normalize();
    const canvas = document.querySelector('.canvas');
    canvas.dataset.subtitleLayout = params.get('layout') === 'horizontal' ? 'horizontal' : 'vertical';
    canvas.style.setProperty('--subtitle-font', params.get('font') === '1.3' ? '1.3' : '1');
    const button = canvas.querySelector('.control.subtitle');
    const danmaku = canvas.querySelector('.control.danmaku');
    if (danmaku) {
      danmaku.querySelector('span').textContent = state.danmaku === 'on' ? '弹幕开' : '弹幕关';
      danmaku.setAttribute('aria-pressed', String(state.danmaku === 'on'));
      if (state.danmaku === 'off') danmaku.querySelector('svg').insertAdjacentHTML('beforeend', '<path d="M3 2L22 22"/>');
    }
    if (button) {
      button.setAttribute('aria-haspopup', 'dialog');
      button.setAttribute('aria-expanded', String(model.panel === 'quick'));
      button.setAttribute('aria-label', '字幕'+(model.selected === 'off' ? '已关闭' : '：'+examples.find(item => item.id === model.selected)?.name)+'，按确认选择');
      if (model.selected === 'off') {
        button.querySelector('svg').innerHTML = '<path d="M4 5H18.5A3 3 0 0 1 21.5 8V16M18 19.5H5.5A3 3 0 0 1 2.5 16.5V8M6 14.5H9M14 10H18M3 2L22 22"/>';
      }
    }
    canvas.querySelector('.video-subtitle').style.visibility = model.selected === 'off' ? 'hidden' : 'visible';
    canvas.querySelector('.settings').outerHTML = `<button class="settings" aria-label="字幕详细设置" data-subtitle-settings>${svg('settings','open')}</button>`;
    if (model.panel !== 'closed') canvas.querySelectorAll('.control').forEach(item => item.classList.remove('focused'));
    if (model.panel === 'quick' && controlsVisible) {
      canvas.insertAdjacentHTML('beforeend', quickPanel());
      const option = canvas.querySelector('.subtitle-option.focused,.subtitle-retry.focused');
      option.scrollIntoView({block:'nearest',inline:'nearest'});
      option.focus({preventScroll:true});
      const scroller = canvas.querySelector('.subtitle-options');
      const updateOverflow = () => {
        const top = scroller.scrollTop > 1, bottom = scroller.scrollTop+scroller.clientHeight < scroller.scrollHeight-1;
        scroller.dataset.overflow = top && bottom ? 'both' : top ? 'top' : bottom ? 'bottom' : 'none';
        const scrollbar = canvas.querySelector('.subtitle-scrollbar');
        scrollbar.hidden = !top && !bottom;
        scrollbar.firstElementChild.style.height = scroller.clientHeight/scroller.scrollHeight*100+'%';
        scrollbar.firstElementChild.style.top = scroller.scrollTop/scroller.scrollHeight*100+'%';
      };
      updateOverflow();
      scroller.addEventListener('scroll',updateOverflow);
      canvas.querySelector('.subtitle-more').hidden = scroller.scrollLeft+scroller.clientWidth >= scroller.scrollWidth-2;
      startFocusedMarquee(canvas);
    }
    if (model.panel === 'details') {
      canvas.insertAdjacentHTML('beforeend', detailsPanel());
      canvas.querySelector('.subtitle-detail-row.focused').focus({preventScroll:true});
    }
    if (model.panel === 'closed' && controlsVisible) canvas.querySelector(`.control.${state.focus}`)?.focus({preventScroll:true});
    document.querySelector('.caption').innerHTML = `${model.panel === 'details' ? '右侧设置 · 入口结构示意' : '字幕快捷面板'} <small>已确认单行滚动方案</small>`;
    document.getElementById('subtitle-panel').value = model.panel;
    document.getElementById('subtitle-tracks').value = model.tracks;
    document.getElementById('subtitle-selected').innerHTML = choices().map(item => `<option value="${item.id}">${escapeText(item.name)}</option>`).join('');
    document.getElementById('subtitle-selected').value = model.selected;
    document.getElementById('subtitle-font').value = params.get('font') === '1.3' ? '1.3' : '1';
    document.getElementById('subtitle-layout').value = canvas.dataset.subtitleLayout;
    document.getElementById('variant-label').textContent = `${canvas.dataset.subtitleLayout === 'vertical' ? '单行滚动稿' : '横排旧稿'} · ${model.panel === 'quick' ? (canvas.dataset.subtitleLayout === 'vertical' ? '上下选择 / 确认应用' : '左右选择 / 确认应用') : model.panel === 'details' ? '返回收起详细设置' : '确认打开字幕面板'}`;
    Object.entries({tracks:model.tracks,subtitle:model.selected,option:model.focused,panel:model.panel}).forEach(([key,value]) => params.set(key,value));
    history.replaceState(null,'','?'+params);
  };
  function closePanel() {
    model.panel = 'closed';
    state.focus = 'subtitle';
    lastFocus = 'subtitle';
  }
  function choose(id) {
    if (id === 'retry') {model.tracks = 'multiple';model.focused = model.selected;render();return;}
    model.selected = id;
    model.focused = id;
    closePanel();
    render();
  }
  act = function(key) {
    if (key === 'subtitle') {
      model.panel = model.panel === 'quick' ? 'closed' : 'quick';
      model.focused = model.selected;
    } else if (key === 'danmaku') state.danmaku = state.danmaku === 'on' ? 'off' : 'on';
    else originalAct(key);
  };
  document.getElementById('subtitle-panel').onchange = event => {model.panel = event.target.value;controlsVisible = true;render();};
  document.getElementById('subtitle-tracks').onchange = event => {model.tracks = event.target.value;normalize();model.panel = 'quick';controlsVisible = true;render();};
  document.getElementById('subtitle-selected').onchange = event => {model.selected = event.target.value;model.focused = model.selected;render();};
  document.getElementById('subtitle-font').onchange = event => {params.set('font',event.target.value);render();};
  document.getElementById('subtitle-layout').onchange = event => {params.set('layout',event.target.value);render();};
  document.getElementById('previous-variant').onclick = document.getElementById('next-variant').onclick = () => {model.panel = model.panel === 'quick' ? 'closed' : 'quick';model.focused = model.selected;controlsVisible = true;render();};
  document.getElementById('screens').addEventListener('click', event => {
    const option = event.target.closest('[data-subtitle]');
    if (option) choose(option.dataset.subtitle);
    if (event.target.closest('[data-subtitle-settings]')) {model.panel = 'details';render();}
    if (event.target.closest('[data-retry]')) {model.tracks = 'multiple';model.focused = 'off';render();}
    const detail = event.target.closest('[data-detail]');
    if (detail) {
      model.detailFocus = Number(detail.dataset.detail);
      document.querySelector('header p').textContent = `“${['样式与位置','时间同步','添加外挂字幕'][model.detailFocus]}”将沿用现有配置页面。本稿只确认快捷面板样式和右侧入口职责。`;
      render();
    }
  });
  document.addEventListener('keydown', event => {
    if (event.target.matches('input,select,textarea')) return;
    if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter','Escape','Backspace'].includes(event.key)) return;
    if (model.panel === 'closed') {
      if (['ArrowDown','Enter'].includes(event.key) && !controlsVisible) {
        event.preventDefault();event.stopImmediatePropagation();controlsVisible = true;state.focus = lastFocus;render();
      }
      return;
    }
    event.preventDefault();
    event.stopImmediatePropagation();
    if (event.key === 'Escape' || event.key === 'Backspace') closePanel();
    else if (model.panel === 'quick') {
      if (event.key === 'Enter') {choose(model.focused);return;}
      const vertical = params.get('layout') !== 'horizontal';
      const items = model.tracks === 'error' && vertical ? [{id:'retry'}, ...choices()] : choices();
      const index = items.findIndex(item => item.id === model.focused);
      const previous = vertical ? 'ArrowUp' : 'ArrowLeft', next = vertical ? 'ArrowDown' : 'ArrowRight';
      if (event.key === previous || event.key === next) model.focused = items[Math.max(0,Math.min(items.length-1,index+(event.key === previous ? -1 : 1)))].id;
      if (model.tracks === 'error' && !vertical && event.key === 'ArrowUp') model.focused = 'retry';
      if (model.tracks === 'error' && !vertical && event.key === 'ArrowDown') model.focused = 'off';
    } else if (model.panel === 'details') {
      if (event.key === 'ArrowUp' || event.key === 'ArrowDown') model.detailFocus = Math.max(0,Math.min(2,model.detailFocus+(event.key === 'ArrowUp' ? -1 : 1)));
      if (event.key === 'Enter') document.querySelector('header p').textContent = `“${['样式与位置','时间同步','添加外挂字幕'][model.detailFocus]}”将沿用现有配置页面。本稿只确认快捷面板样式和右侧入口职责。`;
    }
    render();
  }, true);
  window.subtitleMenuPreview = {state:model};
  render();
})();
