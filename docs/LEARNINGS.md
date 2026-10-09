# GraalVM native image - learnings

Say it: **"GRAHL - vee - em"** ("Graal" = old French for Grail, long "a" like in "father").

## 1. What GraalVM is

Two things share the name:

| Part | What it does |
|---|---|
| **GraalVM JDK** | A normal JDK with a newer JIT compiler (Graal JIT). You can run `java -jar` on it like any JDK. |
| **native-image** | A tool inside GraalVM that compiles your app **ahead of time (AOT)** into one OS binary. This is the "exe" part. |

This project is about `native-image`.

## 2. JVM jar vs native binary

```
JVM (jar):     build -> app.jar ............ run: JVM starts -> loads classes -> interprets
                                                  -> JIT compiles hot code while running (warm-up)

Native:        build -> Spring AOT -> native-image (analyse + compile EVERYTHING now) -> app (binary)
                                             run: binary starts, code already machine code
```

Why native starts fast:
- No JVM to boot, no class loading, no bytecode verification, no JIT warm-up.
- **Heap snapshot**: objects created by static initializers at build time are stored inside
  the binary and mapped into memory at start.
- Spring AOT already decided the bean wiring at build time (generated Java code), so Spring
  does not scan the classpath or read annotations at startup.

## 3. The "closed world" rule (the main catch)

native-image starts from `main()` and follows every call it can **see**. Anything it cannot
see is **not in the binary**:
- `Class.forName(nameFromUser)`, reflection, dynamic proxies, resources loaded by name,
  serialization, JNI.

Fix = give it **hints** (metadata). In Spring: a `RuntimeHintsRegistrar` + `@ImportRuntimeHints`
(see `GreeterHints.java`). Spring and most libraries already ship hints for their own code;
you only add hints for **your own** dynamic code. Hints end up in
`target/spring-aot/main/resources/META-INF/native-image/**/reflect-config.json`
(newer GraalVM: `reachability-metadata.json`).

Test hints on the JVM in milliseconds (`GreeterHintsTest`) instead of finding out after a
5-minute native build.

## 4. Tradeoffs

| Good | Bad |
|---|---|
| Startup in tens of ms | Build takes minutes and GBs of RAM |
| Low memory, no JIT/JVM overhead | Reflection / proxies / resources need hints |
| One file, no JRE in the image | Binary per OS + CPU (no cross-compile) |
| Great for serverless, CLI tools, scale-to-zero | Peak throughput can be lower than a warmed-up JIT (PGO in Oracle GraalVM helps) |
| Smaller attack surface | Debugging/profiling tools are different (no JFR/JMX by default) |
| | Some libraries do not work without metadata |

When to use: cold start matters (Lambda / Azure Functions / K8s scale-from-zero / CLI),
or many small instances where memory costs money.
When NOT: long-running services where JIT peak speed matters and startup is rare.

## 5. Commands

```
docker build -f Dockerfile.jvm    -t graal-study:jvm .
docker build -f Dockerfile.native -t graal-study:native .
pwsh ./compare.ps1
```

Local JVM tests (no Docker): `.\mvnw.cmd test` with `JAVA_HOME` = a JDK 21.

Native tests (no local GraalVM needed - runs in the GraalVM container, bash/WSL syntax).
`--entrypoint bash` is required: the image's default entrypoint is `native-image` itself.
```
docker run --rm -v "$PWD:/src:ro" -v graal-study-m2:/root/.m2 --entrypoint bash \
  ghcr.io/graalvm/native-image-community:21 \
  -c "cp -r /src /work && cd /work && rm -rf target dist && sed -i 's/\r$//' mvnw && sh mvnw -B -PnativeTest test"
```

Copy the native binary out of the image (to run it in WSL / on Linux):
```
docker create --name graal-extract graal-study:native
docker cp graal-extract:/app dist/graalvm-study
docker rm graal-extract
```

## 6. Measured on this machine

2026-10-09, Docker Desktop (8 GB, 12 CPU), same app, `compare.ps1 -Runs 3`.
Run 1 of the JVM is slower (cold disk cache); runs 2-3 are typical.

| | JVM jar (Temurin 21) | Native (GraalVM CE 21) | Native gain |
|---|---|---|---|
| Spring "Started in" | 1.91 - 2.29 s | **0.05 - 0.06 s** | ~35x |
| First HTTP 200 (incl. `docker run`) | 2.9 - 4.4 s | **0.39 - 0.42 s** | ~7x |
| Memory idle | 175 - 215 MiB | **27 MiB** | ~7x |
| Memory after 500 requests | 180 - 215 MiB | **38 MiB** | ~5x |
| Image size (content) | 131 MB | **54 MB** | ~2.4x |
| Build time | ~2.5 min (mostly Maven download) | 2 min 5 s native-image alone, peak 2.6 GB RAM | slower |

