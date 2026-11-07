package com.example.audiovideotrimmer

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Developer contact details and the WhatsApp helpers. */
object Contact {
    const val INTERNATIONAL = "923411188190"

    enum class SendResult { WHATSAPP_CHAT, SHARE_SHEET, FAILED }

    /** Opens a WhatsApp chat with the developer (works with any number, no saved contact needed). */
    fun openChat(context: Context, message: String): Boolean = try {
        val uri = Uri.parse("https://wa.me/$INTERNATIONAL?text=${Uri.encode(message)}")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    } catch (e: Exception) {
        AppLog.w("Contact", "Could not open WhatsApp chat", e)
        false
    }

    /**
     * Sends the log report to the developer's WhatsApp chat. The user still has to press
     * WhatsApp's send button – Android does not allow any app to send a message silently.
     * If WhatsApp is missing, the normal share sheet opens instead.
     */
    fun sendReport(activity: Activity, report: File): SendResult {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", report)
        fun base() = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Audio Video Trimmer – bug report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("report", uri)
        }
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                val intent = base().setPackage(pkg).putExtra("jid", "$INTERNATIONAL@s.whatsapp.net")
                activity.startActivity(intent)
                return SendResult.WHATSAPP_CHAT
            } catch (_: Exception) {
            }
        }
        return try {
            activity.startActivity(Intent.createChooser(base(), "Send log report"))
            SendResult.SHARE_SHEET
        } catch (e: Exception) {
            AppLog.e("Contact", "Could not share the report", e)
            SendResult.FAILED
        }
    }
}
