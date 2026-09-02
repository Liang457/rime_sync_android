package com.rimesync.android.core

open class RimeSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ApiErrorException(message: String, cause: Throwable? = null) : RimeSyncException(message, cause)

class ConfigException(message: String, cause: Throwable? = null) : RimeSyncException(message, cause)

class PathTraversalException(message: String) : RimeSyncException(message)