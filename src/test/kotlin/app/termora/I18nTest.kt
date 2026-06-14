package app.termora

import org.apache.commons.lang3.LocaleUtils
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

class I18nTest {

    @Test
    fun test_ru_RU() {
        val bundle = ResourceBundle.getBundle("i18n/messages", LocaleUtils.toLocale("ru_RU"))
        assertEquals("Ок", bundle.getString("termora.confirm"))
    }
}
