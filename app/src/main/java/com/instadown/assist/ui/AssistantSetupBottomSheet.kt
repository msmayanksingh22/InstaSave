package com.instadown.assist.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.instadown.assist.R
import com.instadown.assist.service.FloatingTriggerService
import com.instadown.assist.service.InstaAccessibilityService

class AssistantSetupBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AssistantSetupBottomSheet"
        fun newInstance(): AssistantSetupBottomSheet = AssistantSetupBottomSheet()
    }

    private var onAssistantActivated: (() -> Unit)? = null

    fun setOnAssistantActivatedListener(listener: () -> Unit) {
        onAssistantActivated = listener
    }

    private lateinit var btnClose: ImageButton
    private lateinit var tvOverlayStatusBadge: TextView
    private lateinit var btnEnableOverlay: MaterialButton
    private lateinit var tvA11yStatusBadge: TextView
    private lateinit var btnEnableA11y: MaterialButton
    private lateinit var btnDone: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_assistant_setup, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnClose = view.findViewById(R.id.btnAssistantSheetClose)
        tvOverlayStatusBadge = view.findViewById(R.id.tvOverlayStatusBadge)
        btnEnableOverlay = view.findViewById(R.id.btnEnableOverlay)
        tvA11yStatusBadge = view.findViewById(R.id.tvA11yStatusBadge)
        btnEnableA11y = view.findViewById(R.id.btnEnableA11y)
        btnDone = view.findViewById(R.id.btnAssistantSheetDone)

        btnClose.setOnClickListener { dismiss() }

        btnEnableOverlay.setOnClickListener {
            val ctx = requireContext()
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            startActivity(intent)
        }

        btnEnableA11y.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnDone.setOnClickListener {
            val ctx = requireContext()
            if (Settings.canDrawOverlays(ctx)) {
                FloatingTriggerService.start(ctx)
                onAssistantActivated?.invoke()
            }
            dismiss()
        }

        updatePermissionStates()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setDimAmount(0.82f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        val ctx = context ?: return
        val canOverlay = Settings.canDrawOverlays(ctx)
        val canA11y = InstaAccessibilityService.isServiceEnabled(ctx)

        // Overlay status
        if (canOverlay) {
            tvOverlayStatusBadge.visibility = View.VISIBLE
            btnEnableOverlay.visibility = View.GONE
        } else {
            tvOverlayStatusBadge.visibility = View.GONE
            btnEnableOverlay.visibility = View.VISIBLE
        }

        // Accessibility status
        if (canA11y) {
            tvA11yStatusBadge.visibility = View.VISIBLE
            btnEnableA11y.visibility = View.GONE
        } else {
            tvA11yStatusBadge.visibility = View.GONE
            btnEnableA11y.visibility = View.VISIBLE
        }

        // Done button text
        if (FloatingTriggerService.isRunning) {
            btnDone.text = "Assistant is Running"
        } else if (canOverlay) {
            btnDone.text = "Start Floating Assistant"
        } else {
            btnDone.text = "Enable Overlay First"
        }
    }
}
