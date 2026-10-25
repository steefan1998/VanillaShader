plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.148" apply false
}

stonecutter active "26.1.2"

// Plain renames go here; anything structural is a //? condition in the source (mostly in GpuCompat and McCompat).
stonecutter parameters {
    replacements {
        string(current.parsed < "1.21.11") {
            replace("Identifier", "ResourceLocation")
        }
        string(current.parsed < "26.1") {
            replace("getPos().x()", "getPos().x")
            replace("getPos().z()", "getPos().z")
            replace("level.getMinY()", "level.getMinBuildHeight()")
            replace("level.getMaxY()", "(level.getMaxBuildHeight() - 1)")
            replace("ChunkPos.pack(", "ChunkPos.asLong(")
            replace("import net.minecraft.world.entity.animal.squid.GlowSquid;", "import net.minecraft.world.entity.GlowSquid;")
            replace("minecraft.getDeltaTracker()", "minecraft.getTimer()")
        }
        string(current.parsed >= "26.2" && current.parsed < "26.3") {
            replace("import com.mojang.blaze3d.textures.TextureFormat;", "import com.mojang.blaze3d.GpuFormat;")
        }
        // 26.3 moved the GPU API out of blaze3d into renderpearl.
        string(current.parsed >= "26.3") {
            replace("import com.mojang.blaze3d.textures.TextureFormat;", "import com.mojang.renderpearl.api.GpuFormat;")
            replace("com.mojang.blaze3d.GpuFormat", "com.mojang.renderpearl.api.GpuFormat")
            replace("import com.mojang.blaze3d.buffers.GpuBuffer;", "import com.mojang.renderpearl.api.buffers.GpuBuffer;")
            replace("import com.mojang.blaze3d.buffers.GpuBufferSlice;", "import com.mojang.renderpearl.api.buffers.GpuBufferSlice;")
            replace("import com.mojang.blaze3d.pipeline.BindGroupLayout;", "import com.mojang.renderpearl.api.pipeline.BindGroupLayout;")
            replace("import com.mojang.blaze3d.pipeline.RenderPipeline;", "import com.mojang.renderpearl.api.pipeline.RenderPipeline;")
            replace("import com.mojang.blaze3d.shaders.UniformType;", "import com.mojang.renderpearl.api.pipeline.UniformType;")
            replace("import com.mojang.blaze3d.systems.CommandEncoder;", "import com.mojang.renderpearl.api.commands.CommandEncoder;")
            replace("import com.mojang.blaze3d.systems.RenderPass;", "import com.mojang.renderpearl.api.commands.RenderPass;")
            replace("import com.mojang.blaze3d.systems.GpuQueryPool;", "import com.mojang.renderpearl.api.commands.GpuQueryPool;")
            replace("import com.mojang.blaze3d.systems.GpuDevice;", "import com.mojang.renderpearl.api.device.GpuDevice;")
            replace("import com.mojang.blaze3d.textures.FilterMode;", "import com.mojang.renderpearl.api.textures.FilterMode;")
            replace("import com.mojang.blaze3d.textures.GpuSampler;", "import com.mojang.renderpearl.api.textures.GpuSampler;")
            replace("import com.mojang.blaze3d.textures.GpuTexture;", "import com.mojang.renderpearl.api.textures.GpuTexture;")
            replace("import com.mojang.blaze3d.textures.GpuTextureView;", "import com.mojang.renderpearl.api.textures.GpuTextureView;")
            replace("Lcom/mojang/blaze3d/buffers/GpuBufferSlice;", "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")
            // SDL3 replaced GLFW: keys are scancodes now.
            replace("InputConstants.Type.KEYSYM", "InputConstants.Type.KEYBOARD")
        }
        string(current.parsed >= "26.2") {
            replace("TextureFormat.RGBA8", "GpuFormat.RGBA8_UNORM")
            replace("EntityType.", "EntityTypes.")
            replace("import net.minecraft.world.entity.EntityType;", "import net.minecraft.world.entity.EntityType;\nimport net.minecraft.world.entity.EntityTypes;")
            replace("gameRenderer.getMainCamera()", "gameRenderer.mainCamera()")
            replace("minecraft.setScreen(", "minecraft.setScreenAndShow(")
        }
    }
}
