$ErrorActionPreference = 'Stop'
# For the local Ubuntu instance configured during stage2 setup.
$redisClient = [System.Net.Sockets.TcpClient]::new()
try {
    $redisClient.Connect('127.0.0.1', 6379)
    $redisStream = $redisClient.GetStream()
    $redisStream.ReadTimeout = 1500
    $pingBytes = [System.Text.Encoding]::ASCII.GetBytes("PING`r`n")
    $redisStream.Write($pingBytes, 0, $pingBytes.Length)
    $replyBytes = New-Object byte[] 128
    $readCount = $redisStream.Read($replyBytes, 0, $replyBytes.Length)
    if ([System.Text.Encoding]::ASCII.GetString($replyBytes, 0, $readCount).StartsWith('+PONG')) {
        Write-Host 'Redis is already responding on 127.0.0.1:6379.'
        return
    }
} catch {
    # Redis is not reachable; continue with the configured local startup.
} finally {
    $redisClient.Dispose()
}
wsl -d Ubuntu -u root -- service redis-server stop
if ($LASTEXITCODE -ne 0) { throw 'Could not stop the background Redis service.' }
Start-Process -FilePath 'wsl.exe' -ArgumentList '-d','Ubuntu','-u','redis','--exec','/usr/bin/redis-server','/etc/redis/redis.conf','--daemonize','no','--supervised','no' -WindowStyle Hidden
Write-Host 'Redis process started. Verify with: wsl -d Ubuntu -u root -- redis-cli ping'
