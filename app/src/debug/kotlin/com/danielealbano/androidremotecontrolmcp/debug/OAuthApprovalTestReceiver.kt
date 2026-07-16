package com.danielealbano.androidremotecontrolmcp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthApprovalCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-ONLY [BroadcastReceiver] that approves a pending OAuth authorization for E2E tests, so the test
 * can drive the number-match approval without a human.
 *
 * This class lives in the `debug` source set, so it does NOT exist in the release APK at all. It is also
 * guarded by [BuildConfig.DEBUG] (belt-and-suspenders). It MUST NEVER be reachable in release — approving
 * an authorization is an auth-bypass.
 *
 * **Usage** (from an E2E test via adb):
 * ```
 * # Approve a specific pending request
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.OAUTH_APPROVE \
 *   -n com.danielealbano.androidremotecontrolmcp.debug/.OAuthApprovalTestReceiver \
 *   --es approval_id "<id>"
 *
 * # Or approve all currently-pending requests (omit approval_id)
 * adb shell am broadcast \
 *   -a com.danielealbano.androidremotecontrolmcp.debug.OAUTH_APPROVE \
 *   -n com.danielealbano.androidremotecontrolmcp.debug/.OAuthApprovalTestReceiver
 * ```
 */
@AndroidEntryPoint
class OAuthApprovalTestReceiver : BroadcastReceiver() {
    @Inject
    lateinit var approvalCoordinator: OAuthApprovalCoordinator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_APPROVE) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }
        val id = intent.getStringExtra(EXTRA_APPROVAL_ID)
        scope.launch {
            val nowMs = System.currentTimeMillis()
            if (!id.isNullOrEmpty()) {
                approvalCoordinator.approve(id, nowMs)
                Log.i(TAG, "Approved pending request by id")
            } else {
                approvalCoordinator.observePending().value.forEach { approvalCoordinator.approve(it.id, nowMs) }
                Log.i(TAG, "Approved all pending requests")
            }
        }
    }

    companion object {
        private const val TAG = "E2E:OAuthApproval"
        const val ACTION_APPROVE = "com.yedhant.androidremotecontrolmcp.debug.OAUTH_APPROVE"
        const val EXTRA_APPROVAL_ID = "approval_id"
    }
}
