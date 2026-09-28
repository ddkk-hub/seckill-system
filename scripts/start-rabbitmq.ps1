$ErrorActionPreference = 'Stop'
& wsl.exe -d Ubuntu -u root --exec /usr/sbin/service rabbitmq-server start
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ service failed to start' }
& wsl.exe -d Ubuntu -u root --exec /usr/sbin/rabbitmq-diagnostics -q ping
if ($LASTEXITCODE -ne 0) { throw 'RabbitMQ health check failed' }
