package pro.ezboss.mobile

data class EstimateTabContract(val label: String, val enabled: Boolean)

internal fun estimateDashboardTabs(isChangeOrder: Boolean): List<EstimateTabContract> = buildList {
    add(EstimateTabContract("Estimate", true))
    add(EstimateTabContract("Client View", true))
    if (!isChangeOrder) add(EstimateTabContract("Change Orders", false))
    add(EstimateTabContract("Payments", false))
    add(EstimateTabContract("Timeline", false))
    add(EstimateTabContract("Communication", false))
    add(EstimateTabContract("History", false))
}

internal fun estimateStateQuery(label: String): String? = when (label) {
    "Draft" -> "draft"
    "Estimate" -> "estimate"
    "Change order" -> "change-order"
    "Invoice" -> "invoice"
    else -> null
}

internal fun clientStateQuery(label: String): String? = when (label) {
    "Sent" -> "sent"
    "Viewed" -> "viewed"
    "Accepted" -> "accepted"
    "Denied" -> "denied"
    "On hold" -> "on-hold"
    "Expired" -> "expired"
    "No client status" -> "none"
    else -> null
}
