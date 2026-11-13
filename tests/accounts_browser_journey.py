"""Account onboarding against a disposable evaluation instance."""
import os
from pathlib import Path
from uuid import uuid4
from playwright.sync_api import sync_playwright

base = os.environ['OPEN_SERVICE_EVAL_URL'].rstrip('/')
password = os.environ['OPEN_SERVICE_EVAL_PASSWORD']
root = Path(__file__).resolve().parents[1]
artifacts = root / 'artifacts'
artifacts.mkdir(exist_ok=True)
suffix = uuid4().hex[:8]
names = {'agent': 'EvalAgent' + suffix, 'reader': 'EvalReader' + suffix}

with sync_playwright() as playwright:
    browser = playwright.chromium.launch()
    admin = browser.new_page(viewport={'width': 1512, 'height': 1100})
    errors = []
    admin.on('pageerror', lambda error: errors.append(str(error)))
    admin.goto(base)
    admin.get_by_label('Username', exact=True).fill('admin')
    admin.get_by_label('Password', exact=True).fill(password)
    admin.get_by_role('button', name='Enter your workspace').click()
    admin.get_by_role('link', name='Accounts', exact=False).click()
    assert admin.get_by_label('Role', exact=True).input_value() == 'reader'
    for role, name in names.items():
        admin.get_by_label('Username', exact=True).fill(name)
        admin.get_by_label('Password', exact=True).fill(password)
        admin.get_by_label('Role', exact=True).select_option(role)
        admin.get_by_role('button', name='Create account').click()
        admin.get_by_role('cell', name=name, exact=True).wait_for()
        assert admin.get_by_label('Password', exact=True).input_value() == ''
    admin.get_by_label('Username', exact=True).fill(names['agent'])
    admin.get_by_label('Password', exact=True).fill(password)
    admin.get_by_role('button', name='Create account').click()
    admin.get_by_role('alert').filter(has_text='User already exists').wait_for()
    assert admin.get_by_label('Password', exact=True).input_value() == ''
    assert password not in admin.content()
    admin.goto(base + '/admin/accounts')
    admin.screenshot(path=str(artifacts / 'accounts-desktop.png'), full_page=True)
    admin.set_viewport_size({'width': 390, 'height': 844})
    assert admin.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Mobile page overflow'
    admin.screenshot(path=str(artifacts / 'accounts-mobile.png'), full_page=True)

    for role, name in names.items():
        context = browser.new_context(viewport={'width': 1280, 'height': 900})
        page = context.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base + '/login')
        page.get_by_label('Username', exact=True).fill(name)
        page.get_by_label('Password', exact=True).fill(password)
        page.get_by_role('button', name='Enter your workspace').click()
        page.get_by_role('heading', name='Make room for better work.').wait_for()
        assert page.locator('a[href="/admin/accounts"]').count() == 0
        response = page.goto(base + '/admin/accounts')
        assert response.status == 403
        page.goto(base + '/workspace/incident')
        if role == 'agent':
            page.get_by_role('link', name='New record').click()
            page.get_by_label('Summary', exact=True).fill('Synthetic account evaluation ' + suffix)
            page.get_by_role('button', name='Create record').click()
            page.locator('select[name=state]').select_option('2')
            page.get_by_role('button', name='Save changes').click()
            page.reload()
            assert page.locator('select[name=state]').input_value() == '2'
            record_url = page.url
        else:
            assert page.get_by_role('link', name='New record').count() == 0
            page.goto(record_url)
            assert page.get_by_label('Summary', exact=True).input_value() == 'Synthetic account evaluation ' + suffix
            assert page.get_by_label('Summary', exact=True).get_attribute('readonly') is not None
            assert page.locator('select[name=state]').is_disabled()
            assert page.get_by_role('button', name='Save changes').count() == 0
        context.close()
    assert not errors, errors
    browser.close()
print('Account browser journey passed: onboarding, sign-in, roles, duplicate handling, password secrecy and responsive layout.')
