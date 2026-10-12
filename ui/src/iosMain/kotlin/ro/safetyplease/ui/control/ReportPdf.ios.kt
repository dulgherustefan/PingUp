package ro.safetyplease.ui.control

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import okio.Path
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsBeginPDFContextToFile
import platform.UIKit.UIGraphicsBeginPDFPage
import platform.UIKit.UIGraphicsEndPDFContext
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes

@OptIn(ExperimentalForeignApi::class)
actual fun writePdf(path: Path, draw: (PdfCanvas) -> Unit) {
    UIGraphicsBeginPDFContextToFile(path.toString(), CGRectMake(0.0, 0.0, PdfCanvas.PAGE_W.toDouble(), PdfCanvas.PAGE_H.toDouble()), null)
    try {
        draw(IosCanvas())
    } finally {
        UIGraphicsEndPDFContext()
    }
}

/** UIKit's PDF pages, in the system font, which has the Romanian letters too. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosCanvas : PdfCanvas {
    private val fonts = PdfFont.entries.associateWith {
        if (it.bold) UIFont.boldSystemFontOfSize(it.size.toDouble()) else UIFont.systemFontOfSize(it.size.toDouble())
    }
    private val gray = UIColor(red = 0x6E / 255.0, green = 0x6E / 255.0, blue = 0x73 / 255.0, alpha = 1.0)
    private val lightGray = UIColor(red = 0xD1 / 255.0, green = 0xD1 / 255.0, blue = 0xD6 / 255.0, alpha = 1.0)

    private fun attributes(font: PdfFont): Map<Any?, *> =
        mapOf(NSFontAttributeName to fonts.getValue(font), NSForegroundColorAttributeName to if (font.gray) gray else UIColor.blackColor)

    override fun lineHeight(font: PdfFont) = fonts.getValue(font).lineHeight.toFloat()

    // UIKit counts the descender down from the baseline as negative
    override fun descent(font: PdfFont) = -fonts.getValue(font).descender.toFloat()

    override fun width(text: String, font: PdfFont) = NSString.create(string = text).sizeWithAttributes(attributes(font)).useContents { width }.toFloat()

    override fun text(text: String, x: Float, baseline: Float, font: PdfFont) {
        // UIKit draws a line from its top, the font's ascender above the baseline
        val top = baseline.toDouble() - fonts.getValue(font).ascender
        NSString.create(string = text).drawAtPoint(CGPointMake(x.toDouble(), top), withAttributes = attributes(font))
    }

    override fun rule(fromX: Float, toX: Float, y: Float) {
        val line = UIBezierPath()
        line.moveToPoint(CGPointMake(fromX.toDouble(), y.toDouble()))
        line.addLineToPoint(CGPointMake(toX.toDouble(), y.toDouble()))
        line.lineWidth = 0.6
        lightGray.setStroke()
        line.stroke()
    }

    override fun startPage() = UIGraphicsBeginPDFPage()

    // the next page, or the end of the file, closes this one
    override fun finishPage() = Unit
}
