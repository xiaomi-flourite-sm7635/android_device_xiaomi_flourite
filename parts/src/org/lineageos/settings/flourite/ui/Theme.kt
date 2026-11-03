/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.flourite.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.theme.SettingsTheme

/** Uses the same dynamic-colour and typography pipeline as Lineage Settings. */
@Composable
fun FlouritePartsTheme(content: @Composable () -> Unit) {
    SettingsTheme(content)
}

/** Motion tokens for controls and live readings; page navigation follows Settings. */
object Motion {
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
