package nl.chaos.vanillashader.client.gpu;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import nl.chaos.vanillashader.VanillaShader;
import org.jspecify.annotations.Nullable;
//? if >=26.2
/*import com.mojang.blaze3d.pipeline.BindGroupLayout;*/

/**
 * A full-screen pass running {@code shaders/post/<name>.fsh}. Not registered with Minecraft: it treats every
 * registered pipeline as required and drops ALL shaders (crashing the game) if one fails to compile on a driver.
 * Compiled on first use instead, where {@link #compiles()} turns a failure into one missing effect.
 */
public final class PostPipeline {
    private static final Identifier SCREEN_QUAD = Identifier.withDefaultNamespace("core/screenquad");

    private final RenderPipeline pipeline;

    private PostPipeline(RenderPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /** Samplers are named without their "Sampler" suffix. */
    public static PostPipeline create(String name, @Nullable String uniformBlock, String... samplers) {
        return new PostPipeline(builder(name, uniformBlock, samplers).build());
    }

    public static PostPipeline of(RenderPipeline pipeline) {
        return new PostPipeline(pipeline);
    }

    public static RenderPipeline.Builder builder(String name, @Nullable String uniformBlock, String... samplers) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(VanillaShader.id("pipeline/" + name))
            .withVertexShader(SCREEN_QUAD)
            .withFragmentShader(VanillaShader.id("post/" + name));
        // From 26.2 the depth buffer is reversed (cleared to 0, nearer is larger).
        //? if >=26.2
        /*builder.withShaderDefine("VANILLASHADER_REVERSED_Z");*/
        // From 26.3 a pipeline names its colour target; ours write plain RGBA8 unless a caller overrides it.
        //? if >=26.3
        /*builder.withColorTargetState(com.mojang.renderpearl.api.pipeline.ColorTargetState.DEFAULT);*/
        //? if >=26.2 {
        /*BindGroupLayout.Builder layout = BindGroupLayout.builder();
        *///?} else
        RenderPipeline.Builder layout = builder;
        if (uniformBlock != null) {
            layout.withUniform(uniformBlock, UniformType.UNIFORM_BUFFER);
        }
        for (String sampler : samplers) {
            //? if >=26.3 {
            /*layout.withUniform(sampler + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
            *///?} else
            layout.withSampler(sampler + "Sampler");
        }
        //? if >=26.2
        /*builder.withBindGroupLayout(layout.build());*/
        return builder;
    }

    /** Whether the shaders compiled on this GPU; compiles them on first use. */
    public boolean compiles() {
        //? if >=26.3 {
        /*return RenderSystem.getCompiledPipelineNullable(pipeline) != null;
        *///?} else
        return RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
    }

    public String location() {
        return pipeline.getLocation().toString();
    }

    public String path() {
        return pipeline.getLocation().getPath();
    }

    RenderPipeline pipeline() {
        return pipeline;
    }
}
