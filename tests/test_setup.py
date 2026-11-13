from pathlib import Path
import runpy
import stat


def test_private_credentials_and_non_destructive_rerun(tmp_path,monkeypatch,capsys):
    script=Path(__file__).resolve().parents[1]/'scripts/setup-local.py'
    monkeypatch.setattr('sys.argv',[str(script),'--directory',str(tmp_path)])
    runpy.run_path(str(script),run_name='__main__')
    data=(tmp_path/'.env').read_text()
    values=dict(line.split('=',1) for line in data.splitlines())
    assert set(values)=={'DB_PASSWORD','SECRET_KEY','ADMIN_PASSWORD'}
    assert len(set(values.values()))==3
    assert all(len(value)==48 for value in values.values())
    assert stat.S_IMODE((tmp_path/'.env').stat().st_mode)==0o600
    runpy.run_path(str(script),run_name='__main__')
    assert (tmp_path/'.env').read_text()==data
    output=capsys.readouterr().out
    assert 'preserved' in output
    assert all(value not in output for value in values.values())
    runpy.run_path(str(script))
