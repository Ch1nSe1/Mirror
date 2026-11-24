package memore.app.utils

import android.util.Log

object LogUtil {
    private const val TAG = "Memore"

    fun d(message: String) {
        Log.d(TAG, message)
    }

    fun e(where: String, action: String, error: Throwable) {
        Log.e(TAG, "[$where] $action -> ${error.javaClass.simpleName}: ${error.message}")
        Log.e(TAG, error.stackTraceToString())
    }

    fun e(where: String, action: String, message: String) {
        Log.e(TAG, "[$where] $action -> $message")
    }

    fun i(message: String) {
        Log.i(TAG, message)
    }
}
