# GraalVM native image - learning project

The same small Spring Boot 3.5 (Java 21) app, started 5 different ways and compared:

- **JVM** - normal `app.jar`, run with `java -jar`
- **CDS** - JVM + archive of pre-parsed classes
- **Leyden** - JDK 25 AOT cache (classes pre-parsed and pre-linked)
- **CRaC** - snapshot of the running JVM, restored on start
- **Native** - compiled ahead-of-time by GraalVM `native-image` into one Linux binary, no Java needed to run it

Everything builds in Docker, so no local GraalVM install is needed.

## Results (measured on one dev PC)

| | JVM | CDS | Leyden | CRaC | Native |
|---|---|---|---|---|---|
| App start (Spring "Started/Restored in") | ~2.0 s | ~1.4 s | ~0.8 s | **~0.11 s** | **~0.05 s** |
| Memory, idle | ~174 MiB | ~141 MiB | ~180 MiB | ~110 MiB | **~27 MiB** |
| Image size | 131 MB | 157 MB | 162 MB | 293 MB | **54 MB** |
| Code change needed | - | none | none (JDK 25) | `org.crac` dep | hints for reflection |
| `Class.forName` on a class with no hint | works | works | works | works | **`ClassNotFoundException`** |

## Quick start

Needs Docker (give it 6-8 GB RAM for the native build).

```bash
docker build -f Dockerfile.jvm    -t graal-study:jvm .
docker build -f Dockerfile.native -t graal-study:native .
docker build -f Dockerfile.cds    -t graal-study:cds .
docker build -f Dockerfile.leyden -t graal-study:leyden .
pwsh ./build-crac.ps1
pwsh ./compare.ps1
```

Try the endpoints on each image (`docker run --rm -p 8080:8080 graal-study:native`):

- `GET /hello` - plain endpoint
- `GET /info` - says if it runs on the JVM or as native (`Substrate VM`)
- `GET /reflect?cls=RegisteredGreeter` / `?cls=UnregisteredGreeter` - the reflection "closed world" demo

## Layout

| Path | What |
|---|---|
| `src/main/.../DemoController.java` | the endpoints |
| `src/main/.../GreeterHints.java` | reflection hint for native-image (`RuntimeHintsRegistrar`) |
| `src/test/...` | JVM tests + native-only tests (`@EnabledInNativeImage`) |
| `Dockerfile.jvm` / `.native` / `.cds` / `.leyden` / `.crac` | the five builds |
| `build-crac.ps1` | CRaC needs a running process to snapshot: build, checkpoint, save as image |
| `compare.ps1` | starts every image, measures startup / memory / size |
| [`docs/LEARNINGS.md`](docs/LEARNINGS.md) | **start here** - how it works, numbers, testing, next experiments |
| [`implementation-notes.md`](implementation-notes.md) | decisions and problems hit while building |
