/*
 * Copyright (c) 2023. Proton AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.protonvpn.android.settings.data

import android.os.Build
import com.protonvpn.android.appconfig.GetFeatureFlags
import com.protonvpn.android.appconfig.Restrictions
import com.protonvpn.android.appconfig.RestrictionsConfig
import com.protonvpn.android.auth.usecase.CurrentUser
import com.protonvpn.android.concurrency.VpnDispatcherProvider
import com.protonvpn.android.netshield.NetShieldAvailability
import com.protonvpn.android.netshield.NetShieldProtocol
import com.protonvpn.android.netshield.getNetShieldAvailability
import com.protonvpn.android.tv.IsTvCheck
import com.protonvpn.android.utils.SyncStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Current user settings that are in effect.
 *
 * The effective value is based on the raw user setting but may be affected by additional constraints like feature
 * flags, paid vs free plan etc.
 */
@Singleton
class EffectiveCurrentUserSettings(
    mainScope: CoroutineScope,
    private val effectiveCurrentUserSettingsFlow: Flow<LocalUserSettings>
) {
    val effectiveSettings = effectiveCurrentUserSettingsFlow
        .distinctUntilChanged()
        .shareIn(mainScope, SharingStarted.Lazily, 1)

    val apiUseDoh = distinct { it.apiUseDoh }
    val netShield = distinct { it.netShield }
    val protocol = distinct { it.protocol }
    val safeMode = distinct { it.safeMode }
    val secureCore = distinct { it.secureCore }
    val telemetry = distinct { it.telemetry }
    val vpnAccelerator = distinct { it.vpnAccelerator }
    val vpnAcceleratorNotifications = distinct { it.vpnAcceleratorNotifications }

    @Inject
    constructor(mainScope: CoroutineScope, effectiveCurrentUserSettingsFlow: EffectiveCurrentUserSettingsFlow)
        : this(mainScope, effectiveCurrentUserSettingsFlow as Flow<LocalUserSettings>)

    private fun <T> distinct(transform: (LocalUserSettings) -> T): Flow<T> =
        effectiveSettings.map(transform).distinctUntilChanged()
}

@Singleton
class EffectiveCurrentUserSettingsFlow constructor(
    rawCurrentUserSettingsFlow: Flow<LocalUserSettings>,
    getFeatureFlags: GetFeatureFlags,
    currentUser: CurrentUser,
    isTv: IsTvCheck,
    restrictionFlow: Flow<Restrictions>
) : Flow<LocalUserSettings> {

    private val effectiveSettings: Flow<LocalUserSettings> = combine(
        rawCurrentUserSettingsFlow,
        getFeatureFlags,
        currentUser.vpnUserFlow,
        restrictionFlow
    ) { settings, features, vpnUser, restrictions ->
        val effectiveVpnAccelerator = !features.vpnAccelerator || restrictions.vpnAccelerator || settings.vpnAccelerator
        val netShieldAvailable = vpnUser.getNetShieldAvailability() == NetShieldAvailability.AVAILABLE
        val effectiveSplitTunneling = if (restrictions.splitTunneling)
            SplitTunnelingSettings(isEnabled = false) else settings.splitTunneling
        val effectiveSafeMode = when {
            !features.safeMode -> null
            restrictions.safeMode -> true // safe mode enabled when setting is restricted
            else -> settings.safeMode
        }
        settings.copy(
            connectOnBoot = Build.VERSION.SDK_INT < 26 && settings.connectOnBoot,
            defaultProfileId = if (!restrictions.quickConnect || isTv()) settings.defaultProfileId else null,
            lanConnections = isTv() || (!restrictions.lan && settings.lanConnections),
            netShield = if (netShieldAvailable && features.netShieldEnabled) settings.netShield else NetShieldProtocol.DISABLED,
            safeMode = effectiveSafeMode,
            telemetry = features.telemetry && settings.telemetry,
            vpnAccelerator = effectiveVpnAccelerator,
            vpnAcceleratorNotifications =
                features.vpnAccelerator && effectiveVpnAccelerator && settings.vpnAcceleratorNotifications,
            splitTunneling = effectiveSplitTunneling
        )
    }

    @Inject
    constructor(
        localUserSettings: CurrentUserLocalSettingsManager,
        getFeatureFlags: GetFeatureFlags,
        currentUser: CurrentUser,
        isTv: IsTvCheck,
        restrictions: RestrictionsConfig
    ) : this(localUserSettings.rawCurrentUserSettingsFlow, getFeatureFlags, currentUser, isTv, restrictions.restrictionFlow)

    override suspend fun collect(collector: FlowCollector<LocalUserSettings>) = effectiveSettings.collect(collector)
}

@Deprecated(
    "Use EffectiveCurrentUserSettings.effectiveSettings flow, this object is for synchronous access in legacy code"
)
@Singleton
class EffectiveCurrentUserSettingsCached(
    private val stateFlow: StateFlow<LocalUserSettings>
) : StateFlow<LocalUserSettings> by stateFlow {

    @Inject constructor(
        mainScope: CoroutineScope,
        dispatcherProvider: VpnDispatcherProvider,
        effectiveCurrentUserSettingsFlow: EffectiveCurrentUserSettingsFlow
    ) : this(SyncStateFlow(mainScope, effectiveCurrentUserSettingsFlow, dispatcherProvider))
}
