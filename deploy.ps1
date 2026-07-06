# WebAgent 云服务器部署脚本。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\deploy.ps1
#
# 常用参数：
#   -SkipBuild          跳过本地 Maven 打包，直接上传现有 jar
#   -NoRestart          只上传并替换 jar，不重启服务
#   -HealthUrl <url>    覆盖公网健康检查地址

[CmdletBinding()]
param(
    [string]$HostName = "104.208.99.11",
    [string]$UserName = "azureuser",
    [string]$KeyPath = "$env:USERPROFILE\.ssh\webagent-vm_key.pem",
    [string]$JarPath = "target\sandbox-1.0-SNAPSHOT.jar",
    [string]$RemoteTmpJar = "/tmp/webagent-app.jar",
    [string]$RemoteAppJar = "/opt/webagent/app.jar",
    [string]$ServiceName = "webagent",
    [string]$HealthUrl = "http://104.208.99.11/",
    [switch]$SkipBuild,
    [switch]$NoRestart
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "未找到命令：$Name。请先安装或把它加入 PATH。"
    }
}

function Invoke-Remote {
    param([string]$Command)
    $remote = "{0}@{1}" -f $UserName, $HostName
    & ssh -i $KeyPath -o BatchMode=yes $remote $Command
    if ($LASTEXITCODE -ne 0) {
        throw "远程命令执行失败，退出码：$LASTEXITCODE"
    }
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

Write-Step "检查本地依赖"
Assert-Command "ssh"
Assert-Command "scp"
if (-not $SkipBuild) {
    Assert-Command "mvn"
}
if (-not (Test-Path -LiteralPath $KeyPath)) {
    throw "私钥文件不存在：$KeyPath"
}

if (-not $SkipBuild) {
    Write-Step "本地打包"
    & mvn -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 打包失败，退出码：$LASTEXITCODE"
    }
}

$fullJarPath = Join-Path $repoRoot $JarPath
if (-not (Test-Path -LiteralPath $fullJarPath)) {
    throw "找不到待部署 jar：$fullJarPath"
}

$jarInfo = Get-Item -LiteralPath $fullJarPath
Write-Step "准备上传 jar"
Write-Host ("文件：{0}" -f $jarInfo.FullName)
Write-Host ("大小：{0:N2} MB" -f ($jarInfo.Length / 1MB))

Write-Step "上传到服务器"
$remoteTarget = "{0}@{1}:{2}" -f $UserName, $HostName, $RemoteTmpJar
& scp -i $KeyPath $fullJarPath $remoteTarget
if ($LASTEXITCODE -ne 0) {
    throw "上传 jar 失败，退出码：$LASTEXITCODE"
}

$timestamp = Get-Date -Format "yyyyMMddHHmmss"
$remoteScript = @"
set -e
if [ ! -f "$RemoteTmpJar" ]; then
  echo "临时 jar 不存在：$RemoteTmpJar" >&2
  exit 1
fi
sudo mkdir -p /opt/webagent/backups
if [ -f "$RemoteAppJar" ]; then
  sudo cp "$RemoteAppJar" "/opt/webagent/backups/app-$timestamp.jar"
fi
sudo mv "$RemoteTmpJar" "$RemoteAppJar"
sudo chown webagent:webagent "$RemoteAppJar"
sudo chmod 644 "$RemoteAppJar"
"@

if (-not $NoRestart) {
    $remoteScript += @"

sudo systemctl restart "$ServiceName"
for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if systemctl is-active --quiet "$ServiceName"; then
    code=`$(curl -s -o /tmp/webagent-deploy-health.html -w '%{http_code}' http://127.0.0.1:8081/ || true)
    echo "本机健康检查 HTTP: `$code"
    if [ "`$code" = "200" ]; then
      exit 0
    fi
  fi
  sleep 5
done
sudo systemctl --no-pager --full status "$ServiceName" || true
tail -n 120 /opt/webagent/logs/app.log || true
exit 1
"@
}

Write-Step "替换远程 jar"
Invoke-Remote $remoteScript

if (-not $NoRestart) {
    Write-Step "公网健康检查"
    try {
        $response = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 20
        Write-Host ("公网健康检查 HTTP: {0}" -f [int]$response.StatusCode)
        if ([int]$response.StatusCode -ne 200) {
            throw "公网健康检查未返回 200"
        }
    } catch {
        Write-Host "公网健康检查失败，可稍后手动查看：$HealthUrl" -ForegroundColor Yellow
        throw
    }
}

Write-Step "部署完成"
Write-Host "访问地址：$HealthUrl"
Write-Host "查看日志：ssh -i `"$KeyPath`" $UserName@$HostName `"tail -f /opt/webagent/logs/app.log`""
