# 阶段二环境准备

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

仍保留可运行的阶段一代码和默认配置。阶段二设计在 stage2-plan.md；阶段一归档在 experiments.md。尚未运行阶段二集成测试或压测，不能宣称阶段二完成。

## 官方依据

- [Microsoft：安装 WSL](https://learn.microsoft.com/en-us/windows/wsl/install)
- [Microsoft：在 WSL 中使用 Redis](https://learn.microsoft.com/en-us/windows/wsl/tutorials/wsl-database)
- [Redis：Windows 11 与 WSL](https://redis.io/blog/install-redis-windows-11/)
