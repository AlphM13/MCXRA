package net.sorenon.mcxr.play.openxr;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.sorenon.mcxr.core.JOMLUtil;
import net.sorenon.mcxr.core.MCXRCore;
import net.sorenon.mcxr.core.Pose;
import net.sorenon.mcxr.core.client.MCXRCoreClient;
import net.sorenon.mcxr.play.FlatGuiManager;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.accessor.MinecraftClientExt;
import net.sorenon.mcxr.play.accessor.MouseExt;
import net.sorenon.mcxr.play.input.ControllerPosesImpl;
import net.sorenon.mcxr.play.input.actionsets.GuiActionSet;
import net.sorenon.mcxr.play.input.actionsets.VanillaGameplayActionSet;
import net.sorenon.mcxr.play.rendering.MainRenderTarget;
import net.sorenon.mcxr.play.rendering.RenderPass;
import net.sorenon.mcxr.play.rendering.XrCamera;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackMalloc;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * This class is where most of the OpenXR stuff happens
 */
public class OpenXR {

    public OpenXRInstance instance;
    public OpenXRSession session;
    int formFactor = XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    int viewConfigType = XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;

    MinecraftClient client = MinecraftClient.getInstance();
    MinecraftClientExt clientExt = ((MinecraftClientExt) MinecraftClient.getInstance());

    public static Logger LOGGER = LogManager.getLogger("MCXR");

    public static final XrPosef identityPose = XrPosef.malloc().set(
            XrQuaternionf.malloc().set(0, 0, 0, 1),
            XrVector3f.calloc()
    );

    /**
     * Creates an array of XrStructs with their types pre set to @param type
     */
    static ByteBuffer mallocAndFillBufferStack(int capacity, int sizeof, int type) {
        ByteBuffer b = stackMalloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    static ByteBuffer mallocAndFillBufferHeap(int capacity, int sizeof, int type) {
        ByteBuffer b = memAlloc(capacity * sizeof);

        for (int i = 0; i < capacity; i++) {
            b.position(i * sizeof);
            b.putInt(type);
        }
        b.rewind();
        return b;
    }

    public boolean tryInitialize() {
        try {
            if (instance == null) {
                instance = createOpenXRInstance();
            }
            session = instance.createSession(viewConfigType, instance.getSystem(formFactor));
            session.createXRReferenceSpaces();
            session.createSwapchains();
            MCXRPlayClient.INSTANCE.postRenderManagerInit();
            return true;
        } catch (XrException e) {
            if (instance != null) instance.close();
            instance = null;
            LOGGER.error(e.getMessage());
            return false;
        }
    }

    public OpenXRInstance createOpenXRInstance() throws XrException {
        try (MemoryStack stack = stackPush()) {
            IntBuffer numExtensions = stack.mallocInt(1);
            check(XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, null));

            XrExtensionProperties.Buffer properties = new XrExtensionProperties.Buffer(
                    mallocAndFillBufferStack(numExtensions.get(0), XrExtensionProperties.SIZEOF, XR10.XR_TYPE_EXTENSION_PROPERTIES)
            );

            check(XR10.xrEnumerateInstanceExtensionProperties((ByteBuffer) null, numExtensions, properties));

//            LOGGER.info(String.format("OpenXR loaded with %d extensions", numExtensions.get(0)));
//            LOGGER.info("~~~~~~~~~~~~~~~~~~");
            PointerBuffer extensions = stack.mallocPointer(numExtensions.get(0));
            boolean missingOpenGL = true;
            while (properties.hasRemaining()) {
                XrExtensionProperties prop = properties.get();
                String extensionName = prop.extensionNameString();
//                LOGGER.info(extensionName);
                extensions.put(memASCII(extensionName));
                if (extensionName.equals(KHROpenglEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME)) {
                    missingOpenGL = false;
                }
            }
            extensions.rewind();
//            LOGGER.info("~~~~~~~~~~~~~~~~~~");

            if (missingOpenGL) {
                throw new XrException("OpenXR library does not provide required extension: " + KHROpenglEnable.XR_KHR_OPENGL_ENABLE_EXTENSION_NAME);
            }

            XrApplicationInfo applicationInfo = XrApplicationInfo.mallocStack();
            applicationInfo.apiVersion(XR10.XR_CURRENT_API_VERSION);
            applicationInfo.applicationName(stack.UTF8("[MCXR] Minecraft VR"));

            XrInstanceCreateInfo createInfo = XrInstanceCreateInfo.mallocStack();
            createInfo.set(
                    XR10.XR_TYPE_INSTANCE_CREATE_INFO,
                    0,
                    0,
                    applicationInfo,
                    null,
                    extensions
            );

            PointerBuffer instancePtr = stack.mallocPointer(1);

            int xrResult = XR10.xrCreateInstance(createInfo, instancePtr);
            if (xrResult == XR10.XR_ERROR_RUNTIME_FAILURE) {
                throw new XrException("Failed to create xrInstance, are you sure your headset is plugged in?");
            } else if (xrResult == XR10.XR_ERROR_INSTANCE_LOST) {
                throw new XrException("Failed to create xrInstance due to runtime updating");
            } else if (xrResult < 0) {
                throw new XrException("XR method returned " + xrResult);
            }

            return new OpenXRInstance(new XrInstance(instancePtr.get(0), createInfo));
        }
    }

