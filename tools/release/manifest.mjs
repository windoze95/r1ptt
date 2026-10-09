import fs from 'node:fs';
import { createHash, X509Certificate, verify } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { releaseVersion } from './version.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
export function apkFacts(badging, signing) {
  const pkg = /^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m.exec(badging);
  const sdk = /^sdkVersion:'(\d+)'$/m.exec(badging);
  const certs = [...signing.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$/gm)];
  if (!pkg || !sdk || certs.length !== 1) throw new Error('APK must have one verified signer and complete package metadata.');
  return { packageName: pkg[1], versionCode: Number(pkg[2]), versionName: pkg[3], minSdk: Number(sdk[1]), certificateSha256: certs[0][1] };
}

export function payloadFor(tag, commitSha, apk, facts, expectedCertificate) {
  const version = releaseVersion(tag);
  if (!/^[0-9a-f]{64}$/.test(expectedCertificate || '') || facts.certificateSha256 !== expectedCertificate)
    throw new Error('Release certificate does not match the explicitly configured signer fingerprint.');
  if (facts.packageName !== 'dev.r1ptt' || facts.versionCode !== version.versionCode || facts.versionName !== version.versionName || facts.minSdk !== 33)
    throw new Error('APK package/version/minSdk does not match the release.');
  if (!/^[0-9a-f]{40}$/.test(commitSha) || apk.length < 1 || apk.length > 64 * 1024 * 1024) throw new Error('Invalid commit or APK size.');
  return { schema: 1, repository: 'windoze95/robotOS', packageName: 'dev.r1ptt',
    versionCode: version.versionCode, versionName: version.versionName, tag,
    apkName: 'robotOS.apk', apkSha256: hash(apk), apkSize: apk.length,
    certificateSha256: facts.certificateSha256, minSdk: facts.minSdk, commitSha };
}

export function verifyBundle(directory, expectedCertificate) {
  const certificate = new X509Certificate(fs.readFileSync(path.join(directory, 'signer.cer')));
  if (!/^[0-9a-f]{64}$/.test(expectedCertificate || '') || hash(certificate.raw) !== expectedCertificate)
    throw new Error('Public certificate does not match the trusted fingerprint.');
  const bytes = fs.readFileSync(path.join(directory, 'update.json'));
  if (bytes.length === 0 || bytes.length > 32 * 1024) throw new Error('Metadata size is invalid.');
  const envelope = JSON.parse(bytes.toString('utf8'));
  if (Object.keys(envelope).sort().join(',') !== 'payload,signature' ||
      typeof envelope.payload !== 'string' || typeof envelope.signature !== 'string') throw new Error('Invalid metadata envelope.');
  const payload = Buffer.from(envelope.payload, 'base64');
  if (payload.length === 0 || payload.length > 8192 || Buffer.from(envelope.signature, 'base64').length > 1024)
    throw new Error('Invalid signed payload size.');
  if (!verify('sha256', payload, certificate.publicKey, Buffer.from(envelope.signature, 'base64')))
    throw new Error('Invalid metadata signature.');
  const manifest = JSON.parse(payload);
  const apk = fs.readFileSync(path.join(directory, 'robotOS.apk'));
  const expected = payloadFor(manifest.tag, manifest.commitSha, apk, manifest, expectedCertificate);
  if (Object.keys(manifest).sort().join(',') !== Object.keys(expected).sort().join(',') ||
      Object.entries(expected).some(([name, value]) => manifest[name] !== value))
    throw new Error('Release contents or identity do not match signed metadata.');
  return manifest;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const [mode, directory, tag, commit] = process.argv.slice(2);
    const fingerprint = process.env.ROBOTOS_SIGNER_SHA256;
    if (mode === 'create') {
      const apkPath = path.join(directory, 'robotOS.apk');
      const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
      if (!sdk) throw new Error('ANDROID_HOME is required.');
      const buildTools = path.join(sdk, 'build-tools/35.0.0');
      const badging = execFileSync(path.join(buildTools, 'aapt2'), ['dump', 'badging', apkPath], { encoding: 'utf8' });
      const signing = execFileSync(path.join(buildTools, 'apksigner'), ['verify', '--verbose', '--print-certs', apkPath], { encoding: 'utf8' });
      const payload = payloadFor(tag, commit, fs.readFileSync(apkPath), apkFacts(badging, signing), fingerprint);
      fs.writeFileSync(path.join(directory, 'payload.json'), JSON.stringify(payload) + '\n');
    } else if (mode === 'verify') {
      const manifest = verifyBundle(directory, fingerprint);
      console.log(`Verified signed metadata and APK checksum for ${manifest.tag}.`);
    } else throw new Error('Usage: manifest.mjs create|verify DIRECTORY [TAG COMMIT]');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
