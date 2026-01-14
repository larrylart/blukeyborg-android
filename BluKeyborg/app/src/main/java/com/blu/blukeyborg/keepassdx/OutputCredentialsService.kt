package com.blu.blukeyborg.keepassdx

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import org.keepassdx.output.IOutputCredentialsService
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.blu.blukeyborg.BleHub
import com.blu.blukeyborg.PreferencesUtil

private const val TAG = "BK-OutputService"

// TODO: if you want to pin KeePassDX's signing cert, put its SHA-256 hex here.
// Leave empty to skip fingerprint check and only enforce package name.
private const val EXPECTED_KEEPASSDX_CERT_SHA256 = ""

////////////////////////////////////////////////////////////////////
// AIDL provider for KeePassDX -> BluKeyborg credential output.
//
// KeePassDX binds to this using the IOutputCredentialsService AIDL interface.
////////////////////////////////////////////////////////////////////
class OutputCredentialsService : Service() {

    companion object {
        // Return codes (keep stable so KeePassDX can interpret them)
        private const val RC_OK = 0
        private const val RC_UNKNOWN_MODE = -1
        private const val RC_EXCEPTION = -2
        private const val RC_DISABLED = -3
        private const val RC_NO_DEVICE = -4
        private const val RC_CONNECT_FAIL = -5
        private const val RC_SEND_FAIL = -6
        private const val RC_TIMEOUT = -7
        private const val RC_EMPTY = -8

        // Optional behavior: disconnect after send 
        // If you prefer to keep the session alive for repeated fills, set false.
        private const val DISCONNECT_AFTER_SEND = false // was true now it is false - we rely on idle (5m disconnect) - not to have to reconnect every time
        private const val DISCONNECT_SUPPRESS_MS = 4000L
    }

    // Single Stub instance returned from onBind()
    private val binder = object : IOutputCredentialsService.Stub() {

        override fun getProviderName(): String {
            enforceKeePassDxCaller()
            return "BluKeyborg"
        }

		override fun sendPayload(
			requestId: String?,
			mode: String?,
			username: String?,
			password: String?,
			otp: String?,
			entryTitle: String?,
			entryUuid: String?
		): Int {
			enforceKeePassDxCaller()

			// IMPORTANT: don’t send anything if user hasn’t enabled output device / selected a dongle.
			if (!PreferencesUtil.useExternalKeyboardDevice(this@OutputCredentialsService)) {
				Log.w(TAG, "sendPayload: output device disabled in prefs")
				return RC_DISABLED
			}
			if (!BleHub.hasSelectedDevice()) {
				Log.w(TAG, "sendPayload: no selected device")
				return RC_NO_DEVICE
			}

			val safeMode = mode ?: "unknown"
			val user = username.orEmpty()
			val pass = password.orEmpty()
			val otpVal = otp.orEmpty()

			Log.d(
				TAG,
				"sendPayload: id=$requestId mode=$safeMode user='${user.take(4)}...' title=$entryTitle uuid=$entryUuid"
			)

			// Build exactly ONE payload string (tab/newline included) and send it once.
			val payload = when (safeMode) {
				"user" -> user
				"pass" -> pass
				"user_tab_pass_enter" -> buildString {
					append(user)
					append('\t')
					append(pass)
					append('\n')
				}
				"user_enter_pass_enter" -> buildString {
					append(user)
					append('\n')
					append(pass)
					append('\n')
				}
				else -> {
					Log.w(TAG, "sendPayload: unknown mode '$safeMode'")
					return RC_UNKNOWN_MODE
				}
			}.let { base ->
				if (otpVal.isNotEmpty()) base + otpVal else base
			}

			if (payload.isEmpty()) {
				Log.w(TAG, "sendPayload: empty payload for mode=$safeMode")
				return RC_EMPTY
			}

			// Do the BLE work synchronously (AIDL method returns Int).
			return sendViaBleHubBlocking(payload)
		}

    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind from action=${intent?.action}")
        return binder
    }

	private val ALLOWED_CALLER_PREFIXES = listOf(
		"com.kunzisoft.keepass"  // covers free/pro/flavors
	)

