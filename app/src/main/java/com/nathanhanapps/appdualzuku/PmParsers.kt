//package com.nathanhanapps.appdualzuku
//
//object PmParsers {
//
//    /** Parses output of: pm list packages --user X */
//    fun parsePmListPackages(output: String): Set<String> {
//        // lines look like: package:com.android.vending
//        val set = HashSet<String>()
//        output.lineSequence().forEach { line ->
//            val t = line.trim()
//            if (t.startsWith("package:") && t.length > "package:".length) {
//                set.add(t.substringAfter("package:").trim())
//            }
//        }
//        return set
//    }
//}
package com.nathanhanapps.appdualzuku

object PmParsers {

    /** Parses output of: pm list packages --user X
     * Works even if output is wrapped like:
     * exitCode=0
     * stdout:
     * package:com.xxx
     */
    fun parsePmListPackages(output: String): Set<String> {
        val set = HashSet<String>()

        output.lineSequence().forEach { raw ->
            val line = raw.trim()

            // Find "package:" anywhere in the line
            val idx = line.indexOf("package:")
            if (idx >= 0) {
                val pkg = line.substring(idx + "package:".length).trim()
                if (pkg.isNotEmpty()) set.add(pkg)
            }
        }

        return set
    }
}
