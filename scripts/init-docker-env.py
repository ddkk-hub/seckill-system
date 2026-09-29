"""Create local Docker credentials once; never overwrite an existing environment."""
from pathlib import Path
import secrets
path=Path('.env')
if path.exists():
    print('.env already exists; left unchanged.')
else:
    with path.open('x',encoding='utf-8',newline='\n') as file:
        file.write(''.join(key+'='+secrets.token_hex(24)+'\n' for key in ['MYSQL_ROOT_PASSWORD','APP_DB_PASSWORD','MQ_PASSWORD']))
    print('Created .env with random credentials. Values are not printed; file is Git-ignored.')
