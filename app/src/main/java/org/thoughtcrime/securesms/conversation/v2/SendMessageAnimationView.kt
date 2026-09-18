package org.thoughtcrime.securesms.conversation.v2

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * أنيميشن إرسال الرسالة — أسلوب تيليجرام/واتساب.
 *
 * الرسم كله Canvas، بدون أي مكتبة خارجية.
 * الاستخدام:
 *   view.start(sendButton, recyclerTopY)
 */
class SendMessageAnimationView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

  // —————— الحالة ——————
  private var progress = 0f          // 0..1 : الرحلة الرئيسية
  private var sparkProgress = 0f     // 0..1 : تشتت الشرارات (sparkles)
  private var bubbleScale = 1f
  private var bubbleAlpha = 1f

  private var startX = 0f
  private var startY = 0f
  private var endX = 0f
  private var endY = 0f

  private val arcHeight = 90f        // مدى ارتفاع القوس
  private val bubbleColor = Color.parseColor("#3A76F0")   // لون Signal/Fallback
  private val sparkColor = Color.parseColor("#FFB74D")     // لون الشرارات

  // —————— الأدوات ——————
  private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = bubbleColor
    style = Paint.Style.FILL
  }
  private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = sparkColor
    style = Paint.Style.FILL
  }
  private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = bubbleColor
    style = Paint.Style.FILL
    alpha = 90
  }

  private val bubbleRect = RectF()
  private val tailPath = Path()

  // شرارات ثابتة حول الفقاعة (نسب ثابتة لتكون الحركة متسقة)
  private val sparks = listOf(
    Spark(angleDeg = -60f, distance = 30f, radius = 6f),
    Spark(angleDeg = -20f, distance = 38f, radius = 5f),
    Spark(angleDeg = 20f, distance = 34f, radius = 7f),
    Spark(angleDeg = 60f, distance = 28f, radius = 5f),
    Spark(angleDeg = -90f, distance = 45f, radius = 4f),
    Spark(angleDeg = 90f, distance = 40f, radius = 6f)
  )

  private var bubbleSize = 56f
  private var currentX = 0f
  private var currentY = 0f

  private var mainAnimator: ValueAnimator? = null

  /**
   * يبدأ الأنيميشن.
   * @param fromX إحداثي X لزر الإرسال (نسبة للـ parent)
   * @param fromY إحداثي Y لزر الإرسال
   * @param toX إحداثي X لهدف الرسالة الجديدة
   * @param toY إحداثي Y لهدف الرسالة الجديدة
   * @param onComplete يُستدعى عند الانتهاء
   */
  fun start(
    fromX: Float,
    fromY: Float,
    toX: Float,
    toY: Float,
    onComplete: (() -> Unit)? = null
  ) {
    cancel()

    startX = fromX
    startY = fromY
    endX = toX
    endY = toY
    progress = 0f
    sparkProgress = 0f
    bubbleAlpha = 1f
    bubbleScale = 1f

    visibility = VISIBLE
    invalidate()

    mainAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 480L
      interpolator = DecelerateInterpolator(1.6f)

      addUpdateListener { anim ->
        progress = anim.animatedValue as Float

        // الموضع في قوس (Bezier من الدرجة الثانية)
        currentX = startX + (endX - startX) * progress
        val linearY = startY + (endY - startY) * progress
        val arcLift = -arcHeight * (4f * progress * (1f - progress))  // أقصى ارتفاع في المنتصف
        currentY = linearY + arcLift

        // الشرارات تظهر في النصف الأول ثم تتلاشى
        sparkProgress = when {
          progress < 0.5f -> progress * 2f
          else -> (1f - progress) * 2f
        }

        // الفقاعة تصغر وتتلاشى في آخر 25%
        bubbleScale = 1f - 0.25f * progress
        bubbleAlpha = if (progress > 0.75f) ((1f - progress) / 0.25f) else 1f

        invalidate()
      }

      addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
          visibility = GONE
          progress = 0f
          sparkProgress = 0f
          onComplete?.invoke()
        }
      })

      start()
    }
  }

  fun cancel() {
    mainAnimator?.cancel()
    mainAnimator = null
    visibility = GONE
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (progress == 0f || progress >= 1f) return

    // —————— 1. الذيل (Tail) خلف الفقاعة ——————
    val tailAlpha = (100 * sparkProgress).toInt().coerceIn(0, 100)
    tailPaint.alpha = tailAlpha
    tailPath.reset()
    // ذيل مثلث صغير خلف الفقاعة
    val tailLen = 22f * bubbleScale
    tailPath.moveTo(currentX - tailLen, currentY)
    tailPath.lineTo(currentX - tailLen * 2.2f, currentY + tailLen * 0.8f)
    tailPath.lineTo(currentX - tailLen, currentY + tailLen * 0.6f)
    tailPath.close()
    canvas.drawPath(tailPath, tailPaint)

    // —————— 2. الفقاعة الأساسية ——————
    bubblePaint.alpha = (255 * bubbleAlpha).toInt().coerceIn(0, 255)

    val halfSize = (bubbleSize * bubbleScale) / 2f
    bubbleRect.set(
      currentX - halfSize,
      currentY - halfSize,
      currentX + halfSize,
      currentY + halfSize
    )

    // زوايا دائرية، مع زاوية حادة جهة اليمين (شكل فقاعة محادثة)
    val corner = halfSize * 0.55f
    val radii = floatArrayOf(
      corner, corner,   // top-left
      corner, corner,   // top-right
      corner * 0.4f, corner * 0.4f,   // bottom-right (أكثر حدة)
      corner, corner    // bottom-left
    )
    val bubblePath = Path().apply { addRoundRect(bubbleRect, radii, Path.Direction.CW) }
    canvas.drawPath(bubblePath, bubblePaint)

    // —————— 3. خطوط داخل الفقاعة (إيحاء نص) ——————
    if (bubbleAlpha > 0.3f) {
      val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = (200 * bubbleAlpha).toInt()
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
      }
      val lineY1 = currentY - halfSize * 0.25f
      val lineY2 = currentY + halfSize * 0.15f
      val lineStart = currentX - halfSize * 0.55f
      val lineEnd1 = currentX + halfSize * 0.35f
      val lineEnd2 = currentX + halfSize * 0.05f
      canvas.drawLine(lineStart, lineY1, lineEnd1, lineY1, linePaint)
      canvas.drawLine(lineStart, lineY2, lineEnd2, lineY2, linePaint)
    }

    // —————— 4. الشرارات (Sparkles) ——————
    if (sparkProgress > 0.01f) {
      sparkPaint.alpha = (255 * sparkProgress).toInt().coerceIn(0, 255)
      sparks.forEach { spark ->
        val rad = Math.toRadians(spark.angleDeg.toDouble())
        val dist = spark.distance * sparkProgress
        val sx = currentX + (cos(rad) * dist).toFloat()
        val sy = currentY + (sin(rad) * dist).toFloat()
        val r = spark.radius * (1f - sparkProgress * 0.4f)
        canvas.drawCircle(sx, sy, r, sparkPaint)
      }
    }
  }

  private data class Spark(
    val angleDeg: Float,
    val distance: Float,
    val radius: Float
  )
}
