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
    // Built-in function names. The spec allows hiding them with a variable or parameter, but some Intel and older
    // NVIDIA drivers then fail to compile the shader, so treat it as an error.
    private static final Set<String> BUILTINS = Set.of(
        "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
        "pow", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt", "abs", "sign", "floor", "trunc", "round", "roundEven",
        "ceil", "fract", "mod", "modf", "min", "max", "clamp", "mix", "step", "smoothstep", "isnan", "isinf", "length",
        "distance", "dot", "cross", "normalize", "faceforward", "reflect", "refract", "matrixCompMult", "outerProduct",
        "transpose", "determinant", "inverse", "lessThan", "greaterThan", "equal", "notEqual", "any", "all", "not",
        "texture", "textureSize", "texelFetch", "textureLod", "textureGrad", "textureOffset", "dFdx", "dFdy", "fwidth",
        "noise1", "noise2", "noise3", "noise4"
    );
    private static final Pattern DECLARATION = Pattern.compile(
        "\\b(?:float|int|uint|bool|[biu]?vec[234]|mat[234](?:x[234])?|sampler2D)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:[=;,)\\[])"
    );
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern IMPORT = Pattern.compile("#(?:moj_import|include) <([a-z0-9_]+):([a-z0-9_/.]+)>");

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
        // Minecraft 26.2+ compiles our shaders with this define (reversed depth); check that variant too.
        long reversedZ = shaderc_compile_options_clone(options);
        shaderc_compile_options_add_macro_definition(reversedZ, "VANILLASHADER_REVERSED_Z", "1");

        int failures = 0;
        for (Path file : fragments) {
            String source = resolveImports(Files.readString(file), shaders);
            String problems = styleProblems(Files.readString(file), source);
            if (!problems.isEmpty()) {
                failures++;
                System.err.println("FAILED " + file.getFileName() + ": " + problems);
                continue;
            }
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
        shaderc_compile_options_release(reversedZ);
        shaderc_compiler_release(compiler);

        if (failures > 0) {
            throw new IllegalStateException(failures + " shader(s) failed to compile");
        }
    }

    private static String compileError(long compiler, String source, Path file, long options) {
        long result = shaderc_compile_into_spv(compiler, source, shaderc_fragment_shader, file.getFileName().toString(), "main", options);
        try {
            return shaderc_result_get_compilation_status(result) == shaderc_compilation_status_success ? null : shaderc_result_get_error_message(result);
        } finally {
            shaderc_result_release(result);
        }
    }

    private static String styleProblems(String original, String source) {
        List<String> problems = new java.util.ArrayList<>();
        if (!original.startsWith("#version 330")) {
            problems.add("must start with #version 330");
        }
        String code = COMMENTS.matcher(source).replaceAll(" ");
        Matcher declarations = DECLARATION.matcher(code);
        Set<String> hiding = new TreeSet<>();
        while (declarations.find()) {
            if (BUILTINS.contains(declarations.group(1))) {
                hiding.add(declarations.group(1));
            }
        }
        if (!hiding.isEmpty()) {
            problems.add("variables or parameters named like built-in functions: " + String.join(", ", hiding));
        }
        Matcher identifiers = IDENTIFIER.matcher(code);
        Set<String> underscores = new TreeSet<>();
        while (identifiers.find()) {
            if (identifiers.group().contains("__")) {
                underscores.add(identifiers.group());
            }
        }
        if (!underscores.isEmpty()) {
            problems.add("names with a double underscore are reserved: " + String.join(", ", underscores));
        }
        return String.join("; ", problems);
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
