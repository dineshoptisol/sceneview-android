package io.github.sceneview.ar

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.lifecycle.*
import com.google.ar.core.*
import com.google.ar.core.CameraConfig.FacingDirection
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.sceneform.ArCamera
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.rendering.*
import com.gorisse.thomas.sceneview.Instructions
import io.github.sceneview.SceneLifecycle
import io.github.sceneview.SceneLifecycleObserver
import io.github.sceneview.SceneLifecycleOwner
import io.github.sceneview.SceneView
import io.github.sceneview.ar.arcore.*
import io.github.sceneview.ar.scene.PlaneRenderer
import io.github.sceneview.light.defaultMainLightIntensity
import io.github.sceneview.light.destroy
import io.github.sceneview.light.intensity
import io.github.sceneview.light.sunnyDayMainLightIntensity
import io.github.sceneview.node.Node
import io.github.sceneview.scene.exposureFactor
import io.github.sceneview.utils.setKeepScreenOn

/**
 * A SurfaceView that integrates with ARCore and renders a scene.
 */
open class ArSceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : SceneView(
    context,
    attrs,
    defStyleAttr,
    defStyleRes
), ArSceneLifecycleOwner, ArSceneLifecycleObserver {

    companion object {
        val defaultFocusMode = Config.FocusMode.AUTO
        val defaultPlaneFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        val defaultDepthEnabled = true
        val defaultInstantPlacementEnabled = true
        val defaultLightEstimationMode = LightEstimationMode.REALISTIC
    }

    /**
     * ### Sets the desired focus mode
     *
     * See [Config.FocusMode] for available options.
     */
    var focusMode: Config.FocusMode = defaultFocusMode
        set(value) {
            field = value
            session?.focusMode = value
        }

    /**
     * ### Sets the desired plane finding mode
     *
     * See the [Config.PlaneFindingMode] enum
     * for available options.
     */
    var planeFindingMode: Config.PlaneFindingMode = defaultPlaneFindingMode
        set(value) {
            field = value
            session?.planeFindingMode = value
        }

    /**
     * ### Enable or disable the [Config.DepthMode.AUTOMATIC]
     *
     * Not all devices support all modes. Use [Session.isDepthModeSupported] to determine whether the
     * current device and the selected camera support a particular depth mode.
     */
    var depthEnabled: Boolean = defaultDepthEnabled
        set(value) {
            field = value
            session?.depthEnabled = value
        }

    /**
     * ### Enable or disable the [Config.InstantPlacementMode.LOCAL_Y_UP]
     *
     * // TODO : Doc
     */
    var instantPlacementEnabled: Boolean = defaultInstantPlacementEnabled
        set(value) {
            field = value
            session?.instantPlacementEnabled = value
        }

    /**
     * ### ARCore light estimation configuration
     *
     * ARCore estimate lighting to provide directional light, ambient spherical harmonics,
     * and reflection cubemap estimation
     *
     * Light bounces off of surfaces differently depending on whether the surface has specular
     * (highly reflective) or diffuse (not reflective) properties.
     * For example, a metallic ball will be highly specular and reflect its environment, while
     * another ball painted a dull matte gray will be diffuse. Most real-world objects have a
     * combination of these properties — think of a scuffed-up bowling ball or a well-used credit
     * card.
     *
     * Reflective surfaces also pick up colors from the ambient environment. The coloring of an
     * object can be directly affected by the coloring of its environment. For example, a white ball
     * in a blue room will take on a bluish hue.
     *
     * The main directional light API calculates the direction and intensity of the scene's
     * main light source. This information allows virtual objects in your scene to show reasonably
     * positioned specular highlights, and to cast shadows in a direction consistent with other
     * visible real objects.
     *
     * LightEstimationConfig.SPECTACULAR vs LightEstimationConfig.REALISTIC mostly differs on the
     * reflections parts and you will mainly only see differences if your model has more metallic
     * than roughness material values.
     *
     * Adjust the based reference/factored lighting intensities and other values with:
     * - [io.github.sceneview.ar.ArSceneView.mainLight]
     * - [io.github.sceneview.ar.ArSceneView.environment][io.github.sceneview.environment.Environment.indirectLight]
     *
     * @see LightEstimationMode.REALISTIC
     * @see LightEstimationMode.SPECTACULAR
     * @see LightEstimationMode.AMBIENT_INTENSITY
     * @see LightEstimationMode.DISABLED
     */
    var lightEstimationMode: LightEstimationMode = defaultLightEstimationMode
        set(value) {
            field = value
            session?.lightEstimationMode = value
        }

    override val sceneLifecycle: ArSceneLifecycle = ArSceneLifecycle(context, this)

    private val cameraTextureId = GLHelper.createCameraTexture()

    //TODO : Move it to Lifecycle and NodeParent when Kotlined
    override val camera by lazy { ArCamera(this) }
    override val arCore = ARCore(cameraTextureId, lifecycle)

    override val renderer: Renderer by lazy {
        ArRenderer(this, camera).apply {
            enablePerformanceMode()
        }
    }

    /**
     * ### The [CameraStream] used to control if the occlusion should be enabled or disabled
     */
    val cameraStream = CameraStream(cameraTextureId, renderer)

    /**
     * ### [PlaneRenderer] used to control plane visualization.
     */
    val planeRenderer = PlaneRenderer(lifecycle)

    /**
     * ### The environment and main light that are estimated by AR Core to render the scene.
     *
     * - Environment handles a reflections, indirect lighting and skybox.
     * - ARCore will estimate the direction, the intensity and the color of the light
     */
    var estimatedLights: EnvironmentLightsEstimate? = null
        internal set(value) {
            //TODO: Move to Renderer when kotlined it
            val environment = value?.environment ?: environment
            if (renderer.getEnvironment() != environment) {
                if (field?.environment != environment) {
                    field?.environment?.destroy()
                }
                renderer.setEnvironment(environment)
            }
            val mainLight = value?.mainLight ?: mainLight
            if (renderer.getMainLight() != mainLight) {
                if (field?.mainLight != mainLight) {
                    field?.mainLight?.destroy()
                }
                renderer.setMainLight(mainLight)
            }
            field = value
        }

    private var isProcessingFrame = false

    val instructions = Instructions(this, lifecycle)

    override fun onArSessionCreated(session: ArSession) {
        super.onArSessionCreated(session)

        session.focusMode = focusMode
        session.planeFindingMode = planeFindingMode
        session.depthEnabled = depthEnabled
        session.instantPlacementEnabled = instantPlacementEnabled
        session.lightEstimationMode = lightEstimationMode

        // Feature config, therefore facing direction, can only be configured once per session.
        if (session.cameraConfig.facingDirection == FacingDirection.FRONT) {
            renderer.isFrontFaceWindingInverted = true
        }

        // Set max frames per seconds here.
        maxFramesPerSeconds = session.cameraConfig.fpsRange.upper
    }

    override fun onArSessionResumed(session: ArSession) {
        super.onArSessionResumed(session)

        // Don't remove this code-block. It is important to correctly set the DisplayGeometry for
        // the ArCore-Session if for example the permission Dialog is shown on the screen.
        // If we remove this part, the camera is flickering if returned from the permission Dialog.
        if (renderer.desiredWidth != 0 && renderer.desiredHeight != 0) {
            session.setDisplayGeometry(
                display!!.rotation,
                renderer.desiredWidth,
                renderer.desiredHeight
            )
        }
    }

    override fun onArSessionConfigChanged(session: ArSession, config: Config) {
        // Set the correct Texture configuration on the camera stream
        //TODO: Move CameraStream to lifecycle aware
        cameraStream.checkIfDepthIsEnabled(session, config)

        mainLight?.intensity = when (config.lightEstimationMode) {
            Config.LightEstimationMode.DISABLED -> defaultMainLightIntensity
            else -> sunnyDayMainLightIntensity
        }
    }

    /**
     * Before the render call occurs, update the ARCore session to grab the latest frame and update
     * listeners.
     *
     * The super.onFrame() is called if the session updated successfully and a new frame was
     * obtained. Update the scene before rendering.
     */
    override fun doFrame(frameTime: FrameTime) {
        // TODO : Move to dedicated Lifecycle aware classes when Kotlined them
        val session = session?.takeIf { !isProcessingFrame } ?: return

        // No new frame, no drawing
        session.updateFrame(frameTime)?.let { frame ->
            isProcessingFrame = true
            doArFrame(frame)
            super.doFrame(frameTime)
            isProcessingFrame = false
        }
    }

    /**
     * ### Invoked once per [Frame] immediately before the Scene is updated.
     *
     * The listener will be called in the order in which they were added.
     */
    protected open fun doArFrame(arFrame: ArFrame) {
        // TODO : Move to dedicated Lifecycle aware classes when Kotlined them
        val (session, _, frame, arCamera) = arFrame
        if (arCamera.isTracking != session.previousFrame?.camera?.isTracking) {
            // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
            // You will say thanks when still have battery after a long day debugging an AR app.
            // ...and it's better for your users
            activity.setKeepScreenOn(arCamera.isTracking)
        }

        cameraStream.apply {
            // Setup Camera Stream if needed.
            if (!isTextureInitialized) {
                initializeTexture(arCamera)
            }
            // Recalculate camera Uvs if necessary.
            if (frame.hasDisplayGeometryChanged()) {
                recalculateCameraUvs(frame)
            }
            if (depthOcclusionMode == CameraStream.DepthOcclusionMode.DEPTH_OCCLUSION_ENABLED) {
                try {
                    when (depthMode) {
                        CameraStream.DepthMode.DEPTH -> frame.acquireDepthImage()
                        CameraStream.DepthMode.RAW_DEPTH -> frame.acquireRawDepthImage()
                        else -> null
                    }?.use { depthImage ->
                        recalculateOcclusion(depthImage)
                    }
                } catch (ignored: NotYetAvailableException) {
                } catch (ignored: DeadlineExceededException) {
                }
            }
        }

        // At the start of the frame, update the tracked pose of the camera
        // to use in any calculations during the frame.
        this.camera.updateTrackedPose(arCamera)

        // Update the light estimate.
        if (session.config.lightEstimationMode != Config.LightEstimationMode.DISABLED) {
            estimatedLights = arFrame.environmentLightsEstimate(
                session.lightEstimationMode,
                estimatedLights,
                environment,
                mainLight,
                renderer.camera.exposureFactor
            )
        }

        if (onAugmentedImageUpdate != null) {
            arFrame.updatedAugmentedImages.forEach(onAugmentedImageUpdate)
        }

        if (onAugmentedFaceUpdate != null) {
            arFrame.updatedAugmentedFaces.forEach(onAugmentedFaceUpdate)
        }

        onArFrame.forEach { it(arFrame) }
        lifecycle.dispatchEvent<ArSceneLifecycleObserver> {
            onArFrame(arFrame)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        estimatedLights?.destroy()
        estimatedLights = null
    }

    override fun getLifecycle(): ArSceneLifecycle = sceneLifecycle

    /**
     * ### Define the session config used by ARCore
     *
     * Prefer calling this method before the global (Activity or Fragment) onResume() cause the session
     * base configuration in made there.
     * Any later calls (after onSessionResumed()) to this function are not completely sure be taken in
     * account by ARCore (even if most of them will work)
     *
     * Please check that all your Session Config parameters are taken in account by ARCore at
     * runtime.
     *
     * @param applyConfig the apply block for the new config
     */
    fun configureSession(applyConfig: (Config) -> Unit) {
        lifecycle.doOnArSessionCreated { session ->
            session.configure(applyConfig)
        }
    }

    override fun onTouch(selectedNode: Node?, motionEvent: MotionEvent): Boolean {
        if (!super.onTouch(selectedNode, motionEvent) &&
            selectedNode == null
        ) {
            // TODO : Should be handled by the nodesTouchEventDispatcher
            nodeGestureRecognizer.selectNode(null)
            session?.let { session ->
                session.currentFrame?.hitTest(motionEvent)?.let { hitResult ->
                    onTouchAr(hitResult, motionEvent)
                    return true
                }
            }
        }
        return false
    }

    /**
     * ### Invoked when an ARCore error occurred
     *
     * Registers a callback to be invoked when the ARCore Session cannot be initialized because
     * ARCore is not available on the device or the camera permission has been denied.
     */
    var onARCoreException: ((exception: Exception) -> Unit)?
        get() = arCore.onException
        set(value) {
            arCore.onException = value
        }

    /**
     * ### Invoked when an ARCore [Trackable] is tapped
     *
     * The callback will only be invoked if **no** [com.google.ar.sceneform.Node] was tapped.
     *
     * - hitResult: The ARCore hit result that occurred when tapping the plane
     * - plane: The ARCore Plane that was tapped
     * - motionEvent: the motion event that triggered the tap
     */
    protected open fun onTouchAr(hitResult: HitResult, motionEvent: MotionEvent) {
        onTouchAr?.invoke(hitResult, motionEvent)
    }

    /**
     * ### Invoked when an ARCore frame is processed
     *
     * Registers a callback to be invoked when a valid ARCore Frame is processing.
     *
     * The callback to be invoked once per frame **immediately before the scene
     * is updated**.
     *
     * The callback will only be invoked if the Frame is considered as valid.
     */
    val onArFrame = mutableListOf<(arFrame: ArFrame) -> Unit>()

    /**
     * ### Invoked when an ARCore plane is tapped
     *
     * Registers a callback to be invoked when an ARCore [Trackable] is tapped.
     * Depending on the session config you defined, the [HitResult.getTrackable] can be:
     * - a [Plane] if [ArSession.planeFindingEnabled]
     * - an [InstantPlacementPoint] if [ArSession.instantPlacementEnabled]
     * - a [DepthPoint] if [ArSession.depthEnabled]
     *
     * The callback will only be invoked if no [com.google.ar.sceneform.Node] was tapped.
     *
     * - hitResult: The ARCore hit result that occurred when tapping the plane
     * - motionEvent: the motion event that triggered the tap
     */
    var onTouchAr: ((hitResult: HitResult, motionEvent: MotionEvent) -> Unit)? =
        null

    /**
     * ### Invoked when an ARCore AugmentedImage TrackingState/TrackingMethod is updated
     *
     * Registers a callback to be invoked when an ARCore AugmentedImage TrackingState/TrackingMethod
     * is updated. The callback will be invoked on each AugmentedImage update.
     *
     * @see AugmentedImage.getTrackingState
     * @see AugmentedImage.getTrackingMethod
     */
    var onAugmentedImageUpdate: ((augmentedImage: AugmentedImage) -> Unit)? = null

    /**
     * ### Invoked when an ARCore AugmentedFace TrackingState is updated
     *
     * Registers a callback to be invoked when an ARCore AugmentedFace TrackingState is updated. The
     * callback will be invoked on each AugmentedFace update.
     *
     * @see AugmentedFace.getTrackingState
     */
    var onAugmentedFaceUpdate: ((augmentedFace: AugmentedFace) -> Unit)? = null
}

/**
 * ### A SurfaceView that integrates with ARCore and renders a scene.
 */
interface ArSceneLifecycleOwner : SceneLifecycleOwner {
    val arCore: ARCore
    val session get() = arCore.session
    val sessionConfig get() = session?.config
}

class ArSceneLifecycle(context: Context, override val owner: ArSceneLifecycleOwner) :
    SceneLifecycle(context, owner) {
    val arCore get() = owner.arCore
    val session get() = owner.session
    val sessionConfig get() = owner.sessionConfig

    /**
     * ### Performs the given action when ARCore session is created
     *
     * If the ARCore session is already created the action will be performed immediately, otherwise
     * the action will be performed after the ARCore session is next created.
     * The action will only be invoked once, and any listeners will then be removed.
     */
    fun doOnArSessionCreated(action: (session: ArSession) -> Unit) {
        session?.let(action) ?: addObserver(onArSessionCreated = {
            removeObserver(this)
            action(it)
        })
    }

    fun addObserver(
        onArSessionCreated: (ArSceneLifecycleObserver.(session: ArSession) -> Unit)? = null,
        onArSessionResumed: (ArSceneLifecycleObserver.(session: ArSession) -> Unit)? = null,
        onArSessionConfigChanged: (ArSceneLifecycleObserver.(session: ArSession, config: Config) -> Unit)? = null,
        onArFrameUpdated: (ArSceneLifecycleObserver.(arFrame: ArFrame) -> Unit)? = null
    ) {
        addObserver(object : ArSceneLifecycleObserver {
            override fun onArSessionCreated(session: ArSession) {
                onArSessionCreated?.invoke(this, session)
            }

            override fun onArSessionResumed(session: ArSession) {
                onArSessionResumed?.invoke(this, session)
            }

            override fun onArSessionConfigChanged(session: ArSession, config: Config) {
                onArSessionConfigChanged?.invoke(this, session, config)
            }

            override fun onArFrame(arFrame: ArFrame) {
                onArFrameUpdated?.invoke(this, arFrame)
            }
        })
    }
}

interface ArSceneLifecycleObserver : SceneLifecycleObserver {

    fun onArSessionCreated(session: ArSession) {
    }

    fun onArSessionResumed(session: ArSession) {
    }

    fun onArSessionConfigChanged(session: ArSession, config: Config) {
    }

    fun onArFrame(arFrame: ArFrame) {
    }
}