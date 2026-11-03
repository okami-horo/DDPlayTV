from pathlib import Path
import datetime
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

root = Path('E:/Project/DDPlayTV')
task = root / '.tmp/a03-release'
output = root / 'app/build/outputs/apk/release'
evidence = root / 'document/prototypes/tv-ui/previews/container-hierarchy/native-2026-11-02/evidence'
sdk_tools = Path('C:/Android/Sdk/build-tools/36.0.0')
java = root / '.dev-env/jdk/ms-17.0.15/bin/java.exe'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
build_log = task / 'assemble-release.log'
log = build_log.read_text(encoding='utf-8-sig')
result = re.findall(r'BUILD SUCCESSFUL in [^\r\n]+', log)
assert result and 'BUILD FAILED' not in log, 'Release build did not complete successfully'
validated = json.loads((task / 'validated-inputs.json').read_text(encoding='utf-8'))
for name, expected in validated.items():
    assert sha(root / name) == expected, name
staged = subprocess.check_output(['git', 'diff', '--cached', '--raw', '--no-abbrev'], cwd=root)
assert hashlib.sha256(staged).hexdigest() == json.loads((task / 'staged-before.json').read_text())['sha256']
metadata = json.loads((output / 'output-metadata.json').read_text(encoding='utf-8'))
assert metadata['applicationId'] == 'com.okamihoro.ddplaytv'
assert len(metadata['elements']) == 3
artifacts = []
for item in metadata['elements']:
    assert item['versionCode'] == 72 and item['versionName'] == '4.2.5'
    apk = output / item['outputFile']
    assert apk.is_file(), apk
    badging = subprocess.check_output([str(sdk_tools / 'aapt2.exe'), 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    assert package and package.groups() == ('com.okamihoro.ddplaytv', '72', '4.2.5'), badging[:250]
    assert 'application-debuggable' not in badging
    signature = subprocess.check_output([str(java), '-jar', str(sdk_tools / 'lib/apksigner.jar'), 'verify', '--verbose', '--print-certs', str(apk)], text=True, encoding='utf-8')
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
    cert = re.search(r'Signer #1 certificate SHA-256 digest: (\w+)', signature)
    assert cert
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')})
    expected_abi = next((f['value'] for f in item['filters'] if f['filterType'] == 'ABI'), None)
    assert abis == ([expected_abi] if expected_abi else ['arm64-v8a', 'armeabi-v7a']), (apk.name, abis)
    signature_path = evidence / ('release-4.2.5-' + (expected_abi or 'universal') + '-signature.log')
    signature_path.write_text(signature, encoding='utf-8')
    artifacts.append(dict(path=apk.relative_to(root).as_posix(), bytes=apk.stat().st_size,
                          sha256=sha(apk), versionName='4.2.5', versionCode=72,
                          applicationId='com.okamihoro.ddplaytv', debuggable=False,
                          abis=abis, signatureV2Verified=True, certificateSha256=cert.group(1),
                          signatureLog=signature_path.name))
assert len({item['certificateSha256'] for item in artifacts}) == 1
record = dict(verifiedAt=datetime.datetime.now().astimezone().isoformat(),
              base=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
              buildCommand='scripts/dev-env/gradlew-local.bat :app:assembleRelease --offline',
              javaToolOptions='-Djdk.net.unixdomain.tmpdir=E:/Project/DDPlayTV/.tmp/wh-jvm -Djava.io.tmpdir=E:/Project/DDPlayTV/.tmp/wh-jvm',
              buildResult=result[-1], buildLogSha256=sha(build_log),
              versionsSourceSha256=sha(root / 'buildSrc/src/main/java/Versions.kt'),
              validatedA03InputsUnchanged=validated, stagedBlobIdentitiesUnchanged=True,
              artifacts=artifacts,
              runtimeEvidence='Reuse the unchanged A03 inputs and earlier JVM/DPAD checks; no new runtime test or device operation.',
              signingConfiguration=('Existing release configuration used its debug-signing fallback; release credentials were absent.'
                                    if 'fallback to debug signing config' in log else 'Configured release signing.'),
              limits=['No APK installation, physical-TV operation, staging or commit.'])
shutil.copyfile(build_log, evidence / 'release-4.2.5-build.log')
shutil.copyfile(__file__, evidence / 'verify-release-4.2.5.py')
path = evidence / 'release-4.2.5-verification.json'
path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
print(json.dumps({'buildResult':record['buildResult'], 'artifacts':artifacts, 'record':str(path)}, ensure_ascii=False, indent=2))