Reading the numbers:
- "First HTTP 200" for native (~400 ms) is almost all Docker container start; the app itself
  is ready in ~55 ms. Outside Docker the gap is even bigger.
- `/info` on native says `vmName: Substrate VM` - the tiny runtime (GC, threads) that
  native-image links into the binary instead of a full JVM.

Reflection demo result (the closed-world rule, live):

| `/reflect?cls=` | JVM | Native |
|---|---|---|
| `RegisteredGreeter` (has hint) | 200 ok | 200 ok |
| `UnregisteredGreeter` (no hint) | 200 ok | **500 `ClassNotFoundException`** |

Same code, same jar input - the class simply is not in the native binary.

native-image stage times (from `build-native.log`):

| Stage | Time | Peak RAM |
|---|---|---|
| [1/8] Initializing | 4.8 s | 0.18 GB |
| [2/8] Performing analysis (closed world) | **53.3 s** | 1.95 GB |
| [3-5/8] Universe, parsing, inlining | 14 s | 2.2 GB |
| [6/8] Compiling methods (AOT) | **36.9 s** | 1.58 GB |
| [7-8/8] Layout + create image | 15 s | 2.6 GB |

Gotcha hit while building: the native binary is named after `<artifactId>`
(`target/graalvm-study`), **not** `<finalName>`. Set `<imageName>` in the native plugin
config if you want a different name.

## 7. Testing

Daily work stays on the JVM: `mvnw test`, IDE run/debug, `spring-boot:run` - nothing changes.

`mvnw -PnativeTest test` compiles the JUnit tests INTO a native binary and runs them there.
Measured: 2 min 13-18 s compiling (native-image), then the tests run fast. Total ~2.5-3.3 min
vs seconds on the JVM - so run it in CI / before release, not on every change.

Why it is worth it: a missing hint is **never a build error, always a runtime error**.
`javac` and native-image both say BUILD SUCCESS; only running the code finds it.

What the native test run caught here:

| Run | Failure | Lesson |
|---|---|---|
| 1 | `infoReportsJvm` - nativeImage was `true` | test assumed the JVM |
| 1 | `UnregisteredGreeter` - 500 instead of 200 | the missing-hint bug, caught by a test instead of a user |
| 2 | `Cannot determine correct type for matchesSafely() method` | **Hamcrest** `startsWith()` uses reflection internally and ships no native hints - a 3rd-party library breaking only in native |

Fixes: `@DisabledInNativeImage` / `@EnabledInNativeImage` (JUnit 5) mark which runtime a test
is for; replaced the Hamcrest matcher with an exact string.

## 8. Faster startup WITHOUT native: CDS, Leyden, CRaC

Same jar, normal JVM, so reflection works with no hints and builds stay fast.
Each one does a **training run** at build time and saves something, so later starts skip work.

| Method | What is saved from the training run | Where | Needs |
|---|---|---|---|
| **CDS** (AppCDS) | classes already parsed + verified (`app.jsa`) | in the JDK since long ago | unpacked jar (`-Djarmode=tools extract`) |
| **Leyden AOT cache** | classes parsed **and linked**, plus method profiles (`app.aot`, 50 MB) | JDK 24+ (one-step flag in JDK 25) | JDK 25 runtime |
| **CRaC** | a **snapshot of the whole running process**: heap, threads, JIT-compiled code | JDK built with CRaC (Azul Zulu, BellSoft Liberica) | `org.crac` dep + checkpoint step (`build-crac.ps1`) |
| GraalVM native | the whole app compiled to machine code | GraalVM | hints, slow build |

Measured 2026-10-09, same machine, `compare.ps1 -Runs 3` (Spring time / first HTTP 200 incl. `docker run`):

| Image | Spring start | First HTTP 200 | Memory idle | Image size | Reflection, no hint |
|---|---|---|---|---|---|
| jvm (Temurin 21) | 2.01 - 2.04 s | 2.9 - 3.2 s | ~174 MiB | 131 MB | works |
| cds (Temurin 21) | 1.34 - 1.49 s | ~2.1 s | ~141 MiB | 157 MB | works |
| leyden (Temurin 25) | 0.69 - 1.08 s | 1.3 - 1.6 s | ~180 MiB | 162 MB | works |
| **crac** (Zulu 21 CRaC) | **0.11 - 0.12 s** ("Restored in") | 0.53 - 0.61 s | ~110 MiB | 293 MB | works |
| **native** (GraalVM 21) | **0.05 - 0.14 s** | 0.38 - 0.55 s | **~27 MiB** | **54 MB** | **fails** |

