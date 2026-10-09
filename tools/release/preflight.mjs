import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { requireNewer } from './version.mjs';

try {
  const { GITHUB_REPOSITORY: repo, GITHUB_REF_NAME: tag, GITHUB_SHA: sha } = process.env;
  if (repo !== 'windoze95/robotOS' || !/^[0-9a-f]{40}$/.test(sha || '')) throw new Error('Unexpected release repository or commit.');
  const pages = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const version = requireNewer(tag, pages);
  const target = execFileSync('git', ['rev-parse', `refs/tags/${tag}^{commit}`], { encoding: 'utf8' }).trim();
  if (target !== sha) throw new Error('Release tag does not match the triggering commit.');
  execFileSync('git', ['merge-base', '--is-ancestor', sha, 'origin/main']);
  console.log(`Verified ${tag}: versionCode ${version.versionCode}; commit belongs to main.`);
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
