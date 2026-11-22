package com.sahl.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SahlCore.catchCrashes(this)
        val sp = getSharedPreferences("sahl_gallery_prefs", MODE_PRIVATE)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val bg = if (dark) Color.parseColor("#111315") else Color.parseColor("#F5F6F5")
        val cardBg = if (dark) Color.parseColor("#1C2023") else Color.WHITE
        val textPrimary = if (dark) Color.WHITE else Color.parseColor("#14171A")
        val textSecondary = if (dark) Color.parseColor("#9AA3AB") else Color.parseColor("#5C666F")
        val accent = Color.parseColor("#0E6B4E")
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
            text = getString(R.string.settings_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
        })
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }

        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = 16 * dp
            }
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (14 * dp).toInt() }
        }

        fun divider() = View(this).apply {
            setBackgroundColor(if (dark) Color.parseColor("#2B3035") else Color.parseColor("#EAECEF"))
            layoutParams = LinearLayout.LayoutParams(-1, (1 * dp).toInt())
        }

        val card1 = card()
        val colRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
        }
        val colTitle = TextView(this).apply {
            text = getString(R.string.setting_columns)
            textSize = 15.5f
            setTextColor(textPrimary)
        }
        val colVal = TextView(this).apply {
            text = sp.getInt("grid_cols", 3).toString()
            textSize = 13f
            setTextColor(accent)
        }
        colRow.addView(colTitle)
        colRow.addView(colVal)
        colRow.setOnClickListener {
            val opts = arrayOf("2", "3", "4", "5")
            AlertDialog.Builder(this)
                .setTitle(R.string.setting_columns)
                .setItems(opts) { _, which ->
                    val cols = which + 2
                    sp.edit().putInt("grid_cols", cols).apply()
                    colVal.text = cols.toString()
                }.show()
        }
        card1.addView(colRow)
        card1.addView(divider())

        val hiddenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        hiddenRow.addView(TextView(this).apply {
            text = getString(R.string.setting_show_hidden)
            textSize = 15.5f
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        hiddenRow.addView(Switch(this).apply {
            isChecked = sp.getBoolean("show_hidden", false)
            setOnCheckedChangeListener { _, checked -> sp.edit().putBoolean("show_hidden", checked).apply() }
        })
        card1.addView(hiddenRow)
        card1.addView(divider())

        val vaultRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            setOnClickListener { startActivity(Intent(this@SettingsActivity, VaultActivity::class.java)) }
        }
        vaultRow.addView(TextView(this).apply {
            text = getString(R.string.vault_title)
            textSize = 15.5f
            setTextColor(textPrimary)
        })
        vaultRow.addView(TextView(this).apply {
            text = getString(R.string.vault_unlock)
            textSize = 13f
            setTextColor(accent)
        })
        card1.addView(vaultRow)
        content.addView(card1)

        val card2 = card()
        val updateRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
        }
        updateRow.addView(TextView(this).apply {
            text = Updater.checkText(this@SettingsActivity)
            textSize = 15.5f
            setTextColor(textPrimary)
        })
        val updateSub = TextView(this).apply {
            text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
            textSize = 13f
            setTextColor(textSecondary)
        }
        updateRow.addView(updateSub)
        updateRow.setOnClickListener {
            updateSub.text = Updater.checkingText(this)
            Thread {
                val rel = Updater.fetchNewer()
                runOnUiThread {
                    if (rel != null) {
                        updateSub.text = Updater.availableText(this, rel)
                        AlertDialog.Builder(this)
                            .setTitle(Updater.availableText(this, rel))
                            .setMessage(rel.notes.ifBlank { Updater.updateNowText(this) })
                            .setPositiveButton(Updater.updateNowText(this)) { _, _ -> Updater.downloadAndInstall(this, rel) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        updateSub.text = Updater.upToDateText(this)
                        Toast.makeText(this, Updater.upToDateText(this), Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        card2.addView(updateRow)
        content.addView(card2)

        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
