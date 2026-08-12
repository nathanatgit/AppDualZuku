package com.nathanhanapps.appdual

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reads/writes the batch-selection package list as a small, human-readable JSON file. */
object PackageListIO {
    private const val FORMAT = "appdual.package-list"
    private const val VERSION = 1

    fun serialize(packages: Collection<String>): String {
        val obj = JSONObject()
        obj.put("format", FORMAT)
        obj.put("version", VERSION)
        obj.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
        obj.put("packages", JSONArray(packages.toList()))
        return obj.toString(2)
    }

    /** @throws org.json.JSONException if [json] isn't a valid package-list export. */
    fun deserialize(json: String): List<String> {
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("packages")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun defaultFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "AppDual_export_$ts.json"
    }
}
