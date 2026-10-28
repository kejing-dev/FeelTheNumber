package io.embodit.feelnumber.domain.model

import kotlin.math.PI
import kotlin.math.cbrt

/** Gases are balls (like the reference's blue bubbles), liquids and solids are cubes. Fixed per question. */
enum class Shape { SPHERE, CUBE }

/**
 * One question of the bank (design `questionBank`). The volume is the only truth: the size (diameter or
 * edge) is derived from it. Gases at 15 °C and 1 atm (the reference's convention).
 */
data class Question(
    val id: String,
    val set: String,
    val shape: Shape,
    val volumeM3: Double,
    val titleZh: String,
    val titleEn: String,
    val promptZh: String,
    val promptEn: String,
    val factZh: String,
    val factEn: String,
    val sourceZh: String,
    val sourceEn: String,
) {
    /** Diameter (sphere) or edge (cube), metres. */
    val sizeM: Float
        get() = Geometry.sizeOf(shape, volumeM3).toFloat()

    fun title(en: Boolean) = if (en) titleEn else titleZh

    fun prompt(en: Boolean) = if (en) promptEn else promptZh

    fun fact(en: Boolean) = if (en) factEn else factZh

    fun source(en: Boolean) = if (en) sourceEn else sourceZh
}

object Geometry {
    fun volumeOf(shape: Shape, size: Double): Double =
        when (shape) {
            Shape.SPHERE -> PI * size * size * size / 6.0
            Shape.CUBE -> size * size * size
        }

    fun sizeOf(shape: Shape, volume: Double): Double =
        when (shape) {
            Shape.SPHERE -> cbrt(6.0 * volume / PI)
            Shape.CUBE -> cbrt(volume)
        }

    /** Level radius used by the clearance rule: ball R, cube half diagonal (design `guessModel.placement`). */
    fun levelRadius(shape: Shape, size: Float): Float =
        when (shape) {
            Shape.SPHERE -> size / 2f
            Shape.CUBE -> size * 0.70710677f
        }
}

data class QuestionSet(val id: String, val zh: String, val en: String) {
    fun name(en: Boolean) = if (en) this.en else zh
}

/** The 20 questions, 4 sets x 5, each set from small to large (design `questionBank`). */
object QuestionBank {
    private const val GAS_ZH = "15 °C、1 个大气压下的气体体积（与参照 Real World Visuals 同口径）"
    private const val GAS_EN = "gas volume at 15 °C and 1 atm (as in Real World Visuals)"

    /** CO₂ density at 15 °C, kg/m³. */
    private const val CO2 = 1.87

    val sets =
        listOf(
            QuestionSet("co2", "二氧化碳", "CO₂"),
            QuestionSet("kilo", "一公斤", "One kilo"),
            QuestionSet("body", "身边", "Everyday"),
            QuestionSet("big", "装不下", "Too big"),
        )

