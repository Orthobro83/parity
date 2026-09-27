package app.parity.core.list

import app.parity.core.list.Category.BABY
import app.parity.core.list.Category.BAKERY
import app.parity.core.list.Category.BEVERAGES
import app.parity.core.list.Category.DAIRY_EGGS
import app.parity.core.list.Category.DESSERTS_SNACKS
import app.parity.core.list.Category.FROZEN
import app.parity.core.list.Category.HOUSEHOLD
import app.parity.core.list.Category.MEAT_FISH
import app.parity.core.list.Category.OTHER
import app.parity.core.list.Category.PANTRY
import app.parity.core.list.Category.PERSONAL_CARE
import app.parity.core.list.Category.PET
import app.parity.core.list.Category.PRODUCE
import app.parity.core.scan.Names

/**
 * Tier 1 of the classifier in design §8.2: a bundled lexicon. Multi-word phrases win over single
 * words ("peanut butter" is pantry, not dairy), and for single words the last known noun wins
 * ("chocolate chip cookies" → cookie). User overrides are checked by the caller first.
 */
object CategoryClassifier {
    private val phrases: Map<String, Category> = mapOf(
        "ground beef" to MEAT_FISH, "hot dog" to MEAT_FISH, "chicken breast" to MEAT_FISH, "deli meat" to MEAT_FISH,
        "cold cut" to MEAT_FISH, "fish stick" to FROZEN, "french fry" to FROZEN, "frozen pizza" to FROZEN,
        "ice cream" to DESSERTS_SNACKS, "ice pop" to DESSERTS_SNACKS, "trail mix" to DESSERTS_SNACKS,
        "granola bar" to DESSERTS_SNACKS, "protein bar" to DESSERTS_SNACKS, "chewing gum" to DESSERTS_SNACKS,
        "sour cream" to DAIRY_EGGS, "cream cheese" to DAIRY_EGGS, "cottage cheese" to DAIRY_EGGS,
        "whipped cream" to DAIRY_EGGS, "heavy cream" to DAIRY_EGGS,
        "peanut butter" to PANTRY, "coconut milk" to PANTRY, "tomato paste" to PANTRY, "tomato sauce" to PANTRY,
        "soy sauce" to PANTRY, "olive oil" to PANTRY, "black pepper" to PANTRY, "ground pepper" to PANTRY,
        "baking soda" to PANTRY, "baking powder" to PANTRY, "maple syrup" to PANTRY, "corn flake" to PANTRY,
        "dried fruit" to PANTRY, "bread crumb" to PANTRY,
        "almond milk" to BEVERAGES, "oat milk" to BEVERAGES, "soy milk" to BEVERAGES, "chocolate milk" to BEVERAGES,
        "orange juice" to BEVERAGES, "mineral water" to BEVERAGES, "sparkling water" to BEVERAGES,
        "energy drink" to BEVERAGES, "iced tea" to BEVERAGES, "hot chocolate" to BEVERAGES,
        "ground coffee" to BEVERAGES, "coffee creamer" to BEVERAGES,
        "green onion" to PRODUCE, "bell pepper" to PRODUCE, "sweet potato" to PRODUCE, "green bean" to PRODUCE,
        "english muffin" to BAKERY, "rye bread" to BAKERY,
        "toilet paper" to HOUSEHOLD, "paper towel" to HOUSEHOLD, "dish soap" to HOUSEHOLD, "trash bag" to HOUSEHOLD,
        "garbage bag" to HOUSEHOLD, "bin bag" to HOUSEHOLD, "plastic wrap" to HOUSEHOLD, "cling film" to HOUSEHOLD,
        "aluminum foil" to HOUSEHOLD, "light bulb" to HOUSEHOLD, "air freshener" to HOUSEHOLD,
        "fabric softener" to HOUSEHOLD, "laundry detergent" to HOUSEHOLD,
        "hand soap" to PERSONAL_CARE, "body wash" to PERSONAL_CARE, "shower gel" to PERSONAL_CARE,
        "shaving cream" to PERSONAL_CARE, "lip balm" to PERSONAL_CARE, "cotton swab" to PERSONAL_CARE,
        "hand sanitizer" to PERSONAL_CARE,
        "baby wipe" to BABY, "baby food" to BABY, "dog food" to PET, "cat food" to PET, "cat litter" to PET,
        "pet food" to PET, "dog treat" to PET, "cat treat" to PET,
    )

