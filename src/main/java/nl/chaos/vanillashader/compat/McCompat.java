package nl.chaos.vanillashader.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.Vec3;
import nl.chaos.vanillashader.mixin.MainTargetAccessor;
import org.jspecify.annotations.Nullable;

/** Game objects that moved between Minecraft versions. */
public final class McCompat {
    private McCompat() {
    }

    /** The target the level and GUI are drawn into (the scaled one while FSR renders the level). */
    public static RenderTarget mainTarget() {
        //? if >=26.2 {
        /*return Minecraft.getInstance().gameRenderer.mainRenderTarget();
        *///?} else
        return Minecraft.getInstance().getMainRenderTarget();
    }

    public static void setMainTarget(RenderTarget target) {
        //? if >=26.2 {
        /*((MainTargetAccessor) Minecraft.getInstance().gameRenderer).vanillashader$setMainRenderTarget(target);
        *///?} else
        ((MainTargetAccessor) Minecraft.getInstance()).vanillashader$setMainRenderTarget(target);
    }

    /** The open screen (menu, inventory, chat, ...), null while playing. */
    public static @Nullable Screen screen() {
        //? if >=26.2 {
        /*return Minecraft.getInstance().gui.screen();
        *///?} else
        return Minecraft.getInstance().screen;
    }

    /** The refresh rate of the monitor the window is on, or 0 when unknown. */
    public static int refreshRate() {
        //? if >=26.3 {
        /*com.mojang.blaze3d.platform.VideoMode mode = Minecraft.getInstance().getWindow().getActiveVideoMode();
        return mode == null ? 0 : Math.round(mode.getRefreshRate());
        *///?} else
        return Minecraft.getInstance().getWindow().getRefreshRate();
    }

    public static @Nullable Camera camera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }

    public static Vec3 cameraPosition() {
        //? if >=26.1 {
        return camera().position();
        //?} else
        /*return camera().getPosition();*/
    }
}
