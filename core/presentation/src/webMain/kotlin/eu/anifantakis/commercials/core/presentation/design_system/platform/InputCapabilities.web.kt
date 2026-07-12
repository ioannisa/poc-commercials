package eu.anifantakis.commercials.core.presentation.design_system.platform

import kotlinx.browser.window

/**
 * Web is the one target where the hardware genuinely varies per session
 * (phone, desktop, hybrid), so it probes the media-query facts.
 *
 * `any-pointer`/`any-hover` (not `pointer`/`hover`): the singular forms
 * describe only the PRIMARY pointing device, while a hybrid device can be
 * coarse AND fine at once - exactly the case the density policy needs to
 * know about.
 */
internal actual fun startupInputCapabilities(): InputCapabilities = InputCapabilities(
    hasCoarsePointer = window.matchMedia("(any-pointer: coarse)").matches,
    hasFinePointer = window.matchMedia("(any-pointer: fine)").matches,
    canHover = window.matchMedia("(any-hover: hover)").matches,
)
