package com.instadown.assist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.instadown.assist.R

class DownloadErrorBottomSheet : DialogFragment() {

    private var onRetryListener: (() -> Unit)? = null

    fun setOnRetryListener(listener: () -> Unit) {
        this.onRetryListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_download_error, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = arguments?.getString(ARG_TITLE) ?: "Could Not Resolve Media"
        val subtitle = arguments?.getString(ARG_SUBTITLE) ?: "Please verify your internet connection or check that the post is public."
        val diagnostic = arguments?.getString(ARG_DIAGNOSTIC) ?: "Network request failed"
        val postUrl = arguments?.getString(ARG_URL) ?: ""

        val tvTitle = view.findViewById<TextView>(R.id.tvErrorTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvErrorSubtitle)
        val tvDiagnostic = view.findViewById<TextView>(R.id.tvDiagnosticDetails)
        val btnRetry = view.findViewById<Button>(R.id.btnErrorRetry)
        val btnOpenInstagram = view.findViewById<Button>(R.id.btnErrorOpenInstagram)
        val btnCopyDebug = view.findViewById<Button>(R.id.btnErrorCopyDebug)
        val btnDismiss = view.findViewById<Button>(R.id.btnErrorDismiss)

        tvTitle.text = title
        tvSubtitle.text = subtitle
        tvDiagnostic.text = diagnostic

        btnRetry.setOnClickListener {
            dismiss()
            onRetryListener?.invoke()
        }

        btnOpenInstagram.setOnClickListener {
            dismiss()
            if (postUrl.isNotBlank()) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(postUrl)).apply {
                        setPackage("com.instagram.android")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(postUrl)))
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnCopyDebug.setOnClickListener {
            val clipManager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val debugText = "URL: $postUrl\nDiagnostic: $diagnostic"
            clipManager?.setPrimaryClip(ClipData.newPlainText("InstaSave Debug", debugText))
            Toast.makeText(requireContext(), "Debug details copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnDismiss.setOnClickListener {
            dismiss()
        }
    }

    companion object {
        const val TAG = "DownloadErrorBottomSheet"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_SUBTITLE = "arg_subtitle"
        private const val ARG_DIAGNOSTIC = "arg_diagnostic"
        private const val ARG_URL = "arg_url"

        fun newInstance(
            title: String,
            subtitle: String,
            diagnostic: String,
            url: String
        ): DownloadErrorBottomSheet {
            return DownloadErrorBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_SUBTITLE, subtitle)
                    putString(ARG_DIAGNOSTIC, diagnostic)
                    putString(ARG_URL, url)
                }
            }
        }
    }
}
