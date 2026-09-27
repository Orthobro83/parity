package app.parity.core.list

enum class Category(val label: String) {
    MEAT_FISH("Meat & Fish"),
    DAIRY_EGGS("Dairy & Eggs"),
    PRODUCE("Produce"),
    BAKERY("Bakery"),
    BEVERAGES("Beverages"),
    DESSERTS_SNACKS("Desserts & Snacks"),
    PANTRY("Pantry"),
    FROZEN("Frozen"),
    HOUSEHOLD("Household"),
    PERSONAL_CARE("Personal Care"),
    BABY("Baby"),
    PET("Pet"),
    OTHER("Other"),
    ;

    companion object {
        fun fromName(name: String?): Category = entries.firstOrNull { it.name == name } ?: OTHER
    }
}
