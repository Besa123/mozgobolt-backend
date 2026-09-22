package com.mozgobolt.feature.vehicleTracking.service

import com.mozgobolt.feature.vehicleTracking.domain.VehicleLocationBuffer
import com.mozgobolt.feature.vehicleTracking.domain.model.VehicleLocationUpdate
import java.util.concurrent.ConcurrentLinkedQueue

class InMemoryVehicleLocationBuffer : VehicleLocationBuffer {
    private val queue = ConcurrentLinkedQueue<VehicleLocationUpdate>()

    override fun enqueue(update: VehicleLocationUpdate) {
        queue.add(update)
    }

    override fun drainAll(): List<VehicleLocationUpdate> =
        buildList {
            while (true) add(queue.poll() ?: break)
        }
}
