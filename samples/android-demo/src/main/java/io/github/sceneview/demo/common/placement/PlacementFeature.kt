package io.github.sceneview.demo.common.placement

import com.google.ar.core.Config
import com.google.ar.core.Session
import io.github.sceneview.demo.R

/**
 * The three device-capability features that share the automatic-placement comparison
 * screen. Each one carries its own copy and the ARCore probe that decides whether the
 * comparison can honestly be offered at all.
 */
internal enum class PlacementFeature(val title: Int, val explanation: Int, val requirement: Int) {
    DEPTH(R.string.demo_ar_depth_occlusion_title, R.string.ar_depth_comparison, R.string.ar_depth_requirement),
    PEOPLE(R.string.demo_ar_people_occlusion_title, R.string.ar_people_comparison, R.string.ar_people_requirement),
    STABILIZATION(R.string.demo_ar_image_stabilization_title, R.string.ar_eis_comparison, R.string.ar_eis_requirement);

    fun isSupported(session: Session): Boolean = when (this) {
        DEPTH -> session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        PEOPLE -> session.isSemanticModeSupported(Config.SemanticMode.ENABLED) &&
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        STABILIZATION -> session.isImageStabilizationModeSupported(Config.ImageStabilizationMode.EIS)
    }
}
