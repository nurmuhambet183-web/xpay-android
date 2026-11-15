package app.xpay.assistant

/** Поиск имени ассистента во фразе. Возвращает команду после имени, пустую строку (только имя) или null. */
object Wake {
    private val greet = setOf("привет", "эй", "хей", "слушай", "окей", "ок", "алло", "hey", "hi", "hello", "ok", "okay")

    fun aliases(name: String): List<String> {
        val n = name.lowercase().trim()
        val list = mutableListOf(n)
        if (n == "xpay") {
            list.addAll(listOf("икспей", "экспей", "икспэй", "экспэй", "икс пей", "экс пей", "x pay", "иксбей", "иксплей"))
        }
        return list
    }

    fun parse(text: String, name: String): String? {
        val cleaned = text.lowercase()
            .replace(Regex("[.,!?;:\"«»()\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val s = " $cleaned "
        var best = -1
        var len = 0
        for (a in aliases(name)) {
            val i = s.indexOf(" $a ")
            if (i >= 0 && (best < 0 || i < best)) {
                best = i
                len = a.length + 2
            }
        }
        if (best < 0) return null
        val before = s.substring(0, best).trim().split(" ").dropWhile { it in greet }.joinToString(" ").trim()
        val after = s.substring(best + len).trim()
        return if (after.isNotEmpty()) after else before
    }
}
