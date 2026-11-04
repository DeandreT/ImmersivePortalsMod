Updated to Minecraft 26.3 (Java 25, Fabric Loader 0.20.1, Fabric API 0.165.2), based on the port by nyannoying1337. Portal rendering was redesigned, so back up your worlds first.

Reduced memory use: smaller terrain buffers, free Java and native memory is returned to the system after loading, and the terrain data of dimensions no portal has shown for 30 seconds is released. The new `compactTerrainBuffers`, `trimJavaHeap` and `releaseIdleRenderers` options turn these off.
