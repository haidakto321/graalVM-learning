# GraalVM native image - learning project

The same small Spring Boot 3.5 (Java 21) app, built two ways and compared:

- **JVM** - normal `app.jar`, run with `java -jar`
- **Native** - compiled ahead-of-time by GraalVM `native-image` into one Linux binary, no Java needed to run it

Everything builds in Docker, so no local GraalVM install is needed.

## Results (measured on one dev PC)

| | JVM jar | Native binary |
|---|---|---|
| App start (Spring "Started in") | ~2.0 s | **~0.055 s** |
| Memory, idle | ~175 MiB | **~27 MiB** |
| Image size | 131 MB | **54 MB** |
| Build | seconds | +2 min, ~2.6 GB RAM |
| `Class.forName` on a class with no hint | works | **`ClassNotFoundException`** |

## Quick start

Needs Docker (give it 6-8 GB RAM for the native build).

```bash
docker build -f Dockerfile.jvm    -t graal-study:jvm .
docker build -f Dockerfile.native -t graal-study:native .
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
| `Dockerfile.jvm` / `Dockerfile.native` | the two builds |
| `compare.ps1` | starts both images, measures startup / memory / size |
| [`docs/LEARNINGS.md`](docs/LEARNINGS.md) | **start here** - how it works, numbers, testing, next experiments |
| [`implementation-notes.md`](implementation-notes.md) | decisions and problems hit while building |
