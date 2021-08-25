package net.sorenon.mcxr.play.mixin.rendering;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Matrix4f;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.openxr.XrRenderer;
import net.sorenon.mcxr.play.rendering.RenderPass;
import net.sorenon.mcxr.play.accessor.Matrix4fExt;
import org.joml.Math;
import org.lwjgl.openxr.XrFovf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import virtuoel.pehkui.util.ScaleUtils;

@Mixin(Matrix4f.class)
public abstract class Matrix4fMixin implements Matrix4fExt {

    @Unique
    private static final XrRenderer XR_RENDERER = MCXRPlayClient.RENDERER;

    @Shadow
    protected float a00;

    @Shadow
    protected float a01;

    @Shadow
    protected float a02;

    @Shadow
    protected float a03;

    @Shadow
    protected float a13;

    @Shadow
    protected float a12;

    @Shadow
    protected float a11;

    @Shadow
    protected float a10;

    @Shadow
    protected float a20;

    @Shadow
    protected float a21;

    @Shadow
    protected float a22;

    @Shadow
    protected float a23;

    @Shadow
    protected float a30;

    @Shadow
    protected float a31;

    @Shadow
    protected float a32;

    @Shadow
    protected float a33;

    @Override
    public void createProjectionFov(XrFovf fov, float nearZ, float farZ) {
        if (FabricLoader.getInstance().isModLoaded("pehkui")) {
            var client = MinecraftClient.getInstance();
            nearZ = ScaleUtils.modifyProjectionMatrixDepth(MCXRPlayClient.getCameraScale(), nearZ, client.getCameraEntity(), client.getTickDelta());
        }
        float tanLeft = Math.tan(fov.angleLeft());
        float tanRight = Math.tan(fov.angleRight());
        float tanDown = Math.tan(fov.angleDown());
        float tanUp = Math.tan(fov.angleUp());
        float tanAngleWidth = tanRight - tanLeft;
        float tanAngleHeight = tanUp - tanDown;
        a00 = 2.0f / tanAngleWidth;
        a10 = 0.0f;
        a20 = 0.0f;
        a30 = 0.0f;
        a01 = 0.0f;
        a11 = 2.0f / tanAngleHeight;
        a21 = 0.0f;
        a31 = 0.0f;
        a02 = (tanRight + tanLeft) / tanAngleWidth;
        a12 = (tanUp + tanDown) / tanAngleHeight;
        a22 = -(farZ + nearZ) / (farZ - nearZ);
        a32 = -1.0f;
        a03 = 0.0f;
        a13 = 0.0f;
        a23 = -(farZ * (nearZ + nearZ)) / (farZ - nearZ);
        a33 = 0.0f;
    }

    /**
     * why does yarn have viewboxMatrix and projectionMatrix mixed up?
     * mojmap time?
     */
    @Inject(method = "viewboxMatrix", cancellable = true, at = @At("HEAD"))
    private static void overwriteProjectionMatrix(double fov, float aspectRatio, float cameraDepth, float viewDistance, CallbackInfoReturnable<Matrix4f> cir) {
        if (XR_RENDERER.fov != null) {
            Matrix4f mat = new Matrix4f();
            mat.loadIdentity();
            ((Matrix4fExt) (Object) mat).createProjectionFov(XR_RENDERER.fov, cameraDepth, viewDistance);
            cir.setReturnValue(mat);
        }
    }
}