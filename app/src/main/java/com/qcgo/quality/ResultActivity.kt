package com.qcgo.quality

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.qcgo.quality.databinding.ActivityResultBinding

/**
 * Result screen. Shows the overall verdict, clean/dirty counts and ratio, and a
 * per-item list with each item's ROI thumbnail and prediction.
 */
class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanAgainButton.setOnClickListener { finish() }

        val result = ResultStore.latest
        if (result == null || result.total == 0) {
            finish()
            return
        }
        render(result)
    }

    private fun render(r: FrameResult) {
        val cleanColor = ContextCompat.getColor(this, R.color.clean)
        val dirtyColor = ContextCompat.getColor(this, R.color.dirty)

        // Verdict headline.
        if (r.dirty == 0) {
            binding.verdictText.text = getString(R.string.verdict_all_clean)
            binding.verdictText.setTextColor(cleanColor)
        } else {
            binding.verdictText.text = getString(R.string.verdict_dirty_detected)
            binding.verdictText.setTextColor(dirtyColor)
        }
        binding.verdictSub.text = getString(R.string.scanned_fmt, r.total)

        // Stat tiles.
        binding.statTotal.text = r.total.toString()
        binding.statClean.text = r.clean.toString()
        binding.statDirty.text = r.dirty.toString()

        // Ratio bar (weights) + percentages.
        setBarWeight(binding.cleanBar, r.clean.toFloat())
        setBarWeight(binding.dirtyBar, r.dirty.toFloat())
        val cleanPct = Math.round(r.clean * 100f / r.total)
        binding.pctClean.text = getString(R.string.pct_clean_fmt, cleanPct)
        binding.pctDirty.text = getString(R.string.pct_dirty_fmt, 100 - cleanPct)

        // Per-item cards.
        val inflater = LayoutInflater.from(this)
        r.items.forEachIndexed { i, item ->
            val row = inflater.inflate(R.layout.item_result, binding.itemsContainer, false)
            row.findViewById<ImageView>(R.id.itemImage).setImageBitmap(item.thumb)
            row.findViewById<TextView>(R.id.itemTitle).text = getString(R.string.item_fmt, i + 1)
            row.findViewById<TextView>(R.id.itemConfidence).text =
                getString(R.string.confidence_fmt, (item.confidence * 100).toInt())

            val chip = row.findViewById<TextView>(R.id.itemChip)
            if (item.label == QualityClassifier.Label.CLEAN) {
                chip.text = getString(R.string.label_clean)
                chip.setBackgroundResource(R.drawable.bg_chip_clean)
                chip.setTextColor(cleanColor)
            } else {
                chip.text = getString(R.string.label_dirty)
                chip.setBackgroundResource(R.drawable.bg_chip_dirty)
                chip.setTextColor(dirtyColor)
            }
            binding.itemsContainer.addView(row)
        }
    }

    private fun setBarWeight(view: android.view.View, weight: Float) {
        val lp = view.layoutParams as LinearLayout.LayoutParams
        lp.weight = weight
        view.layoutParams = lp
    }
}
