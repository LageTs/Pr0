package com.pr0gramm.app.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.FragmentManager
import com.pr0gramm.app.Logger
import com.pr0gramm.app.ui.dialog
import com.pr0gramm.app.util.ErrorFormatting
import com.pr0gramm.app.util.bundle
import com.pr0gramm.app.util.weakref
import java.util.concurrent.CancellationException

/**
 * This dialog fragment shows and error to the user.
 */
class ErrorDialogFragment : androidx.fragment.app.DialogFragment() {
    interface OnErrorDialogHandler {
        fun showErrorDialog(error: Throwable, formatter: ErrorFormatting.Formatter)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return dialog(this) {
            content(arguments?.getString("content") ?: "no content :(")
            positive()
        }
    }

    companion object {
        private val logger = Logger("ErrorDialogFragment")

        private var previousError by weakref<Throwable>(null)

        var GlobalErrorDialogHandler by weakref<OnErrorDialogHandler>(null)

        private fun processError(error: Throwable, handler: OnErrorDialogHandler?) {
            logger.error("An error occurred", error)

            if (error is CancellationException) {
                logger.warn { "Ignoring cancellation exception." }
                return
            }

            try {
                previousError = error

                // format and log
                val formatter = ErrorFormatting.getFormatter(error)

                handler?.showErrorDialog(error, formatter)

            } catch (thr: Throwable) {
            }
        }


        fun showErrorString(fragmentManager: FragmentManager?, message: String) {
            logger.info { message }

            if (fragmentManager == null)
                return

            try {
                // remove previous dialog, if any
                dismissErrorDialog(fragmentManager)

                val dialog = ErrorDialogFragment()
                dialog.arguments = bundle { putString("content", message) }
                dialog.show(fragmentManager, "ErrorDialog")

            } catch (error: Exception) {
                logger.error("Could not show error dialog", error)
            }
        }

        /**
         * Dismisses any previously shown error dialog.
         */
        private fun dismissErrorDialog(fm: FragmentManager) {
            try {
                val previousFragment = fm.findFragmentByTag("ErrorDialog")
                (previousFragment as? androidx.fragment.app.DialogFragment)?.dismissAllowingStateLoss()

            } catch (error: Throwable) {
                logger.warn("Error removing previous dialog", error)
            }
        }

        fun handleOnError(err: Throwable) {
            processError(err, GlobalErrorDialogHandler)
        }
    }
}
