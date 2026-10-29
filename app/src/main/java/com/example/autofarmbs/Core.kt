package com.example.autofarmbs

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * ⚠️ DISCLAIMER: автоматизация и читы нарушают Terms of Service Brawl Stars
 * и приводят к бану аккаунта. Это учебный/исследовательский концепт,
 * использование на свой риск.
 */
const val DISCLAIMER =
    "⚠️ Автоматизация и читы (аимбот, авто-уворот и т.п.) нарушают Terms of Service Brawl Stars " +
    "и приводят к бану аккаунта. Приложение — учебный/исследовательский концепт, " +
    "использование на свой риск."

enum class FarmMode(val title: String) {
    TROPHY_PUSH("Кубки текущим бойцом"),
    REWARDS_ONLY("Только награды/попапы")
}

data class Config(
    val mode: FarmMode = FarmMode.TROPHY_PUSH,
    val aim: Boolean = true,
    val dodge: Boolean = true,
    val aggro: Float = 0.6f,        // 0..1 — насколько близко лезем к врагу
    val dodgeLevel: Float = 0.5f,   // 0..1 — сила стрейфа
    val useSuper: Boolean = true,
    val useGadget: Boolean = true,
    val stopAtTrophies: Int = 0,    // 0 = без лимита
    val tickMs: Long = 120
)

data class Stats(
    val matches: Int = 0, val wins: Int = 0,
    val startTrophies: Int? = null, val trophies: Int? = null,
    val startedAt: Long = 0, val history: List<Int> = emptyList()
) {
    val delta: Int get() = if (startTrophies != null && trophies != null) trophies - startTrophies else 0
}

object Bus {
    val running = MutableStateFlow(false)
    val stats = MutableStateFlow(Stats())
    val logs = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    fun log(s: String) = logs.update { (it + "${fmt.format(Date())}  $s").takeLast(300) }
}

private val Context.ds by preferencesDataStore("cfg")

class ConfigStore(private val ctx: Context) {
    private val kMode = stringPreferencesKey("mode")
    private val kAim = booleanPreferencesKey("aim")
    private val kDodge = booleanPreferencesKey("dodge")
    private val kAggro = floatPreferencesKey("aggro")
    private val kDodgeLvl = floatPreferencesKey("dodgeLvl")
    private val kSuper = booleanPreferencesKey("super")
    private val kGadget = booleanPreferencesKey("gadget")
    private val kStop = intPreferencesKey("stop")

    val flow: Flow<Config> = ctx.ds.data.map { p ->
        val d = Config()
        Config(
            mode = runCatching { FarmMode.valueOf(p[kMode] ?: "") }.getOrDefault(d.mode),
            aim = p[kAim] ?: d.aim, dodge = p[kDodge] ?: d.dodge,
            aggro = p[kAggro] ?: d.aggro, dodgeLevel = p[kDodgeLvl] ?: d.dodgeLevel,
            useSuper = p[kSuper] ?: d.useSuper, useGadget = p[kGadget] ?: d.useGadget,
            stopAtTrophies = p[kStop] ?: d.stopAtTrophies
        )
    }

    suspend fun save(c: Config) {
        ctx.ds.edit {
            it[kMode] = c.mode.name; it[kAim] = c.aim; it[kDodge] = c.dodge
            it[kAggro] = c.aggro; it[kDodgeLvl] = c.dodgeLevel
            it[kSuper] = c.useSuper; it[kGadget] = c.useGadget; it[kStop] = c.stopAtTrophies
        }
    }
}
