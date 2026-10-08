package com.autoclicker.core.script

import kotlinx.serialization.json.Json

object ScriptSerializer {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(script: Script): String = json.encodeToString(Script.serializer(), script)

    fun decode(text: String): Script = json.decodeFromString(Script.serializer(), text)
}