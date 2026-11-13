"""Create local-only credentials without replacing existing configuration."""
import argparse
from pathlib import Path
import secrets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path(__file__).resolve().parents[1])
    path = parser.parse_args().directory / '.env'
    try:
        with path.open('x') as stream:
            path.chmod(0o600)
            stream.write('\n'.join(f'{name}={secrets.token_hex(24)}' for name in ['DB_PASSWORD', 'SECRET_KEY', 'ADMIN_PASSWORD']) + '\n')
        print('Created .env with private local credentials. Username: admin. Read ADMIN_PASSWORD in .env.')
    except FileExistsError:
        print('.env already exists; preserved it.')


if __name__ == '__main__':
    main()
