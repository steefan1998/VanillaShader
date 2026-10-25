import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// Decompiling and recompiling Minecraft for several versions at once eats all memory; do one at a time.
interface NeoForgeMutex : BuildService<BuildServiceParameters.None>

val mutex = gradle.sharedServices.registerIfAbsent("createMinecraftArtifactsMutex", NeoForgeMutex::class.java) {
    maxParallelUsages.set(1)
}

tasks.named { it == "createMinecraftArtifacts" }.configureEach {
    usesService(mutex)
}