    ////////////////////////////////////////////////////////////////
    // Optionally enforce that only KeePassDX can call into this service.
    // This avoids other apps abusing the credential output channel.
    ////////////////////////////////////////////////////////////////
    private fun enforceKeePassDxCaller() {
        try {
			val pm = packageManager
			val callingUid = Binder.getCallingUid()
			val callingPkgs = pm.getPackagesForUid(callingUid) ?: emptyArray()

			val kpdxPkg = callingPkgs.firstOrNull { pkg ->
				ALLOWED_CALLER_PREFIXES.any { prefix -> pkg.startsWith(prefix) }
			} ?: run {
				Log.w(TAG, "Rejecting caller uid=$callingUid pkgs=${callingPkgs.toList()}")
				throw SecurityException("Caller is not KeePassDX")
			}

            // Optional certificate pinning; guarded so it won't crash if info is missing.
			if (EXPECTED_KEEPASSDX_CERT_SHA256.isNotEmpty()) {

				val certSha256 = getSigningCertSha256(pm, kpdxPkg)
					?: run {
						Log.w(TAG, "No signing cert SHA-256 for $kpdxPkg")
						throw SecurityException("No signatures for KeePassDX")
					}

				if (!certSha256.equals(EXPECTED_KEEPASSDX_CERT_SHA256, ignoreCase = true)) {
					Log.w(
						TAG,
						"Cert mismatch for $kpdxPkg expected=$EXPECTED_KEEPASSDX_CERT_SHA256 actual=$certSha256"
					)
					throw SecurityException("KeePassDX certificate mismatch")
				}
			}

        } catch (e: Exception) {
            if (e is SecurityException) throw e
            Log.w(TAG, "Caller verification failed", e)
            throw SecurityException("Caller verification failed")
        }
    }

	private fun getSigningCertSha256(pm: PackageManager, packageName: String): String? {
		return try {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
				val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
				val si = pi.signingInfo ?: return null

				val signer = if (si.hasMultipleSigners()) {
					si.apkContentsSigners.firstOrNull()
				} else {
					si.signingCertificateHistory.firstOrNull()
				} ?: return null

				val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
				digest.joinToString("") { b -> "%02x".format(b) }
			} else {
				@Suppress("DEPRECATION")
				val pi = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)

				@Suppress("DEPRECATION")
				val sig = pi.signatures?.firstOrNull() ?: return null

				val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
				digest.joinToString("") { b -> "%02x".format(b) }
			}
		} catch (_: Throwable) {
			null
		}
	}
	
	private fun sendViaBleHubBlocking(payload: String): Int {
		val rc = AtomicInteger(RC_EXCEPTION)
		val latch = CountDownLatch(1)

		try {
			// If already secure/connected, just send.
			val alreadySecure = (BleHub.connected.value == true)

			if (alreadySecure) {
				BleHub.sendStringAwaitHash(payload, timeoutMs = 8000L) { ok, err ->
					rc.set(if (ok) RC_OK else RC_SEND_FAIL)
					if (!ok) Log.w(TAG, "sendStringAwaitHash failed: ${err ?: "?"}")

					if (DISCONNECT_AFTER_SEND) {
						try {
							// Same “don’t auto-reconnect immediately” idea you use elsewhere
							BleHub.disconnect(suppressMs = DISCONNECT_SUPPRESS_MS)
						} catch (t: Throwable) {
							Log.w(TAG, "disconnect after send threw", t)
						}
					}

					latch.countDown()
				}
			} else {
				// auto-pick any reachable provisioned dongle (fast), then send
				BleHub.autoConnectForServices(
					onReady = { ok: Boolean, err: String? ->
						if (!ok) {
							rc.set(RC_CONNECT_FAIL)
							Log.w(TAG, "autoConnectForServices failed: ${err ?: "?"}")

							if (DISCONNECT_AFTER_SEND) {
								try {
									BleHub.disconnect(suppressMs = DISCONNECT_SUPPRESS_MS)
								} catch (t: Throwable) {
									Log.w(TAG, "disconnect after connect-fail threw", t)
								}
							}

							latch.countDown()
							return@autoConnectForServices
						}

						BleHub.sendStringAwaitHash(payload, timeoutMs = 8000L) { ok2: Boolean, err2: String? ->
							rc.set(if (ok2) RC_OK else RC_SEND_FAIL)
							if (!ok2) Log.w(TAG, "sendStringAwaitHash failed: ${err2 ?: "?"}")

							if (DISCONNECT_AFTER_SEND) {
								try {
									BleHub.disconnect(suppressMs = DISCONNECT_SUPPRESS_MS)
								} catch (t: Throwable) {
									Log.w(TAG, "disconnect after send threw", t)
								}
							}

							latch.countDown()
						}
					}
				)

			}

			// Block the AIDL thread briefly waiting for callbacks.
			val completed = latch.await(10, TimeUnit.SECONDS)
			return if (completed) rc.get() else RC_TIMEOUT
		} catch (t: Throwable) {
			Log.e(TAG, "sendViaBleHubBlocking exception", t)
			return RC_EXCEPTION
		}
	}
	
}

