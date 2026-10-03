package com.bartech.demonfc_app.smarttag.core

class SmartTagException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)