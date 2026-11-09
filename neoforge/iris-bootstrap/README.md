# Iris bootstrap library

This Java 25 library uses FML 12's public `ClassProcessor` service, discovered
before the transforming game class loader and Mixin plugins. Its jar manifest
declares `FMLModType: LIBRARY`; it has no normal mod entrypoint or game classes.
The Apache-2.0 license and project notices are in the repository's
[`LICENSE`](../../LICENSE) and [`NOTICE`](../../NOTICE), and are included in its jars.

The processor selects only
`net.irisshaders.iris.platform.IrisForgeHelpers.getBlockAppearance` with the
published Iris 1.11.7 (`Ag5S66CF`) descriptor and instruction sequence. It inserts
one `CHECKCAST BlockAndLightGetter` after the existing level-argument load. The
cast supplies the following call's exact symbolic argument type without early
verifier hierarchy resolution. The appearance call, argument order, nulls,
result, maximum stack/local counts and existing frames are unchanged. A correctly
cast method is left untouched; unfamiliar layouts fail explicitly.

The library references only JDK, FML SPI and ASM types. Minecraft/Iris names are
strings, not bootstrap type dependencies. ASM and FML are compile-only library
dependencies, supplied by the existing loader at runtime. It requests no frame
computation and runs before Mixin's frame-context processor and Mixin itself.

The native composite bundles it through JarJar as `qouteall:riftwoven-iris-bootstrap`
and resolves the same separate jar as a runtime-only dependency for source-set launches.
Its service must remain in this separate `LIBRARY`, not the ordinary mod jar:
FML collects early processors through its plugin class loader before preparing
game Mixin configs. Native `check` and `test` include this module's checks, and
the native package audit requires its manifest and service. Fabric does not bundle it.

Run the independent module's checks from the repository root with the existing
Java 25 toolchain and Gradle wrapper:

```sh
./gradlew -p neoforge/iris-bootstrap --offline --no-daemon test jar
```

Tests inspect the exact original published Iris artifact and fork separate
Java 25 JVMs with `-Xverify:all`. The fork model copies its actual appearance
method into an isolated helper with Minecraft-shaped stub interfaces. It checks
that the original verifier loads the world hierarchy early, the patched helper
does not, and later normal calls preserve argument identities, null handling,
return identity and null-receiver failure. These are isolated bytecode/verifier
checks, not a real-client or complete shader compatibility qualification.
