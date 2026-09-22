package com.mozgobolt.feature.vehicle.routing.dto.request

import kotlin.test.Test
import kotlin.test.assertTrue

private const val VALID_PLATE = "ABC-123"

class CreateVehicleRequestDtoTest {
    @Test
    fun `a well-formed label and plate has no validation errors`() {
        assertTrue(CreateVehicleRequestDto(label = "Truck 1", licensePlate = VALID_PLATE).validate().isEmpty())
    }

    @Test
    fun `a blank label is rejected`() {
        assertTrue(CreateVehicleRequestDto(label = "", licensePlate = VALID_PLATE).validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only label is rejected`() {
        assertTrue(CreateVehicleRequestDto(label = "   ", licensePlate = VALID_PLATE).validate().isNotEmpty())
    }

    @Test
    fun `a label with leading or trailing whitespace is rejected`() {
        assertTrue(CreateVehicleRequestDto(label = " Truck 1", licensePlate = VALID_PLATE).validate().isNotEmpty())
        assertTrue(CreateVehicleRequestDto(label = "Truck 1 ", licensePlate = VALID_PLATE).validate().isNotEmpty())
    }

    @Test
    fun `a label over one hundred characters is rejected`() {
        assertTrue(CreateVehicleRequestDto(label = "a".repeat(101), licensePlate = VALID_PLATE).validate().isNotEmpty())
    }

    @Test
    fun `a label at exactly one hundred characters is accepted`() {
        assertTrue(CreateVehicleRequestDto(label = "a".repeat(100), licensePlate = VALID_PLATE).validate().isEmpty())
    }

    @Test
    fun `a label containing markup-like characters is rejected`() {
        assertTrue(
            CreateVehicleRequestDto(
                label = "<script>Truck</script>",
                licensePlate = VALID_PLATE,
            ).validate().isNotEmpty(),
        )
    }

    @Test
    fun `a label with accented Hungarian characters is accepted`() {
        assertTrue(
            CreateVehicleRequestDto(label = "Kürtőskalács Furgon", licensePlate = VALID_PLATE).validate().isEmpty(),
        )
    }

    @Test
    fun `a blank license plate is rejected`() {
        assertTrue(CreateVehicleRequestDto(label = "Truck 1", licensePlate = "").validate().isNotEmpty())
    }

    @Test
    fun `a whitespace-only license plate is rejected`() {
        assertTrue(CreateVehicleRequestDto(label = "Truck 1", licensePlate = "   ").validate().isNotEmpty())
    }

    @Test
    fun `a license plate over twenty characters is rejected`() {
        assertTrue(CreateVehicleRequestDto(label = "Truck 1", licensePlate = "a".repeat(21)).validate().isNotEmpty())
    }

    @Test
    fun `a license plate at exactly twenty characters is accepted`() {
        assertTrue(CreateVehicleRequestDto(label = "Truck 1", licensePlate = "a".repeat(20)).validate().isEmpty())
    }

    @Test
    fun `a null picture url is accepted`() {
        assertTrue(
            CreateVehicleRequestDto(
                label = "Truck 1",
                licensePlate = VALID_PLATE,
                pictureUrl = null,
            ).validate().isEmpty(),
        )
    }
}
