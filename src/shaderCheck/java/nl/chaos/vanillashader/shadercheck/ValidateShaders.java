package nl.chaos.vanillashader.shadercheck;

import static org.lwjgl.util.shaderc.Shaderc.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Build-time check: compiles every post shader with glslang (through shaderc), the Khronos reference compiler, and
 * rejects GLSL reserved words used as names, which glslang and AMD accept but NVIDIA does not. Errors that only some
 * drivers report then fail the build instead of a player's game. Run with {@code ./gradlew validateShaders}.
 */
public final class ValidateShaders {
    // Reserved for future use in the GLSL 3.30 spec, plus keywords of later versions. glslang (and AMD) accept some of
    // these as names, NVIDIA rejects them, e.g. "packed".
    private static final Set<String> RESERVED = Set.of(
        "common", "partition", "active", "asm", "class", "union", "enum", "typedef", "template", "this", "packed", "resource",
        "goto", "inline", "noinline", "volatile", "public", "static", "extern", "external", "interface", "long", "short",
        "double", "half", "fixed", "unsigned", "superp", "input", "output", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3",
        "dvec4", "fvec2", "fvec3", "fvec4", "sampler3DRect", "filter", "sizeof", "cast", "namespace", "using", "row_major",
        "sample", "patch", "subroutine", "precise", "buffer", "shared", "coherent", "restrict", "readonly", "writeonly",
        "atomic_uint", "invariant", "attribute", "varying", "precision", "highp", "mediump", "lowp"
    );
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern IMPORT = Pattern.compile("#moj_import <([a-z0-9_]+):([a-z0-9_/.]+)>");

    private ValidateShaders() {
    }

    public static void main(String[] args) throws IOException {
        Path shaders = Path.of(args[0]);
        List<Path> fragments;
        try (Stream<Path> files = Files.list(shaders.resolve("post"))) {
            fragments = files.filter(file -> file.toString().endsWith(".fsh")).sorted().toList();
        }

        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5);
        shaderc_compile_options_set_auto_map_locations(options, true);
        shaderc_compile_options_set_auto_bind_uniforms(options, true);
        shaderc_compile_options_set_forced_version_profile(options, 330, shaderc_profile_core);

        int failures = 0;
        for (Path file : fragments) {
            String source = resolveImports(Files.readString(file), shaders);
            String reserved = reservedWordsUsed(source);
            if (!reserved.isEmpty()) {
                failures++;
                System.err.println("FAILED " + file.getFileName() + ": uses GLSL reserved word(s) as names: " + reserved);
                continue;
            }
            long result = shaderc_compile_into_spv(compiler, source, shaderc_fragment_shader, file.getFileName().toString(), "main", options);
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                failures++;
                System.err.println("FAILED " + file.getFileName() + ":\n" + shaderc_result_get_error_message(result));
            } else {
                System.out.println("ok     " + file.getFileName());
            }
            shaderc_result_release(result);
        }
        shaderc_compile_options_release(options);
        shaderc_compiler_release(compiler);

        if (failures > 0) {
            throw new IllegalStateException(failures + " shader(s) failed to compile");
        }
    }

    private static String reservedWordsUsed(String source) {
        Matcher matcher = IDENTIFIER.matcher(COMMENTS.matcher(source).replaceAll(" "));
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            if (RESERVED.contains(matcher.group())) {
                found.add(matcher.group());
            }
        }
        return String.join(", ", found);
    }

    // Inlines our own includes the way Minecraft's preprocessor does; anything else must not be imported here.
    private static String resolveImports(String source, Path shaders) throws IOException {
        Matcher matcher = IMPORT.matcher(source);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Path include = shaders.resolve("include").resolve(matcher.group(2));
            matcher.appendReplacement(out, Matcher.quoteReplacement(Files.readString(include)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
