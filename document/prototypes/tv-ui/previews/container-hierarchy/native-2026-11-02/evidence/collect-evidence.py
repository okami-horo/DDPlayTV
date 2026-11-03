from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import hashlib
import html
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

root = Path('E:/Project/DDPlayTV')
task = root / '.tmp/a03-containers'
out = root / 'document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02'
(out / 'images').mkdir(parents=True, exist_ok=True)
(out / 'evidence').mkdir(exist_ok=True)
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
pages = [
    ('C01', '缓存管理', 'ui-polish-verification/CacheManagerActivity'),
    ('C02', '常用目录', 'ui-polish-verification/CommonlyFolderActivity'),
    ('C03', '深色模式', 'ui-polish-verification/SwitchThemesActivity'),
    ('C04', '开源许可', 'ui-polish-verification/LicenseActivity'),
    ('C05', '关于与归属', 'ui-polish-verification/AboutUsActivity'),
    ('C06', '番剧简介', 'ui-remaining-verification/detail-intro'),
    ('C07', '番剧分集', 'ui-remaining-verification/detail-episodes'),
    ('C08', '相关作品', 'ui-remaining-verification/detail-recommend'),
]
extra = [
    ('C01', '系统缓存确认／取消', 'cache-system-confirm'),
    ('C01', '延时确认与取消', 'cache-delayed-confirm'),
    ('C01', '截图缓存行焦点', 'cache-screenshot-row'),
    ('C01', '清理任务夹具后统计', 'cache-cleared'),
    ('C02', '未设置与长路径', 'folders-empty-long'),
    ('C02', '目录选择器', 'folders-picker'),
    ('C02', '遥控器长按清除', 'folders-clear-confirm'),
    ('C03', '手动模式', 'themes-manual'),
    ('C03', '跟随系统', 'themes-system'),
    ('C03', '重启确认可取消', 'themes-restart-confirm'),
    ('C04', '全部真实许可首条', 'license-first'),
    ('C04', '超长许可开头', 'license-long-start'),
    ('C04', '超长许可内部滚动', 'license-long-scrolled'),
    ('C04', '完整列表末条', 'license-last'),
    ('C05', '第一个网站焦点', 'about-first-link'),
    ('C05', '第二个网站焦点与滚动', 'about-second-link'),
]
for code, label, source in pages[:5]:
    extra.append((code, '普通主题 · ' + label, 'light-' + source.rsplit('/', 1)[1]))
detail_extra = [
    ('C06', '展开资料与长简介', 'detail-intro-long'),
    ('C06', '无简介', 'detail-intro-empty'),
    ('C07', '独立行内图标焦点', 'detail-episode-action'),
    ('C07', '批量全选', 'detail-episodes-mark'),
    ('C07', '批量反选', 'detail-episodes-inverted'),
    ('C07', '空分集', 'detail-episodes-empty'),
    ('C08', '三列滚动到底', 'detail-recommend-scroll'),
    ('C08', '遥控器返回页签', 'detail-recommend-return'),
    ('C08', '无推荐时保留原两页签规则', 'detail-no-recommend-tab'),
]
images = []

def add(stage, font, code, label, source):
    folder = f'{stage}-' + ('normal' if font == '1' else 'font130')
    p = task / folder / (source + '.png')
    assert p.is_file(), p
    im = Image.open(p)
    assert im.size == (1440, 810), (p, im.size)
    filename = f'{stage}-font{font.replace(".", "")}-{source.rsplit("/", 1)[1]}.png'
    target = out / 'images' / filename
    shutil.copyfile(p, target)
    item = dict(stage=stage, font=font, group=code, title=label, path='images/' + filename,
                source=p.relative_to(root).as_posix(), sha256=sha(target))
    images.append(item)
    return item

pairs = []
for font in ['1', '1.3']:
    for code, label, source in pages:
        before = add('before', font, code, label, source)
        after = add('after', font, code, label, source)
        pairs.append(dict(group=code, title=label, font=font, before=before['path'], after=after['path']))
    for code, label, source in extra:
        add('after', font, code, label, 'a03-verification/' + source)
    for code, label, source in detail_extra:
        add('after', font, code, label, 'ui-remaining-verification/' + source)

font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 28)
for pair in pairs:
    if pair['font'] == '1' and pair['group'] in ['C01', 'C04', 'C07'] or pair['font'] == '1.3' and pair['group'] == 'C02':
        board = Image.new('RGB', (2880, 862), '#202026')
        draw = ImageDraw.Draw(board)
        draw.text((24, 8), '调整前 · 实际 APK · ' + pair['title'], font=font, fill='white')
        draw.text((1464, 8), '调整后 · 实际 APK · 字体 ' + pair['font'], font=font, fill='white')
        board.paste(Image.open(out / pair['before']), (0, 52))
        board.paste(Image.open(out / pair['after']), (1440, 52))
        board.save(out / ('compare-' + pair['group'] + '-font' + pair['font'] + '.png'))

