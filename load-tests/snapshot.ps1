<#
.SYNOPSIS
  부하 실험 전·후·도중의 앱 커넥션 풀 지표와 DB 행 잠금 통계를 찍는다.

.EXAMPLE
  # 실험 전 기준값
  ./load-tests/snapshot.ps1 -Label before

  # 실험 도중 1초 간격으로 30초 관찰 (별도 터미널에서 k6 와 동시에)
  ./load-tests/snapshot.ps1 -Label during -WatchSeconds 30

  # 실험 후, 쿠폰 사후 검증까지
  ./load-tests/snapshot.ps1 -Label after -CouponId 40

.NOTES
  DB 접속은 mariadb.exe 를 쓴다. 비밀번호는 환경변수 DB_PASSWORD 가 있으면 그것을, 없으면 프롬프트로 받는다.
  Actuator metrics 는 application.properties 의 management.endpoints.web.exposure.include 에 포함돼 있어야 한다.
#>
param(
    [string]$Label = "snapshot",
    [int]$WatchSeconds = 0,
    [long]$CouponId = 0,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$MariadbExe = "C:\Program Files\MariaDB 12.3\bin\mariadb.exe",
    [string]$DbHost = "127.0.0.1",
    [int]$DbPort = 3307,
    [string]$DbUser = "root",
    [string]$Database = "coupon_service"
)

function Get-Metric([string]$name) {
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/$name" -TimeoutSec 2
        return [double]$r.measurements[0].value
    } catch {
        return $null
    }
}

function Get-HikariLine {
    $active = Get-Metric "hikaricp.connections.active"
    $pending = Get-Metric "hikaricp.connections.pending"
    $timeout = Get-Metric "hikaricp.connections.timeout"
    $max = Get-Metric "hikaricp.connections.max"
    if ($null -eq $active) { return "hikari: (actuator 응답 없음: 앱이 꺼져 있거나 metrics 미노출)" }
    return "hikari: active=$active pending=$pending timeout=$timeout max=$max"
}

function Invoke-Sql([string]$sql) {
    if (-not (Test-Path $MariadbExe)) { return "(mariadb.exe 없음: $MariadbExe)" }
    if ($env:DB_PASSWORD) { $env:MYSQL_PWD = $env:DB_PASSWORD }
    $cliArgs = @("-h", $DbHost, "-P", $DbPort, "-u", $DbUser, "--batch", "--skip-column-names", $Database, "-e", $sql)
    if (-not $env:MYSQL_PWD) { $cliArgs = @("-p") + $cliArgs }
    return (& $MariadbExe @cliArgs) -join "`n"
}

function Get-RowLockLine {
    $out = Invoke-Sql "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';"
    $pairs = @()
    foreach ($line in ($out -split "`n")) {
        $parts = $line -split "`t"
        if ($parts.Count -eq 2) { $pairs += ($parts[0] -replace "Innodb_row_lock_", "") + "=" + $parts[1] }
    }
    if ($pairs.Count -eq 0) { return "row_lock: (조회 실패) $out" }
    return "row_lock: " + ($pairs -join " ")
}

function Get-CouponLine([long]$id) {
    $sql = "SELECT c.id, c.total_quantity, c.remaining_quantity, COUNT(ci.id), c.total_quantity - c.remaining_quantity - COUNT(ci.id) FROM coupon c LEFT JOIN coupon_issue ci ON ci.coupon_id = c.id WHERE c.id = $id GROUP BY c.id, c.total_quantity, c.remaining_quantity;"
    $out = Invoke-Sql $sql
    $p = ($out -split "`t")
    if ($p.Count -ne 5) { return "coupon: (조회 실패) $out" }
    return "coupon: id=$($p[0]) total=$($p[1]) remaining=$($p[2]) issue_count=$($p[3]) consistency_gap=$($p[4])"
}

$stamp = Get-Date -Format "HH:mm:ss"

if ($WatchSeconds -gt 0) {
    Write-Output "[$Label] $stamp 관찰 시작, ${WatchSeconds}초 동안 1초 간격 (hikari 만)"
    $maxPending = 0; $maxActive = 0; $samples = 0
    for ($i = 0; $i -lt $WatchSeconds; $i++) {
        $active = Get-Metric "hikaricp.connections.active"
        $pending = Get-Metric "hikaricp.connections.pending"
        if ($null -ne $active) {
            $samples++
            if ($pending -gt $maxPending) { $maxPending = $pending }
            if ($active -gt $maxActive) { $maxActive = $active }
            Write-Output ("  {0} active={1} pending={2}" -f (Get-Date -Format "HH:mm:ss"), $active, $pending)
        }
        Start-Sleep -Seconds 1
    }
    Write-Output "[$Label] 관찰 종료: samples=$samples max_active=$maxActive max_pending=$maxPending"
    exit 0
}

Write-Output "[$Label] $stamp"
Write-Output ("  " + (Get-HikariLine))
Write-Output ("  " + (Get-RowLockLine))
if ($CouponId -gt 0) { Write-Output ("  " + (Get-CouponLine $CouponId)) }
