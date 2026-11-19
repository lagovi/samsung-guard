package com.cybermiller.guard

import android.content.Context
import java.util.Random

object DemoSms {
    private data class Demo(val address: String, val body: String)

    private val samples = listOf(
        Demo("900", "Ваш код подтверждения: 483920. Не сообщайте его никому."),
        Demo("MEGAFON", "Успейте! Только сегодня скидка 50% на связь. Подробности на сайте."),
        Demo("112", "МЧС: ожидается сильный ветер до 25 м/с. Будьте осторожны."),
        Demo("MOM", "Купи хлеб по дороге домой, я приготовлю ужин"),
        Demo("SBERBANK", "Выписка: поступление 15 000 руб. Баланс: 42 350 руб."),
        Demo("CLUB", "Вы приглашены в закрытый клуб скидок! Жми на ссылку."),
    )

    fun send(ctx: Context) {
        val appCtx = ctx.applicationContext
        val d = samples[Random().nextInt(samples.size)]
        Thread {
            SmsStore.onSmsReceived(appCtx, d.address, d.body, System.currentTimeMillis())
        }.start()
    }
}