logs = []
for p in sorted(task.glob('*.log')):
    if p.name.startswith('emulator'):
        continue
    shutil.copyfile(p, out / 'evidence' / p.name)
    logs.append(dict(path='evidence/' + p.name, sha256=sha(p)))
shutil.copyfile(task / 'scoped-format.gradle', out / 'evidence/scoped-format.gradle')
for filename in ['about-second-link-font130.xml', 'about-return-font130.xml']:
    shutil.copyfile(task / filename, out / 'evidence' / filename)
shutil.copyfile(task / 'about-second-link-font130.png', out / 'evidence/about-focus-red.png')

native_runs = []
for filename, font, passed, failures, scope in [
    ('native-complete-normal.log', '1.0', 9, 0, 'A03 tools, detail children, empty detail and both About actions'),
    ('native-complete-font130.log', '1.3', 9, 0, 'The same nine A03 cases'),
    ('regression-complete-normal.log', '1.0', 7, 0, 'Cinema/BACK/settings, A02 selectors/subtitles, player episode/panel stack and both real kernels'),
    ('regression-complete-font130.log', '1.3', 2, 0, 'A02 selectors and both real kernels with right panel/BACK'),
]:
    log = (task / filename).read_text(encoding='utf-8-sig')
    passed_tests = re.findall(r'INSTRUMENTATION_STATUS: test=(\w+)\s+INSTRUMENTATION_STATUS_CODE: 0', log)
    failed_tests = re.findall(r'INSTRUMENTATION_STATUS: test=(\w+)\s+INSTRUMENTATION_STATUS_CODE: -2', log)
    assert len(passed_tests) == passed and len(failed_tests) == failures, filename
    native_runs.append(dict(log='evidence/' + filename, fontScale=font, scope=scope,
                            passedTests=passed_tests, failedTests=failed_tests,
                            runner='com.okamihoro.ddplaytv.debug.test/androidx.test.runner.AndroidJUnitRunner'))

paths = set(subprocess.check_output(['git', 'diff', '--name-only', 'HEAD'], cwd=root, text=True).splitlines())
paths.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines())
source_hashes = {p: sha(root / p) for p in sorted(paths) if p.startswith(('user_component/src/', 'anime_component/src/', 'app/src/androidTest/')) and (root / p).is_file()}
reports = []
for p in sorted(root.glob('*/build/test-results/testDebugUnitTest/TEST-*.xml')):
    data = ET.parse(p).getroot().attrib
    reports.append(dict(report=p.relative_to(root).as_posix(), sha256=sha(p), name=data['name'],
                        **{k: int(data.get(k, 0)) for k in ['tests', 'failures', 'errors', 'skipped']}))
