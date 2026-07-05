package com.legendsayantan.adbtools.data

data class RouteConfig(
    val pkg: String,
    var deviceId: Int = -1,
    var volume: Float = 1.0f,
    var balance: Float = 0f,
    var bandCount: Int = 5,
    var bands: FloatArray = FloatArray(5) { 50f },
    var uid: Int = -1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RouteConfig

        if (pkg != other.pkg) return false
        if (deviceId != other.deviceId) return false
        if (volume != other.volume) return false
        if (balance != other.balance) return false
        if (bandCount != other.bandCount) return false
        if (!bands.contentEquals(other.bands)) return false
        if (uid != other.uid) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pkg.hashCode()
        result = 31 * result + deviceId
        result = 31 * result + volume.hashCode()
        result = 31 * result + balance.hashCode()
        result = 31 * result + bandCount
        result = 31 * result + bands.contentHashCode()
        result = 31 * result + uid
        return result
    }
}
