package com.visionnav.platform.sos

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.visionnav.core.sensing.LocationFix
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [SosMessenger.sendSos]. */
sealed interface SosResult {
    /**
     * At least one recipient was handed to the SMS system without a reported failure.
     * @param sentTo recipients whose every message part the radio reported as sent.
     * @param unconfirmed recipients handed to the SMS system with no send report before the
     *   timeout (may still go out, e.g. once signal returns). Speak these honestly as "being sent".
     * @param failed recipients for which sending failed.
     */
    data class Sent(
        val sentTo: List<String>,
        val unconfirmed: List<String>,
        val failed: List<String>,
    ) : SosResult

    /** Sending failed for every recipient (e.g. no service). Offer [NeedsUserAction]/a call. */
    data class Failed(val failed: List<String>, val fallback: Intent) : SosResult

    /**
     * The app cannot send directly (no `SEND_SMS` permission or no SMS service): the UI should
     * launch [intent] (`ACTION_SENDTO smsto:`), which opens the user's SMS app pre-filled.
     */
    data class NeedsUserAction(val intent: Intent, val reason: Reason) : SosResult {
        enum class Reason { PERMISSION_MISSING, SMS_UNAVAILABLE }
    }

    /** No plausible phone number in the contact list. */
    data object NoValidContacts : SosResult
}

/**
 * Sends the SOS SMS (PLAN Phase 8).
 *
 * **Confirmation is the caller's responsibility.** [sendSos] sends immediately and
 * unconditionally; it must only be called after the user has explicitly confirmed (spoken
 * confirmation / deliberate gesture). This class never decides to send on its own.
 *
 * - With `SEND_SMS` granted: sends via [SmsManager] (multipart via `divideMessage`) to each
 *   recipient and waits up to [SEND_REPORT_TIMEOUT_MS] for the radio's per-part send reports.
 * - Without it (or with no SMS service): returns [SosResult.NeedsUserAction] with an
 *   `ACTION_SENDTO smsto:` intent for the UI to launch.
 * - Use [buildMessage] for the text: real coordinates only, or "location unavailable".
 */
@Singleton
class SosMessenger @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** See [SosMessageBuilder.buildMessage]. */
    fun buildMessage(fix: LocationFix?, fixAgeMs: Long? = null): String =
        SosMessageBuilder.buildMessage(fix, fixAgeMs)

    /** True if SMS can be sent directly without user interaction in another app. */
    fun canSendDirectly(): Boolean = hasSendPermission() && smsManager() != null

    /** Intent that opens the user's SMS app with [message] pre-filled for [contacts]. */
    fun composeIntent(contacts: List<String>, message: String): Intent {
        val numbers = SosMessageBuilder.normalizeNumbers(contacts)
        return Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";")))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Sends [message] to every valid number in [contacts]. **Only call after explicit user
     * confirmation.** Suspends until send reports arrive or [SEND_REPORT_TIMEOUT_MS] elapses.
     */
    suspend fun sendSos(contacts: List<String>, message: String): SosResult {
        val numbers = SosMessageBuilder.normalizeNumbers(contacts)
        if (numbers.isEmpty()) return SosResult.NoValidContacts
        if (!hasSendPermission()) {
            return SosResult.NeedsUserAction(
                composeIntent(numbers, message),
                SosResult.NeedsUserAction.Reason.PERMISSION_MISSING,
            )
        }
        val sms = smsManager() ?: return SosResult.NeedsUserAction(
            composeIntent(numbers, message),
            SosResult.NeedsUserAction.Reason.SMS_UNAVAILABLE,
        )
        return withContext(Dispatchers.Main) { sendDirect(sms, numbers, message) }
    }

    private suspend fun sendDirect(sms: SmsManager, numbers: List<String>, message: String): SosResult {
        val parts = try {
            sms.divideMessage(message)
        } catch (_: RuntimeException) {
            arrayListOf(message)
        }
        val tracker = SendTracker(numbers.size, parts.size)
        val action = "$ACTION_PREFIX${UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val recipient = intent.getIntExtra(EXTRA_RECIPIENT, -1)
                val part = intent.getIntExtra(EXTRA_PART, -1)
                tracker.report(recipient, part, ok = resultCode == Activity.RESULT_OK)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            numbers.forEachIndexed { index, number ->
                val sentIntents = ArrayList<PendingIntent>(parts.size)
                for (part in parts.indices) {
                    val intent = Intent(action)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_RECIPIENT, index)
                        .putExtra(EXTRA_PART, part)
                    sentIntents += PendingIntent.getBroadcast(
                        context,
                        index * MAX_PARTS + part,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                    )
                }
                try {
                    if (parts.size == 1) {
                        sms.sendTextMessage(number, null, parts[0], sentIntents[0], null)
                    } else {
                        sms.sendMultipartTextMessage(number, null, parts, sentIntents, null)
                    }
                } catch (_: RuntimeException) {
                    tracker.failRecipient(index)
                }
            }
            withTimeoutOrNull(SEND_REPORT_TIMEOUT_MS) { tracker.allResolved.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
        val sent = numbers.filterIndexed { i, _ -> tracker.status(i) == Status.SENT }
        val failed = numbers.filterIndexed { i, _ -> tracker.status(i) == Status.FAILED }
        val unconfirmed = numbers.filterIndexed { i, _ -> tracker.status(i) == Status.PENDING }
        return if (sent.isEmpty() && unconfirmed.isEmpty()) {
            SosResult.Failed(failed, composeIntent(numbers, message))
        } else {
            SosResult.Sent(sentTo = sent, unconfirmed = unconfirmed, failed = failed)
        }
    }

    private fun hasSendPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun smsManager(): SmsManager? = try {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    } catch (_: RuntimeException) {
        null
    }

    private enum class Status { PENDING, SENT, FAILED }

    /** Per-recipient aggregation of per-part send reports. Main-thread only. */
    private class SendTracker(private val recipients: Int, private val partsPerRecipient: Int) {
        private val okParts = IntArray(recipients)
        private val statuses = Array(recipients) { Status.PENDING }
        val allResolved = CompletableDeferred<Unit>()

        fun status(recipient: Int): Status = statuses[recipient]

        fun report(recipient: Int, part: Int, ok: Boolean) {
            if (recipient !in 0 until recipients || part !in 0 until partsPerRecipient) return
            if (statuses[recipient] != Status.PENDING) return
            if (!ok) {
                statuses[recipient] = Status.FAILED
            } else if (++okParts[recipient] == partsPerRecipient) {
                statuses[recipient] = Status.SENT
            }
            checkDone()
        }

        fun failRecipient(recipient: Int) {
            statuses[recipient] = Status.FAILED
            checkDone()
        }

        private fun checkDone() {
            if (statuses.none { it == Status.PENDING }) allResolved.complete(Unit)
        }
    }

    companion object {
        /** How long to wait for the radio's send reports before returning "unconfirmed". */
        const val SEND_REPORT_TIMEOUT_MS = 30_000L

        private const val MAX_PARTS = 100
        private const val ACTION_PREFIX = "com.visionnav.platform.action.SOS_SENT."
        private const val EXTRA_RECIPIENT = "recipient"
        private const val EXTRA_PART = "part"
    }
}
