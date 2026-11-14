const fs = require('node:fs');
const path = require('node:path');
const convert = require('v8-to-istanbul');
(async () => {
  const filename = path.resolve('src/main/resources/static/migrations.js');
  const raw = JSON.parse(fs.readFileSync('target/migration-browser-v8.json', 'utf8'));
  if (raw.source !== fs.readFileSync(filename, 'utf8')) throw Error('Browser coverage is stale');
  const coverage = convert(filename, 0, {source: raw.source});
  await coverage.load();
  coverage.applyCoverage(raw.functions);
  const report = coverage.toIstanbul();
  fs.writeFileSync('target/browser-coverage.json', JSON.stringify(report));
  const result = report[filename];
  for (const [name, counts] of Object.entries({lines: Object.values(result.s), branches: Object.values(result.b).flat(), functions: Object.values(result.f)})) {
    if (counts.length === 0) throw Error('No browser ' + name + ' measured');
    const covered = counts.filter(count => count > 0).length;
    console.log(`Browser ${name}: ${covered}/${counts.length}`);
    if (covered !== counts.length) throw Error('Incomplete browser ' + name + '; inspect target/browser-coverage.json');
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