What the numbers say:
- **CDS** = ~30% faster start for free (no code change, still Java 21). The easiest win.
- **Leyden** = ~2x faster than plain JVM, still no code change - but needs JDK 25.
- **CRaC** = almost native start speed, with normal JVM behaviour and a **warm JIT** (code is
  already compiled when restored - native never gets JIT). But memory stays JVM-sized and the
  image is the biggest (it carries the snapshot).
- **Native** still wins on memory and size by far.

CRaC catches (why it is not free):
- The snapshot holds **everything in memory** - including passwords, tokens, keys read at
  startup. Treat the image like a secret. Do not checkpoint after loading production secrets.
- Open files / sockets / DB connections cannot be saved. Spring closes and reopens its own
  (`Spring-managed lifecycle restart` in the log); your own resources need `org.crac.Resource`
  callbacks. Same "frozen at build time" problem as section 3: time, random seeds, hostnames.
- Restore must run on a compatible CPU (the JIT code uses CPU features of the checkpoint machine).
- Engine: Azul's default here is **warp** (user space) - worked **without** `--privileged`.
  The older **CRIU** engine needs extra container permissions.

## 9. Training run, redeploys, and running without Docker

**"Training run"** = not ML. Start the app once while the JVM records what it does (classes
loaded, linking, hot methods), save that to a file, and later starts read the file instead of
redoing the work. What the training run exercises is what gets faster - ours stops right after
startup (`-Dspring.context.exit=onRefresh`), so only startup is sped up.

**Redeploys with Docker:** the training run is a normal `RUN` line, so normal layer cache applies.

| Situation | Trains again? |
|---|---|
| Redeploy the same image | No - `app.aot` is inside the image, deploy = pull + run |
| Rebuild, no code change, same machine | No - every layer `CACHED` (rebuild took 2.8 s) |
| Code or base JDK changed | Yes - and it must (cache has to match jar + JDK) |
| Fresh CI runner | Yes, unless you share cache (`buildx --cache-from/--cache-to`) |

Catch: Maven writes timestamps into the jar, so a rebuilt jar with the same code is still a
"new" file and retrains. Fix: reproducible builds (`project.build.outputTimestamp` in the pom).
CRaC is not a `RUN` step (`docker commit` in `build-crac.ps1`), so layer cache does not cover it.

**Without Docker** - measured on Windows, JDK 25, plain `java -jar` (time until Spring ready):

| | Start | Extra step |
|---|---|---|
| plain jar | 1.64 s | - |
| Leyden AOT cache | 0.76 - 0.87 s | training run once (8 s, `app.aot` 52 MB) |
| auto CDS | ~1.0 s | none |

Three ways to manage the cache file yourself:

- **A. Deploy script trains** (Leyden): copy jar -> extract -> training run -> start with
  `-XX:AOTCache=app.aot`. Same jar + same JDK on redeploy -> skip training (compare checksum).
- **B. CI trains, ships `app.jar` + `app.aot` together.** CI must use the exact same JDK version
  as the server.
- **C. Auto CDS** - no step at all, the JVM manages the file (JDK 19+):
  `java -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=app.jsa -jar app.jar`
  First start writes `app.jsa`; a changed jar or JDK makes the JVM rebuild it by itself.
  Smaller gain than Leyden here (1.6x vs 2x) but zero work. Best first choice on a plain server.

Not tested yet: what Leyden does when `app.aot` does not match the jar/JDK (expected: warning +
normal start without the cache).

PowerShell gotcha: quote `-D` options when calling `java` (`'-Dspring.context.exit=onRefresh'`).
Unquoted, PowerShell splits at the dot and Java fails with
`Could not find or load main class .context.exit=onRefresh`.

## 10. Next experiments

1. Remove `@ImportRuntimeHints` -> rebuild native -> `RegisteredGreeter` also fails.
2. Use the tracing agent to find hints automatically:
   `java -agentlib:native-image-agent=config-output-dir=... -jar app.jar` then hit all endpoints.
3. Fully static binary on `FROM scratch`: `--static --libc=musl` (needs musl toolchain).
4. Real Windows `.exe`: install GraalVM JDK 21 + Visual Studio Build Tools (C++ workload),
   then `.\mvnw.cmd -Pnative native:compile` in an "x64 Native Tools" prompt.
5. Throughput: run a load test (e.g. `hey` / `wrk`) for 60s on both - JVM should catch up or win.
