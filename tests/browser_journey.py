"""Run against the local Docker demo: python tests/browser_journey.py."""
import json
from pathlib import Path
import urllib.request
import base64
from playwright.sync_api import sync_playwright

root=Path(__file__).resolve().parents[1]
config=dict(line.split('=',1) for line in (root/'.env').read_text().splitlines() if '=' in line)
base='http://127.0.0.1:8090'
auth='Basic '+base64.b64encode(('admin:'+config['ADMIN_PASSWORD']).encode()).decode()

def api(path, payload, method="POST"):
    req=urllib.request.Request(base+path,data=json.dumps(payload).encode() if payload is not None else None,method=method,headers={'Authorization':auth,'Content-Type':'application/json'})
    with urllib.request.urlopen(req) as response:
        return json.load(response) if response.status != 204 else None

# Deterministic synthetic records demonstrate the workspace without touching source systems.
examples=[
 ('incident','a1000000000000000000000000000001','INC0001001','Email delivery delayed for the finance team','2','2','Workplace technology'),
 ('incident','a1000000000000000000000000000002','INC0001002','Restore VPN access for the London office','1','1','Infrastructure'),
 ('incident','a1000000000000000000000000000003','INC0001003','Meeting room display needs a firmware update','3','4','Workplace technology'),
 ('incident','a1000000000000000000000000000004','INC0001004','Customer portal sign-in interruption','6','2','Application support'),
 ('incident','a1000000000000000000000000000005','INC0001005','New starter cannot access shared folders','1','3','Service desk'),
 ('incident','a1000000000000000000000000000006','INC0001006','Print service unavailable on the second floor','2','3','Service desk'),
 ('change_request','b1000000000000000000000000000001','CHG0001001','Upgrade the production database cluster','review','2','Infrastructure'),
 ('change_request','b1000000000000000000000000000002','CHG0001002','Roll out the new employee access policy','draft','3','Identity'),
 ('sc_request','c1000000000000000000000000000001','REQ0001001','Provision a developer workspace','open','3','Workplace technology'),
 ('cmdb_ci','d1000000000000000000000000000001','CI0001001','Production PostgreSQL cluster','','','Infrastructure'),
 ('cmdb_ci','d1000000000000000000000000000002','CI0001002','London core network switch','','','Infrastructure'),
 ('kb_knowledge','e1000000000000000000000000000001','KB0001001','Troubleshooting remote access','','','Service desk'),
]
for table,identifier,number,summary,state,priority,group in examples:
    api('/api/openservice/import/'+table+'?dry_run=false',[{'sys_id':identifier,'number':number,'short_description':summary,'state':state,'priority':priority,'assignment_group':group,'description':'Synthetic demo record. No customer data.','sys_created_by':'demo','sys_updated_by':'demo'}])
(root/'artifacts').mkdir(exist_ok=True)
with sync_playwright() as p:
    browser=p.chromium.launch()
    page=browser.new_page(viewport={'width':1512,'height':1100},device_scale_factor=1)
    errors=[]
    page.on('pageerror',lambda error:errors.append(str(error)))
    page.goto(base)
    page.get_by_label('Username').fill('admin')
    page.get_by_label('Password').fill(config['ADMIN_PASSWORD'])
    page.get_by_role('button',name='Enter your workspace').click()
    page.get_by_role('heading',name='Make room for better work.').wait_for()
    page.screenshot(path=str(root/'artifacts/workspace.png'),full_page=True)
    page.get_by_role('link',name='New incident').click()
    page.get_by_label('Summary',exact=True).fill('Browser journey — keyboard and form validation')
    page.get_by_label('Description',exact=True).fill('Created through the real UI during browser verification.')
    page.get_by_role('button',name='Create record').click()
    page.locator('select[name=state]').select_option('2')
    page.get_by_role('button',name='Save changes').click()
    record_url=page.url
    page.get_by_label('Add an internal note').fill('Verified the incident transition and activity history.')
    page.get_by_role('button',name='Add note',exact=True).click()
    page.get_by_text('Verified the incident transition and activity history.',exact=True).wait_for()
    page.screenshot(path=str(root/'artifacts/record.png'),full_page=True)
    api('/api/now/table/incident/'+record_url.rsplit('/',1)[1],None,'DELETE')
    page.goto(base+'/migration')
    page.locator('input[type=file]').set_input_files({'name':'preview.json','mimeType':'application/json','buffer':json.dumps({'result':[{'sys_id':'f'*32,'short_description':'Browser migration preview'}]}).encode()})
    page.get_by_role('button',name='Review and run').click()
    page.get_by_role('heading',name='Dry-run report').wait_for()
    page.screenshot(path=str(root/'artifacts/migration.png'),full_page=True)
    page.set_viewport_size({'width':390,'height':844})
    page.goto(base)
    assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Mobile viewport overflow'
    page.screenshot(path=str(root/'artifacts/mobile.png'),full_page=True)
    assert not errors,errors
    browser.close()
print('Browser journey passed: login, creation, transition, note, migration preview, responsive viewport.')
