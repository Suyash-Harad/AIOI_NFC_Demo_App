package com.bartech.demonfc_app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bartech.demonfc_app.databinding.ActivityMainMenuBinding
import com.bartech.demonfc_app.databinding.LoadingDialogBinding
import com.google.android.material.snackbar.Snackbar

class MainMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainMenuBinding
    private var loadingBinding: LoadingDialogBinding? = null
    private var loadingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainMenuBinding.inflate(layoutInflater)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        window.statusBarColor = getColor(R.color.background_primary)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        binding.nfcTagReadWriteLayout.setOnClickListener {
            val intent = Intent(this, NFCTagDataReadWriteActivity::class.java)
            startActivity(intent)
        }

        binding.nfcTagImageUpdateLayout.setOnClickListener {
            val intent = Intent(this, NFCTagImageUpdateActivity::class.java)
            startActivity(intent)
        }

    }

    private fun showLoading(message: String = "Please wait...") {

        if (loadingDialog == null) {
            loadingBinding = LoadingDialogBinding.inflate(layoutInflater)
            loadingDialog = Dialog(this).apply {
                setCancelable(false)
                setContentView(loadingBinding!!.root)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }

        loadingBinding?.tvLoadingMessage?.text = message

        if (loadingDialog?.isShowing != true) {
            loadingDialog?.show()
        }
    }

    private fun hideLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun showSuccessResult(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.success))
            .setTextColor(Color.WHITE)
            .show()
    }

    private fun showWarningResult(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.warning))
            .setTextColor(Color.WHITE)
            .show()
    }

    private fun showErrorResult(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(ContextCompat.getColor(this, R.color.error))
            .setTextColor(Color.WHITE)
            .show()
    }

}