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
  V4 부터 실시간 재고의 정본은 Redis 다. 쿠폰 사후 검증은 Redis 재고 키가 있으면 그 값을, 없으면 DB 잔여 수량을 쓴다.
  Redis 접근은 PATH 의 redis-cli 를 먼저 찾고, 없으면 docker 컨테이너 안의 redis-cli 를 쓴다.
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
    [string]$Database = "coupon_service",
    [string]$RedisContainer = "coupon-redis"
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

function Invoke-Redis([string[]]$redisArgs) {
    $cli = Get-Command redis-cli -ErrorAction SilentlyContinue
    if ($cli) { return (& $cli.Source @redisArgs) -join "`n" }

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { return $null }

    $out = & $docker.Source exec $RedisContainer redis-cli @redisArgs
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($out -join "`n")
}

# V4 재고 키를 읽는다. 키가 없거나 Redis 에 닿지 못하면 $null 이다.
function Get-RedisStock([long]$id) {
    return (Get-RedisLong @("GET", "coupon:$id`:stock"))
}

# V4 발급 사용자 집합의 크기. 키가 없으면 SCARD 는 0 을 돌려준다.
function Get-RedisIssuedCount([long]$id) {
    return (Get-RedisLong @("SCARD", "coupon:$id`:issued"))
}

function Get-RedisLong([string[]]$redisArgs) {
    $out = Invoke-Redis $redisArgs
    if ($null -eq $out) { return $null }

    $value = [long]0
    if (-not [long]::TryParse($out.Trim(), [ref]$value)) { return $null }
    return $value
}

function Get-CouponLine([long]$id) {
    $sql = "SELECT c.total_quantity, c.remaining_quantity, COUNT(ci.id) FROM coupon c LEFT JOIN coupon_issue ci ON ci.coupon_id = c.id WHERE c.id = $id GROUP BY c.id, c.total_quantity, c.remaining_quantity;"
    $out = Invoke-Sql $sql
    $p = ($out -split "`t")
    if ($p.Count -ne 3) { return "coupon: (조회 실패) $out" }

    $total = [long]$p[0]
    $dbRemaining = [long]$p[1]
    $issueCount = [long]$p[2]

    # V1~V3 는 발급할 때마다 coupon.remaining_quantity 를 줄이지만 V4 는 그 칸을 갱신하지 않는다.
    # 그래서 잔여의 정본을 버전별로 고르지 않고, Redis 재고 키가 있는지로 판단한다.
    # V1~V3 로 발급한 쿠폰은 Redis 에 키 자체가 없으므로 예전과 똑같이 DB 잔여를 본다.
    $redisStock = Get-RedisStock $id
    if ($null -eq $redisStock) {
        $gap = $total - $dbRemaining - $issueCount
        return "coupon: id=$id total=$total remaining=$dbRemaining(db) issue_count=$issueCount consistency_gap=$gap"
    }

    $gap = $total - $redisStock - $issueCount
    $line = "coupon: id=$id total=$total remaining=$redisStock(redis) issue_count=$issueCount consistency_gap=$gap"

    # 발급 사용자 집합의 크기도 발급 이력 수와 같아야 한다. 재고와 명단은 같은 Lua 안에서 함께 바뀐다.
    $issuedUsers = Get-RedisIssuedCount $id
    if ($null -ne $issuedUsers) {
        $line += " issued_users=$issuedUsers issued_gap=$($issueCount - $issuedUsers)"
    }

    # 같은 쿠폰을 V4 이전 버전으로도 발급한 적이 있으면 DB 잔여도 함께 남긴다.
    if ($dbRemaining -ne $total) { $line += " db_remaining=$dbRemaining" }
    return $line
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
