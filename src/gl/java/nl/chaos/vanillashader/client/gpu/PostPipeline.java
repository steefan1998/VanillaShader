package nl.chaos.vanillashader.client.gpu;

import org.jspecify.annotations.Nullable;

/** A full-screen pass running {@code shaders/post/<name>.fsh}, compiled on first use. */
public final class PostPipeline {
    private final String name;
    private final GlProgram program;

    private PostPipeline(String name, GlProgram program) {
        this.name = name;
        this.program = program;
    }

    /** Samplers are named without their "Sampler" suffix. */
    public static PostPipeline create(String name, @Nullable String uniformBlock, String... samplers) {
        return new PostPipeline(name, GlProgram.fromResource(name, uniformBlock, samplers));
    }

    /** Whether the shaders compiled on this GPU; compiles them on first use. */
    public boolean compiles() {
        return program.compiles();
    }

    public String location() {
        return "vanillashader:" + path();
    }

    public String path() {
        return "pipeline/" + name;
    }

    GlProgram program() {
        return program;
    }
}
