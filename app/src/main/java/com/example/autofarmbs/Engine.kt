package com.example.autofarmbs

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.opencv.core.Point
import kotlin.math.hypot

/** Раскладка HUD в долях экрана — подгони под своё устройство. */
object Layout {
    val JOY = 0.13 to 0.74
    val ATK = 0.88 to 0.74
    val SUPER = 0.78 to 0.84
    val GADGET = 0.90 to 0.52
    const val R = 80.0  // радиус джойстика в пикселях эталонного кадра 1280 px
}

fun launchGame(ctx: Context) {
    val i = ctx.packageManager.getLaunchIntentForPackage("com.supercell.brawlstars")
    if (i == null) Bus.log("Brawl Stars не установлен") else ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Бой: движение, прицел, супер, гаджет. Всё эвристика — калибруй. */
class Combat(private val vision: Vision) {
    private var n = 0
    private var side = 1.0
    private var lastFlip = 0L

    fun tick(f: Frame, c: Config) {
        val g = GameInputService.inst ?: run { Bus.log("Accessibility не включён"); return }
        n++
        val W = f.w.toDouble(); val H = f.h.toDouble(); val k = f.sx.toDouble()
        val me = Point(W * 0.5, H * 0.55)                // камера центрируется на игроке
        val tgt = vision.enemies(f).minByOrNull { hypot(it.x - me.x, it.y - me.y) }
        val now = System.currentTimeMillis()
        if (now - lastFlip > 700) { side = -side; lastFlip = now }

        var mx: Double; var my: Double; var ux = 0.0; var uy = 0.0; var d = 1e9
        if (tgt != null) {
            val vx = tgt.x - me.x; val vy = tgt.y - me.y
            d = hypot(vx, vy).coerceAtLeast(1.0); ux = vx / d; uy = vy / d
            val range = W * (0.32 - 0.17 * c.aggro)          // агрессия = ближе к врагу (KillAura-подобно)
            val radial = when { d > range -> 1.0; d < range * 0.55 -> -1.0; else -> 0.0 }
            mx = ux * radial; my = uy * radial
            if (c.dodge) { mx += -uy * side * c.dodgeLevel * 1.5; my += ux * side * c.dodgeLevel * 1.5 }
        } else { mx = side * 0.3; my = -0.7 }                 // врага не видно — идём вперёд
        val len = hypot(mx, my); if (len > 1) { mx /= len; my /= len }

        val R = Layout.R * k
        fun px(p: Pair<Double, Double>) = (p.first * W * k) to (p.second * H * k)
        val t = ArrayList<Touch>()
        val (jx, jy) = px(Layout.JOY)
        if (len > 0.05) t += Touch(jx, jy, jx + mx * R, jy + my * R, 0, c.tickMs + 30)

        if (tgt != null && c.aim && d < W * 0.5 && n % 2 == 0) {
            val (ax, ay) = px(Layout.ATK); t += Touch(ax, ay, ax + ux * R, ay + uy * R, 0, 60)
        }
        if (tgt != null && c.useSuper && n % 3 == 0 && vision.find(f, "super_ready") != null) {
            val (sx, sy) = px(Layout.SUPER); t += Touch(sx, sy, sx + ux * R, sy + uy * R, 0, 70)
        }
        if (tgt != null && c.useGadget && d < W * 0.35 && n % 5 == 0 && vision.find(f, "gadget_ready") != null) {
            val (gx, gy) = px(Layout.GADGET); t += Touch(gx, gy)
        }
        g.dispatch(t)
    }
}

/** Конечный автомат по распознанному экрану: LOBBY → MATCH → RESULT → REWARD → LOBBY … */
class Orchestrator(
    private val ctx: Context, private val cap: ScreenCapture,
    private val vision: Vision, private val cfg: () -> Config
) {
    private val combat = Combat(vision)
    private var last: Screen? = null
    private var unknown = 0

    suspend fun run() {
        Bus.log("Старт. $DISCLAIMER")
        Bus.stats.value = Stats(startedAt = System.currentTimeMillis())
        while (currentCoroutineContext().isActive && Bus.running.value) {
            val bmp = cap.latest()
            if (bmp == null) { delay(300); continue }
            val f = vision.prepare(bmp)
            try { step(f, cfg()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Bus.log("Ошибка: ${e.message}"); delay(1000) }
            finally { f.release() }
        }
        Bus.log("Стоп")
    }

    private fun tap(p: Point?, f: Frame) {
        if (p != null) GameInputService.inst?.dispatch(listOf(Touch(p.x * f.sx, p.y * f.sx)))
    }

    private suspend fun step(f: Frame, c: Config) {
        val hit = vision.classify(f)
        val s = hit.screen
        val entered = s != last
        if (entered) Bus.log("Экран: ${s.title}")
        unknown = if (s == Screen.UNKNOWN) unknown + 1 else 0

        when (s) {
            Screen.MATCH -> {
                if (entered) Bus.stats.update { it.copy(matches = it.matches + 1) }
                combat.tick(f, c); delay(c.tickMs)
            }
            Screen.LOBBY -> {
                if (entered) readTrophies(f, c)
                if (c.mode == FarmMode.TROPHY_PUSH && Bus.running.value) { tap(hit.at, f); delay(1500) }
                else delay(2000)
            }
            Screen.RESULT -> {
                if (entered) {
                    if (vision.find(f, "victory") != null) {
                        Bus.stats.update { it.copy(wins = it.wins + 1) }; Bus.log("Победа")
                    } else Bus.log("Поражение/ничья — повторяем")
                }
                tap(hit.at, f); delay(1200)
            }
            Screen.REWARD, Screen.POPUP -> {
                if (entered) Bus.log("Забираю награду / закрываю окно")
                tap(hit.at, f); delay(800)
            }
            Screen.UNKNOWN -> {
                if (unknown % 20 == 3) Bus.log("Экран не распознан (попытка $unknown)")
                if (unknown >= 40 && unknown % 10 == 0) tap(Point(f.w * 0.5, f.h * 0.92), f) // «tap to continue»
                if (unknown >= 120) { Bus.log("Перезапуск игры"); launchGame(ctx); unknown = 0 }
                delay(500)
            }
        }
        last = s
    }

    private suspend fun readTrophies(f: Frame, c: Config) {
        val t = vision.readTrophies(f) ?: return
        Bus.stats.update { it.copy(startTrophies = it.startTrophies ?: t, trophies = t, history = it.history + t) }
        val d = Bus.stats.value.delta
        Bus.log("Кубки: $t (${if (d >= 0) "+" else ""}$d за сессию)")
        if (c.stopAtTrophies > 0 && t >= c.stopAtTrophies) {
            Bus.log("Цель по кубкам достигнута"); Bus.running.value = false
        }
    }
}
