package io.embodit.feelnumber.ui.feel.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val CARD = "questionCard"
    const val TAG = "sizeTag"
    const val RECALL = "recallPill"

    /** Width / height in dp (1 dp = 0.8 mm at scale 1: the card is 0.37 x 0.51 m). */
    val CARD_SIZE = 460f to 640f
    val TAG_SIZE = 300f to 104f
    val RECALL_SIZE = 320f to 72f

    const val CARD_SCALE = 1.0f

    val pool: List<String> = listOf(CARD, TAG, RECALL)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            CARD -> CARD_SIZE
            RECALL -> RECALL_SIZE
            else -> TAG_SIZE
        }
}
