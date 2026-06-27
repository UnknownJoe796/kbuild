package com.ivieleague.kbuild.common

/**
 * Compare two Maven-style version strings part-by-part: numerically where both parts are numbers
 * (so "1.10.2" > "1.9.0"), lexically otherwise. Used for highest-version-wins conflict resolution on
 * compile/test classpaths.
 *
 * Deliberately looser than [Version], which demands strict major.minor.patch and would reject many
 * real coordinates (e.g. "2.0.0-RC1", four-segment or date-based versions).
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.', '-')
    val pb = b.split('.', '-')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i) ?: "0"
        val y = pb.getOrNull(i) ?: "0"
        val xi = x.toIntOrNull()
        val yi = y.toIntOrNull()
        val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
        if (c != 0) return c
    }
    return 0
}
