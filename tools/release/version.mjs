import { pathToFileURL } from 'node:url';

export function releaseVersion(tag) {
  const match = /^v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$/.exec(tag);
  if (!match) throw new Error('Expected a stable tag vMAJOR.MINOR.PATCH (no leading zeros or suffixes).');
  const [major, minor, patch] = match.slice(1).map(Number);
  const versionCode = major * 1_000_000 + minor * 1_000 + patch;
  if (major > 2099 || versionCode < 2 || versionCode > 2_099_999_999) throw new Error('Version code is out of range.');
  return { tag, versionName: tag.slice(1), versionCode };
}

export function requireNewer(tag, pages) {
  const next = releaseVersion(tag);
  if (!Array.isArray(pages)) throw new Error('Invalid release inventory.');
  for (const release of pages.flat()) {
    if (!release || typeof release.draft !== 'boolean' || typeof release.tag_name !== 'string')
      throw new Error('Invalid release inventory.');
    if (release.tag_name === tag) throw new Error('A release with this tag already exists; inspect it manually.');
    if (!release.draft && releaseVersion(release.tag_name).versionCode >= next.versionCode)
      throw new Error('A published release already has this or a higher version code.');
  }
  return next;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try { console.log(JSON.stringify(releaseVersion(process.argv[2]))); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
