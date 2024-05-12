package top.jie65535.jpicstitcher

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import android.content.ContentValues
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date


class MainActivity : AppCompatActivity() {

    private lateinit var rbVertical: RadioButton
    private lateinit var rbHorizontal: RadioButton
    private lateinit var btnStitch: Button

    private var stitchMode: Int = STITCH_MODE_VERTICAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        btnStitch = findViewById(R.id.btn_stitch)
        btnStitch.setOnClickListener {
            if (checkPermission()) {
                pickImagesFromGallery()
            }
        }

        rbVertical = findViewById(R.id.rb_vertical)
        rbHorizontal = findViewById(R.id.rb_horizontal)
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.any { !it }) {
                Toast.makeText(this, "无权限", Toast.LENGTH_SHORT).show()
            } else {
                pickImagesFromGallery()
            }
        }

    private fun checkPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return if (ContextCompat.checkSelfPermission(
                    this,
                    READ_MEDIA_IMAGES
                ) != PERMISSION_GRANTED
            ) {
                requestPermissions.launch(arrayOf(READ_MEDIA_IMAGES))
                false
            } else true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return if (ContextCompat.checkSelfPermission(
                    this,
                    READ_MEDIA_VISUAL_USER_SELECTED
                ) != PERMISSION_GRANTED
            ) {
                requestPermissions.launch(
                    arrayOf(
                        READ_MEDIA_IMAGES,
                        READ_MEDIA_VISUAL_USER_SELECTED
                    )
                )
                false
            } else true
        } else {
            return if (ContextCompat.checkSelfPermission(
                    this,
                    READ_EXTERNAL_STORAGE
                ) != PERMISSION_GRANTED
            ) {
                requestPermissions.launch(arrayOf(READ_EXTERNAL_STORAGE))
                false
            } else true
        }
    }

    private fun pickImagesFromGallery() {
        stitchMode = if (rbVertical.isChecked) STITCH_MODE_VERTICAL else STITCH_MODE_HORIZONTAL

        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isEmpty()) {
                Toast.makeText(this, "未选择图片", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            lifecycleScope.launch {
                try {
                    val combinedBitmap = withContext(Dispatchers.IO) {
                        val selectedBitmaps = uris.map {
                            val source = ImageDecoder.createSource(contentResolver, it)
                            ImageDecoder.decodeBitmap(source)
                                .copy(Bitmap.Config.ARGB_8888, false)
                        }
                        if (stitchMode == STITCH_MODE_VERTICAL) {
                            combineVerticalBitmaps(selectedBitmaps)
                        } else {
                            combineHorizontalBitmaps(selectedBitmaps)
                        }
                    }

                    saveImageToGallery(combinedBitmap)
                } catch (e: Throwable) {
                    Toast.makeText(this@MainActivity, "处理错误：$e", Toast.LENGTH_LONG).show()
                }
            }
        }

    private fun combineVerticalBitmaps(bitmaps: List<Bitmap>): Bitmap {
        var totalHeight = 0
        var maxWidth = 0
        for (bitmap in bitmaps) {
            totalHeight += bitmap.height
            if (bitmap.width > maxWidth) {
                maxWidth = bitmap.width
            }
        }
        val combinedBitmap = Bitmap.createBitmap(maxWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)
        var currentHeight = 0
        for (bitmap in bitmaps) {
            canvas.drawBitmap(bitmap, 0f, currentHeight.toFloat(), null)
            currentHeight += bitmap.height
        }
        return combinedBitmap
    }

    private fun combineHorizontalBitmaps(bitmaps: List<Bitmap>): Bitmap {
        var totalWidth = 0
        var maxHeight = 0
        for (bitmap in bitmaps) {
            totalWidth += bitmap.width
            if (bitmap.height > maxHeight) {
                maxHeight = bitmap.height
            }
        }
        val combinedBitmap = Bitmap.createBitmap(totalWidth, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)
        var currentWeight = 0
        for (bitmap in bitmaps) {
            canvas.drawBitmap(bitmap, currentWeight.toFloat(), 0f, null)
            currentWeight += bitmap.width
        }
        return combinedBitmap
    }

    private suspend fun saveImageToGallery(bitmap: Bitmap) {
        val resolver = contentResolver

        val imageCollection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val newImage = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${Date().time}.png")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val savedImageUri = resolver.insert(imageCollection, newImage)

        if (savedImageUri == null) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "处理中...", Toast.LENGTH_SHORT).show()
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(savedImageUri).use {
                if (it != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }

            newImage.clear()
            newImage.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(savedImageUri, newImage, null, null)
        }

        Toast.makeText(this, "已保存到图库", Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val STITCH_MODE_VERTICAL = 0
        private const val STITCH_MODE_HORIZONTAL = 1
    }
}