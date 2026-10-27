package com.verdenroz.vpnchain.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Whether a rendered config asks sing-box to log every connection. */
fun logsConnections(configJson: String): Boolean =
    Json.parseToJsonElement(configJson).jsonObject.getValue("log").jsonObject
        .getValue("level").jsonPrimitive.content == "info"
