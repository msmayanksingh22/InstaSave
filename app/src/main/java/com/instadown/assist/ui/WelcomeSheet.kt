package com.instadown.assist.ui

import android.os.Bundle
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.instadown.assist.R

class WelcomeSheet : BottomSheetDialogFragment(R.layout.sheet_welcome) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireContext().getSharedPreferences("home_intro", 0).edit().putBoolean("welcomed", true).apply()
        view.findViewById<View>(R.id.startSaving).setOnClickListener { dismiss() }
    }
}
