plugins {
    id("java-library")
    id("idea")
    id("net.neoforged.moddev")
    id("neoforge-mutex")
}

fun prop(key: String): String = sc.properties[key]

val mcVersion = sc.current.version
val modId = prop("mod.id")

version = "${prop("mod.version")}+mc$mcVersion"
group = prop("mod.group")
base.archivesName = modId

val javaVersion = if (sc.current.parsed >= "26.1") 25 else 21
java.toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)

val devCapture = findProperty("devCapture")
val noSodium = hasProperty("noSodium")
val jvmProperties = properties.filterKeys { it.startsWith("jvm.") }

// 26.1.2 keeps the original run/ folder (test world, Sodium, WeatherStory); other versions get runs/<version>/.
val runDir = if (mcVersion == "26.1.2") rootProject.file("run") else rootProject.file("runs/$mcVersion")

neoForge {
    version = prop("deps.neoforge")

    runs {
        register("client") {
            client()
            gameDirectory = runDir
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            if (devCapture != null) {
                // ./gradlew :<version>:runClient -PdevCapture : loads <run dir>/saves/world and screenshots every profile
                systemProperty("vanillashader.devCapture", "true")
                systemProperty("vanillashader.devCapture.commands", devCapture.toString())
                programArguments.addAll("--quickPlaySingleplayer", "world")
                // -Pjvm.some.property=value becomes -Dsome.property=value, e.g. to force WeatherStory's cloud preview
                jvmProperties.forEach { (key, value) -> systemProperty(key.substring(4), value.toString()) }
            }
        }
        register("server") {
            server()
            gameDirectory = runDir
            programArgument("--nogui")
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
        }
    }
}

// LevelRendererMixin hooks methods that only exist from 26.3 (its source is empty before that).
val clientMixins = listOf("GameRendererMixin", "MainTargetAccessor") + if (sc.current.parsed >= "26.3") listOf("LevelRendererMixin") else listOf()

val generateModMetadata = tasks.register<ProcessResources>("generateModMetadata") {
    val replaceProperties = mapOf(
        "minecraft_version_range" to prop("mod.mc_range"),
        "neo_version_range" to prop("deps.neoforge_range"),
        "mod_id" to modId,
        "mod_name" to prop("mod.name"),
        "mod_version" to version.toString(),
        "mod_authors" to prop("mod.authors"),
        "mod_description" to prop("mod.description"),
        "mixin_java" to "JAVA_$javaVersion",
        "client_mixins" to clientMixins.joinToString(",\n    ") { "\"$it\"" },
    )
    inputs.properties(replaceProperties)
    expand(replaceProperties)
    from(rootProject.file("src/main/templates"))
    into(layout.buildDirectory.dir("generated/sources/modMetadata"))
}
sourceSets.main.get().resources.srcDir(generateModMetadata)
neoForge.ideSyncTask(generateModMetadata)

// From 26.3 shaders go through SPIR-V, which needs explicit locations on stage inputs and outputs (like vanilla's), and
// includes are written #include.
if (sc.current.parsed >= "26.3") {
    tasks.named<ProcessResources>("processResources") {
        // Gradle cannot see changes inside the filter below; bump this when editing it.
        inputs.property("shaderRewriteVersion", 2)
        filesMatching("assets/vanillashader/shaders/post/*.fsh") {
            filter { line ->
                when (line) {
                    "#version 330" -> "#version 330\n#extension GL_ARB_separate_shader_objects : require"
                    "in vec2 texCoord;" -> "layout(location = 0) in vec2 texCoord;"
                    "out vec4 fragColor;" -> "layout(location = 0) out vec4 fragColor;"
                    // 26.3 renamed Mojang's include directive.
                    else -> line.replace("#moj_import <", "#include <")
                }
            }
        }
    }
}

// The GPU layer (client/gpu) has two implementations: Minecraft's own GPU API from 26.1, and plain OpenGL in
// src/gl for 1.21.1, which has no such API yet.
if (sc.current.parsed < "26.1") {
    val glSources = rootProject.file("src/gl/java")
    sourceSets.main {
        java.srcDir(glSources)
        java.exclude { it.relativePath.pathString.startsWith("nl/chaos/vanillashader/client/gpu/") && !it.file.startsWith(glSources) }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.named("createMinecraftArtifacts") {
    dependsOn("stonecutterGenerate")
}

repositories {
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

val sodiumMod: Configuration by configurations.creating

// The Sodium release jar nests the real mod jar under META-INF/jarjar; unpack it so its API is on the compile classpath.
val extractSodiumApi = tasks.register<Sync>("extractSodiumApi") {
    from({ zipTree(sodiumMod.singleFile) }) {
        include("META-INF/jarjar/*.jar")
        eachFile { path = name }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("sodium-api"))
}

// Puts the matching Sodium release in this version's run folder, so dev runs test with it.
val copySodiumToRun = tasks.register<Copy>("copySodiumToRun") {
    // -PnoSodium skips it (remove the jar from the mods folder too) to tell Sodium problems from ours.
    onlyIf { !noSodium }
    from(sodiumMod)
    into(File(runDir, "mods"))
}
tasks.named { it == "runClient" }.configureEach { dependsOn(copySodiumToRun) }

dependencies {
    // Sodium is optional: we only compile against its config API, it is never required at runtime.
    sodiumMod("maven.modrinth:sodium:${prop("deps.sodium")}")
    compileOnly(files(extractSodiumApi).asFileTree.matching { include("*.jar") })
    if (sc.current.parsed < "26.1") {
        // Minecraft only ships JSpecify from 26.1 on.
        compileOnly("org.jspecify:jspecify:1.0.0")
    }
}

// Compiles every shader with glslang (the Khronos reference compiler) so that driver-specific GLSL errors - the kind
// AMD accepts but NVIDIA rejects - break the build instead of the game. Run: ./gradlew validateShaders
val shaderCheck: SourceSet = sourceSets.create("shaderCheck") {
    java.setSrcDirs(listOf(rootProject.file("src/shaderCheck/java")))
}

dependencies {
    "shaderCheckImplementation"(platform("org.lwjgl:lwjgl-bom:3.4.1"))
    "shaderCheckImplementation"("org.lwjgl:lwjgl")
    "shaderCheckImplementation"("org.lwjgl:lwjgl-shaderc")
    for (natives in listOf("natives-windows", "natives-linux")) {
        "shaderCheckRuntimeOnly"("org.lwjgl:lwjgl::$natives")
        "shaderCheckRuntimeOnly"("org.lwjgl:lwjgl-shaderc::$natives")
    }
}

val validateShaders = tasks.register<JavaExec>("validateShaders") {
    group = "verification"
    description = "Compiles all post shaders with glslang to catch errors strict drivers would report."
    classpath = shaderCheck.runtimeClasspath
    mainClass = "nl.chaos.vanillashader.shadercheck.ValidateShaders"
    // The processed shaders, so version-specific rewrites (see processResources) are checked too.
    dependsOn("processResources")
    args(layout.buildDirectory.dir("resources/main/assets/vanillashader/shaders").get().asFile.absolutePath)
}
tasks.named("check") {
    dependsOn(validateShaders)
}
