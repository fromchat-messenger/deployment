package ru.fromchat.ui.profile

import android.widget.Toast
import com.pr0gramm3r101.utils.UtilsLibrary

actual fun showProfileLoadErrorMessage(message: String) {
    Toast.makeText(UtilsLibrary.context, message, Toast.LENGTH_SHORT).show()
}