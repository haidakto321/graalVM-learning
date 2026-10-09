# Compare the same Spring Boot app started 5 different ways.
# Build the images first (missing ones are skipped):
#   docker build -f Dockerfile.jvm    -t graal-study:jvm .
#   docker build -f Dockerfile.native -t graal-study:native .
#   docker build -f Dockerfile.cds    -t graal-study:cds .
#   docker build -f Dockerfile.leyden -t graal-study:leyden .
#   pwsh ./build-crac.ps1                                      # -> graal-study:crac
# Usage: pwsh ./compare.ps1 [-Runs 3]
param([int]$Runs = 3)

$ErrorActionPreference = 'Stop'
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(2)

# tag, host port, extra `docker run` args
$variants = @(
    @{ tag = 'graal-study:jvm';    port = 18080; args = @() }
    @{ tag = 'graal-study:cds';    port = 18082; args = @() }
    @{ tag = 'graal-study:leyden'; port = 18083; args = @() }
    @{ tag = 'graal-study:crac';   port = 18084; args = @() }   # warp engine: no --privileged needed
    @{ tag = 'graal-study:native'; port = 18081; args = @() }
)

function Get-Status([string]$url) {
    try {
        $resp = $http.GetAsync($url).GetAwaiter().GetResult()
        return [int]$resp.StatusCode
    } catch { return 0 }
}

function Get-Body([string]$url) {
    $resp = $http.GetAsync($url).GetAwaiter().GetResult()
    return "$([int]$resp.StatusCode) $($resp.Content.ReadAsStringAsync().GetAwaiter().GetResult())"
}

function Measure-Image([string]$tag, [int]$port, [string[]]$runArgs) {
    $name = "graal-study-$($tag -replace '[:]', '-')"
    $results = @()
    for ($i = 1; $i -le $Runs; $i++) {
        docker rm -f $name 2>$null | Out-Null
        # Stopwatch covers container start + app start + first HTTP answer = what a user waits for.
        $sw = [Diagnostics.Stopwatch]::StartNew()
        docker run -d --name $name -p "${port}:8080" @runArgs $tag | Out-Null
        while ((Get-Status "http://localhost:$port/hello") -ne 200) {
            if ($sw.Elapsed.TotalSeconds -gt 60) {
                Write-Host (docker logs $name 2>&1 | Out-String)
                throw "$tag did not answer in 60s"
            }
            Start-Sleep -Milliseconds 10
        }
        $firstResponseMs = [int]$sw.Elapsed.TotalMilliseconds

        # Spring's own number: time inside the process, without Docker overhead.
        # A CRaC restore logs "Restored ... in X seconds" instead of "Started".
        $log = docker logs $name 2>&1 | Out-String
        $springSec = if ($log -match '(?:Started|Restored) \S+ in ([\d.]+) seconds') { [double]$Matches[1] } else { $null }

        $memIdle = (docker stats --no-stream --format '{{.MemUsage}}' $name).Split('/')[0].Trim()
        # Some load so the JVM has a chance to grow its heap / JIT-compile.
        1..500 | ForEach-Object { Get-Status "http://localhost:$port/hello" | Out-Null }
        $memLoad = (docker stats --no-stream --format '{{.MemUsage}}' $name).Split('/')[0].Trim()

        $results += [pscustomobject]@{
            image = $tag; run = $i; firstResponseMs = $firstResponseMs; springStartSec = $springSec
            memIdle = $memIdle; memAfter500Req = $memLoad
        }

        if ($i -eq $Runs) {
            Write-Host "`n--- $tag endpoints ---" -ForegroundColor Cyan
            Write-Host "/info                            -> $(Get-Body "http://localhost:$port/info")"
            Write-Host "/reflect?cls=UnregisteredGreeter -> $(Get-Body "http://localhost:$port/reflect?cls=UnregisteredGreeter")"
        }
        docker rm -f $name | Out-Null
    }
    return $results
}

$present = $variants | Where-Object { docker image inspect $_.tag 2>$null | Out-Null; $LASTEXITCODE -eq 0 }
$all = @()
foreach ($v in $present) { $all += Measure-Image $v.tag $v.port $v.args }

Write-Host "`n--- startup + memory ($Runs runs each) ---" -ForegroundColor Cyan
$all | Format-Table -AutoSize | Out-String | Write-Host

Write-Host "--- image size ---" -ForegroundColor Cyan
foreach ($v in $present) {
    $bytes = [long](docker image inspect -f '{{.Size}}' $v.tag)
    '{0,-20} {1,8:N0} MB' -f $v.tag, ($bytes / 1MB)
}
