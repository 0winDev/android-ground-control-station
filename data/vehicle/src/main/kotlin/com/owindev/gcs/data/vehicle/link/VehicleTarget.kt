package com.owindev.gcs.data.vehicle.link

import com.owindev.gcs.core.domain.vehicle.SystemId
import java.net.InetSocketAddress

/** The vehicle to ask for something, and the address its HEARTBEATs come from. */
internal data class VehicleTarget(val systemId: SystemId, val address: InetSocketAddress)
