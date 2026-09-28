param([Parameter(Mandatory = $true)][string]$EnvironmentKey)

$ErrorActionPreference = 'Stop'
if ($EnvironmentKey -ne 'testforge-selftest') { throw "Unsupported TestForge self-test environment: $EnvironmentKey" }
$adminPassword = $env:TESTFORGE_MYSQL_ADMIN_PASSWORD
if (-not $adminPassword) { throw 'TESTFORGE_MYSQL_ADMIN_PASSWORD is required for blank-environment initialization' }
$adminUser = if ($env:TESTFORGE_MYSQL_ADMIN_USER) { $env:TESTFORGE_MYSQL_ADMIN_USER } else { 'root' }
$appUser = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'testforge' }
$appPassword = $env:MYSQL_PASSWORD
if (-not $appPassword) { throw 'MYSQL_PASSWORD is required for blank-environment initialization' }
$mysqlHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' }
$mysql = Get-Command mysql.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
if (-not $mysql) {
    $defaultMysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
    if (Test-Path -LiteralPath $defaultMysql) { $mysql = $defaultMysql }
}
if (-not $mysql) { throw 'mysql.exe is required for TestForge self-test environment initialization' }

function Escape-SqlLiteral([string]$value) { return $value.Replace("'", "''") }

$escapedUser = Escape-SqlLiteral $appUser
$escapedPassword = Escape-SqlLiteral $appPassword
$sql = @"
CREATE DATABASE IF NOT EXISTS testforge_selftest CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$escapedUser'@'127.0.0.1' IDENTIFIED BY '$escapedPassword';
ALTER USER '$escapedUser'@'127.0.0.1' IDENTIFIED BY '$escapedPassword';
GRANT ALL PRIVILEGES ON testforge_selftest.* TO '$escapedUser'@'127.0.0.1';
FLUSH PRIVILEGES;
"@
$savedMysqlPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $adminPassword
    $sql | & $mysql --protocol=tcp --host=$mysqlHost --user=$adminUser --batch
    if ($LASTEXITCODE -ne 0) { throw "MySQL initialization failed with exit code $LASTEXITCODE" }
} finally {
    $env:MYSQL_PWD = $savedMysqlPassword
}

$target = if ($env:TESTFORGE_SELFTEST_ROOT) { $env:TESTFORGE_SELFTEST_ROOT } else { Join-Path $env:ProgramData 'TestForge/platform-selftest' }
New-Item -ItemType Directory -Force -Path $target | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $target 'logs') | Out-Null
Write-Host "Initialized TestForge self-test environment prerequisites at $target"
