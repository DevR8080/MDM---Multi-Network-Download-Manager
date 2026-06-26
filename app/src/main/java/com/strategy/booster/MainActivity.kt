package com.strategy.booster

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.URLUtil
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.play.core.install.model.AppUpdateType
import com.strategy.booster.comps.PlayUpdater
import com.strategy.booster.databinding.ActivityMainBinding
import com.strategy.booster.notif.DownloadNotifConst
import com.strategy.booster.vm.DownloadViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private val viewModel: DownloadViewModel by viewModels()
    private var addDownloadChosenDir: Uri? = null
    private lateinit var updater: PlayUpdater
    private val reqNotifPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {/* show a tip if denied */ }

    private val pickFolderForAdd =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                addDownloadChosenDir = uri
                pendingSaveToLabelUpdater?.invoke(uri)
            }
        }

    private var pendingSaveToLabelUpdater: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        updater = PlayUpdater(this, AppUpdateType.FLEXIBLE)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                reqNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val ch = NotificationChannel(
            DownloadNotifConst.CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Download progress and controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)

        if (intent?.getBooleanExtra("EXIT_APP", false) == true) {
            finishAndRemoveTask()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {

            val navHostFragment =
                supportFragmentManager.findFragmentById(R.id.mainNavHost) as NavHostFragment
            navController = navHostFragment.navController
            binding.bottomNav.setupWithNavController(navController)

            withContext(Dispatchers.Main) {

                binding.centerButton.setOnClickListener {
                    showAddDownloadDialog()
                }

                updater.checkAndPrompt()

            }

        }

    }

    override fun onSupportNavigateUp(): Boolean {
        return super.onSupportNavigateUp() || navController.navigateUp()
    }

    private fun showAddDownloadDialog() {
        val titleView = layoutInflater.inflate(R.layout.dialog_title, null)
        val contentView = layoutInflater.inflate(R.layout.dialog_add_download, null)

        val inputLayout = contentView.findViewById<TextInputLayout>(R.id.urlInputLayout)
        val input = contentView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.urlInput)
        val nameInput = contentView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.nameInput)
        val saveToLabel = contentView.findViewById<TextView>(R.id.saveToLabel)
        val btnChooseFolder = contentView.findViewById<Button>(R.id.btnChooseFolder)

        addDownloadChosenDir = null
        saveToLabel.text = getString(R.string.save_to_default)

        inputLayout.setEndIconOnClickListener {
            val cm = getSystemService<ClipboardManager>()
            val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!clip.isNullOrEmpty() && URLUtil.isValidUrl(clip)) {
                input.setText(clip)
                input.setSelection(clip.length)
            } else {
                Toast.makeText(this, R.string.clipboard_not_url, Toast.LENGTH_SHORT).show()
            }
        }

        btnChooseFolder.setOnClickListener {
            pendingSaveToLabelUpdater = { uri ->
                saveToLabel.text = "Save to: ${uri.lastPathSegment ?: uri.toString()}"
            }
            pickFolderForAdd.launch(null)
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.MyAlertDialog)
            .setCustomTitle(titleView)
            .setView(contentView)
            .create()

        dialog.setOnShowListener {
            val btnStart = contentView.findViewById<Button>(R.id.btnStart)
            val btnCancel = contentView.findViewById<Button>(R.id.btnCancel)

            btnStart.setOnClickListener {
                val url = input.text?.toString()?.trim().orEmpty()
                val nameOverride = nameInput.text?.toString()?.trim().orEmpty().ifBlank { null }
                if (url.isEmpty()) { dialog.dismiss(); return@setOnClickListener }

                viewModel.addDownloadWithOverrides(
                    url = url,
                    customDisplayName = sanitizeFileName(nameOverride, url),
                    preferredDirTreeUri = addDownloadChosenDir
                )
                dialog.dismiss()
            }
            btnCancel.setOnClickListener { dialog.dismiss() }
        }

        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
        dialog.show()
    }

    private fun sanitizeFileName(overrideOrNull: String?, url: String): String? {
        val forbidden = Regex("""[<>:"/\\|?*\u0000-\u001F]""")
        fun clean(s: String) = s.replace(forbidden, "_").take(120).trim().ifBlank { null }

        val fromUrl = url.substringAfterLast('/').substringBefore('?')
        val urlExt = fromUrl.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val cleanOverride = overrideOrNull?.let(::clean)

        if (cleanOverride == null) return null
        val hasExt = cleanOverride.contains('.')
        return if (hasExt || urlExt.isBlank()) cleanOverride else "$cleanOverride.$urlExt"
    }

    override fun onResume() {
        super.onResume()
        updater.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("EXIT_APP", false)) {
            finishAndRemoveTask()
        }
    }

}