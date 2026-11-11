package com.dshm.bot.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.view.View
import androidx.core.graphics.PathParser
import com.dshm.bot.data.remote.BotCache
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.ui.components.BotShapeSpec
import com.dshm.bot.ui.components.BotShapeSpecs
import kotlin.math.min

internal data class PhoneBotLook(
    val id: String?,
    val name: String?,
    val inkId: String?,
    val avatarImage: String?,
    val avatarShape: String?,
    val glyph: AvatarGlyph,
)

internal object PhoneBotLooks {
    fun load(context: Context): PhoneBotLook? {
        val savedId = context.getSharedPreferences("dshm_settings", Context.MODE_PRIVATE)
            .getString("phone_control_bot_id", null)
        val wanted = PhoneControlSession.activeBotId ?: savedId
        val bots = runCatching { BotCache(context).load() }.getOrDefault(emptyList())
        val bot = bots.firstOrNull { it.id == wanted }
            ?: bots.firstOrNull { !it.isGroup && it.glyph == AvatarGlyph.BOT }
        return bot?.let {
            PhoneBotLook(
                id = it.id,
                name = it.name,
                inkId = it.inkId ?: BotInks.defaultColorId(it.id),
                avatarImage = it.avatarImage,
                avatarShape = it.avatarShape,
                glyph = it.glyph,
            )
        } ?: wanted?.let {
            PhoneBotLook(
                id = it,
                name = null,
                inkId = BotInks.defaultColorId(it),
                avatarImage = null,
                avatarShape = null,
                glyph = AvatarGlyph.BOT,
            )
        }
    }
}

/** 悬浮条上的 Bot 头像：照片 / DS Bot 角色形状 / 彩色圆底图标，与主列表同一份数据。 */
internal class PhoneBotFace(
    context: Context,
    private val sizePx: Int,
    look: PhoneBotLook?,
) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
        color = 0x33FFFFFF
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private var bitmap: Bitmap? = null
    private var look: PhoneBotLook? = look

    init {
        setLook(look)
    }

    fun setLook(value: PhoneBotLook?) {
        look = value
        bitmap = decode(value?.avatarImage)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - stroke.strokeWidth / 2f
        val circle = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(circle)
        val item = look
        val ink = BotInks.color(item?.inkId, dark = true).toInt()
        val image = bitmap
        val shape = item?.avatarShape?.takeIf { BotShapeSpecs.all.containsKey(it) }
            ?: if (item?.glyph == AvatarGlyph.BOT) "whale" else null
        when {
            image != null -> {
                fill.color = ink
                canvas.drawCircle(cx, cy, radius, fill)
                drawCenterCrop(canvas, image, cx, cy, radius)
            }

            shape != null -> {
                fill.color = 0xFFF7F7F8.toInt()
                canvas.drawCircle(cx, cy, radius, fill)
                drawCharacter(canvas, BotShapeSpecs.all.getValue(shape), ink)
            }

            else -> {
                fill.color = ink
                canvas.drawCircle(cx, cy, radius, fill)
                drawGlyph(canvas, cx, cy, radius)
            }
        }
        canvas.restore()
        canvas.drawCircle(cx, cy, radius, stroke)
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, cx: Float, cy: Float, radius: Float) {
        val side = min(bitmap.width, bitmap.height)
        val src = Rect(
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            (bitmap.width + side) / 2,
            (bitmap.height + side) / 2,
        )
        val dst = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawBitmap(bitmap, src, dst, imagePaint)
    }

    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val icon = resources.getDrawable(com.dshm.bot.R.drawable.ic_lucide_bot, context.theme)
        val size = (radius * 1.05f).toInt()
        icon.setBounds(
            (cx - size / 2f).toInt(),
            (cy - size / 2f).toInt(),
            (cx + size / 2f).toInt(),
            (cy + size / 2f).toInt(),
        )
        icon.setTint(Color.WHITE)
        icon.draw(canvas)
    }

    private fun drawCharacter(canvas: Canvas, spec: BotShapeSpec, ink: Int) {
        val pad = sizePx * 0.12f
        val scale = (sizePx - pad * 2f) / 100f
        canvas.save()
        canvas.translate(pad, pad)
        canvas.scale(scale, scale)
        fill.style = Paint.Style.FILL
        fill.color = ink
        fill.alpha = 255
        canvas.drawPath(path(spec.body), fill)
        spec.paints.forEach { paint ->
            fill.color = parseColor(paint.color)
            fill.alpha = (255 * paint.alpha).toInt().coerceIn(0, 255)
            canvas.drawPath(path(paint.path), fill)
        }
        spec.shade?.let {
            fill.color = Color.BLACK
            fill.alpha = 36
            canvas.drawPath(path(it), fill)
        }
        spec.light?.let {
            fill.color = Color.WHITE
            fill.alpha = 42
            canvas.drawPath(path(it), fill)
        }
        fill.color = Color.BLACK
        fill.alpha = 255
        spec.eyes.forEach { eye ->
            if (eye.size < 5 || eye[2] < 5f) return@forEach
            canvas.save()
            canvas.rotate(eye[4], eye[0], eye[1])
            canvas.drawRoundRect(
                eye[0] - eye[2] / 2f,
                eye[1] - eye[3] / 2f,
                eye[0] + eye[2] / 2f,
                eye[1] + eye[3] / 2f,
                eye[2] / 2f,
                eye[2] / 2f,
                fill,
            )
            canvas.restore()
        }
        canvas.restore()
    }

    private fun path(data: String): Path =
        runCatching { PathParser.createPathFromPathData(data) }.getOrElse { Path() }

    private fun parseColor(value: String): Int =
        runCatching { Color.parseColor(value) }.getOrDefault(Color.BLACK)

    private fun decode(data: String?): Bitmap? {
        if (data.isNullOrBlank()) return null
        return runCatching {
            val raw = if (data.startsWith("data:")) {
                data.substringAfter(',', "")
            } else {
                data
            }
            if (raw.isEmpty()) return@runCatching null
            val bytes = if (data.startsWith("data:")) {
                Base64.decode(raw, Base64.DEFAULT)
            } else {
                java.io.File(data).readBytes()
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
