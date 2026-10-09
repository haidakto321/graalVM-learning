# Implementation notes - graalvm-study

Decisions not in the request, changes, tradeoffs. Newest at bottom.

## Decisions

1. **Build in Docker, not as a Windows .exe** (user choice 2026-10-09). This machine has no
   GraalVM and no Visual Studio C++ Build Tools, and native-image on Windows needs both.
   Docker gives a **Linux** binary - the same thing you would ship to a server or K8s.
   native-image cannot cross-compile: the binary always matches the OS it was built on.
2. **Java 21, Spring Boot 3.5.6.** Same versions as `springboot-rag` (known to work here).
   GraalVM image `ghcr.io/graalvm/native-image-community:21` matches the Java version.
   JDK 25 is the machine default, but Java 21 is the safer pairing for Boot 3.5.
3. **Maven wrapper copied from `springboot-rag`** (3.3.4, only-script, Maven 3.9.16)
   instead of downloading a new one.
4. **`finalName` = `app`** so both Dockerfiles use fixed paths (`target/app.jar`, `target/app`).
5. **Runtime image for native = `debian:bookworm-slim`**, not `distroless` or `scratch`.
   The binary is dynamically linked to glibc; debian slim has glibc and a shell for poking
   around. A fully static binary (`--static --libc=musl`) on `scratch` would be smaller -
   left as a next experiment, see LEARNINGS.md.
6. **Reflection demo uses two greeters in one build** (`RegisteredGreeter` declared in
   `GreeterHints`, `UnregisteredGreeter` not declared), so one native build shows both
   success and failure - no rebuild needed to see the contrast.
7. **`/reflect` only accepts a simple class name inside `com.example.graal.greeters`.**
   Loading any class named by a request parameter is a remote-code-execution pattern;
   even in a learning app, never copy that into real code.
8. **No actuator, no DB.** Kept the app tiny so the numbers show GraalVM, not library weight.

## Changes made during the build

- **First native build failed at `COPY target/app`.** `finalName` only renames the jar; the
  native plugin names the binary after `artifactId` (`target/graalvm-study`). Fixed the
  `COPY` path in `Dockerfile.native` instead of adding `<imageName>` to the pom - that kept
  the cached 2-minute native-compile layer, so the rebuild took seconds.
- `compare.ps1`: table printed under the wrong heading (pipeline output vs `Write-Host`
  ordering); now piped through `Out-String | Write-Host`.

- **Native tests (`-PnativeTest test`) added as a check, run in the GraalVM container**
  (source mounted read-only and copied to `/work`, Maven cache in docker volume `graal-study-m2`).
  First run: 2 of 7 failed - tests assumed the JVM. Split them with `@DisabledInNativeImage` /
  `@EnabledInNativeImage`. Second run: Hamcrest `startsWith()` failed in native (reflection inside
  Hamcrest); replaced with an exact error string. Third run: 7 passed, 2 skipped, 0 failed.
  Command: LEARNINGS.md section 5.
- First `docker run` for native tests failed with exit 20: the GraalVM image's ENTRYPOINT is
  `native-image` itself, so a shell command must use `--entrypoint bash`.

## CDS / Leyden / CRaC round (2026-10-09)

- **Same project, not a new one**: the point is comparing startup methods on the same app.
- **Added dependency `org.crac:crac`** (user approved; version 1.5.0 managed by Spring Boot, so no
  version in the pom). Needed for `-Dspring.context.checkpoint=onRefresh`. No effect on other JDKs.
  The native image was NOT rebuilt after this pom change - the native app code is unchanged.
- CDS and Leyden use the training run `-Dspring.context.exit=onRefresh` inside `docker build`;
  CRaC cannot (needs a running container to snapshot), hence `build-crac.ps1` + `docker commit`.
- Leyden runs Java 21 bytecode on a **JDK 25** runtime (Spring Boot 3.5.6 started fine on it).
- CRaC base image: `azul/zulu-openjdk:21-jdk-crac-latest`. Checking `:21-jdk-crac` hit a network
  reset from Docker Hub's CDN (not proven missing); used the tag that resolved. Pin a digest or
  fixed version for anything real - `-latest` can change under you.
- **First version used `--privileged`** (assumed the CRIU engine). The log showed Azul's **warp**
  engine instead; tested checkpoint AND restore without `--privileged` - both work, so it was
  removed. Less privilege by default.
- `graal-study:crac-checkpoint` is an intermediate image left behind by `build-crac.ps1`; safe to
  delete, it is rebuilt on the next run.

## Local (no Docker) Leyden / auto-CDS check (2026-10-09)

- Ran on Windows with the installed Temurin 25, `dist/app.jar`, in a scratch folder (not in repo).
- **First measurement was wrong:** unquoted `-Dspring.context.exit=onRefresh` in PowerShell was
  split at the dot, every run failed in ~0.08 s and looked "fast". Caught because a Spring start
  cannot take 0.08 s and the cache was 10 MB instead of 50 MB. Re-ran with quoted args and a
  check that the Spring context actually initialized. Numbers in LEARNINGS.md section 9.

## Tradeoffs / things to know

- `ghcr.io/graalvm/native-image-community:21` ships **Java 21.0.2** (old patch level); the
  JVM image runs 21.0.12. Fine for learning; for real use pin a newer GraalVM tag
  (e.g. `:21.0.x` or `:25`).
- Native `/info` reports heapMax ~6 GB: Substrate VM's default max heap is a % of the
  machine's RAM (the Docker VM here), not `-Xmx`. Set `-Xmx` as a runtime arg to cap it.
- `dist/graalvm-study` = the native binary copied out of the image (`docker create` + `docker cp`)
  so it can be run in WSL. 80 MB on disk (has debug info) vs 54 MB image content (compressed).
  In WSL from `/mnt/d` it started in 0.28 s vs 0.055 s in Docker - WSL reads the Windows drive
  slowly. `dist` is in `.dockerignore` so it is not sent as build context.
- Build logs (`build-native.log`, `native-test.log`), `target/` and `dist/` are git-ignored. The
  per-stage native-image times from the log are copied into LEARNINGS.md section 6.
- `.gitattributes` forces LF on `mvnw` / Dockerfiles, so a Windows checkout still builds in Linux
  containers (the `sed 's/\r$//'` in the Dockerfiles stays as a second guard).

- `compare.ps1` "first response" time includes `docker run` overhead (same for both images),
  so it is larger than Spring's own "Started ... in X seconds" line. The script prints both.
- Image size from `docker image inspect` depends on the Docker image store; compare the two
  numbers to each other, not to sizes quoted online.
- Docker Desktop VM here has 8 GB RAM and 12 CPUs. native-image wants several GB; with less
  RAM the build can be killed with exit code 137.
