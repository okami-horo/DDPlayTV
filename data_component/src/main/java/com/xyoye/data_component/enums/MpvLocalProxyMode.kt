package com.xyoye.data_component.enums

enum class MpvLocalProxyMode(
    val value: Int
) {
    OFF(0),
    AUTO(1),
    FORCE(2);

    companion object {
        fun from(value: Int?): MpvLocalProxyMode {
            return when (value) {
                OFF.value -> OFF
                FORCE.value -> FORCE
                AUTO.value -> AUTO
                else -> AUTO
            }
        }
    }
}
