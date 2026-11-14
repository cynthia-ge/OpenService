(() => {
  'use strict';
  const root = document.getElementById('migration-workspace');
  const el = id => document.getElementById('migration-' + id);
  const api = '/api/openservice/migrations';
  let running = false, busy = false;
  async function request(path, body, token) {
    const headers = {'Content-Type': 'application/json', 'X-CSRF-Token': root.dataset.csrf};
    if (token) headers['X-Source-Token'] = token;
    const response = await fetch(api + path, {method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body)});
    const value = await response.json();
    if (!response.ok) throw new Error(value.error?.detail || 'Migration request failed');
    return value.result;
  }
  async function refresh(selected = el('jobs').value) {
    const jobs = await request('');
    el('jobs').replaceChildren(...jobs.map(job => {
      const option = document.createElement('option');
      option.value = job.job_id;
      option.textContent = `${job.source_url} · ${job.detail.status} · ${job.job_id.slice(0,8)}`;
      return option;
    }));
    if (jobs.some(job => job.job_id === selected)) el('jobs').value = selected;
    if (el('jobs').value) await show();
  }
  async function show() {
    const job = await request('/' + el('jobs').value);
    el('report').textContent = JSON.stringify(job, null, 2);
    el('archives').replaceChildren(...job.phases.map(table => {
      const link = document.createElement('a');
      link.href = `${api}/${job.job_id}/archive?table=${encodeURIComponent(table)}`;
      link.textContent = `Review ${table} (first 100 rows) `;
      return link;
    }));
    return job;
  }
  async function guarded(work) {
    if (busy) return;
    busy = true;
    el('message').textContent = 'Working…';
    try { await work(); el('message').textContent = 'Saved. Review the migration report below.'; }
    catch (error) { el('message').textContent = error.message; }
    finally { busy = false; running = false; }
  }
  el('create').addEventListener('submit', event => {
    event.preventDefault();
    guarded(async () => { const job = await request('', JSON.parse(el('plan').value)); await refresh(job.job_id); });
  });
  el('refresh').onclick = () => guarded(() => refresh());
  el('jobs').onchange = () => guarded(show);
  el('stop').onclick = () => { running = false; };
  el('fetch').onclick = () => guarded(async () => {
    running = true;
    const selected = el('jobs').value, token = el('token').value;
    let job = await request('/' + selected);
    while (running && job.detail.status === 'fetching') {
      job = await request('/' + selected + '/step', {}, token);
      el('report').textContent = JSON.stringify(job, null, 2);
    }
    el('token').value = '';
    await refresh(selected);
  });
  for (const action of ['preview', 'apply']) el(action).onclick = () => guarded(async () => {
    const report = await request('/' + el('jobs').value + '/' + action, {});
    await refresh();
    el('report').textContent = JSON.stringify(report, null, 2);
  });
  el('sync').onclick = () => guarded(async () => { const job = await request('/' + el('jobs').value + '/sync', {}); await refresh(job.job_id); });
  el('cutover').onclick = () => guarded(async () => {
    if (!el('confirm').checked) throw new Error('Review and confirm all cutover conditions first.');
    await request('/' + el('jobs').value + '/cutover', {source_frozen: true, completeness_reviewed: true, permissions_reviewed: true, automation_reviewed: true});
    await refresh();
  });
  guarded(() => refresh());
})();
