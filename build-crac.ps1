# CRaC needs a RUNNING process to snapshot, so it cannot happen inside `docker build`.
# Steps: build checkpoint image -> run it once (it snapshots itself and exits)
#        -> save that container as a new image whose start command RESTORES the snapshot.
# No --privileged needed: Azul Zulu's default CRaC engine here is "warp", which runs in user
# space. The older CRIU engine (-XX:CRaCEngine=criu) needs extra kernel permissions
# (--privileged or --cap-add CHECKPOINT_RESTORE,SYS_PTRACE). Tested both steps without it.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

docker build -f Dockerfile.crac -t graal-study:crac-checkpoint .
if ($LASTEXITCODE -ne 0) { throw "build failed" }

docker rm -f graal-crac-cp 2>$null | Out-Null
docker run --name graal-crac-cp graal-study:crac-checkpoint
Write-Host "checkpoint container exit code: $LASTEXITCODE (non-zero is normal: the JVM is killed after the dump)"

docker commit --change 'ENTRYPOINT ["java", "-XX:CRaCRestoreFrom=/crac"]' graal-crac-cp graal-study:crac | Out-Null
docker rm graal-crac-cp | Out-Null
Write-Host "Done. Run: docker run --rm -p 8080:8080 graal-study:crac"
