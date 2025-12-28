package com.xyoye.data_component.enums

enum class LocalProxyMode(
    val value: Int
) {
    OFF(0),
    AUTO(1),
    FORCE(2);

    companion object {
        fun from(value: Int?): LocalProxyMode {
            return when (value) {
                OFF.value -> OFF
                FORCE.value -> FORCE
                AUTO.value -> AUTO
                else -> AUTO
            }
        }
    }
}