    public boolean pollEvents() {
        XrEventDataBaseHeader event = instance.nextEvent();
        while (event != null) {
            switch (event.type()) {
                case XR10.XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING: {
                    XrEventDataInstanceLossPending instanceLossPending = XrEventDataInstanceLossPending.create(event.address());
                    LOGGER.warn("XrEventDataInstanceLossPending by " + instanceLossPending.lossTime());

                    instance.close();
                    instance = null;
                    return true;
                }
                case XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED: {
                    XrEventDataSessionStateChanged sessionStateChangedEvent = XrEventDataSessionStateChanged.create(event.address());
                    return session.handleSessionStateChangedEvent(sessionStateChangedEvent/*, requestRestart*/);
                }
                case XR10.XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED:
                    break;
                case XR10.XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING:
                default: {
                    LOGGER.debug(String.format("Ignoring event type %d", event.type()));
                    break;
                }
            }
            event = instance.nextEvent();
        }

        return false;
    }

    public void renderFrame() {
        try (MemoryStack stack = stackPush()) {
            XrFrameWaitInfo frameWaitInfo = XrFrameWaitInfo.callocStack();
            frameWaitInfo.type(XR10.XR_TYPE_FRAME_WAIT_INFO);
            XrFrameState frameState = XrFrameState.callocStack();
            frameState.type(XR10.XR_TYPE_FRAME_STATE);
            check(XR10.xrWaitFrame(session.handle, frameWaitInfo, frameState));

            XrFrameBeginInfo frameBeginInfo = XrFrameBeginInfo.callocStack();
            frameBeginInfo.type(XR10.XR_TYPE_FRAME_BEGIN_INFO);
            check(XR10.xrBeginFrame(session.handle, frameBeginInfo));

            XrCompositionLayerProjection layerProjection = XrCompositionLayerProjection.callocStack();
            layerProjection.type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION);
            PointerBuffer layers = stack.callocPointer(1);

            if (frameState.shouldRender()) {
                if (MCXRPlayClient.isXrMode()) {
                    if (renderLayerOpenXR(frameState.predictedDisplayTime(), layerProjection)) {
                        layers.put(layerProjection.address());
                    }
                } else {
                    if (renderLayerBlankOpenXR(frameState.predictedDisplayTime(), layerProjection)) {
                        layers.put(layerProjection.address());
                    }
                }
            }
            layers.flip();

            XrFrameEndInfo frameEndInfo = XrFrameEndInfo.callocStack()
                    .type(XR10.XR_TYPE_FRAME_END_INFO)
                    .displayTime(frameState.predictedDisplayTime())
                    .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                    .layers(layers);
            check(XR10.xrEndFrame(session.handle, frameEndInfo));

            if (layers.limit() > 0) {
                layerProjection.views().free(); //These values were allocated in a child function so they must be freed manually as we could not use the stack
            }
        }
    }

    private boolean renderLayerOpenXR(long predictedDisplayTime, XrCompositionLayerProjection layer) {
        try (MemoryStack stack = stackPush()) {
            XrCompositionLayerProjectionView.Buffer projectionLayerViews;

            XrViewState viewState = new XrViewState(stack.calloc(XrViewState.SIZEOF));
            viewState.type(XR10.XR_TYPE_VIEW_STATE);
            IntBuffer intBuf = stack.mallocInt(1);

            XrViewLocateInfo viewLocateInfo = new XrViewLocateInfo(stack.malloc(XrViewLocateInfo.SIZEOF));
            viewLocateInfo.set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                    0,
                    viewConfigType,
                    predictedDisplayTime,
                    session.xrAppSpace
            );

            check(XR10.xrLocateViews(session.handle, viewLocateInfo, viewState, intBuf, session.views));

            if ((viewState.viewStateFlags() & XR10.XR_VIEW_STATE_POSITION_VALID_BIT) == 0 ||
                    (viewState.viewStateFlags() & XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
                return false;  // There is no valid tracking poses for the views.
            }
            int viewCountOutput = intBuf.get(0);
//            assert (viewCountOutput == views.capacity());
//            assert (viewCountOutput == swapchains.length);
//            assert (viewCountOutput == 2);

//            projectionLayerViews = new XrCompositionLayerProjectionView.Buffer(mallocAndFillBufferHeap(viewCountOutput, XrCompositionLayerProjectionView.SIZEOF, XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW));
            projectionLayerViews = XrCompositionLayerProjectionView.calloc(viewCountOutput);

            // Update hand position based on the predicted time of when the frame will be rendered! This
            // should result in a more accurate location, and reduce perceived lag.
            if (session.state == XR10.XR_SESSION_STATE_FOCUSED) {
                for (int i = 0; i < 2; i++) {
                    if (!MCXRPlayClient.handsActionSet.grip.isActive[i]) {
                        continue;
                    }
                    setPosesFromSpace(MCXRPlayClient.handsActionSet.grip.spaces[i], predictedDisplayTime, MCXRPlayClient.handsActionSet.gripPoses[i]);
                    setPosesFromSpace(MCXRPlayClient.handsActionSet.aim.spaces[i], predictedDisplayTime, MCXRPlayClient.handsActionSet.aimPoses[i]);
                }
            }

            setPosesFromSpace(session.xrViewSpace, predictedDisplayTime, MCXRPlayClient.viewSpacePoses);

            XrCamera camera = (XrCamera) MinecraftClient.getInstance().gameRenderer.getCamera();
            camera.updateXR(this.client.world, this.client.getCameraEntity() == null ? this.client.player : this.client.getCameraEntity(), MCXRPlayClient.viewSpacePoses.getGamePose());
            MainRenderTarget mainRenderTarget = (MainRenderTarget) client.getFramebuffer();

            long frameStartTime = Util.getMeasuringTimeNano();
//            MCXRCore.pose.set(MineXRaftClient.viewSpacePoses.getPhysicalPose());
            if (MinecraftClient.getInstance().player != null && MCXRCoreClient.INSTANCE.fullFunc) {
                MCXRCore.INSTANCE.playerPose(
                        MinecraftClient.getInstance().player,
                        MCXRPlayClient.viewSpacePoses.getPhysicalPose());
            }
            clientExt.preRenderXR(true, () -> {
                if (camera.getFocusedEntity() != null) {
                    float tickDelta = client.getTickDelta();
                    Entity camEntity = camera.getFocusedEntity();
                    MCXRPlayClient.xrOrigin.set(MathHelper.lerp(tickDelta, camEntity.prevX, camEntity.getX()) + MCXRPlayClient.xrOffset.x,
                            MathHelper.lerp(tickDelta, camEntity.prevY, camEntity.getY()) + MCXRPlayClient.xrOffset.y,
                            MathHelper.lerp(tickDelta, camEntity.prevZ, camEntity.getZ()) + MCXRPlayClient.xrOffset.z);

                    float scale = MCXRPlayClient.getScale();
                    MCXRPlayClient.viewSpacePoses.updateGamePose(MCXRPlayClient.xrOrigin, scale);
                    for (var poses : MCXRPlayClient.handsActionSet.gripPoses) {
                        poses.updateGamePose(MCXRPlayClient.xrOrigin, scale);
                    }
                    for (var poses : MCXRPlayClient.handsActionSet.aimPoses) {
                        poses.updateGamePose(MCXRPlayClient.xrOrigin, scale);
                    }
                }
            });

            {
                FlatGuiManager FGM = MCXRPlayClient.INSTANCE.flatGuiManager;
                MCXRPlayClient.renderPass = RenderPass.GUI;
                mainRenderTarget.setFramebuffer(FGM.framebuffer);
                MouseExt mouse = ((MouseExt) MinecraftClient.getInstance().mouse);
                if (FGM.isScreenOpen()) {
                    Pose pose = MCXRPlayClient.handsActionSet.gripPoses[MCXRPlayClient.mainHand].getPhysicalPose();
                    Vector3d pos = new Vector3d(pose.getPos());
                    Vector3f dir = pose.getOrientation().rotateX((float) Math.toRadians(MCXRPlayClient.handPitchAdjust), new Quaternionf()).transform(new Vector3f(0, -1, 0));
                    Vector3d result = FGM.guiRaycast(pos, new Vector3d(dir));
                    if (result != null) {
                        Vector3d vec = result.sub(JOMLUtil.convert(FGM.pos));
                        FGM.rot.invert(new Quaterniond()).transform(vec);
                        vec.y *= ((double) FGM.framebufferWidth / FGM.framebufferHeight);

                        ((MouseExt) MinecraftClient.getInstance().mouse).cursorPos(
                                FGM.framebufferWidth * (0.5 - vec.x),
                                FGM.framebufferHeight * (1 - vec.y)
                        );
                    }
                    GuiActionSet actionSet = MCXRPlayClient.guiActionSet;
                    if (actionSet.pickup.changedSinceLastSync || actionSet.quickMove.changedSinceLastSync) {
                        if (actionSet.pickup.currentState || actionSet.quickMove.currentState) {
                            mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
                        } else {
                            mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
                        }
                    }
                    if (actionSet.split.changedSinceLastSync) {
                        if (actionSet.split.currentState) {
                            mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS, 0);
                        } else {
                            mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE, 0);
                        }
                    }
                    if (actionSet.scroll.changedSinceLastSync) {
                        var state = actionSet.scroll.currentState;
                        double sensitivity = 0.25;
                        mouse.mouseScroll(-state.x() * sensitivity, state.y() * sensitivity);
                    }
                } else {
                    VanillaGameplayActionSet actionSet = MCXRPlayClient.vanillaGameplayActionSet;
                    if (actionSet.attack.changedSinceLastSync) {
                        if (actionSet.attack.currentState) {
                            mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
                        } else {
                            mouse.mouseButton(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
                        }
                    }
                    if (actionSet.inventory.currentState) {
                        long heldTime = predictedDisplayTime - actionSet.inventory.lastChangeTime;
                        if (heldTime * 1E-09 > 1) {
                            client.openPauseMenu(false);
                            MCXRPlayClient.XR_INPUT.menuButton = false;
                        }
                    }
                }

                FGM.framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
                clientExt.doRenderXR(true, frameStartTime);
                mainRenderTarget.resetFramebuffer();
                MCXRPlayClient.renderPass = RenderPass.VANILLA;
            }
            // Render view to the appropriate part of the swapchain image.
            for (int viewIndex = 0; viewIndex < viewCountOutput; viewIndex++) {
                // Each view has a separate swapchain which is acquired, rendered to, and released.
                OpenXRSwapchain viewSwapchain = session.swapchains[viewIndex];

                XrSwapchainImageAcquireInfo acquireInfo = new XrSwapchainImageAcquireInfo(stack.calloc(XrSwapchainImageAcquireInfo.SIZEOF));
                acquireInfo.type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO);

                check(XR10.xrAcquireSwapchainImage(viewSwapchain.handle, acquireInfo, intBuf));
                int swapchainImageIndex = intBuf.get(0);

                XrSwapchainImageWaitInfo waitInfo = new XrSwapchainImageWaitInfo(stack.malloc(XrSwapchainImageWaitInfo.SIZEOF));
                waitInfo.set(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO, 0, XR10.XR_INFINITE_DURATION);

                check(XR10.xrWaitSwapchainImage(viewSwapchain.handle, waitInfo));

                XrCompositionLayerProjectionView projectionLayerView = projectionLayerViews.get(viewIndex);
                projectionLayerView.type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW);
                projectionLayerView.pose(session.views.get(viewIndex).pose());
                projectionLayerView.fov(session.views.get(viewIndex).fov());
                projectionLayerView.subImage().swapchain(viewSwapchain.handle);
                projectionLayerView.subImage().imageRect().offset().set(0, 0);
                projectionLayerView.subImage().imageRect().extent().set(viewSwapchain.width, viewSwapchain.height);

                {
                    XrSwapchainImageOpenGLKHR xrSwapchainImageOpenGLKHR = viewSwapchain.images.get(swapchainImageIndex);
                    viewSwapchain.framebuffer.setColorAttachment(xrSwapchainImageOpenGLKHR.image());
                    viewSwapchain.framebuffer.endWrite();
                    mainRenderTarget.setXrFramebuffer(viewSwapchain.framebuffer);
                    MCXRPlayClient.fov = session.views.get(viewIndex).fov();
                    MCXRPlayClient.eyePoses.updatePhysicalPose(session.views.get(viewIndex).pose(), MCXRPlayClient.yawTurn);
                    MCXRPlayClient.eyePoses.updateGamePose(MCXRPlayClient.xrOrigin, MCXRPlayClient.getScale());
                    MCXRPlayClient.viewIndex = viewIndex;
                    camera.setPose(MCXRPlayClient.eyePoses.getGamePose());
                    MCXRPlayClient.renderPass = RenderPass.WORLD;
                    clientExt.doRenderXR(true, frameStartTime);
                    MCXRPlayClient.renderPass = RenderPass.VANILLA;
                    MCXRPlayClient.fov = null;
                }

                XrSwapchainImageReleaseInfo releaseInfo = new XrSwapchainImageReleaseInfo(stack.calloc(XrSwapchainImageReleaseInfo.SIZEOF));
                releaseInfo.type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO);
                check(XR10.xrReleaseSwapchainImage(viewSwapchain.handle, releaseInfo));
            }
            mainRenderTarget.resetFramebuffer();
            camera.setPose(MCXRPlayClient.viewSpacePoses.getGamePose());
            clientExt.postRenderXR(true);

            layer.space(session.xrAppSpace);
            layer.views(projectionLayerViews);
            return true;
        }
    }

    private boolean renderLayerBlankOpenXR(long predictedDisplayTime, XrCompositionLayerProjection layer) {
        try (MemoryStack stack = stackPush()) {
            XrCompositionLayerProjectionView.Buffer projectionLayerViews;

            XrViewState viewState = new XrViewState(stack.calloc(XrViewState.SIZEOF));
            viewState.type(XR10.XR_TYPE_VIEW_STATE);
            IntBuffer intBuf = stack.mallocInt(1);

            XrViewLocateInfo viewLocateInfo = new XrViewLocateInfo(stack.malloc(XrViewLocateInfo.SIZEOF));
            viewLocateInfo.set(XR10.XR_TYPE_VIEW_LOCATE_INFO,
                    0,
                    viewConfigType,
                    predictedDisplayTime,
                    session.xrAppSpace
            );

            check(XR10.xrLocateViews(session.handle, viewLocateInfo, viewState, intBuf, session.views));

            if ((viewState.viewStateFlags() & XR10.XR_VIEW_STATE_POSITION_VALID_BIT) == 0 ||
                    (viewState.viewStateFlags() & XR10.XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0) {
                return false;  // There is no valid tracking poses for the views.
            }
            int viewCountOutput = intBuf.get(0);
//            assert (viewCountOutput == views.capacity());
//            assert (viewCountOutput == swapchains.length);

//            projectionLayerViews = new XrCompositionLayerProjectionView.Buffer(mallocAndFillBufferHeap(viewCountOutput, XrCompositionLayerProjectionView.SIZEOF, XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW));
            projectionLayerViews = XrCompositionLayerProjectionView.calloc(viewCountOutput);

            //For some reason this has to be called between frame start and frame end or the OpenXR runtime crashes
            clientExt.render();

            // Render view to the appropriate part of the swapchain image.
            for (int viewIndex = 0; viewIndex < viewCountOutput; viewIndex++) {
                // Each view has a separate swapchain which is acquired, rendered to, and released.
                OpenXRSwapchain viewSwapchain = session.swapchains[viewIndex];

                XrSwapchainImageAcquireInfo acquireInfo = new XrSwapchainImageAcquireInfo(stack.calloc(XrSwapchainImageAcquireInfo.SIZEOF));
                acquireInfo.type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO);

                check(XR10.xrAcquireSwapchainImage(viewSwapchain.handle, acquireInfo, intBuf));
                int swapchainImageIndex = intBuf.get(0);

                XrSwapchainImageWaitInfo waitInfo = new XrSwapchainImageWaitInfo(stack.malloc(XrSwapchainImageWaitInfo.SIZEOF));
                waitInfo.set(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO, 0, XR10.XR_INFINITE_DURATION);

                check(XR10.xrWaitSwapchainImage(viewSwapchain.handle, waitInfo));

                XrCompositionLayerProjectionView projectionLayerView = projectionLayerViews.get(viewIndex);
                projectionLayerView.type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW);
                projectionLayerView.pose(session.views.get(viewIndex).pose());
                projectionLayerView.fov(session.views.get(viewIndex).fov());
                projectionLayerView.subImage().swapchain(viewSwapchain.handle);
                projectionLayerView.subImage().imageRect().offset().set(0, 0);
                projectionLayerView.subImage().imageRect().extent().set(viewSwapchain.width, viewSwapchain.height);

                XrSwapchainImageOpenGLKHR xrSwapchainImageOpenGLKHR = viewSwapchain.images.get(swapchainImageIndex);
                viewSwapchain.framebuffer.setColorAttachment(xrSwapchainImageOpenGLKHR.image());
                viewSwapchain.framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);

                XrSwapchainImageReleaseInfo releaseInfo = new XrSwapchainImageReleaseInfo(stack.calloc(XrSwapchainImageReleaseInfo.SIZEOF));
                releaseInfo.type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO);
                check(XR10.xrReleaseSwapchainImage(viewSwapchain.handle, releaseInfo));
            }

            layer.space(session.xrAppSpace);
            layer.views(projectionLayerViews);
            return true;
        }
    }

    public void check(int result) throws XrRuntimeException {
        if (result >= 0) return;

        if (instance != null) {
            ByteBuffer str = stackMalloc(XR10.XR_MAX_RESULT_STRING_SIZE);
            if (XR10.xrResultToString(instance.handle, result, str) >= 0) {
                throw new XrRuntimeException(memUTF8Safe(str));
            }
        }
        throw new XrRuntimeException("XR method returned " + result);
    }

    public void setPosesFromSpace(XrSpace handSpace, long time, ControllerPosesImpl result) {
        try (MemoryStack ignored = stackPush()) {
            XrSpaceLocation space_location = XrSpaceLocation.callocStack().type(XR10.XR_TYPE_SPACE_LOCATION);
            int res = XR10.xrLocateSpace(handSpace, session.xrAppSpace, time, space_location);
            if (res == XR10.XR_SUCCESS &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0 &&
                    (space_location.locationFlags() & XR10.XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {

                result.updatePhysicalPose(space_location.pose(), MCXRPlayClient.yawTurn);
            }
        }
    }
}