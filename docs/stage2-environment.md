# 阶段二环境准备

最新状态：用户已重启；Ubuntu 与 Redis 8.0.5 安装完成，Windows 127.0.0.1:6379 PING 返回 PONG。Redis 已配置 AOF everysec、noeviction、256mb。当前通过 scripts/start-redis.ps1 中的方式以 redis 用户驻留运行。下文保留最初安装流程供复现；无需再次安装或重启。

2026-09-27 实际检查：WSL 2.7.14.0 已安装；内核 6.18.33.2-2；尚无 Linux 分发；Redis 尚未安装。Windows DISM 日志明确标记 Reboot required=yes，安装使用 NoRestart，没有自动重启。

## 下一步

1. 保存项目和其他工作，手动重启 Windows。
2. 在管理员 PowerShell 中安装 Ubuntu：

```powershell
wsl --install -d Ubuntu
```

3. 按 Ubuntu 首次启动提示创建 Linux 用户名和密码。它们与 MySQL 账号无关；输入 Linux 密码时终端不显示字符，这是正常现象。
4. 检查分发并进入 Ubuntu：

```powershell
wsl --list --verbose
wsl -d Ubuntu
```

5. 在 Ubuntu 终端安装并启动 Redis：

```bash
sudo apt update
sudo apt install -y redis-server
sudo service redis-server start
redis-cli ping
redis-server --version
```

预期 ping 输出 PONG；记录实际安装的 Redis 版本。不要关闭 protected-mode 或把监听地址改成 0.0.0.0。此处只验证本地环境，业务库存持久化和 maxmemory-policy 配置在接入代码前继续完成。

6. Windows PowerShell 验证：

```powershell
wsl -d Ubuntu -- redis-cli ping
Test-NetConnection localhost -Port 6379
```

预期 PONG 且 TcpTestSucceeded 为 True。如果只有 WSL 内 PONG、Windows 端不通，需要排查 WSL localhost 转发，再接入 Java，不能通过放开 Redis 公网监听解决。

## 项目状态

默认配置仍运行阶段一；使用 stage2 profile 运行阶段二。20 项测试已通过，已完成的压测结果见 stage2-report.md；完整启动步骤见 stage2.md。

## 官方依据

- [Microsoft：安装 WSL](https://learn.microsoft.com/en-us/windows/wsl/install)
- [Microsoft：在 WSL 中使用 Redis](https://learn.microsoft.com/en-us/windows/wsl/tutorials/wsl-database)
- [Redis：Windows 11 与 WSL](https://redis.io/blog/install-redis-windows-11/)

实际安装的分发为 Ubuntu 26.04.1 LTS。自动安装命令使用显式 root 管理身份，Redis 驻留进程以 redis 用户运行；本次未创建额外个人 Linux 账号或密码。

2026-09-28：启动脚本遇到 PowerShell 禁止脚本策略，使用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-redis.ps1` 可运行；作用范围仅为本次子进程。Redis 恢复后商品 1 为 98，未决预扣为 0。
