package com.sahl.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class VaultActivity : Activity() {
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private var unlocked = false
    private val files = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.parseColor("#111315") else Color.parseColor("#F5F6F5")
        val textPrimary = if (dark) Color.WHITE else Color.parseColor("#14171A")
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(textPrimary)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.vault_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        root.addView(header)

        emptyText = TextView(this).apply {
            text = getString(R.string.vault_empty)
            textSize = 15f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            visibility = View.GONE
        }
        root.addView(emptyText)

        recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@VaultActivity, 3)
            visibility = View.GONE
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        authenticate()
    }

    private fun authenticate() {
        val bm = getSystemService(BiometricManager::class.java)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (bm.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true
            loadVault()
            return
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.vault_title))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                unlocked = true
                loadVault()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                finish()
            }
        })
    }

    private fun loadVault() {
        if (!unlocked) return
        files.clear()
        files.addAll(MediaRepo.listVaultFiles(this))
        if (files.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.adapter = VaultAdapter()
        }
    }

    inner class VaultAdapter : RecyclerView.Adapter<VaultAdapter.VH>() {
        inner class VH(val img: ImageView) : RecyclerView.ViewHolder(img)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val size = resources.displayMetrics.widthPixels / 3
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(2, 2, 2, 2)
            }
            return VH(iv)
        }

        override fun getItemCount(): Int = files.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            holder.img.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, opts))
            holder.img.setOnClickListener {
                val preview = ImageView(this@VaultActivity).apply {
                    setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                    adjustViewBounds = true
                }
                AlertDialog.Builder(this@VaultActivity)
                    .setView(preview)
                    .setPositiveButton("Restore") { _, _ ->
                        MediaRepo.restoreFromVault(this@VaultActivity, f)
                        Toast.makeText(this@VaultActivity, R.string.vault_restored, Toast.LENGTH_SHORT).show()
                        loadVault()
                    }
                    .setNegativeButton("Delete") { _, _ ->
                        f.delete()
                        loadVault()
                    }
                    .setNeutralButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
