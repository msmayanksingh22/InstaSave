package com.instadown.assist.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.*
import com.instadown.assist.R
import com.instadown.assist.util.ClipboardReader

object DialogManualDownload {

    fun show(context: Context, prefillUrl: String? = null) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_manual_download, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val etUrl = view.findViewById<EditText>(R.id.etInstagramUrl)
        val btnPaste = view.findViewById<Button>(R.id.btnPasteUrl)
        val btnDismiss = view.findViewById<ImageButton>(R.id.btnDismissDialog)
        val tvStatus = view.findViewById<TextView>(R.id.tvManualStatus)
        val btnLoad = view.findViewById<Button>(R.id.btnLoadMedia)

        btnDismiss.setOnClickListener { dialog.dismiss() }

        // Prefill if provided or check clipboard
        if (!prefillUrl.isNullOrBlank()) {
            etUrl.setText(prefillUrl)
            etUrl.setSelection(prefillUrl.length)
        } else {
            val clip = ClipboardReader.readCurrentClip(context)
            if (clip.isValidInstagramUrl) {
                etUrl.setText(clip.cleanUrl)
                etUrl.setSelection(clip.cleanUrl.length)
            }
        }

        btnPaste.setOnClickListener {
            val clip = ClipboardReader.readCurrentClip(context)
            if (clip.text.isNotBlank()) {
                etUrl.setText(clip.text)
                etUrl.setSelection(etUrl.text.length)
            } else {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        fun validateInput(): String? {
            val raw = etUrl.text.toString().trim()
            if (raw.isBlank()) return null
            val (isValid, cleanUrl) = ClipboardReader.validateInstagramUrl(raw)
            return if (isValid) cleanUrl else null
        }

        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val clean = validateInput()
                if (clean != null) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "Valid Instagram URL detected"
                    tvStatus.setTextColor(context.getColor(R.color.status_green))
                    btnLoad.isEnabled = true
                } else if (etUrl.text.isNotBlank()) {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "Please enter a valid Instagram post or reel link"
                    tvStatus.setTextColor(context.getColor(R.color.status_amber))
                    btnLoad.isEnabled = false
                } else {
                    tvStatus.visibility = View.GONE
                    btnLoad.isEnabled = false
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnLoad.setOnClickListener {
            val clean = validateInput()
            if (clean != null) {
                dialog.dismiss()
                if (context is MainActivity) {
                    context.executeManualDownload(clean)
                } else {
                    MediaSelectionActivity.start(context, clean)
                }
            } else {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Please enter a valid Instagram post or reel link"
                tvStatus.setTextColor(context.getColor(R.color.status_amber))
            }
        }

        // Initial validation check
        val initialValid = validateInput()
        if (initialValid != null) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Valid Instagram URL detected"
            tvStatus.setTextColor(context.getColor(R.color.status_green))
            btnLoad.isEnabled = true
        } else {
            btnLoad.isEnabled = false
        }

        dialog.show()
    }
}
