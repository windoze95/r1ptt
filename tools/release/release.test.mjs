import test from 'node:test';
import assert from 'node:assert/strict';
import { releaseVersion, requireNewer } from './version.mjs';
import { apkFacts, payloadFor } from './manifest.mjs';

const cert = 'a'.repeat(64), commit = 'b'.repeat(40);
const facts = { packageName: 'dev.r1ptt', versionCode: 2000, versionName: '0.2.0', minSdk: 33, certificateSha256: cert };
const apk = Buffer.from('synthetic APK fixture');

test('version code is deterministic, positive and ordered across semantic boundaries', () => {
  assert.deepEqual(releaseVersion('v0.2.0'), { tag: 'v0.2.0', versionName: '0.2.0', versionCode: 2000 });
  assert.equal(releaseVersion('v2099.999.999').versionCode, 2099999999);
  assert.ok(releaseVersion('v1.0.0').versionCode > releaseVersion('v0.999.999').versionCode);
  for (const tag of ['v0.0.0', 'v0.0.1', 'v2100.0.0', 'v1.1000.0', 'v01.2.0', 'v1.2.03', 'v1.2.3-beta', 'v1.2.3/evil', '1.2.3', 'v1.2.3\n'])
    assert.throws(() => releaseVersion(tag), undefined, tag);
});
test('release inventory rejects duplicate tags, downgrades and malformed or unknown history', () => {
  assert.equal(requireNewer('v0.2.0', [[]]).versionCode, 2000);
  assert.equal(requireNewer('v1.0.0', [[{ draft: false, tag_name: 'v0.2.0' }]]).versionCode, 1000000);
  for (const pages of [
    [[{ draft: false, tag_name: 'v0.3.0' }]], [[{ draft: true, tag_name: 'v0.2.0' }]],
    [[{ draft: false, tag_name: 'v0.1.0' }], [{ draft: false, tag_name: 'v1.0.0' }]],
    [[{ draft: false, tag_name: 'unknown-legacy-tag' }]], [[{ draft: 'false', tag_name: 'v1.0.0' }]], {},
  ]) assert.throws(() => requireNewer('v0.2.0', pages));
});
test('manifest binds package, signer, size, checksum, version and commit', () => {
  const payload = payloadFor('v0.2.0', commit, apk, facts, cert);
  assert.equal(payload.apkSize, apk.length);
  assert.match(payload.apkSha256, /^[0-9a-f]{64}$/);
  assert.equal(payload.apkName, 'robotOS.apk');
  assert.equal(payload.repository, 'windoze95/robotOS');
  for (const bad of [{ packageName: 'other' }, { versionCode: 1 }, { versionName: 'other' }, { minSdk: 34 }, { certificateSha256: 'c'.repeat(64) }])
    assert.throws(() => payloadFor('v0.2.0', commit, apk, { ...facts, ...bad }, cert));
  assert.throws(() => payloadFor('v0.2.0', commit, apk, facts, undefined));
  assert.throws(() => payloadFor('v0.2.0', 'main', apk, facts, cert));
  assert.throws(() => payloadFor('v0.2.0', commit, Buffer.alloc(0), facts, cert));
});
test('APK inspection requires a sole verified signer and complete identity', () => {
  const badging = "package: name='dev.r1ptt' versionCode='2000' versionName='0.2.0'\nsdkVersion:'33'\n";
  const signing = `Signer #1 certificate SHA-256 digest: ${cert}\n`;
  assert.deepEqual(apkFacts(badging, signing), facts);
  assert.throws(() => apkFacts(badging, `${signing}Signer #2 certificate SHA-256 digest: ${cert}\n`));
  assert.throws(() => apkFacts(badging, 'not signed'));
  assert.throws(() => apkFacts('', signing));
});
