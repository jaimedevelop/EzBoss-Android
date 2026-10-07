package pro.ezboss.mobile.data

/** JSON null and legacy null strings must never become visible account names. */
internal fun usableName(value: Any?): String? = (value as? String)?.trim()
    ?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

internal fun dashboardGreetingName(profileFirstName: Any?, accountDisplayName: String?): String =
    usableName(profileFirstName)
        ?: usableName(accountDisplayName)?.split(Regex("\\s+"))?.first()
        ?: "User"
