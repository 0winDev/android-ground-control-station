package com.owindev.gcs.core.domain.vehicle.command

import kotlinx.coroutines.flow.StateFlow

/** The active vehicle's route for commands, and the operator's acceptance of a new one (REQ-051). */
interface CommandRouteRepository {

    /** The active live vehicle's route, on the current key; `null` while there is none. */
    val route: StateFlow<VehicleRoute?>

    /**
     * Pins [offer]'s new route only while its key is current and [RouteRule] still finds the route changed from and to
     * the offer's addresses. Accepting moves the key, so whatever was confirmed before goes nowhere, and restarts the
     * uplink's evidence on the new route.
     */
    suspend fun acceptRoute(offer: RouteOffer): RouteAcceptance
}