    private val words: Map<String, Category> = buildMap {
        fun put(category: Category, list: String) = list.split(' ').filter { it.isNotEmpty() }.forEach { this[it] = category }
        put(MEAT_FISH, "chicken beef pork lamb mutton veal turkey duck bacon ham sausage salami pepperoni chorizo steak mince meat meatball rib brisket jerky fish salmon tuna cod tilapia trout shrimp prawn crab lobster mussel oyster clam scallop squid octopus anchovy sardine mackerel herring wiener frankfurter prosciutto pastrami liver kebab mtsvadi fillet wing drumstick thigh")
        put(DAIRY_EGGS, "milk cheese yogurt yoghurt butter cream kefir egg ghee mozzarella cheddar parmesan feta brie gouda ricotta mascarpone halloumi sulguni imeruli matsoni buttermilk skyr quark tvorog ayran")
        put(PRODUCE, "apple banana orange lemon lime grape strawberry blueberry raspberry blackberry cherry peach pear plum apricot mango pineapple kiwi melon watermelon avocado tomato potato onion garlic carrot cucumber lettuce spinach kale cabbage broccoli cauliflower pepper chili zucchini eggplant aubergine mushroom celery pumpkin squash beet beetroot radish ginger herb parsley cilantro coriander dill basil mint tarragon scallion leek asparagus pea pomegranate persimmon fig grapefruit tangerine mandarin clementine nectarine coconut salad fruit vegetable veggie berry greens arugula rocket jalapeno shallot turnip fennel artichoke okra sprout corn")
        put(BAKERY, "bread baguette bun roll bagel croissant muffin tortilla pita lavash naan pastry doughnut brioche sourdough toast crumpet khachapuri shoti puri flatbread wrap scone danish loaf")
        put(BEVERAGES, "coffee tea creamer juice water soda cola coke pepsi lemonade beer wine vodka whiskey whisky rum gin tequila brandy chacha cognac champagne prosecco cider kombucha gatorade cocoa smoothie espresso latte borjomi nabeglavi lagidze kvass drink tonic")
        put(DESSERTS_SNACKS, "chocolate candy cookie biscuit cake pie chip crisp popcorn pretzel cracker nut peanut almond cashew pistachio walnut hazelnut gum marshmallow brownie pudding jelly gummy gummie licorice churchkhela gozinaki halva baklava waffle wafer dessert snack sweet donut tiramisu macaron cupcake cheesecake")
        put(PANTRY, "rice pasta spaghetti noodle flour sugar salt oil vinegar sauce ketchup mayonnaise mayo mustard tkemali adjika honey jam nutella cereal oat oatmeal granola bean lentil chickpea can soup broth stock bouillon spice cinnamon paprika cumin oregano vanilla yeast buckwheat quinoa couscous bulgur semolina olive pickle sauerkraut salsa hummus dressing syrup cornstarch gelatin tahini raisin prune date")
        put(FROZEN, "frozen pizza dumpling khinkali pelmeni fries popsicle ice")
        put(HOUSEHOLD, "tissue napkin detergent laundry dishwasher sponge foil ziploc bleach cleaner disinfectant wipe battery candle match lighter softener broom mop glove")
        put(PERSONAL_CARE, "shampoo conditioner soap toothpaste toothbrush floss mouthwash deodorant razor lotion sunscreen moisturizer makeup cotton tampon pad sanitary condom vitamin medicine painkiller ibuprofen paracetamol aspirin bandage perfume cologne")
        put(BABY, "diaper nappy formula pacifier baby")
        put(PET, "kibble litter pet")
    }

    fun classify(text: String): Category {
        val tokens = Names.tokens(text)
        if (tokens.isEmpty()) return OTHER
        val joined = tokens.joinToString(" ")
        phrases.entries.sortedByDescending { it.key.length }.firstOrNull { (phrase, _) ->
            " $joined ".contains(" $phrase ")
        }?.let { return it.value }
        if ("frozen" in tokens) return FROZEN
        for (token in tokens.asReversed()) {
            words[token]?.let { return it }
        }
        return OTHER
    }
}
