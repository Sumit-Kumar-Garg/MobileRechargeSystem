package com.app.recharge.core.network

/**
 * Turns a concrete URL path into a template ("/v1/orders/3f2a.../pay" -> "/v1/orders/{id}/pay").
 * Telemetry only ever carries templates: no ids (privacy) and a bounded number of distinct values.
 */
object EndpointTemplates {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val longNumber = Regex("^[0-9]{3,}$")

    fun normalize(path: String): String =
        path.split('/').joinToString("/") { segment ->
            if (uuid.matches(segment) || longNumber.matches(segment)) "{id}" else segment
        }
}