(out / 'evidence/jvm-report-summary.json').write_text(json.dumps(reports, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
evidence = dict(date='2026-11-02', base='5324dd7f3', viewport=[1440, 810], densityDpi=240,
    device='task-owned read-only Pixel_5_API_34 / emulator-5588 / private ADB 5087', images=images,
    sourceSha256=source_hashes, apkSha256={p: sha(root / p) for p in ['.tmp/a03-containers/baseline-main.apk', '.tmp/a03-containers/baseline-test.apk', '.tmp/a03-containers/pre-about-main.apk', '.tmp/a03-containers/pre-about-test.apk', '.tmp/a03-containers/final-main.apk', '.tmp/a03-containers/final-test.apk']},
    prototypeSha256=sha(root / 'document/prototypes/tv-ui/container-hierarchy-prototype.html'), logs=logs,
    jvmTotals={k: sum(s[k] for s in reports) for k in ['tests', 'failures', 'errors', 'skipped']},
    nativeRuns=native_runs, installedApkIdentity='evidence/installed-apk-identity.log',
    nativeImageCount=len(images), comparisonCount=len(pairs),
    limits=['Anime data and covers use local fixtures; the APK renders actual Android views.',
            'Cache totals include task screenshot output and can differ; screenshots are not retouched.',
            'Folder groups and license spacing intentionally differ. At font 1.3, shortcut rows now grow to fit text.',
            'No recommendations retains the original behavior of hiding its tab, instead of adding an empty page.',
            'No physical-TV operation, live account/marking acceptance, release, version change, staging or commit.'])
(out / 'verification.json').write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')

document = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>A03 原生前后对比</title><script src="../../../../../local-screenshot-fallback.js"></script>
<style>
*{box-sizing:border-box}body{margin:0;background:#111114;color:#f5f5f7;font:15px/1.7 system-ui,"Microsoft YaHei",sans-serif}header{padding:32px 4vw 24px;border-bottom:1px solid #303038}h1{font-size:28px;margin:0 0 12px}p{max-width:1180px;color:#b3b3bd;margin:10px 0}a{color:#d4d9f3}nav{display:flex;flex-wrap:wrap;gap:20px;padding:20px 4vw;position:sticky;top:0;background:#17171bea;backdrop-filter:blur(12px);z-index:1}select{font:inherit;padding:8px;background:#28282e;color:white;border:1px solid #50505c;border-radius:8px}main{padding:0 4vw 40px}figure{margin:20px 0 40px}figcaption{font-size:18px;padding:10px 0}.pair{display:grid;grid-template-columns:1fr 1fr;gap:12px}img{display:block;width:100%;height:auto;background:#202026;border:1px solid #303038;border-radius:6px}.tag{color:#9999a6;font-size:13px;margin-bottom:6px}.single{max-width:1440px}.count{align-self:center;color:#a4a4ae}footer{padding:24px 4vw;border-top:1px solid #303038;color:#9999a6}@media(max-width:900px){.pair{grid-template-columns:1fr}h1{font-size:23px}nav{position:static;gap:12px}header,main{padding-left:18px;padding-right:18px}}
</style><header><p>截图仅在本地归档，不随 Git 分发；<a href="../../../../../README.md#截图与预览产物">恢复方式与保存约定</a>。</p>
<h1>A03 · 实际 APK 的容器减层</h1>
<p>八页原生前后对比，1440 × 810／240dpi；可查看 1.0／1.3 字体、普通主题和操作状态。番剧使用本地数据夹具与原生占位封面，工具页保留真实统计、许可和 TMDB 归属。两侧均为模拟器 APK 截图。</p>
<p>目录分组线、许可留白为设计变化；大字体目录行按内容增高。缓存统计随截图产物变化。无推荐继续隐藏推荐页签。普通主题仍有既有 Cinema 静态标题配色问题，本轮适配许可正文／链接与新增焦点材质；实体电视及在线账号验收另行记录。</p>
<p><a href="../../../container-hierarchy-prototype.html">已确认的设计重建</a> · <a href="verification.json">身份与验证记录</a> · <a href="../../../../../../docs/superpowers/plans/2026-11-02-a03-container-hierarchy.md">执行账本</a></p></header>
<nav><label>查看 <select id="view"><option value="compare">原生前后</option><option value="after">改后页面／状态</option><option value="before">改前八页</option></select></label><label>页面 <select id="group"><option value="all">全部</option>GROUPS</select></label><label>字体 <select id="font"><option value="1">1.0</option><option value="1.3">1.3</option></select></label><span class="count" id="count"></span></nav><main id="gallery"></main>
<footer>原图未经裁剪或改色。点击图片可查看 1440 × 810 原图；复合图只在画面外添加前后标签。完整构建／测试日志及失败记录见 evidence/。</footer><script>
const images=IMAGES,pairs=PAIRS;const $=id=>document.getElementById(id);
function pic(path,label){return `<div><div class="tag">${label}</div><a href="${path}" target="_blank"><img src="${path}" alt="${label}" loading="lazy" width="1440" height="810"></a></div>`}
function render(){const view=$('view').value,group=$('group').value,font=$('font').value;let items=(view==='compare'?pairs:images.filter(i=>i.stage===view)).filter(i=>i.font===font&&(group==='all'||i.group===group));$('gallery').innerHTML=items.map(i=>`<figure><figcaption>${i.group} · ${i.title}</figcaption>`+(view==='compare'?`<div class="pair">${pic(i.before,'调整前 · 实际 APK')}${pic(i.after,'调整后 · 实际 APK')}</div>`:`<div class="single">${pic(i.path,(view==='before'?'调整前':'调整后')+' · 字体 '+font)}</div>`)+`</figure>`).join('');$('count').textContent=items.length+' 组';}
for(const id of ['view','group','font'])$(id).addEventListener('change',render);render();
</script></html>'''
document = document.replace('GROUPS', ''.join(f'<option value="{code}">{label}</option>' for code, label, _ in pages))
document = document.replace('IMAGES', json.dumps(images, ensure_ascii=False)).replace('PAIRS', json.dumps(pairs, ensure_ascii=False))
(out / 'index.html').write_text(document, encoding='utf-8')
shutil.copyfile(__file__, out / 'evidence/collect-evidence.py')
print(json.dumps(dict(nativeImages=len(images), comparisons=len(pairs), sourceFiles=len(source_hashes), jvm=evidence['jvmTotals']), ensure_ascii=False))