    val all: List<Question> =
        listOf(
            // ---- co2
            // v0.1.0 build check (N5): the EPA calculator was revised to 1.24e-5 t CO₂ per smartphone charge (iPhone 16 data, 2024/25);
            // the design's 8.22e-6 was the 2019 value. 12.4 g / 1.87 = 0.00663 m³, a 0.23 m ball (about a basketball).
            Question(
                "co2-phone", "co2", Shape.SPHERE, 0.0124 / CO2,
                "手机充一次电", "One phone charge",
                "给手机充满一次电，发电时排出的二氧化碳有多大？", "How big is the CO₂ from charging a phone once?",
                "12 克听起来什么都不是，但它是一个差不多篮球大的气泡。", "12 grams sounds like nothing — it is a bubble about the size of a basketball.",
                "US EPA 温室气体当量计算器：每次手机充电 1.24×10⁻⁵ t CO₂（美国电网）；$GAS_ZH", "US EPA Greenhouse Gas Equivalencies: 1.24×10⁻⁵ t CO₂ per phone charge (US grid); $GAS_EN",
            ),
            Question(
                "co2-1kg", "co2", Shape.SPHERE, 1.0 / CO2,
                "1 公斤二氧化碳", "1 kg of CO₂",
                "1 公斤二氧化碳，捏一个球出来：它有多大？", "1 kg of CO₂ — pinch out a ball: how big is it?",
                "差不多一个 1 米的大球，正好够一个人一天呼出的量。", "About a 1-metre ball — roughly what one person breathes out in a day.",
                "CO₂ 密度 1.87 kg/m³（15 °C）；Real World Visuals 换算口径", "CO₂ density 1.87 kg/m³ at 15 °C; Real World Visuals convention",
            ),
            Question(
                "co2-petrol", "co2", Shape.SPHERE, 2.31 / CO2,
                "烧掉 1 升汽油", "Burning 1 litre of petrol",
                "烧掉 1 升汽油，排出的二氧化碳有多大？", "Burn 1 litre of petrol. How big is the CO₂?",
                "1 升液体进去，1200 多升气体出来——碳和空气里的氧结合了。", "One litre of liquid in, over 1,200 litres of gas out — the carbon picks up oxygen from the air.",
                "纯矿物汽油 2.31 kg CO₂/L（UK DESNZ 换算因子）；$GAS_ZH", "100 % mineral petrol 2.31 kg CO₂/L (UK DESNZ conversion factors); $GAS_EN",
            ),
            Question(
                "co2-flight", "co2", Shape.SPHERE, 90.0 / CO2,
                "北京飞上海，一位乘客", "Beijing → Shanghai, one passenger",
                "坐经济舱从北京飞到上海，分到你头上的二氧化碳有多大？", "Fly economy from Beijing to Shanghai. How big is your share of the CO₂?",
                "一趟两小时的航班，分给你的那份比一间卧室还大。", "A two-hour flight leaves you a share bigger than a bedroom.",
                "ICAO 碳排放计算器量级（PEK–SHA 经济舱约 80–100 kg，取 90 kg）；$GAS_ZH", "ICAO Carbon Emissions Calculator, PEK–SHA economy about 80–100 kg (90 kg used); $GAS_EN",
            ),
            Question(
                "co2-1t", "co2", Shape.SPHERE, 1000.0 / CO2,
                "1 吨二氧化碳", "1 tonne of CO₂",
                "1 吨二氧化碳：猜猜这个球有多大？", "1 tonne of CO₂ — how big is this ball?",
                "直径约 10 米，三层楼高——参照作品把纽约一年的排放画成了 5400 万个这样的球。", "About 10 m across, three storeys tall — the reference piece filled New York with 54 million of these.",
                "Real World Visuals：1 t CO₂ ≈ 直径 10.07 m 的球", "Real World Visuals: 1 t of CO₂ ≈ a ball 10.07 m across",
            ),
            // ---- kilo
            Question(
                "kg-gold", "kilo", Shape.CUBE, 1.0 / 19320.0,
                "1 公斤黄金", "1 kg of gold",
                "1 公斤黄金有多大？", "How big is 1 kg of gold?",
                "边长不到 4 厘米，能握在手心。", "Under 4 cm on a side — it sits in your palm.",
                "金密度 19.32 g/cm³", "Gold density 19.32 g/cm³",
            ),
            Question(
                "kg-dryice", "kilo", Shape.CUBE, 1.0 / 1562.0,
                "1 公斤干冰", "1 kg of dry ice",
                "1 公斤干冰（固态二氧化碳）有多大？", "How big is 1 kg of dry ice (solid CO₂)?",
                "同样是 1 公斤 CO₂，变成气体要大 800 多倍。", "The same kilo of CO₂ as gas is over 800 times bigger.",
                "干冰密度约 1.56 g/cm³", "Dry ice density about 1.56 g/cm³",
            ),
            Question(
                "kg-water", "kilo", Shape.CUBE, 0.001,
                "1 公斤水", "1 kg of water",
                "1 公斤水有多大？", "How big is 1 kg of water?",
                "正好 1 升，边长 10 厘米——拿它校准你的手感。", "Exactly 1 litre, 10 cm on a side — use it to calibrate your eye.",
                "定义：1 L = 0.001 m³", "By definition: 1 L = 0.001 m³",
            ),
            Question(
                "kg-air", "kilo", Shape.SPHERE, 1.0 / 1.225,
                "1 公斤空气", "1 kg of air",
                "1 公斤空气有多大？", "How big is 1 kg of air?",
                "你身边这间屋子里的空气，通常有好几十公斤。", "The air in the room around you usually weighs tens of kilos.",
                "国际标准大气海平面密度 1.225 kg/m³（15 °C）", "International Standard Atmosphere, 1.225 kg/m³ at sea level, 15 °C",
            ),
            Question(
                "kg-hydrogen", "kilo", Shape.SPHERE, 1.0 / 0.0852,
                "1 公斤氢气", "1 kg of hydrogen",
                "1 公斤氢气有多大？", "How big is 1 kg of hydrogen?",
                "氢燃料车一箱只装 5–6 公斤，所以要压到 700 个大气压。", "A hydrogen car tank holds only 5–6 kg — squeezed to 700 bar.",
                "氢气密度 0.0852 kg/m³（15 °C）", "Hydrogen density 0.0852 kg/m³ at 15 °C",
            ),
            // ---- body
            Question(
                "body-breath", "body", Shape.SPHERE, 0.0005,
                "一口气", "One breath",
                "安静地吸一口气，吸进去的空气有多大？", "Take one calm breath. How big is that air?",
                "大约一个拳头大一点的气球。", "Roughly a balloon a bit bigger than your fist.",
                "成人静息潮气量约 500 mL（生理学教材）", "Adult resting tidal volume about 500 mL (physiology textbooks)",
            ),
            Question(
                "body-blood", "body", Shape.SPHERE, 0.005,
                "全身的血", "All your blood",
                "一个成年人全身的血加起来有多大？", "All the blood in an adult body — how big?",
                "约 5 升，差不多一个排球那么大。", "About 5 litres — roughly a volleyball.",
                "成人血容量约 4.5–5.5 L", "Adult blood volume about 4.5–5.5 L",
            ),
            Question(
                "body-water-year", "body", Shape.CUBE, 0.73,
                "一年喝的水", "A year of drinking water",
                "每天喝 2 升，一年喝下去的水有多大？", "Drink 2 litres a day. How big is a year of it?",
                "一个边长 90 厘米的水箱，比洗衣机还大。", "A 90 cm tank — bigger than a washing machine.",
                "按每天 2 L × 365 天", "2 L a day × 365 days",
            ),
            Question(
                "body-water-ton", "body", Shape.CUBE, 1.0,
                "1 吨水", "1 tonne of water",
                "1 吨水有多大？", "How big is 1 tonne of water?",
                "1 立方米。水费账单上的「1 吨」就是它。", "One cubic metre — the 'tonne' on a water bill.",
                "定义：1 t 水 = 1 m³", "By definition: 1 t of water = 1 m³",
            ),
            Question(
                "body-air-day", "body", Shape.SPHERE, 11.0,
                "一天吸进的空气", "A day of breathing",
                "一个人一天吸进的空气有多大？", "How big is all the air you breathe in a day?",
                "接近 3 米的球，大多数客厅的天花板挡住它的头顶。", "Nearly a 3 m ball — most living-room ceilings cut off its top.",
                "American Lung Association：约 11,000 升 / 天", "American Lung Association: about 11,000 litres a day",
            ),
            // ---- big
            Question(
                "big-lifetime-water", "big", Shape.CUBE, 56.94,
                "一辈子喝的水", "A lifetime of drinking water",
                "每天 2 升，喝一辈子（78 年）的水有多大？", "2 litres a day for a lifetime (78 years). How big?",
                "一个近 4 米的水立方，你家的层高装不下。", "A cube almost 4 m on a side — taller than your ceiling.",
                "按每天 2 L、预期寿命 78 岁", "2 L a day, life expectancy 78 years",
            ),
            Question(
                "big-whale", "big", Shape.SPHERE, 150.0,
                "一头蓝鲸", "A blue whale",
                "把一头蓝鲸揉成一个球，有多大？", "Squash a blue whale into a ball. How big?",
                "地球上最大的动物，揉成球直径也只有 6 米多。", "The largest animal on Earth makes a ball only about 6.6 m wide.",
                "蓝鲸成体约 100–190 t，取 150 t，密度约等于水", "Adult blue whale about 100–190 t (150 t used), density about that of water",
            ),
            Question(
                "big-pool", "big", Shape.CUBE, 2500.0,
                "一个标准泳池的水", "An Olympic pool of water",
                "50 米标准泳池里的水，堆成立方体有多大？", "Stack the water of a 50 m pool into a cube. How big?",
                "13.6 米见方，四五层楼高。", "13.6 m on a side — four or five storeys.",
                "按 50 × 25 m、水深 2 m", "50 × 25 m, 2 m deep",
            ),
            Question(
                "big-air-year", "big", Shape.SPHERE, 4015.0,
                "一年吸进的空气", "A year of breathing",
                "一个人一年吸进的空气有多大？", "How big is a year of your breathing?",
                "直径近 20 米——比你家整栋楼都宽。", "Nearly 20 m across — wider than your whole building.",
                "American Lung Association：约 11 m³ / 天 × 365", "American Lung Association: about 11 m³ a day × 365",
            ),
            Question(
                "big-co2-capita", "big", Shape.SPHERE, 8900.0 / CO2,
                "中国人均一年的二氧化碳", "One person's CO₂ for a year (China)",
                "中国人均一年排放的二氧化碳，有多大？", "One average person in China, one year of CO₂. How big?",
                "直径约 21 米，相当于 9 个 1 吨的球。", "About 21 m across — nine of the 1-tonne balls.",
                "Global Carbon Budget / Our World in Data：2022 年人均化石 CO₂ 约 8.9 t；$GAS_ZH", "Global Carbon Budget / Our World in Data: about 8.9 t fossil CO₂ per person in 2022; $GAS_EN",
            ),
        )

    private val byId = all.associateBy { it.id }

    const val FIRST = "co2-1kg"

    fun get(id: String): Question = byId[id] ?: byId.getValue(FIRST)

    fun has(id: String): Boolean = id in byId

    fun inSet(set: String): List<Question> = all.filter { it.set == set }

    fun set(id: String): QuestionSet = sets.firstOrNull { it.id == id } ?: sets.first()

    /** 1-based position of a question inside its set. */
    fun indexInSet(id: String): Int = inSet(get(id).set).indexOfFirst { it.id == id } + 1

    fun isLastInSet(id: String): Boolean = indexInSet(id) == inSet(get(id).set).size

    /** Next question; after the last of a set the first of the next set, after the 4th set the 1st. */
    fun next(id: String): String {
        val i = all.indexOfFirst { it.id == id }
        return all[(i + 1).mod(all.size)].id
    }
}
