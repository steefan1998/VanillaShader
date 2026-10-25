package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;

/**
 * A full-screen shader program on plain OpenGL: one triangle made from the vertex index, our fragment shader (with
 * {@code #moj_import} resolved the way newer Minecraft versions do it) and at most one std140 uniform block.
 */
final class GlProgram {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern IMPORT = Pattern.compile("#moj_import <([a-z0-9_]+):([a-z0-9_/.]+)>");
    private static final int BLOCK_BINDING = 3;
    private static final String VERTEX = """
        #version 330

        out vec2 texCoord;

        void main() {
            vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            texCoord = uv;
        }
        """;
    private static int linearSampler;
    private static int nearestSampler;

    private final String name;
    private final @Nullable String builtinSource;
    private final @Nullable String uniformBlock;
    private final String[] samplers;
    private int program;
    private boolean tried;

    private GlProgram(String name, @Nullable String builtinSource, @Nullable String uniformBlock, String[] samplers) {
        this.name = name;
        this.builtinSource = builtinSource;
        this.uniformBlock = uniformBlock;
        this.samplers = samplers;
    }

    /** {@code assets/vanillashader/shaders/post/<name>.fsh}; samplers without their "Sampler" suffix. */
    static GlProgram fromResource(String name, @Nullable String uniformBlock, String... samplers) {
        return new GlProgram(name, null, uniformBlock, samplers);
    }

    /** A program whose fragment shader body (without #version) lives in the code. */
    static GlProgram builtin(String name, String fragmentBody, String... samplers) {
        return new GlProgram(name, "#version 330\n\n" + fragmentBody, null, samplers);
    }

    /** Whether the program compiled and linked on this GPU; tries once, on first use, and logs the error. */
    boolean compiles() {
        if (!tried) {
            tried = true;
            try {
                program = build();
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Vanilla Shader: could not build shader {}", name, e);
                program = 0;
            }
        }
        return program != 0;
    }

    /** Draws into the framebuffer; the caller has called {@link GlState#prepareDraw()}. */
    void draw(int framebuffer, int width, int height, @Nullable UniformBlock uniforms, Gpu.Input... inputs) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GlStateManager._viewport(0, 0, width, height);
        GlStateManager._glUseProgram(program);
        if (uniforms != null) {
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BLOCK_BINDING, uniforms.buffer());
        }
        for (Gpu.Input input : inputs) {
            int unit = unitOf(input.name());
            if (unit < 0) {
                continue;
            }
            GlState.bindTexture(unit, input.texture().id());
            GL33.glBindSampler(unit, sampler(input.linear()));
        }
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private int unitOf(String sampler) {
        for (int i = 0; i < samplers.length; i++) {
            if (samplers[i].equals(sampler)) {
                return i;
            }
        }
        return -1;
    }

    private int build() throws IOException {
        String fragment = builtinSource != null ? builtinSource : resolveImports(read("shaders/post/" + name + ".fsh"), new HashSet<>());
        int vertexShader = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fragmentShader = compile(GL20.GL_FRAGMENT_SHADER, fragment);
        if (vertexShader == 0 || fragmentShader == 0) {
            GL20.glDeleteShader(vertexShader);
            GL20.glDeleteShader(fragmentShader);
            return 0;
        }
        int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, vertexShader);
        GL20.glAttachShader(linked, fragmentShader);
        GL20.glLinkProgram(linked);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Vanilla Shader: shader {} did not link:\n{}", name, GL20.glGetProgramInfoLog(linked));
            GL20.glDeleteProgram(linked);
            return 0;
        }
        if (uniformBlock != null) {
            int index = GL31.glGetUniformBlockIndex(linked, uniformBlock);
            if (index != GL31.GL_INVALID_INDEX) {
                GL31.glUniformBlockBinding(linked, index, BLOCK_BINDING);
            }
        }
        GlStateManager._glUseProgram(linked);
        for (int i = 0; i < samplers.length; i++) {
            int location = GL20.glGetUniformLocation(linked, samplers[i] + "Sampler");
            if (location >= 0) {
                GL20.glUniform1i(location, i);
            }
        }
        GlStateManager._glUseProgram(0);
        return linked;
    }

    private int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            LOGGER.error("Vanilla Shader: shader {} did not compile:\n{}", name, GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    // Inlines our includes (each once), like Minecraft's own preprocessor.
    private static String resolveImports(String source, Set<String> seen) throws IOException {
        Matcher matcher = IMPORT.matcher(source);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String file = matcher.group(1) + ":" + matcher.group(2);
            String included = seen.add(file) ? resolveImports(read(matcher.group(1), "shaders/include/" + matcher.group(2)), seen) : "";
            matcher.appendReplacement(out, Matcher.quoteReplacement(included));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String read(String path) throws IOException {
        return read("vanillashader", path);
    }

    private static String read(String namespace, String path) throws IOException {
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(namespace, path);
        try (InputStream stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location).open()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int sampler(boolean linear) {
        if (linearSampler == 0) {
            linearSampler = createSampler(GL11.GL_LINEAR);
            nearestSampler = createSampler(GL11.GL_NEAREST);
        }
        return linear ? linearSampler : nearestSampler;
    }

    private static int createSampler(int filter) {
        int sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // Depth textures could be set up for shadow comparisons; we want their plain values.
        GL33.glSamplerParameteri(sampler, GL30.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        return sampler;
    }
}
